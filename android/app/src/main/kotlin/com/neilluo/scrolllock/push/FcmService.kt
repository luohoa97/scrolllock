package com.neilluo.scrolllock.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.neilluo.scrolllock.AppGraph
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch

/** Receives the relay's data pushes and hands them to the state repository. Nothing else. */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        AppGraph.init(this)
        if (AppGraph.state.applyPush(message.data)) {
            AppGraph.scope.launch { runCatching { com.neilluo.scrolllock.surface.StatusWidget().updateAll(this@FcmService) } }
        }
    }

    override fun onNewToken(token: String) {
        AppGraph.init(this)
        AppGraph.scope.launch { AppGraph.fcm.onNewToken(token) }
    }
}
