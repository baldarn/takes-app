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
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import app.takes.core.Size
import app.takes.core.SubtitleCue
import app.takes.core.SubtitleEditing
import app.takes.core.SubtitleFont
import app.takes.core.SubtitlePlacement
import app.takes.core.SubtitleSettings
import app.takes.core.TextColor
import kotlin.math.ceil

/**
 * The captions of a movie, laid out once on a canvas with a 1080 pixel short
 * side and drawn from there at any size, so the preview and the exported movie
 * break lines at the same words. Position, size and color are read at every
 * draw; a new [SubtitleSettings.style] or font needs a new layout.
 *
 * Not for two threads at once: the preview and an export each make their own.
 */
class SubtitleLayout(val cues: List<SubtitleCue>, settings: SubtitleSettings, width: Int, height: Int) {
    private val style = settings.style
    private val font = settings.resolvedFont
    private val canvas = SubtitlePlacement.canvas(width.toDouble(), height.toDouble())
    private val fontSize = (minOf(canvas.width, canvas.height) * SubtitlePlacement.REFERENCE_SIZE).toFloat()
    private val padding = fontSize * 0.35f
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        typeface = typeface(font, bold = this@SubtitleLayout.style != SubtitleSettings.Style.Clean)
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    /** One caption's text, broken into lines, and the box around it. Canvas units. */
    private class Caption(val text: StaticLayout, val width: Float, val height: Float)

    private val captions: Map<String, Caption> = cues.map { it.text }.distinct().associateWith { text ->
        val room = (canvas.width * SubtitlePlacement.WIDTH_SHARE - padding * 2).toInt().coerceAtLeast(1)
        val lines = StaticLayout.Builder.obtain(text, 0, text.length, paint, room)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val widest = (0 until lines.lineCount).maxOfOrNull { lines.getLineWidth(it) } ?: 0f
        Caption(lines, ceil(maxOf(fontSize, widest)) + padding * 2 + 2, ceil(lines.height.toFloat()) + padding * 2 + 2)
    }

    /** Where the series may be placed and how large it may get, for every caption to show whole. */
    val placement = SubtitlePlacement(canvas, SubtitlePlacement.largest(cues) { text -> captions.getValue(text).let { Size(it.width.toDouble(), it.height.toDouble()) } })

    /** Draws the captions showing at timeline second [time] on a picture of [width] by [height] pixels. */
    fun draw(target: Canvas, width: Float, height: Float, time: Double, value: SubtitleSettings) {
        if (width < 1 || height < 1) return
        val active = SubtitleEditing.active(cues, time).mapNotNull { captions[it.text] }
        if (active.isEmpty()) return
        val settings = placement.constrained(value)
        // Canvas units to pixels of this picture, at the chosen size.
        val scale = (settings.size / SubtitlePlacement.REFERENCE_SIZE * width / canvas.width).toFloat()
        val tops = placement.tops(active.map { it.height.toDouble() }, settings)
        val color = TextColor.valueOf(settings.color.name)
        val ink = Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        active.forEachIndexed { index, caption ->
            target.save()
            target.translate((settings.centerX * width).toFloat() - caption.width * scale / 2, (tops[index] * height).toFloat())
            target.scale(scale, scale)
            if (style == SubtitleSettings.Style.Box || style == SubtitleSettings.Style.Highlight) {
                // A dark plate under light text, or a plate in the chosen color under dark text.
                box.color = if (style == SubtitleSettings.Style.Box) Color.argb(209, 5, 5, 5) else ink
                target.drawRoundRect(1f, 1f, caption.width - 1, caption.height - 1, padding * 0.7f, padding * 0.7f, box)
                paint.clearShadowLayer()
            } else {
                // No plate: a soft shadow keeps the text readable over any picture.
                paint.setShadowLayer(fontSize * 0.12f, 0f, fontSize * 0.04f, Color.argb(230, 0, 0, 0))
            }
            paint.color = if (style == SubtitleSettings.Style.Highlight) Color.rgb(8, 8, 8) else ink
            target.translate((caption.width - caption.text.width) / 2, padding + 1)
            caption.text.draw(target)
            target.restore()
        }
    }

    private companion object {
        /**
         * The original's five caption fonts are iPhone fonts. These are the
         * nearest faces every Android phone has; a name the phone does not
         * know falls back to its default sans-serif.
         */
        fun typeface(font: SubtitleFont, bold: Boolean): Typeface = when (font) {
            SubtitleFont.System -> Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            SubtitleFont.Modern -> Typeface.create(if (bold) "sans-serif-medium" else "sans-serif-light", Typeface.NORMAL)
            SubtitleFont.Classic -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            SubtitleFont.Mono -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            SubtitleFont.Condensed -> Typeface.create("sans-serif-condensed", Typeface.BOLD) // one weight, as in the original
        }
    }
}

/**
 * Export overlay for the whole movie: a frame-sized bitmap holding the
 * captions showing at each moment, drawn by the same [SubtitleLayout] code as
 * the preview. Applied once, over the finished picture, like the titles.
 */
class SubtitleOverlay(cues: List<SubtitleCue>, private val settings: SubtitleSettings, private val width: Int, private val height: Int) : BitmapOverlay() {
    private val layout = SubtitleLayout(cues, settings, width, height)
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var frame: Bitmap? = null
    private var shown = emptyList<String>()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1e6
        val visible = SubtitleEditing.active(layout.cues, time).map { it.id }
        // A caption stays for many frames: draw when it changes, not for every frame.
        if (visible != shown) {
            shown = visible
            bitmap = if (visible.isEmpty()) empty else (frame ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame = it }).also {
                it.eraseColor(Color.TRANSPARENT)
                layout.draw(Canvas(it), width.toFloat(), height.toFloat(), time, settings)
            }
        }
        return bitmap
    }
}
