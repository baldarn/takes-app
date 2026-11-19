package app.takes.core

import org.junit.Test
import org.junit.Assert.assertEquals

class StreamTransformTest {
    private fun matrix(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float) =
        floatArrayOf(a, b, 0f, 0f, c, d, 0f, 0f, 0f, 0f, 1f, 0f, tx, ty, 0f, 1f)

    private fun assertAffine(expected: Affine, actual: Affine) {
        listOf(expected.a to actual.a, expected.b to actual.b, expected.c to actual.c, expected.d to actual.d,
            expected.tx to actual.tx, expected.ty to actual.ty).forEach { (e, a) -> assertEquals(e, a, 1e-6) }
    }

    private fun apply(t: Affine, x: Double, y: Double) = (t.a * x + t.c * y + t.tx) to (t.b * x + t.d * y + t.ty)

    @Test fun aPixelsMatricesHaveNoCrop() {
        // As a Pixel 9a gives them with two cameras and Stabilize on: the rear camera's and the front camera's.
        val rear = matrix(0f, -1f, -1f, 0f, 1f, 1f)
        val front = matrix(0f, -1f, 1f, 0f, 0f, 1f)
        for (m in listOf(rear, front)) {
            assertEquals(m.toList(), StreamTransform.orientation(m).toList())
            assertAffine(Affine.IDENTITY, StreamTransform.crop(m))
        }
    }

    @Test fun aMovingCropIsTakenOutOfTheOrientation() {
        // A quarter turn with a vertical flip, and a crop of 90 % moved by a steadying camera.
        val orientation = Affine(0.0, -1.0, -1.0, 0.0, 1.0, 1.0)
        for (shift in listOf(0.0, 0.03, -0.04)) {
            val crop = Affine(0.9, 0.0, 0.0, 0.9, 0.05 + shift, 0.05 - shift)
            val whole = orientation.concatenating(crop)
            val m = matrix(whole.a.toFloat(), whole.b.toFloat(), whole.c.toFloat(), whole.d.toFloat(), whole.tx.toFloat(), whole.ty.toFloat())
            assertAffine(orientation, StreamTransform.affine(StreamTransform.orientation(m)))
            assertAffine(crop, StreamTransform.crop(m))
            // Through the crop, then the orientation: as through the whole transform.
            for ((x, y) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.3 to 0.8)) {
                val viaParts = apply(StreamTransform.crop(m), apply(orientation, x, y).first, apply(orientation, x, y).second)
                val viaWhole = apply(whole, x, y)
                assertEquals(viaWhole.first, viaParts.first, 1e-5)
                assertEquals(viaWhole.second, viaParts.second, 1e-5)
            }
        }
    }

    @Test fun anIdentityWithTheTexelShrinkKeepsItsSmallCrop() {
        val m = matrix(0.999f, 0f, 0f, -0.999f, 0.0005f, 0.9995f)
        assertAffine(Affine(1.0, 0.0, 0.0, -1.0, 0.0, 1.0), StreamTransform.affine(StreamTransform.orientation(m)))
        val crop = StreamTransform.crop(m)
        assertEquals(0.999, crop.a, 1e-6)
        assertEquals(0.999, crop.d, 1e-6)
    }

    @Test fun aTransformThatIsNotAQuarterTurnIsLeftWhole() {
        val m = matrix(0.7f, 0.7f, -0.7f, 0.7f, 0.2f, 0f)
        assertEquals(m.toList(), StreamTransform.orientation(m).toList())
        assertAffine(Affine.IDENTITY, StreamTransform.crop(m))
    }
}
