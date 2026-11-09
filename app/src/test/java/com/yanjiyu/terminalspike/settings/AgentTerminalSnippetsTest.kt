package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.ui.SnippetDispatchOutcome
import com.yanjiyu.terminalspike.ui.dispatchSnippet
import org.junit.Assert.*
import org.junit.Test

class AgentTerminalSnippetsTest {
    @Test fun effortShortcutsSubmitOneNativeSessionCommandWithoutChangingTheModel() {
        val expected = listOf("/model --reasoning=low", "/model --reasoning=medium", "/model --reasoning=high")
        AgentTerminalSnippets.forAgent("codex").zip(expected).forEach { (snippet, command) ->
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

    @Test fun scopedCommandsDoNotLeakIntoGeneralSnippetsOrOtherAgents() {
        for (agent in listOf("", "claude", "pi", "opencode")) {
            assertTrue(AgentTerminalSnippets.forAgent(agent).isEmpty())
        }
        val all = AgentTerminalSnippets.all + DefaultTerminalSnippets.all
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertTrue(AgentTerminalSnippets.all.all { it.id < 0 })
        assertEquals(6, AgentTerminalSnippets.forAgent("codex").size)
    }
}
