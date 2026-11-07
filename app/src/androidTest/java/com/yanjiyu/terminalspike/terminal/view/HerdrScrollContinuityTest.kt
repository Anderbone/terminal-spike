package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.connection.HerdrPaneArea
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Production View/MotionEvent regressions; controlled data is not real-Codex acceptance. */
class HerdrScrollContinuityTest {
    @Test fun firstDragJoinsTheNewerVisibleTailWithoutWaitingForAnotherCapture() = fixture { f ->
        f.publishHistory(clientChrome = true)
        f.frame("\u001B[1;1H\u001B[32m + \u001B[0m")
        var pane: HerdrPaneHistory? = null
        f.scenario.onActivity {
            pane = requireNotNull(f.controller.herdrHistoryForDisplay())
            f.controller.publishHerdrHistory(requireNotNull(f.controller.herdrHistory).copy(
                lines = (1..189).map { TerminalLine.plain("CODEX_SCROLL_%03d".format(it)) } +
                    List(4) { TerminalLine.plain("old footer $it") },
            ))
        }
        val rect = requireNotNull(pane)
        val visible = ((200 - (rect.rows - 4) + 1)..200).map { "CODEX_SCROLL_%03d".format(it) } + List(4) { "new footer $it" }
        f.frame(buildString {
            for ((row, text) in visible.withIndex()) append("\u001B[${rect.y + row + 1};${rect.x + 1}H${text.padEnd(rect.columns)}")
        })
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.scenario.onActivity {
            val native = FastTerminalView::class.java.getDeclaredField("herdrScroll").apply { isAccessible = true }.get(f.view) as HerdrNativeScroll
            val markers = requireNotNull(native.reader.snapshot).lines.map { it.text.trimEnd() }.filter { it.startsWith("CODEX_SCROLL_") }
            assertEquals((1..200).map { "CODEX_SCROLL_%03d".format(it) }, markers)
            assertTrue(f.controller.herdrHistoryReading)
            assertTrue(f.sent.isEmpty())
        }
        f.event(MotionEvent.ACTION_UP, 8f)
    }

    @Test fun nativeFlingContinuesOnTheActualViewFrameClock() = fixture { f ->
        f.publishHistory()
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f, intervalMillis = 10)
        f.event(MotionEvent.ACTION_MOVE, 11f, intervalMillis = 10)
        f.event(MotionEvent.ACTION_UP, 13f, intervalMillis = 10)
        val native = FastTerminalView::class.java.getDeclaredField("herdrScroll").apply { isAccessible = true }
            .get(f.view) as HerdrNativeScroll
        var released = 0f
        f.scenario.onActivity {
            val physics = HerdrNativeScroll::class.java.getDeclaredField("scroller").apply { isAccessible = true }.get(native) as android.widget.OverScroller
            android.util.Log.i("HerdrFrameClock", "release shown=${f.view.isShown} focus=${f.view.hasWindowFocus()} window=${f.view.windowVisibility} y=${native.reader.viewport.scrollY} finished=${physics.isFinished} velocity=${physics.currVelocity} final=${physics.finalY}")
            assertTrue("The frame-clock fixture needs a visible, focused window", f.view.isShown && f.view.hasWindowFocus())
            released = native.reader.viewport.scrollY
        }
        SystemClock.sleep(150)
        f.scenario.onActivity {
            android.util.Log.i("HerdrFrameClock", "after y=${native.reader.viewport.scrollY} reader=${f.controller.herdrHistoryReading}")
            assertTrue("Window frames must continue native motion after release", native.reader.viewport.scrollY < released)
        }
    }

    @Test fun cancellationDuringLateHistoryDeliveryKeepsTheNextGestureNative() = fixture { f ->
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.publishHistory()
        f.event(MotionEvent.ACTION_CANCEL, 5f)
        f.scenario.onActivity {
            assertFalse(f.controller.herdrHistoryReading)
            assertTrue("Cancellation must not send remote input", f.sent.isEmpty())
        }
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative()
        f.event(MotionEvent.ACTION_UP, 8f)
    }

    @Test fun latestOverlayAtANonzeroRemoteOffsetSurvivesAnIncompleteChromeFrame() = fixture { f ->
        f.publishHistory(clientChrome = true, offset = 6)
        f.frame("\u001B[1;1H\u001B[32m + \u001B[0m")
        f.event(MotionEvent.ACTION_DOWN, 10f)
        f.event(MotionEvent.ACTION_MOVE, 2f)
        f.event(MotionEvent.ACTION_UP, 2f)
        f.assertNative()
        f.scenario.onActivity { assertFalse("Fixture must have reached the latest overlay", f.controller.herdrHistoryPinned) }
        val latest = f.visible()
        assertTrue(latest.any { it.contains("ROW_1000") })
        f.frame("\u001B[1;1H\u001B[2K")
        f.assertNative("latest overlay after incomplete redraw")
        assertEquals(latest, f.visible())
    }

    @Test fun repeatedReversalsAndChromeRedrawsStayNativeInBothInputModes() = fixture { f ->
        f.publishHistory(clientChrome = true)
        f.frame("\u001B[1;1H\u001B[32m + \u001B[0m")
        for (direct in listOf(true, false)) {
            f.scenario.onActivity { f.view.setDirectInputEnabled(direct) }
            repeat(12) {
                f.event(MotionEvent.ACTION_DOWN, 5f)
                f.event(MotionEvent.ACTION_MOVE, 8f)
                f.event(MotionEvent.ACTION_MOVE, 7.9f)
                f.assertNative()
                val before = f.visible()
                f.frame("\u001B[1;1H\u001B[2K")
                f.assertNative("redraw during repeated reversal")
                assertEquals(before, f.visible())
                f.frame("\u001B[1;1H\u001B[32m + \u001B[0m")
                f.event(MotionEvent.ACTION_MOVE, 8.1f)
                f.event(MotionEvent.ACTION_UP, 8.1f)
                f.assertNative()
            }
        }
    }

    @Test fun realPaneReplacementDoesNotLeakTheRestOfANativeGestureIntoRemoteWheels() = fixture { f ->
        f.publishHistory()
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative()
        f.scenario.onActivity { f.controller.publishHerdrHistory(null) }
        f.frame("\u001B[HREPLACED")
        f.event(MotionEvent.ACTION_MOVE, 9f)
        f.event(MotionEvent.ACTION_UP, 9f)
        f.scenario.onActivity {
            assertFalse(f.controller.herdrHistoryReading)
            assertTrue("Invalidation must consume the remaining gesture without remote reports", f.sent.isEmpty())
        }
        f.publishHistory()
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative()
        f.event(MotionEvent.ACTION_UP, 8f)
    }

    @Test fun aGestureThatAlreadySentWheelsWaitsForTheNextDownBeforeGoingNative() = fixture { f ->
        f.publishHistory()
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.scenario.onActivity { f.controller.publishHerdrHistory(null) }
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.scenario.onActivity { assertTrue(f.sent.any { it.startsWith("\u001B[<64;") }) }
        f.publishHistory()
        f.event(MotionEvent.ACTION_MOVE, 9f)
        f.scenario.onActivity { assertFalse("One gesture must not mix remote wheels and native pixels", f.controller.herdrHistoryReading) }
        f.event(MotionEvent.ACTION_UP, 9f)
        f.scenario.onActivity { f.sent.clear() }
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative()
        f.event(MotionEvent.ACTION_UP, 8f)
    }

    @Test fun historyArrivingBetweenDownAndFirstMoveUsesNativeScrolling() = fixture { f ->
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.publishHistory()
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative()
        f.event(MotionEvent.ACTION_MOVE, 8.1f)
        f.event(MotionEvent.ACTION_UP, 8.1f)
        f.assertNative()
    }

    @Test fun partialNavigationRedrawCannotEraseAPinnedReader() = fixture { f ->
        f.publishHistory(clientChrome = true)
        f.frame("\u001B[1;1H\u001B[32m + \u001B[0m")
        f.scenario.onActivity { assertNotNull("Fixture must have a valid projected source", f.controller.herdrHistoryForDisplay()) }
        f.event(MotionEvent.ACTION_DOWN, 5f)
        f.event(MotionEvent.ACTION_MOVE, 8f)
        f.assertNative("before redraw")
        val before = f.visible()
        f.frame("\u001B[1;1H\u001B[2K") // First frame of a remote chrome redraw.
        f.assertNative("after incomplete redraw")
        assertEquals(before, f.visible())
        f.event(MotionEvent.ACTION_MOVE, 8.1f)
        f.event(MotionEvent.ACTION_UP, 8.1f)
        f.assertNative()
    }

    private fun fixture(block: (Fixture) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val f = Fixture(scenario)
            try { f.initialize(); block(f) } finally { scenario.onActivity { f.controller.resetInputSink() } }
        }
    }

    private class Fixture(val scenario: ActivityScenario<MainActivity>) {
        lateinit var controller: TerminalController
        lateinit var view: FastTerminalView
        lateinit var engine: VtTerminalEngine
        val sent = mutableListOf<String>()
        val resized = CountDownLatch(1)
        private var downTime = 0L
        private var elapsed = 0L

        fun initialize() {
            scenario.onActivity { activity ->
                controller = TerminalController()
                controller.herdrSessionActive = true
                controller.publishInputContext(TerminalInputContext("herdr/default/w1:p1/term1", true))
                controller.setInputSink(object : TerminalInputSink {
                    override fun send(bytes: ByteArray) { sent += bytes.toString(Charsets.US_ASCII) }
                }, onResize = { _, _ -> resized.countDown() })
                view = FastTerminalView(activity)
                activity.setContentView(view)
                view.attachController(controller)
            }
            assertTrue(resized.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { engine = VtTerminalEngine(controller.terminalColumns, controller.terminalRows) }
            frame("\u001B[?1000;1006hLIVE")
        }

        fun frame(bytes: String) {
            scenario.onActivity { controller.updateTerminalFrame(engine.accept(bytes.toByteArray())) }
            // updateTerminalFrame publishes on Choreographer, not the idle-message queue.
            val latch = CountDownLatch(1)
            scenario.onActivity { view.postOnAnimation { view.postOnAnimation { latch.countDown() } } }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        }

        fun publishHistory(clientChrome: Boolean = false, offset: Int = 0) {
            scenario.onActivity {
                val rows = controller.terminalRows - if (clientChrome) 3 else 0
                controller.publishHerdrHistory(HerdrPaneHistory(
                    "default/w1:p1/term1", 0, 0, controller.terminalColumns, rows, offset,
                    (1..1000).map { TerminalLine.plain("ROW_%04d".format(it)) },
                    surface = if (clientChrome) HerdrPaneArea(0, 0, controller.terminalColumns, rows) else null,
                ))
                if (clientChrome) controller.publishHerdrSidebarLayout(HerdrSidebarLayout(
                    0, controller.terminalColumns, 1, rows,
                ))
            }
            if (clientChrome) frame("\u001B[${controller.terminalRows};${controller.terminalColumns - 9}H│ switch  ")
        }

        fun event(action: Int, row: Float, intervalMillis: Long = 40) = scenario.onActivity {
            if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); elapsed = 0 }
            else elapsed += intervalMillis
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action,
                view.width / 2f, row * controller.viewport.lineHeightPx, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }

        fun visible(): List<String> {
            var result = emptyList<String>()
            scenario.onActivity { result = view.herdrVisibleRowsForTesting() }
            return result
        }

        fun assertNative(stage: String = "gesture") = scenario.onActivity {
            assertTrue("$stage must retain the native reader", controller.herdrHistoryReading)
            assertTrue("Native history must retain a fractional reader, not emit remote wheels: $sent", sent.isEmpty())
            assertTrue(view.herdrVisibleRowsForTesting().any { it.startsWith("ROW_") })
        }
    }
}
