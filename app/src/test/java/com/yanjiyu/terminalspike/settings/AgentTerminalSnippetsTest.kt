package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.ui.SnippetDispatchOutcome
import com.yanjiyu.terminalspike.ui.dispatchSnippet
import org.junit.Assert.*
import org.junit.Test

class AgentTerminalSnippetsTest {
    @Test fun effortShortcutsSubmitOneNativeSessionCommandWithoutChangingTheModel() {
        val expected = listOf(
            "/model --reasoning=low",
            "/model --reasoning=medium",
            "/model --reasoning=high",
            "/model --reasoning=xhigh",
            "/model --reasoning=max",
            "/model --reasoning=ultra",
        )
        val snippets = AgentTerminalSnippets.forAgent("codex")
        assertEquals(expected.size, snippets.size)
        snippets.zip(expected).forEach { (snippet, command) ->
            assertEquals(command, snippet.command)
            assertNull(SnippetSequence.parse(snippet.command))
            assertTrue(snippet.sendsImmediately)
            var submissions = 0
            assertEquals(SnippetDispatchOutcome.SENT, dispatchSnippet(snippet, confirmed = true) {
                assertTrue(it)
                submissions++
                true
            })
            assertEquals(1, submissions)
        }
    }

    @Test fun claudeEffortUsesNativeCommandsWithoutChangingTheModel() {
        val snippets = AgentTerminalSnippets.forAgent("claude")
        assertEquals(listOf("/effort low", "/effort medium", "/effort high", "/effort xhigh", "/effort max", "/effort auto"),
            snippets.map { it.command })
        snippets.forEach { snippet ->
            assertTrue(snippet.sendsImmediately)
            assertNull(SnippetSequence.parse(snippet.command))
            assertFalse(AgentTerminalSnippets.forAgent("codex").any { it.id == snippet.id })
        }
    }

    @Test fun effortCommandsCannotCrossAgentBoundaries() {
        for ((agent, other) in listOf("codex" to "claude", "claude" to "codex")) {
            for (snippet in AgentTerminalSnippets.forAgent(agent)) {
                assertTrue(AgentTerminalSnippets.canSend(snippet.id, agent, true))
                assertFalse(AgentTerminalSnippets.canSend(snippet.id, other, true))
                assertFalse(AgentTerminalSnippets.canSend(snippet.id, "pi", true))
                assertFalse(AgentTerminalSnippets.canSend(snippet.id, "", true))
            }
        }
        assertFalse(AgentTerminalSnippets.canSend(-201, "", false))
        assertTrue(AgentTerminalSnippets.canSend(-101, "", false))
    }

    @Test fun scopedCommandsDoNotLeakIntoGeneralSnippetsOrOtherAgents() {
        for (agent in listOf("", "pi", "opencode")) {
            assertTrue(AgentTerminalSnippets.forAgent(agent).isEmpty())
        }
        val all = AgentTerminalSnippets.all + DefaultTerminalSnippets.all
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(AgentTerminalSnippets.all.all { it.id < 0 })
        assertEquals(6, AgentTerminalSnippets.forAgent("codex").size)
    }
}
