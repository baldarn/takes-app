package app.takes.core

import java.text.Collator
import java.util.Locale

/**
 * Footage brought in from the phone's files and photos, as the original brings it in since it split its Import menu
 * and gave Add video a Files entry (YouRec/AddVideoPanel.swift:46-55, 107-116, YouRec/ContentView.swift:798-822,
 * YouRec/ProjectStore.swift:508-526, YouRec/OrderedMediaPicker.swift:93-97).
 */
object MediaImport {
    /**
     * [items] in the order of their file names, as Finder sorts them (`localizedStandardCompare`): letters without
     * regard to case or accents, and a run of digits by its number, so "clip 2" comes before "clip 10"
     * (AddVideoPanel.swift:111). Names that compare the same keep their order.
     */
    fun <T> byName(items: List<T>, locale: Locale = Locale.getDefault(), name: (T) -> String): List<T> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return items.sortedWith { a, b -> compareNames(name(a), name(b), collator) }
    }

    /** Two names compared piece by piece: runs of digits by their value, the text between by [collator]. */
    internal fun compareNames(a: String, b: String, collator: Collator): Int {
        val left = pieces(a)
        val right = pieces(b)
        for (index in 0 until minOf(left.size, right.size)) {
            val x = left[index]
            val y = right[index]
            val xDigits = x.first().isDigit()
            val yDigits = y.first().isDigit()
            val order = when {
                xDigits && yDigits -> {
                    val xs = x.trimStart('0').ifEmpty { "0" }
                    val ys = y.trimStart('0').ifEmpty { "0" }
                    if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
                }
                else -> collator.compare(x, y)
            }
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    private fun pieces(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val digits = text[start].isDigit()
            var end = start + 1
            while (end < text.length && text[end].isDigit() == digits) end++
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
