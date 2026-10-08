package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import org.junit.Assert.*
import org.junit.Test

class HerdrInputContextTest {
    private val choice = HerdrStartupChoice("/usr/bin/herdr", "work ' quoted")

    private fun response(command: String, name: String = "codex", paneId: String = "w1:p2"): TmuxExecOutput {
        val body = when {
            "'layout'" in command -> """{"layout":{"focused_pane_id":"$paneId","tab_id":"w1:t1"}}"""
            "'get'" in command -> """{"pane":{"pane_id":"$paneId","tab_id":"w1:t1","terminal_id":"term1","agent":"codex"}}"""
            "'process-info'" in command -> """{"process_info":{"pane_id":"$paneId","foreground_processes":[{"name":"$name"}]}}"""
            else -> error("Unexpected command: $command")
        }
        return TmuxExecOutput("""{"result":$body}""".toByteArray(), 0)
    }

    @Test fun onlyConfirmedFullscreenPiReceivesPaneScopedPaging() {
        for ((name, agent, maxOffset, expected) in listOf(
            listOf("pi", "pi", "0", "true"),
            listOf("pi", "pi", "80", "false"),
            listOf("bash", "pi", "0", "false"),
            listOf("codex", "codex", "0", "false"),
        )) {
            val context = captureHerdrInputContext({ command ->
                val output = response(command, name)
                output.copy(stdout = output.stdout.toString(Charsets.UTF_8)
                    .replace("\"agent\":\"codex\"", "\"agent\":\"$agent\",\"scroll\":{\"max_offset_from_bottom\":$maxOffset}")
                    .replace("\"focused_pane_id\":\"w1:p2\"", "\"focused_pane_id\":\"w1:p2\",\"panes\":[{\"pane_id\":\"w1:p2\",\"rect\":{\"x\":26,\"y\":1,\"width\":62,\"height\":32}}]")
                    .toByteArray())
            }, choice)
            assertEquals(expected.toBoolean(), context.herdrPiScrollPane != null)
            context.herdrPiScrollPane?.let { pane ->
                assertTrue(pane.contains(26, 1))
                assertFalse(pane.contains(25, 10))
                assertFalse(pane.contains(30, 0))
            }
        }
    }

    @Test fun focusedCodexUsesMetadataWithoutReadingHistoryOrSendingInput() {
        val commands = mutableListOf<String>()
        val result = captureHerdrInputContext({ commands += it; response(it) }, choice)
        assertEquals(TerminalInputContext("herdr/work ' quoted/w1:p2/term1", true), result)
        assertEquals(4, commands.size)
        assertTrue(commands.all { it.startsWith("'/usr/bin/herdr' '--session' 'work '\\'' quoted'") })
        assertTrue(commands.any { "'process-info' '--pane' 'w1:p2'" in it })
    }

    @Test fun letterPaneNumberPreservesAgentInputContext() {
        val result = captureHerdrInputContext({ response(it, paneId = "w6:pC") }, choice)
        assertEquals(TerminalInputContext("herdr/work ' quoted/w6:pC/term1", true), result)
    }

    @Test fun returningToShellOrAnotherProgramDoesNotInheritAnAgentLabel() {
        for (name in listOf("bash", "zsh", "vim", "")) {
            assertFalse(captureHerdrInputContext({ response(it, name) }, choice).agent)
        }
    }

    @Test fun paneSwitchDuringInspectionDiscardsTheOldResult() {
        var layouts = 0
        val result = captureHerdrInputContext({ command ->
            val switched = "'layout'" in command && ++layouts == 2
            response(command, paneId = if (switched) "w1:p3" else "w1:p2")
        }, choice)
        assertEquals(TerminalInputContext(), result)
    }

    @Test fun unavailableMalformedAndOversizedMetadataDefaultToDirectInput() {
        for (output in listOf(TmuxExecOutput(byteArrayOf(), 1), TmuxExecOutput("{}".toByteArray(), 0),
            TmuxExecOutput(ByteArray(65_537), 0))) {
            assertEquals(TerminalInputContext(), captureHerdrInputContext({ output }, choice))
        }
    }
}
