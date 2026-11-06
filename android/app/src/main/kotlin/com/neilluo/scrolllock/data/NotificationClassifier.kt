package com.neilluo.scrolllock.data

/**
 * Decides whether a notification from the Claude app means "I'm waiting on you". The words are a guess
 * until we've seen real ones (Settings, Capture Claude notifications), so they live in one place.
 * Pure and tested. Notifications that say a task finished are not waiting: that's the opposite.
 */
object NotificationClassifier {
    private val needs = listOf(
        "needs your", "need your", "waiting for you", "waiting on you", "your input", "your approval", "approval",
        "approve", "permission", "question", "blocked", "decision", "needs a decision", "asked you", "wants to know",
        "action required", "your turn",
    )
    private val done = listOf("finished", "completed", "is done", "is ready", "task complete", "all done")

    fun needsYou(title: String?, text: String?, ongoing: Boolean): Boolean {
        if (ongoing) return false
        val t = "${title.orEmpty()} ${text.orEmpty()}".lowercase()
        if (t.isBlank()) return false
        if (needs.none { t.contains(it) }) return false
        // "Finished" on its own is not waiting. A finished task that also asks something still is.
        val asks = t.contains("?") || t.contains("question") || t.contains("approv") || t.contains("permission") || t.contains("your input")
        return done.none { t.contains(it) } || asks
    }
}
