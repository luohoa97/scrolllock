package com.neilluo.scrolllock.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The phone's view of the relay. Push updates arrive through [applyPush]; [refresh] is the fallback
 * used when Instagram comes to the front and as a slow safety net while it stays there.
 */
class StateRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val stats: StatsStore,
    private val api: RelayClient,
    scope: CoroutineScope,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val bootCount: () -> Int = { readBootCount(context) },
) {
    private val snapshot = MutableStateFlow<RelaySnapshot?>(null)
    private val fetchFailed = MutableStateFlow(false)

    /** For things that react to new information without ticking, like the always-on notification. */
    val snapshots: StateFlow<RelaySnapshot?> = snapshot
    val failed: StateFlow<Boolean> = fetchFailed

    /** Things the Claude app's notifications say are waiting on you. Set by the notification listener, in memory only. */
    private val localWaiting = MutableStateFlow<List<Waiter>>(emptyList())
    fun setLocalWaiting(list: List<Waiter>) { localWaiting.value = list }
    val localWaitingFlow: StateFlow<List<Waiter>> = localWaiting

    /** Everyone waiting on you: sessions the relay knows about, and the Claude app's own notifications. */
    fun waitersNow(showText: Boolean): List<Waiter> {
        val snap = snapshot.value
        val fromRelay = if (snap?.reason?.contains("needs_you") == true) snap.waiting.map { Waiter.fromRelay(it, showText) } else emptyList()
        return fromRelay + localWaiting.value
    }

    /** Agents working as of the last answer. Pushes carry no task data, so a push keeps the list we had while still working. */
    val agents: Flow<List<AgentStatus>> = snapshot.map { it?.agents.orEmpty() }
    fun agentsNow(): List<AgentStatus> = snapshot.value?.agents.orEmpty()

    /** Counts once a second while someone collects; stops when nobody is looking. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(elapsed())
            delay(1000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val gate: StateFlow<Gate> = combine(settings.flow, snapshot, fetchFailed, ticker, localWaiting) { s, snap, failed, now, local ->
        val bypassLeft = BypassPolicy.remainingMs(s.bypass, bootCount(), now)
        GateLogic.compute(s.configured, snap, failed, now, bypassLeft, local.isNotEmpty())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Gate.Closed(ClosedReason.NoSetup, null))

    /** Milliseconds since the last successful push or fetch, or null if there has been none. */
    fun ageMs(): Long? = snapshot.value?.let { elapsed() - it.receivedElapsed }

    fun gateNow(s: Settings): Gate {
        val now = elapsed()
        val bypassLeft = BypassPolicy.remainingMs(s.bypass, bootCount(), now)
        return GateLogic.compute(s.configured, snapshot.value, fetchFailed.value, now, bypassLeft, localWaiting.value.isNotEmpty())
    }

    /** True when the gate is closed on an answer too old to trust, so the first look should wait for a fresh one. */
    fun unconfirmedNow(s: Settings): Boolean = GateLogic.unconfirmed(gateNow(s), snapshot.value, elapsed())

    suspend fun refresh(): Result<RelayStatus> {
        val s = settings.current()
        if (!s.configured) return Result.failure(IllegalStateException("Relay is not set up"))
        val r = api.status(s)
        r.onSuccess {
            snapshot.value = RelaySnapshot.fromStatus(it, elapsed())
            fetchFailed.value = false
        }.onFailure { fetchFailed.value = true }
        return r
    }

    fun applyPush(data: Map<String, String>): Boolean {
        val snap = RelaySnapshot.fromPush(data, elapsed()) ?: return false
        val prev = snapshot.value
        snapshot.value = when {
            snap.working && prev?.working == true -> snap.copy(agents = prev.agents)
            // A push says someone is waiting but not who; keep the names we had if they were waiting before.
            snap.reason.contains("needs_you") && prev?.reason?.contains("needs_you") == true -> snap.copy(waiting = prev.waiting)
            else -> snap
        }
        fetchFailed.value = false
        return true
    }

    /** One bypass per calendar day, where "day" comes from the relay's clock when we have it. */
    fun canBypassNow(s: Settings): Boolean = s.bypassEnabled && BypassPolicy.canUse(s.bypass, today())

    /**
     * Turning the bypass off is always allowed. Turning it on needs Claude to be working right now,
     * so the one tap that arms it can't happen while Instagram is locked.
     */
    suspend fun setBypassEnabled(on: Boolean): Boolean {
        if (on && gateNow(settings.current()) !is Gate.Open) return false
        settings.setBypassEnabled(on)
        return true
    }

    suspend fun startBypass(): Boolean {
        val s = settings.current()
        val today = today()
        if (!canBypassNow(s)) return false
        settings.setBypass(BypassPolicy.start(bootCount(), elapsed(), today))
        stats.addBypass(today)
        return true
    }

    private fun today(): String {
        val snap = snapshot.value
        val nowMs = if (snap != null) snap.serverTimeAtReceipt + (elapsed() - snap.receivedElapsed) else System.currentTimeMillis()
        return BypassPolicy.dayKey(nowMs)
    }

    companion object {
        fun readBootCount(context: Context): Int =
            AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT, 0)
    }
}
