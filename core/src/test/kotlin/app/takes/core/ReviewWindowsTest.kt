package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the editor plays to check something. The numbers are the original's:
 * YouRec/EditorController.swift:408-413 (quick review) and 437-462 (a cut
 * before it is made), YouRec/TransitionEditor.swift:123 (a transition),
 * YouRec/EffectApplication.swift:9-12 (an effect). Tests marked TRANSLATED
 * carry over an expectation of the iPhone app's tests.
 */
class ReviewWindowsTest {
    private fun review(start: Double, end: Double, returnTo: Double) = ReviewWindows.Review(start, end, returnTo)

    // MARK: Quick review

    @Test fun quickReviewIsASecondEachSideThenBack() {
        assertEquals(review(29.0, 31.0, 30.0), ReviewWindows.quick(30.0, 60.0))
    }

    @Test fun quickReviewStopsAtTheEndsOfTheMovie() {
        assertEquals(review(0.0, 1.0, 0.0), ReviewWindows.quick(0.0, 60.0))
        assertEquals(review(0.0, 1.4, 0.4), ReviewWindows.quick(0.4, 60.0))
        assertEquals(review(59.0, 60.0, 60.0), ReviewWindows.quick(60.0, 60.0))
        assertEquals(review(58.5, 60.0, 59.5), ReviewWindows.quick(59.5, 60.0))
    }

    @Test fun quickReviewOfAPlayheadOutsideTheMovieIsAtItsNearerEnd() {
        assertEquals(review(0.0, 1.0, 0.0), ReviewWindows.quick(-3.0, 60.0))
        assertEquals(review(59.0, 60.0, 60.0), ReviewWindows.quick(75.0, 60.0))
    }

    @Test fun quickReviewOfAMovieShorterThanTheWindowPlaysAllOfIt() {
        assertEquals(review(0.0, 1.5, 0.75), ReviewWindows.quick(0.75, 1.5))
        assertEquals(review(0.0, 0.0, 0.0), ReviewWindows.quick(5.0, 0.0))
    }

    @Test fun aSecondTapWithinAboutAQuarterSecondIsAQuickReview() {
        assertEquals(0.27, ReviewWindows.DOUBLE_TAP, 0.0)
        assertEquals(1.0, ReviewWindows.REVIEW, 0.0)
    }

    // MARK: A cut before it is made

    @Test fun aCutIsPreviewedAcrossWhereTheRemovedStretchBegan() {
        // 10 s removed from second 10 of a minute: 50 s would remain.
        assertEquals(review(9.0, 11.0, 23.0), ReviewWindows.cut(10.0, 50.0, 23.0))
    }

    @Test fun aCutNearTheStartBeginsAtZero() {
        assertEquals(review(0.0, 1.5, 0.5), ReviewWindows.cut(0.5, 50.0, 0.5))
        assertEquals(review(0.0, 1.0, 0.0), ReviewWindows.cut(0.0, 50.0, 0.0))
    }

    @Test fun aCutOfTheTailStopsJustShortOfTheNewEnd() {
        // The last 2 s of a minute removed: 58 s would remain, and nothing follows the cut.
        assertEquals(review(57.0, 57.99, 59.0), ReviewWindows.cut(58.0, 58.0, 59.0))
        // Half a second would be left after the cut.
        assertEquals(review(9.0, 10.49, 10.0), ReviewWindows.cut(10.0, 10.5, 10.0))
    }

    @Test fun removingEverythingHasNothingToPreview() {
        assertNull(ReviewWindows.cut(0.0, 0.0, 0.0))
        assertNull(ReviewWindows.cut(0.0, Double.NaN, 0.0))
    }

    @Test fun aCutLeavingAlmostNothingNeverEndsBeforeItStarts() {
        assertEquals(review(0.0, 0.0, 0.0), ReviewWindows.cut(0.0, 0.005, 0.0))
    }

    // MARK: A transition

    @Test fun aTransitionIsPlayedWithAMarginOnEachSide() {
        val range = ReviewWindows.transition(4.5, 5.5, 10.0)
        assertEquals(3.85, range.start, 1e-12)
        assertEquals(6.15, range.endInclusive, 1e-12)
    }

    @Test fun aTransitionNearAnEndIsCutAtThatEnd() {
        assertEquals(0.0..1.3, ReviewWindows.transition(0.2, 1.0, 1.3))
        assertEquals(0.0..0.5, ReviewWindows.transition(0.0, 0.5, 0.5))
        val closing = ReviewWindows.transition(9.0, 10.0, 10.0)
        assertEquals(8.35, closing.start, 1e-12)
        assertEquals(10.0, closing.endInclusive, 0.0)
    }

    // MARK: An effect

    /** TRANSLATED Tests/TrackMediaTests.swift:83. */
    @Test fun anEffectIsTriedFiveSecondsEachSideOfThePlayhead() {
        assertEquals(25.0..35.0, ReviewWindows.effect(30.0, 0.0..60.0))
    }

    /** TRANSLATED Tests/TrackMediaTests.swift:84. */
    @Test fun atTheBeginningItDoesNotReachFurtherAfter() {
        assertEquals(0.0..5.0, ReviewWindows.effect(0.0, 0.0..60.0))
    }

    /** TRANSLATED Tests/TrackMediaTests.swift:85. */
    @Test fun itNeverLeavesTheSelectedPiece() {
        assertEquals(32.0..34.0, ReviewWindows.effect(33.0, 32.0..34.0))
    }

    @Test fun aPlayheadOutsideTheSelectionCountsAsItsNearerEnd() {
        assertEquals(10.0..15.0, ReviewWindows.effect(2.0, 10.0..40.0))
        assertEquals(35.0..40.0, ReviewWindows.effect(90.0, 10.0..40.0))
        assertEquals(4.0..14.0, ReviewWindows.effect(9.0, 0.0..18.0))
    }
}
