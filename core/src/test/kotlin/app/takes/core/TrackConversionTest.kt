package app.takes.core

import app.takes.core.TrackFixtures.clip
import app.takes.core.TrackFixtures.music
import app.takes.core.TrackFixtures.second
import app.takes.core.TrackFixtures.take
import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** From today's sequence of clips and two sound tracks to the track timeline (docs/TRACKS-PLAN.md, 3.2). */
class TrackConversionTest {
    private fun ticks(seconds: Double) = EditTime.of(seconds)
    private val a = clip(1, take, 0.0, 12.5)
    private val b = clip(2, second, 0.0, 8.25)
    private val c = clip(3, take, 20.0, 31.0)

    // MARK: Pictures, their sound and their link

    @Test
    fun eachClipBecomesAPictureAFollowingSoundAndALink() {
        val timeline = EditState(listOf(a, b, c)).toTimeline()
        assertEquals(listOf(a.id, b.id, c.id), timeline.video.map { it.id })
        assertEquals(listOf(a.id, b.id, c.id), timeline.video.map { it.occurrenceID })
        assertEquals(listOf(0, 1, 2), timeline.video.map { it.layer })
        assertEquals(listOf(ticks(0.0), ticks(12.5), ticks(20.75)), timeline.video.map { it.start })
        assertEquals(listOf(PlaybackRun(ticks(8.25), 0.0, 8.25)), timeline.video[1].mapping.runs)
        assertTrue(timeline.video.all { it.localOrigin == EditTime.ZERO })
        assertEquals(ticks(31.75), timeline.duration)

        assertEquals(listOf(a.id, b.id, c.id).map(TrackTimeline::counterpart), timeline.audio.map { it.id })
        for ((picture, sound) in timeline.video.zip(timeline.audio)) {
            assertEquals(picture.id, sound.occurrenceID)
            assertEquals(picture.id, sound.followsVideo)
            assertEquals(picture.content.sourceID, sound.sourceID)
            assertEquals(picture.start, sound.start)
            assertEquals(picture.mapping, sound.mapping)
            assertEquals(0.0, sound.volumeDB, 0.0)
            assertFalse(sound.muted)
            assertNull(sound.fadeIn)
            assertTrue(sound.enhancements.isEmpty())
        }
        assertEquals(listOf(a.id, b.id, c.id), timeline.links.map { it.id })
        assertEquals(setOf(b.id, TrackTimeline.counterpart(b.id)), timeline.links[1].members)
        assertTrue(timeline.transitions.isEmpty() && timeline.generated.isEmpty() && timeline.placements.isEmpty() && timeline.bookmarks.isEmpty())
        assertNull(timeline.subtitles)
    }

    @Test
    fun aPicturesRecipeKeepsWhatBelongsToThePictureAndNothingElse() {
        val full = a.copy(
            texts = listOf(TextOverlay(id = "title", text = "Title")), color = ColorSettings(id = "color", amount = 0.5), crop = CropSettings(id = "crop", zoom = 2.0),
            speed = SpeedSettings("speed", 2.0), transition = TransitionSettings(nextClipID = b.id), transitionFit = TransitionFit(0.5),
            openingTransition = TransitionSettings(), openingTransitionFit = TransitionFit(1.0), audio = ClipAudio(volumeDB = -3.0),
            bookmarks = listOf(Bookmark("mark", 1.0)), subtitleWords = listOf(SubtitleWord("word", "phrase", "Hi", 1.0, 1.5)),
        )
        val content = EditState(listOf(full, b)).toTimeline().video[0].content
        assertEquals(TimelineClip(a.id, a.sourceID, a.start, a.end, texts = full.texts, color = full.color, crop = full.crop, speed = full.speed), content)
    }

    @Test
    fun aClipAtAnotherSpeedTakesItsPlayedLength() {
        val timeline = EditState(listOf(a.copy(speed = SpeedSettings(rate = 2.5)), b)).toTimeline()
        assertEquals(listOf(PlaybackRun(ticks(5.0), 0.0, 12.5)), timeline.video[0].mapping.runs)
        assertEquals(ticks(5.0), timeline.video[1].start)
        assertEquals(timeline.video[0].mapping, timeline.audio[0].mapping)
    }

    @Test
    fun anEmptyEditIsAnEmptyTimeline() {
        assertEquals(TrackTimeline(), EditState().toTimeline())
    }

    // MARK: Boundaries first

    /** Clips of lengths that are on no grid, as cuts made here are. */
    private fun sequence(random: TrackFixtures.Random, count: Int): List<TimelineClip> = List(count) { index ->
        val start = random.next(0.0, 40.0)
        TimelineClip("clip$index", take.id, start, start + random.next(0.2, 9.0))
    }

    @Test
    fun everyPlainCutLandsOnOneTickOnBothSides() {
        val random = TrackFixtures.Random()
        var cuts = 0
        var apart = 0
        repeat(100_000) {
            val clips = sequence(random, 2 + random.below(7))
            val timeline = EditState(clips).toTimeline()
            for (index in 0 until clips.lastIndex) {
                assertEquals(timeline.video[index].end, timeline.video[index + 1].start)
                assertEquals(timeline.audio[index].end, timeline.audio[index + 1].start)
                // The original's rule: the start and the length each rounded by itself (YouRec/TrackTimeline.swift:514, :58).
                // The first clip starts at zero, which is on every grid, so its cut is exact under either rule and is not counted.
                if (index == 0) continue
                cuts += 1
                val start = TimelineEditing.clipStart(clips, index)
                if (ticks(start) + ticks(clips[index].duration) != ticks(TimelineEditing.clipStart(clips, index + 1))) apart += 1
            }
            // Nothing drifts: every picture is within half a tick of where it plays today, and no length is a tick off.
            clips.indices.forEach { index ->
                assertTrue(abs(timeline.video[index].start.seconds - TimelineEditing.clipStart(clips, index)) <= 0.5 / EditTime.SCALE + 1e-12)
                assertTrue(abs(timeline.video[index].mapping.duration.ticks - ticks(clips[index].duration).ticks) <= 1)
            }
            // The last picture is as long as it plays, rounded by itself: the end is within a tick of where it was.
            assertTrue(abs(timeline.duration.ticks - ticks(TimelineEditing.duration(clips)).ticks) <= 1)
        }
        // What boundaries first avoids: one cut in four a tick apart under the original's rule (docs/TRACKS-PLAN.md measured 23.9 %).
        val share = apart.toDouble() / cuts
        assertTrue("$share of the cuts would be a tick apart", share in 0.23..0.27)
    }

    @Test
    fun aCutOnTheOriginalsGridIsTheSameUnderEitherRule() {
        val random = TrackFixtures.Random(3)
        repeat(2_000) {
            // Lengths in whole frames of 1/30 s, as the original's cuts are.
            val clips = List(2 + random.below(7)) { index -> TimelineClip("clip$index", take.id, 0.0, (6 + random.below(240)) / 30.0) }
            val timeline = EditState(clips).toTimeline()
            clips.indices.forEach { index ->
                assertEquals(ticks(TimelineEditing.clipStart(clips, index)), timeline.video[index].start)
                assertEquals(SourcePlaybackMap.of(clips[index]), timeline.video[index].mapping)
            }
        }
    }

    @Test
    fun convertingTwiceGivesEqualTimelines() {
        for ((name, state) in TrackFixtures.all) {
            val timeline = state.toTimeline()
            assertEquals(name, timeline, state.toTimeline())
            // A step read back from its file converts to the same timeline: undo steps agree on any device.
            val read = EditSnapshot.serializer().let { documentJson.decodeFromString(it, documentJson.encodeToString(it, EditSnapshot(state, 0))) }.state
            assertEquals(name, timeline, read.toTimeline())
            assertEquals(name, timeline, TrackTimeline.decoded(timeline.encoded()))
        }
    }

    // MARK: Transitions

    @Test
    fun aTransitionBecomesARelationWithTheLayoutsFactorAndAnOriginOnTheTickClock() {
        val dissolve = TransitionSettings(id = uuid(10), nextClipID = b.id, duration = 1.0)
        val clips = listOf(a.copy(transition = dissolve), b)
        val timeline = EditState(clips).toTimeline()
        val join = TimelineEditing.layout(clips).joins[1]
        val relation = timeline.transitions.single()
        assertEquals(TimelineTransition(a.id, b.id, dissolve, ticks(11.5), 1.0), relation)
        assertEquals(1.0, join.factor, 0.0)
        // The pictures overlap by the dissolve, and the incoming one starts where the transition needs it.
        assertEquals(ticks(11.5), timeline.video[1].start)
        assertEquals(ticks(12.5), timeline.video[0].end)
        assertEquals(relation.origin + ticks(relation.timing.rightStart), timeline.video[1].start)
        assertEquals(timeline.video[0].end - ticks(relation.timing.leftEnd), relation.origin)
        assertEquals(listOf(TimelineGeneratedRegion(TrackTimeline.counterpart(dissolve.id), dissolve.id, EditInterval.of(11.5, 12.5))), timeline.generated)
        assertEquals(ticks(19.75), timeline.duration)
    }

    @Test
    fun anOpeningNamesOnlyThePictureItOpensAndACloseOnlyTheOneItCloses() {
        val opening = TransitionSettings(id = uuid(11), style = TransitionStyle.FadeColor, duration = 0.5)
        val close = TransitionSettings(id = uuid(12), style = TransitionStyle.FadeColor, duration = 0.75)
        val timeline = EditState(listOf(a.copy(openingTransition = opening), b.copy(transition = close))).toTimeline()
        assertEquals(listOf(null to a.id, b.id to null), timeline.transitions.map { it.outgoing to it.incoming })
        assertEquals(listOf(-1, 1), timeline.transitions.map { it.edge })
        val (first, last) = timeline.transitions
        assertEquals(timeline.video[0].start - ticks(first.timing.rightStart), first.origin)
        assertEquals(timeline.video[1].end - ticks(last.timing.leftEnd), last.origin)
        assertEquals(timeline.transitions.map { it.generated }, timeline.generated)
    }

    @Test
    fun aSavedFitIsTheRelationsFactorAndOneNotSavedIsFitted() {
        val long = TransitionSettings(id = uuid(13), nextClipID = b.id, duration = 30.0)
        val saved = EditState(listOf(a.copy(transition = long, transitionFit = TransitionFit(0.125)), b)).toTimeline()
        assertEquals(0.125, saved.transitions.single().factor, 0.0)
        val derived = EditState(listOf(a.copy(transition = long), b)).toTimeline()
        val factor = derived.transitions.single().factor
        assertEquals(TimelineEditing.layout(listOf(a.copy(transition = long), b)).joins[1].factor, factor, 0.0)
        assertTrue(factor > 0 && factor < 1)
    }

    @Test
    fun settingsThatAreNotInPlayAreDropped() {
        val stale = TransitionSettings(nextClipID = c.id, duration = 1.0)
        val invalid = TransitionSettings(nextClipID = c.id, duration = -1.0)
        val shadowed = TransitionSettings(style = TransitionStyle.FadeColor, duration = 0.5)
        val into = TransitionSettings(id = uuid(14), nextClipID = b.id, duration = 0.5)
        // a names c, which is no longer next; b has invalid settings; then a transition into b takes the slot of b's own opening.
        assertTrue(EditState(listOf(a.copy(transition = stale), b.copy(transition = invalid), c)).toTimeline().transitions.isEmpty())
        val timeline = EditState(listOf(a.copy(transition = into), b.copy(openingTransition = shadowed), c)).toTimeline()
        assertEquals(listOf(into.id), timeline.transitions.map { it.id })
    }

    @Test
    fun anOpeningAndACloseStayWithTheirClipInTheMiddle() {
        val state = TrackFixtures.fades()
        val timeline = state.toTimeline()
        val middle = state.clips[1].id
        assertEquals(listOf(null to middle, middle to null), timeline.transitions.map { it.outgoing to it.incoming })
        // Each sits by its own picture, wherever that is.
        val (opening, close) = timeline.transitions
        assertEquals(timeline.video[1].start - ticks(opening.timing.rightStart), opening.origin)
        assertEquals(timeline.video[1].end - ticks(close.timing.leftEnd), close.origin)
    }

    // MARK: A clip's sound

    @Test
    fun aClipsLevelMuteAndFadesGoToItsSound() {
        val timeline = EditState(listOf(a.copy(audio = ClipAudio(volumeDB = -6.0, muted = true, fadeIn = 1.5, fadeOut = 0.75)), b)).toTimeline()
        val sound = timeline.audio[0]
        assertEquals(-6.0, sound.volumeDB, 0.0)
        assertTrue(sound.muted)
        assertEquals(AudioEdgeFade(1.5), sound.fadeIn)
        assertEquals(AudioEdgeFade(0.75), sound.fadeOut)
        assertNull(timeline.audio[1].fadeOut)
    }

    @Test
    fun aFadeGoesOverAsItIsSaved() {
        // Both forms keep the original's fade: its whole length, which may be the item's or more, and the lower of the two where they cross.
        val sound = EditState(listOf(a.copy(audio = ClipAudio(fadeIn = 10.0, fadeOut = 6.25)), b)).toTimeline().audio[0]
        assertEquals(AudioEdgeFade(10.0), sound.fadeIn)
        assertEquals(AudioEdgeFade(6.25), sound.fadeOut)
        assertEquals(10.0, sound.fadeSeconds(AudioFadeEdge.FadeIn), 0.0)
        val state = EditState(listOf(a.copy(audio = ClipAudio(fadeIn = 10.0, fadeOut = 3.0))))
        val fades = state.toTimeline().audio[0].let { AudioEdgeFades(it.fadeIn, it.fadeOut) }
        for (offset in listOf(0.0, 1.0, 3.0, 6.25, 9.0, 11.0, 12.5)) {
            assertEquals(TimelineEditing.soundLevel(state.clips, 0, offset), fades.gain(offset, 12.5), 1e-12)
        }
    }

    @Test
    fun aFadeCutByASplitKeepsWhatIsBehindItsEdge() {
        // The piece after the cut goes on from the level and with the slope the fade had there, in the timeline as in the clips.
        val whole = EditState(listOf(a.copy(audio = ClipAudio(fadeIn = 4.0, fadeOut = 3.0)), b))
        val split = TimelineEditing.split(whole, 1.0)!!
        val (first, second) = split.toTimeline().audio
        assertEquals(AudioEdgeFade(4.0), first.fadeIn)
        assertNull(first.fadeOut)
        assertEquals(AudioEdgeFade(4.0, 1.0), second.fadeIn)
        assertEquals(AudioEdgeFade(3.0), second.fadeOut)
        // The same pieces the timeline's own cut leaves of the whole sound (TrackTimeline.swift:196-202).
        val sound = whole.toTimeline().audio[0]
        assertEquals(second.fadeIn, sound.sliced(EditInterval(ticks(1.0), sound.mapping.duration), "later").fadeIn)
        assertEquals(first.fadeIn to first.fadeOut, sound.sliced(EditInterval(EditTime.ZERO, ticks(1.0)), sound.id).let { it.fadeIn to it.fadeOut })
        for (offset in listOf(0.0, 0.5, 2.0, 2.999, 11.0)) {
            assertEquals(TimelineEditing.soundLevel(split.clips, 1, offset), AudioEdgeFades(second.fadeIn, second.fadeOut).gain(offset, 11.5), 1e-12)
        }
    }

    @Test
    fun noiseReductionBecomesOneChainOverTheWholeSound() {
        val timeline = EditState(listOf(a.copy(audio = ClipAudio(denoise = 0.6)), b)).toTimeline()
        val id = TrackTimeline.counterpart(a.id)
        val region = timeline.audio[0].enhancements.single()
        assertEquals(id, region.id)
        assertEquals(EditInterval(EditTime.ZERO, timeline.audio[0].mapping.duration), region.interval)
        assertEquals(listOf(AudioEffect(id, AudioEffectKind.VoiceIsolation, amount = 0.6, model = AudioModel.RNNoise)), region.effects)
        assertTrue(region.effects.single().supported)
        assertTrue(timeline.audio[1].enhancements.isEmpty())
    }

    @Test
    fun aClipsWordsMoveToItsSound() {
        val timeline = TrackFixtures.captions().toTimeline()
        assertEquals(listOf("Hello", "there"), timeline.audio[0].subtitleWords!!.map { it.text })
        assertEquals(TrackFixtures.captions().clips[1].subtitleWords, timeline.audio[1].subtitleWords)
        assertNull(timeline.audio[2].subtitleWords)
        assertTrue(timeline.video.all { it.content.subtitleWords == null })
    }

    // MARK: Sound of its own

    @Test
    fun aSoundItemBecomesASoundThatFollowsNothing() {
        val item = AudioItem(uuid(30), music.id, 4.5, 10.0, 25.0, track = 1, audio = ClipAudio(volumeDB = -12.0, fadeIn = 2.0, fadeOut = 20.0, denoise = 0.5))
        val timeline = EditState(listOf(a, b), audio = listOf(item)).toTimeline()
        assertEquals(
            AudioTimelineItem(
                id = item.id, occurrenceID = item.id, sourceID = music.id, start = ticks(4.5), mapping = SourcePlaybackMap.single(ticks(15.0), 10.0, 25.0),
                volumeDB = -12.0, fadeIn = AudioEdgeFade(2.0), fadeOut = AudioEdgeFade(20.0),
            ),
            timeline.audio[2],
        )
        // Its sound is not in any link, and the chain a sound item could not play is not invented.
        assertEquals(2, timeline.links.size)
        assertTrue(timeline.links.none { item.id in it.members })
    }

    @Test
    fun theSoundsAreTheClipsInClipOrderThenTheItemsInSavedOrder() {
        val items = listOf(AudioItem(uuid(31), music.id, 9.0, 0.0, 3.0), AudioItem(uuid(30), music.id, 1.0, 0.0, 3.0, track = 1))
        val timeline = EditState(listOf(a, b), audio = items).toTimeline()
        assertEquals(listOf(TrackTimeline.counterpart(a.id), TrackTimeline.counterpart(b.id), uuid(31), uuid(30)), timeline.audio.map { it.id })
    }

    @Test
    fun twoPiecesOfOneSoundThatMeetStillMeet() {
        val random = TrackFixtures.Random(5)
        repeat(20_000) {
            val start = random.next(0.0, 50.0)
            val first = AudioItem("first", music.id, start, 0.0, random.next(0.2, 30.0))
            val rest = AudioItem("rest", music.id, first.end, first.sourceEnd, first.sourceEnd + random.next(0.2, 30.0))
            val (x, y) = EditState(listOf(a), audio = listOf(first, rest)).toTimeline().audio.drop(1)
            assertEquals(x.end, y.start)
        }
    }

    @Test
    fun unlinkedSoundIsTheClipsSoundWithoutItsLink() {
        val state = TrackFixtures.unlinked()
        val clipID = state.clips[1].id
        val item = state.audio!!.single()
        val timeline = state.toTimeline()
        val sound = timeline.audio[1]
        assertEquals(TrackTimeline.counterpart(clipID), sound.id)
        assertEquals(clipID, sound.occurrenceID)
        assertNull(sound.followsVideo)
        assertFalse(sound.muted)
        assertEquals(ticks(item.start), sound.start)
        assertEquals(listOf(PlaybackRun(ticks(item.end) - ticks(item.start), item.sourceStart, item.sourceEnd)), sound.mapping.runs)
        // One sound for the clip, not two; the picture keeps its place and the other clips their links.
        assertEquals(3, timeline.audio.size)
        assertEquals(listOf(state.clips[0].id, state.clips[2].id), timeline.links.map { it.id })
        assertEquals(ticks(TimelineEditing.clipStart(state.clips, 1)), timeline.video[1].start)
        // The clip's words stay with its sound.
        assertEquals(state.clips[1].subtitleWords, sound.subtitleWords)
    }

    @Test
    fun aSoundItemUnderAClipsIdThatIsNotItsUnlinkedSoundGetsAnIdOfItsOwn() {
        // The clip is not muted: its own sound plays, and the item is just a sound with an unlucky id.
        val item = AudioItem(a.id, music.id, 1.0, 0.0, 5.0)
        val timeline = EditState(listOf(a, b), audio = listOf(item)).toTimeline()
        assertEquals(listOf(TrackTimeline.counterpart(a.id), TrackTimeline.counterpart(b.id), TrackTimeline.alias(a.id)), timeline.audio.map { it.id })
        assertEquals(TrackTimeline.alias(a.id), timeline.audio[2].occurrenceID)
        assertEquals(a.id, timeline.audio[0].followsVideo)
        assertEquals(5, timeline.allIDs.size)
        // A muted clip whose namesake plays another source is the same case.
        val other = EditState(listOf(a.copy(audio = ClipAudio(muted = true)), b), audio = listOf(item)).toTimeline()
        assertEquals(TrackTimeline.alias(a.id), other.audio[2].id)
        assertTrue(other.audio[0].muted)
    }

    // MARK: Bookmarks and captions

    @Test
    fun aClipsBookmarksBecomeSourceBookmarksOfItsOccurrence() {
        val timeline = TrackFixtures.captions().toTimeline()
        assertEquals(
            listOf(
                TimelineBookmark(uuid(60), "", BookmarkAnchor.Source(a.id, take.id, 2.25)),
                TimelineBookmark(uuid(61), "Retake", BookmarkAnchor.Source(a.id, take.id, 9.0)),
                TimelineBookmark(uuid(62), "", BookmarkAnchor.Source(c.id, take.id, 25.5)),
            ),
            timeline.bookmarks,
        )
        // They are where the clips' marks are today.
        assertEquals(TimelineEditing.bookmarkTimes(TrackFixtures.captions().clips).map(::ticks), timeline.resolvedBookmarks.map { it.time })
    }

    @Test
    fun captionSettingsMoveAndAFingerprintThatMatchedStillMatches() {
        val state = TrackFixtures.captions()
        val timeline = state.toTimeline()
        assertEquals(state.subtitles!!.copy(timingFingerprint = TrackConversion.timingFingerprint(timeline)), timeline.subtitles)
        assertEquals(64, timeline.subtitles!!.timingFingerprint.length)
        // Footage changed after transcription: regrouping stays refused.
        val stale = state.copy(clips = state.clips.dropLast(1)).toTimeline()
        assertEquals("", stale.subtitles!!.timingFingerprint)
    }

    @Test
    fun theFingerprintFollowsStructureAndNothingElse() {
        val timeline = TrackFixtures.captions().toTimeline()
        val print = TrackConversion.timingFingerprint(timeline)
        val louder = timeline.copy(audio = timeline.audio.map { it.copy(volumeDB = 6.0, fadeIn = AudioEdgeFade(1.0), subtitleWords = null) }, bookmarks = emptyList())
        assertEquals(print, TrackConversion.timingFingerprint(louder))
        val recolored = timeline.copy(video = timeline.video.map { it.copy(content = it.content.copy(color = ColorSettings())) })
        assertEquals(print, TrackConversion.timingFingerprint(recolored))
        assertNotEquals(print, TrackConversion.timingFingerprint(timeline.copy(audio = timeline.audio.map { it.copy(muted = true) })))
        assertNotEquals(print, TrackConversion.timingFingerprint(timeline.copy(audio = timeline.audio.dropLast(1))))
        assertNotEquals(print, TrackConversion.timingFingerprint(timeline.copy(video = timeline.video.map { it.copy(start = it.start + EditTime(1)) })))
    }

    // MARK: Ids

    @Test
    fun idsThatAreNotUUIDsConvert() {
        val clips = listOf(TimelineClip("one", take.id, 0.0, 2.0), TimelineClip("two", take.id, 2.0, 4.0, transition = TransitionSettings(id = "fade", duration = 0.5)))
        val timeline = EditState(clips).toTimeline()
        assertEquals(listOf("one~sound", "two~sound"), timeline.audio.map { it.id })
        assertEquals(listOf("fade~sound"), timeline.generated.map { it.id })
        assertEquals(setOf("one", "one~sound"), timeline.links[0].members)
    }
}
