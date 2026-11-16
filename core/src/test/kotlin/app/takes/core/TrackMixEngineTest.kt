package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The audio engine against the reference mixer, sample by sample: every
 * timeline of the fixtures and the golden projects, and timelines with what a
 * sequence cannot hold (four sounds at once, censors, a retimed sound), mixed
 * whole by [TrackMixer] and a block at a time by [TrackMixEngine], from the
 * start and after seeks.
 */
class TrackMixEngineTest {
    private val rate = 4_000
    private val channels = 2

    /** A sound for each source that no two sources share and that changes every frame, so a frame off shows. */
    private fun source(id: String, seconds: Double = 200.0): FloatArray {
        val seed = id.hashCode()
        return FloatArray((seconds * rate).toInt() * channels) { index ->
            val frame = index / channels
            (0.3 * sin(frame * (0.013 + (seed and 0xff) / 9000.0) + index % channels) + 0.2 * sin(frame * 0.71 + seed)).toFloat()
        }
    }

    private val sources = HashMap<String, FloatArray>()
    private fun samples(id: String) = sources.getOrPut(id) { source(id) }

    private fun reference(plan: TrackMixPlan) = TrackMixer.mix(plan, rate, channels) { samples(it.sourceID) }

    private fun engine(plan: TrackMixPlan) = TrackMixEngine(plan, rate, channels, { TrackMixArraySource(samples(it.sourceID), channels) })

    private fun assertSame(name: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$name: frames", expected.size, actual.size)
        var worst = 0f
        var at = -1
        for (index in expected.indices) {
            val difference = abs(expected[index] - actual[index])
            if (difference > worst) { worst = difference; at = index / channels }
        }
        assertTrue("$name: off by $worst at frame $at", worst <= 1e-5f)
    }

    private fun check(name: String, timeline: TrackTimeline) {
        val plan = TrackMix.plan(timeline)
        sources.clear()
        val longest = plan.pieces.groupBy { it.sourceID }.mapValues { (_, pieces) -> pieces.maxOf { piece -> piece.runs.maxOf { maxOf(it.sourceStart, it.sourceEnd) } } }
        for ((id, seconds) in longest) sources[id] = source(id, seconds + 1)
        val expected = reference(plan)
        for (block in listOf(4096, 777)) engine(plan).use { assertSame("$name, blocks of $block", expected, it.renderAll(block)) }
        // From the middle on after a seek, as the preview plays it.
        engine(plan).use { mixer ->
            val from = mixer.frames / 3
            mixer.seek(from)
            val rest = FloatArray(((mixer.frames - from) * channels).toInt())
            val buffer = FloatArray(1000 * channels)
            var at = 0
            while (true) {
                val done = mixer.render(buffer, 1000)
                if (done == 0) break
                System.arraycopy(buffer, 0, rest, at * channels, done * channels)
                at += done
            }
            assertSame("$name, after a seek", expected.copyOfRange((from * channels).toInt(), expected.size), rest)
        }
    }

    @Test fun everyFixtureAndGoldenProjectMixesAsTheReference() {
        var mixed = 0
        for ((name, state) in TrackFixtures.all) { check("fixture $name", state.toTimeline()); mixed++ }
        // The last step of every golden project: the others are the fixtures' edits on the way there.
        for (golden in GoldenProjects.all) { check("golden ${golden.name}", TrackWrapping.timeline(golden.project.history.last())); mixed++ }
        println("The audio engine: $mixed timelines mixed as the reference mixes them")
        assertTrue(mixed > 10)
    }

    private fun sound(number: Int, source: String, start: Double, seconds: Double, sourceStart: Double = 0.0) = AudioTimelineItem(
        id = uuid(number), occurrenceID = uuid(number), sourceID = source, start = EditTime.of(start),
        mapping = SourcePlaybackMap.single(EditTime.of(seconds), sourceStart, sourceStart + seconds),
    )

    @Test fun overlapsCensorsFadesAndARetimedSoundMixAsTheReference() {
        val sounds = listOf(
            sound(1, uuid(100), 0.0, 3.0).settingFade(AudioFadeEdge.FadeIn, 1.0),
            sound(2, uuid(101), 0.5, 2.0, 4.0).copy(volumeDB = -6.0),
            sound(3, uuid(102), 1.0, 2.5),
            sound(4, uuid(100), 1.25, 2.0, 10.0).settingFade(AudioFadeEdge.FadeOut, 0.7),
            // Twice as fast: the reference reads it by linear interpolation, and so does the core's stretch.
            sound(5, uuid(103), 3.0, 1.5).copy(mapping = SourcePlaybackMap.single(EditTime.of(1.5), 2.0, 5.0)),
        )
        var timeline = TrackTimeline(audio = sounds)
        check("overlaps", timeline)
        timeline = TrackAudio.censoring(timeline, AudioCensorSettings(), TimelineSelection(audio = setOf(uuid(1), uuid(3)), range = EditInterval.of(1.1, 1.9)))
        assertTrue(TrackMix.plan(timeline).pieces.any { it.censors.isNotEmpty() })
        check("censored", timeline)
        val split = TrackEditing.split(timeline, EditTime.of(2.2), TimelineSelection(audio = setOf(uuid(3)))).timeline
        check("split", split)
        assertTrue(TrackMix.plan(split).pieces.any { it.continues })
    }

    @Test fun anEmptyTimelineIsSilenceAsLongAsNothing() {
        engine(TrackMix.plan(TrackTimeline())).use { assertEquals(0, it.renderAll().size) }
    }
}
