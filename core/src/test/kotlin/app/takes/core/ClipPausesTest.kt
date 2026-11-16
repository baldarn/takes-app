package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pauses as a setting of the clip, as the original keeps them
 * (YouRec/Project.swift:52, YouRec/VideoTools.swift:238-262): the clip stays
 * one clip, plays its footage without the pauses taken out, and the Pauses
 * tool opens again on what it found (YouRec/EditorTools.swift:618-640,
 * :1027-1041). Swift lines are those of the original at faf1815.
 */
class ClipPausesTest {
    private val tiny = 1e-9
    private val take = SourceMedia(id = "S", filename = "take.mp4", duration = 20.0, recordedAt = 5)

    private fun clip(id: String, end: Double = 4.0, start: Double = 0.0) = TimelineClip(id = id, sourceID = "S", start = start, end = end)
    private fun cutting(vararg cuts: SourceInterval) = PauseSettings(id = "P", cuts = cuts.toList())

    private fun factors(state: EditState) = TimelineEditing.layout(state.clips).joins.filter { it.transition != null }.map { it.factor }

    /** Two 4 s clips with a 1 s dissolve set between them: the pair of Tests/TransitionTests.swift:10-15 and of F11 in docs/TRANSITIONS-REVIEW.md. */
    private val pair = TimelineEditing.setJoin(EditState(listOf(clip("A"), clip("B"))), 1, TransitionSettings(duration = 1.0))!!

    /** Three 4 s clips with a 1 s dissolve at both joins. */
    private val three = EditState(listOf(clip("A"), clip("B"), clip("C"))).let { state ->
        TimelineEditing.setJoin(TimelineEditing.setJoin(state, 1, TransitionSettings(duration = 1.0))!!, 2, TransitionSettings(duration = 1.0))!!
    }

    // MARK: The clip's clock

    /** The kept footage and the source clock skip what was cut (VideoTools.swift:253-277). */
    @Test fun aClipPlaysItsFootageWithoutThePausesTakenOut() {
        val paused = clip("A").copy(pauses = cutting(SourceInterval(0.4, 1.0)))
        assertTrue(paused.paused && paused.timed)
        assertEquals(listOf(SourceInterval(0.0, 0.4), SourceInterval(1.0, 4.0)), paused.keptIntervals)
        assertEquals(listOf(EffectPlaybackSpan(0.0, 0.4, 1.0), EffectPlaybackSpan(1.0, 4.0, 1.0)), paused.playbackSpans)
        assertEquals(3.4, paused.duration, tiny)
        assertEquals(0.3, paused.sourceTime(0.3), tiny)
        assertEquals(1.1, paused.sourceTime(0.5), tiny)
        assertEquals(4.0, paused.sourceTime(3.4), tiny)
        assertEquals(0.5, paused.offset(1.1), tiny)
        // Removed footage maps to where it was cut (VideoTools.swift:274-277).
        assertEquals(0.4, paused.offset(0.7), tiny)
        // At twice the speed every stretch takes half as long.
        assertEquals(1.7, paused.copy(speed = SpeedSettings(rate = 2.0)).duration, tiny)
        // The map of the timeline is one run a stretch (TrackTimeline.swift:49-64).
        val runs = SourcePlaybackMap.of(paused).runs
        assertEquals(listOf(0.0 to 0.4, 1.0 to 4.0), runs.map { it.sourceStart to it.sourceEnd })
        assertEquals(EditTime.of(3.4), SourcePlaybackMap.of(paused).duration)
        // A cut outside the clip, and settings that cut nothing, leave it as it was.
        val outside = clip("A").copy(pauses = cutting(SourceInterval(6.0, 7.0)))
        assertFalse(outside.paused || outside.timed)
        assertEquals(4.0, outside.duration, tiny)
        assertFalse(clip("A").copy(pauses = PauseSettings()).paused)
    }

    // MARK: Transitions

    /**
     * F11 of docs/TRANSITIONS-REVIEW.md: a pause from 0.4 s to 1.0 s taken out
     * of the second of two 4 s clips with a 1 s dissolve. The original keeps
     * one 3.4 s clip and the dissolve at 1 s: its transitions are scaled only
     * when together they cost more than the clip now lasts
     * (TrackTimeline.swift:1376-1385).
     */
    @Test fun theDissolveStaysWholeOnTheClipThePausesLeaveOneClip() {
        val before = pair.duration
        val cut = TimelineEditing.setPauses(pair, 1, cutting(SourceInterval(0.4, 1.0)))!!
        assertEquals(listOf("A", "B"), cut.clips.map { it.id })
        assertEquals(listOf(4.0, 3.4), cut.clips.map { it.duration })
        assertEquals(listOf(1.0), factors(cut))
        assertEquals(1.0, cut.clips[0].transitionFit!!.factor, tiny)
        assertEquals("B", cut.clips[0].transition!!.nextClipID)
        assertEquals(before - 0.6, cut.duration, tiny)
        assertEquals(cutting(SourceInterval(0.4, 1.0)), cut.clips[1].pauses)
        // What earlier versions made of it, and still make: pieces of 0.4 s and 3 s, the dissolve shortened to 0.4 s.
        val pieces = TimelineEditing.removeIntervals(pair, 1, listOf(SourceInterval(0.4, 1.0)))!!
        assertEquals(listOf(4.0, 0.4, 3.0), pieces.clips.map { it.duration })
        assertEquals(listOf(0.4), factors(pieces).map { Math.round(it * 1e6) / 1e6 })
    }

    /** A change of speed and pauses taken out keep the footage: both transitions are scaled by the largest factor that fits (TrackTimeline.swift:1376-1385). */
    @Test fun transitionsAreScaledTogetherOnlyWhenTheyCostMoreThanTheClipLasts() {
        // Three stretches left, 2.5 s in all: the two half-second sides of the dissolves fit.
        val roomy = TimelineEditing.setPauses(three, 1, cutting(SourceInterval(0.5, 1.5), SourceInterval(2.5, 3.0)))!!
        assertEquals(listOf(4.0, 2.5, 4.0), roomy.clips.map { it.duration })
        assertEquals(listOf(1.0, 1.0), factors(roomy))
        // 1.5 s left, and the two dissolves ask 2 s of it: both scaled to three quarters, as for speed.
        val tight = TimelineEditing.setPauses(three, 1, cutting(SourceInterval(0.0, 2.5)))!!
        assertEquals(listOf(4.0, 1.5, 4.0), tight.clips.map { it.duration })
        factors(tight).forEach { assertEquals(0.75, it, 1e-6) }
        assertEquals(factors(TimelineEditing.setSpeed(three, 1, 8.0 / 3)!!).size, factors(tight).size)
        // Putting the pauses back does not give the shortened dissolves their length back, as a slower speed does not.
        val back = TimelineEditing.setPauses(tight, 1, null)!!
        assertEquals(listOf(4.0, 4.0, 4.0), back.clips.map { it.duration })
        factors(back).forEach { assertEquals(0.75, it, 1e-6) }
        assertNull(back.clips[1].pauses)
    }

    @Test fun removingThePausesPutsThemBackAndNothingChangedIsNoEdit() {
        val cut = TimelineEditing.setPauses(pair, 1, cutting(SourceInterval(0.4, 1.0)))!!
        val back = TimelineEditing.setPauses(cut, 1, null)!!
        assertEquals(pair.clips.map { it.duration }, back.clips.map { it.duration })
        assertEquals(pair.duration, back.duration, tiny)
        assertNull(TimelineEditing.setPauses(cut, 1, cut.clips[1].pauses))
        assertNull(TimelineEditing.setPauses(pair, 5, cutting(SourceInterval(0.4, 1.0))))
    }

    /** The pieces of a split keep the settings, as the original's split copies the recipe; each plays its own footage without them. */
    @Test fun aSplitClipKeepsItsPausesOnBothPieces() {
        val cut = TimelineEditing.setPauses(EditState(listOf(clip("A", end = 10.0))), 0, cutting(SourceInterval(2.0, 3.0), SourceInterval(6.0, 7.0)))!!
        assertEquals(8.0, cut.duration, tiny)
        val split = TimelineEditing.split(cut, 4.0)!!
        assertEquals(2, split.clips.size)
        assertEquals(8.0, split.duration, 1e-6)
        assertEquals(listOf(4.0, 4.0), split.clips.map { Math.round(it.duration * 1e6) / 1e6 })
        assertEquals(5.0, split.clips[1].start, 1e-6)
        assertTrue(split.clips.all { it.pauses?.cuts == cut.clips[0].pauses!!.cuts })
    }

    // MARK: Saving

    @Test fun aClipWithoutPausesIsWrittenAsBefore() {
        assertEquals("""{"id":"A","sourceID":"S","start":0.0,"end":4.0}""", documentJson.encodeToString(TimelineClip.serializer(), clip("A")))
        assertEquals(1, clip("A").schema)
    }

    /**
     * The settings are saved whole, with every pause found and its Cut or
     * Keep, so the tool opens again on them (EditorTools.swift:1027-1041,
     * :1533-1536); readers before schema 22 would play the pauses and must refuse.
     */
    @Test fun theSettingsAndEveryChoiceAreSavedAndOlderReadersRefuse() {
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val whole = base.edit.clips[0]
        val candidates = listOf(DetectedPause(SourceInterval(2.0, 3.0)), DetectedPause(SourceInterval(5.0, 6.5), enabled = false), DetectedPause(SourceInterval(9.0, 11.0)))
        val settings = PauseSettings(id = "P1", thresholdDB = -41.0, minimum = 0.8, before = 0.1, after = 0.2, levelMode = PauseLevelMode.Manual)
            .let { it.copy(candidates = candidates, analyzed = SourceInterval(whole.start, whole.end), cuts = PauseDetector.effectiveCuts(candidates, whole, it)) }
        assertEquals(listOf(SourceInterval(2.1, 2.8), SourceInterval(9.1, 10.8)), settings.cuts.map { SourceInterval(Math.round(it.start * 1e9) / 1e9, Math.round(it.end * 1e9) / 1e9) })
        val edited = base.commit(TimelineEditing.setPauses(base.edit, 0, settings)!!, now = 3)
        assertEquals(TimelineClip.PAUSES_SCHEMA, edited.schemaVersion)
        assertEquals(22, TimelineClip.PAUSES_SCHEMA)
        edited.validate()
        val files = mutableMapOf<String, ByteArray>()
        val manifest = edited.manifest { name, data -> files[name] = data }
        val reopened = Project.readManifest(manifest) { files.getValue(it) }
        assertEquals(settings, reopened.edit.clips[0].pauses)
        assertEquals(edited.edit.clips, reopened.edit.clips)
        assertEquals(20.0 - 0.7 - 1.7, reopened.edit.duration, 1e-6)
        // Undo goes back to the clip with all its pauses, at the schema the project now has.
        assertNull(reopened.undo().edit.clips[0].pauses)
        val newer = manifest.decodeToString().replaceFirst("\"schemaVersion\":22", "\"schemaVersion\":${Project.LATEST_READABLE_SCHEMA + 1}")
        try { Project.readManifest(newer.toByteArray()) { files.getValue(it) }; fail("opened") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.NewerVersion, refused.reason)
        }
    }

    /** Project.swift:493-499. */
    @Test fun settingsOutsideTheOriginalsRangesAreRefused() {
        assertTrue(PauseSettings().valid(10.0))
        assertTrue(PauseSettings(minimum = 10.0, before = 2.0, after = 0.0, thresholdDB = -80.0).valid(10.0))
        assertFalse(PauseSettings(minimum = 0.05).valid(10.0))
        assertFalse(PauseSettings(minimum = 10.5).valid(10.0))
        assertFalse(PauseSettings(before = 2.5).valid(10.0))
        assertFalse(PauseSettings(thresholdDB = 1.0).valid(10.0))
        assertFalse(PauseSettings(thresholdDB = Double.NaN).valid(10.0))
        assertFalse(PauseSettings(cuts = listOf(SourceInterval(9.0, 10.5))).valid(10.0))
        assertFalse(PauseSettings(candidates = listOf(DetectedPause(SourceInterval(3.0, 2.0)))).valid(10.0))
        assertFalse(PauseSettings(analyzed = SourceInterval(-1.0, 2.0)).valid(10.0))
        val base = Project("Trip", id = "P", createdAt = 1).addRecording(take, now = 2)
        val bad = base.copy(snapshots = base.snapshots + EditSnapshot(EditState(base.edit.clips.map { it.copy(pauses = PauseSettings(minimum = 20.0)) }), 1), historyCursor = 1)
        try { bad.validate(); fail("valid") } catch (refused: ProjectException) {
            assertEquals(ProjectException.Reason.InvalidDocument, refused.reason)
        }
    }

    // MARK: What the movie plays

    /** The renderer is given the playback pieces of a clip (VideoTools.swift:278-294, MediaRenderer.swift:263): one part a stretch of footage. */
    @Test fun theDecksAreGivenEachStretchOfFootageAsAPartOfItsOwn() {
        val cut = TimelineEditing.setPauses(EditState(listOf(clip("A"), clip("B"))), 0, cutting(SourceInterval(0.4, 1.0)))!!
        val plan = RenderPlan.of(cut)
        val picture = plan.pictures[0]
        assertEquals(listOf(PicturePlayback.Piece(0.0, 0.4, 0.0, 0.4), PicturePlayback.Piece(0.4, 3.4, 1.0, 4.0)), picture.pieces)
        val parts = plan.stretch(0.0, plan.duration).decks.flatten().filterIsInstance<RenderPlan.Part.Footage>()
        assertEquals(listOf(0, 0, 1), parts.map { it.index })
        assertEquals(listOf(0L to 400_000L, 1_000_000L to 4_000_000L, 0L to 4_000_000L), parts.map { it.sourceStartUs to it.sourceEndUs })
        assertEquals(listOf(0.0, 0.4, 3.4), parts.map { Math.round(it.start * 1e6) / 1e6 })
        // A section that begins inside the second stretch cuts it there.
        val later = plan.stretch(1.0, 2.0).decks.flatten().filterIsInstance<RenderPlan.Part.Footage>().single()
        assertEquals(1_600_000L to 2_600_000L, later.sourceStartUs to later.sourceEndUs)
        // One that ends inside the first stretch, before the pause.
        val early = plan.stretch(0.0, 0.2).decks.flatten().filterIsInstance<RenderPlan.Part.Footage>().single()
        assertEquals(0L to 200_000L, early.sourceStartUs to early.sourceEndUs)
        // A clip without pauses is one piece and one part, as always.
        assertEquals(1, plan.pictures[1].pieces.size)
    }

    @Test fun piecesJoinSpansThatFollowOneAnotherInTheSource() {
        val spans = listOf(EffectPlaybackSpan(0.0, 1.0, 1.0), EffectPlaybackSpan(1.0, 2.0, 2.0), EffectPlaybackSpan(3.0, 4.0, 1.0))
        assertEquals(listOf(PicturePlayback.Piece(0.0, 1.5, 0.0, 2.0), PicturePlayback.Piece(1.5, 2.5, 3.0, 4.0)), PicturePlayback.pieces(spans))
        assertEquals(2.6, PicturePlayback.pieces(spans, 2.6).last().to, tiny)
        assertEquals(emptyList<PicturePlayback.Piece>(), PicturePlayback.pieces(emptyList()))
    }

    /** The sound plays the same stretches: one piece of the mix each, as the original's mix cuts a map where its source jumps (TrackRenderer.swift:207, :285). */
    @Test fun theSoundSkipsThePausesToo() {
        val cut = TimelineEditing.setPauses(EditState(listOf(clip("A"))), 0, cutting(SourceInterval(0.4, 1.0)))!!
        val mix = TrackMix.plan(cut.toTimeline())
        val runs = mix.pieces.flatMap { it.runs }
        assertEquals(listOf(0.0 to 0.4, 1.0 to 4.0), runs.map { Math.round(it.sourceStart * 1e6) / 1e6 to Math.round(it.sourceEnd * 1e6) / 1e6 })
        assertEquals(EditTime.of(3.4), mix.pieces.maxOf { it.end })
    }

    /** The words of a pause taken out are not shown, and later ones come earlier, as on the timeline's runs (Subtitles.swift:155-180). */
    @Test fun captionsFollowTheFootageThatPlays() {
        val words = listOf(
            SubtitleWord(id = "W1", phraseID = "F1", text = "Hello", start = 0.1, end = 0.3),
            SubtitleWord(id = "W2", phraseID = "F2", text = "um", start = 0.5, end = 0.8),
            SubtitleWord(id = "W3", phraseID = "F3", text = "world", start = 1.2, end = 1.5),
        )
        val state = EditState(listOf(clip("A").copy(subtitleWords = words)), subtitles = SubtitleSettings())
        val cut = TimelineEditing.setPauses(state, 0, cutting(SourceInterval(0.4, 1.0)))!!
        val cues = SubtitleEditing.cues(cut)
        assertEquals(listOf("Hello", "world"), cues.map { it.text })
        assertEquals(0.6, cues[1].start, 1e-6)
        assertEquals(0.9, cues[1].end, 1e-6)
        // The timeline gives the same.
        assertEquals(cues.map { it.text to Math.round(it.start * 1e6) }, TrackSubtitles.cues(cut.toTimeline()).map { it.text to Math.round(it.start * 1e6) })
    }

    /** The previous and next change stop where a pause was taken out, as at a cut (TimelineNavigation.swift:85-88, :100). */
    @Test fun navigationStopsWhereAPauseWasTakenOut() {
        val cut = TimelineEditing.setPauses(EditState(listOf(clip("A"), clip("B"))), 0, cutting(SourceInterval(0.4, 1.0)))!!
        val stops = TimelineNavigation.of(cut)
        assertEquals(0.4, stops.next(0.0)!!, 1e-6)
        assertEquals(3.4, stops.next(0.5)!!, 1e-6)
        assertTrue(stops.changes.isNotEmpty())
    }

    /** Titles are in source seconds and show where their footage plays. */
    @Test fun aTitleFollowsItsFootage() {
        val title = TextOverlay(id = "T", text = "Hi", start = 1.5, end = 3.0)
        val cut = TimelineEditing.setPauses(EditState(listOf(clip("A").copy(texts = listOf(title)))), 0, cutting(SourceInterval(0.4, 1.0)))!!
        val range = TimelineEditing.titleRange(cut.clips, 0, title)!!
        assertEquals(0.9, range.start, 1e-6)
        assertEquals(2.4, range.endInclusive, 1e-6)
    }
}
