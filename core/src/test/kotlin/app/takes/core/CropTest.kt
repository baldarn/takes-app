package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropTest {
    private val sources = listOf(Size(1920.0, 1080.0), Size(1080.0, 1920.0))
    private val aspects = listOf(16.0 / 9, 9.0 / 16, 1.0, 4.0 / 5)

    // Translated from VideoToolsTests.swift.

    @Test fun cropAtAnEdgeStaysInsideTheSourceAndNeverStretches() {
        for (source in sources) for (aspect in aspects) {
            val canvas = OutputShape.canvas(source, aspect)
            for (zoom in listOf(1.0, 2.0, 8.0)) {
                val area = CropSettings(x = 0.98, y = 0.02, zoom = zoom).rect(source, canvas)
                assertTrue(area.minX >= -0.00001 && area.minY >= -0.00001 && area.maxX <= source.width + 0.00001 && area.maxY <= source.height + 0.00001)
                assertEquals(canvas.width / canvas.height, area.width / area.height, 0.00001)
            }
        }
    }

    @Test fun evenPixelRoundingOfOddMediaIsNotACrop() {
        val oddSource = Size(1179.0, 2556.0)
        val evenCanvas = Size(1180.0, 2556.0)
        assertTrue(CropSettings().isFullFrame(oddSource, evenCanvas))
        // A deliberate zoom remains an edit on odd imported media.
        assertFalse(CropSettings(zoom = 1.01).isFullFrame(oddSource, evenCanvas))
        // A different aspect still crops odd imported media.
        assertFalse(CropSettings().isFullFrame(oddSource, Size(1080.0, 1920.0)))
    }

    @Test fun rotationAspectPanAndZoomNeverExposeAnEmptyCorner() {
        for (source in sources) for (aspect in aspects) {
            val canvas = OutputShape.canvas(source, aspect)
            for (angle in listOf(-180.0, -135.0, -90.0, -45.0, -17.0, 0.0, 17.0, 45.0, 90.0, 135.0, 180.0)) for (zoom in listOf(1.0, 2.0, 8.0)) {
                val inverse = CropSettings(x = 0.98, y = 0.02, zoom = zoom, rotation = angle).transform(source, canvas).inverted()
                for (corner in listOf(Point.ZERO, Point(canvas.width, 0.0), Point(0.0, canvas.height), Point(canvas.width, canvas.height))) {
                    val point = corner.applying(inverse)
                    assertTrue(
                        "angle $angle zoom $zoom corner $corner -> $point",
                        point.x >= -0.00001 && point.y >= -0.00001 && point.x <= source.width + 0.00001 && point.y <= source.height + 0.00001,
                    )
                }
            }
        }
    }

    @Test fun upsideDownFullFrameRemainsARealEdit() {
        assertFalse(CropSettings(rotation = 180.0).isFullFrame(Size(1920.0, 1080.0), Size(1920.0, 1080.0)))
    }

    // Own tests.

    @Test fun defaultCropOfMatchingAspectIsTheWholeSource() {
        val source = Size(1920.0, 1080.0)
        assertEquals(Rect(0.0, 0.0, 1920.0, 1080.0), CropSettings().rect(source, source))
        // Zoom 2 at the center shows the middle half.
        assertEquals(Rect(480.0, 270.0, 960.0, 540.0), CropSettings(zoom = 2.0).rect(source, source))
        // The same aspect at another resolution changes nothing: the window is in source pixels.
        assertEquals(Rect(480.0, 270.0, 960.0, 540.0), CropSettings(zoom = 2.0).rect(source, Size(640.0, 360.0)))
    }

    @Test fun zoomOutsideItsLimitsIsReadAsTheLimit() {
        val source = Size(1920.0, 1080.0)
        assertEquals(CropSettings(zoom = 1.0).rect(source, source), CropSettings(zoom = 0.2).rect(source, source))
        assertEquals(CropSettings(zoom = 8.0).rect(source, source), CropSettings(zoom = 50.0).rect(source, source))
    }

    @Test fun emptySourceOrCanvasGivesNoRectangle() {
        assertEquals(Rect.ZERO, CropSettings().rect(Size(0.0, 1080.0), Size(100.0, 100.0)))
        assertEquals(Rect.ZERO, CropSettings().rect(Size(1920.0, 1080.0), Size(100.0, 0.0)))
        assertEquals(Affine.IDENTITY, CropSettings().transform(Size(0.0, 0.0), Size(100.0, 100.0)))
    }

    @Test fun quarterTurnOfLandscapeIntoLandscapeNeedsTheShortSide() {
        // Rotated 90°, a 16:9 window must fit its long side in the source's height.
        val source = Size(1920.0, 1080.0)
        val crop = CropSettings(rotation = 90.0)
        val area = crop.rect(source, source)
        assertEquals(1080.0, area.width, 0.0001)
        assertEquals(607.5, area.height, 0.0001)
        val footprint = crop.footprint(source, source)
        assertEquals(607.5, footprint.width, 0.0001)
        assertEquals(1080.0, footprint.height, 0.0001)
        assertTrue(Rect(-0.0001, -0.0001, 1920.0002, 1080.0002).contains(footprint))
    }

    @Test fun transformMapsTheWindowOntoTheCanvas() {
        val source = Size(1920.0, 1080.0)
        val canvas = Size(1280.0, 720.0)
        val crop = CropSettings(x = 0.3, y = 0.6, zoom = 2.0)
        val area = crop.rect(source, canvas)
        val transform = crop.transform(source, canvas)
        val origin = Point(area.minX, area.minY).applying(transform)
        val far = Point(area.maxX, area.maxY).applying(transform)
        assertEquals(0.0, origin.x, 1e-9); assertEquals(0.0, origin.y, 1e-9)
        assertEquals(1280.0, far.x, 1e-9); assertEquals(720.0, far.y, 1e-9)
    }

    @Test fun clampedStoresWhatIsShown() {
        val source = Size(1920.0, 1080.0)
        val wild = CropSettings(x = 0.98, y = 0.02, zoom = 20.0, rotation = 377.0)
        val clamped = wild.clamped(source, source)
        assertEquals(8.0, clamped.zoom, 0.0)
        assertEquals(17.0, clamped.rotation, 1e-9)
        assertTrue(clamped.valid)
        assertFalse(wild.valid)
        // Clamping changes the stored numbers, not the picture.
        val expected = wild.copy(zoom = 8.0, rotation = 17.0).rect(source, source)
        val actual = clamped.rect(source, source)
        assertEquals(expected.x, actual.x, 1e-9); assertEquals(expected.y, actual.y, 1e-9)
    }

    @Test fun rotationNormalizesToAHalfTurnEitherWay() {
        assertEquals(0.0, CropSettings.normalizedRotation(360.0), 0.0)
        assertEquals(180.0, CropSettings.normalizedRotation(180.0), 0.0)
        assertEquals(-180.0, CropSettings.normalizedRotation(-180.0), 0.0)
        assertEquals(180.0, CropSettings.normalizedRotation(540.0), 0.0)
        assertEquals(-170.0, CropSettings.normalizedRotation(190.0), 1e-9)
        assertEquals(170.0, CropSettings.normalizedRotation(-190.0), 1e-9)
        assertEquals(0.0, CropSettings.normalizedRotation(Double.NaN), 0.0)
    }

    @Test fun validationRangesMatchTheOriginal() {
        assertTrue(CropSettings().valid)
        assertTrue(CropSettings(x = 0.0, y = 1.0, zoom = 8.0, rotation = -180.0).valid)
        assertFalse(CropSettings(x = -0.01).valid)
        assertFalse(CropSettings(y = 1.01).valid)
        assertFalse(CropSettings(zoom = 0.99).valid)
        assertFalse(CropSettings(zoom = 8.01).valid)
        assertFalse(CropSettings(rotation = 180.5).valid)
        assertFalse(CropSettings(x = Double.NaN).valid)
    }

    @Test fun outputShapesKeepTheShortEdgeOnEvenPixels() {
        val source = Size(1920.0, 1080.0)
        assertEquals(source, OutputShape.canvas(source, null))
        assertEquals(Size(1920.0, 1080.0), OutputShape.canvas(source, OutputShape.Landscape.aspect))
        assertEquals(Size(1080.0, 1920.0), OutputShape.canvas(source, OutputShape.Portrait.aspect))
        assertEquals(Size(1080.0, 1080.0), OutputShape.canvas(source, OutputShape.Square.aspect))
        assertEquals(Size(1080.0, 1350.0), OutputShape.canvas(source, OutputShape.Feed.aspect))
        assertEquals(Size(2.0, 2.0), OutputShape.canvas(Size(1.0, 1.0), 1.0))
    }

    @Test fun mirrorsFlipAroundTheCanvas() {
        val canvas = Size(640.0, 360.0)
        assertEquals(Point(540.0, 50.0), Point(100.0, 50.0).applying(MirrorSettings(horizontal = true).transform(canvas)))
        assertEquals(Point(100.0, 310.0), Point(100.0, 50.0).applying(MirrorSettings(vertical = true).transform(canvas)))
        assertEquals(Affine.IDENTITY, MirrorSettings().transform(canvas))
    }

    @Test fun cropReadsAndWritesTheOriginalFields() {
        val crop = CropSettings(id = "A", x = 0.75, y = 0.3, zoom = 2.0, rotation = 17.0)
        val text = documentJson.encodeToString(CropSettings.serializer(), crop)
        assertEquals("""{"id":"A","x":0.75,"y":0.3,"zoom":2.0,"rotation":17.0}""", text)
        assertEquals(crop, documentJson.decodeFromString(CropSettings.serializer(), text))
        // Older documents have no rotation.
        assertEquals(0.0, documentJson.decodeFromString(CropSettings.serializer(), """{"id":"A","x":0.5,"y":0.5,"zoom":1}""").rotation, 0.0)
    }
}
