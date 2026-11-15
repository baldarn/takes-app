package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * What the preview took from the clips before it took it from a
 * [RenderPlan] (docs/TRACKS-PLAN.md, S3a), kept here as it was written in
 * the editor, and the plan held to it at many moments of every step of every
 * golden project and of the fixtures of the track timeline.
 */
class RenderPlanPreviewTest {
    private val edits: List<Pair<String, EditState>> by lazy {
        TrackFixtures.all.map { (name, state) -> "fixture $name" to state } +
            GoldenProjects.all.filter { !it.large }.flatMap { golden -> golden.project.history.mapIndexed { index, state -> "golden ${golden.name}, step $index" to state } }
    }

    /** Moments from before the movie to after it, and a hair before, on and after every edge of every clip. */
    private fun moments(clips: List<TimelineClip>): List<Double> {
        val total = TimelineEditing.duration(clips)
        val edges = clips.indices.flatMap { val start = TimelineEditing.clipStart(clips, it); listOf(start, start + clips[it].duration) }
        return List(65) { -0.25 + (total + 0.5) * it / 64 } + edges.flatMap { listOf(it - 1e-4, it, it + 1e-4) }
    }

    // MARK: The player of the editor as it decided by itself

    private class Player(val clips: List<TimelineClip>, val sounds: List<AudioItem>) {
        val chains = TimelineEditing.chains(clips)
        val duration = TimelineEditing.duration(clips)
        val items = List(2) { deck -> chains.filterIndexed { chain, _ -> chain % 2 == deck }.flatMap { it.first..it.last } }
        val deckOf = TransitionScene.decks(clips)

        /** The freezes whose clip plays on under them: deck, from, until, the moment the frame is on screen, its recording and the second of it. */
        val sounding: List<List<Any>> = TimelineEditing.layout(clips).let { layout ->
            layout.all.filter { it.transition != null && it.outgoing != null && TransitionLayout.longEnough(it.timing) }.mapNotNull { join ->
                val placement = layout.placements[join.outgoing!!]
                TransitionPicture.heldFrame(join.timing, join.origin, placement.start, placement.duration, TransitionScene.FRAMES_PER_SECOND)
                    ?.takeIf { placement.end > it.from + 0.001 }?.let { held ->
                        val clip = clips[placement.index]
                        listOf(deckOf[placement.index], held.from, held.until, held.shown, clip.sourceID, clip.sourceTime(held.offset))
                    }
            }
        }

        /** What a deck's playlist is made of. */
        val media = clips.map { listOf(it.id, it.sourceID, floor(it.start * 1e6).toLong(), floor(it.end * 1e6).toLong(), it.rate) }

        fun playing(deck: Int, time: Double): Pair<TimelineEditing.Chain, Int>? {
            val chain = chains.withIndex().lastOrNull { it.index % 2 == deck && time >= it.value.start && time < it.value.end }?.value ?: return null
            return chain to ((chain.first..chain.last).lastOrNull { TimelineEditing.clipStart(clips, it) <= time } ?: chain.first)
        }

        fun held(deck: Int, time: Double): TransitionScene.Source.Held? = TransitionScene.moment(clips, time)
            .let { listOf(it.first, it.second) }.filterIsInstance<TransitionScene.Source.Held>().firstOrNull { it.deck == deck }

        fun next(deck: Int, time: Double): TimelineEditing.Chain? = chains.withIndex().firstOrNull { it.index % 2 == deck && it.value.start > time }?.value

        fun leading(time: Double): Pair<TimelineEditing.Chain, Int>? = chains.withIndex().lastOrNull { time >= it.value.start && time < it.value.end }?.let { it.value to it.index % 2 }

        fun cue(track: Int, time: Double): AudioItem? =
            TimelineEditing.audioAt(sounds, track, time) ?: sounds.filter { it.track == track && it.start > time }.minByOrNull { it.start }

        // The screen around the player.
        fun deckPlaying(deck: Int, playhead: Double): Int? =
            clips.indices.lastOrNull { deckOf[it] == deck && playhead >= TimelineEditing.clipStart(clips, it) && playhead < TimelineEditing.clipStart(clips, it) + clips[it].duration }

        fun deckClip(deck: Int, playhead: Double): Int? = deckPlaying(deck, playhead)
            ?: TransitionScene.moment(clips, playhead, decks = deckOf).let { listOf(it.first, it.second) }.firstNotNullOfOrNull { (it as? TransitionScene.Source.Held)?.takeIf { held -> held.deck == deck }?.index }

        fun deckSound(deck: Int, playhead: Double): Double =
            deckPlaying(deck, playhead)?.let { TimelineEditing.soundLevel(clips, it, playhead - TimelineEditing.clipStart(clips, it)) } ?: 0.0

        fun trackSound(track: Int, playhead: Double): Double = TimelineEditing.audioAt(sounds, track, playhead)?.let { TimelineEditing.soundLevel(it, playhead - it.start) } ?: 0.0
    }

    // MARK: The tests

    @Test fun thePlayerIsLoadedWithWhatItMadeOfTheClips() {
        for ((name, state) in edits) {
            val was = Player(state.clips, state.audio.orEmpty())
            val plan = TrackRenderPlan.of(state)
            assertEquals(name, was.duration, plan.duration, 0.0)
            assertEquals(name, was.chains, plan.chains)
            assertEquals(name, was.items, plan.decks.map { it.clips })
            assertEquals(name, was.deckOf, plan.deckOf)
            assertEquals(name, was.sounding, plan.freezes.filter { it.sounding }.map { listOf(it.deck, it.from, it.until, it.shown, plan.pictures[it.clip].sourceID, it.sourceTime) })
            assertEquals(name, was.media, plan.pictures.map { listOf(it.id, it.sourceID, it.sourceStartUs, it.sourceEndUs, it.rate) })
            assertEquals(name, state.clips.indices.map { TimelineEditing.clipStart(state.clips, it) to state.clips[it].duration }, plan.pictures.map { it.start to it.duration })
            assertEquals(name, state.clips.map { it.color to it.crop }, plan.pictures.map { it.color to it.crop })
        }
    }

    @Test fun atEveryMomentTheDecksAndTheTracksDoWhatTheyDidOfTheClips() {
        var asked = 0
        for ((name, state) in edits) {
            val was = Player(state.clips, state.audio.orEmpty())
            val plan = TrackRenderPlan.of(state)
            for (time in moments(state.clips)) {
                for (deck in 0..1) {
                    assertEquals("$name at $time", was.playing(deck, time), plan.playing(deck, time)?.let { (chain, clip) -> plan.chains[chain] to clip })
                    assertEquals("$name at $time", was.held(deck, time), plan.held(deck, time))
                    assertEquals("$name at $time", was.next(deck, time), plan.coming(deck, time)?.let { plan.chains[it] })
                    assertEquals("$name at $time", was.deckPlaying(deck, time), plan.onDeck(deck, time)?.index)
                    assertEquals("$name at $time", was.deckClip(deck, time), plan.onDeck(deck, time)?.index ?: plan.held(deck, time)?.index)
                    assertEquals("$name at $time", was.deckSound(deck, time), plan.deckLevel(deck, time), 0.0)
                }
                assertEquals("$name at $time", was.leading(time), plan.leading(time)?.let { plan.chains[it] to plan.pictures[plan.chains[it].first].deck })
                for (track in 0 until AudioItem.TRACKS) {
                    assertEquals("$name at $time", was.cue(track, time), plan.cued(track, time))
                    assertEquals("$name at $time", was.trackSound(track, time), plan.trackLevel(track, time), 0.0)
                }
                assertEquals("$name at $time", TransitionScene.moment(state.clips, time, Size(1080.0, 1920.0), was.deckOf) { 60.0 }, plan.moment(time, Size(1080.0, 1920.0)) { 60.0 })
                assertEquals("$name at $time", TitleMotionRanges(state.clips).shown(state.clips, time) { 60.0 }, plan.titlesShown(time) { 60.0 })
                asked++
            }
            val captions = SubtitleEditing.cues(state)
            assertEquals(name, captions, plan.captions)
            assertEquals(name, state.subtitles?.takeIf { captions.isNotEmpty() }, plan.subtitles)
        }
        println("The preview: $asked moments of ${edits.size} edits, each answered as the editor answered it of the clips")
        assertTrue(asked > 10_000)
    }
}
