package com.neilluo.scrolllock

import android.content.Context
import com.neilluo.scrolllock.data.RelayApi
import com.neilluo.scrolllock.data.SettingsStore
import com.neilluo.scrolllock.data.StateRepository
import com.neilluo.scrolllock.data.StatsStore
import com.neilluo.scrolllock.lockdown.Lockdown
import com.neilluo.scrolllock.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired singletons. A graph this small does not need a DI framework. */
object AppGraph {
    private lateinit var app: Context

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
    }

    val settings: SettingsStore by lazy { SettingsStore(app) }
    val stats: StatsStore by lazy { StatsStore(app) }
    val relay: RelayApi by lazy { RelayApi() }
    val fcm: PushRegistrar by lazy { PushRegistrar(app, settings, relay) }
    val state: StateRepository by lazy { StateRepository(app, settings, stats, relay, scope) }
    val lockdown: Lockdown by lazy { Lockdown(app, settings, state) }

    /**
     * Capture tool: for a minute after it's armed, the Claude app's notifications are noted here (channel,
     * category, title, text) so the wording the classifier matches can be checked against real ones.
     * In memory only, and copied out only when the user taps Copy.
     */
    @Volatile var sampleUntil: Long = 0L
    val samples = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun recordSample(channel: String?, category: String?, title: String?, text: String?, ongoing: Boolean) {
        if (android.os.SystemClock.elapsedRealtime() > sampleUntil) return
        samples += "channel=$channel category=$category ongoing=$ongoing title=${title ?: ""} text=${text ?: ""}"
    }

    /** Elapsed-realtime moment at which the service should dump the next Instagram screen, or 0. */
    @Volatile var captureAt: Long = 0L
}
