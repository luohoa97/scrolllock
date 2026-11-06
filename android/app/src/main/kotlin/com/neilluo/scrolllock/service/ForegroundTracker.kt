package com.neilluo.scrolllock.service

/**
 * Remembers which app is in front, from window-state events only. Only the package name is kept.
 * Packages in [ignored] (system UI, keyboards, our own overlay) never count as the foreground app,
 * so pulling the notification shade or opening the keyboard does not unblock or re-block anything.
 */
class ForegroundTracker(
    private val target: String,
    private val ignored: () -> Set<String>,
) {
    var current: String? = null
        private set

    val targetInFront: Boolean get() = current == target

    /** Returns true when the foreground app changed. */
    fun onWindowState(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty() || pkg in ignored()) return false
        if (pkg == current) return false
        current = pkg
        return true
    }

    fun reset() { current = null }
}
