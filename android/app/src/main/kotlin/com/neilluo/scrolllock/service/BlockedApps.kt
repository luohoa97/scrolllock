package com.neilluo.scrolllock.service

/**
 * Apps that are blocked whole while Claude isn't working: every app with reels or short video.
 * Package -> name for the lock screen. Discord is left out on purpose (work tool).
 * Check the packages against the phone:
 * adb shell pm list packages | grep -E 'instagram|youtube|musically|trill|snapchat|facebook'
 */
object BlockedApps {
    val packages = mapOf(
        "com.instagram.android" to "Instagram",
        "com.instagram.lite" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.facebook.lite" to "Facebook",
    )

    /** One entry per app, for display. */
    val names: List<String> = packages.values.distinct()
}
