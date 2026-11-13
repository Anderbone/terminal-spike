package com.yanjiyu.terminalspike.terminal.selection

import org.junit.Assert.*
import org.junit.Test

class TerminalSingleLineCopyTest {
    @Test fun exactUserPastedNineRowsRestoreAll334CharactersWithoutWhitespace() {
        val pasted = listOf(
            "START_0123456789_ABCDEFGHIJKLMNOPQRSTUV",
            "  WXYZ_abcdefghijklmnopqrstuvwxyz_0123456",
            "  789_ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefgh",
            "  ijklmnopqrstuvwxyz_0123456789_ABCDEFGHI",
            "  JKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstu",
            "  vwxyz_0123456789_ABCDEFGHIJKLMNOPQRSTUV",
            "  WXYZ_abcdefghijklmnopqrstuvwxyz_0123456",
            "  789_ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefgh",
            "  ijklmnopqrstuvwxyz_END",
        ).joinToString("\n")
        val original = "START_" + "0123456789_ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz_".repeat(5) + "END"
        val copied = terminalTextAsSingleLine(pasted)
        assertEquals(334, copied.length)
        assertEquals(original, copied)
        assertFalse(copied.any { it.isWhitespace() })
    }

    @Test fun explicitFlatteningHandlesCrLfAndTabsWithoutChangingInternalSpacesOrUnicode() {
        assertEquals("中文 🚀hello world", terminalTextAsSingleLine("\t中文 🚀 \r\n  hello world\t\r\n"))
        assertEquals("https://example.com/very-long-path?q=1", terminalTextAsSingleLine(
            "https://example.com/very-\n  long-path?q=1",
        ))
        assertEquals("", terminalTextAsSingleLine("\r\n \t\n"))
    }
}
