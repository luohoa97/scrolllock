package com.neilluo.scrolllock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.neilluo.scrolllock.MainActivity
import com.neilluo.scrolllock.R

/**
 * The always-on notification: in the shade and on the lock screen, whether or not a blocked app is open.
 * It says whether apps are unlocked and which agents are working. It is ongoing and no-clear, and if the
 * system lets it be swiped away anyway (Android 14 and up allow that), it is posted again at once.
 * The lock screen shows a public version with counts only, never task text. It has no clock, so it
 * is only re-posted when its words change.
 */
class StatusNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var last: Content? = null

    private data class Content(val title: String, val text: String, val big: String, val publicText: String)

    init {
        // Earlier builds used other channels. Importance is fixed per channel, so changing it needs a new id.
        listOf("where", "where2").forEach { nm?.deleteNotificationChannel(it) }
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "ScrollLock status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Whether your apps are unlocked and which agents are working"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /** [publicText] is what a locked screen may show. */
    fun show(title: String, text: String, big: String = "", publicText: String = text) {
        val c = Content(title, text, big, publicText)
        if (c == last) return
        last = c
        post(c)
    }

    /** Called when the notification was dismissed: put it straight back. */
    fun repost() {
        post(last ?: return)
    }

    fun clear() {
        last = null
        runCatching { nm?.cancel(ID) }
    }

    private fun post(c: Content) {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismissed = PendingIntent.getBroadcast(
            ctx, 1, Intent(ACTION_DISMISSED).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pub = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_tile).setContentTitle(c.title).setContentText(c.publicText).build()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
        if (c.big.isNotEmpty()) b.setStyle(Notification.BigTextStyle().bigText(c.big))
        val n = b.build().also { it.flags = it.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
        runCatching { nm?.notify(ID, n) }
    }

    /** False when the user has turned this app's notifications off, which we can't undo. */
    fun enabled(): Boolean = nm?.areNotificationsEnabled() != false

    companion object {
        const val ACTION_DISMISSED = "com.neilluo.scrolllock.STATUS_DISMISSED"
        private const val CHANNEL = "where3"
        private const val ID = 4242
    }
}
