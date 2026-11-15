package app.takes.core

import app.takes.core.TimelinePasteTarget.Anchor
import app.takes.core.TimelinePasteTarget.Kind
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy, Cut and Paste: all of the original's `Tests/TimelineClipboardChecks.swift`
 * and the clipboard checks of `Tests/TrackTimelineTests.swift`, with their
 * numbers and lines. Its check of Undo across a saved project (`:66-72`)
 * waits for a project that holds a timeline. Where the original uses an
 * operation that is not in these files (adding a sound, a fade, a chain,
 * setting a transition), the same state is written out, or made in the
 * sequence form and converted.
 */
class TrackClipboardTest {
    private fun t(seconds: Double) = EditTime.of(seconds)

    // Tests/TimelineClipboardChecks.swift:9-16.
    private val source = SourceMedia(id = uuid(1), filename = "clipboard.mov", duration = 10.0)
    private val sources = listOf(source)
    private val clips = (0 until 5).map { TimelineClip(uuid(10 + it), source.id, it * 2.0, it * 2.0 + 2) }
    private val plain = EditState(clips).toTimeline()
    private val indices = clips.indices.toList()

    private fun boundary(timeline: TrackTimeline, second: Double, kind: Kind = Kind.Picture): TimelinePasteTarget? =
        timeline.pasteTargets().firstOrNull { it.kind == kind && it.isAt(t(second)) }

    private fun order(timeline: TrackTimeline) = timeline.chronologicalVideo.map { (it.content.start / 2).toInt() }

    private fun settled(timeline: TrackTimeline) {
        assertNull(TrackInvariants.violation(timeline, sources))
        assertEquals(timeline, timeline.pruned())
    }

    /** The last scene with a bookmark, a caption word, a fade, a chain and a title (:43-48). */
    private val title = TextOverlay(id = uuid(30), text = "Last scene", start = 8.0, end = 9.0)
    private val base: TrackTimeline = plain.addingBookmark(t(8.75)).let { marked ->
        val sound = marked.audio[4]
        marked.copy(
            audio = marked.audio.dropLast(1) + sound.copy(
                subtitleWords = listOf(SubtitleWord(text = "Last", start = 8.2, end = 8.8)),
                fadeIn = AudioEdgeFade.of(0.25, sound.mapping.duration),
                enhancements = listOf(AudioEnhancementRegion(interval = EditInterval(EditTime.ZERO, sound.mapping.duration), effects = listOf(AudioEffect(kind = AudioEffectKind.Gain, amount = -3.0)))),
            ),
            video = marked.video.dropLast(1) + marked.video[4].let { it.copy(content = it.content.copy(texts = listOf(title))) },
        )
    }
    private val late = base.copied(base.selection(clips[4].id))

    private fun score(id: Int = 40) = AudioTimelineItem(uuid(id), uuid(id + 1), source.id, EditTime.ZERO, SourcePlaybackMap.single(t(10.0), 0.0, 10.0))

    private val dissolve = TransitionSettings(id = uuid(50), duration = 0.5)
    private val blend = TimelineEditing.setJoin(EditState(clips), 2, dissolve)!!.toTimeline()
    private val join = TrackLayout.of(blend).joins.first { it.transition != null }

    // MARK: Tests/TrackTimelineTests.swift:103-112

    /** "Linked clipboard inserts time and keeps both components". */
    @Test
    fun aLinkedCopyOpensTimeAndBringsPictureAndSound() {
        val source = SourceMedia(id = uuid(1), filename = "source.mp4", duration = 4.0)
        val a = TimelineClip(uuid(2), source.id, 0.0, 2.0)
        val b = TimelineClip(uuid(3), source.id, 2.0, 4.0)
        val baseline = EditState(listOf(a, b)).toTimeline()
        val copy = baseline.copied(baseline.selection(a.id))
        val pasted = baseline.pasting(copy, t(1.0))
        assertTrue(copy.insertsTime)
        assertEquals(t(6.0), pasted.timeline.duration)
        assertEquals(1, pasted.selection.video.size)
        assertEquals(1, pasted.selection.audio.size)
        assertNull(TrackInvariants.violation(pasted.timeline, listOf(source)))
        assertEquals(pasted.timeline, pasted.timeline.pruned())
        // The clip under the playhead is cut, and its later piece comes after the pasted one.
        assertEquals(listOf(t(0.0), t(1.0), t(3.0), t(4.0)), pasted.timeline.video.map { it.start })

        // "Audio paste overlays sound without shifting picture"
        val separate = baseline.unlinked(baseline.selection(a.id))
        val soundCopy = separate.copied(separate.selection(baseline.audio[0].id))
        val overlaid = separate.pasting(soundCopy, t(1.0))
        assertFalse(soundCopy.insertsTime)
        assertEquals(separate.video, overlaid.timeline.video)
        assertTrue(overlaid.selection.video.isEmpty())
        assertEquals(1, overlaid.selection.audio.size)
        assertEquals(separate.duration, overlaid.timeline.duration)
        assertEquals(t(1.0), overlaid.timeline.audio.last().start)
        assertNull(TrackInvariants.violation(overlaid.timeline, listOf(source)))
    }

    // MARK: Tests/TimelineClipboardChecks.swift

    /**
     * Tests/TimelineClipboardChecks.swift:17-41: every choice of scenes out
     * of five, copied into every slot and cut and pasted into every slot:
     * "Disjoint scenes must pack in playback order", "Copy into slot … must
     * keep the originals", "Cut removes only the selected scenes", "Cut and
     * paste must reconstruct a complete sequence, including an empty
     * destination".
     */
    @Test
    fun anyScenesCopiedOrCutGoIntoAnySlotInOrder() {
        for (mask in 1 until (1 shl clips.size)) {
            val chosen = indices.filter { mask and (1 shl it) != 0 }
            val kept = indices.filter { it !in chosen }
            val scope = plain.selection(chosen.mapTo(HashSet()) { clips[it].id })
            val copy = plain.copied(scope)
            assertEquals(t(chosen.size * 2.0), copy.timeline.duration)
            for (slot in 0..clips.size) {
                val time = slot * 2.0
                val result = plain.pasting(copy, t(time), boundary(plain, time))
                assertEquals("mask $mask slot $slot", indices.take(slot) + chosen + indices.drop(slot), order(result.timeline))
                assertTrue(result.selection.ids.none { it in plain.allIDs })
                settled(result.timeline)
            }
            val cut = TrackClipboard.cut(plain, scope, EditTime.ZERO) ?: error("Cut unexpectedly empty")
            assertEquals(kept, order(cut.timeline))
            for (slot in 0..kept.size) {
                val time = slot * 2.0
                val result = cut.timeline.pasting(cut.clipboard, t(time), boundary(cut.timeline, time)).timeline
                assertEquals("mask $mask slot $slot", kept.take(slot) + chosen + kept.drop(slot), order(result))
                assertEquals(plain.duration, result.duration)
                settled(result)
            }
        }
    }

    /** Tests/TimelineClipboardChecks.swift:43-60: "Each paste has its own media, mark, and edit identities", "Pasted titles, captions, fades and enhancements remain editable independently". */
    @Test
    fun everyPasteIsACopyOfItsOwn() {
        val firstPaste = base.pasting(late, EditTime.ZERO, boundary(base, 0.0))
        val secondPaste = firstPaste.timeline.pasting(late, firstPaste.timeline.duration, boundary(firstPaste.timeline, firstPaste.timeline.duration.seconds))
        val layered = secondPaste.timeline
        assertTrue(firstPaste.selection.ids.none { it in secondPaste.selection.ids })
        assertEquals(listOf(t(0.75), t(10.75), t(12.75)), layered.resolvedBookmarks.map { it.time })
        val audio = layered.audio.first { it.id in firstPaste.selection.audio }
        val video = layered.video.first { it.id in firstPaste.selection.video }
        assertEquals("Last", audio.subtitleWords?.first()?.text)
        assertEquals(base.audio[4].fadeIn, audio.fadeIn)
        assertEquals(base.audio[4].enhancements.first().effects, audio.enhancements.first().effects)
        assertEquals("Last scene", video.content.texts?.first()?.text)
        assertNotEquals(title.id, video.content.texts?.first()?.id)
        // Nothing is shared with the original or with the other copy.
        val other = layered.audio.first { it.id in secondPaste.selection.audio }
        assertNotEquals(base.audio[4].subtitleWords!![0].id, audio.subtitleWords!![0].id)
        assertNotEquals(other.subtitleWords!![0].id, audio.subtitleWords!![0].id)
        assertNotEquals(base.audio[4].enhancements[0].id, audio.enhancements[0].id)
        assertNotEquals(base.video[4].occurrenceID, video.occurrenceID)
        assertEquals(3, layered.bookmarks.map { it.id }.toSet().size)
        assertEquals(video.id, audio.followsVideo)
        assertEquals(7, layered.links.map { it.id }.toSet().size)
        settled(layered)
    }

    /** Tests/TimelineClipboardChecks.swift:61-65: "Cut and the clipboard must use the same exact range fragments". */
    @Test
    fun aRangeCutTakesExactlyThePiecesItDeletes() {
        val piece = TrackClipboard.cut(base, base.entireSelection(EditInterval.of(1.0, 3.0)), t(8.0))!!
        assertEquals(t(6.0), piece.position)
        assertEquals(t(8.0), piece.timeline.duration)
        assertEquals(t(2.0), piece.clipboard.timeline.duration)
        settled(piece.timeline)
        // :68, the paste the original then undoes.
        val pasted = piece.timeline.pasting(piece.clipboard, t(0.5)).timeline
        assertEquals(t(10.0), pasted.duration)
        settled(pasted)
        assertNull(TrackClipboard.cut(base, TimelineSelection(), t(1.0)))
    }

    /** Tests/TimelineClipboardChecks.swift:74-80: "Choose layering on picture or inserting into the sound sequence". */
    @Test
    fun soundGoesOverThePictureOrIntoTheSoundSequence() {
        val detached = base.unlinked(base.entireSelection())
        val sound = detached.copied(detached.selection(detached.audio[4].id))
        val atPicture = detached.pasting(sound, t(2.0), boundary(detached, 2.0)).timeline
        val atSound = detached.pasting(sound, t(2.0), boundary(detached, 2.0, Kind.Sound)).timeline
        assertEquals(detached.video, atPicture.video)
        assertEquals(detached.audio, atPicture.audio.take(5))
        assertEquals(t(2.0), atPicture.audio[5].start)
        assertEquals(detached.video, atSound.video)
        assertEquals(t(4.0), atSound.audio[1].start)
        assertEquals(listOf(0.0, 4.0, 6.0, 8.0, 10.0, 2.0).map(::t), atSound.audio.map { it.start })
        settled(atPicture)
        settled(atSound)
    }

    /** Tests/TimelineClipboardChecks.swift:81-86: "Paste picture at a sound cut preserves detached music and splits dialogue in sync". */
    @Test
    fun pictureAtASoundCutCutsThePictureThereAndLeavesTheMusic() {
        val music = score()
        var mix = base.copy(audio = base.audio + music)
        mix = TrackEditing.split(mix, t(1.0), TimelineSelection(audio = setOf(music.id))).timeline
        val pasted = mix.pasting(late, t(1.0), boundary(mix, 1.0, Kind.Sound)).timeline
        assertTrue(pasted.video.any { it.occurrenceID == base.video[0].occurrenceID && it.start == t(3.0) })
        assertEquals(EditTime.ZERO, pasted.audio.first { it.id == music.id }.start)
        assertEquals(mix.audio.filter { it.sourceID == source.id && it.occurrenceID == music.occurrenceID }, pasted.audio.filter { it.occurrenceID == music.occurrenceID })
        settled(pasted)
    }

    /** Tests/TimelineClipboardChecks.swift:87-92: "Pasting picture into a dissolve recovers both source handles". */
    @Test
    fun pictureIntoADissolveGivesBothPicturesTheirFootageBack() {
        val separated = blend.pasting(late, EditTime.of(join.time), boundary(blend, join.time)).timeline
        assertTrue(separated.transitions.isEmpty())
        assertEquals(t(12.0), separated.duration)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0).map(::t), separated.chronologicalVideo.map { it.start })
        settled(separated)
    }

    /** Tests/TimelineClipboardChecks.swift:93-98: "An internal transition travels with both selected partners and keeps the real overlap". */
    @Test
    fun aTransitionTravelsOnlyWithBothItsPictures() {
        val both = blend.copied(blend.selection(setOf(clips[1].id, clips[2].id)))
        assertEquals(t(3.5), both.timeline.duration)
        val copied = plain.pasting(both, plain.duration)
        assertEquals(1, copied.timeline.transitions.size)
        val relation = copied.timeline.transitions[0]
        assertNotEquals(dissolve.id, relation.id)
        assertEquals(copied.selection.video, setOfNotNull(relation.outgoing, relation.incoming))
        assertEquals(relation.incoming, relation.settings.nextClipID)
        assertEquals(t(13.5), copied.timeline.duration)
        settled(copied.timeline)
        // One of the two alone leaves the transition behind.
        val one = blend.copied(blend.selection(clips[1].id))
        assertTrue(one.timeline.transitions.isEmpty() && one.timeline.generated.isEmpty())
        assertEquals(t(2.0), one.timeline.duration)
    }

    /** Tests/TimelineClipboardChecks.swift:100-104: "A marked range retains the intentional gap between included clips". */
    @Test
    fun aMarkedRangeKeepsTheGapBetweenItsClips() {
        val spaced = base.moved(base.selection(clips.takeLast(2).mapTo(HashSet()) { it.id }), t(2.0))
        val marked = spaced.copied(spaced.entireSelection(EditInterval.of(4.0, 10.0)))
        assertEquals(t(6.0), marked.timeline.duration)
        assertEquals(listOf(EditTime.ZERO, t(4.0)), marked.timeline.chronologicalVideo.map { it.start })
        // Chosen by name, the same two pack together.
        val packed = spaced.copied(spaced.selection(setOf(clips[2].id, clips[3].id)))
        assertEquals(listOf(EditTime.ZERO, t(2.0)), packed.timeline.chronologicalVideo.map { it.start })
    }

    /** Tests/TimelineClipboardChecks.swift:105-113: "A mid-shot scene paste must leave detached music continuous", "A mid-transition scene paste retains all source handles". */
    @Test
    fun aSceneInTheMiddleOfAShotLeavesMusicWholeAndInsideATransitionRemovesIt() {
        val music = score()
        val bed = base.copy(audio = base.audio + music)
        val pasted = bed.pasting(late, t(1.5)).timeline
        assertEquals(music, pasted.audio.first { it.id == music.id })
        assertEquals(t(12.0), pasted.duration)
        settled(pasted)

        val inside = blend.pasting(late, EditTime.of(join.time - 0.1)).timeline
        assertTrue(inside.transitions.isEmpty())
        assertEquals(t(12.0), inside.duration)
        settled(inside)
    }

    /** Tests/TimelineClipboardChecks.swift:115-123: "Overlapping picks retain offset and custom framing". */
    @Test
    fun overlappingPicturesKeepTheirOffsetAndTheirWindows() {
        val pip = VideoTimelineItem.of(uuid(60), t(8.5), TimelineClip(uuid(61), source.id, 0.0, 1.0), layer = 5)
        val third = VideoTimelineItem.of(uuid(62), t(8.6), TimelineClip(uuid(63), source.id, 0.0, 0.4), layer = 6)
        val window = VideoPlacement(width = 0.4, order = 2)
        val arrangement = base.copy(
            video = base.video + pip + third,
            placements = listOf(
                VideoPlacementRegion(participants = setOf(clips[4].id, pip.id, third.id), videos = mapOf(clips[4].id to VideoPlacement(), pip.id to window, third.id to VideoPlacement())),
            ),
        )
        settled(arrangement)
        val parts = arrangement.copied(arrangement.selection(setOf(clips[4].id, pip.id)))
        assertEquals(t(0.5), parts.timeline.video.first { it.id == pip.id }.start)
        assertEquals(window, parts.timeline.placements.first().videos[pip.id])
        assertEquals(setOf(clips[4].id, pip.id), parts.timeline.placements.single().participants)

        val pasted = arrangement.pasting(parts, arrangement.duration)
        val copy = pasted.timeline.placements.last()
        assertEquals(pasted.selection.video, copy.participants)
        assertEquals(setOf(window, VideoPlacement()), copy.videos.values.toSet())
        assertEquals(2, pasted.timeline.placements.map { it.id }.toSet().size)
        // The pasted pictures are in front of everything, in their own order.
        val layers = pasted.timeline.video.filter { it.id in pasted.selection.video }.sortedBy { it.start }.map { it.layer }
        assertEquals(listOf(7, 8), layers)
        // A cut keeps the windows of the pictures that stay.
        val cut = TrackClipboard.cut(arrangement, arrangement.selection(third.id), t(0.0))!!
        assertEquals(setOf(clips[4].id, pip.id), cut.timeline.placements.single().participants)
        assertEquals(window, cut.timeline.placements.single().videos[pip.id])
        settled(cut.timeline)
    }

    // MARK: Targets

    @Test
    fun theTargetsOfASequenceAreItsCutsItsStartAndItsEnd() {
        val targets = plain.pasteTargets()
        assertEquals(
            listOf(2.0, 4.0, 6.0, 8.0).mapIndexed { index, time -> TimelinePasteTarget(Kind.Picture, t(time), Anchor.Between(clips[index].id, clips[index + 1].id)) } +
                TimelinePasteTarget(Kind.Picture, EditTime.ZERO, Anchor.Start) + TimelinePasteTarget(Kind.Picture, t(10.0), Anchor.End),
            targets,
        )
        assertEquals(listOf("Video · V1 | V2", "Video · V2 | V3", "Video · V3 | V4", "Video · V4 | V5", "Start", "End"), targets.map { it.label(plain) })
        assertTrue(TrackTimeline().pasteTargets().isEmpty())
        // A dissolve is a target at its join, with its id.
        val cut = blend.pasteTargets().first { it.transition != null }
        assertEquals(TimelinePasteTarget(Kind.Picture, EditTime.of(join.time), Anchor.Between(clips[1].id, clips[2].id), dissolve.id), cut)
    }

    @Test
    fun independentSoundHasTargetsOfItsOwnAndTheTimelineItsOuterEdges() {
        // The picture begins a second late; music from zero, cut at 10 s, runs past the end of the picture.
        val music = AudioTimelineItem(uuid(40), uuid(41), source.id, EditTime.ZERO, SourcePlaybackMap.single(t(10.0), 0.0, 10.0))
        val more = AudioTimelineItem(uuid(42), uuid(43), source.id, t(10.0), SourcePlaybackMap.single(t(4.0), 0.0, 4.0))
        val late = plain.moved(plain.entireSelection(), t(1.0))
        val mixed = late.copy(audio = late.audio + music + more)
        val targets = mixed.pasteTargets()
        assertTrue(TimelinePasteTarget(Kind.Sound, t(10.0), Anchor.Between(music.id, more.id)) in targets)
        assertTrue(TimelinePasteTarget(Kind.Picture, t(1.0), Anchor.Start) in targets)
        assertTrue(TimelinePasteTarget(Kind.Picture, t(11.0), Anchor.End) in targets)
        assertTrue(TimelinePasteTarget(Kind.Project, EditTime.ZERO, Anchor.Start) in targets)
        assertTrue(TimelinePasteTarget(Kind.Project, t(14.0), Anchor.End) in targets)
        assertEquals(4 + 1 + 2 + 2, targets.size)
        assertEquals("Audio · A6 | A7", targets.first { it.kind == Kind.Sound }.label(mixed))
        // With no picture the outer edges belong to the sound.
        val soundOnly = TrackTimeline(audio = listOf(music, more))
        assertEquals(
            listOf(
                TimelinePasteTarget(Kind.Sound, t(10.0), Anchor.Between(music.id, more.id)),
                TimelinePasteTarget(Kind.Sound, EditTime.ZERO, Anchor.Start),
                TimelinePasteTarget(Kind.Sound, t(14.0), Anchor.End),
            ),
            soundOnly.pasteTargets(),
        )
    }

    /** YouRec/TimelinePaste.swift:16-22: within 24 ticks, or on the first frame of a 30 a second display at or after the cut. */
    @Test
    fun aTargetIsUnderThePlayheadWithinAFewTicksOrOnTheFrameAfterIt() {
        val target = TimelinePasteTarget(Kind.Picture, EditTime(8_000L * 3 + 100), Anchor.Start)
        assertTrue(target.isAt(EditTime(8_000L * 3 + 100)))
        assertTrue(target.isAt(EditTime(8_000L * 3 + 123)))
        assertFalse(target.isAt(EditTime(8_000L * 3 + 124)))
        assertTrue(target.isAt(EditTime(8_000L * 3 + 77)))
        assertFalse(target.isAt(EditTime(8_000L * 3 + 76)))
        assertTrue(target.isAt(EditTime(8_000L * 4)))
        assertFalse(target.isAt(EditTime(8_000L * 3)))
        assertEquals(8_000L, TimelinePasteTarget.FRAME_TICKS)
        assertEquals(24L, TimelinePasteTarget.REACH)
    }

    // MARK: Where a paste goes

    @Test
    fun pictureAtTheStartOrInAGapUsesFreeTimeBeforeItMovesAnything() {
        val one = plain.copied(plain.selection(clips[0].id))
        // Picture from 3 s on: two seconds fit before it.
        val late = plain.moved(plain.entireSelection(), t(3.0))
        val start = late.pasting(one, EditTime.ZERO, TimelinePasteTarget(Kind.Picture, t(3.0), Anchor.Start))
        assertEquals(late.video, start.timeline.video.drop(1))
        assertEquals(t(1.0), start.timeline.video[0].start)
        // On the timeline's own start it goes to zero.
        val zero = late.pasting(one, EditTime.ZERO, TimelinePasteTarget(Kind.Project, EditTime.ZERO, Anchor.Start))
        assertEquals(EditTime.ZERO, zero.timeline.video[0].start)
        assertEquals(late.video, zero.timeline.video.drop(1))
        // A gap of one second after the second clip: the rest moves by the second that does not fit.
        val spaced = plain.moved(plain.selection(clips.drop(2).mapTo(HashSet()) { it.id }), t(1.0))
        val gap = spaced.pasting(one, EditTime.ZERO, TimelinePasteTarget(Kind.Picture, t(4.0), Anchor.Gap))
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0).map(::t), gap.timeline.video.map { it.start })
        assertEquals(gap.selection.video.single(), gap.timeline.video[2].id)
        assertEquals(t(12.0), gap.timeline.duration)
        settled(start.timeline)
        settled(zero.timeline)
        settled(gap.timeline)
    }

    @Test
    fun soundAtTheStartOfTheSoundSequenceMovesEverythingUntilItFits() {
        val detached = plain.unlinked(plain.entireSelection())
        val sound = detached.copied(detached.selection(detached.audio[1].id))
        val pasted = detached.pasting(sound, EditTime.ZERO, TimelinePasteTarget(Kind.Sound, EditTime.ZERO, Anchor.Start))
        assertEquals(detached.audio.map { it.start + t(2.0) } + EditTime.ZERO, pasted.timeline.audio.map { it.start })
        assertEquals(detached.video.map { it.start + t(2.0) }, pasted.timeline.video.map { it.start })
        // At the end nothing moves.
        val end = detached.pasting(sound, EditTime.ZERO, TimelinePasteTarget(Kind.Picture, t(10.0), Anchor.End))
        assertEquals(detached.audio, end.timeline.audio.dropLast(1))
        assertEquals(t(10.0), end.timeline.audio.last().start)
        assertEquals(t(12.0), end.timeline.duration)
    }

    @Test
    fun aPasteWithoutATargetStaysInsideTheTimeline() {
        val one = plain.copied(plain.selection(clips[0].id))
        assertEquals(t(10.0), plain.pasting(one, t(99.0)).timeline.video.last().start)
        assertEquals(EditTime.ZERO, plain.pasting(one, t(-5.0)).timeline.video.first().start)
        val nothing = plain.pasting(TimelineClipboard(TrackTimeline(), insertsTime = false), t(1.0))
        assertSame(plain, nothing.timeline)
        assertTrue(nothing.selection.isEmpty)
    }

    @Test
    fun marksOnTheTimelineStayBehindAndMoveWithTheTimeAPasteOpens() {
        val marked = plain.copy(
            bookmarks = listOf(
                TimelineBookmark(uuid(70), anchor = BookmarkAnchor.Project(t(1.0))),
                TimelineBookmark(uuid(71), anchor = BookmarkAnchor.Project(t(5.0))),
                TimelineBookmark(uuid(72), "Take", BookmarkAnchor.Source(clips[0].id, source.id, 0.5)),
            ),
        )
        val copy = marked.copied(marked.selection(clips[0].id))
        assertEquals(listOf(uuid(72)), copy.timeline.bookmarks.map { it.id })
        val pasted = marked.pasting(copy, t(4.0), boundary(marked, 4.0)).timeline
        assertEquals(listOf(t(0.5), t(1.0), t(4.5), t(7.0)), pasted.resolvedBookmarks.map { it.time })
        assertEquals("Take", pasted.resolvedBookmarks[2].bookmark.label)
        assertEquals(4, pasted.bookmarks.map { it.id }.toSet().size)
        // A cut takes the marks of what it lifts and leaves the others.
        val cut = TrackClipboard.cut(marked, marked.selection(clips[0].id), t(0.0))!!
        assertEquals(listOf(uuid(70), uuid(71)), cut.timeline.bookmarks.map { it.id })
        assertEquals(listOf(uuid(72)), cut.clipboard.timeline.bookmarks.map { it.id })
    }

    @Test
    fun captionSettingsComeAlongOnlyWithWordsAndOnlyIntoATimelineWithout() {
        val settings = SubtitleSettings(id = uuid(80), timingFingerprint = "abc")
        val captioned = base.copy(subtitles = settings)
        assertEquals(settings, captioned.copied(captioned.selection(clips[4].id)).timeline.subtitles)
        assertNull(captioned.copied(captioned.selection(clips[0].id)).timeline.subtitles)
        val words = captioned.copied(captioned.selection(clips[4].id))
        assertEquals(settings.copy(timingFingerprint = ""), plain.pasting(words, plain.duration).timeline.subtitles)
        val other = SubtitleSettings(id = uuid(81), timingFingerprint = "own")
        assertEquals(other, plain.copy(subtitles = other).pasting(words, plain.duration).timeline.subtitles)
    }

    // MARK: Any sequence of edits

    /**
     * "Persistent Undo with generated edit sequences" (docs/EDITOR-TRACKS-DESIGN.md
     * of the original): whatever arranging operations follow one another,
     * the timeline is one a project may hold, the prune leaves it as it is,
     * and it is written and read back exactly.
     */
    @Test
    fun generatedSequencesOfArrangingEditsLeaveATimelineAProjectMayHold() {
        val random = TrackFixtures.Random(11)
        repeat(60) { round ->
            var timeline = if (round % 3 == 0) blend else base
            var clipboard: TimelineClipboard? = null
            repeat(25) { step ->
                val before = timeline
                fun anyItem(): String = timeline.allIDs.toList().let { it[random.below(it.size)] }
                fun time() = EditTime((random.next() * (timeline.duration.ticks + 48_000)).toLong())
                fun pick(): TimelineSelection {
                    val selection = if (random.below(3) == 0) TimelineSelection(video = timeline.video.filter { random.below(2) == 0 }.mapTo(HashSet()) { it.id }) else timeline.selection(anyItem(), linked = random.below(4) != 0)
                    if (random.below(4) != 0) return selection
                    val a = time()
                    val b = time()
                    return if (a == b) selection else selection.copy(range = EditInterval(minOf(a, b), maxOf(a, b)))
                }
                if (timeline.allIDs.isEmpty()) timeline = base
                val operation = random.below(12)
                when (operation) {
                    0 -> {
                        val scoped = TrackEditing.scoped(timeline, pick())
                        timeline = scoped.timeline.moved(scoped.selection, EditTime((random.next(-6.0, 6.0) * EditTime.SCALE).toLong()))
                    }
                    1 -> timeline = timeline.moveChoices(timeline.selection(anyItem())).let { timeline.moved(it[TimelineMoveScope.entries[random.below(4)]], t(random.next(-3.0, 3.0))) }
                    2 -> timeline = timeline.unlinked(timeline.selection(anyItem()))
                    3 -> timeline = (timeline.joining(pick().copy(range = null), intersectionOnly = random.below(2) == 0) as? TimelineLinkOutcome.Done)?.value?.timeline ?: timeline
                    4 -> clipboard = timeline.copied(pick())
                    5 -> TrackClipboard.cut(timeline, pick(), time())?.let { timeline = it.timeline; clipboard = it.clipboard }
                    6 -> clipboard?.let { timeline = timeline.pasting(it, time()).timeline }
                    7 -> clipboard?.let { held -> timeline.pasteTargets().let { targets -> if (targets.isNotEmpty()) timeline = timeline.pasting(held, time(), targets[random.below(targets.size)]).timeline } }
                    8 -> timeline = timeline.closingGap(time())
                    9 -> timeline.audio.takeIf { it.isNotEmpty() }?.let { timeline = timeline.restoringSync(it[random.below(it.size)].id) }
                    10 -> timeline = timeline.addingBookmark(time(), if (random.below(2) == 0) anyItem() else null)
                    else -> timeline = TrackEditing.split(timeline, time(), pick()).timeline
                }
                val name = "round $round step $step operation $operation"
                assertNull(name, TrackInvariants.violation(timeline, sources))
                assertEquals(name, timeline, timeline.pruned())
                if (timeline.duration.seconds > 120) timeline = before
            }
            assertEquals(timeline, TrackTimeline.decoded(timeline.encoded()))
            clipboard?.let { assertNull(TrackInvariants.violation(it.timeline, sources)) }
        }
    }
}
