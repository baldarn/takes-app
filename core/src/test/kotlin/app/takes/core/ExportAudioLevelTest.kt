package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an export measures its mix. Tests/TrackMediaTests.swift:483-523 against YouRec/AudioGain.swift:158-199. */
class ExportAudioLevelTest {
    private val source = SourceMedia(filename = "source.mp4", duration = 4.0, recordedAt = 1)
    private val sources = listOf(source)
    private val original: TrackTimeline = EditState(listOf(TimelineClip(sourceID = source.id, start = 0.0, end = 4.0))).toTimeline()
    private val sound get() = original.audio.single()

    private fun interval(start: Double, end: Double) = EditInterval.of(start, end)
    private fun ranges(timeline: TrackTimeline, backgrounds: List<EditInterval> = emptyList()) = ExportAudioLevel.checkRanges(timeline, sources, backgrounds)
    private fun withAudio(vararg items: AudioTimelineItem) = original.copy(audio = items.toList())

    /** The sound cut at [at] seconds into two items, as a split leaves it. */
    private fun split(at: Double): List<AudioTimelineItem> {
        val cut = EditTime.of(at)
        return listOf(sound.sliced(EditInterval(EditTime.ZERO, cut), "A"), sound.sliced(EditInterval(cut, sound.mapping.duration), "B"))
    }

    // TrackMediaTests.swift:503-504.
    @Test fun originalAudioAndCutsNeedNoScan() {
        assertTrue(ranges(original).isEmpty())
        assertTrue(ranges(withAudio(*split(2.0).toTypedArray())).isEmpty())
    }

    // TrackMediaTests.swift:505-511: fades, a lower volume and bypassed or neutral processing cannot boost the sound.
    @Test fun fadesLowerVolumeAndNeutralProcessingNeedNoScan() {
        val (a, b) = split(2.0)
        val bypassed = AudioEffect(kind = AudioEffectKind.Gain, amount = 12.0, enabled = false)
        val neutral = AudioEffect(kind = AudioEffectKind.Gain, amount = 0.0)
        val whole = AudioEnhancementRegion(interval = EditInterval(EditTime.ZERO, EditTime.of(2.0)), effects = listOf(bypassed, neutral))
        val timeline = withAudio(a.copy(fadeIn = AudioEdgeFade(0.3), enhancements = listOf(whole)), b.copy(volumeDB = -1.0, enhancements = listOf(whole)))
        assertTrue(ranges(timeline).isEmpty())
    }

    // TrackMediaTests.swift:512-514: an audio filter is checked where it acts.
    @Test fun aFilterIsCheckedWhereItActs() {
        val filtered = sound.copy(enhancements = listOf(AudioEnhancementRegion(interval = interval(0.8, 1.2), effects = listOf(AudioEffect.make(AudioEffectKind.HighPass)))))
        assertEquals(listOf(interval(0.8, 1.2)), ranges(withAudio(filtered)))
    }

    // TrackMediaTests.swift:515-521: tracks at unity are checked only where they overlap; muted ones and background sound.
    @Test fun overlapsAreChecked() {
        val second = sound.copy(id = "second", start = EditTime.of(3.0))
        assertEquals(listOf(interval(3.0, 4.0)), ranges(withAudio(sound, second)))
        assertTrue(ranges(withAudio(sound, second.copy(muted = true))).isEmpty())
        assertEquals(listOf(interval(1.0, 2.0)), ranges(original, listOf(interval(1.0, 2.0))))
    }

    // TrackMediaTests.swift:522-523: time-pitch processing is checked on its playback clock.
    @Test fun aRetimedRunIsChecked() {
        val run = sound.mapping.runs.single().copy(duration = EditTime.of(2.0))
        assertEquals(listOf(interval(0.0, 2.0)), ranges(withAudio(sound.copy(mapping = SourcePlaybackMap(listOf(run))))))
    }

    // AudioGain.swift:166-170: a volume above 0 dB is checked, a still photo's sound never.
    @Test fun aRaisedVolumeIsCheckedAndStillsAreNot() {
        assertEquals(listOf(interval(0.0, 4.0)), ranges(withAudio(sound.copy(volumeDB = 0.5))))
        assertTrue(ExportAudioLevel.checkRanges(withAudio(sound.copy(volumeDB = 6.0)), listOf(source.copy(isStillPhoto = true))).isEmpty())
    }

    // AudioGain.swift:190-199.
    @Test fun whichStagesCanRaiseAPeak() {
        assertTrue(ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.Gain, amount = 1.0)))
        assertTrue(!ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.Gain, amount = -1.0)))
        assertTrue(!ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.Equalizer, amount = 0.0)))
        assertTrue(ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.Equalizer, amount = -3.0)))
        assertTrue(ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.VoiceIsolation, amount = 1.0)))
        assertTrue(ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.HighPass)))
        assertTrue(ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.Unavailable("future"))))
        assertTrue(!ExportAudioLevel.canRaisePeak(AudioEffect(kind = AudioEffectKind.HighPass, enabled = false)))
    }

    // AudioGain.swift:213-219, 236-265 and TrackMediaTests.swift:557-563: only frames inside the ranges count, even
    // a short one, and a loud passage elsewhere does not.
    @Test fun onlyTheRangesAreMeasured() {
        val rate = 48_000
        val frames = 4 * rate
        val block = FloatArray(frames * 2) { index -> val frame = index / 2; if (frame in 120_000 until 144_000) 1.5f else 0.4f }
        fun measured(vararg ranges: EditInterval): Float {
            val spans = ExportAudioLevel.frameSpans(ranges.toList(), 4.0, rate)
            var peak = 0f
            // In blocks of 4096 frames, as the mix is written.
            var first = 0
            while (first < frames) {
                val count = minOf(4096, frames - first)
                peak = ExportAudioLevel.peak(block.copyOfRange(first * 2, (first + count) * 2), first.toLong(), count, 2, spans, peak)
                first += count
            }
            return peak
        }
        assertEquals(0.4f, measured(interval(3.1, 3.4), interval(0.5, 1.1)), 0f)
        assertEquals(1.5f, measured(interval(2.701, 2.71)), 0f)
        assertEquals(0f, measured(), 0f)
        assertEquals(listOf(48_000L until 96_000L), ExportAudioLevel.frameSpans(listOf(interval(1.0, 2.0), interval(1.5, 1.8)), 4.0, rate))
        assertEquals(listOf(144_000L until 192_000L), ExportAudioLevel.frameSpans(listOf(interval(3.0, 9.0)), 4.0, rate))
    }
}
