package com.neilluo.scrolllock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BypassAndCoolingOffTest {
    @Test fun oneBypassPerDay() {
        val used = BypassPolicy.start(bootCount = 3, nowElapsed = 1000, today = "2026-11-06")
        assertFalse(BypassPolicy.canUse(used, "2026-11-06"))
        assertTrue(BypassPolicy.canUse(used, "2026-11-07"))
        assertTrue(BypassPolicy.canUse(null, "2026-11-06"))
    }

    @Test fun bypassLastsFiveMinutes() {
        val b = BypassPolicy.start(3, 1000, "d")
        assertEquals(300_000L, BypassPolicy.remainingMs(b, 3, 1000))
        assertEquals(1000L, BypassPolicy.remainingMs(b, 3, 1000 + 299_000))
        assertEquals(0L, BypassPolicy.remainingMs(b, 3, 1000 + 400_000))
    }

    @Test fun aRebootEndsABypass() {
        val b = BypassPolicy.start(3, 1000, "d")
        assertEquals(0L, BypassPolicy.remainingMs(b, bootCount = 4, nowElapsed = 2000))
    }

    @Test fun dayKeyFollowsTheZone() {
        // 2026-11-06 23:30 UTC is already the 7th in Sydney.
        val ms = java.time.Instant.parse("2026-11-06T23:30:00Z").toEpochMilli()
        assertEquals("2026-11-06", BypassPolicy.dayKey(ms, ZoneId.of("UTC")))
        assertEquals("2026-11-07", BypassPolicy.dayKey(ms, ZoneId.of("Australia/Sydney")))
    }

    @Test fun coolingOffCountsDown() {
        val req = UnlockRequest(bootCount = 1, requestedAtElapsed = 0)
        val day = CoolingOff.DEFAULT_PERIOD_MS
        assertEquals(day, CoolingOff.remainingMs(req, 1, 0))
        assertEquals(day - 3_600_000, CoolingOff.remainingMs(req, 1, 3_600_000))
        assertFalse(CoolingOff.canConfirm(req, 1, day - 1))
        assertTrue(CoolingOff.canConfirm(req, 1, day))
    }

    @Test fun aRebootRestartsTheWaitInsteadOfShorteningIt() {
        val req = UnlockRequest(bootCount = 1, requestedAtElapsed = 0)
        // 23 hours in, the phone reboots; elapsedRealtime restarts near zero.
        val after = CoolingOff.remainingMs(req, bootCount = 2, nowElapsed = 60_000)
        assertEquals(CoolingOff.DEFAULT_PERIOD_MS, after)
        assertEquals(UnlockRequest(2, 60_000), CoolingOff.normalize(req, 2, 60_000))
    }

    @Test fun timeFormatting() {
        assertEquals("0s", TimeFormat.elapsed(-5))
        assertEquals("45s", TimeFormat.elapsed(45_000))
        assertEquals("12m 04s", TimeFormat.elapsed(12 * 60_000 + 4_000))
        assertEquals("3h 12m", TimeFormat.elapsed((3 * 3600 + 12 * 60) * 1000L))
        assertEquals("2d 3h", TimeFormat.elapsed((2 * 86_400 + 3 * 3600) * 1000L))
    }
}
