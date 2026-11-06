package com.neilluo.scrolllock.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.neilluo.scrolllock.AppGraph
import com.neilluo.scrolllock.data.NotificationClassifier
import com.neilluo.scrolllock.data.Waiter

/**
 * Watches the Claude app's own notifications on this phone, so "waiting on you" from any project, thread or
 * account you're signed into locks the blocked apps too. It looks at one package only, keeps the result in
 * memory, and sends nothing anywhere. Needs notification access, which the user grants in system settings.
 */
class ClaudeAppWatcher : NotificationListenerService() {
    private val waiting = LinkedHashMap<String, Waiter>()

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun onListenerConnected() {
        PermissionAlerts.listenerGranted(this)
        waiting.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach { consider(it) }
        publish()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (consider(sbn)) publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != CLAUDE) return
        if (waiting.remove(sbn.key) != null) publish()
    }

    override fun onListenerDisconnected() {
        waiting.clear()
        publish()
        // A disconnect is either the user revoking access or the system rebinding. Only the first is worth an alert.
        val still = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (still) runCatching { requestRebind(android.content.ComponentName(this, ClaudeAppWatcher::class.java)) }
        else if (PermissionAlerts.listenerWanted(this)) PermissionAlerts.listenerRevoked(this)
    }

    /** True when the set of waiting items may have changed. */
    private fun consider(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != CLAUDE) return false
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        AppGraph.recordSample(n.channelId, n.category, title, text, sbn.isOngoing)
        val needs = NotificationClassifier.needsYou(title, text, sbn.isOngoing)
        return if (needs) {
            // The title is shown on the phone only (never the lock screen), and only the first words of it.
            waiting[sbn.key] = Waiter("Claude app", title?.take(40)?.takeIf { it.isNotBlank() })
            true
        } else {
            waiting.remove(sbn.key) != null
        }
    }

    private fun publish() = AppGraph.state.setLocalWaiting(waiting.values.toList())

    companion object {
        const val CLAUDE = "com.anthropic.claude"
    }
}
