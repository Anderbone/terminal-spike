package com.yanjiyu.terminalspike.terminal

import org.junit.Assert.*
import org.junit.Test

class CodexEffortTest {
    @Test fun busyFooterDoesNotRequireAnIdleComposer() {
        assertEquals("low", codexEffortFromLiveFooter("Working…\nGPT-6.1-Sol low · Context 49% left …"))
    }

    @Test fun transientMissRetainsConfirmedValueButExpiryAndProcessChangesClearIt() {
        var time = 1L
        val observation = CodexEffortObservation { time }
        val confirmed = TerminalInputContext("pane-1", true, agentId = "codex", reasoningEffort = "low", agentProcessId = 10)
        assertEquals("low", observation.observe(confirmed).reasoningEffort)
        time += 1_000_000_000L
        assertEquals("low", observation.observe(confirmed.copy(reasoningEffort = null)).reasoningEffort)
        time += 3_000_000_001L
        assertNull(observation.observe(confirmed.copy(reasoningEffort = null)).reasoningEffort)
        observation.observe(confirmed)
        assertNull(observation.observe(confirmed.copy(reasoningEffort = null, agentProcessId = 11)).reasoningEffort)
        observation.observe(confirmed)
        assertNull(observation.observe(confirmed.copy(identity = "pane-2", reasoningEffort = null)).reasoningEffort)
        assertNull(observation.observe(TerminalInputContext()).reasoningEffort)
    }
    @Test fun currentFooterReportsTheActualEffort() {
        assertEquals("medium", codexEffortFromLiveFooter("›\n\n  GPT-6.1-Sol medium · Context 49% left …"))
        assertEquals("high", codexEffortFromLiveFooter("›\ngpt-6.1-sol high · /model"))
        assertEquals("xhigh", codexEffortFromLiveFooter("›\ngpt-6-astra xhigh"))
    }

    @Test fun promptsExamplesCommandsAndOldHistoryDoNotBecomeCurrentEffort() {
        for (text in listOf(
            "/model --reasoning=high", "› gpt-6.1-sol high", "Example: gpt-6.1-sol low",
            "Codex · High", "gpt-6.1-sol high would be better", "gpt-6.1-sol default",
            "gpt-6.1-sol high · an example\n›\nweekly limit 98% left",
            "gpt-6.1-sol low\n" + "chat output\n".repeat(12), "x".repeat(8193),
        )) assertNull(text, codexEffortFromLiveFooter(text))
    }
}
