package com.neilluo.scrolllock.lockdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neilluo.scrolllock.AppGraph
import kotlinx.coroutines.launch

/** After a restart, make sure the relay still knows where to push. Everything else restarts itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppGraph.init(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try { AppGraph.fcm.ensureRegistered() } finally { pending.finish() }
        }
    }
}
