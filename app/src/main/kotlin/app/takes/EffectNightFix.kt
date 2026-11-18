@file:OptIn(UnstableApi::class)

package app.takes

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.core.NightFixMode
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectNightFusion
import app.takes.core.VideoEffectSettings
import app.takes.core.resolvingEffects
import java.util.concurrent.Executor
import kotlin.math.pow

/**
 * Night fix on [clip]'s raw frames in an export, before their color and framing, as the original restores them
 * (`VideoToolCompositor.swift:324`). In Fast mode the single-frame passes of `VideoEffectNightFix.fast`: adjust, the
 * chroma cleanup across then down, and the mix by intensity. In Medium and Precise each frame is first fused with its
 * neighbours, one or four each side, aligned by their motion ([NightFusion]), and the passes take the fused picture
 * (`VideoEffectNightFix.restore`).
 *
 * The original fuses the frames of the recording ahead of the export and keeps them in the project's cache
 * (YouRec/VideoEffects/NightFix.swift:57-154); here they are fused as the export draws them: the program keeps the
 * frames of the piece as they come and gives each on once the frames after it in its window have come, so its
 * neighbours are the frames of the piece around it, not of the whole recording. [begins] is where the clip starts on
 * its sequence.
 */
class NightFixEffect(private val clip: TimelineClip, private val begins: Double) : GlEffect {
    /** The mode the clip's frames are fused in, null for none (NightFix.swift:65-70). */
    private val fusion: NightFixMode? = VideoEffectNightFusion.mode(clip.videoEffects, clip.start, clip.end)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = clip.videoEffects.orEmpty().none { it.kind == VideoEffectID.NightFix && it.enabled }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = fusion?.let { Temporal(it) } ?: Program()

    private fun sourceTime(presentationTimeUs: Long): Double = clip.sourceTime((presentationTimeUs / 1e6 - begins).coerceIn(0.0, clip.duration))

    /** The effects in force at [sourceTime]: a Night fix only inside its windows, as the original resolves each frame (YouRec/VideoToolCompositor.swift:204-205). */
    private fun effectsAt(sourceTime: Double): List<VideoEffectSettings>? = clip.resolvingEffects(sourceTime).videoEffects

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var size = Size(0, 0)
        private val gpu = EffectGpu()

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            size = Size(inputWidth, inputHeight)
            return size
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            val raw = EffectSource(inputTexId, size.width, size.height, encoded = false)
            val time = sourceTime(presentationTimeUs)
            NightFixPasses.draw(gpu, raw, null, bound[0], effectsAt(time), time)
        }

        override fun release() {
            super.release()
            gpu.release()
        }
    }

    /** A picture the program gives on: its texture and framebuffer, and whether the frame drawn in it is still held downstream. */
    private class Output(width: Int, height: Int) {
        val texture = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents = */ false)
        val framebuffer = GlUtil.createFboForTexture(texture)
        val info = GlTextureInfo(texture, framebuffer, /* rboId = */ C.INDEX_UNSET, width, height)
        var held = false

        fun release() {
            GlUtil.deleteFbo(framebuffer)
            GlUtil.deleteTexture(texture)
        }
    }

    /** A frame to give on: the frame the fusion keeps (the same again for a frame held), its stamp and its source second. */
    private class Pending(val frame: NightFusion.Frame, val timeUs: Long, val sourceTime: Double)

    /**
     * Medium and Precise in [mode]: frames kept, fused once the window after them has come, given on in order. A
     * frame of another mode, or none, is drawn as Fast from the frame kept (NightFix.swift:346). The window starts
     * again where the footage jumps (back in time or by more than a quarter second) and at the end of the piece.
     */
    private inner class Temporal(private val mode: NightFixMode) : GlShaderProgram {
        private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
        private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
        private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
        private var errorExecutor: Executor = Executor { it.run() }

        private val gpu = EffectGpu()
        private val fusion = NightFusion(mode, gpu)
        private val radius = mode.radius
        private val frames = ArrayList<NightFusion.Frame>()
        private val pending = ArrayDeque<Pending>()
        private val outputs = ArrayList<Output>()
        private var order = 0L
        private var segment = 0
        private var granted = false
        private var ending = false

        /** The most frames kept: a window either side and the frame waiting. */
        private val capacity = 2 * radius + 3

        override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
            this.inputListener = inputListener
            granted = false
            grant()
        }

        override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
            this.outputListener = outputListener
        }

        override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
            errorExecutor = executor
            this.errorListener = errorListener
        }

        private fun grant() {
            if (granted || frames.size >= capacity) return
            granted = true
            inputListener.onReadyToAcceptInputFrame()
        }

        private fun fail(e: Exception) {
            errorExecutor.execute { errorListener.onError(VideoFrameProcessingException.from(e)) }
        }

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            granted = false
            try {
                val time = sourceTime(presentationTimeUs)
                val last = frames.lastOrNull()?.takeIf { it.segment == segment }
                val jumps = last != null && (inputTexture.width != last.width || inputTexture.height != last.height || time < last.time || time - last.time > LONGEST_STEP)
                if (jumps) segment++
                val frame = if (last != null && !jumps && time == last.time) last
                else fusion.add(inputTexture.texId, inputTexture.width, inputTexture.height, order++, segment, time).also { frames += it }
                pending.addLast(Pending(frame, presentationTimeUs, time))
                inputListener.onInputFrameProcessed(inputTexture)
                drain()
                grant()
            } catch (e: Exception) {
                fail(e)
            }
        }

        /** Whether [p]'s window has come: the frames after it to the window's end, a jump after it, or the piece's end. */
        private fun ready(p: Pending): Boolean {
            if (ending) return true
            val newest = frames.last()
            return newest.segment > p.frame.segment || newest.order >= p.frame.order + radius
        }

        private fun drain() {
            while (pending.isNotEmpty() && ready(pending.first())) {
                val first = pending.first()
                val output = outputs.firstOrNull { !it.held && it.info.width == first.frame.width && it.info.height == first.frame.height }
                    ?: if (outputs.count { it.held } < OUTPUTS) Output(first.frame.width, first.frame.height).also { outputs += it } else return
                outputs.filter { !it.held && it !== output && (it.info.width != first.frame.width || it.info.height != first.frame.height) }.forEach { it.release(); outputs.remove(it) }
                draw(first, output)
                output.held = true
                pending.removeFirst()
                prune()
                outputListener.onOutputFrameAvailable(output.info, first.timeUs)
            }
            if (ending && pending.isEmpty()) {
                ending = false
                frames.forEach(fusion::forget)
                frames.clear()
                segment++
                outputListener.onCurrentOutputStreamEnded()
            }
        }

        /** Lets go the frames no frame still to come can take in its window. */
        private fun prune() {
            val newest = frames.lastOrNull() ?: return
            val first = pending.firstOrNull()?.frame ?: newest
            val keep = { f: NightFusion.Frame -> f.segment > first.segment || f.segment == first.segment && f.order >= minOf(first.order, newest.order) - radius }
            val gone = frames.filterNot(keep)
            gone.forEach(fusion::forget)
            frames.removeAll(gone.toSet())
        }

        private fun draw(p: Pending, output: Output) {
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            val effects = effectsAt(p.sourceTime)
            val effect = NightFixPasses.active(effects, p.sourceTime)
            val fused = if (effect != null && effect.nightFixMode == mode && NightFixPasses.works(effect, p.sourceTime) && fusion.available) {
                val n = p.frame
                fusion.fuse(n, frames.filter { it !== n && it.segment == n.segment && kotlin.math.abs(it.order - n.order) <= radius }.sortedBy { it.order })
            } else {
                null
            }
            NightFixPasses.draw(gpu, p.frame.source, fused, output.framebuffer, effects, p.sourceTime)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
        }

        override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
            outputs.firstOrNull { it.texture == outputTexture.texId }?.held = false
            try {
                drain()
                grant()
            } catch (e: Exception) {
                fail(e)
            }
        }

        override fun signalEndOfCurrentInputStream() {
            ending = true
            try {
                drain()
            } catch (e: Exception) {
                fail(e)
            }
        }

        override fun flush() {
            pending.clear()
            frames.forEach(fusion::forget)
            frames.clear()
            segment++
            ending = false
            for (output in outputs) output.held = false
            inputListener.onFlush()
            granted = false
            grant()
        }

        override fun release() {
            frames.forEach(fusion::forget)
            frames.clear()
            pending.clear()
            outputs.forEach(Output::release)
            outputs.clear()
            fusion.release()
            gpu.release()
        }
    }

    private companion object {
        /** Frames given on that downstream may hold at once. */
        const val OUTPUTS = 2

        /** A step between two frames longer than this starts the window again. */
        const val LONGEST_STEP = 0.25
    }
}

/** The numbers of Night fix's passes, as `VideoEffectNightFix` works them out, and the passes. */
object NightFixPasses {
    /** The Night fix drawn at a source second: the first enabled one whose range holds it. */
    fun active(effects: List<VideoEffectSettings>?, sourceTime: Double): VideoEffectSettings? =
        effects.orEmpty().firstOrNull { it.kind == VideoEffectID.NightFix && it.enabled && sourceTime >= it.start && sourceTime < it.end }

    /** Whether [effect] changes the frame at [sourceTime]: some intensity, and some reduction or brightening (NightFix.swift:208). */
    fun works(effect: VideoEffectSettings, sourceTime: Double): Boolean {
        val mix = effect.number("intensity", sourceTime)
        val amount = effect.number("reduction", sourceTime)
        val stops = effect.number("brightness", sourceTime)
        return !(mix <= 0 || amount <= 0 && stops == 0.0)
    }

    /** `uFix` of the adjust pass: the reach of its taps, its mixing, the noise it expects and the gain. VideoEffectNightFix.adjust. */
    fun adjust(reduction: Double, brightness: Double, detail: Double): FloatArray {
        val reach = 0.85 + maxOf(0.0, reduction - 1) * 1.3
        val noise2 = (6.0 / 255).pow(2) / 3 * reduction * reduction * (1.5 + (0.2 - 1.5) * detail)
        return floatArrayOf(reach.toFloat(), minOf(1.0, reduction).toFloat(), noise2.toFloat(), 2.0.pow(brightness).toFloat())
    }

    /** `uChroma` of the chroma passes: the radius, the blend and the reduction. VideoEffectNightFix.chroma. */
    fun chroma(reduction: Double): FloatArray =
        floatArrayOf((10.0 * reduction).coerceIn(1.0, 30.0).toInt().toFloat(), minOf(.98, .85 * reduction).toFloat(), reduction.toFloat())

    /**
     * Night fix of the [raw] frame at [sourceTime] into [framebuffer]: adjust, with the [fused] picture where the frame
     * was fused, the chroma cleanup across then down, and the mix by intensity; the frame as it is where nothing
     * works on it (VideoEffectNightFix.restore).
     */
    internal fun draw(gpu: EffectGpu, raw: EffectSource, fused: EffectTarget?, framebuffer: Int, effects: List<VideoEffectSettings>?, sourceTime: Double) {
        val width = raw.width
        val height = raw.height
        val effect = active(effects, sourceTime)
        val mix = effect?.number("intensity", sourceTime) ?: 0.0
        val amount = effect?.number("reduction", sourceTime) ?: 0.0
        val stops = effect?.number("brightness", sourceTime) ?: 0.0
        if (effect == null || !works(effect, sourceTime)) {
            // The frame as it is.
            return gpu.drawInto(EffectPassShaders.NIGHT_MIX, raw, framebuffer, width, height, { p ->
                p.setSamplerTexIdUniform("uRestored", raw.texture, 1)
                p.setFloatsUniform("uMix", floatArrayOf(0f))
            })
        }
        val fix = adjust(amount, stops, effect.number("detail", sourceTime))
        val adjusted = if (fused == null) {
            gpu.draw(EffectPassShaders.NIGHT_ADJUST, raw, "adjusted", width, height, { p -> p.setFloatsUniform("uFix", fix) })
        } else {
            gpu.draw(NightFusionShaders.ADJUST, raw, "adjusted", width, height, { p ->
                p.setFloatsUniform("uFix", fix)
                p.setSamplerTexIdUniform("uFused", fused.texture, 1)
            })
        }
        fun chroma(from: EffectTarget, key: String, horizontal: Boolean) = gpu.draw(EffectPassShaders.NIGHT_CHROMA, EffectSource(from), key, width, height, { p ->
            p.setFloatsUniform("uDir", if (horizontal) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f))
            p.setFloatsUniform("uChroma", chroma(amount))
        })
        val restored = chroma(chroma(adjusted, "across", true), "down", false)
        gpu.drawInto(EffectPassShaders.NIGHT_MIX, raw, framebuffer, width, height, { p ->
            p.setSamplerTexIdUniform("uRestored", restored.texture, 1)
            p.setFloatsUniform("uMix", floatArrayOf(mix.toFloat()))
        })
    }
}
