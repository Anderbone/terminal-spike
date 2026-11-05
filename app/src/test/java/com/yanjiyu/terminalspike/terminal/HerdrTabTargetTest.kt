package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrTabTargetTest {
    @Test fun newSpaceLongPressExcludesMenuExistingSpacesAndPaneOutput() {
        val screen = listOf(
            TerminalLine.plain(" spaces       │ tab +"),
            TerminalLine.plain(" project      │ new menu"),
            TerminalLine.plain("              │ output"),
            TerminalLine.plain(" new     menu │ output"),
            TerminalLine.plain(" agents       │ output"),
            TerminalLine.plain(" codex        │ output"),
        )
        for (column in 1..3) assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 40, 6, column, 3))
        for ((column, row) in listOf(1 to 0, 1 to 1, 1 to 4, 9 to 3, 15 to 1, -1 to 3, 1 to 6)) {
            assertFalse(HerdrNavigationChrome.isNewSpaceCell(screen, 40, 6, column, row))
        }
        val broken = screen.toMutableList().apply { this[2] = TerminalLine.plain("new menu") }
        assertFalse(HerdrNavigationChrome.isNewSpaceCell(broken, 40, 6, 1, 3))
    }

    @Test fun spacesExcludeHeadersFooterAgentsAndEmptyRows() {
        val screen = listOf(
            TerminalLine.plain(" spaces       │ tab +"),
            TerminalLine.plain(" project      │ output"),
            TerminalLine.plain("              │ output"),
            TerminalLine.plain(" new     menu │ output"),
            TerminalLine.plain(" agents       │ output"),
            TerminalLine.plain(" codex        │ output"),
        )
        assertTrue(HerdrNavigationChrome.isSpaceCell(screen, 40, 6, 3, 1))
        for ((column, row) in listOf(3 to 0, 3 to 2, 3 to 3, 3 to 4, 3 to 5, 14 to 1, -1 to 1)) {
            assertFalse(HerdrNavigationChrome.isSpaceCell(screen, 40, 6, column, row))
        }
    }

    @Test fun newSpacePickerConsumesLongPressWithoutSendingMouseOrShellInput() {
        val frames = ArrayDeque<android.view.Choreographer.FrameCallback>()
        val scheduler = object : TerminalFrameScheduler {
            override fun postFrame(callback: android.view.Choreographer.FrameCallback) { frames.addLast(callback) }
        }
        val sent = mutableListOf<Any?>()
        val connection = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.yanjiyu.terminalspike.connection.Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isHerdrSession" -> true
                "trySend", "send" -> { sent += args?.firstOrNull(); true }
                "equals" -> false
                "hashCode" -> 1
                else -> null
            }
        } as com.yanjiyu.terminalspike.connection.Connection
        val controller = TerminalController(TerminalBuffer(), scheduler)
        controller.setInputSink(connection, onResize = { _, _ -> })
        controller.reportTerminalSize(40, 6)
        controller.herdrSessionActive = true
        val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(40, 6)
        val output = buildString {
            append("\u001B[?1000;1006h\u001B[H spaces")
            for (row in 1..6) append("\u001B[$row;15H│")
            append("\u001B[4;1H new     menu ")
        }
        controller.updateTerminalFrame(engine.accept(output.toByteArray()))
        while (frames.isNotEmpty()) frames.removeFirst().doFrame(1L)
        assertFalse(controller.showHerdrProjectPickerAt(10, 3))
        assertTrue(controller.showHerdrProjectPickerAt(2, 3))
        org.junit.Assert.assertSame(connection, controller.herdrProjectPicker.value)
        assertTrue(sent.isEmpty())
        controller.resetInputSink()
        org.junit.Assert.assertNull(controller.herdrProjectPicker.value)
        assertFalse(controller.showHerdrProjectPickerAt(2, 3))
    }

    @Test fun closeUsesTheClickedTabsMenuAndRejectsPaneAndSidebar() {
        val frames = ArrayDeque<android.view.Choreographer.FrameCallback>()
        val scheduler = object : TerminalFrameScheduler {
            override fun postFrame(callback: android.view.Choreographer.FrameCallback) { frames.addLast(callback) }
        }
        val sent = mutableListOf<ByteArray>()
        val controller = TerminalController(TerminalBuffer(), scheduler)
        controller.setInputSink(object : TerminalInputSink {
            override fun send(bytes: ByteArray) { sent += bytes }
        }, onResize = { _, _ -> })
        controller.reportTerminalSize(40, 5)
        controller.herdrSessionActive = true
        val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(40, 5)
        val output = buildString {
            append("\u001B[?1000;1006h\u001B[H spaces")
            for (row in 1..5) append("\u001B[$row;8H│")
            append("\u001B[1;9H tab \u001B[38;2;100;150;200m + \u001B[0m")
        }
        controller.updateTerminalFrame(engine.accept(output.toByteArray()))
        while (frames.isNotEmpty()) frames.removeFirst().doFrame(1L)
        assertFalse(controller.closeHerdrTabAt(2, 0))
        assertFalse(controller.closeHerdrTabAt(10, 2))
        assertFalse(controller.closeHerdrTabAt(14, 0))
        assertTrue(sent.isEmpty())
        assertTrue(controller.closeHerdrTabAt(10, 0))
        org.junit.Assert.assertEquals("\u001B[<2;11;1M\u001B[<2;11;1m\u001B[B\u001B[B\r", sent.single().toString(Charsets.US_ASCII))
        controller.herdrSessionActive = false
        assertFalse(controller.closeHerdrTabAt(10, 0))
    }

    @Test fun closeTargetsOnlyTabsBeforeTheAddControlOnEitherEdge() {
        for (tabRow in listOf(0, 4)) {
            val screen = MutableList(5) { TerminalLine.plain("       │ output") }
            screen[0] = TerminalLine.plain(" spaces│ output")
            screen[tabRow] = TerminalLine.styled(listOf(
                TerminalRun(if (tabRow == 0) " spaces│ tab " else "       │ tab ", startColumn = 0, columnWidth = 13),
                TerminalRun(" + ", startColumn = 13, columnWidth = 3),
            ))
            assertTrue(HerdrNavigationChrome.isTabCell(screen, 30, 5, 10, tabRow))
            for ((column, row) in listOf(2 to tabRow, 13 to tabRow, 20 to tabRow, 10 to 2, -1 to tabRow)) {
                assertFalse(HerdrNavigationChrome.isTabCell(screen, 30, 5, column, row))
            }
            screen[2] = TerminalLine.plain("ordinary pane text")
            assertFalse(HerdrNavigationChrome.isTabCell(screen, 30, 5, 10, tabRow))
        }
    }
}
