package app.takes.core

import app.takes.core.TrackFixtures.clip
import app.takes.core.TrackFixtures.second
import app.takes.core.TrackFixtures.sources
import app.takes.core.TrackFixtures.take
import app.takes.core.TrackFixtures.uuid
import app.takes.core.TrackInvariants.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a timeline is refused for, and what the prune repairs (docs/TRACKS-PLAN.md, 1.5 and 1.3). */
class TrackInvariantsTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)
    private val a = clip(1, take, 0.0, 12.5)
    private val b = clip(2, second, 0.0, 8.25)
    private val c = clip(3, take, 20.0, 31.0)
    private val dissolve = TransitionSettings(id = uuid(10), nextClipID = b.id, duration = 1.0)
    private val timeline = EditState(listOf(a.copy(transition = dissolve), b, c)).toTimeline()
    private val sound = timeline.audio[0]

    private fun violation(changed: TrackTimeline) = TrackInvariants.violation(changed, sources)
    private fun sounds(first: AudioTimelineItem) = timeline.copy(audio = listOf(first) + timeline.audio.drop(1))
    private fun pictures(first: VideoTimelineItem) = timeline.copy(video = listOf(first) + timeline.video.drop(1))

    // MARK: Converted edits

    @Test
    fun everyConvertedEditIsValidAndAtRest() {
        for ((name, state) in TrackFixtures.all) {
            val converted = state.toTimeline()
            assertNull(name, TrackInvariants.violation(converted, sources))
            assertEquals(name, converted, converted.pruned())
            converted.validate(sources)
        }
    }

    @Test
    fun randomSequencesWithTransitionsConvertToTimelinesAtRest() {
        val random = TrackFixtures.Random(17)
        val styles = TransitionStyle.entries
        repeat(5_000) { round ->
            val count = 1 + random.below(8)
            var clips = List(count) { index ->
                val start = random.next(0.0, 30.0)
                TimelineClip(uuid(1000 + index), take.id, start, start + random.next(0.3, 9.0))
            }
            clips = clips.mapIndexed { index, item ->
                fun settings(id: Int, next: String?) = TransitionSettings(
                    id = uuid(id + index), nextClipID = next, style = styles[random.below(styles.size)], duration = random.next(0.05, 4.0),
                    hold = if (random.next() < 0.4) random.next(0.0, 1.5) else null,
                    audioOffset = if (random.next() < 0.4) random.next(-0.7, 0.7) else null, audioBlend = if (random.next() < 0.4) random.next(0.0, 1.0) else null,
                    stillDissolve = if (random.next() < 0.3) 0.3 else null,
                )
                item.copy(
                    openingTransition = if (random.next() < 0.2) settings(4000, null) else null,
                    transition = if (random.next() < 0.6) settings(5000, if (random.next() < 0.8) clips.getOrNull(index + 1)?.id else null) else null,
                )
            }
            // Half of them as an old project has them, without saved fits.
            if (round % 2 == 0) clips = TimelineEditing.fitted(clips)
            val converted = EditState(clips).toTimeline()
            assertNull("round $round", TrackInvariants.violation(converted, sources))
            assertEquals("round $round", converted, converted.pruned())
        }
    }

    // MARK: Refusals

    @Test
    fun itemsAreUniqueAcrossPicturesAndSounds() {
        assertEquals(Violation.DuplicateItem, violation(timeline.copy(video = timeline.video + timeline.video[0])))
        assertEquals(Violation.DuplicateItem, violation(sounds(sound.copy(id = b.id))))
    }

    @Test
    fun theTimelineIsNoLongerThanAWeek() {
        val far = TimelineGeneratedRegion("G", "T", EditInterval(EditTime.ZERO, EditTime((EditTime.LIMIT.toLong() + 1) * EditTime.SCALE)))
        assertEquals(Violation.Duration, violation(timeline.copy(generated = timeline.generated + far)))
    }

    @Test
    fun anItemStartsAtZeroOrLaterAndReadsInsideItsSource() {
        assertEquals(Violation.Start, violation(pictures(timeline.video[0].copy(start = EditTime(-1)))))
        assertEquals(Violation.Start, violation(sounds(sound.copy(start = EditTime(-1)))))
        fun mapped(vararg runs: PlaybackRun) = violation(pictures(timeline.video[0].copy(mapping = SourcePlaybackMap(runs.toList()))))
        assertEquals(Violation.Mapping, mapped())
        assertEquals(Violation.Mapping, mapped(PlaybackRun(EditTime.ZERO, 0.0, 1.0)))
        assertEquals(Violation.Mapping, mapped(PlaybackRun(ticks(1.0), -0.5, 1.0)))
        assertEquals(Violation.Mapping, mapped(PlaybackRun(ticks(1.0), 2.0, 1.0)))
        assertEquals(Violation.Mapping, mapped(PlaybackRun(ticks(1.0), 0.0, Double.NaN)))
        assertEquals(Violation.Mapping, mapped(PlaybackRun(ticks(1.0), 0.0, take.duration + 0.002)))
        assertEquals(Violation.Mapping, violation(sounds(sound.copy(sourceID = "unknown"))))
        // A hold is a run, and the source may be read a thousandth past its measured length.
        val held = SourcePlaybackMap(listOf(PlaybackRun(ticks(1.0), 3.0, 3.0), PlaybackRun(ticks(1.0), 59.0, take.duration + 0.001)))
        assertNull(violation(timeline.copy(video = timeline.video + VideoTimelineItem("H", ticks(40.0), clip(7, take, 0.0, 1.0), mapping = held))))
    }

    @Test
    fun aSoundsLevelAndFadesAreInRange() {
        assertEquals(Violation.Level, violation(sounds(sound.copy(volumeDB = 12.5))))
        assertEquals(Violation.Level, violation(sounds(sound.copy(volumeDB = -60.5))))
        assertEquals(Violation.Level, violation(sounds(sound.copy(volumeDB = Double.NaN))))
        assertEquals(Violation.Fade, violation(sounds(sound.copy(fadeIn = AudioEdgeFade(0.0)))))
        assertEquals(Violation.Fade, violation(sounds(sound.copy(fadeOut = AudioEdgeFade(1.0, 1.0)))))
        assertNull(violation(sounds(sound.copy(volumeDB = 12.0, fadeIn = AudioEdgeFade(30.0, 29.0)))))
    }

    @Test
    fun aLinkHasTwoMembersThatExistAndAnItemIsInOneLink() {
        fun linked(vararg links: TimelineLink) = violation(timeline.copy(links = timeline.links + links))
        assertEquals(Violation.Link, linked(TimelineLink("L", setOf(c.id))))
        assertEquals(Violation.Link, linked(TimelineLink("L", setOf(c.id, "gone"))))
        assertEquals(Violation.Link, linked(TimelineLink("L", setOf(a.id, c.id))))
        assertEquals(Violation.DuplicateRelation, violation(timeline.copy(links = timeline.links.dropLast(1) + timeline.links[2].copy(id = a.id))))
    }

    @Test
    fun captionsAndWordsAreValid() {
        assertEquals(Violation.Subtitles, violation(timeline.copy(subtitles = SubtitleSettings(maxWords = 0))))
        fun worded(vararg words: SubtitleWord) = violation(sounds(sound.copy(subtitleWords = words.toList())))
        assertNull(worded(SubtitleWord("1", "p", "one", 0.0, 1.0), SubtitleWord("2", "p", "two", 59.0, 60.001)))
        assertEquals(Violation.Words, worded(SubtitleWord("1", "p", "one", 0.0, 1.0), SubtitleWord("1", "p", "two", 1.0, 2.0)))
        assertEquals(Violation.Words, worded(SubtitleWord("1", "p", "", 0.0, 1.0)))
        assertEquals(Violation.Words, worded(SubtitleWord("1", "p", "x".repeat(4001), 0.0, 1.0)))
        assertEquals(Violation.Words, worded(SubtitleWord("1", "p", "late", 59.0, 60.5)))
        assertEquals(Violation.Words, worded(SubtitleWord("1", "p", "backwards", 2.0, 1.0)))
    }

    @Test
    fun idsAreUniqueAmongRelationsOfAKind() {
        val mark = TimelineBookmark("M", anchor = BookmarkAnchor.Project(ticks(1.0)))
        assertEquals(Violation.DuplicateRelation, violation(timeline.copy(bookmarks = listOf(mark, mark))))
        val region = VideoPlacementRegion("R", setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement()))
        assertEquals(Violation.DuplicateRelation, violation(timeline.copy(placements = listOf(region, region.copy(participants = setOf(b.id, c.id), videos = mapOf(b.id to VideoPlacement(), c.id to VideoPlacement()))))))
        val twice = timeline.transitions[0].copy(outgoing = b.id, incoming = c.id)
        assertEquals(Violation.DuplicateRelation, violation(timeline.copy(transitions = timeline.transitions + twice)))
        // A bookmark and a link may share an id: they are different kinds.
        assertNull(violation(timeline.copy(bookmarks = listOf(mark.copy(id = a.id)))))
    }

    @Test
    fun aPlacementNamesItsPicturesAndHasAWindowForEach() {
        fun placed(participants: Set<String>, videos: Map<String, VideoPlacement>) = violation(timeline.copy(placements = listOf(VideoPlacementRegion("R", participants, videos))))
        assertNull(placed(setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement(x = 0.75, width = 0.5))))
        assertEquals(Violation.Placement, placed(setOf(a.id), mapOf(a.id to VideoPlacement())))
        assertEquals(Violation.Placement, placed(setOf(a.id, "gone"), mapOf(a.id to VideoPlacement(), "gone" to VideoPlacement())))
        assertEquals(Violation.Placement, placed(setOf(a.id, b.id), mapOf(a.id to VideoPlacement())))
        assertEquals(Violation.Placement, placed(setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement(), c.id to VideoPlacement())))
        assertEquals(Violation.Placement, placed(setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement(width = 3.5))))
        assertEquals(Violation.Placement, placed(setOf(a.id, sound.id), mapOf(a.id to VideoPlacement(), sound.id to VideoPlacement())))
    }

    @Test
    fun aPictureHasOneTransitionInAndOneOut() {
        val other = TransitionSettings(id = uuid(11), duration = 0.5)
        assertEquals(Violation.TransitionSlot, violation(timeline.copy(transitions = timeline.transitions + TimelineTransition(null, b.id, other, ticks(11.5)))))
        assertEquals(Violation.TransitionSlot, violation(timeline.copy(transitions = timeline.transitions + TimelineTransition(a.id, null, other, ticks(11.5)))))
        // One in and one out of the same picture is what a clip between two transitions has.
        assertNull(violation(timeline.copy(transitions = timeline.transitions + TimelineTransition(b.id, c.id, other, ticks(19.0)))))
    }

    @Test
    fun aTransitionNamesAPictureAndHasValidSettingsAndFactor() {
        fun related(relation: TimelineTransition) = violation(timeline.copy(transitions = listOf(relation)))
        val relation = timeline.transitions[0]
        assertEquals(Violation.Transition, related(relation.copy(outgoing = null, incoming = null)))
        assertEquals(Violation.Transition, related(relation.copy(incoming = "gone")))
        assertEquals(Violation.Transition, related(relation.copy(incoming = timeline.audio[1].id)))
        assertEquals(Violation.Transition, related(relation.copy(settings = dissolve.copy(duration = 0.0))))
        assertEquals(Violation.Transition, related(relation.copy(factor = 1.5)))
        assertEquals(Violation.Transition, related(relation.copy(factor = Double.NaN)))
        // Whether its pictures still meet is not a refusal: the prune decides.
        assertNull(related(relation.copy(origin = ticks(500.0))))
    }

    @Test
    fun aBookmarkPointsInsideItsSourceOrInsideAWeek() {
        fun marked(anchor: BookmarkAnchor, label: String = "") = violation(timeline.copy(bookmarks = listOf(TimelineBookmark("M", label, anchor))))
        assertNull(marked(BookmarkAnchor.Source("anything", take.id, 60.001), "x".repeat(500)))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Project(ticks(1.0)), "x".repeat(501)))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Source(a.id, take.id, 61.0)))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Source(a.id, take.id, -1.0)))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Source(a.id, "unknown", 1.0)))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Local(a.id, EditTime(-1))))
        assertEquals(Violation.Bookmark, marked(BookmarkAnchor.Project(EditTime((EditTime.LIMIT.toLong() + 1) * EditTime.SCALE))))
    }

    @Test
    fun aSoundThatFollowsAPictureIsLinkedToItAndHasItsTiming() {
        assertEquals(Violation.Follower, violation(sounds(sound.copy(followsVideo = "gone"))))
        assertEquals(Violation.Follower, violation(sounds(sound.copy(followsVideo = b.id))))
        assertEquals(Violation.Follower, violation(timeline.copy(links = timeline.links.drop(1))))
        assertEquals(Violation.Follower, violation(sounds(sound.copy(start = sound.start + EditTime(1)))))
        assertEquals(Violation.Follower, violation(sounds(sound.copy(mapping = SourcePlaybackMap.single(sound.mapping.duration - EditTime(1), 0.0, 12.5)))))
        // Unlinked, it may be anywhere.
        assertNull(violation(timeline.copy(audio = listOf(sound.copy(followsVideo = null, start = ticks(3.0))) + timeline.audio.drop(1), links = timeline.links.drop(1))))
    }

    @Test
    fun censorsAndChainsAreInOrderInsideTheItemAndValid() {
        fun censor(from: Double, to: Double) = AudioCensorRegion(newID(), EditInterval.of(from, to), ticks(from), AudioCensorSettings())
        assertNull(violation(sounds(sound.withCensors(listOf(censor(1.0, 2.0), censor(2.0, 3.0))))))
        assertEquals(Violation.Censor, violation(sounds(sound.withCensors(listOf(censor(1.0, 2.5), censor(2.0, 3.0))))))
        assertEquals(Violation.Censor, violation(sounds(sound.withCensors(listOf(censor(12.0, 13.0))))))
        fun chain(from: Double, to: Double, vararg effects: AudioEffect = arrayOf(AudioEffect.make(AudioEffectKind.Gain))) =
            AudioEnhancementRegion(newID(), EditInterval.of(from, to), effects.toList())
        assertNull(violation(sounds(sound.copy(enhancements = listOf(chain(4.0, 12.5), chain(0.0, 4.0))))))
        assertEquals(Violation.Enhancement, violation(sounds(sound.copy(enhancements = listOf(chain(0.0, 4.5), chain(4.0, 8.0))))))
        assertEquals(Violation.Enhancement, violation(sounds(sound.copy(enhancements = listOf(chain(4.0, 13.0))))))
        assertEquals(Violation.Enhancement, violation(sounds(sound.copy(enhancements = listOf(chain(4.0, 4.0))))))
        val stage = AudioEffect.make(AudioEffectKind.Gain, "G")
        assertEquals(Violation.Enhancement, violation(sounds(sound.copy(enhancements = listOf(chain(0.0, 4.0, stage, stage))))))
        assertEquals(Violation.Enhancement, violation(sounds(sound.copy(enhancements = listOf(chain(0.0, 4.0, stage.copy(amount = 30.0)))))))
    }

    @Test
    fun gapsOverlapsAndLayersAreNotRefused() {
        val moved = timeline.copy(
            video = timeline.video.map { if (it.id == c.id) it.copy(start = ticks(2.0), layer = 99) else it },
            audio = timeline.audio.map { if (it.followsVideo == c.id) it.copy(start = ticks(2.0)) else it },
        )
        assertNull(violation(moved))
        assertNull(violation(TrackTimeline()))
    }

    @Test
    fun validateThrowsTheProjectsRefusal() {
        val error = assertThrows(ProjectException::class.java) { timeline.copy(video = timeline.video + timeline.video[0]).validate(sources) }
        assertEquals(ProjectException.Reason.InvalidDocument, error.reason)
    }

    // MARK: The prune

    @Test
    fun aLinkLosesMembersThatAreGoneAndGoesWithUnderTwo() {
        val third = timeline.copy(links = timeline.links + TimelineLink("L", setOf(a.id, "gone", c.id)))
        assertEquals(TimelineLink("L", setOf(a.id, c.id)), third.pruned().links.last())
        val alone = timeline.copy(audio = timeline.audio.drop(1))
        assertEquals(listOf(b.id, c.id), alone.pruned().links.map { it.id })
    }

    @Test
    fun aSoundStopsFollowingAPictureItIsNoLongerLinkedTo() {
        val unlinked = timeline.copy(links = timeline.links.drop(1)).pruned()
        assertNull(unlinked.audio[0].followsVideo)
        assertEquals(b.id, unlinked.audio[1].followsVideo)
        assertNull(violation(unlinked))
    }

    @Test
    fun aPlacementGoesWhenItsPicturesNoLongerPlayAtOnce() {
        val region = VideoPlacementRegion("R", setOf(a.id, b.id), mapOf(a.id to VideoPlacement(), b.id to VideoPlacement()))
        assertEquals(listOf(region), timeline.copy(placements = listOf(region)).pruned().placements)
        val apart = VideoPlacementRegion("S", setOf(a.id, c.id), mapOf(a.id to VideoPlacement(), c.id to VideoPlacement()))
        assertTrue(timeline.copy(placements = listOf(apart)).pruned().placements.isEmpty())
        assertTrue(timeline.copy(placements = listOf(region), video = timeline.video.drop(1)).pruned().placements.isEmpty())
    }

    @Test
    fun aTransitionGoesWithItsPicture() {
        val gone = timeline.copy(video = timeline.video.drop(1)).pruned()
        assertTrue(gone.transitions.isEmpty() && gone.generated.isEmpty())
    }

    private fun moved(picture: String, by: EditTime) = timeline.copy(
        video = timeline.video.map { if (it.id == picture) it.copy(start = it.start + by) else it },
        audio = timeline.audio.map { if (it.followsVideo == picture) it.copy(start = it.start + by) else it },
    )

    @Test
    fun aTransitionIsFittedAgainToHowFarItsPicturesOverlap() {
        // The incoming picture is pushed 0.4 s later: 0.6 s of overlap is left of the dissolve's second.
        val pruned = moved(b.id, ticks(0.4)).pruned()
        val relation = pruned.transitions.single()
        assertEquals(0.6, relation.factor, 1e-6)
        assertEquals(0.6, relation.timing.overlap, 1e-6)
        assertEquals(pruned.video[0].end - ticks(relation.timing.leftEnd), relation.origin)
        assertEquals(listOf(relation.generated), pruned.generated)
        assertEquals(pruned, pruned.pruned())
    }

    @Test
    fun aTransitionGoesWhenNoFactorGivesTheOverlap() {
        // More overlap than the dissolve has at its longest, and none at all.
        assertTrue(moved(b.id, ticks(-0.5)).pruned().transitions.isEmpty())
        assertTrue(moved(b.id, ticks(3.0)).pruned().transitions.isEmpty())
        // Within a tick or two it is the same overlap and nothing is fitted again.
        assertEquals(1.0, moved(b.id, EditTime(1)).pruned().transitions.single().factor, 0.0)
    }

    @Test
    fun anOpeningAndACloseFollowTheirPicture() {
        val fades = TrackFixtures.fades().toTimeline()
        val middle = fades.video[1]
        val later = fades.copy(video = fades.video.map { if (it.id == middle.id) it.copy(start = it.start + ticks(30.0)) else it }).pruned()
        assertEquals(fades.transitions.map { it.origin + ticks(30.0) }, later.transitions.map { it.origin })
        assertEquals(later.transitions.map { it.generated }, later.generated)
    }

    @Test
    fun ofTwoTransitionsInOneSlotTheLaterStays() {
        val other = TransitionSettings(id = uuid(11), nextClipID = b.id, style = TransitionStyle.FadeColor, duration = 1.0)
        val relation = timeline.transitions[0]
        val both = timeline.copy(transitions = listOf(relation, relation.copy(settings = other)))
        val kept = TrackInvariants.retainingUniqueTransitionSlots(both.copy(generated = both.transitions.map { it.generated }))
        assertEquals(listOf(other.id), kept.transitions.map { it.id })
        assertEquals(listOf(other.id), kept.generated.map { it.owner })
        // An opening of the incoming picture claims the same slot as a transition into it.
        val opening = TimelineTransition(null, b.id, other, relation.origin)
        assertEquals(listOf(other.id), TrackInvariants.retainingUniqueTransitionSlots(timeline.copy(transitions = listOf(relation, opening))).transitions.map { it.id })
        assertEquals(listOf(dissolve.id), TrackInvariants.retainingUniqueTransitionSlots(timeline.copy(transitions = listOf(opening, relation))).transitions.map { it.id })
    }

    @Test
    fun aTimelineAtRestIsLeftAsItIs() {
        assertTrue(TrackInvariants.settled(timeline))
        val pruned = timeline.pruned()
        assertEquals(timeline, pruned)
        // Nothing is rebuilt that did not change.
        assertSame(timeline.links[0], pruned.links[0])
        assertSame(timeline.audio[0], pruned.audio[0])
    }
}
