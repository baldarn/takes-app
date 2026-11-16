@file:OptIn(UnstableApi::class)

package app.takes

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import app.takes.core.TitleDesign
import app.takes.core.TitleMotion
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The captions of a movie, laid out once on a canvas with a 1080 pixel short
 * side and drawn from there at any size, so the preview and the exported movie
 * break lines at the same words. Position, size and color are read at every
 * draw; a new [SubtitleSettings.style], font, design (other than its colors)
 * or motion needs a new layout.
 *
 * A series with a design ([SubtitleSettings.appearance]) is laid out and
 * painted as styled titles are, at most 0.84 of the canvas wide
 * (YouRec/SubtitleRenderer.swift:134-138, 160); with a motion, each caption
 * arrives and leaves over its own time on screen (YouRec/SubtitleRenderer.swift:75-83, 114).
 * A series with neither is drawn exactly as it always was.
 *
 * Not for two threads at once: the preview and an export each make their own.
 */
class SubtitleLayout(val cues: List<SubtitleCue>, settings: SubtitleSettings, width: Int, height: Int, assets: AssetManager) {
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
    private val design = settings.appearance

    /** One caption's text, broken into lines, and the box around it. Canvas units. */
    private class Caption(val text: StaticLayout?, val styled: StyledText?, val width: Float, val height: Float)

    private val captions: Map<String, Caption> = cues.map { it.text }.distinct().associateWith { text ->
        if (design != null) {
            val styled = StyledText(assets, text, design, fontSize, (canvas.width * SubtitlePlacement.WIDTH_SHARE).toFloat())
            return@associateWith Caption(null, styled, styled.width, styled.height)
        }
        val room = (canvas.width * SubtitlePlacement.WIDTH_SHARE - padding * 2).toInt().coerceAtLeast(1)
        val lines = StaticLayout.Builder.obtain(text, 0, text.length, paint, room)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val widest = (0 until lines.lineCount).maxOfOrNull { lines.getLineWidth(it) } ?: 0f
        Caption(lines, null, ceil(maxOf(fontSize, widest)) + padding * 2 + 2, ceil(lines.height.toFloat()) + padding * 2 + 2)
    }

    /** Where the series may be placed and how large it may get, for every caption to show whole, moving included. */
    val placement = SubtitlePlacement(
        canvas,
        SubtitlePlacement.moving(SubtitlePlacement.largest(cues) { text -> captions.getValue(text).let { Size(it.width.toDouble(), it.height.toDouble()) } }, settings.motion),
    )

    // The pictures of designed or moving captions, by text, size and colors; a few at a time (YouRec/SubtitleRenderer.swift:89-104).
    private data class Key(val text: String, val scale: Float, val color: SubtitleSettings.Color, val paint: TitleDesign?)
    private val pictures = object : LinkedHashMap<Key, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap?>): Boolean = size > 8
    }
    private val drawing = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val source = Rect()
    private val place = RectF()

    /** Draws the captions showing at timeline second [time] on a picture of [width] by [height] pixels. */
    fun draw(target: Canvas, width: Float, height: Float, time: Double, value: SubtitleSettings) {
        if (width < 1 || height < 1) return
        val showing = SubtitleEditing.active(cues, time).filter { it.text in captions }
        if (showing.isEmpty()) return
        val active = showing.map { captions.getValue(it.text) }
        val settings = placement.constrained(value)
        // Canvas units to pixels of this picture, at the chosen size.
        val scale = (settings.size / SubtitlePlacement.REFERENCE_SIZE * width / canvas.width).toFloat()
        val tops = placement.tops(active.map { it.height.toDouble() }, settings)
        active.forEachIndexed { index, caption ->
            val cue = showing[index]
            val pose = SubtitlePlacement.pose(settings.motion, time - cue.start, cue.end - cue.start)
            if (caption.styled == null && pose == null) {
                // Neither designed nor moving: drawn straight on the picture, as it always was.
                target.save()
                target.translate((settings.centerX * width).toFloat() - caption.width * scale / 2, (tops[index] * height).toFloat())
                target.scale(scale, scale)
                plain(target, caption, settings)
                target.restore()
                return@forEachIndexed
            }
            val picture = pictures.getOrPut(Key(cue.text, scale, settings.color, settings.appearance)) {
                caption.styled?.image(scale, settings.appearance ?: caption.styled.design) ?: plainPicture(caption, scale, settings)
            } ?: return@forEachIndexed
            // Centered on the series' place, from the top the stack gives it; moved in square pixels.
            val still = app.takes.core.Rect(settings.centerX * width - picture.width / 2.0, tops[index] * height, picture.width.toDouble(), picture.height.toDouble())
            val shown = pose ?: TitleMotion.Pose()
            val visible = shown.crop(picture.width, picture.height) ?: return@forEachIndexed
            val moved = shown.rect(still)
            source.set(visible.x.toInt(), visible.y.toInt(), (visible.x + visible.width).toInt(), (visible.y + visible.height).toInt())
            place.set(moved.x.toFloat(), moved.y.toFloat(), (moved.x + moved.width).toFloat(), (moved.y + moved.height).toFloat())
            drawing.alpha = (shown.opacity.coerceIn(0.0, 1.0) * 255).toInt()
            target.drawBitmap(picture, source, place, drawing)
        }
    }

    /** A caption in the series' style and color, in canvas units on [target]. */
    private fun plain(target: Canvas, caption: Caption, settings: SubtitleSettings) {
        val text = caption.text ?: return
        val color = TextColor.valueOf(settings.color.name)
        // Rounded to the nearest of the 256 levels: truncating could make a channel one level darker.
        val ink = Color.rgb((color.red * 255).roundToInt(), (color.green * 255).roundToInt(), (color.blue * 255).roundToInt())
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
        target.translate((caption.width - text.width) / 2, padding + 1)
        text.draw(target)
    }

    /** A caption in the series' style as a picture of its own, for a motion to move it as a whole (YouRec/SubtitleRenderer.swift:166-181). */
    private fun plainPicture(caption: Caption, scale: Float, settings: SubtitleSettings): Bitmap? {
        val pixelWidth = ceil(caption.width * scale).toInt().coerceAtLeast(1)
        val pixelHeight = ceil(caption.height * scale).toInt().coerceAtLeast(1)
        if (pixelWidth > 16384 || pixelHeight > 16384) return null
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        plain(canvas, caption, settings)
        return bitmap
    }

    /** What decides the picture at [time]: the captions showing and how each is posed. The export draws again only when it changes. */
    fun state(time: Double, value: SubtitleSettings): List<Pair<String, TitleMotion.Pose?>> =
        SubtitleEditing.active(cues, time).map { it.id to SubtitlePlacement.pose(value.motion, time - it.start, it.end - it.start) }

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
class SubtitleOverlay(
    cues: List<SubtitleCue>, private val settings: SubtitleSettings, private val width: Int, private val height: Int, assets: AssetManager,
    /** Whether the picture is an HDR movie's, HLG ([HdrMovie]): the captions' colors are then carried into it. */
    private val hdr: Boolean = false,
) : BitmapOverlay() {
    private val layout = SubtitleLayout(cues, settings, width, height, assets)
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var frame: Bitmap? = null
    private var shown: List<Pair<String, TitleMotion.Pose?>> = emptyList()
    private var bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val time = presentationTimeUs / 1e6
        val visible = layout.state(time, settings)
        // A caption stays for many frames: draw when it changes or moves, not for every frame.
        if (visible != shown) {
            shown = visible
            bitmap = if (visible.isEmpty()) empty else (frame ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame = it }).let {
                it.eraseColor(Color.TRANSPARENT)
                layout.draw(Canvas(it), width.toFloat(), height.toFloat(), time, settings)
                straightened(it)
            }
        }
        return bitmap
    }

    private var straight: Bitmap? = null
    private var pixels: IntArray? = null

    /**
     * Android keeps a bitmap's colors already multiplied by their opacity;
     * Media3 multiplies an overlay's colors by its opacity again when it lays
     * it over the picture. The see-through plate, the soft shadow and the
     * smoothed edges of the letters would all come out darker than in the
     * preview, so the captions are handed over with their colors not
     * multiplied, as styled titles are.
     */
    private fun straightened(drawn: Bitmap): Bitmap {
        // A band of rows at a time: a whole 4K frame of numbers would be a large thing to keep.
        val band = minOf(height, 64)
        val buffer = pixels ?: IntArray(width * band).also { pixels = it }
        val result = straight ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.isPremultiplied = false; straight = it }
        for (top in 0 until height step band) {
            val rows = minOf(band, height - top)
            drawn.getPixels(buffer, 0, width, 0, top, width, rows) // read as plain colors
            if (hdr) for (index in 0 until width * rows) buffer[index] = HdrMovie.sdrToHlg(buffer[index])
            result.setPixels(buffer, 0, width, 0, top, width, rows) // and stored as they are
        }
        return result
    }
}
