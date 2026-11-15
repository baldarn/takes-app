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
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.RgbMatrix
import app.takes.core.TextOverlay
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing
import app.takes.core.TitleMotionRanges

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
 * visible at each moment. It is applied once, after the clips are blended, so
 * a title dissolves and fades with the clip it belongs to. The drawing is
 * [TitlePainter]'s, the same as the preview's.
 */
class TitleOverlay(private val clips: List<TimelineClip>, private val width: Int, private val height: Int, assets: AssetManager) : BitmapOverlay() {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val ranges = TitleMotionRanges(clips)
    private val painter = TitlePainter(assets)
    private var frame: Bitmap? = null
    private var shown: List<TitleMotionRanges.Shown> = emptyList()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1e6
        val level = TimelineEditing.locate(clips, time)?.let { TimelineEditing.level(clips, it.index, it.offset) } ?: 1.0
        // Opacity in steps, so a steady title is drawn once. A title on its way in or out is drawn for every frame.
        val visible = ranges.shown(clips, time).map { it.copy(share = (it.share * level * 64).toInt() / 64.0) }.filter { it.share > 0 }
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

/** Fades the clip's picture from and to black. [timelineStart] is where the clip begins in the sequence. */
class FadeEffect(private val timelineStart: Double, private val duration: Double, private val fadeIn: Double, private val fadeOut: Double) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val level = TimelineEditing.level(presentationTimeUs / 1e6 - timelineStart, duration, fadeIn, fadeOut).toFloat()
        return floatArrayOf(level, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, 1f)
    }
}

/**
 * Applies a clip's sound level over time in an export: its volume and mute,
 * its fades and its share in a transition. [level] takes seconds from the
 * start of the clip and may exceed 1 for a boosted clip; samples are clipped.
 */
class SoundProcessor(private val level: (Double) -> Double) : BaseAudioProcessor() {
    private var frames = 0L
    private var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.hasRemaining()) {
            // A new level every 64 frames is far finer than any fade needs.
            if (frames % 64 == 0L) gain = level(frames.toDouble() / format.sampleRate).toFloat()
            repeat(format.channelCount) {
                if (format.encoding == C.ENCODING_PCM_16BIT) {
                    output.putShort((inputBuffer.short * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                } else {
                    output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
                }
            }
            frames++
        }
        output.flip()
    }

    override fun onFlush() { frames = 0 }

    override fun onReset() { frames = 0 }
}

/**
 * Blends the two sequences of an export with dissolves. The first sequence is
 * drawn on top, so its opacity is the share of the clip it is showing; the
 * second stays opaque underneath.
 */
class DissolveCompositor(private val clips: List<TimelineClip>, private val canvas: Size) : VideoCompositorSettings {
    private val top = TimelineEditing.chains(clips).filterIndexed { index, _ -> index % 2 == 0 }

    override fun getOutputSize(inputSizes: List<Size>): Size = canvas

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val opacity = if (inputId == 0) topShare(presentationTimeUs / 1e6).toFloat() else 1f
        return object : OverlaySettings {
            override fun getAlphaScale(): Float = opacity
        }
    }

    private fun topShare(time: Double): Double {
        val chain = top.firstOrNull { time >= it.start && time < it.end } ?: return 0.0
        val clip = (chain.first..chain.last).lastOrNull { TimelineEditing.clipStart(clips, it) <= time } ?: chain.first
        return TimelineEditing.weight(clips, clip, time - TimelineEditing.clipStart(clips, clip))
    }
}
