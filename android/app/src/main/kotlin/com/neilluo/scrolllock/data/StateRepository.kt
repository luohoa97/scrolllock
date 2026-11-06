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

    /** Counts once a second while someone collects; stops when nobody is looking. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(elapsed())
            delay(1000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val gate: StateFlow<Gate> = combine(settings.flow, snapshot, fetchFailed, ticker) { s, snap, failed, now ->
        val bypassLeft = BypassPolicy.remainingMs(s.bypass, bootCount(), now)
        GateLogic.compute(s.configured, snap, failed, now, bypassLeft)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Gate.Closed(ClosedReason.NoSetup, null))

    /** Milliseconds since the last successful push or fetch, or null if there has been none. */
    fun ageMs(): Long? = snapshot.value?.let { elapsed() - it.receivedElapsed }

    fun gateNow(s: Settings): Gate {
        val now = elapsed()
        val bypassLeft = BypassPolicy.remainingMs(s.bypass, bootCount(), now)
        return GateLogic.compute(s.configured, snapshot.value, fetchFailed.value, now, bypassLeft)
    }

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
        snapshot.value = snap
        fetchFailed.value = false
        return true
    }

    /** One bypass per calendar day, where "day" comes from the relay's clock when we have it. */
    suspend fun canBypass(): Boolean {
        val s = settings.current()
        return BypassPolicy.canUse(s.bypass, today())
    }

    suspend fun startBypass(): Boolean {
        val s = settings.current()
        val today = today()
        if (!BypassPolicy.canUse(s.bypass, today)) return false
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
