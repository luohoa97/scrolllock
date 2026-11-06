package com.neilluo.scrolllock.data

/** [Checking] is "nothing heard yet": a fresh process before its first answer, not a verdict about Claude. */
enum class ClosedReason { Idle, LeaseExpired, RelayUnreachable, PromptWindowExpired, NoEvents, NoSetup, Checking, NeedsYou }

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

    /** An answer older than this is a memory, not news: the relay's lease is about this long. */
    const val FRESH_MS = 10_000L

    /**
     * A closed gate whose answer is old (or missing) and could well be wrong by now, so it is worth asking the relay
     * before drawing a lock screen that the answer would then take straight down. False for locks that need no
     * asking: nothing set up, the relay unreachable, or something waiting on you.
     */
    fun unconfirmed(gate: Gate, snapshot: RelaySnapshot?, nowElapsed: Long): Boolean {
        if (gate !is Gate.Closed) return false
        return when (gate.reason) {
            ClosedReason.NoSetup, ClosedReason.RelayUnreachable, ClosedReason.NeedsYou -> false
            ClosedReason.Checking, ClosedReason.LeaseExpired -> true
            else -> snapshot == null || nowElapsed - snapshot.receivedElapsed > FRESH_MS
        }
    }

    fun compute(
        configured: Boolean,
        snapshot: RelaySnapshot?,
        lastFetchFailed: Boolean,
        nowElapsed: Long,
        bypassRemainingMs: Long,
        /** The Claude app has a notification that needs an answer from you. Phone-side, no relay involved. */
        localWaiting: Boolean = false,
    ): Gate {
        // Something is waiting on you: locked, whatever else is working, and a bypass doesn't override it.
        if (localWaiting || snapshot?.reason?.contains("needs_you") == true) {
            return Gate.Closed(ClosedReason.NeedsYou, snapshot?.let { (it.sinceAgoMs ?: 0) + (nowElapsed - it.receivedElapsed).coerceAtLeast(0) })
        }
        if (bypassRemainingMs > 0) return Gate.Bypass(bypassRemainingMs)
        if (!configured) return Gate.Closed(ClosedReason.NoSetup, null)
        if (snapshot == null) return Gate.Closed(if (lastFetchFailed) ClosedReason.RelayUnreachable else ClosedReason.Checking, null)

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
