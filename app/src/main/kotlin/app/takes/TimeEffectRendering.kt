@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package app.takes

import android.content.Context
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.common.util.Size
import androidx.media3.common.SpeedParameters
import app.takes.core.EffectPlaybackSpan
import app.takes.core.RenderPlan
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSamplePlan
import app.takes.core.VideoEffectSequenceClock
import app.takes.core.VideoEffectTiming
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.floor

/*
 * The export's time effects (docs/PIP-AND-EFFECTS-PLAN.md, 2.2 "History frames
 * or a retimed clock"), around a picture's video effect chain:
 *
 *  - the footage is played at the rates of the picture's stretches
 *    ([spanSpeed]), so a retimed clip's frames come at the seconds the playback
 *    map gives them;
 *  - a held frame, of a Freeze Frame or of a cadence (Undercranking, Super 8),
 *    is kept as it came in and drawn again for every later frame of the hold
 *    ([TimeHoldEffect]), before the chain, which then draws it as it looks at
 *    the second it holds ([VideoEffectTiming.shown]), as the original's
 *    compositor reads the held source frame and processes it
 *    (YouRec/VideoToolCompositor.swift:326-336, :365-371);
 *  - Step Printing's blocks of frames are blended after the chain, each frame
 *    processed at its own second, and every frame of a block shows the blend
 *    ([StepPrintingEffect], YouRec/VideoToolCompositor.swift:371-399): the
 *    frames of a block are kept until its last one has come, and then given
 *    on with their own times.
 *
 * A held frame of a Freeze Frame plays footage from the second it holds, at
 * speed 1 where the recording has enough of it, and only its first frame is
 * drawn: Media3 gives a piece frames only as its footage has them.
 */

/** The speed a piece of footage from source second [from] plays at, following [spans]; null at speed 1 throughout. */
fun spanSpeed(spans: List<EffectPlaybackSpan>, from: Double): SpeedParameters? {
    if (spans.size <= 1) return spans.firstOrNull()?.let { steadySpeed(it.rate) }
    if (spans.all { it.rate == 1.0 }) return null
    val starts = spans.map { Math.round((it.start - from) * 1e6) }
    return SpeedParameters(
        object : SpeedProvider {
            private fun index(timeUs: Long): Int {
                var found = 0
                for (i in starts.indices) if (starts[i] <= timeUs) found = i
                return found
            }

            override fun getSpeed(timeUs: Long) = spans[index(timeUs)].rate.toFloat()

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                val at = index(timeUs)
                for (i in at + 1 until spans.size) if (spans[i].rate != spans[at].rate) return starts[i]
                return C.TIME_UNSET
            }
        },
        /* shouldMaintainPitch = */ true,
    )
}

/** The length of a recording in seconds, read once per file. */
object SourceLengths {
    private val known = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun of(file: File): Double? = known[file.path] ?: MediaMetadataRetriever().let { reader ->
        try {
            reader.setDataSource(file.path)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }?.also { known[file.path] = it }
        } catch (_: RuntimeException) {
            null
        } finally {
            reader.release()
        }
    }
}

/**
 * How one part of a picture is played by an export: the footage it is cut to,
 * in microseconds, and the speed it goes at. A part of a held frame plays the
 * footage from the held second on for as long as the part, slower where the
 * recording ends sooner; any other part plays its cut at the picture's rates.
 */
data class TimedPiece(val startUs: Long, val endUs: Long, val speed: SpeedParameters?) {
    companion object {
        fun of(clip: TimelineClip, spans: List<EffectPlaybackSpan>, part: RenderPlan.Part.Footage, file: File?): TimedPiece {
            val hold = clip.frozenFrame
            if (hold == null) return TimedPiece(part.sourceStartUs, part.sourceEndUs, spanSpeed(spans, part.sourceStartUs / 1e6))
            val wanted = maxOf(0.001, part.end - part.start)
            val available = (file?.let(SourceLengths::of) ?: (hold.sourceTime + wanted)) - hold.sourceTime - 0.001
            val footage = minOf(wanted, maxOf(1.0 / 60, available))
            val start = RenderPlan.cutMicros(hold.sourceTime)
            return TimedPiece(start, start + maxOf(1L, Math.round(footage * 1e6)), steadySpeed(footage / wanted))
        }
    }
}

/**
 * The seconds into [clip] of a frame stamped [presentationTimeUs] on a
 * sequence where the clip begins at [begins]. A stamp is cut to the
 * microsecond, so it stands for the moment a microsecond later: the frame at
 * a third of a second, stamped 333,333 µs, is on the cadence's boundary
 * there, not a hair before it.
 */
fun frameOffset(presentationTimeUs: Long, begins: Double, clip: TimelineClip): Double =
    ((presentationTimeUs + 1) / 1e6 - begins).coerceIn(0.0, clip.duration)

/**
 * [frameOffset] at the frame of the movie nearest to it, on a movie of
 * [frameRate] frames a second: a cadence holds by the movie's clock. Footage
 * at another rate than the movie's (a clip sped up) gives frames between the
 * movie's, and the movie shows each at the frame nearest to it, so that is
 * where its hold is decided; the original decides it at the movie's frame
 * (YouRec/VideoToolCompositor.swift:173). [sequenceStart] is where the
 * sequence begins on the movie's grid, in seconds of the sequence.
 */
fun movieFrameOffset(presentationTimeUs: Long, begins: Double, clip: TimelineClip, frameRate: Double): Double {
    val time = (presentationTimeUs + 1) / 1e6
    return (Math.round(time * frameRate) / frameRate - begins).coerceIn(0.0, clip.duration)
}

/** Whether a clip's picture needs [TimeHoldEffect]: a held frame, or a cadence that holds frames. */
fun holdsFrames(clip: TimelineClip): Boolean =
    clip.frozenFrame != null || clip.videoEffects.orEmpty().any { it.enabled && (it.kind == VideoEffectID.Undercranking || it.kind == VideoEffectID.SuperEight) }

/** Whether a clip's picture needs [StepPrintingEffect]. */
fun printsSteps(clip: TimelineClip): Boolean = clip.frozenFrame == null && clip.videoEffects.orEmpty().any { it.enabled && it.kind == VideoEffectID.StepPrinting }

/** The shaders the time effects draw with. Texture rows run from the bottom. */
internal object TimeShaders {
    val VERTEX = """
        attribute vec4 aFramePosition;
        varying vec2 vTex;
        void main() { gl_Position = aFramePosition; vTex = aFramePosition.xy * 0.5 + 0.5; }
    """

    val COPY = """
        precision mediump float;
        uniform sampler2D uTex;
        varying vec2 vTex;
        void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
    """

    /** Up to [FRAMES] frames, sRGB-encoded, mixed in linear light by their weights, as Core Image dissolves them (Compositor:383-389). */
    val BLEND = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        ${(0 until FRAMES).joinToString("\n") { "uniform sampler2D uF$it;" }}
        uniform float uW[$FRAMES];
        varying vec2 vTex;
        vec3 above(vec3 c, float edge) { return step(vec3(edge), c) * (1.0 - step(c, vec3(edge))); }
        vec3 lin(vec3 c) { return mix(c / 12.92, pow(max((c + 0.055) / 1.055, 0.0), vec3(2.4)), above(c, 0.04045)); }
        vec3 srgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(max(c, 0.0), vec3(1.0 / 2.4)) - 0.055, above(c, 0.0031308)); }
        void main() {
            vec3 sum = vec3(0.0);
            ${(0 until FRAMES).joinToString("\n            ") { "sum += lin(clamp(texture2D(uF$it, vTex).rgb, 0.0, 1.0)) * uW[$it];" }}
            gl_FragColor = vec4(clamp(srgb(max(sum, 0.0)), 0.0, 1.0), 1.0);
        }
    """

    /** The most frames a block of Step Printing holds (its Step frames, 2–6). */
    const val FRAMES = 6

    fun program(fragment: String): GlProgram = GlProgram(VERTEX, fragment).also {
        it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    fun nearest(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    }

    fun linear(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /** Draws [texture] into the framebuffer bound, pixel for pixel. */
    fun copy(program: GlProgram, texture: Int) {
        program.use()
        program.setSamplerTexIdUniform("uTex", texture, 0)
        program.bindAttributesAndUniforms()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        nearest(texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        linear(texture)
        GlUtil.checkGlError()
    }
}

/**
 * Keeps the frame a hold shows and draws it for every frame of the hold: a
 * Freeze Frame's held frame, the first frame of its piece, or the frame a
 * cadence holds ([VideoEffectTiming.heldFrame]), the first that comes once
 * the cadence moves on. Other frames go through as they are. [begins] is
 * where the clip starts on its sequence, as for [VideoEffectChain].
 */
class TimeHoldEffect(private val clip: TimelineClip, private val begins: Double, private val frameRate: Double) : GlEffect {
    private val clock = VideoEffectSequenceClock.of(clip)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = !holdsFrames(clip)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
        private var program: GlProgram? = null
        private var size = Size(0, 0)
        private var held = 0
        private var heldFramebuffer = 0
        private var heldKey: Long? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != size.width || inputHeight != size.height) releaseHeld()
            size = Size(inputWidth, inputHeight)
            return size
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val copy = program ?: TimeShaders.program(TimeShaders.COPY).also { program = it }
            val offset = movieFrameOffset(presentationTimeUs, begins, clip, frameRate)
            val key = VideoEffectTiming.heldFrame(clip, offset, clock)
            if (key == null) {
                heldKey = null
                TimeShaders.copy(copy, inputTexId)
                return
            }
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            if (key != heldKey || heldFramebuffer == 0) {
                if (heldFramebuffer == 0) {
                    held = GlUtil.createTexture(size.width, size.height, /* useHighPrecisionColorComponents = */ false)
                    heldFramebuffer = GlUtil.createFboForTexture(held)
                }
                GlUtil.focusFramebufferUsingCurrentContext(heldFramebuffer, size.width, size.height)
                TimeShaders.copy(copy, inputTexId)
                heldKey = key
            }
            GlUtil.focusFramebufferUsingCurrentContext(bound[0], size.width, size.height)
            TimeShaders.copy(copy, held)
        }

        override fun flush() {
            heldKey = null
            super.flush()
        }

        private fun releaseHeld() {
            if (heldFramebuffer != 0) {
                GlUtil.deleteFbo(heldFramebuffer)
                GlUtil.deleteTexture(held)
            }
            heldFramebuffer = 0
            held = 0
            heldKey = null
        }

        override fun release() {
            super.release()
            releaseHeld()
            program?.delete()
            program = null
        }
    }
}

/**
 * Step Printing's blend ([VideoEffectSamplePlan]): every frame of a block
 * shows the block's frames mixed by their weights, each frame as the chain
 * drew it. The frames of a block are kept as they come; when the block is
 * complete (a frame of the next one comes, or the piece ends) the blend is
 * drawn once and given on for each of them, at its own time. A block cut by
 * the clip's edge repeats its nearest frame, as the core's block does
 * ([app.takes.core.VideoEffectFrameIndex.block]). [sourceFPS] is the
 * footage's frames a second.
 */
class StepPrintingEffect(private val clip: TimelineClip, private val begins: Double, private val sourceFPS: Double) : GlEffect {
    private val clock = VideoEffectSequenceClock.of(clip)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = !printsSteps(clip)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    /** A picture of the program's own: its texture and framebuffer. */
    private class Picture(width: Int, height: Int) {
        val texture = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents = */ false)
        val framebuffer = GlUtil.createFboForTexture(texture)
        val info = GlTextureInfo(texture, framebuffer, /* rboId = */ C.INDEX_UNSET, width, height)

        /** How many of the frames given on with it are still held downstream. */
        var given = 0

        fun release() {
            GlUtil.deleteFbo(framebuffer)
            GlUtil.deleteTexture(texture)
        }
    }

    /** What a frame of the piece is: the block it belongs to (its first second), its place in it, and the block's weights. */
    private class Place(val block: Double, val slot: Int, val weights: List<Double>)

    private inner class Program : GlShaderProgram {
        private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
        private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
        private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }
        private var errorExecutor: Executor = Executor { it.run() }

        private var copy: GlProgram? = null
        private var blend: GlProgram? = null
        private var width = 0
        private var height = 0
        // The frames of the block being gathered, by arrival, with their times and places.
        private val frames = ArrayList<Picture>()
        private val times = ArrayList<Long>()
        private val places = ArrayList<Place?>()
        private var gathered = 0
        private val outputs = ArrayList<Picture>()
        private var ending = false

        override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
            this.inputListener = inputListener
            inputListener.onReadyToAcceptInputFrame()
        }

        override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
            this.outputListener = outputListener
        }

        override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
            errorExecutor = executor
            this.errorListener = errorListener
        }

        private fun place(presentationTimeUs: Long): Place? {
            val offset = frameOffset(presentationTimeUs, begins, clip)
            val sourceTime = clip.sourceTime(offset)
            val plan = VideoEffectSamplePlan.make(clip.videoEffects.orEmpty(), sourceTime, sourceFPS, clock) ?: return null
            if (plan.times.size <= 1) return null
            val slot = floor((sourceTime - plan.times[0]) * sourceFPS + 0.000001).toInt().coerceIn(0, plan.times.size - 1)
            return Place(plan.times[0], slot, plan.weights)
        }

        private fun free(): Picture? = outputs.firstOrNull { it.given == 0 && it.width() == width }

        private fun Picture.width(): Int = info.width

        override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
            try {
                if (inputTexture.width != width || inputTexture.height != height) {
                    give()
                    releasePictures()
                    width = inputTexture.width
                    height = inputTexture.height
                }
                val place = place(presentationTimeUs)
                // A frame of another block, or one no block holds, completes the block gathered so far.
                if (gathered > 0 && (place == null || places[0] == null || place.block != places[0]!!.block)) give()
                if (frames.size <= gathered) frames += Picture(width, height)
                val target = frames[gathered]
                val bound = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
                GlUtil.focusFramebufferUsingCurrentContext(target.framebuffer, width, height)
                TimeShaders.copy(copy ?: TimeShaders.program(TimeShaders.COPY).also { copy = it }, inputTexture.texId)
                GlUtil.focusFramebufferUsingCurrentContext(bound[0], width, height)
                if (times.size <= gathered) { times += presentationTimeUs; places += place } else { times[gathered] = presentationTimeUs; places[gathered] = place }
                gathered++
                inputListener.onInputFrameProcessed(inputTexture)
                // A frame that belongs to no block goes on by itself; a block goes on once it has all its frames.
                if (place == null || gathered >= place.weights.size || gathered >= TimeShaders.FRAMES) give()
                if (free() != null || outputs.size < OUTPUTS) inputListener.onReadyToAcceptInputFrame()
            } catch (e: Exception) {
                errorExecutor.execute { errorListener.onError(androidx.media3.common.VideoFrameProcessingException.from(e)) }
            }
        }

        /** Draws the block gathered and gives it on, once for each of its frames. */
        private fun give() {
            if (gathered == 0) return
            val output = free() ?: Picture(width, height).also { outputs += it }
            val bound = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
            GlUtil.focusFramebufferUsingCurrentContext(output.framebuffer, width, height)
            val first = places[0]
            if (first == null) {
                TimeShaders.copy(copy ?: TimeShaders.program(TimeShaders.COPY).also { copy = it }, frames[0].texture)
            } else {
                val program = blend ?: TimeShaders.program(TimeShaders.BLEND).also { blend = it }
                // Each slot of the block shows the frame that came in it, or the nearest one that did.
                val bySlot = HashMap<Int, Int>()
                for (i in 0 until gathered) places[i]?.let { bySlot.putIfAbsent(it.slot, i) }
                val weights = FloatArray(TimeShaders.FRAMES)
                val chosen = IntArray(TimeShaders.FRAMES)
                for (slot in first.weights.indices.take(TimeShaders.FRAMES)) {
                    val frame = bySlot[slot] ?: bySlot.minByOrNull { (other, _) -> Math.abs(other - slot) * 2 + (if (other < slot) 0 else 1) }!!.value
                    chosen[slot] = frame
                    weights[slot] = first.weights[slot].toFloat()
                }
                program.use()
                for (i in 0 until TimeShaders.FRAMES) program.setSamplerTexIdUniform("uF$i", frames[chosen[i]].texture, i)
                program.bindAttributesAndUniforms()
                GLES20.glUniform1fv(program.getUniformLocation("uW"), TimeShaders.FRAMES, weights, 0)
                for (i in 0 until TimeShaders.FRAMES) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                    TimeShaders.nearest(frames[chosen[i]].texture)
                }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GlUtil.checkGlError()
            }
            GlUtil.focusFramebufferUsingCurrentContext(bound[0], width, height)
            output.given = gathered
            val count = gathered
            gathered = 0
            for (i in 0 until count) outputListener.onOutputFrameAvailable(output.info, times[i])
        }

        override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
            val output = outputs.firstOrNull { it.texture == outputTexture.texId } ?: return
            output.given = maxOf(0, output.given - 1)
            if (output.given == 0) {
                if (ending) finish() else inputListener.onReadyToAcceptInputFrame()
            }
        }

        override fun signalEndOfCurrentInputStream() {
            ending = true
            finish()
        }

        private fun finish() {
            if (gathered > 0) {
                if (free() == null && outputs.size >= OUTPUTS) return // given when an output comes back
                give()
            }
            ending = false
            outputListener.onCurrentOutputStreamEnded()
        }

        override fun flush() {
            gathered = 0
            ending = false
            for (output in outputs) output.given = 0
            inputListener.onFlush()
            inputListener.onReadyToAcceptInputFrame()
        }

        private fun releasePictures() {
            for (picture in frames) picture.release()
            for (picture in outputs) picture.release()
            frames.clear()
            outputs.clear()
            times.clear()
            places.clear()
            gathered = 0
        }

        override fun release() {
            releasePictures()
            copy?.delete()
            blend?.delete()
            copy = null
            blend = null
        }
    }

    private companion object {
        /** Blends kept for the frames downstream still holds: a block's frames go on together. */
        const val OUTPUTS = 3
    }
}
