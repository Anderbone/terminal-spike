package com.yanjiyu.terminalspike.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SnippetSequenceTest {
    @Test fun plainTextRemainsLiteral() {
        for (text in listOf("ctrl+c", "text hello\nenter", "echo '#!keys'", "#!keys extra")) {
            assertNull(SnippetSequence.parse(text))
        }
    }

    @Test fun exampleSendsRealKeysAndSeparateTextInOrder() = runTest {
        val events = mutableListOf<String>()
        assertTrue(SnippetSequence.execute(
            SnippetSequence.parse(SnippetSequence.CODEX_EXAMPLE)!!,
            canContinue = { true },
            sendText = { events += "text:$it"; true },
            sendKey = { events += "key:$it"; true },
        ))
        assertEquals(listOf("key:3", "text:/clear", "key:13", "text:codex --yolo", "key:13"), events)
        assertEquals(1000, testScheduler.currentTime)
    }

    @Test fun validatesEntireSequenceBeforeSending() {
        for (text in listOf("#!keys", "#!keys\nenter\nbogus", "#!keys\nwait -1", "#!keys\nwait 10001",
            "#!keys\n" + "wait 10000\n".repeat(7))) {
            assertTrue(runCatching { SnippetSequence.parse(text) }.isFailure)
        }
    }

    @Test fun textPreservesWhitespaceAndSupportsWindowsLineEndings() {
        assertEquals(listOf(SnippetSequence.Step.Text("  a  "), SnippetSequence.Step.Key(9)),
            SnippetSequence.parse("#!keys\r\ntext   a  \r\ntab"))
    }

    @Test fun stopsAfterRejectedInput() = runTest {
        var keys = 0
        assertFalse(SnippetSequence.execute(SnippetSequence.parse("#!keys\nctrl+c\nenter")!!,
            { true }, { error("Unexpected text") }, { keys++; false }))
        assertEquals(1, keys)
    }

    @Test fun rechecksTargetAfterWait() = runTest {
        var keys = 0
        assertFalse(SnippetSequence.execute(SnippetSequence.parse("#!keys\nwait 500\nenter")!!,
            { testScheduler.currentTime < 500 }, { true }, { keys++; true }))
        assertEquals(0, keys)
    }
}
