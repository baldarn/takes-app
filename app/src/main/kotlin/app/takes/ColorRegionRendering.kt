@file:OptIn(UnstableApi::class)

package app.takes

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import app.takes.core.ColorGrade
import app.takes.core.ColorSettings
import app.takes.core.LUTInput
import app.takes.core.TimelineClip
import app.takes.core.gradeTime
import java.io.File

/**
 * The grade of a clip graded over parts of it ([TimelineClip.colorRegions]),
 * in an export: each frame takes the grade in force at its second of the
 * footage, and none, or only the conversion of log footage, outside them, as
 * the original's compositor asks `grade(at:)` for every frame
 * (YouRec/VideoToolCompositor.swift:130-134, 193).
 *
 * Media3 gives an item a fixed list of effects, so each grade is a pass that
 * draws its table inside its regions and the identity outside them; exactly
 * one pass draws a table at any frame. The passes work on SDR, so such a clip
 * is graded as SDR in an HDR movie, and a grade that moves inside a region is
 * drawn at its first pose (docs/PARITY.md, section 13).
 */
internal object ColorRegionRendering {
    /** The table that leaves every color as it is, laid out as [lutTexture] reads one. */
    private val identity: FloatArray by lazy {
        val n = ColorGrade.DIMENSION
        FloatArray(n * n * n * 3).also { table ->
            for (blue in 0 until n) for (green in 0 until n) for (red in 0 until n) {
                val index = ((blue * n + green) * n + red) * 3
                table[index] = red / (n - 1f)
                table[index + 1] = green / (n - 1f)
                table[index + 2] = blue / (n - 1f)
            }
        }
    }

    /** Whether [clip] needs these passes in place of one look. */
    fun applies(clip: TimelineClip): Boolean = clip.color == null && !clip.colorRegions.isNullOrEmpty()

    /** The passes for [clip], which begins at second [begins] of its sequence; [log] is the footage's log curve, if any. */
    fun passes(clip: TimelineClip, begins: Double, folder: File, log: LUTInput?): List<Effect> {
        val regions = clip.colorRegions.orEmpty()
        fun source(us: Long): Double = clip.gradeTime((us / 1e6 - begins).coerceIn(0.0, clip.duration))
        val result = ArrayList<Effect>()
        for ((grade, spans) in regions.groupBy { it.grade }) {
            val shown = grade.motion?.let { grade.applying(it.a) } ?: grade
            val table = ColorLuts.table(shown, folder, log)
            result += MovingColorLut(identity, table, hdr = false) { us -> val t = source(us); if (spans.any { t >= it.start && t < it.end }) 1.0 else 0.0 }
        }
        // Outside every region log footage still gets its conversion, the natural grade's.
        if (log != null) {
            val table = ColorLuts.table(ColorSettings(), folder, log)
            result += MovingColorLut(identity, table, hdr = false) { us -> val t = source(us); if (regions.none { t >= it.start && t < it.end }) 1.0 else 0.0 }
        }
        return result
    }
}
