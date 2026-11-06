package com.neilluo.scrolllock.data

/**
 * Something that is waiting on you. [name] says who (an agent, or "Claude app"); [detail] is a project or
 * notification title, shown only on the phone and never on the lock screen.
 */
data class Waiter(val name: String, val detail: String?) {
    companion object {
        fun fromRelay(w: WaitingStatus, showText: Boolean) =
            Waiter(AgentSummary.label(w.agent), w.label?.takeIf { showText && it.isNotBlank() })
    }
}

object WaitingSummary {
    /** "Claude · my-project, Coordinator" for the overlay and notification; "+N more" past three. */
    fun names(list: List<Waiter>): String {
        if (list.isEmpty()) return ""
        val shown = list.take(3).joinToString(", ") { w -> w.detail?.let { "${w.name} · $it" } ?: w.name }
        return if (list.size > 3) "$shown +${list.size - 3} more" else shown
    }

    /** Safe for the lock screen: counts only. */
    fun count(n: Int): String = if (n <= 1) "Something is waiting on you" else "$n things are waiting on you"
}
