package com.neilluo.scrolllock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.neilluo.scrolllock.R
import kotlin.math.abs

/**
 * One ongoing notification that says where you are while a blocked app is on screen:
 * "In Instagram · Claude is working" with a countdown to the lock, "Instagram locked", or the bypass
 * countdown. On Android 16 it asks to be a promoted ongoing notification (a "live update"), which
 * phones show as a status-bar chip or island. Other phones show an ordinary ongoing notification.
 * The countdown is the system's own chronometer, so nothing here ticks.
 */
class StatusNotifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var shown = false
    private var lastTitle = ""
    private var lastText = ""
    private var lastEnds = 0L

    init {
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Where you are", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows which blocked app is open and when it locks"
                setShowBadge(false)
            },
        )
    }

    /** [endsAtWall] is when the countdown ends, in wall-clock ms, or null for no countdown. */
    fun show(title: String, text: String, endsAtWall: Long?, chip: String? = null) {
        val ends = endsAtWall ?: 0L
        // Re-post only when something visible changed. The countdown is steady, so a small drift isn't one.
        if (shown && title == lastTitle && text == lastText && abs(ends - lastEnds) < 1_500) return
        shown = true; lastTitle = title; lastText = text; lastEnds = ends
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        if (endsAtWall != null) {
            b.setWhen(endsAtWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (chip != null) b.setShortCriticalText(chip)
        runCatching { nm?.notify(ID, b.build()) }
    }

    fun clear() {
        if (!shown) return
        shown = false
        runCatching { nm?.cancel(ID) }
    }

    private companion object {
        const val CHANNEL = "where"
        const val ID = 4242
    }
}
