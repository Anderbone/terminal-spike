package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.*
import org.junit.Test

class TerminalTextExtractionTest {
    @Test fun joinsSoftWrapsButKeepsCommandNewlinesAndDetachesFromRedraw() {
        val lines = mutableListOf(
            TerminalLine.plain("说明：中文 🚀"),
            TerminalLine.plain("printf '%s\\n' ", softWrappedToNext = true),
            TerminalLine.plain("'hello world'"),
            TerminalLine.plain("git status"),
        )
        val source = source(lines)
        val selection = TerminalSelectionModel()
        selection.beginWord(source, 1, 0)
        selection.updateEndpoint(source, TerminalSelectionEndpoint.END, 2, 13)
        val result = requireNotNull(TerminalTextExtraction.capture(source, selection))
        assertEquals("说明：中文 🚀\nprintf '%s\\n' 'hello world'\ngit status", result.text)
        assertEquals("printf '%s\\n' 'hello world'", result.text.substring(result.selectionStart, result.selectionEnd))
        lines.clear()
        assertTrue(result.text.contains("hello world"))
    }

    @Test fun contextIsBoundedAroundTheHitAndKeepsExactUnicodeSelection() {
        val source = source(MutableList(1000) { TerminalLine.plain("row$it 中文 🚀") })
        val selection = TerminalSelectionModel()
        selection.beginWord(source, 500, 7)
        val result = requireNotNull(TerminalTextExtraction.capture(source, selection))
        assertEquals(17, result.text.lines().size)
        assertEquals("中文", result.text.substring(result.selectionStart, result.selectionEnd))
        assertTrue(result.text.startsWith("row492"))
        assertTrue(result.text.endsWith("row508 中文 🚀"))
        val workspace = requireNotNull(TerminalTextExtraction.capture(source, selection, surroundingRows = 30))
        assertTrue(workspace.text.startsWith("row470"))
        assertTrue(workspace.text.endsWith("row530 中文 🚀"))
        assertEquals("中文", workspace.text.substring(workspace.selectionStart, workspace.selectionEnd))
    }

    private fun source(lines: MutableList<TerminalLine>) = object : TerminalSelectionSource {
        override val selectionLineCount get() = lines.size
        override fun selectionLineAt(index: Int) = lines.getOrNull(index)?.let {
            TerminalSelectableLine(TerminalLineAnchor(TerminalLineSpace.PRIMARY_HISTORY, index.toLong()), it)
        }
        override fun selectionIndexOf(anchor: TerminalLineAnchor) = anchor.id.toInt().takeIf { it in lines.indices }
    }
}
