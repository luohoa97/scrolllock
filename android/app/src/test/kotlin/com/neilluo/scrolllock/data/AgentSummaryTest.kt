package com.neilluo.scrolllock.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AgentSummaryTest {
    private val claude = AgentStatus("claude", 3, 7, "Writing the migration", "Ship it")
    private val codex = AgentStatus("codex")

    @Test fun oneAgentShowsCountsOrWorking() {
        assertEquals("Claude 3/7", AgentSummary.one(claude))
        assertEquals("Codex working", AgentSummary.one(codex))
        assertEquals("My-agent working", AgentSummary.one(AgentStatus("my-agent")))
    }

    @Test fun shortListsUpToThreeThenCounts() {
        assertEquals("", AgentSummary.short(emptyList()))
        assertEquals("Claude 3/7 · Codex working", AgentSummary.short(listOf(claude, codex)))
        val many = List(5) { AgentStatus("claude", 1, 2) }
        assertEquals("Claude 1/2 · Claude 1/2 · Claude 1/2 · +2", AgentSummary.short(many))
    }

    @Test fun capsuleIsAsShortAsItCanBe() {
        assertEquals("Working", AgentSummary.island(emptyList()))
        assertEquals("Working", AgentSummary.island(listOf(codex)))
        assertEquals("3/7", AgentSummary.island(listOf(claude)))
        assertEquals("2 agents", AgentSummary.island(listOf(claude, codex)))
    }

    @Test fun rollupSumsOnlyAgentsThatReportCounts() {
        assertNull(AgentSummary.rollup(listOf(codex)))
        assertEquals("3 of 7 tasks done", AgentSummary.rollup(listOf(claude)))
        assertEquals("4 of 9 tasks done across 2 agents", AgentSummary.rollup(listOf(claude, AgentStatus("codex", 1, 2))))
    }

    @Test fun taskTextAppearsOnlyWhenAllowed() {
        assertEquals(listOf("Claude 3/7"), AgentSummary.detailLines(listOf(claude), showText = false))
        assertEquals(listOf("Claude 3/7 · Writing the migration", "   Next: Ship it"), AgentSummary.detailLines(listOf(claude), showText = true))
    }
}
