package com.neilluo.scrolllock.data

import java.time.Instant
import java.time.ZoneId

/** An active or past bypass. [untilElapsed] is on SystemClock.elapsedRealtime, valid only for [bootCount]. */
data class BypassState(
    val bootCount: Int,
    val untilElapsed: Long,
    val lastUsedDay: String?,
)

object BypassPolicy {
    // The bypass is armed by a setting, and the setting can only be switched ON while Claude is
    // working (StateRepository.setBypassEnabled). So it can't be armed on impulse while locked.

    const val DURATION_MS = 5 * 60_000L

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** One bypass per calendar day. */
    fun canUse(state: BypassState?, today: String): Boolean = state?.lastUsedDay != today

    fun start(bootCount: Int, nowElapsed: Long, today: String) =
        BypassState(bootCount, nowElapsed + DURATION_MS, today)

    /** After a reboot elapsedRealtime restarts, so an old bypass is simply over. */
    fun remainingMs(state: BypassState?, bootCount: Int, nowElapsed: Long): Long {
        if (state == null || state.bootCount != bootCount) return 0
        return (state.untilElapsed - nowElapsed).coerceAtLeast(0)
    }
}
