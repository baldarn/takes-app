package app.takes.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLUTTest {
    private val identity = "TITLE \"Identity\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"

    @Test fun readsLatticeAndChannels() {
        val cube = CubeLUT.parse(identity.toByteArray())
        assertEquals(2, cube.dimension)
        assertEquals(8 * 3, cube.rgb.size)
        assertNull(cube.curves)
        assertEquals("Identity", cube.title)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), cube.domainMin, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), cube.domainMax, 0.0)
    }

    @Test fun identityPreservesChannelOrder() {
        val copied = CubeLUT.parse(identity).sample(0.18f, 0.4f, 0.7f)
        assertArrayEquals(floatArrayOf(0.18f, 0.4f, 0.7f), copied, 0.002f)
    }

    @Test fun nonDefaultDomainsAreRespected() {
        val domain = CubeLUT.parse("DOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 1 1 1\n$identity")
        assertEquals(0.5f, domain.sample(0f, 0f, 0f)[0], 0.002f)
    }

    @Test fun standaloneCurveKeepsChannelsIndependent() {
        val curve = CubeLUT.parse("LUT_1D_SIZE 3\n0 0 0\n0.25 0.5 0.75\n1 1 1\n")
        assertEquals(3, curve.dimension)
        assertEquals(9, curve.curves!!.size)
        assertTrue(curve.rgb.isEmpty())
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), curve.sample(0.5f, 0.5f, 0.5f), 0.002f)
    }

    @Test fun malformedFilesAreRejected() {
        val invalid = listOf(
            "LUT_3D_SIZE 999999\n",
            identity + "0 0 0\n",
            identity.replace("1 1 1", "nan 1 1"),
            "LUT_1D_SIZE 2\n$identity",
            "DOMAIN_MIN 1 1 1\nDOMAIN_MAX 1 1 1\n$identity",
            "LUT_3D_SIZE 2\n0 0 0\n",
        )
        for (text in invalid) assertThrows(text, LUTException::class.java) { CubeLUT.parse(text.toByteArray()) }
    }

    // The checks below have no counterpart in the original tests.

    @Test fun sizesAreBounded() {
        fun curve(size: Int) = "LUT_1D_SIZE $size\n" + "0.5 0.5 0.5\n".repeat(size)
        assertEquals(16384, CubeLUT.parse(curve(16384)).dimension)
        assertEquals(65 * 65 * 65 * 3, CubeLUT.parse("LUT_3D_SIZE 65\n" + "0 0 0\n".repeat(65 * 65 * 65)).rgb.size)
        for (text in listOf(curve(1), curve(16385), "LUT_3D_SIZE 1\n0 0 0\n", "LUT_3D_SIZE 66\n", "LUT_3D_SIZE 2.0\n", "LUT_3D_SIZE\n", identity.replace("\n0 0 0", "\n0 0 0 0"))) {
            assertThrows(text.take(40), LUTException::class.java) { CubeLUT.parse(text) }
        }
    }

    @Test fun unknownDirectivesAndBadValuesAreRejected() {
        val rows = identity.substringAfter("LUT_3D_SIZE 2\n")
        val invalid = listOf(
            "LUT_3D_INPUT_RANGE 0 1\n$identity",
            "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n$identity",
            "TITLE \"Again\"\n$identity",
            "DOMAIN_MIN 0 0 0\nDOMAIN_MIN 0 0 0\n$identity",
            "DOMAIN_MAX 1 1\n$identity",
            "DOMAIN_MAX 1 1 70000\n$identity",
            "LUT_3D_SIZE 2\n0 0 0\nDOMAIN_MAX 2 2 2\n" + rows.substringAfter("0 0 0\n"),
            identity.replace("1 1 1", "1 1 70000"),
            identity.replace("1 1 1", "1 1 inf"),
            identity.replace("1 1 1", "1 1 1f"),
            identity.replace("1 1 1", "1 1 one"),
            rows,
        )
        for (text in invalid) assertThrows(text, LUTException::class.java) { CubeLUT.parse(text) }
        assertThrows(LUTException::class.java) { CubeLUT.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(LUTException::class.java) { CubeLUT.parse(ByteArray(CubeLUT.MAXIMUM_BYTES + 1) { '\n'.code.toByte() }) }
    }

    @Test fun commentsBlankLinesAndLineEndingsAreIgnored() {
        val text = "# made by hand\r\n\r\nTITLE  \"Warm look\"  # name\r\nLUT_3D_SIZE 2 # points\r\n" +
            "0 0 0\r\n\t1e0   0 0\r\n0 1 0\r\n1 1 0\r\n0 0 1\r\n1 0 1\r\n0 1 1\r\n1 1 +1.0\r\n"
        val cube = CubeLUT.parse(text)
        assertEquals("Warm look", cube.title)
        assertArrayEquals(CubeLUT.parse(identity).rgb, cube.rgb, 0f)
    }

    @Test fun redIsTheFastestAxisAndSamplingIsTrilinear() {
        // Second entry is input red = 1; it alone maps to (2, 4, 8). Values may exceed 1.
        val cube = CubeLUT.parse("LUT_3D_SIZE 2\n0 0 0\n2 4 8\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n")
        assertArrayEquals(floatArrayOf(2f, 4f, 8f), cube.sample(1f, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), cube.sample(0f, 1f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), cube.sample(0f, 0f, 1f), 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f), cube.sample(0.5f, 0f, 0f), 1e-6f)
        // The corner's weight is the product of the three distances.
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 1f), cube.sample(0.5f, 0.5f, 0.5f), 1e-6f)
    }

    @Test fun inputOutsideTheDomainUsesTheEdge() {
        val cube = CubeLUT.parse(identity)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), cube.sample(4f, -3f, Float.POSITIVE_INFINITY), 0f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0f), cube.sample(Float.NaN, 0.5f, 0f), 0f)
        val curve = CubeLUT.parse("LUT_1D_SIZE 2\n0.1 0.1 0.1\n0.9 0.9 0.9\n")
        assertArrayEquals(floatArrayOf(0.1f, 0.9f, 0.5f), curve.sample(-1f, 2f, 0.5f), 1e-6f)
    }

    @Test fun largerLatticesInterpolateBetweenNeighbours() {
        // A 3-point identity whose middle red sample is lifted to 0.8.
        val text = StringBuilder("LUT_3D_SIZE 3\n")
        for (b in 0..2) for (g in 0..2) for (r in 0..2) text.append("${if (r == 1) 0.8 else r / 2.0} ${g / 2.0} ${b / 2.0}\n")
        val cube = CubeLUT.parse(text.toString())
        assertArrayEquals(floatArrayOf(0.8f, 0.5f, 1f), cube.sample(0.5f, 0.5f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.4f, 0.25f, 0.75f), cube.sample(0.25f, 0.25f, 0.75f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.9f, 1f, 0f), cube.sample(0.75f, 1f, 0f), 1e-6f)
    }
}
