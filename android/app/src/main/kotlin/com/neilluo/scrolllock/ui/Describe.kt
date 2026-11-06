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

    fun detail(g: Gate): String = when (g) {
        is Gate.Open -> buildString {
            append(if (g.sessions == 1) "Claude is working" else "${g.sessions} Claude sessions are working")
        }
        is Gate.Bypass -> "Bypass is on. One a day."
        is Gate.Closed -> when (g.reason) {
            ClosedReason.NoSetup -> "Finish setup to connect to the relay."
            ClosedReason.Checking -> "Asking the relay if Claude is working."
            ClosedReason.RelayUnreachable -> "Can't reach the relay, so it stays locked."
            ClosedReason.LeaseExpired -> "Claude isn't responding."
            ClosedReason.PromptWindowExpired -> "Claude isn't responding."
            ClosedReason.NoEvents -> "No Claude activity seen yet."
            ClosedReason.Idle -> "Claude isn't working."
        }
    }
}
