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
import app.takes.core.ImageOverlay
import app.takes.core.LabelHeadline
import app.takes.core.LabelHeadlineArrangement
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
 * The fonts of title designs. The nine bundled families are the original's
 * own files, in `assets/TitleFonts` under the names [TitleTypeface.resourceName]
 * gives; "system" is the phone's own sans-serif; an "import:" face is a font
 * file of the library or of a project ([ImportedFonts]); any other face is a
 * font family name the phone may know.
 */
object TitleFonts {
    /** Families whose one file holds every weight, on a weight axis. */
    private val variable = setOf("atkinsonhyperlegiblenext", "sourceserif4", "fraunces", "caveat", "bodonimoda", "unbounded")

    /** Font families every Android phone names, offered beside the bundled ones. */
    val deviceFamilies = listOf("serif", "monospace", "sans-serif-condensed", "sans-serif-light", "sans-serif-smallcaps", "serif-monospace", "casual", "cursive")

    private val loaded = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(assets: AssetManager, design: TitleDesign): Typeface {
        // An imported face is loaded again once its file is found, or found elsewhere.
        val imported = if (design.face.startsWith("import:")) ImportedFonts.resolve(design.face) else null
        val key = "${design.face}|${design.weight}|${design.italic}|${imported?.first?.path}"
        return loaded.getOrPut(key) { if (imported != null) imported(imported.first, imported.second, design.weight) else load(assets, design) }
    }

    /**
     * One face of an imported file, at [weight] on its weight axis when it has
     * one, as the original varies it (YouRec/ImportedFonts.swift:165-172); the
     * original offers no italic for such a face.
     */
    private fun imported(file: java.io.File, face: app.takes.core.ImportedFontFace, weight: Int): Typeface = try {
        val builder = Typeface.Builder(file).setTtcIndex(face.reference.index)
        if (face.weights.size > 1) builder.setFontVariationSettings("'wght' $weight")
        builder.build() ?: Typeface.DEFAULT
    } catch (_: Exception) {
        Typeface.DEFAULT
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
        if (face.startsWith("import:")) return ImportedFonts.resolve(face) == null
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
        if (!title.hasText || width < 1 || height < 1) return null
        val picture = Size(width.toDouble(), height.toDouble())
        val group = title.labelHeadline
        val design = title.appearance
        if (group == null && design == null) return plain(title, width, height)
        val bitmap = when {
            group != null -> card(assets, group, title.text, title.size, picture)
            design == TitleDesign() -> untouched(assets, title.text, title.size, picture)
            else -> styled(assets, title.text, design!!, title.size, picture)
        } ?: return null
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

    /** The room around the letters of a title nobody has styled, in em, and the share of the picture's width it may take. */
    private const val UNTOUCHED_PADDING = 0.35
    private const val UNTOUCHED_WIDTH = 0.84

    /** The shadow of a title nobody has styled: 0.04 em down, black at nine tenths. Its blur is the default design's, 0.12 em. */
    private val untouchedDesign = TitleDesign(shadowOffset = 0.04, shade = TitleInk(0x000000, alpha = 230))

    /**
     * A new title whose design is still the default one, as the original draws
     * a title that has no design yet: bold white letters with a soft shadow and
     * no outline, in lines at most 0.84 of the picture wide less 0.35 em on
     * each side. Once anything of the design is changed the title is drawn by
     * [styled], as in the original.
     */
    fun untouched(assets: AssetManager, text: String, size: Double, picture: Size): Bitmap? =
        drawn(assets, text, untouchedDesign, size, picture, UNTOUCHED_PADDING, UNTOUCHED_WIDTH)

    /**
     * [text] in [design] for a picture of [picture] pixels, with letters
     * [size] of its short side tall. Null when there is nothing to draw.
     * Also used, with a small made-up picture, for the specimens in the title tools.
     */
    fun styled(assets: AssetManager, text: String, design: TitleDesign, size: Double, picture: Size): Bitmap? =
        drawn(assets, text, design, size, picture, null, 0.94)

    /**
     * A card of a label and a headline, [headline] its words, for a picture of
     * [picture] pixels, the card's size [size] of its short side, in lines at
     * most 0.94 of its width, scaled down to fit the picture whole
     * (YouRec/VideoToolCompositor.swift:617-624).
     */
    fun card(assets: AssetManager, group: LabelHeadline, headline: String, size: Double, picture: Size): Bitmap? {
        if (picture.width < 1 || picture.height < 1) return null
        val unit = minOf(picture.width, picture.height) / REFERENCE
        val canvasWidth = (picture.width / unit).toFloat()
        val canvasHeight = (picture.height / unit).toFloat()
        val layout = CardText(assets, group, headline, maxOf(8f, (REFERENCE * size).toFloat()), canvasWidth * 0.94f)
        return layout.image(canvasWidth, canvasHeight, unit.toFloat())
    }

    /** [padding], in em, replaces the room a design leaves around its letters; [share] is how much of the picture's width the title may take. */
    private fun drawn(assets: AssetManager, text: String, design: TitleDesign, size: Double, picture: Size, padding: Double?, share: Double): Bitmap? {
        if (text.isEmpty() || picture.width < 1 || picture.height < 1) return null
        // Everything is laid out in reference units; only the last step is in pixels.
        val unit = minOf(picture.width, picture.height) / REFERENCE
        val canvasWidth = (picture.width / unit).toFloat()
        val canvasHeight = (picture.height / unit).toFloat()
        val styled = StyledText(assets, text, design, maxOf(8f, (REFERENCE * size).toFloat()), canvasWidth * share.toFloat(), padding)
        // A title too large for the picture is scaled down to fit it whole.
        return styled.image((unit * minOf(1f, canvasWidth / styled.width, canvasHeight / styled.height)).toFloat())
    }
}

/**
 * [text] in [design], laid out once and painted at any scale, as the
 * original's styled text layout (YouRec/StyledTextRenderer.swift:6-101): the
 * lines are broken for a box at most [maxWidth] wide, both in the units of
 * [fontSize], and [width] by [height] is the box with the room the outline,
 * shadow and plate need around the letters. Titles and designed captions are
 * drawn by it. Not for two threads at once.
 *
 * @param padding the room around the letters in em, in place of the design's.
 */
class StyledText(assets: AssetManager, text: String, val design: TitleDesign, private val fontSize: Float, maxWidth: Float, padding: Double? = null) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = fontSize
        typeface = TitleFonts.typeface(assets, design)
        letterSpacing = design.tracking.toFloat() // in em, as the design stores it
        color = Color.WHITE
    }
    private val inset: Float
    private val textWidth: Int
    private val textHeight: Float
    private val layout: StaticLayout
    val width: Float
    val height: Float

    init {
        val effectPad = (if (design.outlined) design.borderWidth else 0.0) + (if (design.shadow) 2 * design.shadowBlur + abs(design.shadowOffset) else 0.0)
        inset = if (padding != null) fontSize * padding.toFloat()
        else ceil(fontSize * (maxOf(effectPad, if (design.plate == TitlePlate.Off) 0.0 else design.padding) + 0.14).toFloat()) + 2
        val alignment = when (design.alignment) {
            TitleAlignment.Left -> Layout.Alignment.ALIGN_NORMAL
            TitleAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE
            TitleAlignment.Center -> Layout.Alignment.ALIGN_CENTER
        }
        fun lines(room: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, room.coerceAtLeast(1))
            .setAlignment(alignment).setIncludePad(false).setLineSpacing((design.lineSpacing * fontSize).toFloat(), 1f).build()
        val room = floor(maxOf(fontSize, maxWidth - 2 * inset)).toInt()
        val measured = lines(room)
        val widest = (0 until measured.lineCount).maxOfOrNull { measured.getLineMax(it) } ?: 0f
        // The box hugs the widest line, so left and right alignment are about the lines, not the picture.
        textWidth = minOf(room, ceil(maxOf(fontSize, widest)).toInt() + 2)
        layout = lines(textWidth)
        textHeight = ceil(maxOf(fontSize, layout.height.toFloat())) + 2
        width = textWidth + 2 * inset
        height = textHeight + 2 * inset
    }

    /**
     * The text [scale] pixels to a unit, in the colors of [paint]: a design
     * that differs from the one laid out only in its colors
     * ([TitleDesign.layoutKey]). Null when the picture would be too large.
     */
    fun image(scale: Float, paint: TitleDesign = design): Bitmap? {
        val design = paint
        val pixelWidth = ceil(width * scale).toInt()
        val pixelHeight = ceil(height * scale).toInt()
        if (!scale.isFinite() || scale <= 0f) return null
        if (pixelWidth < 1 || pixelHeight < 1 || pixelWidth > 16384 || pixelHeight > 16384 || pixelWidth.toLong() * pixelHeight > 32_000_000) return null
        val letters = this.paint

        fun sheet() = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        fun Bitmap.pen() = Canvas(this).apply { scale(scale, scale); translate(inset, inset) }

        // The letters as a mask first, so see-through colors never add up where glyphs touch.
        val fill = sheet()
        layout.draw(fill.pen())
        val lettering = sheet()
        if (design.outlined && design.borderWidth > 0) {
            letters.style = Paint.Style.STROKE
            letters.strokeJoin = Paint.Join.ROUND
            letters.strokeWidth = (2 * design.borderWidth * fontSize).toFloat() // half of it lies outside the glyph
            layout.draw(lettering.pen())
            letters.style = Paint.Style.FILL
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
 * A card of a label and a headline laid out once, as the original's
 * (YouRec/StyledTextRenderer.swift:144-224): each text laid out by
 * [StyledText] in lines at most [width] less the card's padding wide, the
 * label [LabelHeadline.labelScale] and the headline
 * [LabelHeadline.headlineScale] of [fontSize], placed by
 * [LabelHeadlineArrangement]. A text with nothing but spaces is left out.
 */
class CardText(assets: AssetManager, group: LabelHeadline, headline: String, private val fontSize: Float, width: Float) {
    val label: StyledText?
    val headline: StyledText?
    val arrangement: LabelHeadlineArrangement
    private val card = group.card

    init {
        val pad = ceil(group.card.padding * fontSize).toFloat()
        fun layout(words: String, style: TitleDesign, scale: Double) =
            if (words.isBlank()) null else StyledText(assets, words, style, (fontSize * scale).toFloat(), maxOf(1f, width - 2 * pad))
        label = layout(group.label, group.labelStyle, group.labelScale)
        this.headline = layout(headline, group.headlineStyle, group.headlineScale)
        arrangement = LabelHeadlineArrangement(
            label?.let { Size(it.width.toDouble(), it.height.toDouble()) }, group.labelStyle.alignment,
            this.headline?.let { Size(it.width.toDouble(), it.height.toDouble()) }, group.headlineStyle.alignment,
            group.card, fontSize.toDouble(),
        )
    }

    /**
     * The card for a picture [canvasWidth] by [canvasHeight] units, [unit]
     * pixels each: made smaller to fit it whole, never larger. Null when there
     * is nothing to draw or the picture would be too large.
     */
    fun image(canvasWidth: Float, canvasHeight: Float, unit: Float): Bitmap? {
        val size = arrangement.size
        if (!(size.width > 0 && size.height > 0)) return null
        val scale = unit * minOf(1.0, canvasWidth / size.width, canvasHeight / size.height).toFloat()
        val pixelWidth = ceil(size.width * scale).toInt()
        val pixelHeight = ceil(size.height * scale).toInt()
        if (pixelWidth < 1 || pixelHeight < 1 || pixelWidth > 16384 || pixelHeight > 16384 || pixelWidth.toLong() * pixelHeight > 32_000_000) return null
        val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        val bounds = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val radius = minOf(card.radius * fontSize, minOf(size.width, size.height) / 2).toFloat()
        canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.fill.toColor() })
        val stroke = minOf(card.borderWidth * fontSize, minOf(size.width, size.height)).toFloat()
        if (stroke > 0) {
            val inset = stroke / 2
            val corner = maxOf(0f, radius - inset)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = card.border.toColor() }
            canvas.drawRoundRect(RectF(inset, inset, bounds.right - inset, bounds.bottom - inset), corner, corner, edge)
        }
        canvas.restore()
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for ((text, rect) in listOf(label to arrangement.label, headline to arrangement.headline)) {
            if (text == null || rect == null) continue
            val lettering = text.image(scale) ?: run { bitmap.recycle(); return null }
            val place = RectF((rect.x * scale).toFloat(), (rect.y * scale).toFloat(), ((rect.x + rect.width) * scale).toFloat(), ((rect.y + rect.height) * scale).toFloat())
            canvas.drawBitmap(lettering, null, place, filter)
            lettering.recycle()
        }
        return bitmap
    }
}

/**
 * Draws the titles of a moment over a picture: each one's bitmap, made once
 * and kept, posed by its motion. The preview and the export use the same code
 * at their own sizes. Not for two threads at once: each makes its own.
 */
class TitlePainter(private val assets: AssetManager, private val pictures: OverlayPictures? = null) {
    private data class Key(val text: String, val x: Double, val y: Double, val size: Double, val design: TitleDesign?, val group: LabelHeadline?, val width: Float, val height: Float)

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
        arts.getOrPut(Key(title.text, title.x, title.y, title.size, title.appearance, title.labelHeadline, width, height)) { TitleRaster.art(assets, title, width, height) }

    /** Draws [shown] on a picture of [width] by [height] pixels. [level] dims every title, as a fade to black does. */
    fun draw(target: Canvas, width: Float, height: Float, shown: List<TitleMotionRanges.Shown>, level: Float = 1f) {
        for ((title, share, pose, image) in shown) {
            if (image != null) {
                picture(target, width, height, image, (share * level).toFloat())
                continue
            }
            if (title == null) continue
            val opacity = (share * level * pose.opacity).toFloat().coerceIn(0f, 1f)
            if (opacity <= 0f) continue
            if (title.appearance == null && title.labelHeadline == null && pose.copy(opacity = 1.0) == STILL) {
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

    /**
     * A picture laid over the clip, at its place and size in a picture of [width] by [height] pixels, as transparent
     * as its opacity, blended as it is encoded (YouRec/VideoToolCompositor.swift:405-408, :444-453). [share] is how
     * much of it shows, as for a title. One whose file cannot be read is left out.
     */
    private fun picture(target: Canvas, width: Float, height: Float, image: ImageOverlay, share: Float) {
        val opacity = (share * image.opacity).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0f) return
        val bitmap = pictures?.bitmap(image.filename) ?: return
        val at = image.frame(Size(bitmap.width.toDouble(), bitmap.height.toDouble()), Size(width.toDouble(), height.toDouble()))
        place.set(at.x.toFloat(), at.y.toFloat(), (at.x + at.width).toFloat(), (at.y + at.height).toFloat())
        paint.alpha = Math.round(opacity * 255)
        target.drawBitmap(bitmap, null, place, paint)
    }
}
