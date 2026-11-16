package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The editor's voice recorder, from YouRec/VoiceRecorder.swift:3-4, 41-45,
 * 105-122, 205-235 and 388-391, and the place of the recording on the
 * timeline from YouRec/ProjectStore.swift:472 and YouRec/TrackTimeline.swift:217-222.
 */
class VoiceRecordingTest {
    @get:Rule val folder = TemporaryFolder()

    // MARK: Storage (VoiceRecorder.swift:110-115)

    @Test fun aFirstTakeIsCountedTwiceAndALaterOneThreeTimes() {
        val bps = 96_000.0
        // Nothing recorded yet: the header room is kept, the rest is split between two copies.
        assertEquals((10_000_000 - 65_536) / (2 * bps), VoiceRecording.maxSeconds(10_000_000, 0, 0.0, firstTake = true), 1e-9)
        // Ten seconds taken: twice their bytes are kept as well, and the rest is split between three copies.
        val reserve = 960_000L * 2 + 65_536
        assertEquals((10_000_000 - 1_000_000 - reserve) / (3 * bps), VoiceRecording.maxSeconds(10_000_000, 1_000_000, 10.0, firstTake = false), 1e-9)
    }

    @Test fun lessThanASecondOfRoomRefusesTheTake() {
        val justEnough = 65_536L + 2 * 96_000
        assertTrue(VoiceRecording.canRecord(VoiceRecording.maxSeconds(justEnough, 0, 0.0, true)))
        assertFalse(VoiceRecording.canRecord(VoiceRecording.maxSeconds(justEnough - 1, 0, 0.0, true)))
        assertFalse(VoiceRecording.canRecord(VoiceRecording.maxSeconds(1_000, 5_000, 0.0, true)))
    }

    // MARK: Meter (VoiceRecorder.swift:41-45)

    @Test fun theLevelRunsFromMinus55DecibelsToFullScale() {
        assertEquals(0.0, VoiceRecording.level(-160.0), 0.0)
        assertEquals(0.0, VoiceRecording.level(-55.0), 0.0)
        assertEquals(0.5, VoiceRecording.level(-27.5), 1e-12)
        assertEquals(1.0, VoiceRecording.level(0.0), 0.0)
        assertEquals(1.0, VoiceRecording.level(3.0), 0.0)
    }

    @Test fun theAveragePowerIsTheRootMeanSquareInDecibels() {
        assertEquals(VoiceRecording.SILENCE_DB, VoiceRecording.averagePower(ShortArray(480)), 0.0)
        // A square wave at half of full scale: −6.02 dB.
        val half = ShortArray(480) { if (it % 2 == 0) 16_384 else -16_384 }
        assertEquals(20 * kotlin.math.log10(0.5), VoiceRecording.averagePower(half), 1e-9)
        // Only the samples read count.
        val partly = ShortArray(10) { if (it < 5) 16_384 else 0 }
        assertEquals(VoiceRecording.averagePower(half), VoiceRecording.averagePower(partly, 5), 1e-9)
    }

    // MARK: Words (VoiceRecorder.swift:388-391)

    @Test fun theRecordButtonSaysRecordThenRecordMoreAndStopWhileRecording() {
        assertEquals(VoiceRecording.RecordTitle.Record, VoiceRecording.recordTitle(recording = false, hasAudio = false))
        assertEquals(VoiceRecording.RecordTitle.RecordMore, VoiceRecording.recordTitle(recording = false, hasAudio = true))
        assertEquals(VoiceRecording.RecordTitle.Stop, VoiceRecording.recordTitle(recording = true, hasAudio = true))
    }

    // MARK: Placement (ProjectStore.swift:472, TrackTimeline.swift:217-222)

    @Test fun theRecordingGoesAtThePlayheadInsideTheMovie() {
        assertEquals(4.5, VoiceRecording.position(4.5, 10.0), 0.0)
        assertEquals(0.0, VoiceRecording.position(-1.0, 10.0), 0.0)
        assertEquals(10.0, VoiceRecording.position(12.0, 10.0), 0.0)
        assertEquals(0.0, VoiceRecording.position(Double.NaN, 10.0), 0.0)
    }

    @Test fun theRecordingBecomesASoundItemOfItsWholeLengthAtThePlayheadInOneStep() {
        val project = Project("P", id = "P", createdAt = 1).addRecording(SourceMedia(id = "A", filename = "a.mp4", duration = 10.0, recordedAt = 1), now = 2)
        val voice = SourceMedia(id = "V", filename = "voice.wav", duration = 3.25, audioOnly = true, originalName = "Voice recording.wav")
        val added = project.addSource(voice, now = 3)
        val at = VoiceRecording.position(4.0, added.edit.duration)
        val edit = TimelineEditing.addAudio(added.edit, voice.id, voice.duration, at)!!
        val done = added.commit(edit, now = 4)
        val item = done.edit.audio!!.single()
        assertEquals("V", item.sourceID)
        assertEquals(4.0, item.start, 1e-9)
        assertEquals(3.25, item.end - item.start, 1e-9)
        // One Undo step: undoing it leaves the movie without the sound, the file still in the project.
        assertEquals(added.history.size + 1, done.history.size)
        assertTrue(done.undo(now = 5).edit.audio.isNullOrEmpty())
        assertTrue("voice.wav" in done.mediaFiles)
        assertTrue(done.sources.any { it.id == "V" && it.audioOnly == true })
    }

    // MARK: WAV files (VoiceRecorder.swift:3-4, 121-122, 205-235)

    @Test fun theHeaderIsAPcmWaveOf48KilohertzMono16Bit() {
        val header = WaveFile.header(96_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        fun int(at: Int, size: Int) = (0 until size).fold(0L) { sum, i -> sum or ((header[at + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(36L + 96_000, int(4, 4))
        assertEquals(1L, int(20, 2))         // PCM
        assertEquals(1L, int(22, 2))         // mono
        assertEquals(48_000L, int(24, 4))
        assertEquals(96_000L, int(28, 4))    // bytes a second
        assertEquals(2L, int(32, 2))
        assertEquals(16L, int(34, 2))
        assertEquals(96_000L, int(40, 4))
    }

    private fun take(name: String, samples: ShortArray): File {
        val file = File(folder.root, name)
        file.outputStream().use { out ->
            out.write(WaveFile.header(0))
            for (sample in samples) { out.write(sample.toInt() and 0xFF); out.write((sample.toInt() shr 8) and 0xFF) }
        }
        return file
    }

    @Test fun finishingATakeWritesItsLength() {
        val file = take("one.wav", ShortArray(4_800) { it.toShort() })
        assertEquals(0L, WaveFile.dataBytes(file))
        assertEquals(4_800L, WaveFile.finish(file))
        assertEquals(9_600L, WaveFile.dataBytes(file))
        assertEquals(0.1, VoiceRecording.seconds(4_800), 1e-12)
    }

    @Test fun takesAreJoinedInOrderWithoutTheirHeaders() {
        val first = take("1.wav", ShortArray(70_000) { (it % 1000).toShort() }).also { WaveFile.finish(it) }
        val second = take("2.wav", ShortArray(3) { (-1 - it).toShort() }).also { WaveFile.finish(it) }
        val joined = File(folder.root, "Joined/Voice recording.wav")
        WaveFile.join(listOf(first, second), joined)
        assertEquals(140_006L, WaveFile.dataBytes(joined))
        val bytes = joined.readBytes()
        assertArrayEquals(WaveFile.header(140_006), bytes.copyOfRange(0, 44))
        assertArrayEquals(first.readBytes().copyOfRange(44, 140_044), bytes.copyOfRange(44, 140_044))
        assertArrayEquals(second.readBytes().copyOfRange(44, 50), bytes.copyOfRange(140_044, 140_050))
    }

    @Test fun aJoinThatStopsLeavesNoFile() {
        val first = take("1.wav", ShortArray(100_000)).also { WaveFile.finish(it) }
        val joined = File(folder.root, "joined.wav")
        var chunks = 0
        try {
            WaveFile.join(listOf(first, first), joined) { if (++chunks == 3) throw InterruptedException() }
            fail()
        } catch (_: InterruptedException) {}
        assertFalse(joined.exists())
    }

    @Test fun aFileOfAnotherFormatIsNotATake() {
        val other = File(folder.root, "other.wav")
        other.writeBytes(WaveFile.header(10, sampleRate = 44_100) + ByteArray(10))
        assertNull(WaveFile.dataBytes(other))
        val short = File(folder.root, "short.wav").apply { writeBytes(ByteArray(10)) }
        assertNull(WaveFile.dataBytes(short))
        try {
            WaveFile.join(listOf(other), File(folder.root, "out.wav"))
            fail()
        } catch (_: IOException) {}
    }
}
