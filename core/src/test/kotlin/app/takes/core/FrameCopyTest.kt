package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCopyTest {
    // YouRec/EditorController.swift:14-20: the frame showing at a time, on the composition's clock.
    @Test fun thePictureTimeIsTheFrameShowing() {
        val frame = 1.0 / 30
        assertEquals(0.0, FrameCopy.pictureTime(0.0, 10.0, frame), 0.0)
        assertEquals(1.0, FrameCopy.pictureTime(1.0, 10.0, frame), 1e-12)
        assertEquals(1.0, FrameCopy.pictureTime(1.0 + frame / 2, 10.0, frame), 1e-12)
        assertEquals(1.0 + frame, FrameCopy.pictureTime(1.0 + frame, 10.0, frame), 1e-12)
        // Before the movie, the start; at or past its end, the last frame.
        assertEquals(0.0, FrameCopy.pictureTime(-2.0, 10.0, frame), 0.0)
        assertEquals(10.0 - frame, FrameCopy.pictureTime(10.0, 10.0, frame), 1e-9)
        assertEquals(10.0 - frame, FrameCopy.pictureTime(12.0, 10.0, frame), 1e-9)
        // Without a clock, the instant itself, held before the last 60000th of a second.
        assertEquals(10.0 - 1.0 / 60_000, FrameCopy.pictureTime(12.0, 10.0, null), 1e-12)
        assertEquals(2.345, FrameCopy.pictureTime(2.345, 10.0, 0.0), 0.0)
    }

    // EditorView.swift:597: not in the full-screen preview, not in an effect's range, not while loading or empty.
    @Test fun copyIsOfferedOnALoadedMovie() {
        assertTrue(FrameCopy.enabled(fullPreview = false, effectRange = false, loading = false, duration = 3.0))
        assertFalse(FrameCopy.enabled(fullPreview = true, effectRange = false, loading = false, duration = 3.0))
        assertFalse(FrameCopy.enabled(fullPreview = false, effectRange = true, loading = false, duration = 3.0))
        assertFalse(FrameCopy.enabled(fullPreview = false, effectRange = false, loading = true, duration = 3.0))
        assertFalse(FrameCopy.enabled(fullPreview = false, effectRange = false, loading = false, duration = 0.0))
    }
}
