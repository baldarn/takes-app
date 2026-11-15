package app.takes.core

import app.takes.core.CameraReport.Camera
import app.takes.core.CameraReport.Combination
import app.takes.core.CameraReport.DynamicRange
import app.takes.core.CameraReport.Facing
import app.takes.core.CameraReport.Fact
import app.takes.core.CameraReport.FrameRates
import app.takes.core.CameraReport.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera report as text, and what it concludes for the later steps of the recorder work. */
class CameraReportTest {
    private val rear = Camera(
        id = "0", label = "Rear", facing = Facing.Back, hardwareLevel = "FULL", physicalIds = listOf("2", "3"),
        focalLengths = listOf(6.9), apertures = listOf(1.7), sensorWidth = 9.6, sensorHeight = 7.2,
        intrinsicZoom = 1.0, zoomMinimum = 0.5, zoomMaximum = 7.0,
        dynamicRanges = listOf(
            DynamicRange(CameraReport.SDR, listOf(Quality("UHD", 3840, 2160), Quality("FHD", 1920, 1080), Quality("HD", 1280, 720))),
            DynamicRange(CameraReport.HLG10, listOf(Quality("UHD"), Quality("FHD"))),
        ),
        frameRates = listOf(FrameRates(15, 30), FrameRates(30, 30), FrameRates(60, 60)),
        videoStabilization = true, previewStabilization = true,
        stabilizationModes = listOf("OFF", "ON", "PREVIEW_STABILIZATION"), opticalStabilization = true,
        combinations = listOf(
            Combination("FHD", emptyList(), true),
            Combination("UHD", listOf(CameraReport.HLG10, "preview stabilisation"), false),
            Combination("UHD", listOf("60 fps"), null, "IllegalArgumentException: no"),
        ),
        minimumFocusDistance = 10.0, hyperfocalDistance = 0.25, focusCalibration = "CALIBRATED",
        autofocusModes = listOf("OFF", "AUTO", "CONTINUOUS_VIDEO"), focusRegions = 1, focusMetering = true,
        faceDetectModes = listOf("OFF", "SIMPLE"), maximumFaces = 8,
        timestampSource = "REALTIME", croppingType = "FREEFORM",
        capabilities = listOf("BACKWARD_COMPATIBLE", "LOGICAL_MULTI_CAMERA"),
        facts = listOf(Fact("Sensor orientation", "90")),
    )

    private val front = Camera(id = "1", label = "Front", facing = Facing.Front, problems = listOf("characteristics: CameraAccessException"))

    private val report = CameraReport(
        app = "Takes Dev 0.6-dev (6), app.takes.dev", device = "Google Pixel 9a", system = "Android 16, API 36", library = "1.6.2",
        cameras = listOf(rear, front), concurrent = listOf(listOf("0", "1")),
        facts = listOf(Fact("Gravity sensor", "yes")), problems = listOf("microphones: SecurityException"),
    )

    @Test fun theReportIsOneFactPerLineWithUnknownForWhatCouldNotBeRead() {
        val expected = """
            Takes camera report
            App: Takes Dev 0.6-dev (6), app.takes.dev
            Device: Google Pixel 9a
            System: Android 16, API 36
            Camera library: 1.6.2
            Cameras: 0 (Rear), 1 (Front)
            Cameras that run together: 0 + 1
            Gravity sensor: yes
            Problem: microphones: SecurityException

            Camera 0: Rear
              Facing: back
              Hardware level: FULL
              Lenses behind it: 2, 3
              Focal lengths: 6.9 mm (as 25 mm on full frame)
              Apertures: f/1.7
              Sensor: 9.6 × 7.2 mm
              Lens against the main one: 1×
              Zoom: 0.5× to 7×
              Recording, SDR: UHD 3840×2160, FHD 1920×1080, HD 1280×720
              Recording, HLG 10 bit: UHD, FHD
              Frame rates: 15 to 30, 30, 60
              Stabilisation of preview and recording: yes
              Stabilisation of the recording only: yes
              Stabilisation modes of the camera: OFF, ON, PREVIEW_STABILIZATION
              Optical stabilisation: yes
              Preview and recording together with:
                FHD: yes
                UHD + HLG 10 bit + preview stabilisation: no
                UHD + 60 fps: unknown (IllegalArgumentException: no)
              Nearest focus: 10 dioptres (10 cm)
              Hyperfocal distance: 0.25 dioptres
              Focus scale: CALIBRATED
              Autofocus modes: OFF, AUTO, CONTINUOUS_VIDEO
              Autofocus regions: 1
              Focus point accepted: yes
              Face detection modes: OFF, SIMPLE
              Faces at most: 8
              Frame clock: REALTIME
              Sensor crop: FREEFORM
              Capabilities: BACKWARD_COMPATIBLE, LOGICAL_MULTI_CAMERA
              Sensor orientation: 90
              For the recorder:
                4K (P1): yes
                HDR as HLG 10 bit (P3): yes
                Fixed 30 frames per second (P2): yes
                Stabilisation of preview and recording (P9): yes
                Stabilisation of the recording only (P9): yes
                Tap to focus (F1): yes
                Focus lock and manual focus (F2, F3): yes
                Face detection by the camera (A2): yes
                Pan by sensor crop (Z13, A2): yes
                Frame times on the sensor clock (A1): yes
                Ultrawide listed as a camera of its own (Z7): no
                Runs beside another camera (D1): yes

            Camera 1: Front
              Facing: front
              Hardware level: unknown
              Lenses behind it: one lens
              Focal lengths: unknown
              Apertures: unknown
              Sensor: unknown
              Lens against the main one: unknown
              Zoom: unknown
              Recording: unknown
              Frame rates: unknown
              Stabilisation of preview and recording: unknown
              Stabilisation of the recording only: unknown
              Stabilisation modes of the camera: unknown
              Optical stabilisation: unknown
              Nearest focus: unknown
              Hyperfocal distance: unknown
              Focus scale: unknown
              Autofocus modes: unknown
              Autofocus regions: unknown
              Focus point accepted: unknown
              Face detection modes: unknown
              Faces at most: unknown
              Frame clock: unknown
              Sensor crop: unknown
              Capabilities: unknown
              For the recorder:
                4K (P1): unknown
                HDR as HLG 10 bit (P3): unknown
                Fixed 30 frames per second (P2): unknown
                Stabilisation of preview and recording (P9): unknown
                Stabilisation of the recording only (P9): unknown
                Tap to focus (F1): unknown
                Focus lock and manual focus (F2, F3): unknown
                Face detection by the camera (A2): unknown
                Pan by sensor crop (Z13, A2): unknown
                Frame times on the sensor clock (A1): unknown
                Ultrawide listed as a camera of its own (Z7): unknown
                Runs beside another camera (D1): yes
              Problem: characteristics: CameraAccessException
        """.trimIndent() + "\n"
        assertEquals(expected, report.text())
    }

    @Test fun aPhoneWithoutCamerasStillGivesAReport() {
        val text = CameraReport(app = "Takes", device = "Emulator", system = "Android 8, API 26", library = "unknown", cameras = emptyList()).text()
        assertEquals(
            "Takes camera report\nApp: Takes\nDevice: Emulator\nSystem: Android 8, API 26\nCamera library: unknown\n" +
                "Cameras: none\nCameras that run together: none\n",
            text,
        )
        assertTrue(text.startsWith(CameraReport.TITLE))
    }

    // MARK: What the later steps can offer

    private fun gate(camera: Camera, name: String, concurrent: List<List<String>> = emptyList()): Boolean? =
        camera.gates(concurrent).single { it.name.startsWith(name) }.open

    @Test fun qualityAndColourComeFromWhatTheRecorderCanWrite() {
        val plain = rear.copy(dynamicRanges = listOf(DynamicRange(CameraReport.SDR, listOf(Quality("FHD", 1920, 1080), Quality("HD")))))
        assertEquals(false, gate(plain, "4K"))
        assertEquals(false, gate(plain, "HDR"))
        // 4K in HDR alone is not 4K for the recorder's default colour, and a mode with no quality is no mode.
        val odd = rear.copy(dynamicRanges = listOf(
            DynamicRange(CameraReport.SDR, listOf(Quality("FHD"))), DynamicRange(CameraReport.HLG10, emptyList()),
        ))
        assertEquals(false, gate(odd, "4K"))
        assertEquals(false, gate(odd, "HDR"))
        assertEquals(listOf(Quality("UHD"), Quality("FHD")), rear.qualities(CameraReport.HLG10))
        assertEquals(emptyList<Quality>(), plain.qualities(CameraReport.HLG10))
    }

    @Test fun aFixedFrameRateNeedsTheRangeThirtyToThirty() {
        assertEquals(true, gate(rear, "Fixed 30"))
        assertEquals(false, gate(rear.copy(frameRates = listOf(FrameRates(15, 30), FrameRates(24, 30))), "Fixed 30"))
        assertNull(gate(rear.copy(frameRates = emptyList()), "Fixed 30"))
    }

    @Test fun focusLockNeedsALensThatMovesAndAutofocusThatCanBeTurnedOff() {
        assertEquals(true, gate(rear, "Focus lock"))
        assertEquals(false, gate(rear.copy(minimumFocusDistance = 0.0), "Focus lock"))
        assertEquals(false, gate(rear.copy(autofocusModes = listOf("AUTO", "CONTINUOUS_VIDEO")), "Focus lock"))
        assertNull(gate(rear.copy(minimumFocusDistance = null), "Focus lock"))
        assertNull(gate(rear.copy(autofocusModes = emptyList()), "Focus lock"))
    }

    @Test fun faceDetectionCropAndClock() {
        assertEquals(false, gate(rear.copy(faceDetectModes = listOf("OFF")), "Face detection"))
        assertEquals(true, gate(rear.copy(faceDetectModes = listOf("OFF", "FULL")), "Face detection"))
        assertEquals(false, gate(rear.copy(croppingType = "CENTER_ONLY"), "Pan by sensor crop"))
        assertEquals(false, gate(rear.copy(timestampSource = "UNKNOWN"), "Frame times"))
    }

    @Test fun anUltrawideIsARearLensWellUnderTheMainOne() {
        assertEquals(true, gate(rear.copy(intrinsicZoom = 0.6), "Ultrawide"))
        assertEquals(false, gate(rear.copy(intrinsicZoom = 0.9), "Ultrawide"))
        assertEquals(false, gate(rear.copy(intrinsicZoom = 2.0), "Ultrawide"))
        assertEquals(false, gate(front.copy(intrinsicZoom = 0.6), "Ultrawide"))
    }

    @Test fun aCameraRunsBesideAnotherOnlyInASetOfTwoOrMore() {
        assertEquals(false, gate(rear, "Runs beside"))
        assertEquals(false, gate(rear, "Runs beside", listOf(listOf("0"), listOf("1", "2"))))
        assertEquals(true, gate(rear, "Runs beside", listOf(listOf("1", "2"), listOf("0", "2"))))
    }

    // MARK: Numbers

    @Test fun focalLengthsAreAlsoGivenAsOnFullFrame() {
        // A sensor with a 12 mm diagonal is 3.6 times smaller than full frame's 43.27 mm.
        assertEquals(24.878, rear.equivalentFocalLengths()!!.single(), 0.001)
        assertNull(rear.copy(sensorHeight = null).equivalentFocalLengths())
        assertNull(rear.copy(sensorWidth = 0.0, sensorHeight = 0.0).equivalentFocalLengths())
        assertTrue(rear.copy(sensorWidth = null).let { CameraReport(app = "", device = "", system = "", library = "", cameras = listOf(it)) }
            .text().contains("  Focal lengths: 6.9 mm\n"))
    }

    @Test fun numbersHaveAtMostTwoDecimalsAndNoTrailingZeros() {
        assertEquals("1", CameraReport.number(1.0))
        assertEquals("10", CameraReport.number(10.0))
        assertEquals("100", CameraReport.number(100.0))
        assertEquals("0.5", CameraReport.number(0.5))
        assertEquals("4.38", CameraReport.number(4.3812))
        assertEquals("0", CameraReport.number(0.0))
        assertEquals("0", CameraReport.number(-0.001))
        assertEquals("unknown", CameraReport.number(Double.NaN))
        assertEquals("yes", CameraReport.answer(true))
        assertEquals("no", CameraReport.answer(false))
        assertEquals("unknown", CameraReport.answer(null))
    }

    @Test fun aLensWithFixedFocusIsNamedSo() {
        val fixed = CameraReport(app = "", device = "", system = "", library = "", cameras = listOf(front.copy(minimumFocusDistance = 0.0))).text()
        assertTrue(fixed.contains("  Nearest focus: fixed focus\n"))
    }
}
