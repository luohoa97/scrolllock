package com.neilluo.scrolllock

import android.content.Context
import com.neilluo.scrolllock.data.RelayApi
import com.neilluo.scrolllock.data.SettingsStore
import com.neilluo.scrolllock.data.StateRepository
import com.neilluo.scrolllock.data.StatsStore
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
}
