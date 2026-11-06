package com.neilluo.scrolllock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateLogicTest {
    private fun status(working: Boolean, since: Long? = null, lease: Long? = null, serverTime: Long = 1_000_000, reason: String = "") =
        RelayStatus(working = working, unlockReason = reason, since = since, leaseUntil = lease, serverTime = serverTime, activeSessions = if (working) 1 else 0)

    private fun gate(s: RelaySnapshot?, now: Long, failed: Boolean = false, bypass: Long = 0, configured: Boolean = true, local: Boolean = false) =
        GateLogic.compute(configured, s, failed, now, bypass, local)

    @Test fun unlockedWhileTheLeaseHasTimeLeft() {
        val snap = RelaySnapshot.fromStatus(status(true, since = 990_000, lease = 1_060_000, reason = "claude_busy:1 sessions"), receivedElapsed = 5_000)
        val g = gate(snap, now = 5_000 + 20_000) as Gate.Open
        assertEquals(40_000, g.remainingMs)
        assertEquals(1, g.sessions)
    }

    @Test fun locksItselfWhenTheLeaseRunsOutWithNoNewMessage() {
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_060_000), receivedElapsed = 5_000)
        val g = gate(snap, now = 5_000 + 70_000) as Gate.Closed
        assertEquals(ClosedReason.LeaseExpired, g.reason)
        assertEquals(10_000L, g.elapsedMs)
    }

    @Test fun expiredLeaseAfterAFailedFetchSaysRelayUnreachable() {
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_010_000), receivedElapsed = 0)
        assertEquals(ClosedReason.RelayUnreachable, (gate(snap, now = 60_000, failed = true) as Gate.Closed).reason)
    }

    @Test fun lockedShowsTimeSinceClaudeStopped() {
        // Claude stopped 30s before the status was read, and 45s have passed since.
        val snap = RelaySnapshot.fromStatus(status(false, since = 970_000, reason = "locked:idle"), receivedElapsed = 10_000)
        val g = gate(snap, now = 10_000 + 45_000) as Gate.Closed
        assertEquals(ClosedReason.Idle, g.reason)
        assertEquals(75_000L, g.elapsedMs)
    }

    @Test fun lockedReasonsAreMapped() {
        val a = RelaySnapshot.fromStatus(status(false, reason = "locked:prompt_window_expired"), 0)
        val b = RelaySnapshot.fromStatus(status(false, reason = "locked:no_events_24h"), 0)
        assertEquals(ClosedReason.PromptWindowExpired, (gate(a, 0) as Gate.Closed).reason)
        assertEquals(ClosedReason.NoEvents, (gate(b, 0) as Gate.Closed).reason)
    }

    @Test fun bypassBeatsEverythingExceptItsOwnExpiry() {
        val snap = RelaySnapshot.fromStatus(status(false, reason = "locked:idle"), 0)
        assertTrue(gate(snap, 0, bypass = 1000) is Gate.Bypass)
        assertTrue(gate(snap, 0, bypass = 0) is Gate.Closed)
    }

    @Test fun neverReachedTheRelay() {
        assertEquals(ClosedReason.Checking, (gate(null, 0) as Gate.Closed).reason)
        assertEquals(ClosedReason.RelayUnreachable, (gate(null, 0, failed = true) as Gate.Closed).reason)
    }

    @Test fun notConfiguredIsItsOwnReason() {
        assertEquals(ClosedReason.NoSetup, (gate(null, 0, configured = false) as Gate.Closed).reason)
    }

    @Test fun phoneClockIsNeverConsulted() {
        // Same snapshot, same elapsed time: the answer cannot depend on anything else.
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_090_000), receivedElapsed = 100)
        assertEquals(gate(snap, 50_100), gate(snap, 50_100))
    }

    @Test fun pushPayloadParses() {
        val snap = RelaySnapshot.fromPush(
            mapOf("working" to "true", "reason" to "claude_busy:2 sessions", "since" to "900000", "leaseUntil" to "1090000", "sessions" to "2", "serverTime" to "1000000"),
            receivedElapsed = 7,
        )!!
        assertEquals(90_000L, snap.leaseRemainingMs)
        assertEquals(100_000L, snap.sinceAgoMs)
        assertEquals(2, snap.sessions)
    }

    @Test fun pushWithEmptyTimesMeansNull() {
        val snap = RelaySnapshot.fromPush(mapOf("working" to "false", "since" to "", "leaseUntil" to "", "serverTime" to "5", "reason" to "locked:idle"), 0)!!
        assertNull(snap.sinceAgoMs)
        assertNull(snap.leaseRemainingMs)
    }

    @Test fun malformedPushIsRejected() {
        assertNull(RelaySnapshot.fromPush(mapOf("working" to "true"), 0))
        assertNull(RelaySnapshot.fromPush(mapOf("serverTime" to "5"), 0))
    }

    @Test fun waitingOnYouLocksEvenWhileSessionsWorkAndOverridesABypass() {
        val snap = RelaySnapshot.fromStatus(status(false, reason = "locked:needs_you:1 waiting"), receivedElapsed = 0)
        assertEquals(ClosedReason.NeedsYou, (gate(snap, 1_000) as Gate.Closed).reason)
        assertEquals(ClosedReason.NeedsYou, (gate(snap, 1_000, bypass = 60_000) as Gate.Closed).reason)
    }

    @Test fun theClaudeAppWaitingOnYouLocksWithNoRelayAnswerAtAll() {
        assertEquals(ClosedReason.NeedsYou, (gate(null, 0, local = true) as Gate.Closed).reason)
        val working = RelaySnapshot.fromStatus(status(true, lease = 1_060_000), receivedElapsed = 0)
        assertEquals(ClosedReason.NeedsYou, (gate(working, 1_000, local = true) as Gate.Closed).reason)
        assertTrue(gate(working, 1_000) is Gate.Open)
    }

    private fun unconfirmed(s: RelaySnapshot?, now: Long, failed: Boolean = false, configured: Boolean = true) =
        GateLogic.unconfirmed(gate(s, now, failed = failed, configured = configured), s, now)

    @Test fun anExpiredLeaseIsAskedAboutBeforeALockIsDrawn() {
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_010_000), receivedElapsed = 0)
        assertTrue(unconfirmed(snap, now = 60_000))
    }

    @Test fun nothingHeardYetIsAskedAbout() {
        assertTrue(unconfirmed(null, now = 1_000))
    }

    @Test fun anOldLockedAnswerIsAskedAboutButAFreshOneIsNot() {
        val snap = RelaySnapshot.fromStatus(status(false, since = 970_000, reason = "locked:idle"), receivedElapsed = 0)
        assertTrue(unconfirmed(snap, now = GateLogic.FRESH_MS + 1))
        assertEquals(false, unconfirmed(snap, now = GateLogic.FRESH_MS - 1))
    }

    @Test fun anUnlockedGateNeverNeedsAsking() {
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_060_000), receivedElapsed = 0)
        assertEquals(false, unconfirmed(snap, now = 20_000))
    }

    @Test fun locksThatNeedNoAskingAreDrawnAtOnce() {
        val snap = RelaySnapshot.fromStatus(status(true, lease = 1_010_000), receivedElapsed = 0)
        assertEquals(false, unconfirmed(snap, now = 60_000, failed = true)) // relay unreachable
        assertEquals(false, unconfirmed(null, now = 1_000, configured = false)) // not set up
        assertEquals(false, GateLogic.unconfirmed(gate(null, 1_000, local = true), null, 1_000)) // something waits on you
    }
}
