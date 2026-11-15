package app.takes.core

import app.takes.core.TrackInvariants.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The checks of the original's `Tests/TrackTimelineTests.swift` that concern
 * the model, its construction from clips and its invariants, with the
 * original's numbers. Each names the lines it comes from. The checks of the
 * same file that need an editing operation (split, remove, move, link, paste,
 * enhance, setting a transition) wait for those operations.
 */
class TrackTimelineOriginalTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)

    // Tests/TrackTimelineTests.swift:13-16.
    private val source = SourceMedia(id = TrackFixtures.uuid(1), filename = "source.mp4", duration = 4.0)
    private val a = TimelineClip(TrackFixtures.uuid(2), source.id, 0.0, 2.0)
    private val b = TimelineClip(TrackFixtures.uuid(3), source.id, 2.0, 4.0)
    private val sequential = EditState(listOf(a, b))
    private val baseline = sequential.toTimeline()

    /** Tests/TrackTimelineTests.swift:18. */
    @Test
    fun theConvertedBaselineIsValid() {
        baseline.validate(listOf(source))
    }

    /** Tests/TrackTimelineTests.swift:19: "Conversion gives stable AV/link identities". */
    @Test
    fun conversionGivesStablePictureSoundAndLinkIdentities() {
        assertEquals(baseline, sequential.toTimeline())
        assertEquals(listOf(a.id, b.id), baseline.links.map { it.id })
        assertEquals(baseline.video.map { setOf(it.id, TrackTimeline.counterpart(it.id)) }, baseline.links.map { it.members })
    }

    /** Tests/TrackTimelineTests.swift:20: "Independent timeline preserves complete source duration". */
    @Test
    fun theTimelineKeepsTheWholeSourceDuration() {
        assertEquals(ticks(4.0), baseline.duration)
    }

    /** Tests/TrackTimelineTests.swift:113-118: "Capture bookmarks import once and exclude unrecovered footage". */
    @Test
    fun captureBookmarksAreAddedOnceAndNotPastTheRecordedLength() {
        val capture = source.copy(events = listOf(CaptureEvent(time = 1.2, kind = "bookmark", detail = "Bookmark"), CaptureEvent(time = 4.1, kind = "bookmark", detail = "After recovery end")))
        val marks = baseline.includingCaptureBookmarks(capture).includingCaptureBookmarks(capture)
        assertEquals(1, marks.resolvedBookmarks.size)
        assertEquals(ticks(1.2), marks.resolvedBookmarks[0].time)
        assertEquals(capture.events!![0].id, marks.bookmarks.single().id)
        assertEquals(BookmarkAnchor.Source(a.id, source.id, 1.2), marks.bookmarks.single().anchor)
    }

    /**
     * Tests/TrackTimelineTests.swift:119-121: "Independent positions, links
     * and bookmarks round-trip exactly". The original encodes the timeline it
     * has after a split at 1 s and a move by 4 s (:76-81): three pictures,
     * three sounds, three links and one bookmark. The same timeline is
     * written out here, since split and move are not ported yet.
     */
    @Test
    fun positionsLinksAndBookmarksAreWrittenAndReadBackExactly() {
        val left = a.copy(end = 1.0)
        val right = TimelineClip(TrackFixtures.uuid(4), source.id, 1.0, 2.0)
        fun pair(clip: TimelineClip, start: Double, origin: Double): Pair<VideoTimelineItem, AudioTimelineItem> {
            val picture = VideoTimelineItem.of(a.id, ticks(start), clip, layer = 0).copy(localOrigin = ticks(origin))
            return picture to AudioTimelineItem(
                id = TrackTimeline.counterpart(clip.id), occurrenceID = a.id, sourceID = source.id, start = picture.start,
                mapping = picture.mapping, localOrigin = picture.localOrigin, followsVideo = clip.id,
            )
        }
        val pairs = listOf(pair(left, 0.0, 0.0), pair(right, 5.0, 1.0)) + (baseline.video[1] to baseline.audio[1])
        val marks = TrackTimeline(
            video = pairs.map { it.first }, audio = pairs.map { it.second },
            links = pairs.map { TimelineLink(it.first.id, setOf(it.first.id, it.second.id)) },
            bookmarks = listOf(TimelineBookmark(TrackFixtures.uuid(5), anchor = BookmarkAnchor.Source(a.id, source.id, 1.0))),
        )
        // Tests/TrackTimelineTests.swift:77-81: the mark is not duplicated by the split and follows the piece that holds its second.
        assertEquals(listOf(ticks(5.0)), marks.resolvedBookmarks.map { it.time })
        marks.validate(listOf(source))
        assertEquals(marks, TrackTimeline.decoded(marks.encoded()))
        assertEquals(marks.encoded(), TrackTimeline.decoded(marks.encoded()).encoded())
    }

    /** Tests/TrackTimelineTests.swift:122-125: "Canonical clock exactly represents standard frame durations". */
    @Test
    fun theClockHoldsTheStandardFrameDurations() {
        for (rate in listOf(24.0, 25.0, 30.0, 50.0, 60.0, 24000.0 / 1001, 30000.0 / 1001, 60000.0 / 1001)) {
            assertTrue("$rate", abs(ticks(1 / rate).seconds - 1 / rate) < 1e-10)
        }
    }

    /** Tests/TrackTimelineTests.swift:126-127, :131: "Source mapping preserves speed independently of placement". */
    @Test
    fun theMapKeepsAClipsSpeed() {
        val map = SourcePlaybackMap.of(a.copy(speed = SpeedSettings(rate = 1.5)))
        assertTrue(abs(map.source(ticks(0.5))!! - 0.75) < 0.00001)
        // The same through the conversion, wherever the picture is put.
        val converted = EditState(listOf(b, a.copy(speed = SpeedSettings(rate = 1.5)))).toTimeline()
        assertTrue(abs(converted.video[1].mapping.source(ticks(0.5))!! - 0.75) < 0.00001)
    }

    /**
     * Tests/TrackTimelineTests.swift:158-176: for every style, with and
     * without a hold of 1.5 s, with the sound 0.7 s early, on time and 0.7 s
     * late, the conversion against the layout from before the timeline
     * ("Transition conversion preserves full duration", "… preserves source
     * placement", both within 4 ticks), and the compiled timeline ("Compiler
     * covers transition holds and tails", "Compiler has no instruction gaps").
     * The oracle there, `LegacyTimelineLayout`, is [TransitionLayout.sequence].
     */
    @Test
    fun transitionsConvertToTheOldLayoutForEveryStyleHoldAndSoundOffset() {
        for (style in TransitionStyle.entries) {
            for (hold in listOf(0.0, 1.5)) {
                for (offset in listOf(-0.7, 0.0, 0.7)) {
                    val name = "$style, hold $hold, offset $offset"
                    val clips = listOf(
                        a.copy(
                            openingTransition = TransitionSettings(style = style, duration = 0.7, hold = hold, audioOffset = offset, audioBlend = 1.0),
                            transition = TransitionSettings(nextClipID = b.id, style = style, duration = 1.2, hold = hold, audioOffset = offset, audioBlend = 1.0),
                        ),
                        b.copy(transition = TransitionSettings(style = style, duration = 0.7, hold = hold, audioOffset = offset, audioBlend = 1.0)),
                    )
                    val oracle = TransitionLayout.sequence(clips.map { it.duration }, listOf(clips[0].openingTransition, clips[0].transition, clips[1].transition))
                    val converted = EditState(clips).toTimeline()
                    assertTrue(name, abs(converted.duration.seconds - oracle.duration) <= 4.0 / 240000)
                    for ((actual, expected) in converted.video.zip(oracle.placements)) {
                        assertTrue(name, abs(actual.start.seconds - expected.start) <= 4.0 / 240000)
                    }
                    val compiled = converted.compile()
                    assertEquals(name, EditTime.ZERO, compiled.first().interval.start)
                    assertEquals(name, converted.duration, compiled.last().interval.end)
                    compiled.zipWithNext().forEach { (before, after) -> assertEquals(name, before.interval.end, after.interval.start) }
                    // Beyond the original's check: what it converts to is valid and at rest, and comes back.
                    assertNull(name, TrackInvariants.violation(converted, listOf(source)))
                    assertEquals(name, converted, converted.pruned())
                    assertEquals(name, converted, converted.toSequence()!!.toTimeline())
                }
            }
        }
    }

    // Two pictures that play at once, as the original's placement tests have them.
    private val overlapping = baseline.copy(
        video = listOf(baseline.video[0], baseline.video[1].copy(start = EditTime.ZERO, layer = 1)),
        audio = listOf(baseline.audio[0], baseline.audio[1].copy(start = EditTime.ZERO)),
    )

    /**
     * Tests/TrackTimelineTests.swift:322-323: an edge crop of 0.8 from the
     * left and 0.7 from the right leaves nothing of the picture, and the
     * timeline that holds it is refused.
     */
    @Test
    fun aPlacementWhoseCropLeavesNothingIsRefused() {
        fun placed(crop: PlacementEdgeCrop?) = overlapping.copy(
            placements = listOf(VideoPlacementRegion("R", setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement(order = 1, edgeCrop = crop)))),
        )
        assertNull(TrackInvariants.violation(placed(PlacementEdgeCrop(left = 0.25)), listOf(source)))
        assertEquals(Violation.Placement, TrackInvariants.violation(placed(PlacementEdgeCrop(left = 0.8, right = 0.7)), listOf(source)))
        assertThrows(ProjectException::class.java) { placed(PlacementEdgeCrop(left = 0.8, right = 0.7)).validate(listOf(source)) }
    }

    /**
     * Tests/TrackTimelineTests.swift:956-961: "Validation rejects competing
     * transition slots" and "Pruning deterministically retains the latest
     * transition in each slot". There an opening and a close on two clips
     * are replaced by a transition between them, and the old two are put
     * back beside it.
     */
    @Test
    fun competingTransitionSlotsAreRefusedAndThePruneKeepsTheLatest() {
        val fadeIn = TransitionSettings(id = TrackFixtures.uuid(10), style = TransitionStyle.FadeColor, duration = 0.5)
        val fadeOut = TransitionSettings(id = TrackFixtures.uuid(11), style = TransitionStyle.FadeColor, duration = 0.5)
        val cut = TransitionSettings(id = TrackFixtures.uuid(12), nextClipID = b.id, duration = 0.5)
        // The close of the first clip and the opening of the second: the two slots the transition between them takes.
        val edgeFades = EditState(listOf(a.copy(transition = fadeOut), b.copy(openingTransition = fadeIn))).toTimeline()
        val replaced = EditState(listOf(a.copy(transition = cut), b)).toTimeline()
        assertEquals(listOf(cut.id), replaced.transitions.map { it.id })
        assertEquals(2, edgeFades.transitions.size)
        val duplicate = replaced.copy(transitions = replaced.transitions + edgeFades.transitions)
        assertEquals(Violation.TransitionSlot, TrackInvariants.violation(duplicate, listOf(source)))
        assertThrows(ProjectException::class.java) { duplicate.validate(listOf(source)) }
        val pruned = replaced.copy(transitions = edgeFades.transitions + replaced.transitions).pruned()
        assertEquals(listOf(cut.id), pruned.transitions.map { it.id })
        assertEquals(listOf(cut.id), pruned.generated.map { it.owner })
        // Tests/TrackTimelineTests.swift:955: "Rendering sees only the replacement transition".
        assertTrue(pruned.compile().filter { it.transitionIDs.isNotEmpty() }.all { it.transitionIDs == listOf(cut.id) })
    }
}
