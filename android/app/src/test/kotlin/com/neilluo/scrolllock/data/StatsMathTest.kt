package com.neilluo.scrolllock.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsMathTest {
    private val today = LocalDate.parse("2026-11-06")

    @Test fun emptyHistoryIsAllZero() {
        val s = StatsMath.summarize(emptyMap(), today)
        assertEquals(0, s.cleanStreakDays)
        assertEquals(0L, s.blockedTotalMs)
    }

    @Test fun streakStopsAtTheMostRecentBypass() {
        val days = mapOf(
            "2026-11-01" to DayStats(1000, 1),
            "2026-11-02" to DayStats(1000, 0),
            "2026-11-05" to DayStats(2000, 0),
            "2026-11-06" to DayStats(500, 0),
        )
        // 6th, 5th, 4th, 3rd, 2nd have no bypass; the 1st had one.
        assertEquals(5, StatsMath.summarize(days, today).cleanStreakDays)
    }

    @Test fun aBypassTodayResetsTheStreakToZero() {
        val days = mapOf("2026-11-05" to DayStats(0, 0), "2026-11-06" to DayStats(0, 1))
        assertEquals(0, StatsMath.summarize(days, today).cleanStreakDays)
    }

    @Test fun streakNeverCountsBeforeTheFirstRecord() {
        val days = mapOf("2026-11-05" to DayStats(1000, 0))
        assertEquals(2, StatsMath.summarize(days, today).cleanStreakDays)
    }

    @Test fun totalsAndWeekWindow() {
        val days = mapOf(
            "2026-10-20" to DayStats(10_000, 2),
            "2026-11-03" to DayStats(5_000, 1),
            "2026-11-06" to DayStats(1_000, 0),
        )
        val s = StatsMath.summarize(days, today)
        assertEquals(16_000L, s.blockedTotalMs)
        assertEquals(1_000L, s.blockedTodayMs)
        assertEquals(1, s.bypassesThisWeek)
        assertEquals(3, s.bypassesTotal)
    }
}
