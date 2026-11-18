package app.takes.pixels

import android.graphics.Bitmap
import app.takes.StyledText
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

    fun all(): List<Case> = listOf(
        Case("Title looks: the 24 looks draw the whole text, each its own, a see-through background keeping its opacity") { looks(this) },
    )
}
