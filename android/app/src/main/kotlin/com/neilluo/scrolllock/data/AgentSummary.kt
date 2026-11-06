package com.neilluo.scrolllock.data

/** Words for the agents that are working: one short line for a notification, fuller lines when expanded. Pure, so it's tested. */
object AgentSummary {
    fun label(raw: String): String = when (raw.lowercase()) {
        "claude", "claude-code", "claudecode" -> "Claude"
        "codex" -> "Codex"
        "gemini" -> "Gemini"
        "cursor" -> "Cursor"
        else -> raw.replaceFirstChar { it.uppercase() }
    }

    private fun hasCounts(a: AgentStatus) = (a.total ?: 0) > 0

    /** "Claude 3/7", or "Codex working" when it reports no task list. */
    fun one(a: AgentStatus): String =
        if (hasCounts(a)) "${label(a.agent)} ${a.done ?: 0}/${a.total}" else "${label(a.agent)} working"

    /** For a notification line: up to three agents, then "+N". Empty when nobody is working. */
    fun short(agents: List<AgentStatus>): String {
        if (agents.isEmpty()) return ""
        val shown = agents.take(3).joinToString(" · ") { one(it) }
        return if (agents.size > 3) "$shown · +${agents.size - 3}" else shown
    }

    /** For the island: one agent by name and progress, several as a count. */
    fun island(agents: List<AgentStatus>): String = when (agents.size) {
        0 -> "Claude working"
        1 -> one(agents[0])
        else -> "${agents.size} agents"
    }

    /** "5 of 12 tasks done" across every agent that reports counts, or null if none do. */
    fun rollup(agents: List<AgentStatus>): String? {
        val with = agents.filter { hasCounts(it) }
        if (with.isEmpty()) return null
        val done = with.sumOf { it.done ?: 0 }
        val total = with.sumOf { it.total ?: 0 }
        return if (agents.size > 1) "$done of $total tasks done across ${agents.size} agents" else "$done of $total tasks done"
    }

    /** Expanded notification: one line per agent, with task text only when [showText] and the sender provided it. */
    fun detailLines(agents: List<AgentStatus>, showText: Boolean): List<String> = buildList {
        for (a in agents) {
            val now = a.now?.takeIf { showText && it.isNotBlank() }
            add(if (now != null) "${one(a)} · $now" else one(a))
            val next = a.next?.takeIf { showText && it.isNotBlank() }
            if (next != null) add("   Next: $next")
        }
        rollup(agents)?.takeIf { agents.size > 1 }?.let { add(it) }
    }
}
