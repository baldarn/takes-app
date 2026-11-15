@file:OptIn(UnstableApi::class)

package app.takes

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import androidx.media3.effect.BitmapOverlay
import app.takes.core.AudioEnvelope
import app.takes.core.AudioEnvelopeSegment
import app.takes.core.AudioLevels
import app.takes.core.RenderPlan
import app.takes.core.SoundEnvelope
import app.takes.core.TextOverlay
import app.takes.core.TitleMotionRanges
import app.takes.core.TransitionLayout
import app.takes.core.TransitionScene

/** Draws a title the same way in the editor preview and in the exported movie. */
object TitleRenderer {
    fun draw(canvas: Canvas, width: Float, height: Float, title: TextOverlay, opacity: Float = 1f) {
        if (title.text.isBlank() || width < 1 || height < 1) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (title.size * minOf(width, height)).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(title.text, 0, title.text.length, paint, (width * 0.9f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        // Centered on its anchor, kept inside the frame.
        val top = (title.y * height - layout.height / 2f).toFloat().coerceIn(0f, maxOf(0f, height - layout.height))
        canvas.translate((title.x * width - layout.width / 2f).toFloat().coerceIn(0f, maxOf(0f, width - layout.width)), top)
        // A dark outline keeps white text readable over any picture.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = paint.textSize * 0.12f
        paint.strokeJoin = Paint.Join.ROUND
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.color = Color.BLACK
        paint.alpha = alpha
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * Export overlay for the whole movie: a frame-sized bitmap holding the titles
 * its [plan] shows at each moment ([RenderPlan.titlesShown]). It is applied once, over the finished picture, each
 * title as opaque as its clip is showing, so a title dissolves and fades with
 * the clip it belongs to. The drawing is
 * [TitlePainter]'s, the same as the preview's.
 */
class TitleOverlay(
    private val plan: RenderPlan, private val width: Int, private val height: Int, assets: AssetManager,
    private val frames: (TransitionLayout.Join) -> Double = { TransitionScene.FRAMES_PER_SECOND },
) : BitmapOverlay() {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val painter = TitlePainter(assets)
    private var frame: Bitmap? = null
    private var shown: List<TitleMotionRanges.Shown> = emptyList()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1e6
        // Opacity in steps, so a steady title is drawn once. A title on its way in or out is drawn for every frame.
        val visible = plan.titlesShown(time, frames).map { it.copy(share = (it.share * 64).toInt() / 64.0) }.filter { it.share > 0 }
        if (visible != shown) {
            shown = visible
            bitmap = if (visible.isEmpty()) empty else (frame ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame = it }).let {
                it.eraseColor(Color.TRANSPARENT)
                painter.draw(Canvas(it), width.toFloat(), height.toFloat(), visible)
                // Titles without a design are handed over as they always were, so their movies do not change.
                if (visible.any { shown -> shown.title.styled }) straightened(it) else it
            }
        }
        return bitmap
    }

    private var straight: Bitmap? = null
    private var pixels: IntArray? = null

    /**
     * Android keeps a bitmap's colors already multiplied by their opacity;
     * Media3 multiplies an overlay's colors by its opacity again when it lays
     * it over the picture. Soft shadows, see-through plates and a title that
     * is fading would all come out darker than in the preview, so they are
     * handed over with their colors not multiplied.
     */
    private fun straightened(drawn: Bitmap): Bitmap {
        // A band of rows at a time: a whole 4K frame of numbers would be a large thing to keep.
        val band = minOf(height, 64)
        val buffer = pixels ?: IntArray(width * band).also { pixels = it }
        val result = straight ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.isPremultiplied = false; straight = it }
        for (top in 0 until height step band) {
            val rows = minOf(band, height - top)
            drawn.getPixels(buffer, 0, width, 0, top, width, rows) // read as plain colors
            result.setPixels(buffer, 0, width, 0, top, width, rows) // and stored as they are
        }
        return result
    }
}

/**
 * Applies a clip's or a sound's level in an export, sample by sample, as the
 * original's tap does (CutAudioEnvelope.swift:160-183): [segment] is its run
 * of sound on the movie's clock ([SoundEnvelope]), with its own fades, its
 * share in a transition and its volume, and [begins] the movie second of the
 * first sample handed over. A null [segment] is silence: a muted clip.
 *
 * The few milliseconds of fade at a cut ([AudioLevels.CUT_FADE]) are on the
 * first and the last samples of sound that really arrive, on every run.
 * Media3 cuts sound on the frames of its codec and fills what is missing of
 * an item with silence, so a run begins and ends some milliseconds off its
 * place on the clock; and it decodes every item afresh, so sound never
 * carries on across a cut as it does in the original. A fade timed by the
 * clock would miss the end of the run, and a cut left without one, where the
 * original has none, would click. The newest samples are therefore held back
 * until it is known whether silence or the end follows them.
 *
 * As in the original, 16-bit samples stop at full scale and floating point
 * ones are left as they come out, so nothing below full scale is changed.
 */
class SoundProcessor(segment: AudioEnvelopeSegment?, private val begins: Double) : BaseAudioProcessor() {
    // The fades of the cuts are placed here: the rest of the run's level follows the clock, past its end as well.
    private val envelope = AudioEnvelope(listOfNotNull(
        segment?.copy(fadeIn = 0.0, fadeOut = 0.0, end = Double.POSITIVE_INFINITY, audibleEnd = Double.POSITIVE_INFINITY),
    ))
    private var frames = 0L
    private var gains = DoubleArray(0)
    /** Frames since the first one that was not silence; -1 before it. */
    private var sounding = -1L
    /** How many frames of silence the samples end with so far. */
    private var quiet = 0
    /**
     * The newest samples, already at their level, kept until it is known what
     * follows them: twice the length of a fade, so that when that much
     * silence has come the sound before it is still here to be faded.
     */
    private var held = ByteArray(0)
    private var holding = 0
    private var scratch = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** How many frames the fade of a cut is long. */
    private fun fadeFrames(format: AudioProcessor.AudioFormat) = maxOf(1, (format.sampleRate * AudioLevels.CUT_FADE).roundToInt())

    /** Whether the frame at byte [position] of [buffer] is digital silence. */
    private fun silent(buffer: ByteBuffer, position: Int, format: AudioProcessor.AudioFormat): Boolean {
        val width = format.bytesPerFrame / format.channelCount
        for (channel in 0 until format.channelCount) {
            val at = position + channel * width
            if (if (format.encoding == C.ENCODING_PCM_16BIT) buffer.getShort(at).toInt() != 0 else buffer.getFloat(at) != 0f) return false
        }
        return true
    }

    /** The frames of [samples] from [from] up to [until] go down to silence, which is what follows them. */
    private fun fadeOut(samples: ByteBuffer, from: Int, until: Int, format: AudioProcessor.AudioFormat) {
        val whole = format.encoding == C.ENCODING_PCM_16BIT
        val length = fadeFrames(format)
        val width = format.bytesPerFrame / format.channelCount
        for (frame in maxOf(0, from) until until) {
            val gain = (until - frame).toDouble() / length
            for (channel in 0 until format.channelCount) {
                val at = frame * format.bytesPerFrame + channel * width
                if (whole) samples.putShort(at, (samples.getShort(at) * gain).roundToInt().toShort()) else samples.putFloat(at, (samples.getFloat(at) * gain).toFloat())
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        val whole = format.encoding == C.ENCODING_PCM_16BIT
        val count = inputBuffer.remaining() / format.bytesPerFrame
        val fade = fadeFrames(format)
        if (gains.size < count) gains = DoubleArray(count)
        envelope.fill(begins + frames.toDouble() / format.sampleRate, count, format.sampleRate.toDouble(), gains)
        // What was held, then the new samples at their level.
        val before = holding / format.bytesPerFrame
        val size = holding + count * format.bytesPerFrame
        if (scratch.size < size) scratch = ByteArray(size)
        val all = ByteBuffer.wrap(scratch, 0, size).order(java.nio.ByteOrder.nativeOrder())
        all.put(held, 0, holding)
        for (frame in 0 until count) {
            var gain = gains[frame]
            // The sound comes up from its first sample that is one, not from where the clock puts it.
            if (sounding < 0 && !silent(inputBuffer, inputBuffer.position(), format)) sounding = 0
            if (sounding in 0 until fade) gain *= sounding.toDouble() / fade
            if (sounding >= 0) sounding++
            val at = all.position()
            repeat(format.channelCount) {
                if (whole) all.putShort((inputBuffer.short * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                else all.putFloat((inputBuffer.float * gain).toFloat())
            }
            // Silence as long as a fade, where there was sound, is where the sound ends: of the run, filled up to its
            // length, or of the recording. What came just before it is still held, and fades.
            quiet = if (silent(all, at, format)) quiet + 1 else 0
            if (quiet == fade) fadeOut(all, before + frame + 1 - 2 * fade, before + frame + 1 - fade, format)
        }
        frames += count
        // A buffer is whole frames; nothing of it is left behind.
        inputBuffer.position(inputBuffer.limit())
        val keep = minOf(size, 2 * fade * format.bytesPerFrame)
        val output = replaceOutputBuffer(size - keep)
        output.put(scratch, 0, size - keep)
        output.flip()
        if (held.size < keep) held = ByteArray(2 * fade * format.bytesPerFrame)
        System.arraycopy(scratch, size - keep, held, 0, keep)
        holding = keep
    }

    /**
     * Once the run is over and everything before them has been taken, its
     * last samples go down to silence. (Not when the end is announced: what
     * was handed out last may still be waiting to be read.)
     */
    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && holding > 0) {
            val format = inputAudioFormat
            val fade = fadeFrames(format)
            // The last of the sound: before the silence it ends with, when that is shorter than a fade and so not yet faded.
            val last = holding / format.bytesPerFrame - if (quiet < fade) quiet else 0
            if (quiet < fade) fadeOut(ByteBuffer.wrap(held, 0, holding).order(java.nio.ByteOrder.nativeOrder()), last - fade, last, format)
            val output = replaceOutputBuffer(holding)
            output.put(held, 0, holding)
            output.flip()
            holding = 0
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && holding == 0

    override fun onFlush() { frames = 0; holding = 0; sounding = -1; quiet = 0; envelope.prepare() }

    override fun onReset() { frames = 0; holding = 0; sounding = -1; quiet = 0 }
}
