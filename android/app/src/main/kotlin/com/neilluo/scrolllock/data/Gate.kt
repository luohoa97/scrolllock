package com.neilluo.scrolllock.data

enum class ClosedReason { Idle, LeaseExpired, RelayUnreachable, PromptWindowExpired, NoEvents, NoSetup }

/** The one answer the rest of the app needs: is Instagram open right now, and why not. */
sealed interface Gate {
    data class Open(val reason: String, val sessions: Int, val remainingMs: Long) : Gate
    data class Closed(val reason: ClosedReason, val elapsedMs: Long?) : Gate
    data class Bypass(val remainingMs: Long) : Gate

    val isOpen: Boolean get() = this !is Closed
}

object GateLogic {
    /** Used when an unlocked status arrives without a lease, which the relay never does. */
    const val FALLBACK_LEASE_MS = 90_000L

    fun compute(
        configured: Boolean,
        snapshot: RelaySnapshot?,
        lastFetchFailed: Boolean,
        nowElapsed: Long,
        bypassRemainingMs: Long,
    ): Gate {
        if (bypassRemainingMs > 0) return Gate.Bypass(bypassRemainingMs)
        if (!configured) return Gate.Closed(ClosedReason.NoSetup, null)
        if (snapshot == null) return Gate.Closed(ClosedReason.RelayUnreachable, null)

        val sinceReceipt = (nowElapsed - snapshot.receivedElapsed).coerceAtLeast(0)
        if (snapshot.working) {
            val lease = snapshot.leaseRemainingMs ?: FALLBACK_LEASE_MS
            val left = lease - sinceReceipt
            if (left > 0) return Gate.Open(snapshot.reason, snapshot.sessions, left)
            // Lease ran out with no newer push or fetch. If the last fetch also failed, say so.
            val why = if (lastFetchFailed) ClosedReason.RelayUnreachable else ClosedReason.LeaseExpired
            return Gate.Closed(why, -left)
        }

        val why = when {
            snapshot.reason.contains("prompt_window_expired") -> ClosedReason.PromptWindowExpired
            snapshot.reason.contains("no_events") -> ClosedReason.NoEvents
            else -> ClosedReason.Idle
        }
        return Gate.Closed(why, (snapshot.sinceAgoMs ?: 0) + sinceReceipt)
    }
}
