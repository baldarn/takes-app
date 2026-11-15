package app.takes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DeletionRulesTest {
    // The original's fixture: 1 800 000 000 s after 1970, a Gregorian calendar in GMT (Tests/CoreTests.swift:718-727).
    private val now = 1_800_000_000_000L
    private val utc = ZoneOffset.UTC
    private val cutoff = now - 30 * 86_400_000L

    @Test fun exactlyThirtyDaysIsNotOlderThanThirtyDays() {
        // Tests/CoreTests.swift:725
        assertFalse(DeletionRules.isOlderThan30Days(cutoff, now, utc))
    }

    @Test fun moreThanThirtyDaysQualifies() {
        // Tests/CoreTests.swift:726
        assertTrue(DeletionRules.isOlderThan30Days(cutoff - 1000, now, utc))
        assertTrue(DeletionRules.isOlderThan30Days(cutoff - 1, now, utc))
    }

    @Test fun futureDatesDoNotQualify() {
        // Tests/CoreTests.swift:727
        assertFalse(DeletionRules.isOlderThan30Days(now + 60_000, now, utc))
    }

    @Test fun aRecentDeletionDoesNotExpire() {
        // Tests/CoreTests.swift:747: the age runs from the deletion, not from the last edit.
        assertFalse(DeletionRules.isOlderThan30Days(now, now, utc))
    }

    @Test fun theDaysAreCalendarDaysAcrossSummerTime() {
        // As the original's Calendar.date(byAdding: .day, value: -30): 30 days before 12:00 on 15 April in Rome
        // is 12:00 on 16 March, only 30 times 24 hours less one hour earlier, because the clocks went forward on 29 March.
        val rome = ZoneId.of("Europe/Rome")
        val at = ZonedDateTime.of(2026, 4, 15, 12, 0, 0, 0, rome).toInstant().toEpochMilli()
        val boundary = ZonedDateTime.of(2026, 3, 16, 12, 0, 0, 0, rome).toInstant().toEpochMilli()
        assertEquals(30 * 86_400_000L - 3_600_000L, at - boundary)
        assertFalse(DeletionRules.isOlderThan30Days(boundary, at, rome))
        assertTrue(DeletionRules.isOlderThan30Days(boundary - 1, at, rome))
        // Less than 30 times 24 hours ago, yet more than 30 calendar days.
        assertTrue(DeletionRules.isOlderThan30Days(at - 30 * 86_400_000L + 1, at, rome))
    }

    @Test fun knownDatesFirstThenNewestFirstThenByID() {
        // YouRec/ProjectStore.swift:294-299
        val entries = listOf(
            DeletionRules.Entry("e", null, false),
            DeletionRules.Entry("d", now - 5, false),
            DeletionRules.Entry("b", now - 10, true),
            DeletionRules.Entry("c", now, true),
            DeletionRules.Entry("a", now - 10, true),
            DeletionRules.Entry("f", now + 100, false),
        )
        val order = DeletionRules.ordered(entries) { it }.map { it.id }
        assertEquals(listOf("c", "a", "b", "f", "d", "e"), order)
    }

    @Test fun aMissingDateCountsAsTheBeginningOfTime() {
        val entries = listOf(DeletionRules.Entry("b", null, false), DeletionRules.Entry("a", Long.MIN_VALUE + 1, false))
        assertEquals(listOf("a", "b"), DeletionRules.ordered(entries) { it }.map { it.id })
        val ties = listOf(DeletionRules.Entry("b", null, false), DeletionRules.Entry("a", null, false))
        assertEquals(listOf("a", "b"), DeletionRules.ordered(ties) { it }.map { it.id })
    }
}
