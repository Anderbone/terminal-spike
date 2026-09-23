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
        override val isHerdrSession = true
        override fun captureHerdrSidebarLayout(): HerdrSidebarLayout? = when (failure) {
            1 -> null
            2 -> error("channel is not opened")
            else -> layout
        }
        override fun captureHerdrHistory(previous: HerdrPaneHistory?, reading: Boolean): HerdrPaneHistory? {
            completedReads.incrementAndGet()
            return null
        }
        override fun send(bytes: ByteArray) { sent += bytes.toString(Charsets.US_ASCII) }
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
