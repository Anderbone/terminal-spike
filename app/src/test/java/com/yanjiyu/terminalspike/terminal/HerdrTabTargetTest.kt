package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrTabTargetTest {
    @Test fun mobileSwipeTargetsOnlyVisibleNavigationAtEveryCorner() {
        for (bottom in listOf(false, true)) for (left in listOf(false, true)) {
            val top = if (bottom) 8 else 0
            val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(40, 10)
            val live = buildString {
                append("\u001B[Hworkspace\u001B[${top + 2};1H")
                append(if (left) " switch  │ tab" else "tab                           │ switch")
            }
            val screen = engine.accept(live.toByteArray()).screen
            org.junit.Assert.assertEquals((if (left) 1 else 31) to (top + 1),
                HerdrNavigationChrome.switcherOpen(screen, 40, 10))
            org.junit.Assert.assertNull(HerdrNavigationChrome.switcherClose(screen, 40, 10))
            val overlay = buildString {
                append("\u001B[2J\u001B[${top + 1};1H")
                append(if (left) " close   │ switch" else " switch                       │ close")
                append("\u001B[${top + 2};1H")
                append(if (left) "    ×    │" else "                              │ ×")
                append("\u001B[${if (bottom) 8 else 3};1H" + "─".repeat(40))
            }
            val covered = engine.accept(overlay.toByteArray()).screen
            org.junit.Assert.assertNull(HerdrNavigationChrome.switcherOpen(covered, 40, 10))
            org.junit.Assert.assertEquals((if (left) 1 else 31) to top,
                HerdrNavigationChrome.switcherClose(covered, 40, 10))
        }
        val plain = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(40, 10)
            .accept("shell output mentions switch and close".toByteArray()).screen
        org.junit.Assert.assertNull(HerdrNavigationChrome.switcherOpen(plain, 40, 10))
        org.junit.Assert.assertNull(HerdrNavigationChrome.switcherClose(plain, 40, 10))
    }

    @Test fun switcherTabTapClosesOverlayBeforeClickingAndLeavesListClicksUntouched() {
        for (bottom in listOf(false, true)) for (left in listOf(false, true)) {
            val frames = ArrayDeque<android.view.Choreographer.FrameCallback>()
            val scheduler = object : TerminalFrameScheduler {
                override fun postFrame(callback: android.view.Choreographer.FrameCallback) { frames.addLast(callback) }
            }
            val sent = mutableListOf<ByteArray>()
            val controller = TerminalController(TerminalBuffer(), scheduler)
            controller.setInputSink(object : TerminalInputSink {
                override fun send(bytes: ByteArray) { sent += bytes }
            }, onResize = { _, _ -> })
            controller.reportTerminalSize(40, 10)
            controller.herdrSessionActive = true
            val top = if (bottom) 8 else 1
            val rule = if (bottom) 7 else 3
            val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(40, 10)
            val output = buildString {
                append("\u001B[?1000;1006h\u001B[H 1  2 \u001B[38;5;245m + \u001B[0m")
                append("\u001B[${top + 1};1H")
                append(if (left) " close   │ switch" else " switch                       │ close")
                append("\u001B[${top + 2};1H")
                append(if (left) "    ×    │" else "                              │ ×")
                append("\u001B[${rule + 1};1H" + "─".repeat(40))
            }
            controller.updateTerminalFrame(engine.accept(output.toByteArray()))
            while (frames.isNotEmpty()) frames.removeFirst().doFrame(1L)
            val closeColumn = if (left) 1 else 31
            val dismiss = "\u001B[<0;${closeColumn + 1};${top + 1}M\u001B[<0;${closeColumn + 1};${top + 1}m"
            for (column in listOf(1, 4, 7)) {
                sent.clear()
                assertTrue(controller.sendMouseClick(column, 0))
                org.junit.Assert.assertEquals(dismiss + "\u001B[<0;${column + 1};1M\u001B[<0;${column + 1};1m",
                    sent.single().toString(Charsets.US_ASCII))
            }
            for ((column, row) in listOf(3 to (if (bottom) 2 else 5), 20 to 0)) {
                sent.clear()
                assertTrue(controller.sendMouseClick(column, row))
                org.junit.Assert.assertEquals("\u001B[<0;${column + 1};${row + 1}M\u001B[<0;${column + 1};${row + 1}m",
                    sent.single().toString(Charsets.US_ASCII))
            }
            sent.clear()
            assertTrue(controller.sendMouseClick(1, 0, secondary = true))
            org.junit.Assert.assertEquals("\u001B[<2;2;1M\u001B[<2;2;1m", sent.single().toString(Charsets.US_ASCII))
        }
    }

    @Test fun mobileSwitchHeadersAllowDividersInTheStatusText() {
        for (bottom in listOf(false, true)) {
            for (left in listOf(false, true)) {
                val screen = MutableList(8) { TerminalLine.plain("output") }
                screen[if (bottom) 7 else 1] = TerminalLine.plain(
                    if (left) "switch │ project │ task" else "project │ task                │ switch",
                )
                val area = requireNotNull(HerdrNavigationChrome.outputArea(screen, 40, 8))
                org.junit.Assert.assertEquals(if (bottom) 0 else 2, area.y)
                org.junit.Assert.assertEquals(6, area.rows)
            }
        }
    }

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

    @Test fun mobileNewWorkspaceRequiresTheVisibleSwitcherAndExactAction() {
        val screen = listOf(
            TerminalLine.plain(" switch             │"),
            TerminalLine.plain("                    │ close"),
            TerminalLine.plain("──────────────────────────────"),
            TerminalLine.plain(" spaces"),
            TerminalLine.plain("  + new workspace"),
            TerminalLine.plain("  project"),
        )
        for (column in 2..16) assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 30, 6, column, 4))
        for ((column, row) in listOf(0 to 4, 17 to 4, 30 to 4, 3 to 3, 3 to 5)) {
            assertFalse(HerdrNavigationChrome.isNewSpaceCell(screen, 30, 6, column, row))
        }
        val scrolled = screen.toMutableList().apply { this[4] = TerminalLine.plain("┃  + new workspace") }
        assertTrue(HerdrNavigationChrome.isNewSpaceCell(scrolled, 30, 6, 4, 4))
        assertFalse(HerdrNavigationChrome.isNewSpaceCell(scrolled, 30, 6, 0, 4))
        for (changedRow in 0..2) {
            val output = screen.toMutableList().apply { this[changedRow] = TerminalLine.plain("output") }
            assertFalse(HerdrNavigationChrome.isNewSpaceCell(output, 30, 6, 4, 4))
        }
    }

    @Test fun groupedSidebarNewWorkspaceRequiresVerifiedChromeAndExcludesOtherTargets() {
        for (bottomTabs in listOf(false, true)) {
            for (header in listOf("+ new workspace", "workspace", "agents · active")) {
                val screen = MutableList(8) { TerminalLine.plain("┃ project                 pane output") }
                screen[0] = TerminalLine.styled(listOf(
                    TerminalRun("┃ $header", startColumn = 0, columnWidth = header.length + 2),
                    TerminalRun("«", startColumn = 25, columnWidth = 1),
                ))
                val tabRow = if (bottomTabs) 7 else 0
                screen[tabRow] = TerminalLine.styled(screen[tabRow].runs + listOf(
                    TerminalRun(" tab ", startColumn = 26, columnWidth = 5),
                    TerminalRun(" + ", startColumn = 32, columnWidth = 3),
                ))
                screen[2] = TerminalLine.plain("┃ + new workspace         + new workspace")
                screen[3] = TerminalLine.plain("┃ + new tab")
                screen[4] = TerminalLine.plain("┃ + new workspace extra")
                for (column in 2..16) {
                    assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 60, 8, column, 2))
                }
                if (header == "+ new workspace") {
                    assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 60, 8, 5, 0))
                }
                for ((column, row) in listOf(0 to 2, 1 to 2, 17 to 2, 25 to 2, 30 to 2, 5 to 3, 5 to 4)) {
                    assertFalse(HerdrNavigationChrome.isNewSpaceCell(screen, 60, 8, column, row))
                }
                val noTabs = screen.toMutableList().apply {
                    this[tabRow] = TerminalLine.styled(this[tabRow].runs.filter { it.startColumn < 26 })
                }
                assertFalse(HerdrNavigationChrome.isNewSpaceCell(noTabs, 60, 8, 5, 2))
                val noCollapse = screen.toMutableList().apply {
                    this[0] = TerminalLine.styled(this[0].runs.filter { it.text != "«" })
                }
                assertFalse(HerdrNavigationChrome.isNewSpaceCell(noCollapse, 60, 8, 5, 2))
            }
        }
    }

    @Test fun mobileSwitcherRecognizesAllCloseCornersAndKeepsControlsOutOfTheBody() {
        for (bottom in listOf(false, true)) {
            for (left in listOf(false, true)) {
                val top = if (bottom) 6 else 0
                val rule = if (bottom) 5 else 2
                val actionRow = if (bottom) 0 else 3
                val screen = MutableList(8) { TerminalLine.plain("") }
                screen[top] = TerminalLine.plain(if (left) " close   │ switch" else " switch                       │ close")
                screen[top + 1] = TerminalLine.plain(if (left) "    ×    │" else "                              │ ×")
                screen[rule] = TerminalLine.plain("─".repeat(40))
                screen[actionRow] = TerminalLine.plain("│  + new workspace")
                org.junit.Assert.assertEquals((if (left) 1 else 31) to top,
                    HerdrNavigationChrome.switcherClose(screen, 40, 8))
                assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 40, 8, 4, actionRow))
                assertTrue(HerdrNavigationChrome.isContextMenuCell(screen, 40, 8, 4, actionRow))
                for (row in listOf(top, top + 1, rule)) {
                    assertFalse(HerdrNavigationChrome.isContextMenuCell(screen, 40, 8, 4, row))
                    assertFalse(HerdrNavigationChrome.isNewSpaceCell(screen, 40, 8, 4, row))
                }
                org.junit.Assert.assertNull(HerdrNavigationChrome.outputArea(screen, 40, 8))
            }
        }
    }

    @Test fun mobileTopTabTargetsRequireLiveChromeAndExcludePaneAndNewTab() {
        for (bottom in listOf(false, true)) {
            val screen = MutableList(8) { TerminalLine.plain("output") }
            screen[0] = TerminalLine.styled(listOf(TerminalRun(" tab one ", startColumn = 0, columnWidth = 9),
                TerminalRun(" + ", startColumn = 15, columnWidth = 3)))
            screen[if (bottom) 7 else 2] = TerminalLine.plain("switch │ status")
            assertTrue(HerdrNavigationChrome.isTabCell(screen, 40, 8, 3, 0))
            assertTrue(HerdrNavigationChrome.isContextMenuCell(screen, 40, 8, 3, 0))
            for ((column, row) in listOf(16 to 0, 3 to 3, 3 to 7)) {
                assertFalse(HerdrNavigationChrome.isTabCell(screen, 40, 8, column, row))
            }
            screen[if (bottom) 7 else 2] = TerminalLine.plain("close │ status")
            assertFalse(HerdrNavigationChrome.isTabCell(screen, 40, 8, 3, 0))
        }
    }

    @Test fun mobileSwitcherWithTopTabsKeepsTabBarAndControlsOutOfListTargets() {
        for (bottom in listOf(false, true)) {
            val screen = MutableList(10) { TerminalLine.plain("") }
            screen[0] = TerminalLine.styled(listOf(TerminalRun(" tab ", startColumn = 0, columnWidth = 5),
                TerminalRun(" + ", startColumn = 15, columnWidth = 3)))
            val top = if (bottom) 8 else 1
            val rule = if (bottom) 7 else 3
            val actionRow = if (bottom) 1 else 4
            screen[top] = TerminalLine.plain(" close   │ switch")
            screen[top + 1] = TerminalLine.plain("    ×    │")
            screen[rule] = TerminalLine.plain("─".repeat(40))
            screen[actionRow] = TerminalLine.plain("│  + new workspace")
            assertTrue(HerdrNavigationChrome.isNewSpaceCell(screen, 40, 10, 4, actionRow))
            assertTrue(HerdrNavigationChrome.isContextMenuCell(screen, 40, 10, 4, actionRow))
            for (row in listOf(0, top, top + 1, rule)) {
                assertFalse(HerdrNavigationChrome.isContextMenuCell(screen, 40, 10, 4, row))
            }
            org.junit.Assert.assertNull(HerdrNavigationChrome.outputArea(screen, 40, 10))
        }
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
                "trySend", "send", "sendWithAcceptance" -> { sent += args?.firstOrNull(); true }
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
        controller.dismissHerdrProjectPicker()
        val mobile = "\u001B[2J\u001B[H switch\u001B[1;31H│\u001B[2;31H│ close\u001B[3;1H" +
            "─".repeat(40) + "\u001B[4;1H spaces\u001B[5;1H  + new workspace"
        controller.updateTerminalFrame(engine.accept(mobile.toByteArray()))
        while (frames.isNotEmpty()) frames.removeFirst().doFrame(1L)
        assertTrue(controller.showHerdrProjectPickerAt(5, 4))
        org.junit.Assert.assertSame(connection, controller.herdrProjectPicker.value)
        assertTrue("Opening recent projects must not send a remote click", sent.isEmpty())
        controller.completeHerdrProjectSelection(connection)
        org.junit.Assert.assertNull(controller.herdrProjectPicker.value)
        assertTrue("Successful selection closes the mobile Switch panel", sent.isNotEmpty())
        sent.clear()
        val grouped = "\u001B[2J\u001B[H┃ + new workspace\u001B[1;26H\u001B[38;5;244m«\u001B[0m" +
            "\u001B[1;27H tab \u001B[38;5;245m + \u001B[0m"
        controller.updateTerminalFrame(engine.accept(grouped.toByteArray()))
        while (frames.isNotEmpty()) frames.removeFirst().doFrame(1L)
        assertFalse(controller.showHerdrProjectPickerAt(28, 0))
        assertTrue(controller.showHerdrProjectPickerAt(5, 0))
        org.junit.Assert.assertSame(connection, controller.herdrProjectPicker.value)
        assertTrue("Grouped sidebar picker must not send a remote click", sent.isEmpty())
        controller.completeHerdrProjectSelection(connection)
        assertTrue("Desktop sidebar stays expanded", sent.isEmpty())
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
