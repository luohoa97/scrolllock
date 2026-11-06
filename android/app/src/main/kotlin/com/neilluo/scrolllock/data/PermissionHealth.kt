package com.neilluo.scrolllock.data

/**
 * What ScrollLock needs switched on, and what's missing right now. The accessibility service is the loud
 * one: with it off nothing is blocked. Pure, so the ordering and wording are tested.
 */
object PermissionHealth {
    enum class Need { Accessibility, Notifications, NotificationAccess, Battery }

    data class Gap(val need: Need, val title: String, val detail: String, val action: String)

    /**
     * [notificationAccessWanted]: the user had granted notification access before (so losing it is a revoke,
     * not something they never set up).
     */
    fun gaps(
        accessibilityOn: Boolean,
        notificationsOn: Boolean,
        batteryExempt: Boolean,
        notificationAccessOn: Boolean,
        notificationAccessWanted: Boolean,
    ): List<Gap> = buildList {
        if (!accessibilityOn) add(
            Gap(Need.Accessibility, "ScrollLock is off", "Your blocked apps are NOT blocked right now. Turn the accessibility service back on.", "Turn on"),
        )
        if (!notificationsOn) add(
            Gap(Need.Notifications, "Notifications are off", "The status notification and alerts can't show.", "Open settings"),
        )
        if (notificationAccessWanted && !notificationAccessOn) add(
            Gap(Need.NotificationAccess, "Claude app access was turned off", "\"Waiting on you\" can't see the Claude app's notifications.", "Turn on"),
        )
        if (!batteryExempt) add(
            Gap(Need.Battery, "Battery limits can stop ScrollLock", "Some phones kill background apps. Set ScrollLock to Unrestricted.", "Open settings"),
        )
    }
}
