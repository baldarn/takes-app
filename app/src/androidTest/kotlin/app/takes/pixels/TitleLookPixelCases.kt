package app.takes.pixels

import android.graphics.Bitmap
import app.takes.CardText
import app.takes.StyledText
import app.takes.TitleRaster
import app.takes.core.LabelHeadline
import app.takes.core.TextOverlay
import app.takes.core.TitleCard
import app.takes.core.TitleDesign
import app.takes.core.TitleInk
import app.takes.core.TitlePlate

/**
 * The built-in title looks drawn as the titles are (Tests/TitleDesignTests.swift:150-178): each with the words a
 * narrow canvas has to break, every one of its own appearance, and one whose background lets the footage through
 * keeping that opacity in the picture.
 */
class TitleLookPixelCases(private val bench: Bench) {
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun looks(checks: Checks) = with(checks) {
        val copy = "Città di notte\nVia Roma, 21:05"
        val outlined = TitleDesign(outlined = true, border = TitleInk.opaque(0xFF4040), borderWidth = 0.09)
        val rasters = mutableSetOf<List<Int>>()
        for ((_, name, preset) in TitleDesign.looks) {
            val layout = StyledText(bench.context.assets, copy, preset, 36f, 330f)
            val bitmap = layout.image(1f)
            check(bitmap != null) { "$name lost its picture on a narrow canvas" }
            bitmap ?: continue
            val argb = pixels(bitmap)
            if (preset.plate != TitlePlate.Off && preset.backing.alpha < 1) {
                val opacity = (preset.backing.rgba and 0xff).toInt()
                check(argb.any { (it ushr 24) == opacity }) { "$name lost its background opacity in the raster" }
            }
            check(rasters.add(argb.toList())) { "$name duplicates another rendered look" }
            // The border is a tool of its own: drawn the same with any look.
            check(outlined.applyingLook(preset).let { it.outlined && it.borderWidth == 0.09 }) { "$name must keep the user's outline" }
            bitmap.recycle()
        }
        note("${rasters.size} looks drawn, each its own")
    }

    /** A card laid out as a title of [fontSize] units on a 1280 by 720 picture is, drawn at one pixel a unit. */
    private fun card(group: LabelHeadline, headline: String = LabelHeadline.DEFAULT_HEADLINE, fontSize: Float = 88f, width: Float = 1280 * 0.94f) =
        CardText(bench.context.assets, group, headline, fontSize, width)

    // Tests/TitleDesignTests.swift:180-251
    private fun cards(checks: Checks) = with(checks) {
        val chapter = LabelHeadline()
        val rasters = mutableSetOf<List<Int>>()
        for ((name, look) in LabelHeadline.looks) {
            val layout = card(chapter.applyingLook(look), "After hours", 72f, 500f)
            val label = layout.arrangement.label
            val headline = layout.arrangement.headline
            val bitmap = layout.image(1280f, 720f, 1f)
            check(bitmap != null && label != null && headline != null) { "$name drew no card" }
            if (bitmap == null || label == null || headline == null) continue
            check(label.y + label.height <= headline.y) { "$name keeps the two lines distinct" }
            check(rasters.add(pixels(bitmap).toList())) { "$name has its own rendered appearance" }
            bitmap.recycle()
        }
        val layout = card(chapter)
        val image = layout.image(1280f, 720f, 1f)!!
        val argb = pixels(image)
        check((argb[0] ushr 24) == 0) { "Rounded transparent corners" }
        val margin = argb[image.width * (image.height / 2) + 4]
        check(kotlin.math.abs((margin ushr 24) - 139) < 3) { "Translucent card fill, ${margin ushr 24} where 139" }
        val moved = card(chapter.copy(card = chapter.card.copy(labelOffset = TitleCard.Offset(1.0, 1.0), headlineOffset = TitleCard.Offset(-1.0, -1.0))))
        check(!pixels(moved.image(1280f, 720f, 1f)!!).contentEquals(argb)) { "Offsets change the actual title bitmap" }
        check(pixels(card(chapter.copy(card = chapter.card.copy(labelOffset = null))).image(1280f, 720f, 1f)!!).contentEquals(argb)) { "Reset restores the original pixels" }
        val long = "A headline long enough to wrap onto several lines"
        val narrow = card(chapter, long, 56f, 260f)
        check(narrow.arrangement.headline!!.height > layout.arrangement.headline!!.height) { "The card grows around the complete headline in portrait" }
        val bare = card(chapter.copy(card = chapter.card.copy(fill = TitleInk(0, alpha = 0), borderWidth = 0.0))).image(1280f, 720f, 1f)!!
        val barePixels = pixels(bare)
        check((barePixels[bare.width * (bare.height / 2) + 4] ushr 24) == 0 && barePixels.any { (it ushr 24) == 255 }) { "Removing the card's fill and border keeps the two text styles" }
        val labelOnly = TextOverlay(text = "", labelHeadline = chapter)
        check(TitleRaster.art(bench.context.assets, labelOnly, 1280f, 720f) != null) { "A label-only card remains a title" }
        note("${rasters.size} card looks drawn, each its own")
    }

    fun all(): List<Case> = listOf(
        Case("Title looks: the 24 looks draw the whole text, each its own, a see-through background keeping its opacity") { looks(this) },
        Case("Title cards: a label above a headline on a rounded translucent card, the texts moved, wrapped and drawn without a card") { cards(this) },
    )
}
