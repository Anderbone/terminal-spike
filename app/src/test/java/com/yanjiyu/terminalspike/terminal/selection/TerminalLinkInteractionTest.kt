package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinkInteractionTest {
    private val anchor = TerminalLineAnchor(TerminalLineSpace.PRIMARY_HISTORY, 7L)

    @Test
    fun osc8HitTestingUsesRetainedCellGeometry() {
        val line = TerminalLine.styled(
            listOf(
                TerminalRun("prompt ", startColumn = 0, columnWidth = 7),
                TerminalRun(
                    "docs",
                    hyperlink = TerminalHyperlink("https://example.test/docs", "help"),
                    startColumn = 7,
                    columnWidth = 4,
                ),
            ),
        )
        val selectable = TerminalSelectableLine(anchor, line)

        val target = TerminalLinkResolver.find(selectable, 9, osc8Enabled = true, plainTextUrlsEnabled = false)

        assertEquals("https://example.test/docs", target?.uri)
        assertEquals("help", target?.id)
        assertEquals(TerminalLinkSource.OSC8, target?.source)
        assertEquals(7, target?.startColumn)
        assertEquals(11, target?.endColumn)
        assertNull(TerminalLinkResolver.find(selectable, 6, true, false))
        assertNull(TerminalLinkResolver.find(selectable, 9, false, false))
    }

    @Test
    fun boundedPlainHttpDetectionDropsSentencePunctuation() {
        val selectable = TerminalSelectableLine(
            anchor,
            TerminalLine.plain("See https://example.test/path?q=1, then continue"),
        )

        val target = TerminalLinkResolver.find(selectable, 15, osc8Enabled = false, plainTextUrlsEnabled = true)

        assertEquals("https://example.test/path?q=1", target?.uri)
        assertEquals(TerminalLinkSource.PLAIN_TEXT, target?.source)
    }

    @Test
    fun openingPolicyAllowsOnlyAbsoluteHttpAndHttpsHosts() {
        assertTrue(TerminalLinkPolicy.canOpen("https://example.test/a"))
        assertTrue(TerminalLinkPolicy.canOpen("http://127.0.0.1:8080"))
        assertFalse(TerminalLinkPolicy.canOpen("javascript:alert(1)"))
        assertFalse(TerminalLinkPolicy.canOpen("file:///etc/passwd"))
        assertFalse(TerminalLinkPolicy.canOpen("https:///missing-host"))
        assertFalse(TerminalLinkPolicy.canOpen("https://example.test/a b"))
        assertFalse(TerminalLinkPolicy.canOpen("https://example.test/ab\u202Etxt"))
        assertFalse(TerminalLinkPolicy.canOpen("https://example.test/\uFFFD"))
    }

    @Test
    fun unsafeOsc8MetadataCanBeCopiedButCannotBeOpened() {
        val target = TerminalLinkResolver.find(
            selectable = TerminalSelectableLine(
                anchor,
                TerminalLine.styled(
                    listOf(
                        TerminalRun(
                            "local",
                            hyperlink = TerminalHyperlink("file:///tmp/output"),
                            startColumn = 0,
                            columnWidth = 5,
                        ),
                    ),
                ),
            ),
            column = 2,
            osc8Enabled = true,
            plainTextUrlsEnabled = false,
        )

        assertEquals("file:///tmp/output", target?.uri)
        assertFalse(TerminalLinkPolicy.canOpen(requireNotNull(target).uri))
    }
}
