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
        assertEquals("中文 🚀 hello world", terminalTextAsSingleLine("\t中文 🚀 \r\n  hello world\t\r\n"))
        assertEquals("https://example.com/very-long-path?q=1", terminalTextAsSingleLine(
            "https://example.com/very-\n  long-path?q=1",
        ))
        assertEquals("", terminalTextAsSingleLine("\r\n \t\n"))
    }

    @Test fun claudeCodeWordWrappedCommandRestoresTheDroppedSpaces() {
        // Captured from Claude Code 2.1 in a 42-column terminal: breaks drop the space.
        val rows = listOf(
            "● git commit -am \"fix the wrapping bug in",
            "  copy\" && git push origin main &&",
            "  ./gradlew assembleDebug --console=plain",
        )
        val expected = "git commit -am \"fix the wrapping bug in copy\" && git push origin main && " +
            "./gradlew assembleDebug --console=plain"
        val full = rows.joinToString("\n")
        assertEquals("● $expected", terminalTextAsSingleLine(full))
        // A partial first row must still be measured against the full physical row width.
        assertEquals(expected, terminalTextAsSingleLine(full.removePrefix("● "), widestRow(full)))
    }

    @Test fun claudeCodeHardBrokenLongTokenStartingMidRowStaysJoined() {
        val rows = "see https://example.com/a-very-long-path/\n" +
            "  with/more/segments?and=query and then\n  more words"
        assertEquals(
            "see https://example.com/a-very-long-path/with/more/segments?and=query and then more words",
            terminalTextAsSingleLine(rows),
        )
    }

    @Test fun chineseRowsJoinWithoutSpacesAndAWrappedPathStaysIntact() {
        // Claude Code in a narrow Herdr pane: CJK cells are two columns wide.
        val rows = listOf(
            "之前麻烦的根源是「状态」和「位置」要两",
            "边同时维护。运行 bash",
            "/home/jiyu/git/workspace-cable-flow/main.",
            "worktrees/claude-ci-autofix/scripts/ci/i",
            "nstall-ci-autofix.sh。",
        ).joinToString("\n")
        assertEquals(
            "之前麻烦的根源是「状态」和「位置」要两边同时维护。运行 bash " +
                "/home/jiyu/git/workspace-cable-flow/main.worktrees/claude-ci-autofix/scripts/ci/install-ci-autofix.sh。",
            terminalTextAsSingleLine(rows),
        )
    }

    @Test fun selectionStartingMidRowStillGluesAHardBrokenPath() {
        val rows = "运行 bash /home/jiyu/git/workspace-cable-f\n" +
            "low/main.worktrees/claude-ci-autofix/scr\n" +
            "ipts/ci/install-ci-autofix.sh。"
        val selected = rows.substringAfter("bash ")
        assertEquals(
            "/home/jiyu/git/workspace-cable-flow/main.worktrees/claude-ci-autofix/scripts/ci/install-ci-autofix.sh。",
            terminalTextAsSingleLine(selected, widestRow(rows), cellsOf("运行 bash ")),
        )
    }
}
