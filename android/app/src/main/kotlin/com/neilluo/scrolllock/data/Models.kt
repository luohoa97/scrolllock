package com.neilluo.scrolllock.data

import kotlinx.serialization.Serializable

/** Mirrors the relay's /api/status response. Times are epoch milliseconds on the server's clock. */
@Serializable
data class RelayStatus(
    val working: Boolean,
    val activeSessions: Int = 0,
    val unlockReason: String = "",
    val since: Long? = null,
    val leaseUntil: Long? = null,
    val lastEventAt: Long? = null,
    val relayStale: Boolean = false,
    val serverTime: Long,
)

enum class UnlockMode(val wire: String) {
    ClaudeBusy("claude_busy"),
    MyTurn("my_turn");

    companion object {
        fun fromWire(s: String?): UnlockMode = entries.firstOrNull { it.wire == s } ?: ClaudeBusy
    }
}

/**
 * What the phone knows about the relay, expressed in durations measured at [receivedElapsed]
 * (SystemClock.elapsedRealtime). Nothing here depends on the phone's wall clock, so changing the
 * date or time cannot extend a lease or shorten a lock.
 */
data class RelaySnapshot(
    val working: Boolean,
    val reason: String,
    val sessions: Int,
    /** How long the current locked or unlocked period had lasted when this was received. */
    val sinceAgoMs: Long?,
    /** While unlocked: time left on the lease when this was received. */
    val leaseRemainingMs: Long?,
    val relayStale: Boolean,
    val receivedElapsed: Long,
    /** Server wall-clock time at receipt, used only to pick the calendar day for the bypass limit. */
    val serverTimeAtReceipt: Long,
) {
    companion object {
        fun fromStatus(s: RelayStatus, receivedElapsed: Long) = RelaySnapshot(
            working = s.working,
            reason = s.unlockReason,
            sessions = s.activeSessions,
            sinceAgoMs = s.since?.let { (s.serverTime - it).coerceAtLeast(0) },
            leaseRemainingMs = s.leaseUntil?.let { (it - s.serverTime).coerceAtLeast(0) },
            relayStale = s.relayStale,
            receivedElapsed = receivedElapsed,
            serverTimeAtReceipt = s.serverTime,
        )

        /** FCM data payload: strings only, empty string for null. Returns null if it is malformed. */
        fun fromPush(data: Map<String, String>, receivedElapsed: Long): RelaySnapshot? {
            val serverTime = data["serverTime"]?.toLongOrNull() ?: return null
            val working = data["working"]?.let { it == "true" } ?: return null
            val since = data["since"]?.toLongOrNull()
            val lease = data["leaseUntil"]?.toLongOrNull()
            return RelaySnapshot(
                working = working,
                reason = data["reason"].orEmpty(),
                sessions = data["sessions"]?.toIntOrNull() ?: 0,
                sinceAgoMs = since?.let { (serverTime - it).coerceAtLeast(0) },
                leaseRemainingMs = lease?.let { (it - serverTime).coerceAtLeast(0) },
                relayStale = false,
                receivedElapsed = receivedElapsed,
                serverTimeAtReceipt = serverTime,
            )
        }
    }
}
