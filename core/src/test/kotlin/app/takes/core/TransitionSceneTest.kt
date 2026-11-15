package app.takes.core

import app.takes.core.TransitionScene.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timeline on the original's layout: where clips play, what a moment shows, how loud each clip is. */
class TransitionSceneTest {
    private val a = TimelineClip(id = "A", sourceID = "S", start = 0.0, end = 4.0)
    private val b = TimelineClip(id = "B", sourceID = "S", start = 0.0, end = 4.0)
    private val pair = EditState(listOf(a, b))
    private val tiny = 1e-9

    private fun joined(settings: TransitionSettings, join: Int = 1, state: EditState = pair) = TimelineEditing.setJoin(state, join, settings)!!.clips

    // MARK: Lengths, as the original's tests pin them for two 4 s clips

    @Test fun eachStyleGivesTheTimelineTheOriginalsLength() {
        assertEquals(7.0, TimelineEditing.duration(joined(TransitionSettings(duration = 1.0))), tiny)
        assertEquals(8.0, TimelineEditing.duration(joined(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))), tiny)
        assertEquals(5.0, TimelineEditing.duration(joined(TransitionSettings(duration = 1.0, hold = 2.0))), tiny)
        assertEquals(10.0, TimelineEditing.duration(joined(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0))), tiny)
        assertEquals(10.0, TimelineEditing.duration(joined(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))), tiny)
        assertEquals(9.0, TimelineEditing.duration(joined(TransitionSettings(style = TransitionStyle.Still, duration = 1.0), join = 0)), tiny)
        assertEquals(10.5, TimelineEditing.duration(joined(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5), join = 2)), tiny)
        val both = TimelineEditing.setJoin(EditState(joined(TransitionSettings(duration = 1.0), join = 0)), 2, TransitionSettings(duration = 1.0))!!.clips
        assertEquals(8.0, TimelineEditing.duration(both), tiny)
        for (style in listOf(TransitionStyle.Wipe, TransitionStyle.Split, TransitionStyle.Hitchcock, TransitionStyle.Whip, TransitionStyle.FlashCut, TransitionStyle.Defocus)) {
            assertEquals(style.title, 7.0, TimelineEditing.duration(joined(TransitionSettings(style = style, duration = 1.0))), tiny)
        }
    }

    @Test fun theSettingsOfAJoinAreSavedWhereTheOriginalKeepsThem() {
        val opening = joined(TransitionSettings(duration = 1.0), join = 0)
        assertEquals("A", opening[0].openingTransition!!.nextClipID)
        assertNull(opening[0].transition)
        val closing = joined(TransitionSettings(duration = 1.0), join = 2)
        assertNull(closing[1].transition!!.nextClipID)
        assertEquals(closing[1].transition, TimelineEditing.joinSettings(closing, 2))
        val between = joined(TransitionSettings(duration = 1.0))
        assertEquals("B", between[0].transition!!.nextClipID)
        assertEquals(between[0].transition, TimelineEditing.joinSettings(between, 1))
        // Removing restores the plain cut; removing nothing is no edit.
        assertNull(TimelineEditing.setJoin(EditState(between), 1, null)!!.clips[0].transition)
        assertNull(TimelineEditing.setJoin(pair, 1, null))
        assertNull(TimelineEditing.setJoin(EditState(between), 1, between[0].transition))
        assertNull(TimelineEditing.setJoin(pair, 3, TransitionSettings()))
        assertNull(TimelineEditing.setJoin(pair, 1, TransitionSettings(duration = -1.0)))
        // Shorter than one frame is no transition.
        assertNull(TimelineEditing.setJoin(pair, 1, TransitionSettings(duration = 0.01)))
    }

    @Test fun aShortClipBetweenTwoLongTransitionsSharesItsFootage() {
        val c = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0)
        val short = TimelineClip(id = "M", sourceID = "S", start = 0.0, end = 1.0)
        // Saved without fits, as before fits were saved: each dissolve gets half of the one-second clip.
        val saved = listOf(
            a.copy(transition = TransitionSettings(nextClipID = "M", duration = 3.0)), short.copy(transition = TransitionSettings(nextClipID = "C", duration = 3.0)), c,
        )
        val layout = TimelineEditing.layout(saved)
        assertEquals(0.5, layout.joins[1].timing.videoDuration, 1e-6)
        assertEquals(0.5, layout.joins[2].timing.videoDuration, 1e-6)
        // The two never show three clips at once.
        assertTrue(layout.joins[1].videoRange.endInclusive <= layout.joins[2].videoRange.start + 1e-6)
        assertEquals(8.0, layout.duration, 1e-6)
        // Set one after the other, the first takes the whole clip and nothing is left for the second:
        // a transition is fitted into what its neighbors leave free and they keep theirs (Transitions.swift:349-370).
        val state = TimelineEditing.setJoin(EditState(listOf(a, short, c)), 1, TransitionSettings(duration = 3.0))!!
        assertEquals(1.0, TimelineEditing.layout(state.clips).joins[1].timing.videoDuration, 1e-6)
        assertEquals(1.0 / 3, state.clips[0].transitionFit!!.factor, 1e-9)
        assertNull(TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 3.0)))
        assertEquals(8.0, state.duration, 1e-6)
        assertEquals(3.0, state.clips[0].transition!!.duration, 0.0)
    }

    // MARK: What a moment shows

    @Test fun aDissolveMixesTheTwoDecksByTheEasedProgress() {
        val clips = joined(TransitionSettings(duration = 1.0))
        val before = TransitionScene.moment(clips, 2.0)
        assertEquals(TransitionFrame.First, before.frame)
        assertEquals(Source.Clip(0, 0, 2.0), before.first)
        val quarter = TransitionScene.moment(clips, 3.25)
        assertEquals(TransitionFrame.Dissolve(0.15625), quarter.frame)
        assertEquals(Source.Clip(0, 0, 3.25), quarter.first)
        assertEquals(Source.Clip(1, 1, 0.25), quarter.second)
        val after = TransitionScene.moment(clips, 5.0)
        assertEquals(Source.Clip(1, 1, 2.0), after.first)
        assertEquals(listOf(after.first), after.sources)
    }

    @Test fun aFadeGoesThroughItsColorAndHoldsOnItWithNoClipShowing() {
        val red = TransitionColor(1.0, 0.0, 0.0)
        val clips = joined(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, hold = 2.0, fadeColor = red))
        // The first clip ends at 4, the color holds from 4 to 6, the second clip begins at 6.
        assertEquals(listOf(0.0, 6.0), clips.indices.map { TimelineEditing.clipStart(clips, it) })
        assertEquals(2, TimelineEditing.chains(clips).size)
        val down = TransitionScene.moment(clips, 3.75)
        assertEquals(TransitionFrame.FadeColor(red, 0.5, 0.5, 0.0), down.frame)
        assertEquals(listOf<Source>(Source.Clip(0, 0, 3.75)), down.sources)
        val held = TransitionScene.moment(clips, 5.0)
        assertEquals(TransitionFrame.FadeColor(red, 0.0, 1.0, 0.0), held.frame)
        assertEquals(emptyList<Source>(), held.sources)
        val up = TransitionScene.moment(clips, 6.25)
        assertEquals(TransitionFrame.FadeColor(red, 0.0, 0.5, 0.5), up.frame)
        assertEquals(listOf<Source>(Source.Clip(1, 1, 0.25)), up.sources)
        // Without a hold nothing is inserted and one deck plays both clips.
        val plain = joined(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0))
        assertEquals(1, TimelineEditing.chains(plain).size)
        assertEquals(listOf(0, 0), TransitionScene.decks(plain))
    }

    @Test fun anOpeningAndAClosingHaveTheFillOnTheMissingSide() {
        val red = TransitionColor(1.0, 0.0, 0.0)
        val opening = joined(TransitionSettings(style = TransitionStyle.FadeColor, duration = 1.0, fadeColor = red), join = 0)
        val start = TransitionScene.moment(opening, 0.25)
        // At an edge a fade through color is an eased dissolve from the color.
        assertEquals(TransitionFrame.Dissolve(0.15625), start.frame)
        assertEquals(Source.Fill(red), start.first)
        assertEquals(Source.Clip(0, 0, 0.25), start.second)
        val closing = joined(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0), join = 2)
        val end = TransitionScene.moment(closing, 7.5)
        assertTrue(end.frame is TransitionFrame.Wipe)
        assertEquals(Source.Clip(1, 0, 3.5), end.first)
        assertEquals(Source.Fill(TransitionColor.BLACK), end.second)
        // The very last moment is the fill, not the clip again.
        assertEquals(TransitionFrame.Second, TransitionScene.moment(closing, 8.0).frame)
    }

    @Test fun aStillHoldsTheLastFrameShownThenDissolves() {
        val clips = joined(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))
        assertEquals(listOf(0.0, 6.0), clips.indices.map { TimelineEditing.clipStart(clips, it) })
        val running = TransitionScene.moment(clips, 3.9)
        assertEquals(Source.Clip(0, 0, 3.9), running.first)
        val frozen = TransitionScene.moment(clips, 5.0)
        assertEquals(TransitionFrame.First, frozen.frame)
        val held = frozen.first as Source.Held
        assertEquals(0, held.index)
        assertEquals(4.0 - 1.0 / 30, held.offset, 1e-6)
        assertEquals(4.0, held.from, tiny)
        // The exit dissolve runs over the first half second of the next clip, eased.
        val leaving = TransitionScene.moment(clips, 6.25)
        assertEquals(TransitionFrame.Dissolve(0.5), leaving.frame)
        assertTrue(leaving.first is Source.Held)
        assertEquals(Source.Clip(1, 1, 0.25), leaving.second)
        assertEquals(Source.Clip(1, 1, 1.0), TransitionScene.moment(clips, 7.0).first)
        // The title of the frozen clip stays as it was at that frame.
        val title = TextOverlay(id = "T", text = "Hi", start = 3.0, end = 4.0)
        val titled = TimelineEditing.setText(EditState(clips), 0, title)!!.clips
        assertEquals(listOf(title to 1.0), TimelineEditing.titlesAt(titled, 5.0))
        // At the opening black is held instead; at the close the last frame goes to black.
        val opening = joined(TransitionSettings(style = TransitionStyle.Still, duration = 1.0), join = 0)
        assertEquals(Source.Fill(TransitionColor.BLACK), TransitionScene.moment(opening, 0.5).sources.single())
        assertEquals(Source.Clip(0, 0, 0.5), TransitionScene.moment(opening, 1.5).sources.single())
        val closing = joined(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5), join = 2)
        assertTrue(TransitionScene.moment(closing, 9.0).sources.single() is Source.Held)
        assertEquals(TransitionFrame.Dissolve(0.5), TransitionScene.moment(closing, 10.25).frame)
    }

    @Test fun aFlashCutShowsWholeFramesOnTheThirtyFramesGrid() {
        val clips = joined(TransitionSettings(style = TransitionStyle.FlashCut, duration = 1.0))
        val flashes = (0 until 30).filter { TransitionScene.moment(clips, 3.0 + (it + 0.5) / 30).frame == TransitionFrame.Second }
        assertEquals(listOf(13, 22, 27), flashes)
    }

    @Test fun titlesOfASplitScreenBothShowWhole() {
        val first = TextOverlay(id = "T", text = "One", start = 0.0, end = 4.0)
        val second = TextOverlay(id = "U", text = "Two", start = 0.0, end = 4.0)
        var state = TimelineEditing.setText(TimelineEditing.setText(pair, 0, first)!!, 1, second)!!
        state = TimelineEditing.setJoin(state, 1, TransitionSettings(style = TransitionStyle.Split, duration = 2.0))!!
        assertEquals(listOf(first to 1.0, second to 1.0), TimelineEditing.titlesAt(state.clips, 3.0))
        val wiped = TimelineEditing.setJoin(state, 1, TransitionSettings(style = TransitionStyle.Wipe, duration = 2.0))!!
        assertEquals(listOf(first to 0.5, second to 0.5), TimelineEditing.titlesAt(wiped.clips, 3.0))
    }

    // MARK: Sound

    @Test fun soundFollowsTheOriginalsEnvelope() {
        // A J-cut: the second clip's sound leads the picture by half a second and blends over a quarter.
        val settings = TransitionSettings(duration = 1.0, audioOffset = -0.5, audioBlend = 0.25)
        val clips = joined(settings)
        val join = TimelineEditing.join(clips, 1)!!
        // The second clip starts half a second earlier so its sound can lead.
        assertEquals(join.origin - 0.5, TimelineEditing.clipStart(clips, 1), tiny)
        for (step in -20..40) {
            val time = join.origin + step / 20.0
            val levels = TransitionSound.levels(join.timing, time - join.origin)
            if (time <= 4.0) assertEquals(levels.first, TimelineEditing.soundLevel(clips, 0, time), tiny)
            val offset = time - TimelineEditing.clipStart(clips, 1)
            if (offset >= 0) assertEquals(levels.second, TimelineEditing.soundLevel(clips, 1, offset), tiny)
        }
        assertEquals(0.5, TimelineEditing.soundLevel(clips, 0, join.origin - 0.375), tiny)
        // An L-cut: the first clip's sound stays a second past the picture change.
        val late = joined(TransitionSettings(duration = 0.5, audioOffset = 1.0, audioBlend = 0.5))
        val lateJoin = TimelineEditing.join(late, 1)!!
        assertEquals(1.0, TimelineEditing.soundLevel(late, 0, lateJoin.origin + 0.9), tiny)
        assertEquals(0.5, TimelineEditing.soundLevel(late, 0, lateJoin.origin + 1.25), tiny)
        assertEquals(0.0, TimelineEditing.soundLevel(late, 1, lateJoin.origin + 0.9 - TimelineEditing.clipStart(late, 1)), tiny)
        // The picture has changed long before the sound does.
        assertEquals(TransitionFrame.Second, TransitionScene.moment(late, lateJoin.origin + 0.9).frame)
        // An opening brings the first clip's sound up; a closing takes the last one's down.
        val edges = TimelineEditing.setJoin(EditState(joined(TransitionSettings(duration = 1.0), join = 0)), 2, TransitionSettings(duration = 1.0))!!.clips
        assertEquals(0.25, TimelineEditing.soundLevel(edges, 0, 0.25), tiny)
        assertEquals(0.25, TimelineEditing.soundLevel(edges, 1, 3.75), tiny)
        // A clip's own level multiplies the envelope.
        val quiet = TimelineEditing.setAudio(EditState(clips), 0, ClipAudio(volumeDB = -6.0))!!.clips
        assertEquals(ClipAudio(volumeDB = -6.0).gain / 2, TimelineEditing.soundLevel(quiet, 0, join.origin - 0.375), tiny)
    }

    // MARK: Editing around transitions

    @Test fun noSplitInsideAnyTransitionAndThePiecesKeepTheEnds() {
        for (style in TransitionStyle.entries) {
            val clips = joined(TransitionSettings(style = style, duration = 1.0, stillDissolve = 0.5))
            val join = TimelineEditing.join(clips, 1)!!
            val inside = (join.range.start + join.range.endInclusive) / 2
            assertNull(style.title, TimelineEditing.split(EditState(clips), inside))
            assertNotNull(style.title, TimelineEditing.split(EditState(clips), 1.0))
        }
        var state = TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 0.5))!!
        val pieces = TimelineEditing.split(state, 2.0)!!.clips
        assertNotNull(pieces[0].openingTransition)
        assertNull(pieces[1].openingTransition)
        val last = TimelineEditing.split(state, 6.0)!!.clips
        assertNull(last[1].transition)
        assertNull(last[2].transition!!.nextClipID)
        assertEquals(state.duration, EditState(last).duration, tiny)
    }

    @Test fun theOpeningStaysWithTheFirstClipAndTheCloseWithTheLast() {
        var state = TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 0.5))!!
        val swapped = TimelineEditing.move(state, 0, 1)!!.clips
        assertTrue(swapped.all { it.openingTransition == null && it.transition == null })
        val c = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0)
        // A clip put at the very start or the very end takes the place the transition was tied to.
        assertNull(TimelineEditing.insert(state, listOf(c), 0.0)!!.clips[1].openingTransition)
        assertNull(TimelineEditing.insert(state, listOf(c), state.duration)!!.clips[1].transition)
        // In the middle both ends are kept.
        val middle = TimelineEditing.insert(state, listOf(c), 4.0)!!.clips
        assertNotNull(middle[0].openingTransition)
        assertNotNull(middle[2].transition)
        // A take recorded later lands after the closing clip: the close waits, unused, and returns when the take goes.
        val appended = state.clips + c
        assertNull(TimelineEditing.joinSettings(appended, 3))
        assertEquals(10.0, TimelineEditing.duration(appended), tiny)
        assertNotNull(TimelineEditing.joinSettings(TimelineEditing.delete(EditState(appended), 2)!!.clips, 2))
    }

    @Test fun aClipInsertedOnAJoinRemovesItsTransition() {
        val clips = joined(TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0))
        val c = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0)
        // On the join it goes between the two clips (TimelinePaste.swift:161-163).
        val between = TimelineEditing.insert(EditState(clips), listOf(c), 3.5)!!.clips
        assertEquals(listOf("A", "C", "B"), between.map { it.id })
        assertTrue(between.all { it.transition == null && it.transitionFit == null })
        // Inside the transition it is removed and the clip showing at that second is cut there (TimelinePaste.swift:176-186).
        val inserted = TimelineEditing.insert(EditState(clips), listOf(c), 3.6)!!.clips
        assertEquals(listOf("A", "C", "S", "B"), inserted.mapIndexed { index, clip -> if (index == 2) clip.sourceID else clip.id })
        assertEquals(listOf(0.0 to 3.6, 0.0 to 2.0, 3.6 to 4.0, 0.0 to 4.0), inserted.map { it.start to it.end })
        assertTrue(inserted.all { it.transition == null && it.transitionFit == null })
    }

    @Test fun aTransitionOnAFasterClipIsMeasuredInMovieSeconds() {
        val fast = b.copy(speed = SpeedSettings(rate = 2.0))
        val clips = joined(TransitionSettings(duration = 1.0), state = EditState(listOf(a, fast)))
        assertEquals(2.0, clips[1].duration, tiny)
        assertEquals(5.0, TimelineEditing.duration(clips), tiny)
        val middle = TransitionScene.moment(clips, 3.5)
        assertEquals(Source.Clip(1, 1, 0.5), middle.second)
        assertEquals(1.0, clips[1].sourceTime((middle.second as Source.Clip).offset), tiny)
    }

    // MARK: The frame rate a flash cut counts in

    @Test fun aFlashCutCountsItsFramesInTheFrameRateOfTheMovie() {
        // The original counts in the frame rate of its composition (VideoToolCompositor.swift:296-297, TransitionRenderer.swift:235-238):
        // each flash is one frame of the movie, whatever its rate.
        val flash = TransitionSettings(style = TransitionStyle.FlashCut, duration = 0.5)
        val clips = joined(flash)
        val origin = TimelineEditing.join(clips, 1)!!.origin
        assertEquals(3.5, origin, tiny)
        fun shown(rate: Double) = (0 until (0.5 * rate).toInt()).filter { frame ->
            TransitionScene.moment(clips, origin + (frame + 0.5) / rate, frames = { rate }).frame == TransitionFrame.Second
        }
        assertEquals(listOf(6, 11, 13), shown(30.0))
        assertEquals(listOf(13, 22, 27), shown(60.0))
        assertEquals(listOf(5, 8, 10), shown(24.0))
        assertEquals(flash.flashFrames(0.5, 25.0), shown(25.0))
        assertEquals(listOf(5, 9, 11), shown(25.0))
        // Without a rate, thirty frames a second, as the original's editor.
        assertEquals(TransitionFrame.Second, TransitionScene.moment(clips, origin + 6.5 / 30).frame)
        // The titles change hands on the same frames.
        val titled = clips.mapIndexed { index, clip -> clip.copy(texts = listOf(TextOverlay(text = "T$index", start = 0.0, end = 4.0))) }
        assertEquals(listOf("T1"), TimelineEditing.titlesAt(titled, origin + 13.5 / 60) { 60.0 }.map { it.first.text })
        assertEquals(listOf("T0"), TimelineEditing.titlesAt(titled, origin + 12.5 / 60) { 60.0 }.map { it.first.text })
    }

    @Test fun theMovieHasTheFrameRateOfTheFootageOnTheFirstDeck() {
        val clips = joined(TransitionSettings(style = TransitionStyle.FlashCut, duration = 0.5))
        val join = TimelineEditing.join(clips, 1)!!
        assertEquals(listOf(0, 1), TransitionScene.decks(clips))
        // The first deck leads: its clip's frames are the movie's while both play.
        assertEquals(25.0, TransitionScene.frameRate(clips, join, listOf(25.0, 60.0), 60), tiny)
        assertEquals(60.0, TransitionScene.frameRate(clips, join, listOf(60.0, 25.0), 60), tiny)
        // A ceiling of 30 leaves every other frame of 60 and of 50, and all of 25.
        assertEquals(30.0, TransitionScene.frameRate(clips, join, listOf(60.0, 25.0), 30), tiny)
        assertEquals(25.0, TransitionScene.frameRate(clips, join, listOf(50.0, 25.0), 30), tiny)
        assertEquals(25.0, TransitionScene.frameRate(clips, join, listOf(25.0, 25.0), 30), tiny)
        // Footage that does not say counts as 30; a clip at double speed brings its frames twice as fast.
        assertEquals(30.0, TransitionScene.frameRate(clips, join, listOf(null, 25.0), 60), tiny)
        val fast = joined(TransitionSettings(style = TransitionStyle.FlashCut, duration = 0.5), state = EditState(listOf(a.copy(speed = SpeedSettings(rate = 2.0)), b)))
        assertEquals(50.0, TransitionScene.frameRate(fast, TimelineEditing.join(fast, 1)!!, listOf(25.0, 25.0), 60), tiny)
        assertEquals(25.0, TransitionScene.frameRate(fast, TimelineEditing.join(fast, 1)!!, listOf(25.0, 25.0), 30), tiny)
        // A second transition: its outgoing clip is on the second deck and the incoming one on the first.
        val three = TimelineEditing.setJoin(EditState(clips + TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 4.0)), 2, TransitionSettings(style = TransitionStyle.FlashCut, duration = 0.5))!!.clips
        assertEquals(listOf(0, 1, 0), TransitionScene.decks(three))
        assertEquals(24.0, TransitionScene.frameRate(three, TimelineEditing.join(three, 2)!!, listOf(25.0, 60.0, 24.0), 60), tiny)
    }
}
