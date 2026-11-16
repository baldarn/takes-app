package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The render plan made from the track timeline ([TrackRenderPlan]) against
 * the one made from the saved sequence ([RenderPlan.of]): the gate of stage
 * S3a on the host (docs/TRACKS-PLAN.md). The two are equal, field for field
 * and in everything a plan answers, for every step of the history of every
 * golden project, for the edits the parity suite is run on and for what the
 * editing functions answer on them.
 */
class TrackRenderPlanTest {
    private val tick = TransitionLayout.TICK
    private val full = listOf("TAKES_PARITY_GATE", "TAKES_PARITY_FULL", "TAKES_RECORD_PARITY").any { System.getenv(it) == "1" }

    /** Every edit short enough to be asked many questions: the fixtures of the track timeline, the parity suite's edits, and every step of the golden histories but the long one. */
    private val edits: List<Pair<String, EditState>> by lazy {
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            (TrackParityCases.probes + TrackParityCases.generated).map { it.name to it.state } +
            GoldenProjects.all.filter { !it.large }.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
    }

    // MARK: Comparing two plans

    private fun differing(a: RenderPlan, b: RenderPlan): List<String> = listOfNotNull(
        "clips".takeIf { a.clips != b.clips }, "sounds".takeIf { a.sounds != b.sounds }, "duration".takeIf { a.duration != b.duration },
        "pictures".takeIf { a.pictures != b.pictures }, "chains".takeIf { a.chains != b.chains }, "decks".takeIf { a.decks != b.decks },
        "sequences".takeIf { a.sequences != b.sequences }, "transitions".takeIf { a.transitions != b.transitions }, "freezes".takeIf { a.freezes != b.freezes },
        "endings".takeIf { a.endings != b.endings }, "titles".takeIf { a.titles != b.titles }, "subtitles".takeIf { a.subtitles != b.subtitles },
        "captions".takeIf { a.captions != b.captions }, "soundtracks".takeIf { a.soundtracks != b.soundtracks }, "cleaned".takeIf { a.cleaned != b.cleaned },
    )

    /** Moments from before the movie to after it, [count] evenly and, with [edges], where every clip begins, is halfway and ends. */
    private fun moments(plan: RenderPlan, count: Int, edges: Boolean): List<Double> =
        List(count + 1) { -0.25 + (plan.duration + 0.5) * it / count } +
            if (edges) plan.pictures.flatMap { listOf(it.start, it.start + it.duration / 2, it.end) } else emptyList()

    /** What the picture of a plan answers at [times]: what each deck plays and how loud, the titles, the captions and the frame. */
    private fun picture(plan: RenderPlan, times: List<Double>): List<Any?> = times.flatMap { time ->
        (0 until RenderPlan.DECKS).flatMap { deck -> listOf(plan.playing(deck, time), plan.onDeck(deck, time)?.let { plan.clipLevel(it.index, time - it.start) }) } +
            listOf(plan.titlesAt(time), plan.captionsAt(time), plan.moment(time))
    }

    /** What the sound tracks of a plan answer at [times]: the sound each plays and how loud. */
    private fun tracks(plan: RenderPlan, times: List<Double>): List<Any?> = times.flatMap { time ->
        (0 until AudioItem.TRACKS).flatMap { track -> TimelineEditing.audioAt(plan.sounds, track, time).let { listOf(it, it?.let { item -> plan.soundLevel(item, time - item.start) }) } }
    }

    /** The clips whose cleaned sound may be at hand: every one that asks, none, every other clip, and all that ask but the first. */
    private fun cleanings(state: EditState): List<Set<Int>?> {
        val asking = state.clips.indices.filter { (state.clips[it].audio?.denoise ?: 0.0) > 0 }
        return listOf(null, emptySet(), state.clips.indices.filter { it % 2 == 0 }.toSet(), asking.drop(1).toSet())
    }

    /** The plan of [state] from its timeline is the plan from its clips: as data, and in what it answers. */
    private fun assertPlannedAlike(name: String, state: EditState, moments: Int = 32, edges: Boolean = true, cleanings: List<Set<Int>?> = cleanings(state)) {
        for (cleaned in cleanings) {
            val today = RenderPlan.of(state, cleaned)
            val made = TrackRenderPlan.of(state, cleaned)
            assertEquals("$name, cleaned $cleaned: the fields that differ", emptyList<String>(), differing(today, made))
            assertTrue("$name, cleaned $cleaned", today == made)
            if (moments == 0) continue
            val times = moments(today, moments, edges)
            assertTrue("$name, cleaned $cleaned: the picture", picture(today, times) == picture(made, times))
            assertTrue("$name, cleaned $cleaned: the sound tracks", tracks(today, times) == tracks(made, times))
            assertEquals(name, today.clips.indices.map { index -> List(5) { today.clipLevel(index, today.pictures[index].duration * it / 4) } }, made.clips.indices.map { index -> List(5) { made.clipLevel(index, made.pictures[index].duration * it / 4) } })
        }
    }

    // MARK: The gate

    @Test fun thePlanFromTheTimelineIsThePlanFromTheSequenceForEveryEdit() {
        for ((name, state) in edits) assertPlannedAlike(name, state)
        println("The plan from the timeline: ${edits.size} edits, each planned both ways and equal")
        assertTrue(edits.size > 400)
    }

    @Test fun thePlanFromTheTimelineIsThePlanFromTheSequenceForEveryStepOfTheLongProject() {
        val long = GoldenProjects.all.single { it.large }.project
        assertEquals(300, long.history.size)
        long.history.forEachIndexed { index, state ->
            // Every step as data; the questions, which each look through two thousand clips, at fewer moments and of one step in ten.
            assertPlannedAlike("long, step $index", state, moments = if (index % 10 == 0 || index == long.history.lastIndex) 12 else 0, edges = false, cleanings = listOf(null, emptySet()))
        }
        val edit = long.edit
        assertEquals(2000, edit.clips.size)
        assertPlannedAlike("long", edit, moments = 48, edges = false)
    }

    /**
     * What each editing function answers on each edit of the parity suite,
     * with the calls that suite makes ([TrackParityReport]): the edits a
     * person can reach in one more step. The ordinary run takes the suite's
     * sample of the edits; with `TAKES_PARITY_GATE=1` or `TAKES_PARITY_FULL=1`
     * every edit is taken.
     */
    @Test fun thePlanFromTheTimelineIsThePlanFromTheSequenceForWhatEveryEditingFunctionAnswers() {
        class Group(val subjects: List<TrackParity.Subject>, val samples: Int, val seed: Long, val every: Int)
        val groups = listOf(
            Group(TrackParityCases.probes, 3, 11, 1),
            Group(TrackParityCases.golden, 2, 23, TrackParityReport.GOLDEN_EVERY),
            Group(TrackParityCases.generated, 1, 37, TrackParityReport.GENERATED_EVERY),
        )
        var answered = 0
        val seen = HashSet<EditState>()
        for (group in groups) group.subjects.forEachIndexed { index, subject ->
            if (!full && index % group.every != 0) return@forEachIndexed
            val rng = TrackFixtures.Random(group.seed + index * 104729L)
            for (run in TrackParityCases.runs(subject, rng, group.samples)) {
                val state = run.track()?.state ?: continue
                answered++
                if (!seen.add(state)) continue
                assertPlannedAlike("${subject.name}: ${run.function} ${run.label}", state, moments = 8, edges = false, cleanings = listOf(null))
            }
        }
        println("The plan from the timeline: $answered answers of the editing functions, ${seen.size} different edits, each planned both ways and equal")
        assertTrue(seen.size > 1000)
    }

    // MARK: What a timeline does not hold, and the saved sequence does

    private val a = TimelineClip(id = "A", sourceID = "S", start = 0.0, end = 4.0)
    private val b = TimelineClip(id = "B", sourceID = "S", start = 10.0, end = 13.0)
    private val c = TimelineClip(id = "C", sourceID = "S", start = 20.0, end = 25.0)

    /** [state] is saved in a way its timeline does not read back, and is planned alike all the same; the plan keeps the clips as they are saved. */
    private fun assertKeptAsSaved(name: String, state: EditState) {
        val read = TrackWrapping.seen(state.toTimeline())!!
        assertTrue("$name: the timeline reads back what is saved, so this tests nothing", read.clips != state.clips || read.items != state.audio.orEmpty() || read.subtitles != state.subtitles)
        assertPlannedAlike(name, state)
        assertEquals(name, state.clips, TrackRenderPlan.of(state).clips)
        assertEquals(name, state.audio.orEmpty(), TrackRenderPlan.of(state).sounds)
    }

    @Test fun aProjectSavedBeforeFitsWereKeptIsPlannedAsItsLayoutFitsIt() {
        // Two dissolves that together ask for more than the middle clip has, an opening and a close: none with a fit saved.
        val clips = listOf(
            a.copy(openingTransition = TransitionSettings(id = "T0", style = TransitionStyle.FadeColor, duration = 1.0), transition = TransitionSettings(id = "T1", nextClipID = "B", duration = 2.5)),
            b.copy(transition = TransitionSettings(id = "T2", nextClipID = "C", style = TransitionStyle.Wipe, duration = 2.5)),
            c.copy(transition = TransitionSettings(id = "T3", style = TransitionStyle.Still, duration = 1.0)),
        )
        val state = EditState(clips)
        assertTrue(TimelineEditing.layout(clips).joins.any { it.transition != null && it.factor < 0.999 })
        assertKeptAsSaved("no fits", state)
        // One fit saved and the others not: the layout starts from the saved one.
        assertKeptAsSaved("one fit", state.copy(clips = clips.mapIndexed { index, clip -> if (index == 0) clip.copy(transitionFit = TransitionFit(0.4)) else clip }))
    }

    @Test fun settingsOfATransitionThatDoesNotPlayStayWithTheClipAndChangeNothing() {
        val state = EditState(
            listOf(
                // It names a clip that is not the next one.
                a.copy(transition = TransitionSettings(id = "T1", nextClipID = "C", duration = 1.0), transitionFit = TransitionFit(1.0)),
                b.copy(transition = TransitionSettings(id = "T2", nextClipID = "C", duration = 1.0), transitionFit = TransitionFit(1.0)),
                // An opening on a clip a transition leads into, and what the last clip has saved for a clip after it.
                c.copy(openingTransition = TransitionSettings(id = "T3", style = TransitionStyle.FadeColor, duration = 1.0), transition = TransitionSettings(id = "T4", nextClipID = "A", duration = 1.0)),
            ),
        )
        assertEquals(listOf("T2"), RenderPlan.of(state).transitions.map { it.transition!!.id })
        assertKeptAsSaved("dead settings", state)
        assertKeptAsSaved("settings out of range", EditState(listOf(a.copy(transition = TransitionSettings(id = "T1", nextClipID = "B", duration = -1.0)), b)))
    }

    @Test fun soundSettingsThatSayNothingAreKeptAsTheyAreSaved() {
        // Written out though they are the defaults, and a fade with the nothing a cut left behind it written out.
        val state = EditState(
            listOf(a.copy(audio = ClipAudio()), b.copy(audio = ClipAudio(fadeIn = 1.0, fadeInElapsed = 0.0)), c),
            audio = listOf(AudioItem("M", "MUSIC", 1.0, 0.0, 6.0, audio = ClipAudio(fadeOut = 2.0, fadeOutElapsed = 0.0, denoise = 0.5))),
        )
        assertTrue(RenderPlan.of(state).pictures[0].shaped)
        assertKeptAsSaved("sound settings", state)
        assertKeptAsSaved("bookmarks", EditState(listOf(a.copy(bookmarks = emptyList()), b.copy(bookmarks = listOf(Bookmark("MARK", 11.0, ""))))))
    }

    @Test fun whatAMutedClipRemembersOfItsUnlinkedSoundIsKept() {
        val unlinked = AudioUnlinking.unlink(EditState(listOf(a, b.copy(audio = ClipAudio(volumeDB = -6.0, fadeIn = 0.5)), c)), 1)!!
        assertTrue(unlinked.clips[1].audio!!.muted && unlinked.clips[1].audio!!.volumeDB == -6.0)
        assertKeptAsSaved("unlinked", unlinked)
        // A noise reduction it is given while its sound is elsewhere: today's plan cleans the sound of the muted clip, and so does this one.
        val cleaning = unlinked.copy(clips = unlinked.clips.mapIndexed { index, clip -> if (index == 1) clip.copy(audio = clip.audio!!.copy(denoise = 0.6)) else clip })
        assertTrue(RenderPlan.of(cleaning).pictures[1].soundRemoved)
        assertKeptAsSaved("unlinked, with a noise reduction", cleaning)
    }

    @Test fun soundItemsKeepTheirSecondTheirTrackAndTheirOrder() {
        val state = EditState(
            listOf(a, b, c),
            audio = listOf(
                // Off the tick clock, on the second track alone, later ones first, one under the id of a clip, one past the picture.
                AudioItem("N", "MUSIC", 7.0000013, 2.0, 5.5, track = 1, audio = ClipAudio(volumeDB = -3.0, fadeIn = 1.0)),
                AudioItem("M", "MUSIC", 0.1234567891, 0.0, 6.0, track = 1),
                AudioItem("A", "VOICE", 3.3333333, 1.0, 2.0),
                AudioItem("P", "MUSIC", 11.5, 0.0, 30.0),
            ),
        )
        assertKeptAsSaved("sound items", state)
        assertEquals(listOf(1, 1, 0, 0), TrackRenderPlan.of(state).sounds.map { it.track })
        // Sound without a picture plays nothing: the movie is as long as its picture.
        assertPlannedAlike("sound alone", EditState(audio = state.audio))
        assertPlannedAlike("nothing", EditState())
    }

    @Test fun captionsKeepWhatTheyWereTimedFor() {
        val words = listOf(SubtitleWord("W1", "P1", "Hello", 0.5, 0.9), SubtitleWord("W2", "P1", "there", 1.0, 1.4))
        for (fingerprint in listOf("", "of clips that are gone", SubtitleEditing.fingerprint(listOf(a.copy(subtitleWords = words), b)))) {
            val state = EditState(listOf(a.copy(subtitleWords = words), b), subtitles = SubtitleSettings(id = "CAPTIONS", timingFingerprint = fingerprint))
            assertPlannedAlike("captions timed for \"$fingerprint\"", state)
            assertEquals(fingerprint, TrackRenderPlan.of(state).subtitles!!.timingFingerprint)
            assertEquals(2, TrackRenderPlan.of(state).captions.sumOf { it.text.split(" ").size })
        }
    }

    // MARK: The plan of a timeline by itself

    /**
     * From the timeline alone, with nothing of the saved sequence: the same
     * picture in every field, and the same sound but for what a timeline does
     * not keep, where a sound item begins to less than a tick and which of
     * the two tracks it is on.
     */
    @Test fun aTimelineByItselfPlansTheSamePictureAndTheSameSoundToTheTick() {
        for ((name, state) in edits) {
            val today = RenderPlan.of(state)
            val made = checkNotNull(TrackRenderPlan.of(state.toTimeline())) { "$name: its timeline is no sequence" }
            assertEquals("$name: the fields that differ", emptyList<String>(), differing(today, made) - setOf("clips", "sounds", "subtitles", "soundtracks"))
            assertEquals(name, today.subtitles?.copy(timingFingerprint = ""), made.subtitles?.copy(timingFingerprint = ""))
            // The clips lay out and sound the same.
            assertEquals(name, TimelineEditing.layout(today.clips), TimelineEditing.layout(made.clips))
            val times = moments(today, 32, edges = true)
            assertTrue("$name: the picture", picture(today, times) == picture(made, times))
            // The sound items: each the same piece of the same file with the same settings, on the tick nearest to where it was.
            val items = made.sounds.associateBy { it.id }
            assertEquals(name, today.sounds.map { it.id }.sorted(), made.sounds.map { it.id }.sorted())
            for (item in today.sounds) {
                val other = items.getValue(item.id)
                assertEquals(name, listOf(item.sourceID, item.sourceStart, item.sourceEnd), listOf(other.sourceID, other.sourceStart, other.sourceEnd))
                assertEquals("$name: sound ${item.id}", item.start, other.start, tick / 2 + 1e-9)
                assertEquals(name, List(9) { today.soundLevel(item, item.duration * it / 8) }, List(9) { made.soundLevel(other, other.duration * it / 8) })
            }
            fun carried(plan: RenderPlan) = plan.soundtracks.flatMap { it.entries }.filterIsInstance<RenderPlan.Entry.Sound>().map { Triple(it.item.id, it.sourceStartUs, it.sourceEndUs) }.sortedBy { it.first }
            assertEquals("$name: the sounds an export carries", carried(today).map { it.first }, carried(made).map { it.first })
            for ((one, other) in carried(today).zip(carried(made))) {
                assertEquals(name, one.second, other.second)
                assertTrue(name, abs(one.third - other.third) <= 3)
            }
            // What is heard of the two tracks together, away from where a sound begins or ends.
            val edges = today.sounds.flatMap { listOf(it.start, it.end) }
            for (time in times.filter { time -> edges.none { abs(it - time) < 0.001 } }) {
                fun level(plan: RenderPlan, track: Int) = TimelineEditing.audioAt(plan.sounds, track, time)?.let { plan.soundLevel(it, time - it.start) } ?: 0.0
                val heard = (0 until AudioItem.TRACKS).map { level(today, it) }.sorted()
                val now = (0 until AudioItem.TRACKS).map { level(made, it) }.sorted()
                for ((one, other) in heard.zip(now)) assertEquals("$name at $time", one, other, 0.0001)
            }
        }
    }

    @Test fun aTimelineByItselfPlansTheLongProject() {
        val edit = GoldenProjects.all.single { it.large }.project.edit
        val made = TrackRenderPlan.of(edit.toTimeline(), emptySet())!!
        assertEquals(emptyList<String>(), differing(RenderPlan.of(edit, emptySet()), made) - setOf("clips", "sounds", "subtitles", "soundtracks"))
    }

    @Test fun aTimelineThatIsNoSequenceHasNoPlanYet() {
        val state = TrackFixtures.plain()
        val timeline = state.toTimeline()
        assertNotNull(TrackRenderPlan.of(timeline))
        // Time between two pictures, where a sequence has none.
        val later = EditTime.of(1.0)
        val last = timeline.video.last().id
        val apart = timeline.copy(
            video = timeline.video.map { if (it.id == last) it.copy(start = it.start + later) else it },
            audio = timeline.audio.map { if (it.followsVideo == last) it.copy(start = it.start + later) else it },
        )
        assertNull(TrackInvariants.violation(apart, TrackFixtures.sources))
        assertNull(TrackRenderPlan.of(apart))
        // Two pictures at once without a transition between them.
        val over = timeline.copy(
            video = timeline.video.map { if (it.id == last) it.copy(start = it.start - later) else it },
            audio = timeline.audio.map { if (it.followsVideo == last) it.copy(start = it.start - later) else it },
        )
        assertNull(TrackRenderPlan.of(over))
        // A third sound at once.
        val music = AudioTimelineItem(id = "M1", occurrenceID = "M1", sourceID = TrackFixtures.music.id, start = EditTime.of(1.0), mapping = SourcePlaybackMap.single(EditTime.of(5.0), 0.0, 5.0))
        assertNotNull(TrackRenderPlan.of(timeline.copy(audio = timeline.audio + music + music.copy(id = "M2", occurrenceID = "M2"))))
        assertNull(TrackRenderPlan.of(timeline.copy(audio = timeline.audio + music + music.copy(id = "M2", occurrenceID = "M2") + music.copy(id = "M3", occurrenceID = "M3"))))
    }

    /**
     * Tests/TrackTimelineTests.swift:936-938: "Moved fades are anchored to
     * their own picture", "A moved opening fade leaves the adjacent cut
     * available", as the plan draws them. The original moves the picture
     * twelve seconds later and leaves time before it; here the clip is moved
     * to the end, where the clips still follow one another and the fade
     * plays where the picture now begins. The original's own move, with its
     * gap, is pinned on the timeline in TrackTransitionsTest; a timeline with
     * a gap has no plan of two decks.
     */
    @Test fun aMovedOpeningIsDrawnOnItsOwnPicture() {
        val ids = List(3) { TrackFixtures.uuid(700 + it) }
        val clips = List(3) { TimelineClip(id = TrackFixtures.uuid(700 + it), sourceID = TrackFixtures.sources.first().id, start = 0.0, end = 4.0) }
        val opened = TimelineEditing.setJoin(EditState(clips), 0, TransitionSettings(duration = 1.0))!!
        val moved = TimelineEditing.move(opened, 0, 2)!!
        assertEquals(listOf(ids[1], ids[2], ids[0]), moved.clips.map { it.id })
        val timeline = moved.toTimeline()
        assertEquals(listOf(null to ids[0]), timeline.transitions.map { it.outgoing to it.incoming })
        for (plan in listOf(TrackRenderPlan.of(moved), TrackRenderPlan.of(timeline)!!)) {
            val fade = plan.transitions.single()
            assertEquals(listOf(-1, null, ids[0]), listOf(fade.edge, fade.outgoingID, fade.incomingID))
            assertEquals(8.0, fade.time, 0.0)
            assertEquals(8.0, fade.origin, 0.0)
            // Nothing at the start of the movie, and the cut before the moved picture is a cut.
            assertEquals(TransitionFrame.First, plan.moment(0.25).frame)
            val before = plan.moment(7.9)
            assertNull(before.join)
            assertEquals(1, (before.first as TransitionScene.Source.Clip).index)
            val boundary = TimelineEditing.layout(plan.clips).joins[2]
            assertEquals(listOf(null, ids[2], ids[0]), listOf(boundary.transition, boundary.outgoingID, boundary.incomingID))
            // The picture comes from black on its own clip.
            val arriving = plan.moment(8.25)
            assertEquals(TransitionScene.Source.Fill(TransitionColor.BLACK), arriving.first)
            assertEquals(TransitionScene.Source.Clip(2, plan.deckOf[2], 0.25), arriving.second)
            assertEquals(1.0, plan.moment(10.0).firstShare, 0.0)
        }
        assertEquals(8.0, timeline.transitions.single().origin.seconds, tick)
    }

    // MARK: The plan against what the timeline compiles to

    /**
     * The plan is in the seconds of the layout and the timeline in ticks, so
     * the two are not compared as data. In every stretch the timeline
     * compiles to ([TrackTimeline.compile]) the plan plays the pictures the
     * timeline shows there, each on a deck of its own, with the sounds and
     * the transitions the timeline has under way; every picture is where its
     * item is to within a tick, and every transition is its relation.
     */
    @Test fun thePlanPlaysInEveryStretchWhatTheTimelineCompilesTo() {
        var stretches = 0
        for ((name, state) in edits) {
            val timeline = state.toTimeline()
            val plan = TrackRenderPlan.of(state)
            assertEquals(name, timeline.video.map { it.id }, plan.pictures.map { it.id })
            plan.pictures.forEachIndexed { index, picture ->
                val item = timeline.video[index]
                assertEquals("$name: picture $index begins", item.start.seconds, picture.start, tick / 2 + 1e-9)
                assertEquals("$name: picture $index ends", item.end.seconds, picture.end, 2 * tick)
                assertEquals(name, listOf(item.mapping.runs.single().sourceStart, item.mapping.runs.single().sourceEnd), listOf(picture.sourceStart, picture.sourceEnd))
            }
            // The movie is as long as its picture and what its transitions add, not as its sound.
            val pictured = maxOf(timeline.video.maxOfOrNull { it.end } ?: EditTime.ZERO, timeline.generated.maxOfOrNull { it.interval.end } ?: EditTime.ZERO)
            assertEquals("$name: duration", pictured.seconds, plan.duration, 2 * tick)

            val joins = TimelineEditing.layout(plan.clips).all.filter { it.transition != null }
            assertEquals(name, timeline.transitions.map { it.id }.sorted(), joins.map { it.transition!!.id }.sorted())
            for (relation in timeline.transitions) {
                val join = joins.single { it.transition!!.id == relation.id }
                assertEquals(name, relation.factor, join.factor, 0.0)
                assertEquals("$name: transition ${relation.id}", relation.origin.seconds, join.origin, 2 * tick)
                assertEquals(name, listOf(relation.outgoing, relation.incoming), listOf(join.outgoing?.let { plan.clips[it].id }, join.incoming?.let { plan.clips[it].id }))
            }
            assertEquals(name, joins.filter { TransitionLayout.longEnough(it.timing) }.map { it.transition!!.id }, plan.transitions.map { it.transition!!.id })
            // The plan takes each transition by the pictures its relation names, and so does the plan of the timeline by itself.
            for (join in plan.transitions) {
                val relation = timeline.transitions.single { it.id == join.transition!!.id }
                assertEquals("$name: ${relation.id} relates", listOf(relation.outgoing, relation.incoming), listOf(join.outgoingID, join.incomingID))
                val (outgoing, incoming) = TransitionScene.related(plan.clips, join)
                assertEquals(name, listOf(relation.outgoing, relation.incoming), listOf(outgoing?.let { plan.clips[it].id }, incoming?.let { plan.clips[it].id }))
            }
            assertTrue(name, TrackRenderPlan.related(timeline, plan))

            val followed = timeline.audio.mapNotNull { it.followsVideo }.toSet()
            for (part in timeline.compile()) {
                // The middle of a stretch is away from every edge by more than the ticks the two clocks differ by.
                if (part.interval.duration.ticks < 12) continue
                stretches++
                val time = (part.interval.start.seconds + part.interval.end.seconds) / 2
                val showing = plan.pictures.filter { it.start <= time && time < it.end }
                assertEquals("$name at $time: pictures", part.video.toSet(), showing.map { it.id }.toSet())
                assertEquals("$name at $time: a deck each", showing.size, showing.map { it.deck }.toSet().size)
                for (picture in showing) assertEquals("$name at $time: deck ${picture.deck}", picture.chain to picture.index, plan.playing(picture.deck, time))
                if (time < plan.duration) for (deck in 0 until RenderPlan.DECKS) {
                    if (showing.none { it.deck == deck }) assertTrue("$name at $time: deck $deck", plan.playing(deck, time).let { it == null || time >= plan.pictures[it.second].end })
                }
                assertEquals(
                    "$name at $time: transitions", part.transitionIDs.toSet(),
                    joins.filter { time >= it.range.start && time < it.range.endInclusive }.map { it.transition!!.id }.toSet(),
                )
                // Past its picture the movie holds its last frame, and a close goes on being drawn.
                if (time < plan.duration) plan.moment(time).join?.let { assertTrue("$name at $time: the transition drawn", it.transition!!.id in part.transitionIDs) }
                val own = showing.filter { it.id in followed }.map { TrackTimeline.counterpart(it.id) }
                val apart = state.audio.orEmpty().filter { it.start <= time && time < it.end }.map { TrackWrapping.soundID(state, it.id) }
                assertEquals("$name at $time: sounds", part.audio.toSet(), (own + apart).toSet())
            }
        }
        assertTrue(stretches > 2000)
    }

    // MARK: A long project

    @Test fun aLongProjectIsPlannedFromItsTimelineWithinItsBudget() {
        val state = GoldenProjects.all.single { it.large }.project.edit
        assertEquals(2000, state.clips.size)
        assertSame(state.clips, TrackRenderPlan.of(state).clips)
        // Warm, the best of several runs.
        fun best(work: () -> Unit): Double {
            repeat(40) { work() }
            return (0 until 20).minOf {
                val begun = System.nanoTime()
                work()
                (System.nanoTime() - begun) / 1e6
            }
        }
        // An edit is a new list of clips, so each run lays its clips out anew, as the first plan after an edit does.
        fun fresh() = state.copy(clips = state.clips.toList())
        val today = best { RenderPlan.of(fresh()) }
        val made = best { TrackRenderPlan.of(fresh()) }
        // The plan asked for again of the same edit, as the preview and the export ask: the conversion is at hand.
        val again = best { TrackRenderPlan.of(state) }
        val alone = state.toTimeline().let { timeline -> best { TrackRenderPlan.of(timeline) } }
        // What the timeline adds to a plan: the conversion, the reading back and the comparison with what is saved. It is
        // held to the budget docs/TRACKS-PLAN.md, 7.2 gives compile, lanes and layout of the same project, 5 ms on a
        // machine that is free, stretched by how much longer than usual a fixed piece of arithmetic takes here.
        var sum = 0.0
        val yardstick = best { sum = 0.0; for (index in 0 until 1_000_000) sum += Math.sqrt(index.toDouble()) }
        val allowed = BUDGET_MILLISECONDS * maxOf(1.0, yardstick / YARDSTICK_MILLISECONDS)
        val report = "the plan of 2,000 clips: from the sequence $today ms, from the timeline $made ms (${made - today} ms more; $allowed ms allowed), " +
            "again of the same edit $again ms, of the timeline by itself $alone ms (yardstick $yardstick ms, $sum)"
        // A wall-clock budget fails by chance on a shared machine; it is enforced only where someone asks (TAKES_STRICT_TIMING=1).
        if (System.getenv("TAKES_STRICT_TIMING") == "1") assertTrue(report, made - today < allowed) else println(report)
        assertNotEquals(0.0, sum, 0.0)
    }

    private companion object {
        const val BUDGET_MILLISECONDS = 5.0

        /** What a million square roots, summed, take on a free machine of the kind the budget was written for. */
        const val YARDSTICK_MILLISECONDS = 1.5
    }
}
