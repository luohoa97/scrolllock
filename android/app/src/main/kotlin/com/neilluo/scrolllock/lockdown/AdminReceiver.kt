package com.neilluo.scrolllock.lockdown

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.neilluo.scrolllock.R

/**
 * Being an active device admin makes the system grey out Uninstall for the app, so removing it
 * takes the deactivation step first, which the Settings guard blocks until the cooling-off ends.
 * It asks for no policies: the only reason it exists is the uninstall friction.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
