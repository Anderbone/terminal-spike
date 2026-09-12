package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkAction
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionRequest
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastTerminalSelectionInteractionTest {
    @Test
    fun mouseEnabledTerminalTapSendsClickAndKeepsTextInput() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var view: FastTerminalView
            val received = mutableListOf<ByteArray>()
            scenario.onActivity { activity ->
                controller = TerminalController()
                controller.setInputSink(
                    object : com.yanjiyu.terminalspike.terminal.TerminalInputSink {
                        override fun send(bytes: ByteArray) { received += bytes.copyOf() }
                    },
                    onResize = { _, _ -> },
                )
                view = attachTerminalView(activity, controller)
                val engine = VtTerminalEngine(controller.terminalColumns, controller.terminalRows)
                controller.updateTerminalFrame(engine.accept("\u001B[?1000;1006h".toByteArray()))
            }
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = controller.isMouseTrackingEnabled() }
                if (!ready) SystemClock.sleep(20L)
            }
            assertTrue("Mouse mode frame was applied", ready)
            scenario.onActivity {
                val tapTime = SystemClock.uptimeMillis()
                tapCell(view, column = 0, atTime = tapTime)
                assertEquals(
                    "\u001B[<0;1;1M\u001B[<0;1;1m",
                    received.single().toString(Charsets.US_ASCII),
                )
                // The second tap opens text entry without activating the remote control twice.
                tapCell(view, column = 0, atTime = tapTime + 120L)
                assertEquals(1, received.size)
                assertTrue(view.hasFocus())
                assertTrue(view.onCheckIsTextEditor())
                val connection = requireNotNull(view.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                assertTrue(connection.commitText("hello chat", 1))
                assertEquals("hello chat", received.last().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun herdrNavigationLongPressSendsOnlyRightClickAndOutputStillSelects() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var view: FastTerminalView
            val received = mutableListOf<ByteArray>()
            val resized = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                controller = TerminalController()
                controller.setInputSink(
                    object : com.yanjiyu.terminalspike.terminal.TerminalInputSink {
                        override fun send(bytes: ByteArray) { received += bytes.copyOf() }
                    },
                    onResize = { _, _ -> resized.countDown() },
                )
                view = attachTerminalView(activity, controller)
            }
            assertTrue("Native grid must settle before publishing matching Herdr geometry",
                resized.await(5, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val engine = VtTerminalEngine(controller.terminalColumns, controller.terminalRows)
                controller.updateTerminalFrame(engine.accept("\u001B[?1000;1006hhello".toByteArray()))
            }
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = controller.isMouseTrackingEnabled() }
                if (!ready) SystemClock.sleep(20L)
            }
            assertTrue(ready)
            for (sidebar in listOf(true, false)) {
                scenario.onActivity {
                    controller.publishHerdrSidebarLayout(
                        com.yanjiyu.terminalspike.connection.HerdrSidebarLayout(
                            if (sidebar) 4 else 0, controller.terminalColumns,
                            if (sidebar) 0 else 1, controller.terminalRows - if (sidebar) 0 else 1,
                        ),
                    )
                    received.clear()
                }
                longPressCell(scenario, view, row = 0, column = 0)
                scenario.onActivity {
                    assertEquals("\u001B[<2;1;1M\u001B[<2;1;1m", received.single().toString(Charsets.US_ASCII))
                    assertNull(view.selectedTextForTesting())
                }
            }
            // The same cell is ordinary output when it belongs to a pane, even in Herdr.
            scenario.onActivity {
                controller.publishHerdrSidebarLayout(
                    com.yanjiyu.terminalspike.connection.HerdrSidebarLayout(
                        0, controller.terminalColumns, 0, controller.terminalRows,
                    ),
                )
                received.clear()
            }
            longPressCell(scenario, view, row = 0, column = 0)
            scenario.onActivity {
                assertTrue(received.isEmpty())
                assertEquals("hello", view.selectedTextForTesting())
            }
        }
    }

    @Test
    fun twoHundredLiveTerminalRowsCanScrollBackToTheFirstRow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var view: FastTerminalView
            scenario.onActivity { activity ->
                controller = TerminalController(TerminalBuffer(capacity = 1_000))
                view = attachTerminalView(activity, controller)
                val engine = VtTerminalEngine(
                    columns = controller.terminalColumns,
                    rows = controller.terminalRows,
                )
                (1..200).forEach { row ->
                    controller.updateTerminalFrame(
                        engine.accept("$row. test text here\r\n".encodeToByteArray()),
                    )
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(100L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(
                    "Chunked output retained ${controller.lineCount()} total rows and " +
                        "${controller.buffer.lineCount()} history rows",
                    controller.lineCount() >= 200,
                )
                assertTrue(controller.viewport.scrollY > 0f)

                var swipeCount = 0
                while (controller.viewport.scrollY > 0.5f && swipeCount < 256) {
                    val start = SystemClock.uptimeMillis()
                    val x = view.width / 2f
                    val startY = view.height * 0.25f
                    val endY = view.height * 0.85f
                    val events = listOf(
                        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, startY, 0),
                        MotionEvent.obtain(
                            start,
                            start + 40L,
                            MotionEvent.ACTION_MOVE,
                            x,
                            endY,
                            0,
                        ),
                        MotionEvent.obtain(
                            start,
                            start + 80L,
                            MotionEvent.ACTION_UP,
                            x,
                            endY,
                            0,
                        ),
                    )
                    events.forEach { event ->
                        try {
                            view.onTouchEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    swipeCount += 1
                }

                assertTrue(
                    "Native swipes stopped after $swipeCount attempts with " +
                        "scrollY=${controller.viewport.scrollY}",
                    controller.viewport.scrollY <= 0.5f,
                )
                assertFalse(controller.viewport.autoFollow)
                val firstVisible = controller.viewport.visibleRows(overscan = 0).first
                assertEquals(0, firstVisible)
                assertEquals("1. test text here", controller.lineAt(firstVisible)?.text)
            }
        }
    }

    @Test
    fun reattachedTabCanScrollThroughTwoHundredRowsReceivedWhileDetached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var view: FastTerminalView
            scenario.onActivity { activity ->
                controller = TerminalController(TerminalBuffer(capacity = 1_000))
                view = attachTerminalView(activity, controller)
                activity.setContentView(View(activity))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val engine = VtTerminalEngine(
                    columns = controller.terminalColumns,
                    rows = controller.terminalRows,
                )
                val output = (1..200).joinToString(separator = "") { row ->
                    "$row. test text here\r\n"
                }
                controller.updateTerminalFrame(engine.accept(output.encodeToByteArray()))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> activity.setContentView(view) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(controller.lineCount() >= 200)
                assertTrue(
                    "Reattached viewport retained only ${controller.viewport.lineCount} rows",
                    controller.viewport.lineCount >= 200,
                )

                var swipeCount = 0
                while (controller.viewport.scrollY > 0.5f && swipeCount < 256) {
                    val start = SystemClock.uptimeMillis()
                    val x = view.width / 2f
                    val events = listOf(
                        MotionEvent.obtain(
                            start,
                            start,
                            MotionEvent.ACTION_DOWN,
                            x,
                            view.height * 0.25f,
                            0,
                        ),
                        MotionEvent.obtain(
                            start,
                            start + 40L,
                            MotionEvent.ACTION_MOVE,
                            x,
                            view.height * 0.85f,
                            0,
                        ),
                        MotionEvent.obtain(
                            start,
                            start + 80L,
                            MotionEvent.ACTION_UP,
                            x,
                            view.height * 0.85f,
                            0,
                        ),
                    )
                    events.forEach { event ->
                        try {
                            view.onTouchEvent(event)
                        } finally {
                            event.recycle()
                        }
                    }
                    swipeCount += 1
                }

                assertTrue(controller.viewport.scrollY <= 0.5f)
                val firstVisible = controller.viewport.visibleRows(overscan = 0).first
                assertEquals(0, firstVisible)
                assertEquals("1. test text here", controller.lineAt(firstVisible)?.text)
            }
        }
    }

    @Test
    fun attachingAPrepopulatedTabSynchronizesItsViewportImmediately() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstController = TerminalController()
                val duplicatedController = TerminalController().apply {
                    buffer.append(TerminalLine.plain("duplicate tab is ready"))
                }

                val view = attachTerminalView(activity, firstController)
                view.attachController(duplicatedController)
                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)

                assertEquals(1, duplicatedController.viewport.lineCount)
                assertTrue(duplicatedController.viewport.viewportHeightPx > 0)
                assertEquals(1, duplicatedController.viewport.visibleRows(0).count)
                assertTrue(info.text.toString().contains("duplicate tab is ready"))
                info.recycle()
            }
        }
    }

    @Test
    fun nativeViewExposesLongPressSelectAllAndCopyAccessibilityActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(TerminalLine.plain("alpha bravo charlie delta echo foxtrot golf hotel"))
                    buffer.append(TerminalLine.plain("second row"))
                }
                val view = attachTerminalView(activity, controller)

                assertTrue(view.performLongClick())
                assertNotNull(view.selectedTextForTesting())

                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_select_all })

                assertTrue(view.performAccessibilityAction(R.id.terminal_action_select_all, null))
                assertEquals(
                    "alpha bravo charlie delta echo foxtrot golf hotel\nsecond row",
                    view.selectedTextForTesting(),
                )
                assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
                info.recycle()
            }
        }
    }

    @Test
    fun tappingSafeOsc8LinkOpensItDirectly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(
                        TerminalLine.styled(
                            listOf(
                                TerminalRun(
                                    text = "docs",
                                    hyperlink = TerminalHyperlink("https://example.test/docs"),
                                    startColumn = 0,
                                    columnWidth = 4,
                                ),
                            ),
                        ),
                    )
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                }

                tapFirstCell(view)

                assertEquals(listOf(TerminalLinkAction.OPEN), requests.map { it.action })
            }
        }
    }

    @Test
    fun tappingCodexParenthesizedPlainUrlOpensItDirectly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(TerminalLine.plain("• Google (https://www.google.com)"))
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                }

                tapCell(view, column = 20)

                assertEquals(listOf("https://www.google.com"), requests.map { it.target.uri })
                assertEquals(listOf(TerminalLinkAction.OPEN), requests.map { it.action })
            }
        }
    }

    @Test
    fun longPressingSafeLinkShowsOpenActionAndDispatchesToBrowserCallback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(
                        TerminalLine.styled(
                            listOf(
                                TerminalRun(
                                    text = "https://example.test/docs",
                                    hyperlink = TerminalHyperlink("https://example.test/docs"),
                                    startColumn = 0,
                                    columnWidth = 100,
                                ),
                            ),
                        ),
                    )
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                }

                assertTrue(view.performLongClick())
                assertEquals("https://example.test/docs", view.selectedTextForTesting())
                val info = AccessibilityNodeInfo.obtain()
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_open_link, null))
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_open_link })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_copy_link })
                assertEquals(listOf(TerminalLinkAction.OPEN), requests.map { it.action })
                info.recycle()
            }
        }
    }

    @Test
    fun longPressingWrappedPlainTextLinkSelectsAllRowsAndOffersLinkActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: FastTerminalView
            val requests = mutableListOf<TerminalLinkActionRequest>()
            val clipboardRequests = mutableListOf<TerminalClipboardRequest>()
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(TerminalLine.plain("https://example.", softWrappedToNext = true))
                    buffer.append(TerminalLine.plain("test/a/very/long/", softWrappedToNext = true))
                    buffer.append(TerminalLine.plain("path?q=1"))
                }
                view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                    setClipboardActionCallback { request ->
                        clipboardRequests += request
                        true
                    }
                }
            }
            longPressCell(scenario, view, row = 1, column = 4)
            scenario.onActivity {
                assertEquals(
                    "https://example.test/a/very/long/path?q=1",
                    view.selectedTextForTesting(),
                )
                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_open_link })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_copy_link })
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_open_link, null))
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_copy_link, null))
                assertEquals(listOf(TerminalLinkAction.OPEN), requests.map { it.action })
                assertEquals(
                    listOf("https://example.test/a/very/long/path?q=1"),
                    clipboardRequests.map { it.text },
                )
                info.recycle()
            }
        }
    }

    @Test
    fun unsafeOsc8LinkHasCopyActionButNoOpenAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = TerminalController().apply {
                    buffer.append(
                        TerminalLine.styled(
                            listOf(
                                TerminalRun(
                                    text = "file",
                                    hyperlink = TerminalHyperlink("file:///tmp/result"),
                                    startColumn = 0,
                                    columnWidth = 4,
                                ),
                            ),
                        ),
                    )
                }
                val requests = mutableListOf<TerminalLinkActionRequest>()
                val clipboardRequests = mutableListOf<TerminalClipboardRequest>()
                val view = attachTerminalView(activity, controller).apply {
                    setLinkActionCallback { request -> requests += request }
                    setClipboardActionCallback { request ->
                        clipboardRequests += request
                        true
                    }
                }

                tapFirstCell(view)

                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertFalse(info.actionList.any { it.id == R.id.terminal_action_open_link })
                assertTrue(info.actionList.any { it.id == R.id.terminal_action_copy_link })
                assertFalse(view.performAccessibilityAction(R.id.terminal_action_open_link, null))
                assertTrue(view.performAccessibilityAction(R.id.terminal_action_copy_link, null))
                assertTrue(requests.isEmpty())
                assertEquals(listOf(TerminalClipboardContentKind.LINK), clipboardRequests.map { it.kind })
                info.recycle()
            }
        }
    }

    @Test
    fun controllerHistoryAnchorsResolveAcrossAppendAndDisappearAfterTrim() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val controller = TerminalController(TerminalBuffer(capacity = 2))
                controller.buffer.append(TerminalLine.plain("first"))
                controller.buffer.append(TerminalLine.plain("second"))
                val first = requireNotNull(controller.selectionLineAt(0))

                controller.buffer.append(TerminalLine.plain("third"))

                assertEquals(TerminalLineSpace.PRIMARY_HISTORY, first.anchor.space)
                assertNull(controller.selectionIndexOf(first.anchor))
                assertEquals("second", controller.selectionLineAt(0)?.line?.text)
            }
        }
    }

    private fun attachTerminalView(
        activity: MainActivity,
        controller: TerminalController,
    ): FastTerminalView = FastTerminalView(activity).also { view ->
        activity.setContentView(view)
        view.attachController(controller)
        val width = 1_080
        val height = 600
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun tapFirstCell(view: FastTerminalView) {
        tapCell(view, column = 0)
    }

    private fun tapCell(view: FastTerminalView, column: Int, atTime: Long = SystemClock.uptimeMillis()) {
        val now = atTime
        val density = view.resources.displayMetrics.density
        val x = 8f * density + 9f * density * column + 2f
        val y = 5f * density + 4f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            view.onTouchEvent(down)
        } finally {
            down.recycle()
        }
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.onTouchEvent(up)
        } finally {
            up.recycle()
        }
    }

    private fun longPressCell(
        scenario: ActivityScenario<MainActivity>,
        view: FastTerminalView,
        row: Int,
        column: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        val density = view.resources.displayMetrics.density
        val x = 8f * density + 9f * density * column + 2f
        val y = 5f * density + 17f * density * row + 4f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        scenario.onActivity {
            try {
                view.onTouchEvent(down)
            } finally {
                down.recycle()
            }
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(now, upTime, MotionEvent.ACTION_UP, x, y, 0)
        scenario.onActivity {
            try {
                view.onTouchEvent(up)
            } finally {
                up.recycle()
            }
        }
    }
}
