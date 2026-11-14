@file:OptIn(UnstableApi::class)

package app.takes

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
import androidx.media3.common.audio.GainProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.RgbMatrix
import app.takes.core.TextOverlay
import app.takes.core.TimelineClip
import app.takes.core.TimelineEditing

/** Draws a title the same way in the editor preview and in the exported movie. */
object TitleRenderer {
    fun draw(canvas: Canvas, width: Float, height: Float, title: TextOverlay) {
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
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * Export overlay for one clip: a frame-sized bitmap holding the titles visible
 * at each moment. Video effects receive the time of the whole sequence, so
 * [timelineStart] is where this clip begins in it.
 */
class TitleOverlay(private val clip: TimelineClip, private val timelineStart: Double, private val width: Int, private val height: Int) : BitmapOverlay() {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var shown: List<TextOverlay> = emptyList()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val visible = TimelineEditing.textsAt(clip, clip.start + presentationTimeUs / 1e6 - timelineStart)
        if (visible != shown) {
            shown = visible
            bitmap = if (visible.isEmpty()) empty else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame ->
                val canvas = Canvas(frame)
                visible.forEach { TitleRenderer.draw(canvas, width.toFloat(), height.toFloat(), it) }
            }
        }
        return bitmap
    }
}

/** Fades the clip's picture from and to black. [timelineStart] is where the clip begins in the sequence. */
class FadeEffect(private val timelineStart: Double, private val duration: Double, private val fadeIn: Double, private val fadeOut: Double) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val level = TimelineEditing.level(presentationTimeUs / 1e6 - timelineStart, duration, fadeIn, fadeOut).toFloat()
        return floatArrayOf(level, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, level, 0f, 0f, 0f, 0f, 1f)
    }
}

/** Fades the clip's sound with the picture. Audio positions count from the start of the clip. */
class FadeGain(private val duration: Double, private val fadeIn: Double, private val fadeOut: Double) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
        TimelineEditing.level(samplePosition.toDouble() / sampleRate, duration, fadeIn, fadeOut).toFloat()

    /** The first position after [samplePosition] where the gain leaves 1, per the provider contract. */
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        if (getGainFactorAtSamplePosition(samplePosition, sampleRate) != 1f) return C.TIME_UNSET
        if (fadeOut <= 0) return C.TIME_END_OF_SOURCE
        var next = maxOf(samplePosition + 1, ((duration - fadeOut) * sampleRate).toLong())
        while (getGainFactorAtSamplePosition(next, sampleRate) == 1f) next++
        return next
    }
}
