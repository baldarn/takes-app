package app.takes.core

import java.text.BreakIterator
import java.util.Locale

/**
 * Select cover: the frames offered for the part of the movie the person is looking at, and the JPEG made of
 * the one chosen, as the original's picker (YouRec/CoverPicker.swift:8-80).
 */
object CoverPick {
    /** What the picker offers frames from, as its header names it (CoverPicker.swift:23-31). */
    enum class Scope { Range, Clips, Whole }

    /**
     * The frames of [scope] that show picture ([pool]), and the stretch the header shows, from the earliest
     * start to the latest end of the scope ([start], [end]).
     */
    class Source(val pool: CoverFramePool, val scope: Scope, val start: Double, val end: Double)

    /** The JPEG's quality, 0 to 100 (CoverPicker.swift:72, 0.95). */
    const val QUALITY = 95

    /** The longest side of a frame of the grid, in pixels (CoverPicker.swift:47). */
    const val THUMBNAIL_SIDE = 512

    /** The name of a JPEG whose project has no usable title; the original's is its own name (CoverPicker.swift:78). */
    const val FALLBACK_NAME = "Takes"

    /**
     * What [timeline] offers: the marked [range] when there is one, else the [video] and [audio] items selected,
     * else the whole project (CoverPicker.swift:22-33), over the time that shows picture ([picture]).
     */
    fun source(timeline: TrackTimeline, range: TimeRange?, video: Set<String> = emptySet(), audio: Set<String> = emptySet(), frameDuration: Double = 1.0 / 30): Source {
        val (scope, intervals) = when {
            range != null -> Scope.Range to listOf(CoverFramePool.Interval(range.start, range.end))
            video.isNotEmpty() || audio.isNotEmpty() -> Scope.Clips to
                (timeline.video.filter { it.id in video }.map { it.interval } + timeline.audio.filter { it.id in audio }.map { it.interval })
                    .map { CoverFramePool.Interval(it.start.seconds, it.end.seconds) }
            else -> Scope.Whole to listOf(CoverFramePool.Interval(0.0, timeline.duration.seconds))
        }
        // The frames lie on the composition's own clock (CoverPicker.swift:56-57); the editor's draws 30 a second.
        return Source(CoverFramePool(picture(timeline), intervals, frameDuration), scope, intervals.minOfOrNull { it.start } ?: 0.0, intervals.maxOfOrNull { it.end } ?: 0.0)
    }

    /**
     * Where [timeline] shows picture: its pictures, and after a picture a Still transition holds the time it
     * stands still for, which may reach past its clip, never past the project (CoverPicker.swift:34-39).
     */
    fun picture(timeline: TrackTimeline): List<CoverFramePool.Interval> {
        val pictures = timeline.video.map { CoverFramePool.Interval(it.start.seconds, it.end.seconds) }
        val stills = timeline.transitions.filter { it.settings.style == TransitionStyle.Still && it.outgoing != null }.map { join ->
            CoverFramePool.Interval(join.origin.seconds, minOf(timeline.duration.seconds, join.origin.seconds + join.timing.videoDuration))
        }
        return pictures + stills
    }

    /** Whether Update has other frames to show: more than one screen of choices (CoverPicker.swift:96). */
    fun canUpdate(pool: CoverFramePool): Boolean = pool.count > CoverFramePool.CHOICES

    /**
     * The narrowest a frame of the grid is, in points: wider with large text, narrower for an upright movie
     * (CoverPicker.swift:103).
     */
    fun columnWidth(aspect: Double, accessible: Boolean): Int = if (accessible) 170 else if (aspect < 1) 100 else 148

    /** The shape of the movie's frame, width over height; 16:9 for a canvas without a size (CoverPicker.swift:20). */
    fun aspect(width: Int, height: Int): Double = if (width > 0 && height > 0) width.toDouble() / height else 16.0 / 9.0

    /**
     * The size a frame of the grid is drawn at: the canvas made to fit [THUMBNAIL_SIDE] by [THUMBNAIL_SIDE], never
     * larger (CoverPicker.swift:47), its sides even for the encoder that draws it.
     */
    fun thumbnail(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, THUMBNAIL_SIDE.toDouble() / width, THUMBNAIL_SIDE.toDouble() / height)
        fun even(side: Int) = maxOf(2, Math.round(side * scale / 2).toInt() * 2)
        return even(width) to even(height)
    }

    /**
     * A cover's time: minutes, seconds and hundredths, the seconds rounded to the hundredth
     * (YouRec/CoverPicker.swift:6-9, `coverTimestamp`).
     */
    fun label(seconds: Double): String {
        val hundredths = Math.round(maxOf(0.0, if (seconds.isFinite()) seconds else 0.0) * 100)
        return String.format(Locale.ROOT, "%d:%02d.%02d", hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
    }

    /**
     * The picture a horizontal swipe on the cover's detail leads to: one frame for every 24 points of travel from
     * where the drag began, later to the left, held inside the pool (CoverPicker.swift:198-213).
     */
    fun scrubbed(origin: Int, travel: Double, count: Int): Int =
        (origin - (travel / SWIPE_POINTS).toInt()).coerceIn(0, maxOf(0, count - 1))

    /** The travel of a swipe for one frame, in points (CoverPicker.swift:212). */
    const val SWIPE_POINTS = 24.0

    /**
     * The JPEG's name: the project's [title] without the characters a file name cannot hold, in 40 characters at
     * most, and the [time] of the frame (CoverPicker.swift:73-78).
     */
    fun fileName(title: String, time: Double): String {
        // Split at slashes, colons, backslashes and control and format characters (CharacterSet.controlCharacters is Cc and Cf).
        val name = title.split(Regex("[/:\\\\\\p{Cc}\\p{Cf}]")).filter { it.isNotEmpty() }.joinToString("-").trim()
        val stamp = label(time).replace(':', '-')
        return graphemes(name.ifEmpty { FALLBACK_NAME }, 40) + " - " + stamp + ".jpg"
    }

    /** The first [count] characters of [text] as a reader sees them, so that an emoji or an accent is not cut in two (Swift's `prefix`). */
    private fun graphemes(text: String, count: Int): String {
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var end = 0
        repeat(count) { val next = breaks.next(); if (next == BreakIterator.DONE) return text; end = next }
        return text.substring(0, end)
    }
}
