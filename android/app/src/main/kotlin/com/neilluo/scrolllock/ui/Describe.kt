package com.neilluo.scrolllock.ui

import com.neilluo.scrolllock.data.ClosedReason
import com.neilluo.scrolllock.data.Gate
import com.neilluo.scrolllock.data.TimeFormat

/** Words for the status screen. The overlay has its own, shorter wording. */
object Describe {
    fun headline(g: Gate) = when (g) {
        is Gate.Open -> "Instagram is open"
        is Gate.Bypass -> "Bypass is on"
        is Gate.Closed -> "Instagram is locked"
    }

    fun detail(g: Gate): String = when (g) {
        is Gate.Open -> buildString {
            append(if (g.sessions == 1) "Claude is working" else "${g.sessions} Claude sessions are working")
            append(" · ${TimeFormat.elapsed(g.remainingMs)} left on the lease")
        }
        is Gate.Bypass -> "${TimeFormat.elapsed(g.remainingMs)} left. One bypass a day."
        is Gate.Closed -> when (g.reason) {
            ClosedReason.NoSetup -> "Finish setup to connect to the relay."
            ClosedReason.RelayUnreachable -> "Can't reach the relay, so it stays locked."
            ClosedReason.LeaseExpired -> "Claude went quiet" + (g.elapsedMs?.let { " ${TimeFormat.elapsed(it)} ago." } ?: ".")
            ClosedReason.PromptWindowExpired -> "Your turn ended" + (g.elapsedMs?.let { " ${TimeFormat.elapsed(it)} ago." } ?: ".")
            ClosedReason.NoEvents -> "No Claude activity seen yet."
            ClosedReason.Idle -> "Claude isn't working" + (g.elapsedMs?.let { " · ${TimeFormat.elapsed(it)}" } ?: "")
        }
    }
}
