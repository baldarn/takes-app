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

    @Test fun titlesAreDrawnIntoTheirSideForEveryStyleButSplitScreen() {
        // YouRec/VideoToolCompositor.swift:282, :409-411: each clip is rendered with its overlays before
        // TransitionRenderer.composite, unless the style is split; :298-301: for split, the titles of both once over the pair.
        val first = TextOverlay(id = "T", text = "One", start = 0.0, end = 4.0)
        val second = TextOverlay(id = "U", text = "Two", start = 0.0, end = 4.0)
        val titled = TimelineEditing.setText(TimelineEditing.setText(pair, 0, first)!!, 1, second)!!
        val still = TitleMotion.Pose()
        for (style in TransitionStyle.entries.filter { it != TransitionStyle.Split && it != TransitionStyle.FlashCut && it != TransitionStyle.Still }) {
            val clips = TimelineEditing.setJoin(titled, 1, TransitionSettings(style = style, duration = 2.0))!!.clips
            val plan = TrackRenderPlan.of(EditState(clips))
            val moment = plan.moment(3.0)
            assertTrue(style.title, TransitionScene.titlesInSides(moment.frame))
            val sides = plan.titleSides(3.0)
            // Each whole, whatever the share of its clip: the transition itself mixes, wipes, keys, slides or blurs it.
            assertEquals(style.title, listOf(TitleMotionRanges.Shown(first, 1.0, still)), sides.first)
            assertEquals(style.title, listOf(TitleMotionRanges.Shown(second, 1.0, still)), sides.second)
            assertEquals(style.title, emptyList<TitleMotionRanges.Shown>(), sides.over)
            // On the deck that shows each side the frame shows.
            val decks = sides.onDecks(moment)
            for (source in moment.sources) {
                assertEquals(style.title, if (source == moment.first) sides.first else sides.second, decks[TransitionScene.deckOf(source)!!])
            }
        }
        // A fade through a color between clips one after the other on one deck: the deck carries the side showing.
        val fade = TrackRenderPlan.of(EditState(TimelineEditing.setJoin(titled, 1, TransitionSettings(style = TransitionStyle.FadeColor, duration = 2.0))!!.clips))
        for ((time, text) in listOf(3.5 to "One", 4.5 to "Two")) {
            val moment = fade.moment(time)
            assertEquals(listOf(text), fade.titleSides(time).onDecks(moment)[TransitionScene.deckOf(moment.sources.single())!!].map { it.title?.text })
        }
        // A split screen: no titles in either side, both once over the pair, a title on both clips once.
        val split = TimelineEditing.setJoin(titled, 1, TransitionSettings(style = TransitionStyle.Split, duration = 2.0))!!.clips
        val splitPlan = TrackRenderPlan.of(EditState(split))
        val sides = splitPlan.titleSides(3.0)
        assertTrue(sides.first.isEmpty() && sides.second.isEmpty())
        assertEquals(listOf(first, second), sides.over.map { it.title })
        assertEquals(listOf(emptyList<TitleMotionRanges.Shown>(), emptyList()), sides.onDecks(splitPlan.moment(3.0)))
        val same = split.mapIndexed { index, clip -> if (index == 1) clip.copy(texts = listOf(first)) else clip }
        assertEquals(listOf(first), TrackRenderPlan.of(EditState(same)).titleSides(3.0).over.map { it.title })
        // Outside a transition the clip showing is the first side; the second has nothing to add.
        val alone = TrackRenderPlan.of(titled).titleSides(1.0)
        assertEquals(listOf(first), alone.first.map { it.title })
        assertTrue(alone.second.isEmpty() && alone.over.isEmpty())
        // An opening: the fill has no titles, the clip's go into its side.
        val opening = TrackRenderPlan.of(EditState(TimelineEditing.setJoin(titled, 0, TransitionSettings(style = TransitionStyle.Wipe, duration = 1.0))!!.clips)).titleSides(0.5)
        assertTrue(opening.first.isEmpty())
        assertEquals(listOf(first), opening.second.map { it.title })
    }

    @Test fun theTitlesOfAFrozenClipGoIntoItsHeldSide() {
        // YouRec/VideoToolCompositor.swift:282: the held clip is rendered with its overlays as any other side.
        val title = TextOverlay(id = "T", text = "Hi", start = 3.0, end = 4.0)
        val clips = TimelineEditing.setText(EditState(joined(TransitionSettings(style = TransitionStyle.Still, duration = 2.0, stillDissolve = 0.5))), 0, title)!!.clips
        val plan = TrackRenderPlan.of(EditState(clips))
        val moment = plan.moment(6.25)
        assertTrue(moment.first is Source.Held)
        assertEquals(listOf(title), plan.titleSides(6.25).first.map { it.title })
        assertEquals(listOf(title), plan.titleSides(6.25).onDecks(moment)[(moment.first as Source.Held).deck].map { it.title })
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

    /**
     * As the original, where an opening and a close are relations with one
     * side missing and belong to their picture (Transitions.swift:299-322,
     * TrackTimeline.swift:881-885): moved, or with a clip put before the
     * first or after the last, they play on where the picture is.
     */
    /** A transition is drawn between the pictures it relates, as the original's compile names its pair (TrackTimeline.swift:989-998). */
    @Test fun aTransitionIsTakenByThePicturesItRelates() {
        val clips = joined(TransitionSettings(duration = 1.0))
        val join = TimelineEditing.layout(clips).joins[1]
        assertEquals("A" to "B", join.outgoingID to join.incomingID)
        assertEquals(0 to 1, TransitionScene.related(clips, join))
        // Whatever places it is numbered with, it is between its two pictures.
        assertEquals(0 to 1, TransitionScene.related(clips, join.copy(outgoing = 1, incoming = 0)))
        assertEquals(1 to 0, TransitionScene.related(clips.reversed(), join))
        // A join of a layout made without ids is taken by its places.
        assertEquals(1 to 0, TransitionScene.related(clips, join.copy(outgoing = 1, incoming = 0, outgoingID = null, incomingID = null)))
        val opening = TimelineEditing.layout(TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!.clips).joins[0]
        assertEquals(null to "A", opening.outgoingID to opening.incomingID)
        assertEquals(null to 0, TransitionScene.related(clips, opening))
    }

    @Test fun anOpeningAndACloseStayWithTheirClip() {
        var state = TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 0.5))!!
        val swapped = TimelineEditing.move(state, 0, 1)!!.clips
        assertEquals(listOf("B", "A"), swapped.map { it.id })
        assertEquals(state.clips[0].openingTransition, swapped[1].openingTransition)
        assertEquals(state.clips[1].transition, swapped[0].transition)
        val layout = TimelineEditing.layout(swapped)
        assertEquals(8.0, layout.duration, tiny)
        assertEquals(listOf(null, null, null), layout.joins.map { it.transition })
        assertEquals(listOf(0.0, 4.0, 8.0), layout.joins.map { it.time })
        // The close of the clip now first, and the opening of the clip now last, each under a number of its own.
        val close = layout.join(TransitionLayout.closeIndex(0, 2))!!
        assertEquals(listOf(1, 0, null), listOf(close.edge, close.outgoing, close.incoming))
        assertEquals(3.5..4.0, close.range)
        val opening = layout.join(TransitionLayout.openingIndex(1, 2))!!
        assertEquals(listOf(-1, null, 1), listOf(opening.edge, opening.outgoing, opening.incoming))
        assertEquals(4.0..4.5, opening.range)
        assertEquals(4.0, opening.time, tiny)
        assertEquals(listOf(close, opening).map { it.index }.sorted(), TimelineEditing.transitions(swapped).map { it.index })
        // The picture goes to black and comes back from it; the sound goes down and comes up with it.
        val leaving = TransitionScene.moment(swapped, 3.75)
        assertEquals(Source.Clip(0, 0, 3.75), leaving.first)
        assertEquals(Source.Fill(TransitionColor.BLACK), leaving.second)
        assertEquals(TransitionFrame.Dissolve(0.5), leaving.frame)
        val arriving = TransitionScene.moment(swapped, 4.25)
        assertEquals(Source.Fill(TransitionColor.BLACK), arriving.first)
        assertEquals(Source.Clip(1, 0, 0.25), arriving.second)
        assertEquals(0.5, TimelineEditing.soundLevel(swapped, 0, 3.75), tiny)
        assertEquals(0.5, TimelineEditing.soundLevel(swapped, 1, 0.25), tiny)
        assertEquals(1.0, TimelineEditing.soundLevel(swapped, 1, 2.0), tiny)
        // The clips still follow one another, so one player runs them.
        assertEquals(1, TimelineEditing.chains(swapped).size)
        // The previous and next buttons stop where each fade begins and ends (TimelineNavigation.swift: every join with a transition).
        assertEquals(listOf(0.0, 3.5, 4.0, 4.5, 8.0), TimelineNavigation.of(EditState(swapped)).cutsAndChanges)
        // A clip put before the first or after the last leaves the fade with its picture; it is at the end again when that clip goes.
        val c = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0)
        val before = TimelineEditing.insert(state, listOf(c), 0.0)!!
        assertEquals(listOf("C", "A", "B"), before.clips.map { it.id })
        assertEquals(state.clips[0].openingTransition, before.clips[1].openingTransition)
        assertEquals(2.0..2.5, TimelineEditing.join(before.clips, TransitionLayout.openingIndex(1, 3))!!.range)
        assertEquals(10.0, before.duration, tiny)
        assertEquals(state.clips, TimelineEditing.delete(before, 0)!!.clips)
        val after = TimelineEditing.insert(state, listOf(c), state.duration)!!
        assertEquals(listOf("A", "B", "C"), after.clips.map { it.id })
        assertEquals(state.clips[1].transition, after.clips[1].transition)
        assertEquals(7.5..8.0, TimelineEditing.join(after.clips, TransitionLayout.closeIndex(1, 3))!!.range)
        assertEquals(10.0, after.duration, tiny)
        assertEquals(state.clips, TimelineEditing.delete(after, 2)!!.clips)
        // In the middle both ends are kept.
        val middle = TimelineEditing.insert(state, listOf(c), 4.0)!!.clips
        assertNotNull(middle[0].openingTransition)
        assertNotNull(middle[2].transition)
        // A take recorded later lands after the closing clip: the close plays on with its clip.
        val appended = state.clips + c
        assertNull(TimelineEditing.joinSettings(appended, 3))
        assertEquals(state.clips[1].transition, TimelineEditing.joinSettings(appended, TransitionLayout.closeIndex(1, 3)))
        assertEquals(10.0, TimelineEditing.duration(appended), tiny)
        assertNotNull(TimelineEditing.joinSettings(TimelineEditing.delete(EditState(appended), 2)!!.clips, 2))
    }

    @Test fun aTransitionBetweenTwoClipsTakesThePlaceOfTheirOpeningAndClose() {
        var state = TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.setJoin(state, 2, TransitionSettings(duration = 0.5))!!
        val swapped = EditState(TimelineEditing.move(state, 0, 1)!!.clips)
        // Each picture has one transition at its start and one at its end; a new one replaces the fades on those sides (Transitions.swift:343-347).
        val joinedUp = TimelineEditing.setJoin(swapped, 1, TransitionSettings(duration = 1.0))!!.clips
        assertEquals("A", joinedUp[0].transition!!.nextClipID)
        assertNull(joinedUp[1].openingTransition)
        assertNull(joinedUp[1].openingTransitionFit)
        assertTrue(TimelineEditing.layout(joinedUp).fades.isEmpty())
        assertEquals(7.0, TimelineEditing.duration(joinedUp), tiny)
        // One away from the ends is changed and removed under its own number, and is not made there.
        val opening = TransitionLayout.openingIndex(1, 2)
        val longer = TimelineEditing.setJoin(swapped, opening, TransitionSettings(duration = 1.0))!!.clips
        assertEquals(1.0, longer[1].openingTransition!!.duration, tiny)
        assertEquals("A", longer[1].openingTransition!!.nextClipID)
        assertEquals(4.0..5.0, TimelineEditing.join(longer, opening)!!.range)
        assertNull(TimelineEditing.setJoin(swapped, opening, null)!!.clips[1].openingTransition)
        assertNull(TimelineEditing.setJoin(pair, opening, TransitionSettings()))
        assertNull(TimelineEditing.setJoin(pair, TransitionLayout.closeIndex(0, 2), TransitionSettings()))
        // It keeps to the footage the transition at the clip's other end leaves it.
        val close = TransitionLayout.closeIndex(0, 2)
        assertEquals(1.0, TimelineEditing.join(TimelineEditing.setJoin(swapped, close, TransitionSettings(duration = 9.0))!!.clips, close)!!.factor * 9.0, 4.0 + tiny)
        assertEquals(4.0, TimelineEditing.join(TimelineEditing.setJoin(swapped, close, TransitionSettings(duration = 9.0))!!.clips, close)!!.timing.videoDuration, 1e-6)
    }

    @Test fun aFreezeThatOpensOrClosesAClipIsTimeOfItsOwnWhereverTheClipIs() {
        val c = TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0)
        // An opening still holds black for a second before its clip (TransitionTests.swift:288); with a clip before it, after that clip.
        val opened = TimelineEditing.setJoin(pair, 0, TransitionSettings(style = TransitionStyle.Still, duration = 1.0))!!
        assertEquals(9.0, opened.duration, tiny)
        assertEquals(1.0, TimelineEditing.layout(opened.clips).joins[0].time, tiny)
        val before = TimelineEditing.insert(opened, listOf(c), 0.0)!!.clips
        assertEquals(listOf(0.0, 3.0, 7.0), before.indices.map { TimelineEditing.clipStart(before, it) })
        assertEquals(11.0, TimelineEditing.duration(before), tiny)
        assertEquals(2.0..3.0, TimelineEditing.join(before, TransitionLayout.openingIndex(1, 3))!!.range)
        val black = TransitionScene.moment(before, 2.5)
        assertEquals(Source.Fill(TransitionColor.BLACK), black.first)
        assertEquals(TransitionFrame.First, black.frame)
        assertEquals(TimelineEditing.Location(1, 0.0), TimelineEditing.locate(before, 2.5))
        assertEquals(TimelineEditing.Location(0, 2.0), TimelineEditing.locate(before, 1.999999).let { it!!.copy(offset = Math.rint(it.offset)) })
        // A closing still holds the last frame after its clip; a clip put after it begins when the freeze is over.
        val closed = TimelineEditing.setJoin(pair, 2, TransitionSettings(style = TransitionStyle.Still, duration = 1.0))!!
        assertEquals(9.0, closed.duration, tiny)
        assertEquals(8.0, TimelineEditing.layout(closed.clips).joins[2].time, tiny)
        val after = TimelineEditing.insert(closed, listOf(c), closed.duration)!!.clips
        assertEquals(listOf("A", "B", "C"), after.map { it.id })
        assertEquals(listOf(0.0, 4.0, 9.0), after.indices.map { TimelineEditing.clipStart(after, it) })
        assertEquals(11.0, TimelineEditing.duration(after), tiny)
        val held = TransitionScene.moment(after, 8.5)
        assertTrue(held.first is Source.Held)
        assertEquals(1, (held.first as Source.Held).index)
        assertEquals(TransitionFrame.First, held.frame)
        assertEquals(TimelineEditing.Location(1, 4.0), TimelineEditing.locate(after, 8.5))
        assertEquals(TimelineEditing.Location(2, 0.0), TimelineEditing.locate(after, 9.0))
        // Two chains: the freeze stands between them, as a freeze between two clips does.
        assertEquals(2, TimelineEditing.chains(after).size)
    }

    @Test fun aProjectWithAnOpeningAwayFromTheStartStaysInTheSameFormat() {
        var state = TimelineEditing.setJoin(pair, 0, TransitionSettings(duration = 0.5))!!
        state = TimelineEditing.insert(state, listOf(TimelineClip(id = "C", sourceID = "S", start = 0.0, end = 2.0)), 0.0)!!
        val text = documentJson.encodeToString(EditState.serializer(), state)
        assertEquals(state, documentJson.decodeFromString(EditState.serializer(), text))
        assertEquals(13, state.clips.maxOf { it.schema })
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

    @Test fun aMovieHasTheFrameRateOfItsSettingsWhateverTheFootage() {
        // O/Project.swift:663 (24, 25, 30, 50, 60), O/TrackRenderer.swift:164 (a frame every 1/fps), O/MediaRenderer.swift:750.
        for (setting in ExportSettings.FRAME_RATES) assertEquals(setting, TransitionScene.frameRate(setting))
        // A setting the original does not have is its default, 30 (O/Project.swift:667).
        assertEquals(30, TransitionScene.frameRate(29))
        assertEquals(30, TransitionScene.frameRate(0))
        assertEquals(30, TransitionScene.frameRate(120))
    }

    @Test fun aFlashIsOneFrameOfTheMovieAtItsRate() {
        val clips = joined(TransitionSettings(style = TransitionStyle.FlashCut, duration = 0.5))
        val join = TimelineEditing.join(clips, 1)!!
        for (setting in ExportSettings.FRAME_RATES) {
            val rate = TransitionScene.frameRate(setting)
            val flashes = join.transition!!.flashFrames(join.timing.videoDuration, rate.toDouble())
            val shown = (0 until (join.timing.videoDuration * rate).toInt()).filter { frame ->
                // The export draws the frame on tick n of the movie at n / rate, a microsecond later.
                TransitionScene.moment(clips, join.origin + frame.toDouble() / rate + 1e-6, frames = { rate.toDouble() }).frame == TransitionFrame.Second
            }
            assertEquals("at $setting", flashes, shown)
        }
    }
}
