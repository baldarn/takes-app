package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes into MediaPipe's full-range face detector and what comes out of it, against MediaPipe's own graph
 * (mediapipe/modules/face_detection/face_detection.pbtxt): the letterbox, the anchors, the boxes, the score
 * threshold and the weighted suppression. The model itself runs on a phone (A/FaceFinder.kt).
 */
class FaceDetectionTest {
    private val side = FaceDetection.SIDE
    private val grid = side / 4

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.width, actual.width, tolerance)
        assertEquals(expected.height, actual.height, tolerance)
    }

    /** Model output with each face at the anchor of its column and row, its centre moved by dx, dy model pixels. */
    private fun output(vararg faces: Face): Pair<FloatArray, FloatArray> {
        val boxes = FloatArray(FaceDetection.ANCHORS * FaceDetection.VALUES)
        val scores = FloatArray(FaceDetection.ANCHORS) { -10f }
        for (face in faces) {
            val i = face.row * grid + face.column
            boxes[i * FaceDetection.VALUES] = face.dx
            boxes[i * FaceDetection.VALUES + 1] = face.dy
            boxes[i * FaceDetection.VALUES + 2] = face.w
            boxes[i * FaceDetection.VALUES + 3] = face.h
            scores[i] = face.logit
        }
        return boxes to scores
    }

    private class Face(val column: Int, val row: Int, val dx: Float, val dy: Float, val w: Float, val h: Float, val logit: Float)

    private val square = FaceDetection.Letterbox(0.0, 0.0, 1.0, 1.0)

    @Test fun thePictureLiesAcrossTheSquareByItsLongSideBetweenBars() {
        assertRect(Rect(0.0, 0.21875, 1.0, 0.5625), FaceDetection.letterbox(1920, 1080, 0).let { Rect(it.x, it.y, it.width, it.height) })
        // A landscape camera picture stood upright is tall.
        assertRect(Rect(0.21875, 0.0, 0.5625, 1.0), FaceDetection.letterbox(1920, 1080, 90).let { Rect(it.x, it.y, it.width, it.height) })
        assertRect(Rect(0.0, 0.0, 1.0, 1.0), FaceDetection.letterbox(480, 480, 270).let { Rect(it.x, it.y, it.width, it.height) })
    }

    @Test fun aBoxIsReadAboutItsAnchorInModelPixels() {
        // The anchor of cell (12, 30) is at (12.5 / 48, 30.5 / 48); the box is 48 × 60 model pixels, moved 6 right, 3 up.
        val (boxes, scores) = output(Face(12, 30, 6f, -3f, 48f, 60f, 2f))
        val faces = FaceDetection.decode(boxes, scores, square)
        assertEquals(1, faces.size)
        val cx = 12.5 / 48 + 6.0 / side
        val cy = 30.5 / 48 - 3.0 / side
        assertRect(Rect(cx - 0.125, cy - 60.0 / side / 2, 0.25, 60.0 / side), faces[0].box)
        assertEquals(1 / (1 + Math.exp(-2.0)), faces[0].score, 1e-9)
    }

    @Test fun facesBelowVisionsConfidenceAreLeftOut() {
        // A logit of 0 is a score of 0.5, kept as Vision's 0.5 is; just under it is not.
        val (boxes, scores) = output(Face(5, 5, 0f, 0f, 20f, 20f, 0f), Face(40, 40, 0f, 0f, 20f, 20f, -0.01f))
        val faces = FaceDetection.decode(boxes, scores, square)
        assertEquals(1, faces.size)
        assertEquals(5.5 / 48, faces[0].box.midX, 1e-9)
    }

    @Test fun overlappingBoxesAreOneFaceWeightedByScore() {
        // Two neighbouring anchors see the same face; a third, far off, is another face.
        val (boxes, scores) = output(
            Face(20, 20, 0f, 0f, 40f, 40f, 3f),
            Face(21, 20, 0f, 0f, 40f, 40f, 1f),
            Face(2, 40, 0f, 0f, 16f, 16f, 1.5f),
        )
        val faces = FaceDetection.decode(boxes, scores, square)
        assertEquals(2, faces.size)
        val high = 1 / (1 + Math.exp(-3.0))
        val low = 1 / (1 + Math.exp(-1.0))
        // Best first, with its own score, its centre between the two by their scores.
        assertEquals(high, faces[0].score, 1e-9)
        val mid = (20.5 / 48 * high + 21.5 / 48 * low) / (high + low)
        assertEquals(mid, faces[0].box.midX, 1e-9)
        assertEquals(40.0 / side, faces[0].box.width, 1e-9)
        assertEquals(2.5 / 48, faces[1].box.midX, 1e-9)
    }

    @Test fun boxesThatTouchLittleStayApart() {
        val a = FaceDetection.Face(Rect(0.0, 0.0, 0.2, 0.2), 0.9)
        // Overlapping by a quarter of their union: under the 0.3 MediaPipe merges at.
        val b = FaceDetection.Face(Rect(0.12, 0.0, 0.2, 0.2), 0.8)
        assertTrue(FaceDetection.overlap(a.box, b.box) < FaceDetection.OVERLAP)
        assertEquals(2, FaceDetection.merge(listOf(b, a)).size)
        assertEquals(0.9, FaceDetection.merge(listOf(b, a))[0].score, 0.0)
    }

    @Test fun boxesComeBackInThePictureWithoutTheBars() {
        val letterbox = FaceDetection.letterbox(1920, 1080, 0)
        // The face in the middle of the square is in the middle of the picture, its height taken over 0.5625 of the square.
        val (boxes, scores) = output(Face(24, 24, -2f, -2f, 19.2f, 19.2f, 4f))
        val face = FaceDetection.decode(boxes, scores, letterbox).single()
        assertEquals(0.5, face.box.midX, 1e-9)
        assertEquals(0.5, face.box.midY, 1e-6)
        assertEquals(0.1, face.box.width, 1e-6)
        assertEquals(0.1 / 0.5625, face.box.height, 1e-6)
    }

    @Test fun thePictureIsStoodUpOnTheSquareAndTheBoxTurnedBack() {
        // A landscape camera picture, black but for a white block near its top-left corner, turned a quarter clockwise to stand.
        val width = 400
        val height = 300
        val block = Rect(0.1, 0.2, 0.1, 0.1)
        val pixels = FaceDetection.Pixels { x, y ->
            val fx = (x + 0.5) / width
            val fy = (y + 0.5) / height
            if (fx >= block.minX && fx < block.maxX && fy >= block.minY && fy < block.maxY) 0xFFFFFF else 0x000000
        }
        val input = FloatArray(side * side * 3)
        val letterbox = FaceDetection.fill(input, width, height, 90, pixels)
        // Stood up, the picture is 300 wide and 400 tall: bars on the left and right.
        assertEquals(0.125, letterbox.x, 1e-9)
        assertEquals(-1f, input[(100 * side + 10) * 3])
        // Turning a quarter clockwise takes the camera's top-left to the upright top-right: the block is at the
        // upright (0.7…0.8, 0.1…0.2), seen where it lands on the square.
        val upright = Rect(0.7, 0.1, 0.1, 0.1)
        val mx = ((letterbox.x + upright.midX * letterbox.width) * side).toInt()
        val my = ((letterbox.y + upright.midY * letterbox.height) * side).toInt()
        assertEquals(1f, input[(my * side + mx) * 3], 1e-6f)
        assertEquals(-1f, input[((side - my) * side + mx) * 3], 1e-6f)
        // And that box, turned back, is where the block is on the camera's picture.
        assertRect(block, FaceDetection.toCamera(upright, 90), 1e-9)
        assertRect(upright, FaceDetection.toCamera(FaceDetection.toCamera(FaceDetection.toCamera(block, 90), 90), 90), 1e-9)
    }

    @Test fun eachModelPixelAveragesThePicturesOverItsFootprint() {
        // A 576-pixel-wide picture of alternate black and white columns: three of its pixels to one of the model's, grey.
        val input = FloatArray(side * side * 3)
        FaceDetection.fill(input, 576, 576, 0) { x, _ -> if (x % 2 == 0) 0xFFFFFF else 0 }
        val value = input[(96 * side + 96) * 3]
        assertTrue("$value", value > -0.5f && value < 0.5f)
    }
}
