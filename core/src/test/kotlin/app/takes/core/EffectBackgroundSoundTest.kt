package app.takes.core

import app.takes.core.TrackFixtures.uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound of a Chroma Key's or an Auto Background's movie in the mix, as
 * YouRec/VideoEffects/VideoEffectBackgroundAudio.swift:27-145 lays it out.
 * The checks from the original's own test name its lines.
 */
class EffectBackgroundSoundTest {
    private val rate = 48_000
    private val camera = uuid(100)
    private val movie = "back.mp4"
    private val background = EffectBackgroundSound.sourceID(movie)

    /** A Chroma Key with a Video background of [movie], its Background sound [on]. */
    private fun key(start: Double = 0.0, end: Double = VideoEffectSettings.MAXIMUM_TIME, on: Boolean = true, kind: VideoEffectID = VideoEffectID.ChromaKey) =
        VideoEffectSettings(kind, start, end).with("background", EffectValue.Choice("Video")).with("media", EffectValue.Media(movie))
            .with("backgroundAudio", EffectValue.Toggle(on))

    private fun clip(number: Int, start: Double, end: Double, vararg effects: VideoEffectSettings) =
        TimelineClip(uuid(number), camera, start, end, videoEffects = effects.toList())

    private fun plan(clips: List<TimelineClip>, length: Double = 4.0): TrackMixPlan {
        val timeline = EditState(clips).toTimeline()
        return EffectBackgroundSound.plan(TrackMix.plan(timeline), timeline, mapOf(movie to EffectBackgroundSound.Movie(length, 0.0, length)))
    }

    private fun ours(plan: TrackMixPlan) = plan.pieces.filter { it.sourceID == background }

    /** A sine of 1 kHz at [amplitude], a whole number of periods in every 0.2 s: a loop's two turns meet in phase. */
    private fun tone(seconds: Double, amplitude: Double = 0.5) = FloatArray((seconds * rate).toInt()) { (amplitude * sin(2 * PI * 1000 * it / rate)).toFloat() }

    private fun mix(plan: TrackMixPlan, movie: FloatArray) =
        TrackMixer.mix(plan, rate, 1) { if (it.sourceID == background) movie else FloatArray(10 * rate) }

    private fun rms(values: FloatArray, at: Double, duration: Double = 0.05): Double {
        val a = (at * rate).toInt()
        val b = minOf(values.size, ((at + duration) * rate).toInt())
        return sqrt((a until b).sumOf { values[it].toDouble() * values[it] } / (b - a))
    }

    // Tests/VideoEffectIntegrationTests.swift:569-591: "Background audio is mixed at -18 dB below its original sine" and
    // "Loop audio remains continuous during moving video crossfade".
    @Test fun theMovieSoundsEighteenDecibelsDownAndCarriesOnThroughItsLoop() {
        val plan = plan(listOf(clip(1, 0.0, 4.0, key(0.0, 4.0))))
        val mixed = mix(plan, tone(4.0))
        val baseline = rms(mixed, 1.0)
        assertEquals(0.5 / sqrt(2.0) * EffectBackgroundSound.VOLUME, baseline, 1e-4)
        assertTrue(baseline > 0.006 && baseline < 0.016 * 4)
        // In the overlap the turn that ends and the one that starts are crossfaded in straight lines: the same level.
        for (at in listOf(3.81, 3.85, 3.87, 3.9, 3.94)) assertEquals(baseline, rms(mixed, at, 0.005), baseline * 0.01)
    }

    // VideoEffectBackgroundAudio.swift:58-79: from the effect's start, each turn whole, the next one overlap before its end.
    @Test fun theLoopPlaysEachTurnWholeAndOverlapsTheNext() {
        val pieces = ours(plan(listOf(clip(1, 0.0, 8.0, key(1.0, 7.0))), length = 2.0)).sortedBy { it.start }
        // The turns start at 1, 2.8, 4.6 and 6.4 and the last is cut at the effect's end.
        assertEquals(listOf(1.0, 2.8, 4.6, 6.4), pieces.map { it.start.seconds })
        assertEquals(listOf(3.0, 4.8, 6.6, 7.0), pieces.map { it.end.seconds })
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), pieces.map { it.runs.single().sourceStart })
        assertEquals(0.6, pieces.last().runs.single().sourceEnd, 1e-9)
        // Each turn after the first comes up over the overlap, each goes down over it; two lanes take turns.
        assertEquals(listOf(0.0, 0.2, 0.2, 0.2), pieces.map { it.envelope.transitionIn })
        assertTrue(pieces.all { it.envelope.transitionOut == 0.2 })
        assertEquals(listOf(6.6, 8.4), pieces.drop(2).map { it.envelope.clipEnd }.map { Math.round(it * 1e9) / 1e9 })
        assertEquals(listOf(1, 2, 1, 2), pieces.map { it.lane })
        assertTrue(pieces.all { it.envelope.volume == EffectBackgroundSound.VOLUME && it.envelope.fadeIn == AudioLevels.CUT_FADE })
        // Past the overlap only one turn is heard, at the level of the first.
        val level = pieces[1].envelope.gain(3.5)
        assertEquals(EffectBackgroundSound.VOLUME, level, 1e-12)
        assertEquals(0.0, pieces[0].envelope.gain(3.5), 1e-12)
        assertEquals(EffectBackgroundSound.VOLUME / 2, pieces[0].envelope.gain(2.9), 1e-9)
        assertEquals(EffectBackgroundSound.VOLUME / 2, pieces[1].envelope.gain(2.9), 1e-9)
    }

    // VideoEffectBackgroundAudio.swift:48-49: only where Background sound is on, under a Video background, with a movie that has sound.
    @Test fun nothingSoundsWithoutBackgroundSoundAVideoOrASound() {
        val timeline = EditState(listOf(clip(1, 0.0, 4.0, key(on = false)))).toTimeline()
        val base = TrackMix.plan(timeline)
        assertSame(base, EffectBackgroundSound.plan(base, timeline, mapOf(movie to EffectBackgroundSound.Movie(4.0, 0.0, 4.0))))
        val image = EditState(listOf(clip(1, 0.0, 4.0, key().with("background", EffectValue.Choice("Image"))))).toTimeline()
        assertTrue(ours(EffectBackgroundSound.plan(TrackMix.plan(image), image, mapOf(movie to EffectBackgroundSound.Movie(4.0, 0.0, 4.0)))).isEmpty())
        val silent = EditState(listOf(clip(1, 0.0, 4.0, key()))).toTimeline()
        assertTrue(ours(EffectBackgroundSound.plan(TrackMix.plan(silent), silent, emptyMap())).isEmpty())
        val off = EditState(listOf(clip(1, 0.0, 4.0, key().copy(enabled = false)))).toTimeline()
        assertTrue(ours(EffectBackgroundSound.plan(TrackMix.plan(off), off, mapOf(movie to EffectBackgroundSound.Movie(4.0, 0.0, 4.0)))).isEmpty())
        // Auto Background as Chroma Key.
        assertEquals(1, ours(plan(listOf(clip(1, 0.0, 3.0, key(kind = VideoEffectID.AutoBackground))))).size)
        // What the mix needs read: the movies named where Background sound is on somewhere.
        assertEquals(setOf(movie), EffectBackgroundSound.files(silent))
        assertEquals(emptySet<String>(), EffectBackgroundSound.files(timeline))
        assertNull(EffectBackgroundSound.file(camera))
        assertEquals(movie, EffectBackgroundSound.file(background))
    }

    // VideoEffectBackgroundAudio.swift:43-49: a key of Background sound cuts the stretch, each part read at its start.
    @Test fun aKeyOfBackgroundSoundCutsIt() {
        val keyed = key().withKeyframe("backgroundAudio", 0.0, EffectValue.Toggle(true)).withKeyframe("backgroundAudio", 2.0, EffectValue.Toggle(false))
        val pieces = ours(plan(listOf(clip(1, 0.0, 4.0, keyed))))
        assertEquals(1, pieces.size)
        assertEquals(EditInterval.of(0.0, 2.0), pieces.single().interval)
        assertEquals(AudioLevels.CUT_FADE, pieces.single().envelope.fadeOut, 0.0)
    }

    // VideoEffectBackgroundAudio.swift:64-65: at the picture's speed, its pitch kept.
    @Test fun theMovieGoesAtThePicturesSpeed() {
        val fast = clip(1, 0.0, 4.0, key(0.0, 4.0)).copy(speed = SpeedSettings(rate = 2.0))
        val pieces = ours(plan(listOf(fast), length = 6.0))
        val piece = pieces.single()
        assertEquals(EditInterval.of(0.0, 2.0), piece.interval)
        assertEquals(2.0, piece.runs.single().rate, 1e-9)
        assertEquals(4.0, piece.runs.single().sourceEnd, 1e-9)
        assertEquals(3.0, piece.envelope.clipEnd, 1e-9)
    }

    // VideoEffectBackgroundAudio.swift:84-94, 98-107: the pieces of a split clip carry the movie on, no fade between, one lane.
    @Test fun aSplitCarriesTheMovieOn() {
        val effect = key(0.0, 6.0)
        val pieces = ours(plan(listOf(clip(1, 0.0, 3.0, effect), clip(2, 3.0, 6.0, effect)), length = 10.0))
        assertEquals(listOf(EditInterval.of(0.0, 3.0), EditInterval.of(3.0, 6.0)), pieces.map { it.interval })
        assertEquals(listOf(3.0, 6.0), pieces.map { it.runs.single().sourceEnd })
        assertEquals(pieces[0].lane, pieces[1].lane)
        assertEquals(listOf(false, true), pieces.map { it.continues })
        assertEquals(0.0, pieces[0].envelope.fadeOut, 0.0)
        assertEquals(0.0, pieces[1].envelope.fadeIn, 0.0)
        val mixed = mix(plan(listOf(clip(1, 0.0, 3.0, effect), clip(2, 3.0, 6.0, effect)), length = 10.0), tone(10.0))
        assertEquals(rms(mixed, 2.0), rms(mixed, 2.99, 0.02), rms(mixed, 2.0) * 0.01)
    }

    // VideoEffectBackgroundAudio.swift:66-71; Tests/TrackTimelineTests.swift:932: the picture's transitions shape it.
    @Test fun aTransitionRampsIt() {
        val second = clip(2, 3.0, 6.0, key())
        val first = clip(1, 0.0, 3.0, key()).copy(transition = TransitionSettings(nextClipID = second.id, duration = 0.5))
        val timeline = EditState(listOf(first, second)).toTimeline()
        val pieces = ours(EffectBackgroundSound.plan(TrackMix.plan(timeline), timeline, mapOf(movie to EffectBackgroundSound.Movie(10.0, 0.0, 10.0))))
        val relation = timeline.transitions.single()
        assertEquals(TransitionSound.rampOut(relation.timing, relation.origin.seconds), pieces.first().envelope.outRamp)
        assertEquals(TransitionSound.rampIn(relation.timing, relation.origin.seconds), pieces.last().envelope.inRamp)
    }

    // VideoEffectBackgroundAudio.swift:34-41, 72-77: on a held frame the movie plays on; Cut silences it, Fade out over hold fades it.
    @Test fun aHeldFramePlaysTheMovieOnOrNot() {
        fun held(mode: String): TimelineClip {
            val freeze = VideoEffectSettings(VideoEffectID.FreezeFrame, 1.0, 1.04).with("audio", EffectValue.Choice(mode)).with("hold", EffectValue.Number(2.0))
            return clip(1, 1.0, 1.04, key(), freeze).copy(frozenFrame = FrozenFrameSegment(1.0, 2.0))
        }
        val goes = ours(plan(listOf(held("Continue")))).single()
        assertEquals(EditInterval.of(0.0, 2.0), goes.interval)
        assertEquals(1.0, goes.runs.single().sourceStart, 1e-9)
        assertEquals(3.0, goes.runs.single().sourceEnd, 1e-9)
        assertNull(goes.envelope.endVolume)
        assertTrue(ours(plan(listOf(held("Cut")))).isEmpty())
        val fades = ours(plan(listOf(held("Fade out over hold")))).single()
        assertEquals(EffectBackgroundSound.VOLUME, fades.envelope.volume, 1e-12)
        assertEquals(0.0, fades.envelope.endVolume!!, 1e-12)
    }
}
