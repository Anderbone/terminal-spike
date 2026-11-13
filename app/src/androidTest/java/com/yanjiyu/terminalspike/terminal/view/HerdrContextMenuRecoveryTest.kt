package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.DefaultSshSessionTerminalFactory
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.terminal.TerminalController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production metadata refresh and real long-press dispatch on the old USB phone. */
class HerdrContextMenuRecoveryTest {
    @Test fun clientOwnedDesktopMenusIgnoreServerPaneCoordinates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel().apply { isHerdrSession = false }
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalColumns > 35 && controller.terminalRows > 10 }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                // Fold RED: outer grid 88x31, API reports only a 62x30 surface.
                // Do not fabricate the old server-owned x=26/y=1 metadata.
                connection.layout = HerdrSidebarLayout(0, controller.terminalColumns - 26, 0, controller.terminalRows - 1)
                // Production attaches before the user selects Herdr. The first
                // terminal frame must pick up that completed startup choice.
                connection.isHerdrSession = true
                for (tabRow in listOf(0, controller.terminalRows - 1)) {
                    scenario.onActivity {
                        val output = buildString {
                            append("\u001B[?1000;1006h\u001B[2J\u001B[H spaces")
                            for (row in 1..controller.terminalRows) append("\u001B[$row;26H│")
                            append("\u001B[${controller.terminalRows / 2};2Hagents")
                            append("\u001B[${tabRow + 1};27H tab \u001B[38;2;100;150;200m + \u001B[0m")
                        }
                        terminal.accept(output.toByteArray(), {})
                    }
                    await { controller.isMouseTrackingEnabled() && controller.isHerdrContextMenuCell(30, tabRow) }
                    assertNull("Server pane coordinates must not masquerade as outer navigation", controller.herdrSidebarLayout.value)
                    for (direct in listOf(true, false)) {
                        scenario.onActivity { view.setDirectInputEnabled(direct) }
                        for (failure in listOf(0, 1, 2)) {
                            connection.failure = failure
                            val reads = connection.completedReads.get()
                            await { connection.completedReads.get() > reads }
                            assertMenu(scenario, view, controller, connection, 30, tabRow)
                            assertMenu(scenario, view, controller, connection, 4, 4)
                            assertMenu(scenario, view, controller, connection, 4, controller.terminalRows - 3)
                            assertFalse(controller.isHerdrContextMenuCell(40, 5))
                        }
                    }
                }
                scenario.onActivity { terminal.detach() }
                assertFalse("Detached streams cannot retain client navigation", controller.isHerdrContextMenuCell(4, 4))
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun tabsAndSpacesKeepTheirMenusWhenTheSideChannelFails() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalRows > 8 }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val layout = HerdrSidebarLayout(4, controller.terminalColumns, 1, controller.terminalRows - 1)
                connection.layout = layout
                scenario.onActivity {
                    terminal.accept("\u001B[?1000;1006hhello".toByteArray(), {})
                }
                await { controller.herdrSidebarLayout.value == layout && controller.isMouseTrackingEnabled() }

                fun checkMenus() {
                    assertMenu(scenario, view, controller, connection, 6, 0)
                    assertMenu(scenario, view, controller, connection, 1, 3)
                }

                fun checkMenusWithStaleRefresh() {
                    checkMenus()
                    val before = connection.completedReads.get()
                    connection.failure = 0
                    await { connection.completedReads.get() >= before + 2 }
                    checkMenus()
                    connection.failure = 2
                }

                checkMenus()
                // The live Mosh terminal keeps working while the auxiliary SSH read returns null
                // or throws. Wait for completed production refreshes, not merely a fake callback.
                for (failure in listOf(1, 2)) {
                    val before = connection.completedReads.get()
                    connection.failure = failure
                    await { connection.completedReads.get() >= before + 2 }
                    checkMenus()
                }
                // IME open/close changes height while the same Mosh stream stays connected.
                // Keep SSH unavailable throughout both native-view resizes.
                val fullHeight = view.height
                for (height in listOf(fullHeight / 2, fullHeight)) {
                    val previousRows = controller.terminalRows
                    scenario.onActivity {
                        view.layoutParams = view.layoutParams.apply { this.height = height }
                    }
                    await { controller.terminalRows != previousRows }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    // A delayed reply for the old height must not overwrite the adjusted cache.
                    checkMenusWithStaleRefresh()
                }
                // Font changes alter both dimensions. Widening a verified desktop layout
                // and returning to its original width must not need a working SSH channel.
                val originalFontSize = controller.fontSizeSp
                for (fontSize in listOf(originalFontSize - 2f, originalFontSize)) {
                    val previousColumns = controller.terminalColumns
                    scenario.onActivity { controller.fontSizeSp = fontSize }
                    await { controller.terminalColumns != previousColumns }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    checkMenusWithStaleRefresh()
                }
                // A successful new layout must replace the cached one (including hidden tabs).
                val hidden = HerdrSidebarLayout(0, controller.terminalColumns, 0, controller.terminalRows)
                connection.layout = hidden
                connection.failure = 0
                await { controller.herdrSidebarLayout.value == hidden }
                assertFalse(hidden.isContextMenuCell(6, 0, controller.terminalColumns, controller.terminalRows))
                scenario.onActivity { terminal.detach() }
                assertNull("A detached session must not retain navigation targets", controller.herdrSidebarLayout.value)
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun topAndBottomTabMenusSurviveRepeatedResizesAndViewReplacement() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
            val smallWidth = metrics.widthPixels * 3 / 4
            val smallHeight = metrics.heightPixels / 2
            val largeWidth = metrics.widthPixels * 9 / 10
            val largeHeight = metrics.heightPixels * 2 / 3

            fun replaceView(width: Int, height: Int) = scenario.onActivity { activity ->
                view = FastTerminalView(activity)
                val container = FrameLayout(activity)
                container.addView(view, FrameLayout.LayoutParams(width, height))
                activity.setContentView(container)
                view.attachController(controller)
            }

            try {
                scenario.onActivity { terminal.attach(connection) }
                replaceView(smallWidth, smallHeight)
                await { connection.resizeReports.get() > 0 && controller.terminalRows > 8 }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { terminal.accept("\u001B[?1000;1006hhello".toByteArray(), {}) }
                await { controller.isMouseTrackingEnabled() }
                for (top in listOf(1, 0)) {
                    for (sidebar in listOf(4, 0)) {
                        connection.layout = HerdrSidebarLayout(
                            sidebar, controller.terminalColumns, top, controller.terminalRows - 1,
                        )
                        connection.failure = 0
                        await { controller.herdrSidebarLayout.value == connection.layout }
                        // Exercise the first hold after each resize, without waiting for new
                        // metadata. Alternate null, throwing, and stale successful replies.
                        for (failure in listOf(1, 2, 0)) {
                            connection.failure = failure
                            val reads = connection.completedReads.get()
                            await { connection.completedReads.get() > reads }
                            for ((width, height) in listOf(largeWidth to largeHeight, smallWidth to smallHeight)) {
                                val columns = controller.terminalColumns
                                scenario.onActivity {
                                    view.layoutParams = FrameLayout.LayoutParams(width, height)
                                }
                                await { controller.terminalColumns != columns }
                                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                                val tabRow = if (top == 1) 0 else controller.terminalRows - 1
                                assertMenu(scenario, view, controller, connection, 6, tabRow)
                                if (sidebar > 0) assertMenu(scenario, view, controller, connection, 1, 3)
                            }
                        }
                        // Replacing the native view must preserve the connection's menu
                        // cache, even when the side channel has stopped answering again.
                        connection.failure = 2
                        replaceView(smallWidth, smallHeight)
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        assertMenu(scenario, view, controller, connection, 6, if (top == 1) 0 else controller.terminalRows - 1)
                    }
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun desktopMenusRecoverFromMobileAndOtherClientHeightInBothInputModes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
            val desktopWidth = metrics.widthPixels * 9 / 10
            val mobileWidth = metrics.widthPixels / 2
            val height = metrics.heightPixels * 2 / 3
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    val container = FrameLayout(activity)
                    container.addView(view, FrameLayout.LayoutParams(desktopWidth, height))
                    activity.setContentView(container)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalRows > 8 }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { terminal.accept("\u001B[?1000;1006hhello".toByteArray(), {}) }
                await { controller.isMouseTrackingEnabled() }
                // pane layout may describe the last desktop client with its keyboard open,
                // rather than this phone's height. Top tabs and sidebar still belong here.
                connection.layout = HerdrSidebarLayout(4, controller.terminalColumns, 1, controller.terminalRows / 2)
                await { controller.herdrSidebarLayout.value?.terminalColumns == controller.terminalColumns }
                val desktopColumns = controller.terminalColumns
                for (direct in listOf(true, false, true)) {
                    scenario.onActivity { view.setDirectInputEnabled(direct) }
                    assertMenu(scenario, view, controller, connection, 6, 0)
                    assertMenu(scenario, view, controller, connection, 1, 3)
                    scenario.onActivity { view.layoutParams = FrameLayout.LayoutParams(mobileWidth, height) }
                    await { controller.terminalColumns != desktopColumns }
                    connection.layout = HerdrSidebarLayout(0, controller.terminalColumns, 2, controller.terminalRows - 2)
                    await { controller.herdrSidebarLayout.value == connection.layout }
                    connection.failure = 2
                    scenario.onActivity { view.layoutParams = FrameLayout.LayoutParams(desktopWidth, height) }
                    await { controller.terminalColumns == desktopColumns }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertMenu(scenario, view, controller, connection, 6, 0)
                    assertMenu(scenario, view, controller, connection, 1, 3)
                    if (!direct) scenario.onActivity {
                        assertFalse("Buffered typing must keep ownership of keyboard input", view.onCheckIsTextEditor())
                        assertFalse(view.hasFocus())
                    }
                    connection.failure = 0
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun mobileSwitcherLongPressUsesRemoteMenuInBothInputModes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalRows > 8 }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                // Older servers include the mobile header in their outer coordinates;
                // 0.9.1 reports a client-owned pane surface whose origin is zero.
                for (top in listOf(2, 0)) {
                    connection.layout = HerdrSidebarLayout(0, controller.terminalColumns, top, controller.terminalRows - 2)
                    await { controller.herdrSidebarLayout.value == connection.layout }
                    for (direct in listOf(true, false)) {
                        scenario.onActivity {
                            view.setDirectInputEnabled(direct)
                            terminal.accept("\u001B[?1000;1006h\u001B[2;${controller.terminalColumns - 9}H│    ×    ".toByteArray(), {})
                        }
                        await { controller.isHerdrContextMenuCell(6, 4) }
                        assertMenu(scenario, view, controller, connection, 6, 4)
                        connection.failure = 2
                        assertMenu(scenario, view, controller, connection, 6, 7)
                        scenario.onActivity {
                            terminal.accept("\u001B[2;${controller.terminalColumns - 9}H│ switch  ".toByteArray(), {})
                        }
                        await { !controller.isHerdrContextMenuCell(6, 4) }
                        connection.failure = 0
                    }
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun recentProjectsLongPressHandlesRealMobileScrollThumbInBothInputModes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalColumns > 30 && controller.terminalRows > 12 }
                for (direct in listOf(true, false)) for (action in listOf("+", "+ new workspace")) {
                    scenario.onActivity {
                        view.setDirectInputEnabled(direct)
                        val cols = controller.terminalColumns
                        val rows = controller.terminalRows
                        // Screenshot layout: top tabs, usage above Spaces, a half-block
                        // scrollbar thumb and a bottom-left button with hidden dividers.
                        val output = "\u001B[?1000;1006h\u001B[2J\u001B[H1  2  3 \u001B[38;5;245m + \u001B[0m" +
                            "\u001B[2;1H▌usage · used\u001B[5;1H▌spaces\u001B[6;1H▌$action" +
                            "\u001B[${rows - 2};1H" + "─".repeat(cols) +
                            "\u001B[${rows - 1};1H\u001B[44m  close  \u001B[8m│\u001B[0m switch" +
                            "\u001B[${rows};1H\u001B[44m     ×   \u001B[8m│\u001B[0m"
                        terminal.accept(output.toByteArray(), {})
                    }
                    await { controller.isMouseTrackingEnabled() && controller.isHerdrContextMenuCell(1, 5) }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    connection.sent.clear()
                    val now = SystemClock.uptimeMillis()
                    fun event(actionType: Int) = scenario.onActivity {
                        val density = view.resources.displayMetrics.density
                        val motion = MotionEvent.obtain(now, SystemClock.uptimeMillis(), actionType,
                            8f * density + 1.5f * view.terminalCellWidthPx,
                            5f * density + 5.5f * controller.viewport.lineHeightPx, 0)
                        try { view.onTouchEvent(motion) } finally { motion.recycle() }
                    }
                    event(MotionEvent.ACTION_DOWN)
                    SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    event(MotionEvent.ACTION_UP)
                    scenario.onActivity {
                        assertSame("Real long press must open the local picker: direct=$direct action=$action",
                            connection, controller.herdrProjectPicker.value)
                        assertTrue("No remote click, wheel or shell input", connection.sent.isEmpty())
                        assertNull(view.selectedTextForTesting())
                        controller.dismissHerdrProjectPicker()
                    }
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun groupedAndMobileSpaceDoubleTapClosesOnlyTheClickedWorkspaceInBothInputModes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = FailingSideChannel()
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                }
                await { connection.resizeReports.get() > 0 && controller.terminalColumns > 30 && controller.terminalRows > 12 }
                for (mobile in listOf(true, false)) for (direct in listOf(true, false)) {
                    var switcherFrame = ""
                    scenario.onActivity {
                        view.setDirectInputEnabled(direct)
                        val cols = controller.terminalColumns
                        val rows = controller.terminalRows
                        val chrome = if (mobile) {
                            "\u001B[H1 \u001B[38;5;245m + \u001B[0m" +
                                "\u001B[${rows - 2};1H" + "─".repeat(cols) +
                                "\u001B[${rows - 1};1H  close  \u001B[8m│\u001B[0m switch" +
                                "\u001B[${rows};1H     ×   \u001B[8m│\u001B[0m"
                        } else {
                            "\u001B[1;${cols / 2 + 1}H\u001B[38;5;244m«\u001B[0m\u001B[1;${cols / 2 + 2}Htab \u001B[38;5;245m + \u001B[0m"
                        }
                        switcherFrame = "\u001B[?1000;1006h\u001B[2J" + chrome +
                            "\u001B[4;1H▌\u001B[1;4mspaces\u001B[0m\u001B[5;1H▌\u001B[1m+\u001B[0m" +
                            "\u001B[6;1H▌\u001B[1mclicked space\u001B[0m\u001B[7;1H▌○ 1 task"
                        // A real first left-click opens the Space and removes the list.
                        // Reproduce that transition rather than freezing a fake screen.
                        connection.onSend = { bytes ->
                            if (bytes.startsWith("\u001B[<0;")) {
                                terminal.accept("\u001B[2J\u001B[Hopened workspace pane".toByteArray(), {})
                            }
                        }
                        terminal.accept(switcherFrame.toByteArray(), {})
                    }
                    await { controller.isMouseTrackingEnabled() && controller.isHerdrContextMenuCell(2, 5) }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    connection.sent.clear()
                    fun tap(row: Int = 5) {
                        val now = SystemClock.uptimeMillis()
                        fun event(action: Int) = scenario.onActivity {
                            val density = view.resources.displayMetrics.density
                            val motion = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                                8f * density + 2.5f * view.terminalCellWidthPx,
                                5f * density + (row + 0.5f) * controller.viewport.lineHeightPx, 0)
                            try { view.onTouchEvent(motion) } finally { motion.recycle() }
                        }
                        event(MotionEvent.ACTION_DOWN)
                        SystemClock.sleep(45L)
                        event(MotionEvent.ACTION_UP)
                    }
                    tap()
                    assertTrue("First Space tap must wait so it cannot open and remove the list", connection.sent.isEmpty())
                    SystemClock.sleep(60L)
                    tap()
                    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50L)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals("Double tap closes without first opening the Space: mobile=$mobile direct=$direct",
                        listOf("\u001B[<2;3;6M\u001B[<2;3;6m\u001B[B\r"), connection.sent)
                    scenario.onActivity { assertNull(view.selectedTextForTesting()) }

                    scenario.onActivity {
                        terminal.accept(switcherFrame.toByteArray(), {})
                        connection.sent.clear()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    tap()
                    assertTrue("A single Space tap also waits for double-tap confirmation", connection.sent.isEmpty())
                    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100L)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    assertEquals("Confirmed single tap opens exactly once",
                        listOf("\u001B[<0;3;6M\u001B[<0;3;6m"), connection.sent)

                    scenario.onActivity {
                        terminal.accept(switcherFrame.toByteArray(), {})
                        connection.sent.clear()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    tap(row = 6)
                    assertEquals("Task rows retain immediate single taps",
                        listOf("\u001B[<0;3;7M\u001B[<0;3;7m"), connection.sent)
                    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50L)

                    scenario.onActivity {
                        terminal.accept(switcherFrame.toByteArray(), {})
                        connection.sent.clear()
                    }
                    await { controller.isHerdrContextMenuCell(2, 5) }
                    tap()
                    scenario.onActivity { activity -> activity.setContentView(FrameLayout(activity)) }
                    SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 100L)
                    assertTrue("Leaving the terminal discards a pending Space tap", connection.sent.isEmpty())
                    scenario.onActivity { activity -> activity.setContentView(view) }
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    private fun assertMenu(
        scenario: ActivityScenario<MainActivity>,
        view: FastTerminalView,
        controller: TerminalController,
        connection: FailingSideChannel,
        column: Int,
        row: Int,
    ) {
        connection.sent.clear()
        val now = SystemClock.uptimeMillis()
        fun event(action: Int) = scenario.onActivity {
            val density = view.resources.displayMetrics.density
            val motion = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                8f * density + (column + 0.5f) * view.terminalCellWidthPx,
                5f * density + (row + 0.5f) * controller.viewport.lineHeightPx, 0)
            try { view.onTouchEvent(motion) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        event(MotionEvent.ACTION_UP)
        assertEquals("Long press must send only the menu click, including after failed refreshes",
            listOf("\u001B[<2;${column + 1};${row + 1}M\u001B[<2;${column + 1};${row + 1}m"), connection.sent)
        scenario.onActivity { assertNull(view.selectedTextForTesting()) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
        assertTrue("Timed out waiting for terminal refresh", condition())
    }

    private class FailingSideChannel : Connection {
        @Volatile var layout: HerdrSidebarLayout? = null
        @Volatile var failure = 0
        val completedReads = AtomicInteger()
        val resizeReports = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        var onSend: ((String) -> Unit)? = null
        @Volatile override var isHerdrSession = true
        override fun captureHerdrSidebarLayout(): HerdrSidebarLayout? = when (failure) {
            1 -> null
            2 -> error("channel is not opened")
            else -> layout
        }
        override fun captureHerdrHistory(previous: HerdrPaneHistory?, reading: Boolean): HerdrPaneHistory? {
            completedReads.incrementAndGet()
            return null
        }
        override fun send(bytes: ByteArray) {
            val text = bytes.toString(Charsets.US_ASCII)
            sent += text
            onSend?.invoke(text)
        }
        override fun trySend(bytes: ByteArray): Boolean { send(bytes); return true }
        override suspend fun connect(columns: Int, rows: Int, onBytes: (ByteArray) -> Unit, onState: (ConnectionState) -> Unit) = Unit
        override fun resize(columns: Int, rows: Int) { resizeReports.incrementAndGet() }
        override fun answerHostIdentityPrompt(promptToken: Long, decision: HostIdentityDecision) = Unit
        override fun answerKeyboardInteractiveChallenge(challengeToken: Long, responses: List<CharArray>) = Unit
        override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit
        override fun cancelPendingPrompts() = Unit
        override fun close() = Unit
    }
}
