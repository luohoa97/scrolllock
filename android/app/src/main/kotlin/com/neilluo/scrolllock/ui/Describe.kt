package com.neilluo.scrolllock.ui

import com.neilluo.scrolllock.data.ClosedReason
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.TimeFormat

/** Words for the status screen. The overlay has its own, shorter wording. */
object Describe {
    fun headline(g: Gate) = when (g) {
        is Gate.Open -> "Apps are unlocked"
        is Gate.Bypass -> "Bypass is on"
        is Gate.Closed -> if (g.reason == ClosedReason.Checking) "Checking Claude…" else "Apps are locked"
    }

    /**
     * The line under the headline. While unlocked and the relay has told us who is working, the list below names
     * them, so this stays quiet instead of saying the same thing twice. Without that list it says what it knows.
     */
    fun detail(g: Gate, agentsListed: Boolean = false): String = when (g) {
        is Gate.Open -> when {
            agentsListed -> ""
            g.sessions == 1 -> "Claude is working"
            else -> "${g.sessions} sessions are working"
        }
        is Gate.Bypass -> "Bypass is on. One a day."
        is Gate.Closed -> when (g.reason) {
            ClosedReason.NoSetup -> "Finish setup to connect to the relay."
            ClosedReason.Checking -> "Asking the relay if Claude is working."
            ClosedReason.RelayUnreachable -> "Can't reach the relay, so it stays locked."
            ClosedReason.LeaseExpired -> "Claude isn't responding."
            ClosedReason.PromptWindowExpired -> "Claude isn't responding."
            ClosedReason.NoEvents -> "No Claude activity seen yet."
            ClosedReason.NeedsYou -> "Something is waiting on you."
            ClosedReason.Idle -> "Claude isn't working."
        }
    }
}
