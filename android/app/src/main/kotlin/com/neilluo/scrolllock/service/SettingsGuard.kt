package com.neilluo.scrolllock.service

/**
 * Decides whether a Settings or installer screen is one that could remove or disable ScrollLock.
 * It needs the app's name and one dangerous word on the same screen, so scrolling past ScrollLock
 * in the apps list is fine and opening its app-info page is not. Text is matched here and never
 * stored or logged.
 */
object SettingsGuard {
    fun shouldBlock(texts: List<String>, appLabel: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val hasName = texts.any { it.contains(appLabel, ignoreCase = true) }
        if (!hasName) return false
        return texts.any { t -> keywords.any { k -> t.contains(k, ignoreCase = true) } }
    }
}
