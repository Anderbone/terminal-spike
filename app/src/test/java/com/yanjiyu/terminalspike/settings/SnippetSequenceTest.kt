package com.yanjiyu.terminalspike.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SnippetSequenceTest {
    @Test fun chordsEncodeModifiersAndNavigation() {
        val cases = mapOf(
            "shift+enter" to "\u001b[13;2u",
            "ctrl+alt+left" to "\u001b[1;7D",
            "shift+tab" to "\u001b[Z",
            "ctrl+alt+c" to "\u001b\u0003",
            "shift+1" to "!",
            "alt+plus" to "\u001b+",
            "super+f12" to "\u001b[24;9~",
            "ctrl+shift+alt+enter" to "\u001b[13;8u",
        )
        cases.forEach { (chord, bytes) ->
            assertArrayEquals(chord, bytes.toByteArray(), SnippetKeyChord.encode(chord))
            assertNotNull(SnippetSequence.parse("#!keys\n$chord"))
        }
        listOf("ctrl+ctrl+a", "hyper+a", "shift+", "f13", "ctrl+unknown").forEach {
            assertNull(it, SnippetKeyChord.encode(it))
        }
    }

    @Test fun chordIsSentAtomicallyWithoutPasting() = runTest {
        val sent = mutableListOf<String>()
        assertTrue(SnippetSequence.execute(
            SnippetSequence.parse("#!keys\nshift+enter\nenter")!!,
            { true }, { error("Must not paste key sequences") },
            { sent += "byte:$it"; true },
            { sent += it.toString(Charsets.US_ASCII); true },
        ))
        assertEquals(listOf("\u001b[13;2u", "byte:13"), sent)
    }

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
