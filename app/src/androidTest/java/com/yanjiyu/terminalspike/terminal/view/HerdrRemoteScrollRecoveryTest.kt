package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.DefaultSshSessionTerminalFactory
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.connection.HerdrStartupChoice
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.TmuxExecOutput
import com.yanjiyu.terminalspike.connection.captureHerdrPaneHistory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Production capture/routing regression with a fake transport, not real Mosh/Codex acceptance. */
class HerdrRemoteScrollRecoveryTest {
    @Test fun wrappedOldConversationRetainsNativeMotionAndEveryNewestMarker() =
        verifyNativeRecovery("w6:pC", wrappedHistory = true)

    @Test fun groupedDesktopSidebarKeepsHistoryLocalAndNavigationPixelsFixed() =
        verifyNativeRecovery("w6:pC", clientOwnedDesktop = true, groupedSidebar = true)

    @Test fun slowMetadataDoesNotBlockTheFirstHistoryOrItsNewerOutput() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val metadataEntered = CountDownLatch(1)
            val releaseMetadata = CountDownLatch(1)
            val base = ScrolledHerdr("w6:pC")
            val connection = object : Connection by base {
                override fun captureInputContext(includeCodexStatus: Boolean): com.yanjiyu.terminalspike.terminal.TerminalInputContext {
                    metadataEntered.countDown()
                    check(releaseMetadata.await(15, TimeUnit.SECONDS))
                    return base.captureInputContext()
                }
            }
            try {
                scenario.onActivity {
                    terminal.attach(connection)
                    terminal.accept("LIVE".toByteArray(), {})
                }
                assertTrue(metadataEntered.await(5, TimeUnit.SECONDS))
                await { controller.herdrHistory?.lines?.lastOrNull()?.text == "INPUT_READY" }
                base.latestInput = "NEWER_OUTPUT"
                scenario.onActivity { terminal.accept("new output".toByteArray(), {}) }
                await { controller.herdrHistory?.lines?.lastOrNull()?.text == "NEWER_OUTPUT" }
                assertEquals("The metadata request is still blocked", 1L, releaseMetadata.count)
            } finally {
                releaseMetadata.countDown()
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    @Test fun pinnedHistoryObsidianLinksOpenLocallyInBothInputModes() =
        verifyNativeRecovery("w6:pC", historyLinks = true)

    @Test fun firstNativeGestureSurvivesAnOlderIdleCaptureFailure() =
        verifyNativeRecovery("w6:pC", lateIdleFailure = true)

    @Test fun clientOwnedDesktopKeepsSidebarFixedAndRightEdgeNative() =
        verifyNativeRecovery("w6:pC", clientOwnedDesktop = true)

    @Test fun remotelyScrolledPaneKeepsNativeMotionAndLatestInput() = verifyNativeRecovery("w1:p1")

    @Test fun letterPaneNumberKeepsNativeMotionAndLatestInput() = verifyNativeRecovery("w6:pC")

    @Test fun piPaneKeepsNativeMotionAndLatestInput() = verifyNativeRecovery("wK:p4", agent = "pi")

    @Test fun screenOnlyCodexPaneReceivesOlderAndNewerScrollGestures() = verifyScreenOnlyGestures("codex")

    @Test fun fullscreenPiPaneReceivesPageGesturesInBothInputModes() = verifyScreenOnlyGestures("pi")

    @Test fun fullscreenPiExplicitRemoteKeepsWheelRouting() = verifyScreenOnlyGestures("pi", remote = true)

    private fun verifyScreenOnlyGestures(agent: String, remote: Boolean = false) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = ScrolledHerdr("w6:pF", screenOnly = true, agent = agent)
            if (remote) controller.updateRendererProfile(controller.rendererProfile.copy(
                touchScrollMode = com.yanjiyu.terminalspike.core.model.TouchScrollMode.REMOTE_MOUSE,
            ))
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                    terminal.accept("\u001B[?1000;1006hLIVE".toByteArray(), {})
                }
                await { connection.reads.get() >= 2 && controller.isMouseTrackingEnabled() &&
                    (agent != "pi" || controller.inputContext.value.herdrPiScrollPane != null) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                for (directInput in listOf(true, false)) {
                    scenario.onActivity { view.setDirectInputEnabled(directInput) }
                    for (older in listOf(true, false)) {
                        connection.sent.clear()
                        val now = SystemClock.uptimeMillis()
                        val start = if (older) 4f else 12f
                        val end = if (older) 12f else 4f
                        for ((action, row, elapsed) in listOf(
                            Triple(MotionEvent.ACTION_DOWN, start, 0L),
                            Triple(MotionEvent.ACTION_MOVE, (start + end) / 2, 80L),
                            Triple(MotionEvent.ACTION_MOVE, end, 160L),
                            Triple(MotionEvent.ACTION_UP, end, 320L),
                        )) scenario.onActivity {
                            val event = MotionEvent.obtain(now, now + elapsed, action,
                                view.width / 2f, row * controller.viewport.lineHeightPx, 0)
                            try { view.onTouchEvent(event) } finally { event.recycle() }
                        }
                        val button = if (older) 64 else 65
                        assertTrue("Live $agent must receive the $button wheel gesture",
                            connection.sent.any {
                                if (agent == "pi" && !remote) it == (if (older) "\u001B[5~" else "\u001B[6~")
                                else it.startsWith("\u001B[<$button;")
                            })
                        if (agent == "pi" && !remote) assertEquals(
                            "One classified swipe pages once, including release",
                            listOf(if (older) "\u001B[5~" else "\u001B[6~"), connection.sent.toList(),
                        )
                        scenario.onActivity {
                            assertNull(controller.herdrHistory)
                            assertFalse(controller.herdrHistoryReading)
                        }
                    }
                }
            } finally {
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    private fun verifyNativeRecovery(
        paneId: String,
        agent: String = "codex",
        clientOwnedDesktop: Boolean = false,
        lateIdleFailure: Boolean = false,
        historyLinks: Boolean = false,
        wrappedHistory: Boolean = false,
        groupedSidebar: Boolean = false,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = ScrolledHerdr(paneId, agent = agent, clientOwnedDesktop = clientOwnedDesktop,
                wrappedHistory = wrappedHistory)
            val historyUri = "obsidian://open?vault=Obsjiyu&file=notes%2Fhistory"
            if (historyLinks) connection.historyMarkup = "\u001B]8;;$historyUri\u001B\\note\u001B]8;;\u001B\\"
            val openedLinks = CopyOnWriteArrayList<String>()
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    // This fixture models a desktop sidebar plus an output pane. A
                    // narrow, high-density phone still needs enough cells for both.
                    if (clientOwnedDesktop) controller.fontSizeSp = 10f
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                    if (historyLinks) view.setLinkActionCallback { openedLinks += it.target.uri }
                    terminal.accept("\u001B[?1000;1006hLIVE".toByteArray(), {})
                }
                await { connection.reads.get() >= 2 && controller.isMouseTrackingEnabled() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { connection.resize(controller.terminalColumns, controller.terminalRows) }
                var sidebarBefore: IntArray? = null
                fun sidebarPixels(): IntArray {
                    val density = view.resources.displayMetrics.density
                    val sidebarWidth = (8f * density + 26 * view.terminalCellWidthPx).toInt()
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    return try {
                        view.draw(Canvas(bitmap))
                        IntArray(sidebarWidth * view.height).also {
                            bitmap.getPixels(it, 0, sidebarWidth, 0, 0, sidebarWidth, view.height)
                        }
                    } finally { bitmap.recycle() }
                }
                if (clientOwnedDesktop) {
                    scenario.onActivity {
                        terminal.accept(buildString {
                            append("\u001B[?25l\u001B[2J\u001B[H spaces")
                            if (groupedSidebar) append("\u001B[1;26H\u001B[38;5;244m«\u001B[0m")
                            else for (row in 1..controller.terminalRows) append("\u001B[$row;26H│")
                            append("\u001B[1;27H tab \u001B[32m + \u001B[0m")
                            append("\u001B[5;2Hagents")
                        }.toByteArray(), {})
                    }
                    try { await { controller.herdrHistoryForDisplay()?.x == 26 } } catch (error: AssertionError) {
                        val h = controller.herdrHistory
                        android.util.Log.i("HerdrFrameClock", "desktop grid=${controller.terminalColumns}x${controller.terminalRows} view=${view.width}x${view.height} source=${h?.columns}x${h?.rows} projected=${controller.herdrHistoryForDisplay()?.x} cell=${view.terminalCellWidthPx}")
                        throw error
                    }
                    scenario.onActivity { sidebarBefore = sidebarPixels() }
                }
                fun gestureX() = if (clientOwnedDesktop) {
                    // The fold RED used remote wheels here while the left sidebar
                    // incorrectly acquired the server-origin native history overlay.
                    8f * view.resources.displayMetrics.density +
                        (controller.terminalColumns - 0.5f) * view.terminalCellWidthPx
                } else view.width / 2f
                fun drag(older: Boolean) {
                    val now = SystemClock.uptimeMillis()
                    val start = if (older) 4f else 10f
                    val end = if (older) 10f else 4f
                    for ((action, row, elapsed) in listOf(
                        Triple(MotionEvent.ACTION_DOWN, start, 0L),
                        Triple(MotionEvent.ACTION_MOVE, end, 80L),
                        Triple(MotionEvent.ACTION_UP, end, 240L),
                    )) scenario.onActivity {
                        val event = MotionEvent.obtain(now, now + elapsed, action,
                            gestureX(), row * controller.viewport.lineHeightPx, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                    }
                }
                if (lateIdleFailure) {
                    connection.failOneIdleRead = true
                    assertTrue(connection.idleReadEntered.await(5, TimeUnit.SECONDS))
                }
                // A nonzero remote offset must never force the gesture back to row wheels.
                drag(older = true)
                scenario.onActivity {
                    assertTrue(controller.herdrHistoryReading)
                    assertTrue(controller.herdrHistoryPinned)
                    if (wrappedHistory) {
                        val scroll = FastTerminalView::class.java.getDeclaredField("herdrScroll").apply { isAccessible = true }.get(view) as HerdrNativeScroll
                        val snapshot = requireNotNull(scroll.reader.snapshot)
                        assertEquals(HerdrPaneHistory.ROW_LIMIT, snapshot.lines.size)
                        val markers = snapshot.lines.map { it.text }.filter { it.startsWith("CODEX_SCROLL_") }
                        assertEquals((1..200).map { "CODEX_SCROLL_%03d".format(it) }, markers)
                    }
                    sidebarBefore?.let { assertArrayEquals("History must not paint over navigation", it, sidebarPixels()) }
                }
                assertTrue("The first history gesture must remain native", connection.sent.isEmpty())
                if (lateIdleFailure) {
                    connection.releaseIdleRead.countDown()
                    // This callback starts only after the failed idle result was published.
                    await { connection.pinnedReads.get() > 0 }
                    scenario.onActivity {
                        assertTrue("A late failed idle read must not erase the first reader", controller.herdrHistoryReading)
                        assertTrue(controller.herdrHistoryPinned)
                        assertTrue(view.herdrVisibleRowsForTesting().isNotEmpty())
                    }
                    assertTrue("No wheel fallback during capture handoff", connection.sent.isEmpty())
                }
                // A tap to stop/read must stay on the overlay, including buffered typing.
                if (historyLinks) scenario.onActivity {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 40, action,
                            view.width / 2f, 6f * controller.viewport.lineHeightPx, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                    }
                    openedLinks.clear()
                }
                if (historyLinks) {
                    val downTime = SystemClock.uptimeMillis()
                    val x = 8f * view.resources.displayMetrics.density + view.terminalCellWidthPx
                    val y = 6f * controller.viewport.lineHeightPx
                    scenario.onActivity {
                        val event = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                    }
                    SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 100)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        assertNull("History long press must select without a dialog", view.textActionsDialogForTesting())
                        assertEquals("note", view.selectedTextForTesting())
                        assertTrue(view.performAccessibilityAction(com.yanjiyu.terminalspike.R.id.terminal_action_text_tools, null))
                        val dialog = requireNotNull(view.textActionsDialogForTesting())
                        assertTrue("History must offer the optional tools", dialog.isShowing)
                        val editor = dialog.findViewById<android.widget.EditText>(com.yanjiyu.terminalspike.R.id.terminal_extracted_text)
                        assertTrue(editor.text.toString().contains("note"))
                        assertTrue("Holding a history link must not open it", openedLinks.isEmpty())
                        assertTrue("Holding history must not send mouse input", connection.sent.isEmpty())
                        dialog.dismiss()
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                        assertTrue(controller.herdrHistoryReading)
                        assertTrue(controller.herdrHistoryPinned)
                    }
                }
                for (directInput in listOf(true, false)) scenario.onActivity {
                    view.setDirectInputEnabled(directInput)
                    val now = SystemClock.uptimeMillis()
                    val x = if (historyLinks) 8f * view.resources.displayMetrics.density + view.terminalCellWidthPx else gestureX()
                    val y = 6f * controller.viewport.lineHeightPx
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    try { view.onTouchEvent(down) } finally { down.recycle() }
                    val visible = view.herdrVisibleRowsForTesting()
                    assertTrue(visible.isNotEmpty())
                    val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                    try { view.onTouchEvent(up) } finally { up.recycle() }
                    assertTrue("Tap must preserve the reader", controller.herdrHistoryReading)
                    assertTrue("Tap must preserve the history anchor", controller.herdrHistoryPinned)
                    assertEquals(visible, view.herdrVisibleRowsForTesting())
                    if (historyLinks) assertEquals(if (directInput) listOf(historyUri) else listOf(historyUri, historyUri), openedLinks.toList())
                    assertTrue("History tap must not click the live terminal", connection.sent.isEmpty())
                }
                repeat(8) { drag(older = false) }
                scenario.onActivity {
                    assertTrue("Do not reveal Herdr's older screen at native bottom", controller.herdrHistoryReading)
                    assertFalse(controller.herdrHistoryPinned)
                    assertTrue(view.herdrVisibleRowsForTesting().contains("INPUT_READY"))
                }
                assertEquals(6, connection.offset.get())
                assertTrue("Reaching latest must not send remote wheel reports", connection.sent.isEmpty())
                // Herdr may emit no Mosh frame for output below its older viewport, and
                // its metadata revision stays at 1. Latest still must refresh in the background.
                connection.latestInput = "INPUT_UPDATED"
                await { controller.herdrHistory?.lines?.lastOrNull()?.text == "INPUT_UPDATED" }
                // History publication notifies the view on the next frame; an idle main
                // queue does not prove that the scheduled frame has reached the reader.
                await {
                    var visible = false
                    scenario.onActivity { visible = view.herdrVisibleRowsForTesting().contains("INPUT_UPDATED") }
                    visible
                }
                scenario.onActivity {
                    assertTrue("Latest overlay must refresh, not freeze the input", view.herdrVisibleRowsForTesting().contains("INPUT_UPDATED"))
                }
                connection.offset.set(0)
                await { !controller.herdrHistoryReading }
                assertTrue("Handoff must not synthesize remote input", connection.sent.isEmpty())
            } finally {
                connection.releaseIdleRead.countDown()
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for capture", condition())
    }

    private class ScrolledHerdr(
        private val paneId: String,
        private val screenOnly: Boolean = false,
        private val agent: String = "codex",
        private val clientOwnedDesktop: Boolean = false,
        private val wrappedHistory: Boolean = false,
    ) : Connection {
        @Volatile private var columns = 80
        @Volatile private var rows = 24
        @Volatile var latestInput = "INPUT_READY"
        @Volatile var historyMarkup: String? = null
        val offset = AtomicInteger(if (screenOnly) 0 else 6)
        val reads = AtomicInteger()
        val pinnedReads = AtomicInteger()
        val idleReadEntered = CountDownLatch(1)
        val releaseIdleRead = CountDownLatch(1)
        @Volatile var failOneIdleRead = false
        val sent = CopyOnWriteArrayList<String>()
        override val isHerdrSession = true
        override fun captureInputContext() = com.yanjiyu.terminalspike.terminal.TerminalInputContext(
            "herdr/scroll-test/$paneId/term1", true,
            if (screenOnly && agent == "pi") com.yanjiyu.terminalspike.terminal.HerdrPiScrollPane(0, 0, columns, rows) else null,
        )
        override fun captureHerdrHistory(previous: HerdrPaneHistory?, reading: Boolean): HerdrPaneHistory? {
            if (reading) pinnedReads.incrementAndGet()
            if (!reading && failOneIdleRead) {
                failOneIdleRead = false
                idleReadEntered.countDown()
                check(releaseIdleRead.await(5, TimeUnit.SECONDS))
                reads.incrementAndGet()
                return null
            }
            val paneColumns = if (clientOwnedDesktop) columns - 26 else columns
            val paneRows = if (clientOwnedDesktop) rows - 1 else rows
            val result = captureHerdrPaneHistory({ command ->
                val value = when {
                    "'layout'" in command -> """{"result":{"layout":{"area":{"x":0,"y":0,"width":$paneColumns,"height":$paneRows},"focused_pane_id":"$paneId","tab_id":"w1:t1","panes":[{"pane_id":"$paneId","rect":{"x":0,"y":0,"width":$paneColumns,"height":$paneRows}}]}}}"""
                    "'get'" in command -> """{"result":{"pane":{"pane_id":"$paneId","tab_id":"w1:t1","terminal_id":"term1","agent":"$agent","revision":1,"scroll":{"viewport_rows":$paneRows,"offset_from_bottom":${offset.get()}}}}}"""
                    "'read'" in command -> (1..if (screenOnly) rows else 1000).joinToString("\n", postfix = "\n") {
                        when {
                            it == 1000 -> latestInput
                            wrappedHistory && it < 800 -> "x".repeat(paneColumns + 3)
                            wrappedHistory -> "CODEX_SCROLL_%03d".format(it - 799)
                            else -> historyMarkup ?: "row $it"
                        }
                    }
                    else -> error("Unexpected capture command")
                }
                TmuxExecOutput(value.toByteArray(), 0)
            }, HerdrStartupChoice("/usr/bin/herdr", "scroll-test"), previous, reading)
            reads.incrementAndGet()
            return result
        }
        override fun send(bytes: ByteArray) {
            val value = bytes.toString(Charsets.US_ASCII)
            sent += value
            if (value.startsWith("\u001B[<65;")) offset.updateAndGet { (it - 3).coerceAtLeast(0) }
        }
        override fun trySend(bytes: ByteArray): Boolean { send(bytes); return true }
        override fun resize(columns: Int, rows: Int) { this.columns = columns; this.rows = rows }
        override suspend fun connect(columns: Int, rows: Int, onBytes: (ByteArray) -> Unit, onState: (ConnectionState) -> Unit) = Unit
        override fun answerHostIdentityPrompt(promptToken: Long, decision: HostIdentityDecision) = Unit
        override fun answerKeyboardInteractiveChallenge(challengeToken: Long, responses: List<CharArray>) = Unit
        override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit
        override fun cancelPendingPrompts() = Unit
        override fun close() = Unit
    }
}
