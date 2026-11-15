package app.takes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.pow

/**
 * One spoken word. Times are estimates, anchored in the original sound (source
 * seconds). Words of one caption share a [phraseID]. Each audio item owns its
 * words by value: splits, moves, copies and Undo cannot share mutable text.
 */
@Serializable
data class SubtitleWord(
    val id: String = newID(),
    val phraseID: String = newID(),
    val text: String,
    val start: Double,
    val end: Double,
) {
    val valid: Boolean
        get() = start.isFinite() && end.isFinite() && start >= 0 && end > start && text.isNotEmpty() && text.length <= MAXIMUM_TEXT

    companion object {
        /** The longest text one caption may be retyped to, as in the original's editor. */
        const val MAXIMUM_TEXT = 4000
    }
}

/**
 * How the whole caption series looks, and what was transcribed. One per project.
 * [x] and [font] are optional so projects written before direct caption
 * placement still load, centered in the system font.
 *
 * [appearance] and [motion] are the original app's title design and motion.
 * They are not modeled here yet; keeping them as raw JSON means a project
 * opened and saved by this port does not lose them. [valid] cannot check them.
 */
@Serializable
data class SubtitleSettings(
    val id: String = newID(),
    val maxWords: Int = 6,
    val style: Style = Style.Box,
    val color: Color = Color.White,
    // Font size relative to the shorter canvas dimension.
    val size: Double = 0.052,
    val y: Double = 0.86,
    val language: String = "Auto",
    /** The audio/video structure at transcription. Regrouping is refused once it no longer matches. */
    val timingFingerprint: String = "",
    val x: Double? = null,
    val font: SubtitleFont? = null,
    val appearance: JsonElement? = null,
    val motion: JsonElement? = null,
) {
    /** The original app's TextStyle. Nested until titles are ported and can share it. */
    @Serializable
    enum class Style {
        @SerialName("clean") Clean,
        @SerialName("bold") Bold,
        @SerialName("box") Box,
        @SerialName("highlight") Highlight,
    }

    /** The original app's TextColor. */
    @Serializable
    enum class Color {
        @SerialName("white") White,
        @SerialName("black") Black,
        @SerialName("yellow") Yellow,
        @SerialName("coral") Coral,
        @SerialName("mint") Mint,
        @SerialName("blue") Blue,
    }

    val centerX: Double get() = x ?: 0.5
    val resolvedFont: SubtitleFont get() = font ?: SubtitleFont.System

    val valid: Boolean
        get() = maxWords in MAX_WORDS && size.isFinite() && size in 0.0001..0.30 &&
            y.isFinite() && y in 0.0..1.0 && centerX.isFinite() && centerX in 0.0..1.0

    companion object {
        val MAX_WORDS = 1..16
    }
}

/** One caption as shown: the words of a phrase, in timeline seconds. */
data class SubtitleCue(
    val id: String,
    val audioID: String,
    val wordIDs: List<String>,
    val text: String,
    val start: Double,
    val end: Double,
)

/**
 * Word timing without an alignment model: the recognizer returns only text, so
 * word times are estimated inside the stretches where someone is speaking.
 */
object SubtitleTiming {
    /** A longer silence than this between two words starts a new caption. */
    const val PAUSE = 0.32

    /** Beyond this many words an edit is not matched word by word. */
    const val MATCH_LIMIT = 128

    private const val SENTENCE_END = ".!?。！？"

    /**
     * Words, each keeping the punctuation that follows it; leading punctuation
     * stays with the first word.
     *
     * The original uses Apple's NLTokenizer. BreakIterator finds the same word
     * starts in languages written with spaces. Without spaces the result depends
     * on the runtime: Android's BreakIterator is ICU and splits Chinese, Japanese
     * and Thai with a dictionary, as NLTokenizer does (not necessarily at the
     * same places); a desktop JVM has no dictionary and keeps a run of Han
     * characters between punctuation as one word.
     */
    fun tokens(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val boundaries = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(value) }
        val starts = mutableListOf<Int>()
        var from = boundaries.first()
        var to = boundaries.next()
        while (to != BreakIterator.DONE) {
            // Spaces and punctuation are segments too. Only a segment with a letter or a digit is a word.
            if (isWord(value, from, to)) starts += from
            from = to
            to = boundaries.next()
        }
        if (starts.isEmpty()) return listOf(value)
        starts[0] = 0
        return starts.indices.map { value.substring(starts[it], if (it + 1 < starts.size) starts[it + 1] else value.length).trim() }
            .filter { it.isNotEmpty() }
    }

    fun joined(words: List<String>): String = words.joinToString(" ")

    /**
     * Divide voiced time, never the intervening silence. Allocate each word to
     * one speech island before interpolating within that island. Longer words
     * take longer, less than proportionally.
     */
    fun estimate(text: String, speech: List<SourceInterval>): List<SubtitleWord> {
        val words = tokens(text)
        val spans = speech.filter { it.end > it.start }
        if (words.isEmpty() || spans.isEmpty()) return emptyList()
        val totalTime = spans.sumOf { it.end - it.start }
        val weights = words.map(::weight)
        val totalWeight = weights.sum()
        val grouped = List(spans.size) { mutableListOf<Int>() }
        var consumed = 0.0
        for (i in words.indices) {
            val midpoint = (consumed + weights[i] / 2) / totalWeight * totalTime
            var cursor = 0.0
            var island = spans.lastIndex
            for (j in spans.indices) {
                cursor += spans[j].end - spans[j].start
                if (midpoint < cursor) { island = j; break }
            }
            grouped[island] += i
            consumed += weights[i]
        }
        val result = mutableListOf<SubtitleWord>()
        for (j in spans.indices) {
            val sum = grouped[j].sumOf { weights[it] }
            var cursor = spans[j].start
            for (i in grouped[j]) {
                val end = minOf(spans[j].end, cursor + (spans[j].end - spans[j].start) * weights[i] / maxOf(sum, 0.001))
                result += SubtitleWord(text = words[i], start = cursor, end = end)
                cursor = end
            }
        }
        return result
    }

    /**
     * Gives every word the phrase it is shown with. A caption ends at [maxWords],
     * at a pause, or after a word that ends a sentence. Returns the words in time order.
     */
    fun group(input: List<SubtitleWord>, maxWords: Int): List<SubtitleWord> {
        val words = input.sortedBy { it.start }
        val result = ArrayList<SubtitleWord>(words.size)
        var group = newID()
        var count = 0
        for (i in words.indices) {
            if (i > 0 && (count >= maxWords || words[i].start - words[i - 1].end > PAUSE || endsSentence(words[i - 1].text))) {
                group = newID()
                count = 0
            }
            result += words[i].copy(phraseID = group)
            count++
        }
        return result
    }

    /**
     * The words of one caption after the user retyped it as [text].
     *
     * A longest common subsequence keeps unchanged words anchored. Replacements
     * share only the freed interval between neighboring surviving words. Full
     * rewrites retain the old phrase interval. Punctuation-only edits leave all
     * timings intact. This is a heuristic, not acoustic alignment.
     */
    fun corrected(words: List<SubtitleWord>, text: String): List<SubtitleWord> {
        val old = words.sortedBy { it.start }
        val next = tokens(text)
        if (old.isEmpty() || next.isEmpty()) return emptyList()
        val first = old.first()
        val last = old.last()
        // Bound work for a pasted paragraph, without quadratic allocation.
        if (old.size > MATCH_LIMIT || next.size > MATCH_LIMIT) {
            return estimate(joined(next.take(MATCH_LIMIT)), listOf(SourceInterval(first.start, last.end))).map { it.copy(phraseID = first.phraseID) }
        }
        val a = old.map { normalized(it.text) }
        val b = next.map(::normalized)
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1] else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val anchors = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { anchors += i to j; i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        val result = mutableListOf<SubtitleWord>()
        var priorOld = -1
        var priorNew = -1
        // The closing pair stands for the end of the phrase, so trailing new words are placed too.
        for ((oi, ni) in anchors + (old.size to next.size)) {
            if (ni > priorNew + 1) {
                var start = if (priorOld < 0) first.start else old[priorOld].end
                var end = if (oi == old.size) last.end else old[oi].start
                // Insertion between adjacent words has no empty interval. Borrow
                // a small part of the following/preceding word within the phrase.
                if (end - start < 0.04) {
                    if (oi < old.size) {
                        end = minOf(old[oi].end, start + (old[oi].end - old[oi].start) * 0.45)
                    } else {
                        val previous = result.lastOrNull()
                        if (previous != null) {
                            start = maxOf(previous.start, previous.end - (previous.end - previous.start) * 0.45)
                            result[result.lastIndex] = previous.copy(end = start)
                        }
                    }
                }
                val replacement = estimate(joined(next.subList(priorNew + 1, ni)), listOf(SourceInterval(start, maxOf(start, end))))
                result += replacement.map { it.copy(phraseID = first.phraseID) }
            }
            if (oi < old.size) {
                val start = maxOf(old[oi].start, result.lastOrNull()?.end ?: old[oi].start)
                if (old[oi].end > start) result += old[oi].copy(text = next[ni], start = start)
            }
            priorOld = oi
            priorNew = ni
        }
        return result
    }

    /**
     * The captions of one stretch of an audio item that plays source
     * [sourceStart]..[sourceEnd] during [duration] seconds from [timelineStart].
     * Words outside the stretch are left out and a caption is cut at its edges.
     * A phrase is also broken where its words are more than 0.4 s apart, which
     * happens after an edit removed the words in between.
     *
     * The original walks every run of an item's playback map; [runIndex] is the
     * run's position there and only makes the cue id unique.
     */
    fun cues(
        audioID: String,
        words: List<SubtitleWord>,
        timelineStart: Double,
        sourceStart: Double,
        sourceEnd: Double,
        duration: Double = sourceEnd - sourceStart,
        runIndex: Int = 0,
    ): List<SubtitleCue> {
        if (!(sourceEnd > sourceStart)) return emptyList()
        val scale = duration / (sourceEnd - sourceStart)
        val cues = mutableListOf<SubtitleCue>()
        var group = mutableListOf<SubtitleWord>()
        fun emit() {
            val first = group.firstOrNull() ?: return
            val start = timelineStart + (maxOf(sourceStart, first.start) - sourceStart) * scale
            val end = timelineStart + (minOf(sourceEnd, group.last().end) - sourceStart) * scale
            if (end > start) cues += SubtitleCue("$audioID-$runIndex-${first.id}", audioID, group.map { it.id }, joined(group.map { it.text }), start, end)
        }
        for (word in words.filter { it.end > sourceStart && it.start < sourceEnd }.sortedBy { it.start }) {
            val last = group.lastOrNull()
            if (last != null && (last.phraseID != word.phraseID || word.start - last.end > 0.4)) { emit(); group = mutableListOf() }
            group += word
        }
        emit()
        return cues
    }

    private fun isWord(text: String, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            val point = text.codePointAt(i)
            if (Character.isLetterOrDigit(point)) return true
            i += Character.charCount(point)
        }
        return false
    }

    private fun endsSentence(text: String): Boolean = text.isNotEmpty() && text.last() in SENTENCE_END

    // Counted in code points; the original counts user-perceived characters.
    // They agree except for letters written as several code points.
    private fun weight(text: String): Double =
        maxOf(1, text.codePoints().filter(Character::isLetter).count().toInt()).toDouble().pow(0.65)

    /** Letters and numbers only, lowercase: "Here," and "here" are the same word. */
    private fun normalized(text: String): String {
        val result = StringBuilder()
        text.lowercase().codePoints().forEach { if (Character.isLetter(it) || isNumber(it)) result.appendCodePoint(it) }
        return result.toString()
    }

    // Swift's isNumber also accepts fractions and circled digits, not only decimal digits.
    private fun isNumber(point: Int): Boolean = when (Character.getType(point).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}
