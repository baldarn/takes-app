@file:OptIn(UnstableApi::class)

package app.takes.pixels

import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.takes.EffectShader
import app.takes.StepPrintingEffect
import app.takes.TimeHoldEffect
import app.takes.VideoEffectChain
import app.takes.core.EffectColorMath
import app.takes.core.EffectImage
import app.takes.core.EffectValue
import app.takes.core.EffectVector
import app.takes.core.FrozenFrameSegment
import app.takes.core.TimelineClip
import app.takes.core.VideoEffectFormulasC
import app.takes.core.VideoEffectID
import app.takes.core.VideoEffectSamplePlan
import app.takes.core.VideoEffectSequenceClock
import app.takes.core.VideoEffectSettings
import app.takes.core.VideoEffectTiming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One of the export's programs fed frames one after another, as a piece of
 * footage feeds it: every frame it gives on is read back with its time, so a
 * program that holds frames back (Step Printing) or gives one again (a hold)
 * is seen as the encoder would see it.
 */
class FramesStage(private val bench: Bench, effect: GlEffect, private val width: Int, private val height: Int) {
    private val program: GlShaderProgram = effect.toGlShaderProgram(bench.context, /* useHdr = */ false)
    private var failure: VideoFrameProcessingException? = null
    private val texture = IntArray(1)
    private val bytes: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private val given = ArrayList<Pair<Long, Picture>>()
    private val held = ArrayList<GlTextureInfo>()

    init {
        program.setOutputListener(object : GlShaderProgram.OutputListener {
            override fun onOutputFrameAvailable(outputTexture: GlTextureInfo, presentationTimeUs: Long) {
                given += presentationTimeUs to read(outputTexture)
                held += outputTexture
            }
        })
        program.setInputListener(object : GlShaderProgram.InputListener {})
        program.setErrorListener(Runnable::run) { failure = it }
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        for (filter in listOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, filter, GLES20.GL_LINEAR)
        for (wrap in listOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, wrap, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun read(texture: GlTextureInfo): Picture {
        val bound = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, texture.fboId)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        val out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
        GlUtil.checkGlError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
        val pixels = IntArray(width * height)
        for (row in 0 until height) for (x in 0 until width) {
            val at = (row * width + x) * 4
            pixels[(height - 1 - row) * width + x] = rgb(out.get(at).toInt() and 0xff, out.get(at + 1).toInt() and 0xff, out.get(at + 2).toInt() and 0xff)
        }
        return Picture(width, height, pixels)
    }

    /** Feeds [input] stamped [timeUs]; what the program has given on since, with each frame's time. */
    fun feed(input: Picture, timeUs: Long): List<Pair<Long, Picture>> {
        bytes.clear()
        for (row in 0 until height) for (x in 0 until width) {
            val color = input.at(x, height - 1 - row)
            bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(0xff.toByte())
        }
        bytes.flip()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        program.queueInputFrame(bench.objects, GlTextureInfo(texture[0], C.INDEX_UNSET, C.INDEX_UNSET, width, height), timeUs)
        failure?.let { throw it }
        return drain()
    }

    /** Ends the piece: what the program gives on at its end. */
    fun end(): List<Pair<Long, Picture>> {
        program.signalEndOfCurrentInputStream()
        failure?.let { throw it }
        return drain()
    }

    /** What the program gives on as the frames it gave come back, until it gives no more. */
    fun rest(): List<Pair<Long, Picture>> {
        val out = ArrayList<Pair<Long, Picture>>()
        do {
            val more = drain()
            out += more
        } while (more.isNotEmpty())
        return out
    }

    private fun drain(): List<Pair<Long, Picture>> {
        val out = given.toList()
        given.clear()
        val released = held.toList()
        held.clear()
        for (texture in released) program.releaseOutputFrame(texture)
        return out
    }

    fun release() {
        program.release()
        GLES20.glDeleteTextures(1, texture, 0)
    }
}

/**
 * The time effects drawn by the export against the core: Freeze Frame's
 * push-in and Print and Undercranking's flicker and weave by their formulas
 * ([VideoEffectFormulasC.timePixel]); the frames a cadence or a freeze holds
 * ([VideoEffectTiming.shown]); Step Printing's blend of each block's frames
 * by the core's plan and weights ([VideoEffectSamplePlan]).
 */
class TimePixelCases(private val bench: Bench, private val width: Int = 192, private val height: Int = 108) {
    /** Frame [frame] of a footage of 30 a second: ramps and bands that move with the frame, so each frame is told apart. */
    private fun footage(frame: Int) = Picture(width, height) { x, y ->
        val band = y * 6 / height
        val shift = (frame * 37) % 256
        val v = (x * 255 / (width - 1) + shift) % 256
        when (band) {
            0 -> rgb(v, v, v)
            1 -> rgb(v, 255 - v, (frame * 53) % 256)
            2 -> rgb((x * 7 + y * 13 + frame * 29) % 256, (x * 3 + frame * 11) % 256, (y * 5 + frame * 17) % 256)
            3 -> rgb(255 - v, v / 2, 255)
            4 -> rgb((frame * 40) % 256, 128, v)
            else -> rgb(v, (v + 85) % 256, (v + 170) % 256)
        }
    }

    private fun linear(picture: Picture): EffectImage = EffectImage.of(width, height) { x, y ->
        EffectVector(EffectColorMath.linear(picture.channel(x, y, 0) / 255.0), EffectColorMath.linear(picture.channel(x, y, 1) / 255.0), EffectColorMath.linear(picture.channel(x, y, 2) / 255.0))
    }

    private fun encoded(image: EffectImage) = Picture(width, height) { x, y ->
        val c = image[x, y]
        fun level(v: Double) = (EffectColorMath.encoded(v).coerceIn(0.0, 1.0) * 255).roundToInt()
        rgb(level(c.x), level(c.y), level(c.z))
    }

    private fun distance(a: Int, b: Int): Int = (0..2).maxOf { abs(((a shr (16 - 8 * it)) and 0xff) - ((b shr (16 - 8 * it)) and 0xff)) }

    /** The worst difference of two pictures, and how many pixels are over [allowed]. */
    private fun compare(drawn: Picture, want: Picture, allowed: Int): Pair<Int, Int> {
        var worst = 0
        var over = 0
        for (i in drawn.pixels.indices) {
            val d = distance(drawn.pixels[i], want.pixels[i])
            worst = maxOf(worst, d)
            if (d > allowed) over++
        }
        return worst to over
    }

    private fun settings(kind: VideoEffectID, values: Map<String, Any>): VideoEffectSettings {
        var effect = VideoEffectSettings(kind, 0.0, 10.0)
        for ((id, value) in values) effect = effect.with(id, when (value) {
            is Boolean -> EffectValue.Toggle(value)
            is String -> EffectValue.Choice(value)
            else -> EffectValue.Number((value as Number).toDouble())
        })
        return effect
    }

    /** Freeze Frame's pass on a held frame, at [holdTime] seconds of the hold, against [VideoEffectFormulasC.timePass]. */
    private fun freezePass(label: String, values: Map<String, Any>, holdTime: Double, offset: Double = 0.0) = Case("Freeze Frame: $label") {
        val effect = settings(VideoEffectID.FreezeFrame, values).copy(start = 2.0, end = 2.0 + 1.0 / 30)
        val hold = TimelineClip(id = "H", sourceID = "S", start = 2.0, end = 2.0 + 1.0 / 30, frozenFrame = FrozenFrameSegment(2.0, effect.number("hold", 2.0), offset), videoEffects = listOf(effect))
        val stage = FramesStage(bench, VideoEffectChain(hold, 0.0, RATE), width, height)
        try {
            val input = footage(3)
            val at = holdTime - offset
            val drawn = stage.feed(input, (at * 1e6).roundToLong()).single().second
            val passes = EffectShader.passes(hold.videoEffects, 2.0, RATE, holdTime = holdTime)
            check(passes.singleOrNull()?.effect == VideoEffectID.FreezeFrame) { "Freeze Frame is not drawn" }
            val want = encoded(VideoEffectFormulasC.timePass(passes.single(), linear(input)))
            val (worst, over) = compare(drawn, want, ALLOWED)
            note("hold clock ${"%.2f".format(holdTime)} s: worst $worst levels, $over over $ALLOWED")
            check(over <= OUTLIERS && worst <= OUTLIER_LEVELS) { "$over pixels more than $ALLOWED levels from the core's formula, the worst $worst" }
        } finally {
            stage.release()
        }
    }

    /** Undercranking's pass at movie second [time] of a clip at speed 1, drawn at the frame the cadence holds. */
    private fun undercrankingPass(label: String, values: Map<String, Any>, time: Double) = Case("Undercranking: $label") {
        val effect = settings(VideoEffectID.Undercranking, values + ("speed" to 1.0))
        val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = listOf(effect))
        val stage = FramesStage(bench, VideoEffectChain(clip, 0.0, RATE), width, height)
        try {
            val input = footage(5)
            val drawn = stage.feed(input, (time * 1e6).roundToLong()).single().second
            val shown = VideoEffectTiming.shown(clip, time, RATE, VideoEffectSequenceClock.of(clip))
            val passes = EffectShader.passes(clip.videoEffects, shown.sourceTime, RATE)
            check(passes.singleOrNull()?.effect == VideoEffectID.Undercranking) { "Undercranking is not drawn" }
            val want = encoded(VideoEffectFormulasC.timePass(passes.single(), linear(input)))
            val (worst, over) = compare(drawn, want, ALLOWED)
            note("shown at ${"%.4f".format(shown.sourceTime)} s: worst $worst levels, $over over $ALLOWED")
            check(over <= OUTLIERS && worst <= OUTLIER_LEVELS) { "$over pixels more than $ALLOWED levels from the core's formula, the worst $worst" }
        } finally {
            stage.release()
        }
    }

    /** The frames a hold gives on, one for each frame fed, against the frame the core says each shows. */
    private fun holds(label: String, clip: TimelineClip, frames: Int, shownFrame: (Int) -> Int) = Case("Hold: $label") {
        val stage = FramesStage(bench, TimeHoldEffect(clip, 0.0, RATE), width, height)
        try {
            var worst = 0
            val shown = ArrayList<Int>()
            for (frame in 0 until frames) {
                val given = stage.feed(footage(frame), Math.round(frame * 1e6 / RATE))
                check(given.size == 1) { "frame $frame: ${given.size} frames given" }
                val want = shownFrame(frame)
                shown += want
                val off = compare(given.single().second, footage(want), 0).first
                if (off > 1) note("frame $frame: $off levels from frame $want; nearest fed frame ${(0..frame).minByOrNull { compare(given.single().second, footage(it), 0).first }}")
                worst = maxOf(worst, off)
                check(given.single().first == Math.round(frame * 1e6 / RATE)) { "frame $frame given at ${given.single().first} µs" }
            }
            note("frames shown: ${shown.joinToString(" ")}; worst $worst levels")
            check(worst <= 1) { "a held frame is not the frame the core holds: $worst levels off" }
        } finally {
            stage.release()
        }
    }

    /** Step Printing's blends of footage at 30 a second, each frame given on at its own time, against the core's plan and weights. */
    private fun steps(label: String, values: Map<String, Any>, frames: Int) = Case("Step Printing: $label") {
        val effect = settings(VideoEffectID.StepPrinting, values + ("speed" to 1.0))
        val clip = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = frames / RATE, videoEffects = listOf(effect))
        val clock = VideoEffectSequenceClock.of(clip)
        val stage = FramesStage(bench, StepPrintingEffect(clip, 0.0, RATE), width, height)
        try {
            val given = ArrayList<Pair<Long, Picture>>()
            for (frame in 0 until frames) given += stage.feed(footage(frame), Math.round(frame * 1e6 / RATE))
            given += stage.end()
            check(given.size == frames) { "${given.size} frames given for $frames fed" }
            check(given.map { it.first } == (0 until frames).map { Math.round(it * 1e6 / RATE) }) { "frames given at ${given.map { it.first }}" }
            var worst = 0
            var overAll = 0
            for ((time, drawn) in given) {
                val second = time / 1e6
                val plan = VideoEffectSamplePlan.make(clip.videoEffects!!, second, RATE, clock)!!
                // Each time of the plan reads the first frame at or after it, inside the clip.
                val read = plan.times.map { minOf(frames - 1, maxOf(0, kotlin.math.ceil(it * RATE - 0.0001).toInt())) }
                val weights = plan.weights
                val images = read.map { linear(footage(it)) }
                val blend = EffectImage.of(width, height) { x, y ->
                    var sum = EffectVector.ZERO
                    for (i in images.indices) sum += images[i][x, y] * weights[i]
                    sum
                }
                val (w, over) = compare(drawn, encoded(blend), ALLOWED)
                if (w > ALLOWED && worst <= ALLOWED) {
                    val want = encoded(blend)
                    val i = drawn.pixels.indices.maxByOrNull { distance(drawn.pixels[it], want.pixels[it]) }!!
                    note("at ${"%.4f".format(second)} s reads $read by ${weights.map { "%.3f".format(it) }}: pixel $i drawn #%06x, blend #%06x, from %s".format(drawn.pixels[i], want.pixels[i], read.joinToString { "#%06x".format(footage(it).pixels[i]) }))
                }
                worst = maxOf(worst, w)
                overAll += over
            }
            note("${given.size} frames, worst $worst levels, $overAll pixels over $ALLOWED")
            check(overAll <= OUTLIERS && worst <= OUTLIER_LEVELS) { "$overAll pixels more than $ALLOWED levels from the core's blend, the worst $worst" }
        } finally {
            stage.release()
        }
    }

    fun all(): List<Case> {
        val cadence = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = listOf(settings(VideoEffectID.Undercranking, mapOf("fps" to 12.0, "speed" to 1.0))))
        val cadenceClock = VideoEffectSequenceClock.of(cadence)
        val superEight = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 10.0, videoEffects = listOf(settings(VideoEffectID.SuperEight, mapOf("fps" to 18.0))))
        val superEightClock = VideoEffectSequenceClock.of(superEight)
        val frozen = TimelineClip(id = "H", sourceID = "S", start = 2.0, end = 2.0 + 1.0 / 30, frozenFrame = FrozenFrameSegment(2.0, 3.0),
            videoEffects = listOf(settings(VideoEffectID.FreezeFrame, emptyMap()).copy(start = 2.0, end = 2.0 + 1.0 / 30)))
        return listOf(
            freezePass("Clean, the push-in under way", mapOf("push" to 0.12), holdTime = 1.5),
            freezePass("Clean, the push-in done", mapOf("push" to 0.15, "hold" to 2.0), holdTime = 2.5),
            freezePass("Print, its grey, vignette and grain", mapOf("style" to "Print", "push" to 0.05), holdTime = 0.7),
            freezePass("Print, the later piece of a split hold", mapOf("style" to "Print"), holdTime = 2.2, offset = 1.0),
            undercrankingPass("defaults", emptyMap(), time = 1.0),
            undercrankingPass("strong flicker and weave at 12", mapOf("fps" to 12.0, "flicker" to 1.0, "weave" to 1.0), time = 2.35),
            undercrankingPass("no flicker nor weave", mapOf("flicker" to 0.0, "weave" to 0.0), time = 0.5),
            holds("Undercranking at 12 a second", cadence, 30) { frame ->
                Math.round(VideoEffectTiming.shown(cadence, frame / RATE, RATE, cadenceClock).sourceTime * RATE).toInt()
            },
            holds("Super 8 at 18 a second", superEight, 30) { frame ->
                Math.round(VideoEffectTiming.shown(superEight, frame / RATE, RATE, superEightClock).sourceTime * RATE).toInt()
            },
            holds("a Freeze Frame holds its first frame", frozen, 12) { 0 },
            steps("three frames, smear 0.7", mapOf("step" to 3.0, "smear" to 0.7), 14),
            steps("six frames, full smear", mapOf("step" to 6.0, "smear" to 1.0), 20),
            steps("two frames, no smear: the first of each block", mapOf("step" to 2.0, "smear" to 0.0), 9),
        )
    }

    private companion object {
        const val RATE = 30.0
        const val ALLOWED = 2
        const val OUTLIERS = 64
        const val OUTLIER_LEVELS = 8
    }
}
