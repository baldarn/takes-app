package app.takes.core

import app.takes.core.ExportSettings.Codec
import app.takes.core.ExportSettings.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportFormatTest {
    @get:Rule val temporary = TemporaryFolder()

    // Project.swift:669: HDR is always written as H.265.
    @Test fun hdrForcesTheCodec() {
        assertEquals(Codec.H264, ExportFormat.codec(ExportSettings(), hdr = false))
        assertEquals(Codec.HEVC, ExportFormat.codec(ExportSettings(), hdr = true))
        assertEquals(Codec.HEVC, ExportFormat.codec(ExportSettings(codec = Codec.HEVC), hdr = false))
    }

    // MediaRenderer.swift:280 and 516: the upright size of the footage, rounded to even pixels.
    @Test fun theCanvasIsTheFootageRoundedToEvenPixels() {
        assertEquals(1920 to 1080, ExportFormat.canvas(1920.0, 1080.0))
        assertEquals(1080 to 1920, ExportFormat.canvas(-1080.0, 1920.0)) // a turned recording has negative bounds
        assertEquals(1280 to 720, ExportFormat.canvas(1279.0, 719.0))    // 639.5 and 359.5 round up
        assertEquals(1282 to 722, ExportFormat.canvas(1281.0, 721.0))    // 640.5 and 360.5 round away from zero
        assertEquals(640 to 360, ExportFormat.canvas(640.9, 360.2))
        // MediaRenderer.swift:744: a project without footage is 1920 × 1080.
        assertEquals(1920 to 1080, ExportFormat.EMPTY_CANVAS)
    }

    // Project.swift:650-660, through the existing ExportSettings.Resolution.size; CoreTests.swift:979 uses a 180 × 320 canvas.
    @Test fun theChosenFormatFitsTheCanvasInEitherOrientation() {
        val portrait = 180 to 320
        assertEquals(180 to 320, ExportFormat.size(ExportSettings(resolution = Resolution.Project), portrait))
        assertEquals(720 to 1280, ExportFormat.size(ExportSettings(resolution = Resolution.HD), portrait))
        assertEquals(1080 to 1920, ExportFormat.size(ExportSettings(resolution = Resolution.FullHD), portrait))
        assertEquals(2160 to 3840, ExportFormat.size(ExportSettings(resolution = Resolution.UltraHD), portrait))
        // A square fits the short side; a wide picture the long one.
        assertEquals(1080 to 1080, ExportFormat.size(ExportSettings(resolution = Resolution.FullHD), 1000 to 1000))
        assertEquals(1920 to 804, ExportFormat.size(ExportSettings(resolution = Resolution.FullHD), 2390 to 1000))
        assertEquals(1280 to 720, ExportFormat.size(ExportSettings(resolution = Resolution.HD), 3840 to 2160))
    }

    // MovieExportPanel.swift:92: "Upscaled from …" when the movie is wider than the canvas by more than a pixel.
    @Test fun aLargerFormatThanTheFootageIsUpscaled() {
        val canvas = 1280 to 720
        assertTrue(ExportFormat.isUpscaled(ExportFormat.size(ExportSettings(resolution = Resolution.FullHD), canvas), canvas))
        assertFalse(ExportFormat.isUpscaled(ExportFormat.size(ExportSettings(resolution = Resolution.HD), canvas), canvas))
        assertFalse(ExportFormat.isUpscaled(ExportFormat.size(ExportSettings(), canvas), canvas))
        assertFalse(ExportFormat.isUpscaled(1281 to 720, canvas))
        assertTrue(ExportFormat.isUpscaled(1282 to 722, canvas))
    }

    // MovieExportPanel.swift:65-70.
    @Test fun theSummaryNamesSizeRateAndCodec() {
        assertEquals("1920 × 1080 · 30 fps · H.264", ExportFormat.summary(ExportSettings(), 1920 to 1080, hdr = false))
        assertEquals("720 × 1280 · 60 fps · H.265", ExportFormat.summary(ExportSettings(Codec.HEVC, Resolution.HD, 60), 180 to 320, hdr = false))
        assertEquals("1920 × 1080 · 25 fps · H.265 · HDR", ExportFormat.summary(ExportSettings(frameRate = 25), 1920 to 1080, hdr = true))
        assertEquals("4K · 24 fps · H.264", ExportFormat.summary(ExportSettings(resolution = Resolution.UltraHD, frameRate = 24), null, hdr = false))
        assertEquals("Project size · 30 fps · H.264", ExportFormat.summary(ExportSettings(), null, hdr = false))
    }

    // Project.swift:646-648: the names shown are the names saved.
    @Test fun namesAreTheOnesTheSettingsAreSavedUnder() {
        val saved = documentJson.encodeToString(ExportSettings.serializer(), ExportSettings(Codec.HEVC, Resolution.FullHD, 50))
        assertEquals("""{"codec":"H.265","resolution":"1080p","frameRate":50}""", saved)
        assertEquals(listOf("Project size", "720p", "1080p", "4K"), Resolution.entries.map(ExportFormat::name))
        assertEquals(listOf("H.264", "H.265"), Codec.entries.map(ExportFormat::name))
    }

    // ---- Free space (ProjectRecovery.swift:421-423, 452-455) ----

    // ProjectRecoveryTests.swift:146-147.
    @Test fun aFiftiethOfTheDeviceStaysFreeWithinBounds() {
        assertEquals(512_000_000L, ExportSpace.reserve(16_000_000_000))
        assertEquals(2_000_000_000L, ExportSpace.reserve(256_000_000_000))
        assertEquals(1_280_000_000L, ExportSpace.reserve(64_000_000_000))
        assertEquals(512_000_000L, ExportSpace.reserve(25_600_000_000))
        assertEquals(2_000_000_000L, ExportSpace.reserve(100_000_000_000))
    }

    // ProjectRecovery.swift:452-455 and ProjectRecoveryTests.swift:149-153: the reserve may not be used.
    @Test fun anExportMayNotUseTheReserve() {
        val total = 64_000_000_000L
        val free = 2_000_000_000L // 720 MB above the reserve
        assertEquals(720_000_000L, ExportSpace.room(free, total))
        assertEquals(0L, ExportSpace.missing(720_000_000, free, total))
        assertEquals(1L, ExportSpace.missing(720_000_001, free, total))
        assertEquals("Using all that is free dips into the reserve", 1_280_000_000L, ExportSpace.missing(free, free, total))
        assertEquals(0L, ExportSpace.missing(0, free, total))
        assertEquals("A nonsensical size is refused", 1L, ExportSpace.missing(-5, free, total))
        // Already inside the reserve: even nothing does not fit.
        assertEquals(-280_000_000L, ExportSpace.room(1_000_000_000, total))
        assertEquals(280_000_000L, ExportSpace.missing(0, 1_000_000_000, total))
    }

    @Test fun theDeviceItselfIsAsked() {
        val directory = File(temporary.root, "project/Exports/.paused-export")
        val error = assertThrows(ExportSpaceException::class.java) { ExportSpace.require(Long.MAX_VALUE / 2, directory) }
        assertTrue(error.missing > 0)
    }

    // ExportCheckpoint.swift:102: 32 000 bytes for every second of sound, and 2 MB.
    @Test fun soundAsksForThirtyTwoKilobytesASecond() {
        assertEquals(2_000_000L, ExportSpace.audio(0.0))
        assertEquals(3_920_000L, ExportSpace.audio(60.0))
        assertEquals(2_995_840L, ExportSpace.audio(31.12))
        assertEquals(117_200_000L, ExportSpace.audio(3600.0))
    }

    // ExportCheckpoint.swift:127-128: a byte for every pixel of every second of a section, and 2 MB.
    @Test fun aSectionAsksForAByteForEveryPixelSecond() {
        assertEquals(26_883_200L, ExportSpace.section(1920, 1080, 12.0))
        assertEquals(101_532_800L, ExportSpace.section(3840, 2160, 12.0))
        assertEquals(13_059_200L, ExportSpace.section(1280, 720, 12.0))
        assertEquals(3_640_448L, ExportSpace.section(640, 360, 7.12))
    }

    // ExportCheckpoint.swift:141-143: the parts once more, a fiftieth on top, and 2 MB.
    @Test fun joiningAsksForThePartsAgain() {
        assertEquals(2_000_000L, ExportSpace.join(0))
        assertEquals(104_000_000L, ExportSpace.join(100_000_000))
        assertEquals(2_000_100L, ExportSpace.join(99))
    }
}
