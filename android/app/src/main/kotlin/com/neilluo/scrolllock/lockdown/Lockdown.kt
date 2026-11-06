package com.neilluo.scrolllock.lockdown

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.neilluo.scrolllock.BuildConfig
import com.neilluo.scrolllock.R
import com.neilluo.scrolllock.data.CoolingOff
import com.neilluo.scrolllock.data.SettingsStore
import com.neilluo.scrolllock.data.StateRepository
import com.neilluo.scrolllock.data.UnlockRequest

/**
 * Lockdown is a commitment device, not security: a determined person with a PC and adb can always
 * remove the app. What it does is make the quick impulse path (long-press, Uninstall, done) take
 * a 24 hour detour. It never activates in debug builds so development stays sane.
 */
class Lockdown(
    private val context: Context,
    private val settings: SettingsStore,
    private val state: StateRepository,
) {
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    private val admin get() = ComponentName(context, AdminReceiver::class.java)

    val allowed: Boolean get() = !BuildConfig.DEBUG

    fun isAdminActive(): Boolean = dpm.isAdminActive(admin)

    fun addAdminIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_description))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun batteryIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun enable() {
        if (!allowed) return
        settings.setLockdown(true)
    }

    suspend fun requestUnlock() {
        val s = settings.current()
        if (!s.lockdown || s.unlockRequest != null) return
        settings.setUnlockRequest(UnlockRequest(StateRepository.readBootCount(context), SystemClock.elapsedRealtime()))
    }

    suspend fun cancelUnlock() = settings.setUnlockRequest(null)

    /** Milliseconds of waiting left, or null when no request is open. */
    suspend fun unlockRemainingMs(): Long? {
        val s = settings.current()
        val req = s.unlockRequest ?: return null
        val period = s.coolingOffHours * 3_600_000L
        return CoolingOff.remainingMs(req, StateRepository.readBootCount(context), SystemClock.elapsedRealtime(), period)
    }

    /** Turns Lockdown off, and drops the device admin so the app can be uninstalled. */
    suspend fun confirmUnlock(): Boolean {
        if (unlockRemainingMs() != 0L) return false
        settings.setLockdown(false)
        settings.setUnlockRequest(null)
        if (isAdminActive()) dpm.removeActiveAdmin(admin)
        return true
    }
}
