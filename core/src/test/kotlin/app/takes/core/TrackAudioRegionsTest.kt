package app.takes.core

import app.takes.core.TrackEditingSupport.ticks
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhance audio and Censor as regions of a sound: the badges and what they
 * act on, the chains that play where, and what is processed for them. The
 * checks that come from the original's `Tests/TrackMediaTests.swift` name
 * their lines.
 */
class TrackAudioRegionsTest {
    // Tests/TrackMediaTests.swift:646-652: an imported sound of four seconds, alone on the timeline.
    private val imported = SourceMedia(id = uuid(10), filename = "voice.m4a", duration = 4.0, audioOnly = true)
    private val alone = TrackAudio.inserting(TrackTimeline(), imported, EditTime.ZERO)
    private val settings = AudioCensorSettings()
    private val effectID = uuid(11)

    // Two clips of one take, each with its sound.
    private val take = SourceMedia(id = uuid(1), filename = "take.mp4", duration = 4.0)
    private val a = TimelineClip(uuid(2), take.id, 0.0, 2.0)
    private val b = TimelineClip(uuid(3), take.id, 2.0, 4.0)
    private val pair = EditState(listOf(a, b)).toTimeline()

    private fun gain(amount: Double, number: Int) = AudioEffect.make(AudioEffectKind.Gain, uuid(number)).copy(amount = amount)
    private fun TrackTimeline.sounds() = audio.mapTo(LinkedHashSet()) { it.id }
    private fun TrackTimeline.tones(time: Double) = audio.flatMap { TrackAudio.censorSpans(this, it) }.count { it.emitsTone && it.start <= time && time < it.end }
    private fun on(timeline: TrackTimeline, id: String, from: Double, to: Double) = TimelineSelection(audio = setOf(id), range = EditInterval.of(from, to))

    // MARK: Badges

    /** Tests/TrackMediaTests.swift:684-706: one censor badge over overlapping sounds, retuned and removed, and bridged by an uncut sound. */
    @Test
    fun oneCensorBadgeOverOverlappingSoundsReopensTheWholeEvent() {
        var layered = TrackAudio.inserting(alone.timeline, imported, EditTime.ZERO).timeline
        layered = TrackAudio.censoring(layered, settings, TimelineSelection(audio = layered.sounds(), range = EditInterval.of(1.0, 2.0)), effectID)
        val badge = TrackAudioRegions.badges(layered).first { it.kind == AudioToolKind.Censor }
        val event = TrackAudioRegions.targets(layered, badge)
        assertEquals("One shared censor badge targets the entire overlapping event", 2, event.audio.size)
        assertEquals(EditInterval.of(1.0, 2.0), event.range)
        val retuned = settings.copy(frequency = 440.0)
        val changed = TrackAudio.censoring(TrackAudioRegions.removing(layered, AudioToolKind.Censor, effectID, event), retuned, event, effectID)
        assertEquals("Editing a shared badge never leaves two different beeps behind", 1, changed.tones(1.5))
        assertTrue("Removing a shared badge restores every affected track",
            TrackAudioRegions.removing(changed, AudioToolKind.Censor, effectID, event).audio.all { it.censors.isEmpty() })

        val bridged = layered.split(ticks(1.5), TimelineSelection(audio = setOf(layered.audio[0].id))).timeline
        val badges = TrackAudioRegions.badges(bridged).filter { it.kind == AudioToolKind.Censor }
        assertEquals(3, badges.size)
        for (region in badges) {
            val request = TrackAudioRegions.targets(bridged, region)
            assertEquals("An uncut music bed joins both split voice halves into the same censor event", 3, request.audio.size)
            val again = TrackAudio.censoring(TrackAudioRegions.removing(bridged, AudioToolKind.Censor, effectID, request), retuned, request, effectID)
            for (t in listOf(1.25, 1.75)) assertEquals("Retuning either split half never stacks old and new tones", 1, again.tones(t))
            assertTrue("Removing the bridged event leaves no hidden old region",
                TrackAudioRegions.removing(again, AudioToolKind.Censor, effectID, request).audio.all { it.censors.isEmpty() })
        }
    }

    /** YouRec/VideoTools.swift:457-489: a selection narrows a badge to its pieces; a chain's badge never reaches another piece by itself. */
    @Test
    fun aBadgeWithASelectionActsOnTheSelectedPiecesInItsRange() {
        val both = TrackAudio.inserting(alone.timeline, imported, ticks(1.0)).timeline
        val enhanced = TrackAudio.enhancing(both, listOf(gain(-6.0, 20)), TrackLinking.entireSelection(both), uuid(21))
        val badges = TrackAudioRegions.badges(enhanced)
        assertEquals(listOf(EditInterval.of(0.0, 4.0), EditInterval.of(1.0, 5.0)), badges.map { it.interval })
        assertTrue(badges.all { it.kind == AudioToolKind.Enhance && it.effectID == uuid(21) })
        // Overlapping pieces of one chain are not one event: the badge is its own piece.
        assertEquals(TimelineSelection(audio = setOf(both.audio[0].id), range = EditInterval.of(0.0, 4.0)), TrackAudioRegions.targets(enhanced, badges[0]))
        // A selection of sounds the chain is on, within its range.
        val selection = TimelineSelection(audio = both.sounds(), range = EditInterval.of(3.0, 4.5))
        assertEquals(TimelineSelection(audio = both.sounds(), range = EditInterval.of(3.0, 4.5)), TrackAudioRegions.targets(enhanced, badges[0], selection))
        // A selection that holds none of them leaves the badge's own piece.
        assertEquals(TrackAudioRegions.targets(enhanced, badges[1]), TrackAudioRegions.targets(enhanced, badges[1], TimelineSelection(audio = setOf("other"))))
    }

    /** YouRec/VideoTools.swift:498-509. */
    @Test
    fun removingAChainFromTheMiddleKeepsBothSidesUnderTwoIds() {
        val id = alone.id
        var timeline = TrackAudio.enhancing(alone.timeline, listOf(gain(-6.0, 20)), TrackLinking.entireSelection(alone.timeline), uuid(21))
        timeline = TrackAudio.enhancing(timeline, listOf(gain(3.0, 22)), on(timeline, id, 3.0, 4.0), uuid(23))
        val removed = TrackAudioRegions.removing(timeline, AudioToolKind.Enhance, uuid(21), on(timeline, id, 1.0, 2.0))
        val regions = removed.audio[0].enhancements
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(2.0, 3.0), EditInterval.of(3.0, 4.0)), regions.map { it.interval })
        assertEquals(uuid(21), regions[0].id)
        assertNotEquals(uuid(21), regions[1].id)
        assertEquals(uuid(23), regions[2].id)
        removed.validate(listOf(imported))
        // Without a range the whole sound loses that chain and keeps the others.
        assertEquals(listOf(uuid(23)), TrackAudioRegions.removing(timeline, AudioToolKind.Enhance, uuid(21), TimelineSelection(audio = setOf(id))).audio[0].enhancements.map { it.id })
    }

    /** YouRec/TrackEditors.swift:212-219. */
    @Test
    fun thePanelOpensWithTheChainInScopeAndSaysWhenItIsMixed() {
        val chain = listOf(gain(-6.0, 20))
        val one = TrackAudio.enhancing(pair, chain, TimelineSelection(audio = setOf(pair.audio[0].id)), uuid(21))
        val all = TrackLinking.entireSelection(one)
        assertEquals(AudioChainScope(chain, mixed = true), TrackAudioRegions.chainScope(one, all))
        assertEquals(AudioChainScope(chain, mixed = false), TrackAudioRegions.chainScope(one, TimelineSelection(audio = setOf(pair.audio[0].id))))
        assertEquals(AudioChainScope(emptyList(), mixed = false), TrackAudioRegions.chainScope(pair, all))
        // A range that misses the chain sees none of it.
        assertEquals(AudioChainScope(emptyList(), mixed = false), TrackAudioRegions.chainScope(one, all.copy(audio = setOf(pair.audio[0].id), range = EditInterval.of(3.0, 4.0))))
        val other = TrackAudio.enhancing(one, listOf(gain(2.0, 22)), TimelineSelection(audio = setOf(pair.audio[1].id)), uuid(23))
        assertEquals(AudioChainScope(chain, mixed = true), TrackAudioRegions.chainScope(other, TrackLinking.entireSelection(other)))
        val same = TrackAudio.enhancing(pair, chain, TrackLinking.entireSelection(pair), uuid(21))
        assertEquals(AudioChainScope(chain, mixed = false), TrackAudioRegions.chainScope(same, TrackLinking.entireSelection(same)))
    }

    // MARK: Processing

    /** YouRec/TrackRenderer.swift:27-30. */
    @Test
    fun aProcessedSoundIsNamedBySourceAndByTheStagesThatAreOn() {
        val chain = listOf(gain(-6.0, 20), AudioEffect.make(AudioEffectKind.HighPass, uuid(21)))
        val key = TrackAudioRegions.cacheKey(take.id, chain)
        assertTrue(key.startsWith(take.id + "|"))
        assertEquals(key, TrackAudioRegions.cacheKey(take.id, chain))
        assertEquals("A stage's own id changes nothing heard", key, TrackAudioRegions.cacheKey(take.id, chain.map { it.copy(id = newID()) }))
        assertEquals("A stage that is off is not processed", key, TrackAudioRegions.cacheKey(take.id, chain + gain(12.0, 22).copy(enabled = false)))
        assertNotEquals(key, TrackAudioRegions.cacheKey(imported.id, chain))
        assertNotEquals(key, TrackAudioRegions.cacheKey(take.id, chain.reversed()))
        assertNotEquals(key, TrackAudioRegions.cacheKey(take.id, listOf(gain(-5.0, 20), chain[1])))
    }

    /** Tests/TrackMediaTests.swift:437-443: "Scoped enhancement applies only inside region". */
    @Test
    fun aChainOnPartOfASoundProcessesOnlyThatPart() {
        val chain = listOf(gain(-12.0, 20))
        val enhanced = TrackAudio.enhancing(alone.timeline, chain, on(alone.timeline, alone.id, 1.0, 2.0), uuid(21))
        val pieces = TrackAudioRegions.pieces(enhanced.audio[0])
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(1.0, 2.0), EditInterval.of(2.0, 4.0)), pieces.map { it.local })
        val key = TrackAudioRegions.cacheKey(imported.id, chain)
        assertEquals(listOf(null, key, null), pieces.map { it.key })
        assertEquals(listOf(emptyList(), chain, emptyList()), pieces.map { it.effects })
        assertEquals(listOf(key), TrackAudioRegions.requests(enhanced).keys.toList())
        assertEquals(AudioChainRequest(imported.id, chain), TrackAudioRegions.requests(enhanced)[key])
        // Muted, nothing plays; a chain with every stage off processes nothing.
        assertTrue(TrackAudioRegions.pieces(enhanced.audio[0].copy(muted = true)).isEmpty())
        val off = TrackAudio.enhancing(alone.timeline, chain.map { it.copy(enabled = false) }, on(alone.timeline, alone.id, 1.0, 2.0))
        assertTrue(TrackAudioRegions.pieces(off.audio[0]).all { it.key == null && it.effects.isEmpty() })
        assertTrue(TrackAudioRegions.requests(off).isEmpty())
    }

    /** Tests/TrackMediaTests.swift:77-107: a window of a long take, and a moved sound of two distant runs. */
    @Test
    fun listeningToAStretchProcessesOnlyWhatPlaysThere() {
        val long = SourceMedia(id = uuid(30), filename = "long.caf", duration = 60.0, audioOnly = true)
        val inserted = TrackAudio.inserting(TrackTimeline(), long, EditTime.ZERO)
        val chain = listOf(gain(6.0, 31))
        val enhanced = TrackAudio.enhancing(inserted.timeline, chain, TimelineSelection(audio = setOf(inserted.id)), uuid(32))
        val key = TrackAudioRegions.cacheKey(long.id, chain)
        val window = TrackAudioRegions.windowRequests(enhanced, EditInterval.of(25.0, 35.0))
        assertEquals("Only the requested source window is processed", listOf(SourceInterval(25.0, 35.0)), window.getValue(key).spans)
        assertEquals(listOf(EditInterval.of(25.0, 35.0)), TrackAudioRegions.pieces(enhanced.audio[0], EditInterval.of(25.0, 35.0)).map { it.local })
        assertTrue(TrackAudioRegions.windowRequests(enhanced, EditInterval.of(70.0, 80.0)).isEmpty())

        // "Do not process the large discarded source gap, and retain pitch/timing on both."
        val moved = enhanced.copy(audio = listOf(enhanced.audio[0].copy(
            start = ticks(6.0),
            mapping = SourcePlaybackMap(listOf(PlaybackRun(ticks(2.0), 3.0, 7.0), PlaybackRun(ticks(2.0), 50.0, 51.0))),
            enhancements = listOf(enhanced.audio[0].enhancements[0].copy(interval = EditInterval.of(0.0, 4.0))),
        )))
        assertEquals(listOf(SourceInterval(3.0, 7.0), SourceInterval(50.0, 51.0)), TrackAudioRegions.windowRequests(moved, EditInterval.of(6.0, 10.0)).getValue(key).spans)
        val pieces = TrackAudioRegions.pieces(moved.audio[0])
        assertEquals("A piece never bridges a jump in the source", listOf(EditInterval.of(0.0, 2.0), EditInterval.of(2.0, 4.0)), pieces.map { it.local })
        assertEquals(listOf(key, key), pieces.map { it.key })
        assertEquals(listOf(3.0 to 7.0, 50.0 to 51.0), pieces.map { it.mapping.runs.single().let { run -> run.sourceStart to run.sourceEnd } })
        // A hold and a silent run are cut off from what is heard around them; a stretch all silent is not played.
        val held = moved.audio[0].copy(mapping = SourcePlaybackMap(listOf(
            PlaybackRun(ticks(1.0), 3.0, 4.0), PlaybackRun(ticks(1.0), 4.0, 4.0), PlaybackRun(ticks(1.0), 4.0, 5.0, silent = true), PlaybackRun(ticks(1.0), 5.0, 6.0),
        )))
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(1.0, 2.0), EditInterval.of(3.0, 4.0)), TrackAudioRegions.pieces(held).map { it.local })
    }

    /** OD:417-420: "two occurrences of one source with different chains". */
    @Test
    fun twoUsesOfOneRecordingShareAProcessingOnlyWhenTheirChainsAreTheSame() {
        val chain = listOf(gain(-6.0, 20))
        val same = TrackAudio.enhancing(pair, chain, TrackLinking.entireSelection(pair), uuid(21))
        assertEquals(1, TrackAudioRegions.requests(same).size)
        val apart = TrackAudio.enhancing(same, listOf(gain(-6.0, 22)), TimelineSelection(audio = setOf(pair.audio[1].id)), uuid(23))
        assertEquals("The same settings made apart are processed once", 1, TrackAudioRegions.requests(apart).size)
        val different = TrackAudio.enhancing(same, listOf(gain(3.0, 24)), TimelineSelection(audio = setOf(pair.audio[1].id)), uuid(25))
        val requests = TrackAudioRegions.requests(different)
        assertEquals(2, requests.size)
        assertTrue(requests.values.all { it.sourceID == take.id })
        val keys = different.audio.map { item -> TrackAudioRegions.pieces(item).single().key }
        assertEquals(requests.keys, keys.toSet())
    }

    /** OD:417-420: "adjacent chains". */
    @Test
    fun adjacentChainsMeetWithoutAGapOrAnOverlap() {
        val id = alone.id
        var timeline = TrackAudio.enhancing(alone.timeline, listOf(gain(-6.0, 20)), on(alone.timeline, id, 0.0, 2.0), uuid(21))
        timeline = TrackAudio.enhancing(timeline, listOf(gain(6.0, 22)), on(timeline, id, 2.0, 4.0), uuid(23))
        assertEquals(listOf(uuid(21), uuid(23)), timeline.audio[0].enhancements.map { it.id })
        val keys = TrackAudioRegions.pieces(timeline.audio[0]).map { it.key }
        assertEquals(2, keys.toSet().size)
        timeline = TrackAudio.enhancing(timeline, listOf(gain(1.0, 24)), on(timeline, id, 1.0, 3.0), uuid(25))
        assertEquals(listOf(EditInterval.of(0.0, 1.0), EditInterval.of(1.0, 3.0), EditInterval.of(3.0, 4.0)), TrackAudioRegions.pieces(timeline.audio[0]).map { it.local })
        assertEquals(listOf(uuid(21), uuid(25), uuid(23)), timeline.audio[0].enhancements.map { it.id })
        timeline.validate(listOf(imported))
    }

    /** What split, trim, move and speed do to a chain, and so to what plays where. */
    @Test
    fun aChainGoesWithItsSoundThroughSplitTrimMoveAndSpeed() {
        val chain = listOf(gain(-6.0, 20))
        val enhanced = TrackAudio.enhancing(pair, chain, on(pair, pair.audio[0].id, 0.5, 1.5), uuid(21))
        val key = TrackAudioRegions.cacheKey(take.id, chain)
        fun processed(timeline: TrackTimeline) = timeline.audio.flatMap { item ->
            TrackAudioRegions.pieces(item).filter { it.key == key }.map { EditInterval(item.start + it.local.start, item.start + it.local.end) }
        }
        assertEquals(listOf(EditInterval.of(0.5, 1.5)), processed(enhanced))

        val split = enhanced.split(ticks(1.0), TrackLinking.entireSelection(enhanced)).timeline
        assertEquals(listOf(EditInterval.of(0.5, 1.0), EditInterval.of(1.0, 1.5)), processed(split).sortedBy { it.start })
        split.validate(listOf(take))

        val trimmed = TrackEditing.trimming(enhanced, a.id, left = true, delta = 0.25, sources = listOf(take))!!.timeline
        assertEquals(listOf(EditInterval.of(0.25, 1.25)), processed(trimmed))
        assertEquals(EditInterval.of(0.25, 1.25), trimmed.audio[0].enhancements.single().interval)

        val moved = TrackMove.moved(enhanced, TrackLinking.selection(enhanced, a.id), ticks(6.0))
        assertEquals(listOf(EditInterval.of(6.5, 7.5)), processed(moved))

        val faster = enhanced.replacingVideoContents(enhanced.projectedClips.map { if (it.id == a.id) it.copy(speed = SpeedSettings(rate = 2.0)) else it })
        assertEquals(EditInterval.of(0.25, 0.75), faster.audio[0].enhancements.single().interval)
        assertEquals(listOf(EditInterval.of(0.25, 0.75)), processed(faster))
        faster.validate(listOf(take))
    }

    // MARK: The gate: sequences sound as they do today

    /**
     * For every edit of the sequence form, the timeline it converts to
     * processes what today's noise reduction does: every sound of a clip that
     * asks for it plays the cleaned sound over its whole length, through a
     * chain of noise reduction at the clip's amount, and nothing else is
     * processed. One processing per recording and amount, as today's file of
     * cleaned sound.
     */
    @Test
    fun aConvertedSequenceIsProcessedAsTodaysNoiseReduction() {
        val edits = TrackFixtures.all.values + GoldenProjects.all.filter { !it.large }.flatMap { it.project.history }
        var cleaned = 0
        for (state in edits) {
            val timeline = state.toTimeline()
            val expected = HashSet<Pair<String, Double>>()
            for (item in timeline.audio) {
                val clip = state.clips.firstOrNull { it.id == item.followsVideo }
                val amount = clip?.audio?.denoise ?: 0.0
                val pieces = TrackAudioRegions.pieces(item)
                if (amount <= 0) {
                    assertTrue(pieces.all { it.key == null && it.effects.isEmpty() })
                    continue
                }
                expected += item.sourceID to amount
                cleaned++
                if (item.muted) { assertTrue(pieces.isEmpty()); continue }
                assertTrue(pieces.all { it.effects.single().kind == AudioEffectKind.VoiceIsolation && it.effects.single().amount == amount })
                assertEquals(1, pieces.mapTo(HashSet()) { it.key }.size)
                val heard = item.mapping.runs.filter { !it.silent }.sumOf { it.duration.ticks }
                assertEquals(heard, pieces.sumOf { piece -> piece.mapping.runs.filter { !it.silent }.sumOf { it.duration.ticks } })
            }
            val requests = TrackAudioRegions.requests(timeline)
            assertEquals(expected, requests.values.mapTo(HashSet()) { it.sourceID to it.effects.single().amount })
            assertEquals(expected.size, requests.size)
        }
        assertTrue(cleaned > 0)
    }

    @Test
    fun aSoundThatFollowsNoClipIsNeverProcessedByTheConversion() {
        val inserted = TrackAudio.inserting(pair, imported, EditTime.ZERO)
        assertNull(TrackAudioRegions.pieces(inserted.timeline.audio.last()).single().key)
        assertFalse(TrackAudioRegions.requests(inserted.timeline).isNotEmpty())
    }
}
