package app.takes

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.takes.core.Size
import app.takes.core.TextOverlay
import app.takes.core.TitleAlignment
import app.takes.core.TitleDesign
import app.takes.core.TitleInk
import app.takes.core.TitleMotion
import app.takes.core.TitleMotionRanges
import app.takes.core.TitlePlate
import app.takes.core.TitleTypeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A [TitleInk] as an Android color. */
fun TitleInk.toColor(): Int = Color.argb((rgba and 0xff).toInt(), ((rgba shr 24) and 0xff).toInt(), ((rgba shr 16) and 0xff).toInt(), ((rgba shr 8) and 0xff).toInt())

/**
 * The fonts of title designs. The seven bundled families are the original's
 * own files, in `assets/TitleFonts` under the names [TitleTypeface.resourceName]
 * gives; "system" is the phone's own sans-serif; any other face is a font
 * family name the phone may know.
 */
object TitleFonts {
    /** Families whose one file holds every weight, on a weight axis. */
    private val variable = setOf("atkinsonhyperlegiblenext", "sourceserif4", "fraunces", "caveat")

    /** Font families every Android phone names, offered beside the bundled ones. */
    val deviceFamilies = listOf("serif", "monospace", "sans-serif-condensed", "sans-serif-light", "sans-serif-smallcaps", "serif-monospace", "casual", "cursive")

    private val loaded = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(assets: AssetManager, design: TitleDesign): Typeface {
        val key = "${design.face}|${design.weight}|${design.italic}"
        return loaded.getOrPut(key) { load(assets, design) }
    }

    private fun weighted(base: Typeface, weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight.coerceIn(1, 1000), italic)
        else Typeface.create(base, when {
            weight >= 600 && italic -> Typeface.BOLD_ITALIC
            weight >= 600 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })

    private fun load(assets: AssetManager, design: TitleDesign): Typeface {
        val family = TitleTypeface.family(design.face)
        if (design.face == "system" || design.face.startsWith("import:")) return weighted(Typeface.DEFAULT, design.weight, design.italic)
        if (family == null) return weighted(Typeface.create(design.face, Typeface.NORMAL), design.weight, design.italic)
        return try {
            val builder = Typeface.Builder(assets, "TitleFonts/${TitleTypeface.resourceName(design)}.ttf")
            if (family.id in variable) builder.setFontVariationSettings("'wght' ${design.weight}")
            builder.build() ?: Typeface.DEFAULT
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }

    /** True when the phone has no font for this face and the default one stands in. */
    fun missing(face: String): Boolean {
        if (face == "system" || TitleTypeface.family(face) != null) return false
        if (face.startsWith("import:")) return true
        return Typeface.create(face, Typeface.NORMAL) == Typeface.DEFAULT && face != "sans-serif"
    }
}

/**
 * One title as a picture of its own: the words with their plate, outline and
 * shadow, drawn once and then placed, moved and faded as a whole.
 *
 * A design is laid out on a canvas with a 1080 pixel short side whatever the
 * size of the picture, so the preview and the exported movie break lines at
 * the same words and differ only in scale.
 */
object TitleRaster {
    private const val REFERENCE = 1080.0

    /** [left] and [top] place the bitmap in a picture of the size it was made for. */
    class Art(val bitmap: Bitmap, val left: Float, val top: Float)

    fun art(assets: AssetManager, title: TextOverlay, width: Float, height: Float): Art? {
        if (title.text.isBlank() || width < 1 || height < 1) return null
        val design = title.appearance ?: return plain(title, width, height)
        val bitmap = styled(assets, title.text, design, title.size, Size(width.toDouble(), height.toDouble())) ?: return null
        // Centered on its anchor, kept inside the frame.
        val left = (title.x * width - bitmap.width / 2f).toFloat().coerceIn(0f, maxOf(0f, width - bitmap.width))
        val top = (title.y * height - bitmap.height / 2f).toFloat().coerceIn(0f, maxOf(0f, height - bitmap.height))
        return Art(bitmap, left, top)
    }

    /**
     * A title without a design, as [TitleRenderer.draw] draws it, in a bitmap
     * just large enough for it. Only needed when such a title moves.
     */
    private fun plain(title: TextOverlay, width: Float, height: Float): Art? {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (title.size * minOf(width, height)).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }
        val layout = StaticLayout.Builder.obtain(title.text, 0, title.text.length, paint, (width * 0.9f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val pad = ceil(paint.textSize * 0.12f)
        val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineMax(it) } ?: 0f
        val boxWidth = ceil(minOf(layout.width.toFloat(), widest) + 2 * pad).toInt().coerceAtLeast(1)
        val boxHeight = ceil(layout.height + 2 * pad).toInt().coerceAtLeast(1)
        if (boxWidth.toLong() * boxHeight > 32_000_000) return null
        val bitmap = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate((boxWidth - layout.width) / 2f, pad)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = paint.textSize * 0.12f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        val top = (title.y * height - layout.height / 2f).toFloat().coerceIn(0f, maxOf(0f, height - layout.height))
        val left = (title.x * width - layout.width / 2f).toFloat().coerceIn(0f, maxOf(0f, width - layout.width))
        return Art(bitmap, left + (layout.width - boxWidth) / 2f, top - pad)
    }

    /**
     * [text] in [design] for a picture of [picture] pixels, with letters
     * [size] of its short side tall. Null when there is nothing to draw.
     * Also used, with a small made-up picture, for the specimens in the title tools.
     */
    fun styled(assets: AssetManager, text: String, design: TitleDesign, size: Double, picture: Size): Bitmap? {
        if (text.isEmpty() || picture.width < 1 || picture.height < 1) return null
        // Everything below is in reference units until the last step.
        val unit = minOf(picture.width, picture.height) / REFERENCE
        val canvasWidth = (picture.width / unit).toFloat()
        val canvasHeight = (picture.height / unit).toFloat()
        val fontSize = maxOf(8f, (REFERENCE * size).toFloat())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            textSize = fontSize
            typeface = TitleFonts.typeface(assets, design)
            letterSpacing = design.tracking.toFloat() // in em, as the design stores it
            color = Color.WHITE
        }
        val effectPad = (if (design.outlined) design.borderWidth else 0.0) + (if (design.shadow) 2 * design.shadowBlur + abs(design.shadowOffset) else 0.0)
        val inset = ceil(fontSize * (maxOf(effectPad, if (design.plate == TitlePlate.Off) 0.0 else design.padding) + 0.14).toFloat()) + 2
        val alignment = when (design.alignment) {
            TitleAlignment.Left -> Layout.Alignment.ALIGN_NORMAL
            TitleAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE
            TitleAlignment.Center -> Layout.Alignment.ALIGN_CENTER
        }
        fun lines(room: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, room.coerceAtLeast(1))
            .setAlignment(alignment).setIncludePad(false).setLineSpacing((design.lineSpacing * fontSize).toFloat(), 1f).build()
        val room = floor(maxOf(fontSize, canvasWidth * 0.94f - 2 * inset)).toInt()
        val measured = lines(room)
        val widest = (0 until measured.lineCount).maxOfOrNull { measured.getLineMax(it) } ?: 0f
        // The box hugs the widest line, so left and right alignment are about the lines, not the picture.
        val textWidth = minOf(room, ceil(maxOf(fontSize, widest)).toInt() + 2)
        val layout = lines(textWidth)
        val textHeight = ceil(maxOf(fontSize, layout.height.toFloat())) + 2
        val boundsWidth = textWidth + 2 * inset
        val boundsHeight = textHeight + 2 * inset
        // A title too large for the picture is scaled down to fit it whole.
        val scale = (unit * minOf(1f, canvasWidth / boundsWidth, canvasHeight / boundsHeight)).toFloat()
        val pixelWidth = ceil(boundsWidth * scale).toInt()
        val pixelHeight = ceil(boundsHeight * scale).toInt()
        if (pixelWidth < 1 || pixelHeight < 1 || pixelWidth > 16384 || pixelHeight > 16384 || pixelWidth.toLong() * pixelHeight > 32_000_000) return null

        fun sheet() = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        fun Bitmap.pen() = Canvas(this).apply { scale(scale, scale); translate(inset, inset) }

        // The letters as a mask first, so see-through colors never add up where glyphs touch.
        val fill = sheet()
        layout.draw(fill.pen())
        val lettering = sheet()
        if (design.outlined && design.borderWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (2 * design.borderWidth * fontSize).toFloat() // half of it lies outside the glyph
            layout.draw(lettering.pen())
            paint.style = Paint.Style.FILL
            Canvas(lettering).apply {
                drawBitmap(fill, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                drawColor(design.border.toColor(), PorterDuff.Mode.SRC_IN)
            }
        }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        val gradient = design.gradient
        if (gradient != null) {
            val (from, to) = gradient.axis(Size(textWidth.toDouble(), textHeight.toDouble()))
            ink.shader = LinearGradient(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), gradient.first.toColor(), gradient.last.toColor(), Shader.TileMode.CLAMP)
        } else {
            ink.color = design.fill.toColor()
        }
        fill.pen().drawPaint(ink)
        Canvas(lettering).drawBitmap(fill, 0f, 0f, null)
        fill.recycle()

        val result = sheet()
        val target = Canvas(result)
        if (design.plate != TitlePlate.Off) {
            val pad = (design.padding * fontSize).toFloat()
            val radius = (design.radius * fontSize).toFloat()
            val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.backing.toColor() }
            // One path for all the strips: where two overlap, a see-through color is laid once.
            val path = android.graphics.Path()
            fun add(box: RectF) {
                val corner = minOf(radius, minOf(box.width(), box.height()) / 2)
                path.addRoundRect(box, corner, corner, android.graphics.Path.Direction.CW)
            }
            if (design.plate == TitlePlate.Block) {
                add(RectF(-pad, -pad, textWidth + pad, textHeight + pad))
            } else {
                for (line in 0 until layout.lineCount) {
                    val left = layout.getLineLeft(line)
                    val wide = layout.getLineMax(line)
                    if (wide <= 0) continue
                    val base = layout.getLineBaseline(line).toFloat()
                    add(RectF(left - pad, base + layout.getLineAscent(line) - pad, left + wide + pad, base + layout.getLineDescent(line) + pad))
                }
            }
            path.fillType = android.graphics.Path.FillType.WINDING
            target.save()
            target.scale(scale, scale)
            target.translate(inset, inset)
            target.drawPath(path, plate)
            target.restore()
        }
        // One shadow, cast by the finished lettering and not by the plate.
        if (design.shadow) {
            // The original's blur is about twice the spread Android's filter gives a radius.
            val spread = (design.shadowBlur * fontSize * scale / 2).toFloat()
            val radius = (spread - 0.5f) / 0.57735f
            val blur = Paint().apply { if (radius > 0.1f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
            val offset = IntArray(2)
            val shape = lettering.extractAlpha(blur, offset)
            val shade = Paint().apply { color = design.shade.toColor() }
            target.drawBitmap(shape, offset[0].toFloat(), offset[1] + (design.shadowOffset * fontSize * scale).toFloat(), shade)
            shape.recycle()
        }
        target.drawBitmap(lettering, 0f, 0f, null)
        lettering.recycle()
        return result
    }
}

/**
 * Draws the titles of a moment over a picture: each one's bitmap, made once
 * and kept, posed by its motion. The preview and the export use the same code
 * at their own sizes. Not for two threads at once: each makes its own.
 */
class TitlePainter(private val assets: AssetManager) {
    private data class Key(val text: String, val x: Double, val y: Double, val size: Double, val design: TitleDesign?, val width: Float, val height: Float)

    // Access-ordered: the title being restyled makes a new picture at every change, and old ones go.
    private val arts = object : LinkedHashMap<Key, TitleRaster.Art?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TitleRaster.Art?>): Boolean = size > 6
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val source = Rect()
    private val place = RectF()

    private companion object {
        val STILL = TitleMotion.Pose()
    }

    private fun art(title: TextOverlay, width: Float, height: Float): TitleRaster.Art? =
        arts.getOrPut(Key(title.text, title.x, title.y, title.size, title.appearance, width, height)) { TitleRaster.art(assets, title, width, height) }

    /** Draws [shown] on a picture of [width] by [height] pixels. [level] dims every title, as a fade to black does. */
    fun draw(target: Canvas, width: Float, height: Float, shown: List<TitleMotionRanges.Shown>, level: Float = 1f) {
        for ((title, share, pose) in shown) {
            val opacity = (share * level * pose.opacity).toFloat().coerceIn(0f, 1f)
            if (opacity <= 0f) continue
            if (title.appearance == null && pose.copy(opacity = 1.0) == STILL) {
                // A title without a design that is not moving: exactly as before designs existed.
                TitleRenderer.draw(target, width, height, title, opacity)
                continue
            }
            val art = art(title, width, height) ?: continue
            val bitmap = art.bitmap
            val visible = pose.crop(bitmap.width, bitmap.height) ?: continue
            val moved = pose.rect(app.takes.core.Rect(art.left.toDouble(), art.top.toDouble(), bitmap.width.toDouble(), bitmap.height.toDouble()))
            source.set(visible.x.toInt(), visible.y.toInt(), (visible.x + visible.width).toInt(), (visible.y + visible.height).toInt())
            place.set(moved.x.toFloat(), moved.y.toFloat(), (moved.x + moved.width).toFloat(), (moved.y + moved.height).toFloat())
            paint.alpha = (opacity * 255).toInt()
            target.drawBitmap(bitmap, source, place, paint)
        }
    }
}
