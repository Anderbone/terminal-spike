package com.yanjiyu.terminalspike.terminal

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TerminalPasteSafetyTest(private val bracketed: Boolean, private val appendEnter: Boolean) {
    @Test
    fun embeddedTerminatorsRejectTheWholePasteBeforeCallingTheTransport() {
        val f = Fixture()
        for (marker in listOf("\u001b[201~", "\u009b201~")) {
            for (text in listOf(marker + "tail", "before" + marker + "\rcommand\r", "before" + marker)) {
                assertFalse(f.controller.sendPaste(text, appendEnter))
                assertEquals(0, f.sink.attempts)
                assertTrue(f.sink.received.isEmpty())
                assertEquals(0, f.accepted)
                assertTrue(f.controller.herdrHistoryReading)
            }
        }
    }

    @Test
    fun rejectedPasteKeepsTheExactDraftSelectionAndPersistedText() {
        val f = Fixture()
        val writes = mutableListOf<String>()
        val draft = BufferedInputDraftState { writes += it }
        draft.update(TextFieldValue("keep \u001b[201~\rcommand", selection = TextRange(2, 6)), 7L)
        val before = draft.value
        val persisted = writes.toList()

        draft.dispatch(7L, sendEnabled = true) { _, text -> f.controller.sendPaste(text, appendEnter) }

        assertEquals(before, draft.value)
        assertEquals(persisted, writes)
        assertFalse(draft.canRestoreLastSent)
        assertEquals(0, f.sink.attempts)
        assertEquals(0, f.accepted)
    }

    @Test
    fun ordinaryMultilineUnicodeTextKeepsItsExactBytesAndOptionalEnter() {
        val f = Fixture()
        val text = "printf '漢字 😀'\nnext\r\ttab"
        assertTrue(f.controller.sendPaste(text, appendEnter))
        assertArrayEquals(expected(text), f.sink.received.single())
        assertEquals(1, f.sink.attempts)
        assertEquals(1, f.accepted)
    }

    @Test
    fun literalEscapedNotationAndIncompleteMarkersAreNotRewritten() {
        val f = Fixture()
        for (text in listOf("literal \\u001b[201~", "plain [201~", "\u001b[201")) {
            assertTrue(f.controller.sendPaste(text, appendEnter))
            assertArrayEquals(expected(text), f.sink.received.last())
        }
    }

    private fun expected(text: String): ByteArray = (
        (if (bracketed) "\u001b[200~" else "") + text +
            (if (bracketed) "\u001b[201~" else "") + (if (appendEnter) "\r" else "")
        ).toByteArray(Charsets.UTF_8)

    private inner class Fixture {
        val sink = Sink()
        var accepted = 0
        val controller = TerminalController(TerminalBuffer(), TerminalFrameScheduler {}).apply {
            setInputSink(sink, onInputAccepted = { accepted++ }, onResize = { _, _ -> })
            val mode = if (bracketed) "\u001b[?2004h" else "\u001b[?2004l"
            updateTerminalFrame(VtTerminalEngine(20, 4).accept(mode.toByteArray()))
            herdrHistoryReading = true
        }
    }

    private class Sink : TerminalInputSink {
        var attempts = 0
        val received = mutableListOf<ByteArray>()
        override fun send(bytes: ByteArray) { received += bytes.copyOf() }
        override fun trySend(bytes: ByteArray): Boolean { attempts++; send(bytes); return true }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "bracketed={0},appendEnter={1}")
        fun modes(): List<Array<Boolean>> = listOf(
            arrayOf(false, false), arrayOf(false, true), arrayOf(true, false), arrayOf(true, true),
        )
    }
}
