package app.takes.core

import app.takes.core.CaptureFormat.FrameSize
import app.takes.core.CaptureFormat.Pixels
import app.takes.core.CaptureFormat.Stabilization
import app.takes.core.CaptureFormat.Wish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture a take is recorded with, against what a camera offers. The
 * bit rates are the original's (YouRec/CaptureSegmentWriter.swift:225); the
 * rest is the port's own rule that nothing is asked of a camera that does
 * not have it.
 */
class CaptureFormatTest {
    private val sizes = mapOf(
        FrameSize.UHD to Pixels(3840, 2160), FrameSize.FHD to Pixels(1920, 1080), FrameSize.HD to Pixels(1280, 720), FrameSize.SD to Pixels(720, 480),
    )
    private val flagship = CaptureFormat.Camera(sizes, hdrSizes = setOf(FrameSize.UHD, FrameSize.FHD), previewStabilization = true, recordingStabilization = true, thirty = true)
    private val modest = CaptureFormat.Camera(sizes - FrameSize.UHD, recordingStabilization = true, thirty = true)
    private val emulator = CaptureFormat.Camera(mapOf(FrameSize.HD to Pixels(1280, 720)), thirty = true)

    @Test fun theBitRateIsSixBitsForEachPixel() {
        assertEquals(12_441_600, CaptureFormat.bitRate(1920, 1080))
        assertEquals(49_766_400, CaptureFormat.bitRate(3840, 2160))
        assertEquals(5_529_600, CaptureFormat.bitRate(1280, 720))
        assertNull(CaptureFormat.bitRate(0, 1080))
        assertNull(CaptureFormat.bitRate(100_000, 100_000))
    }

    @Test fun aTakeIs1080pUnless4KIsAskedForAndThere() {
        assertEquals(FrameSize.FHD, CaptureFormat.size(false, sizes.keys))
        assertEquals(FrameSize.UHD, CaptureFormat.size(true, sizes.keys))
        assertEquals(FrameSize.FHD, CaptureFormat.size(true, sizes.keys - FrameSize.UHD))
    }

    @Test fun aCameraWithout1080pRecordsTheNextSizeBelowThenAbove() {
        assertEquals(FrameSize.HD, CaptureFormat.size(false, setOf(FrameSize.HD, FrameSize.SD, FrameSize.UHD)))
        assertEquals(FrameSize.SD, CaptureFormat.size(false, setOf(FrameSize.SD, FrameSize.UHD)))
        assertEquals(FrameSize.UHD, CaptureFormat.size(false, setOf(FrameSize.UHD)))
        assertNull(CaptureFormat.size(true, emptySet()))
    }

    @Test fun nothingIsOnUntilItIsChosen() {
        val format = CaptureFormat.of(Wish(), flagship)
        assertEquals(FrameSize.FHD, format.size)
        assertFalse(format.ultraHD)
        assertFalse(format.hdr)
        assertEquals(Stabilization.Off, format.stabilization)
        assertTrue(format.fixedRate)
        assertEquals(12_441_600, format.bitRate)
    }

    @Test fun aCameraThatHasEverythingGivesWhatIsAsked() {
        val format = CaptureFormat.of(Wish(ultraHD = true, hdr = true, stabilization = true), flagship)
        assertEquals(FrameSize.UHD, format.size)
        assertTrue(format.ultraHD)
        assertTrue(format.hdr)
        // The preview is steadied with the recording where the camera can, so it shows the recorded crop.
        assertEquals(Stabilization.PreviewAndRecording, format.stabilization)
        assertEquals(49_766_400, format.bitRate)
    }

    @Test fun whatACameraLacksIsLeftOut() {
        val format = CaptureFormat.of(Wish(ultraHD = true, hdr = true, stabilization = true), modest)
        assertEquals(FrameSize.FHD, format.size)
        assertFalse(format.ultraHD)
        assertFalse(format.hdr)
        assertEquals(Stabilization.RecordingOnly, format.stabilization)
        assertEquals(12_441_600, format.bitRate)

        val plain = CaptureFormat.of(Wish(ultraHD = true, hdr = true, stabilization = true), emulator)
        assertEquals(CaptureFormat(FrameSize.HD, ultraHD = false, hdr = false, Stabilization.Off, fixedRate = true, bitRate = 5_529_600), plain)
    }

    @Test fun hdrIsRecordedOnlyInASizeTheCameraHasItFor() {
        val only1080 = flagship.copy(hdrSizes = setOf(FrameSize.FHD))
        assertTrue(CaptureFormat.of(Wish(hdr = true), only1080).hdr)
        assertFalse(CaptureFormat.of(Wish(ultraHD = true, hdr = true), only1080).hdr)
        assertEquals(FrameSize.UHD, CaptureFormat.of(Wish(ultraHD = true, hdr = true), only1080).size)
    }

    @Test fun aCameraWithoutARateOfThirtyChoosesItsOwn() {
        assertFalse(CaptureFormat.of(Wish(), flagship.copy(thirty = false)).fixedRate)
        assertNull(CaptureFormat.of(Wish(), CaptureFormat.Camera()).bitRate)
        assertNull(CaptureFormat.of(Wish(), CaptureFormat.Camera()).size)
    }

    @Test fun theCameraSaysWhatItOffers() {
        assertTrue(flagship.ultraHD && flagship.hdr && flagship.stabilization)
        assertFalse(modest.ultraHD || modest.hdr)
        assertTrue(modest.stabilization)
        assertFalse(emulator.ultraHD || emulator.hdr || emulator.stabilization)
    }

    @Test fun aRefusedFormatIsTriedAgainWithoutHdrThenWithoutStabilisation() {
        val full = CaptureFormat.of(Wish(hdr = true, stabilization = true), flagship)
        val attempts = CaptureFormat.attempts(full)
        assertEquals(4, attempts.size)
        assertEquals(full, attempts[0])
        assertEquals(listOf(false, true), listOf(attempts[1].hdr, attempts[1].stabilization != Stabilization.Off))
        assertEquals(listOf(true, false), listOf(attempts[2].hdr, attempts[2].stabilization != Stabilization.Off))
        assertEquals(listOf(false, false), listOf(attempts[3].hdr, attempts[3].stabilization != Stabilization.Off))
        // A plain format has nothing to give up.
        assertEquals(1, CaptureFormat.attempts(CaptureFormat.of(Wish(), flagship)).size)
        assertEquals(2, CaptureFormat.attempts(CaptureFormat.of(Wish(hdr = true), flagship)).size)
    }

    // 60 frames a second, the port's own (issue 161): a rear camera with 4K at 60, a front one with 1080p at 60 only.
    private val rearSixty = flagship.copy(sixty = setOf(FrameSize.UHD, FrameSize.FHD))
    private val frontSixty = flagship.copy(sixty = setOf(FrameSize.FHD))

    @Test fun sixtyIsRecordedWhereTheCameraHasItAtTheSizeAsked() {
        val uhd = CaptureFormat.of(Wish(ultraHD = true, sixty = true), rearSixty)
        assertEquals(FrameSize.UHD, uhd.size)
        assertEquals(60, uhd.framesPerSecond)
        assertTrue(uhd.fixedRate)
        assertEquals(99_532_800, uhd.bitRate)
        val fhd = CaptureFormat.of(Wish(sixty = true), rearSixty)
        assertEquals(FrameSize.FHD, fhd.size)
        assertEquals(60, fhd.framesPerSecond)
        assertEquals(24_883_200, fhd.bitRate)
    }

    @Test fun fourKAtSixtyKeepsSixtyAt1080pOnACameraWithoutIt() {
        val format = CaptureFormat.of(Wish(ultraHD = true, sixty = true), frontSixty)
        assertEquals(FrameSize.FHD, format.size)
        assertFalse(format.ultraHD)
        assertEquals(60, format.framesPerSecond)
    }

    @Test fun aCameraWithoutSixtyRecordsTheSizeAskedAtThirty() {
        val format = CaptureFormat.of(Wish(ultraHD = true, sixty = true), flagship)
        assertEquals(FrameSize.UHD, format.size)
        assertEquals(30, format.framesPerSecond)
        assertEquals(CaptureFormat.of(Wish(ultraHD = true), flagship), format)
        assertEquals(30, CaptureFormat.of(Wish(ultraHD = true), rearSixty).framesPerSecond)
    }

    @Test fun aRefusedSixtyIsTriedAgainAtThirtyAtTheSizeAsked() {
        val wish = Wish(ultraHD = true, hdr = true, stabilization = true, sixty = true)
        val format = CaptureFormat.of(wish, frontSixty)
        val tries = CaptureFormat.attempts(format, CaptureFormat.of(wish.copy(sixty = false), frontSixty))
        assertEquals(listOf(60, 60, 60, 60, 30, 30, 30, 30), tries.map { it.framesPerSecond })
        assertEquals(FrameSize.UHD, tries[4].size)
        assertEquals(49_766_400, tries[4].bitRate)
        // At 30 already, the tries are as before.
        val thirty = CaptureFormat.of(Wish(ultraHD = true), flagship)
        assertEquals(CaptureFormat.attempts(thirty), CaptureFormat.attempts(thirty, thirty))
    }
}
