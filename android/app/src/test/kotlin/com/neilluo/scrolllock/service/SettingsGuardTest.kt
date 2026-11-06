package com.neilluo.scrolllock.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {
    private val kw = listOf("uninstall", "force stop", "deactivate", "use scrolllock")

    @Test fun appInfoPageIsBlocked() {
        assertTrue(SettingsGuard.shouldBlock(listOf("App info", "ScrollLock", "Uninstall", "Force stop"), "ScrollLock", kw))
    }

    @Test fun accessibilityTogglePageIsBlocked() {
        assertTrue(SettingsGuard.shouldBlock(listOf("ScrollLock", "Use ScrollLock", "Off"), "ScrollLock", kw))
    }

    @Test fun appsListAloneIsNotBlocked() {
        assertFalse(SettingsGuard.shouldBlock(listOf("Apps", "Chrome", "ScrollLock", "Spotify"), "ScrollLock", kw))
    }

    @Test fun otherAppsUninstallPageIsNotBlocked() {
        assertFalse(SettingsGuard.shouldBlock(listOf("App info", "Spotify", "Uninstall", "Force stop"), "ScrollLock", kw))
    }

    @Test fun matchingIgnoresCase() {
        assertTrue(SettingsGuard.shouldBlock(listOf("scrolllock", "UNINSTALL"), "ScrollLock", kw))
    }

    @Test fun noKeywordsMeansNoBlocking() {
        assertFalse(SettingsGuard.shouldBlock(listOf("ScrollLock", "Uninstall"), "ScrollLock", emptyList()))
    }
}
