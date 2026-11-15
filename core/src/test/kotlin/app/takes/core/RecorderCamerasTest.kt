package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the flip button and the 1× preset lead, and the zoom presets of a
 * lens. The original covers these with interface tests only; these are the
 * port's own, from YouRec/CaptureController.swift:1255-1271 and 1508-1511 and
 * YouRec/RecorderView.swift:1049-1053, with the presets the owner chose: the
 * lens minimum, 1, 2, 3, 5, 6.
 */
class RecorderCamerasTest {
    private val rear = RecorderCamera("0", front = false)
    private val front = RecorderCamera("1", front = true)
    private val ultrawide = RecorderCamera("2", front = false, lensZoom = 0.6)
    private val tele = RecorderCamera("3", front = false, lensZoom = 5.0)
    private val frontWide = RecorderCamera("4", front = true, lensZoom = 0.7)
    private val all = listOf(rear, front, ultrawide, tele, frontWide)

    // MARK: Flip

    @Test fun flipGoesToTheCameraUsedLastOnTheOtherSide() {
        assertEquals(frontWide, RecorderCameras.opposite(all, "0", lastFront = "4", lastRear = "0"))
        assertEquals(tele, RecorderCameras.opposite(all, "1", lastFront = "1", lastRear = "3"))
    }

    @Test fun flipGoesToTheFirstCameraOfTheOtherSideWhenNoneIsRemembered() {
        assertEquals(front, RecorderCameras.opposite(all, "0", null, null))
        assertEquals(rear, RecorderCameras.opposite(all, "4", null, null))
        // A remembered camera that is gone, or one of the wrong side, is not used.
        assertEquals(front, RecorderCameras.opposite(all, "0", lastFront = "9", lastRear = null))
        assertEquals(front, RecorderCameras.opposite(all, "0", lastFront = "3", lastRear = null))
    }

    @Test fun flipHasNowhereToGoWithoutACameraOnTheOtherSide() {
        assertNull(RecorderCameras.opposite(listOf(rear, tele), "0", null, null))
        assertNull(RecorderCameras.opposite(all, "9", null, null))
        assertNull(RecorderCameras.opposite(all, null, null, null))
    }

    // MARK: 1× Main

    @Test fun oneTimesLeadsToTheMainCameraFromARearUltrawideOnly() {
        assertEquals(rear, RecorderCameras.mainAtOne(all, "2"))
        assertNull(RecorderCameras.mainAtOne(all, "0"))
        assertNull(RecorderCameras.mainAtOne(all, "3"))
        // The front ultrawide magnifies itself, as in the original.
        assertNull(RecorderCameras.mainAtOne(all, "4"))
        assertNull(RecorderCameras.mainAtOne(listOf(ultrawide, front, tele), "2"))
    }

    @Test fun aLensIsNamedByHowItComparesWithTheMainOne() {
        assertEquals(listOf(true, false, false), listOf(rear.main, rear.ultrawide, rear.tele))
        assertEquals(listOf(false, true, false), listOf(ultrawide.main, ultrawide.ultrawide, ultrawide.tele))
        assertEquals(listOf(false, false, true), listOf(tele.main, tele.ultrawide, tele.tele))
    }

    // MARK: Presets

    @Test fun thePresetsAreTheLensMinimumThenOneTwoThreeFiveSix() {
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 5.0, 6.0), RecorderCameras.presets(0.5, 10.0))
        assertEquals(listOf(0.6149, 1.0, 2.0, 3.0, 5.0, 6.0), RecorderCameras.presets(0.6149, 8.0))
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 6.0), RecorderCameras.presets(1.0, 10.0))
    }

    @Test fun aPresetTheLensCannotReachIsNotOffered() {
        assertEquals(listOf(1.0), RecorderCameras.presets(1.0, 1.0))
        assertEquals(listOf(1.0, 2.0, 3.0), RecorderCameras.presets(1.0, 4.0))
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0), RecorderCameras.presets(0.99999, 5.00001))
        // A lens that starts above 1× has no 1× preset.
        assertEquals(listOf(2.0, 3.0), RecorderCameras.presets(2.0, 3.0))
        // A minimum that reads as 1× is not shown twice.
        assertEquals(listOf(1.0, 2.0), RecorderCameras.presets(0.97, 2.0))
    }

    @Test fun thePresetsNeverPassTenTimes() {
        val maximum = PinchZoom.maximum(12.93)
        assertEquals(10.0, maximum, 0.0)
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 6.0), RecorderCameras.presets(1.0, maximum))
        assertEquals(4.0, PinchZoom.maximum(4.0), 0.0)
    }

    @Test fun aPresetIsWrittenWithoutNeedlessDecimals() {
        assertEquals("1×", RecorderCameras.presetLabel(1.0))
        assertEquals("0.5×", RecorderCameras.presetLabel(0.5))
        assertEquals("0.6×", RecorderCameras.presetLabel(0.6149))
        assertEquals("6×", RecorderCameras.presetLabel(6.0))
        assertEquals("1×", RecorderCameras.presetLabel(0.97))
    }
}
