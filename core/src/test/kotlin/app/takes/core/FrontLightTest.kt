package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * The front light. The original has no test for it; these are the port's
 * own, from the numbers of YouRec/RecorderFrontLight.swift:16-68.
 */
class FrontLightTest {
    @Test fun theRingIsAShareOfTheShortSide() {
        // A screen of 412 by 915 dp: 18.5, 35 and 57.7 dp.
        assertEquals(18.54, FrontLight.thickness(412.0, 915.0, FrontLight.Width.Thin), 1e-9)
        assertEquals(35.02, FrontLight.thickness(412.0, 915.0, FrontLight.Width.Medium), 1e-9)
        assertEquals(57.68, FrontLight.thickness(412.0, 915.0, FrontLight.Width.Wide), 1e-9)
        // The same on its side.
        assertEquals(35.02, FrontLight.thickness(915.0, 412.0, FrontLight.Width.Medium), 1e-9)
    }

    @Test fun theOpeningFollowsTheCornerAndNeverGetsSharp() {
        assertEquals(20.0, FrontLight.openingRadius(55.0, 35.0), 0.0)
        // A ring wider than the corner leaves a small radius.
        assertEquals(10.0, FrontLight.openingRadius(40.0, 57.0), 0.0)
        // A square screen keeps its square opening.
        assertEquals(4.0, FrontLight.openingRadius(4.0, 35.0), 0.0)
        assertEquals(0.0, FrontLight.openingRadius(0.0, 35.0), 0.0)
    }

    @Test fun theBulbNestsAgainstTheCorner() {
        assertEquals(25.0, FrontLight.bulbInset(0.0), 0.0)
        assertEquals(25.0, FrontLight.bulbInset(22.0), 0.0)
        assertEquals(25 + 18 * (1 - 1 / sqrt(2.0)), FrontLight.bulbInset(40.0), 1e-12)
    }

    @Test fun theLightStartsMediumWhiteAndFull() {
        assertEquals(FrontLight.Width.Medium, FrontLight.width(null))
        assertEquals(FrontLight.Width.Medium, FrontLight.width("Huge"))
        assertEquals(FrontLight.Width.Wide, FrontLight.width("Wide"))
        assertEquals(FrontLight.Tone.White, FrontLight.tone(null))
        assertEquals(FrontLight.Tone.Cool, FrontLight.tone("Cool"))
        // The fourth swatch was once saved as "Rose".
        assertEquals(FrontLight.Tone.Custom, FrontLight.tone("Rose"))
        assertEquals(1.0, FrontLight.opacity(Double.NaN), 0.0)
    }

    @Test fun theOpacityMovesInStepsOfFivePercent() {
        assertEquals(0.5, FrontLight.opacity(0.52), 1e-12)
        assertEquals(0.55, FrontLight.opacity(0.53), 1e-12)
        assertEquals(0.0, FrontLight.opacity(-0.2), 0.0)
        assertEquals(1.0, FrontLight.opacity(1.4), 0.0)
    }

    @Test fun theFixedColoursAreTheOriginals() {
        assertEquals(FrontLight.Rgb(1.0, 1.0, 1.0), FrontLight.color(FrontLight.Tone.White))
        assertEquals(FrontLight.Rgb(1.0, 0.82, 0.62), FrontLight.color(FrontLight.Tone.Warm))
        assertEquals(FrontLight.Rgb(0.72, 0.87, 1.0), FrontLight.color(FrontLight.Tone.Cool))
    }

    @Test fun theCustomColourIsAPaleHue() {
        fun close(expected: FrontLight.Rgb, actual: FrontLight.Rgb) {
            assertEquals(expected.red, actual.red, 1e-9)
            assertEquals(expected.green, actual.green, 1e-9)
            assertEquals(expected.blue, actual.blue, 1e-9)
        }
        close(FrontLight.Rgb(1.0, 0.7, 0.7), FrontLight.color(FrontLight.Tone.Custom, 0.0))
        close(FrontLight.Rgb(0.7, 1.0, 0.7), FrontLight.color(FrontLight.Tone.Custom, 120.0))
        close(FrontLight.Rgb(0.7, 0.7, 1.0), FrontLight.color(FrontLight.Tone.Custom, 240.0))
        close(FrontLight.Rgb(1.0, 1.0, 0.7), FrontLight.color(FrontLight.Tone.Custom, 60.0))
        // Until one is chosen it is the original's rose: 1, 0.70, 0.79.
        close(FrontLight.Rgb(1.0, 0.70, 0.79), FrontLight.color(FrontLight.Tone.Custom))
        // The hue turns in a circle.
        close(FrontLight.color(FrontLight.Tone.Custom, 20.0), FrontLight.color(FrontLight.Tone.Custom, 380.0))
        close(FrontLight.color(FrontLight.Tone.Custom, 340.0), FrontLight.color(FrontLight.Tone.Custom, -20.0))
    }
}
