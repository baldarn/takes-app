package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectBackgroundMediaTest {
    /** Tests/VideoEffectIntegrationTests.swift:562-564: two moving frames on one clock across the loop. */
    @Test fun theLoopOverlapsTheEndAndTheStart() {
        val loop = EffectBackgroundLoop(4.0)
        val phase = loop.phase(3.9)
        assertEquals(0.1, phase.incoming, 1e-5)
        assertEquals(3.9, phase.outgoing!!, 1e-5)
        assertEquals(0.5, phase.blend, 1e-5)
        // VideoEffectBackgroundAudio.swift:9-14: the first pass has nothing to dissolve from, and past the overlap only one frame.
        assertNull(loop.phase(0.1).outgoing)
        assertEquals(0.1, loop.phase(0.1).incoming, 1e-9)
        assertNull(loop.phase(3.0).outgoing)
        assertEquals(1.0, loop.phase(3.0).blend, 1e-9)
        assertEquals(0.0, loop.phase(-2.0).incoming, 1e-9)
        // A movie of a quarter second overlaps an eighth.
        assertEquals(0.125, EffectBackgroundLoop(0.25).overlap, 1e-9)
    }

    /** VideoEffectAssets.swift:596: the movie plays from the effect's start. */
    @Test fun theMovieStartsWithTheEffect() {
        val effect = VideoEffectSettings(VideoEffectID.ChromaKey, start = 2.0, end = 9.0)
        assertEquals(1.5, EffectBackgroundLoop.time(effect, 3.5), 1e-9)
        assertEquals(0.0, EffectBackgroundLoop.time(effect, 1.0), 1e-9)
    }

    /** VideoEffectModel.swift:252-262: a picture under a Video background, or a movie under an Image one, is let go. */
    @Test fun aBackgroundKeepsOnlyMediaOfItsKind() {
        val picture = VideoEffectSettings(VideoEffectID.ChromaKey).with("background", EffectValue.Choice("Image")).with("media", EffectValue.Media("back.png"))
        assertEquals(picture, picture.sanitizedBackgroundMedia(0.0))
        val video = picture.with("background", EffectValue.Choice("Video"))
        assertEquals(EffectValue.Media(null), video.sanitizedBackgroundMedia(0.0).value("media", 0.0))
        val movie = video.with("media", EffectValue.Media("back.MOV"))
        assertEquals(movie, movie.sanitizedBackgroundMedia(0.0))
        // A Solid background keeps what it had, for when the choice comes back.
        val solid = movie.with("background", EffectValue.Choice("Solid"))
        assertEquals(solid, solid.sanitizedBackgroundMedia(0.0))
        // A background that moves is let go by a key there.
        val moving = movie.withKeyframe("background", 0.0, EffectValue.Choice("Video")).withKeyframe("background", 2.0, EffectValue.Choice("Image"))
        val sanitized = moving.sanitizedBackgroundMedia(2.0)
        assertEquals(EffectValue.Media(null), sanitized.value("media", 2.0))
        assertEquals(listOf(2.0), sanitized.parameters.first { it.id == "media" }.keyframes.map { it.time })
    }

    private fun frame(w: Int, h: Int, level: Int) = ByteArray(w * h * 4) { if (it % 4 == 3) -1 else level.toByte() }

    /** Tests/VideoEffectIntegrationTests.swift:841-848: a full second, at least eight frames, the linear mean kept. */
    @Test fun aCleanPlateAveragesASecondInLinearLight() {
        val plate = CleanPlateAverage()
        for (i in 0..30) {
            val done = plate.append(frame(4, 3, if (i % 2 == 0) 255 else 0), 4, 3, i / 30.0)
            assertEquals(i == 30, done)
        }
        // Sixteen frames of white and fifteen of black: their mean in linear light, encoded.
        val expected = (EffectColorMath.encoded(16.0 / 31) * 255 + .5).toInt()
        assertEquals(expected, plate.encoded()!![0].toInt() and 0xff)
        assertEquals(-1, plate.encoded()!![3].toInt())
        // A second is not enough with fewer than eight frames.
        val sparse = CleanPlateAverage()
        assertFalse((0..4).map { sparse.append(frame(2, 2, 9), 2, 2, it * 0.25) }.last())
        assertTrue(sparse.append(frame(2, 2, 9), 2, 2, 1.2).not())
    }

    /** Tests/VideoEffectIntegrationTests.swift:870-878: another shape, a gap or another range ends the capture. */
    @Test fun aCleanPlateCaptureEndsWhenTheCameraChanges() {
        fun failure(change: (CleanPlateAverage) -> Unit): CleanPlateAverage.Failure {
            val plate = CleanPlateAverage()
            plate.append(frame(4, 3, 9), 4, 3, 0.0)
            return assertThrows(CleanPlateAverage.Failed::class.java) { change(plate) }.failure
        }
        assertEquals(CleanPlateAverage.Failure.Orientation, failure { it.append(frame(3, 4, 9), 3, 4, 0.033) })
        assertEquals(CleanPlateAverage.Failure.Interrupted, failure { it.append(frame(4, 3, 9), 4, 3, 0.5) })
        assertEquals(CleanPlateAverage.Failure.Interrupted, failure { it.append(frame(4, 3, 9), 4, 3, -0.1) })
        assertEquals(CleanPlateAverage.Failure.Format, failure { it.append(frame(4, 3, 9), 4, 3, 0.033, hdr = true) })
    }
}
