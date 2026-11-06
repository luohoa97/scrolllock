package com.neilluo.scrolllock.data

/**
 * Many phone makers kill background apps and accessibility services unless the user allows autostart and
 * exempts the app from battery limits. If ScrollLock is killed the blocked apps are silently unlocked, so the
 * Settings screen shows steps for the phone it's on. The component names are from memory and differ between
 * versions; the app tries each and falls back to App info, so a wrong one is harmless. Pure, so it's tested.
 */
object VendorGuide {
    data class Guide(val maker: String, val steps: List<String>, val components: List<Pair<String, String>>)

    fun forManufacturer(raw: String?): Guide {
        val m = raw.orEmpty().trim().lowercase()
        return when {
            m in setOf("xiaomi", "redmi", "poco") -> Guide(
                "Xiaomi (HyperOS / MIUI)",
                listOf(
                    "Turn Autostart on for ScrollLock.",
                    "App info, Battery saver: choose No restrictions.",
                    "Open Recents, long-press the ScrollLock card, and lock it.",
                ),
                listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            m in setOf("oppo", "realme", "oneplus") -> Guide(
                "Oppo / Realme / OnePlus (ColorOS, OxygenOS)",
                listOf(
                    "App info, Battery usage: allow background activity, and allow auto-launch if it's listed.",
                    "Open Recents, tap the app's menu, and lock it.",
                    "For the status-bar capsule: Settings, Notifications, Live Alerts, and turn on Live Updates for ScrollLock if the option is there.",
                ),
                listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
            )
            m in setOf("honor", "huawei") -> Guide(
                "Honor / Huawei (MagicOS)",
                listOf(
                    "App launch: turn ScrollLock to Manage manually, then allow Auto-launch, Secondary launch and Run in background.",
                    "Open Recents and lock the ScrollLock card.",
                ),
                listOf(
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            )
            m in setOf("vivo", "iqoo") -> Guide(
                "Vivo / iQOO (Funtouch, OriginOS)",
                listOf(
                    "Allow ScrollLock to run in the background and to auto-start.",
                    "Battery, Background power consumption: choose Allow.",
                ),
                listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            m == "samsung" -> Guide(
                "Samsung (One UI)",
                listOf(
                    "Battery, Background usage limits: add ScrollLock to Never sleeping apps.",
                    "App info, Battery: choose Unrestricted.",
                ),
                emptyList(),
            )
            else -> Guide(
                "Your phone",
                listOf(
                    "App info, Battery: choose Unrestricted (or No restrictions).",
                    "If your phone has an Autostart or Auto-launch list, turn ScrollLock on.",
                ),
                emptyList(),
            )
        }
    }
}
