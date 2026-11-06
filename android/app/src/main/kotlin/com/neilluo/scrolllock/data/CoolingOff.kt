package com.neilluo.scrolllock.data

/** A pending request to turn Lockdown off. */
data class UnlockRequest(val bootCount: Int, val requestedAtElapsed: Long)

/**
 * The 24 hour wait before Lockdown can be switched off. It runs on elapsedRealtime so changing the
 * clock does nothing. A reboot restarts elapsedRealtime, and wall-clock time cannot be trusted, so a
 * reboot restarts the wait instead of shortening it.
 */
object CoolingOff {
    const val DEFAULT_PERIOD_MS = 24 * 60 * 60_000L

    /** Re-anchors a request that was made in an earlier boot to now, so the wait begins again. */
    fun normalize(req: UnlockRequest, bootCount: Int, nowElapsed: Long): UnlockRequest =
        if (req.bootCount == bootCount) req else UnlockRequest(bootCount, nowElapsed)

    fun remainingMs(req: UnlockRequest, bootCount: Int, nowElapsed: Long, periodMs: Long = DEFAULT_PERIOD_MS): Long {
        val r = normalize(req, bootCount, nowElapsed)
        return (periodMs - (nowElapsed - r.requestedAtElapsed)).coerceIn(0, periodMs)
    }

    fun canConfirm(req: UnlockRequest, bootCount: Int, nowElapsed: Long, periodMs: Long = DEFAULT_PERIOD_MS) =
        remainingMs(req, bootCount, nowElapsed, periodMs) == 0L
}
