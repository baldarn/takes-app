package app.takes

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * A row of tool buttons spread evenly, as a `Row` with `Arrangement.SpaceEvenly`,
 * that goes onto more lines when its buttons do not fit side by side at their
 * width on one line, instead of squeezing the last ones until their labels
 * break inside a word or fall off the screen. The lines are as even as they can
 * be: six buttons that need two lines go three and three. The original does
 * the same with its tool grid, four columns and two at accessibility sizes
 * (YouRec/EditorView.swift:902-905).
 */
@Composable
internal fun ToolRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Each button's width with its label on one line.
        val natural = measurables.map { min(it.maxIntrinsicWidth(Constraints.Infinity), width) }
        val lines = toolLines(natural, width)
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = natural[i], maxHeight = constraints.maxHeight)) }
        val heights = lines.map { line -> line.maxOf { placeables[it].height } }
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOf { line -> line.sumOf { natural[it] } }
        val height = max(heights.sum(), constraints.minHeight)
        layout(rowWidth, height) {
            var y = 0
            lines.forEachIndexed { l, line ->
                val gap = (rowWidth - line.sumOf { placeables[it].width }).coerceAtLeast(0) / (line.size + 1)
                var x = gap
                for (i in line) {
                    val p = placeables[i]
                    p.placeRelative(x, y + (heights[l] - p.height) / 2)
                    x += p.width + gap
                }
                y += heights[l]
            }
        }
    }
}

/** The buttons of each line: as few lines as fit [widths] into [available], filled as evenly as they can be. */
private fun toolLines(widths: List<Int>, available: Int): List<List<Int>> {
    fun fits(line: List<Int>) = line.sumOf { widths[it] } <= available
    // The fewest lines, filling each in turn.
    val greedy = mutableListOf<MutableList<Int>>()
    widths.indices.forEach { i ->
        val last = greedy.lastOrNull()
        if (last != null && fits(last + i)) last += i else greedy += mutableListOf(i)
    }
    if (greedy.size == 1) return greedy
    // The same number of lines with the buttons shared out evenly, when that fits.
    val per = (widths.size + greedy.size - 1) / greedy.size
    val even = widths.indices.chunked(per)
    return if (even.size == greedy.size && even.all(::fits)) even else greedy
}

/**
 * Tiles in [columns] equal columns, or in half as many, and half again, when a
 * tile does not fit its column: its label on one line, or when [wrapping] its
 * longest word, as labels allowed two lines. Every tile of a row is as tall
 * as the tallest. The original's grids go from four columns to two at
 * accessibility sizes (YouRec/EditorView.swift:902-905,
 * YouRec/TransitionEditor.swift:420).
 */
@Composable
internal fun FitGrid(
    columns: Int, modifier: Modifier = Modifier, spacing: Dp = 6.dp, rowSpacing: Dp = spacing, wrapping: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = spacing.roundToPx()
        val rowGap = rowSpacing.roundToPx()
        val needs = measurables.map { if (wrapping) it.minIntrinsicWidth(Constraints.Infinity) else it.maxIntrinsicWidth(Constraints.Infinity) }
        fun cell(count: Int) = ((width - gap * (count - 1)) / count).coerceAtLeast(0)
        var count = columns.coerceAtLeast(1)
        while (count > 1 && needs.any { it > cell(count) }) count = (count / 2).coerceAtLeast(1)
        val cellWidth = cell(count)
        val rows = measurables.indices.chunked(count)
        val placeables = rows.map { row ->
            val height = row.maxOf { measurables[it].minIntrinsicHeight(cellWidth) }
            row.map { measurables[it].measure(Constraints(cellWidth, cellWidth, height, Constraints.Infinity)) }
        }
        val heights = placeables.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + rowGap * (rows.size - 1).coerceAtLeast(0)
        layout(width, total.coerceIn(constraints.minHeight, maxOf(constraints.minHeight, constraints.maxHeight))) {
            var y = 0
            placeables.forEachIndexed { r, row ->
                row.forEachIndexed { c, p -> p.placeRelative(c * (cellWidth + gap), y) }
                y += heights[r] + rowGap
            }
        }
    }
}

/**
 * Whether text is at what iOS calls an accessibility size, which the original
 * lays out differently (`isAccessibilitySize`): one and a half times the
 * normal size or more, beyond the largest of the ordinary steps (1.3).
 */
@Composable
@ReadOnlyComposable
internal fun accessibilityText(): Boolean = LocalDensity.current.fontScale >= 1.5f

/**
 * A tab's name on one line, its type made smaller where a third of the width
 * cannot hold it at the person's text size, as "Recorder" at the largest
 * sizes, rather than broken inside the word.
 */
@Composable
internal fun TabLabel(text: String) {
    val style = LocalTextStyle.current
    Text(text, maxLines = 1, style = style, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize))
}
