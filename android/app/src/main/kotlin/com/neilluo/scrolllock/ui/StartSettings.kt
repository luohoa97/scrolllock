package com.neilluo.scrolllock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens a system settings screen. Phone makers rename or remove some of them (Live Updates, autostart lists),
 * and starting a screen that isn't there throws and takes the app down. Falls back to this app's own system
 * page, and gives up quietly if even that can't open.
 */
fun Context.startSettings(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    } catch (_: Exception) {
        if (fallback != null) {
            try {
                startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}
