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
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import app.takes.core.RenderPlan
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
    /** Seconds the frames' times are ahead of the plan's: where a clip drawn by itself begins in its sequence. */
    private val shift: Double = 0.0,
) : BitmapOverlay() {
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val painter = TitlePainter(assets)
    private var frame: Bitmap? = null
    private var shown: List<TitleMotionRanges.Shown> = emptyList()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1e6 - shift
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
