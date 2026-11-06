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

    /**
     * One entry per thing worth naming. A session with a task list stands alone ("Claude 3/7"); sessions of the
     * same agent that report nothing are merged, so three idle-looking Claude threads read "3 Claude sessions"
     * instead of "Claude working" three times. [plain] is appended to a single merged entry ("working").
     */
    private class Entry(val text: String, val session: AgentStatus?)

    private fun entries(agents: List<AgentStatus>, plain: String): List<Entry> {
        val seen = mutableSetOf<String>()
        return buildList {
            for (a in agents) {
                if (hasCounts(a)) { add(Entry("${label(a.agent)} ${a.done ?: 0}/${a.total}", a)); continue }
                val l = label(a.agent)
                if (!seen.add(l)) continue
                val n = agents.count { !hasCounts(it) && label(it.agent) == l }
                add(Entry(if (n > 1) "$n $l sessions$plain" else l + plain, null))
            }
        }
    }

    /** For a notification line: up to three entries, then "+N". Empty when nobody is working. */
    fun short(agents: List<AgentStatus>): String {
        val all = entries(agents, " working").map { it.text }
        if (all.isEmpty()) return ""
        val shown = all.take(3).joinToString(" · ")
        return if (all.size > 3) "$shown · +${all.size - 3}" else shown
    }

    /** For the Status screen's list, where the heading already says "working": names and progress only. */
    fun rows(agents: List<AgentStatus>): List<AgentStatus?> = entries(agents, "").map { it.session }

    /** The text of one Status screen row. */
    fun rowText(agents: List<AgentStatus>): List<String> = entries(agents, "").map { it.text }

    /** For the status-bar chip: as short as it can be. "3/7", "2 agents", or "Working". */
    fun chip(agents: List<AgentStatus>): String = when {
        agents.isEmpty() -> "Working"
        agents.size == 1 && hasCounts(agents[0]) -> "${agents[0].done ?: 0}/${agents[0].total}"
        agents.size == 1 -> "Working"
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
        for (e in entries(agents, " working")) {
            val a = e.session
            val now = a?.now?.takeIf { showText && it.isNotBlank() }
            add(if (now != null) "${e.text} · $now" else e.text)
            val next = a?.next?.takeIf { showText && it.isNotBlank() }
            if (next != null) add("   Next: $next")
        }
        rollup(agents)?.takeIf { agents.size > 1 }?.let { add(it) }
    }
}
