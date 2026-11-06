package com.neilluo.scrolllock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.neilluo.scrolllock.R

/**
 * A quiet, ongoing notification in the shade saying which blocked app you're in. The visible
 * "island" is StatusIsland; this is the minimum-importance companion, so it never pops up over the
 * app, and it has no countdown, so it never needs updating on a clock.
 */
class StatusNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var shown = false
    private var lastTitle = ""
    private var lastText = ""

    init {
        nm?.deleteNotificationChannel("where") // the earlier, louder channel
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Where you are", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows which blocked app is open"
                setShowBadge(false)
            },
        )
    }

    fun show(title: String, text: String) {
        if (shown && title == lastTitle && text == lastText) return
        shown = true; lastTitle = title; lastText = text
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        runCatching { nm?.notify(ID, b.build()) }
    }

    fun clear() {
        if (!shown) return
        shown = false
        runCatching { nm?.cancel(ID) }
    }

    private companion object {
        const val CHANNEL = "where2"
        const val ID = 4242
    }
}
