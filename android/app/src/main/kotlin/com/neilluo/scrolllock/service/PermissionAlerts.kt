package com.neilluo.scrolllock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.neilluo.scrolllock.MainActivity
import com.neilluo.scrolllock.R

/**
 * Raised when a permission the lock depends on is switched off. It opens the exact settings screen, stays
 * until the permission is back, and is posted again each time it's lost. If the accessibility service is
 * off nothing is blocked, so this notification is the only signal there is.
 */
object PermissionAlerts {
    private const val CHANNEL = "alerts"
    private const val ID_ACCESSIBILITY = 4301
    private const val ID_LISTENER = 4302
    private const val PREFS = "perm"
    private const val KEY_LISTENER_WANTED = "listenerWanted"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(ctx: Context) {
        nm(ctx)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "ScrollLock alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When something ScrollLock needs has been switched off"
            },
        )
    }

    private fun post(ctx: Context, id: Int, title: String, text: String, target: Intent) {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(ctx, id, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        runCatching { nm(ctx)?.notify(id, n) }
    }

    fun accessibilityOff(ctx: Context) = post(
        ctx, ID_ACCESSIBILITY, "ScrollLock is off",
        "Instagram and the other blocked apps are NOT blocked right now. Tap to turn the accessibility service back on.",
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun accessibilityBack(ctx: Context) { runCatching { nm(ctx)?.cancel(ID_ACCESSIBILITY) } }

    /** Notification access was granted at some point; remember it so losing it later is a revoke worth saying. */
    fun listenerGranted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LISTENER_WANTED, true).apply()
        runCatching { nm(ctx)?.cancel(ID_LISTENER) }
    }

    fun listenerWanted(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LISTENER_WANTED, false)

    fun listenerRevoked(ctx: Context) = post(
        ctx, ID_LISTENER, "Claude app access was turned off",
        "\"Waiting on you\" can't see the Claude app's notifications any more. Tap to turn it back on.",
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    fun appIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)
}
