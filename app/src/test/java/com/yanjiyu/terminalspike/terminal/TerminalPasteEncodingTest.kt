package com.yanjiyu.terminalspike.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalPasteEncodingTest {
    @Test
    fun rawPastePreservesExactUtf8WithoutAddingEnter() {
        val text = "git status --short ✓"

        assertArrayEquals(
            text.toByteArray(Charsets.UTF_8),
            encodeTerminalPaste(text, bracketedPaste = false),
        )
    }

    @Test
    fun bracketedPasteWrapsExactTextWithoutAddingEnter() {
        val encoded = encodeTerminalPaste("first\nsecond", bracketedPaste = true)

        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e) +
                "first\nsecond".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e),
            encoded,
        )
    }

    @Test
    fun snippetPasteAndOptionalEnterAreEncodedAsOneAtomicBatch() {
        val encoded = encodeTerminalPaste(
            text = "git status",
            bracketedPaste = true,
            appendEnter = true,
        )

        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e) +
                "git status".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e, 0x0d),
            encoded,
        )
    }
}
