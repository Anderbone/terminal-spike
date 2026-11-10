package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.*
import org.junit.Test

/** Synthetic native-view regression; run only on the authorized old phone. */
class HerdrMobileSwitcherScrollTest {
    @Test fun horizontalSwipesOpenAndCloseMobileNavigationWithoutWheelReports() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var container: HerdrTerminalContainer
            val sent = mutableListOf<String>()
            val resized = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                controller = TerminalController()
                controller.setInputSink(object : TerminalInputSink {
                    override fun send(bytes: ByteArray) { sent += bytes.toString(Charsets.US_ASCII) }
                }, onResize = { _, _ -> resized.countDown() })
                controller.herdrSessionActive = true
                container = HerdrTerminalContainer(activity)
                activity.setContent { AndroidView(factory = { container }) }
                container.terminal.attachController(controller)
            }
            assertTrue(resized.await(5, java.util.concurrent.TimeUnit.SECONDS))
            fun draw(open: Boolean) {
                scenario.onActivity {
                    val cols = controller.terminalColumns
                    val engine = VtTerminalEngine(cols, controller.terminalRows)
                    val rows = controller.terminalRows
                    // Actual 0.9.5 bottom-left chrome: hidden divider and compatibility label.
                    val text = "\u001B[H1 \u001B[38;5;245m + \u001B[0m" + if (!open) {
                        "\u001B[${rows - 1};1H\u001B[44m  switch \u001B[8m│\u001B[0m status" +
                            "\u001B[${rows};1H\u001B[44;8m  switch │\u001B[0m 1 done"
                    } else {
                        "\u001B[${rows - 2};1H" + "─".repeat(cols) +
                            "\u001B[${rows - 1};1H\u001B[44m  close  \u001B[8m│\u001B[0m switch" +
                            "\u001B[${rows};1H\u001B[44m     ×   \u001B[8m│\u001B[0m"
                    }
                    controller.updateTerminalFrame(engine.accept(("\u001B[?1000;1006h" + text).toByteArray()))
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val deadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ready = controller.herdrMobileNavigationTarget(open = !open) != null }
                    if (!ready) SystemClock.sleep(20)
                }
                assertTrue("Visible navigation must be applied", ready)
            }
            fun swipe(direction: Int) {
                var start = 0f
                var y = 0f
                var distance = 0f
                scenario.onActivity {
                    sent.clear()
                    val location = IntArray(2)
                    container.getLocationOnScreen(location)
                    start = location[0] + container.width / 2f
                    y = location[1] + container.height / 2f
                    distance = container.width * 0.3f * direction
                }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val now = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        12 -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val motion = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        start + distance * step / 12f, y, 0)
                    motion.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
                    SystemClock.sleep(10)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals("Only one button click and release; no wheels or trailing tap", 1, sent.size)
                    assertTrue(sent.single().startsWith("\u001B[<0;"))
                    assertTrue(sent.single().endsWith("m"))
                }
            }
            draw(false)
            scenario.onActivity {
                controller.publishHerdrHistory(HerdrPaneHistory(
                    "default/w1:p1/term1", 0, 0, controller.terminalColumns,
                    controller.terminalRows - 3, 0,
                    (1..1000).map { TerminalLine.plain("row $it") },
                ))
                sent.clear()
                val now = SystemClock.uptimeMillis()
                for ((action, dy, delay) in listOf(
                    Triple(MotionEvent.ACTION_DOWN, 0f, 0L),
                    Triple(MotionEvent.ACTION_MOVE, 200f, 300L),
                    Triple(MotionEvent.ACTION_UP, 200f, 600L),
                )) {
                    val motion = MotionEvent.obtain(now, now + delay, action,
                        container.width / 2f, container.height / 2f + dy, 0)
                    try { container.dispatchTouchEvent(motion) } finally { motion.recycle() }
                }
                assertTrue("Reproduce user's history reader before horizontal swipe", controller.herdrHistoryReading)
                assertTrue("History reading sends no wheels", sent.isEmpty())
            }
            swipe(1)
            scenario.onActivity { assertFalse("Navigation exits the old reader", controller.herdrHistoryReading) }
            draw(true)
            swipe(-1)
        }
    }

    @Test fun switcherKeepsRemoteSwipesAndClosingRestoresNativeHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: TerminalController
            lateinit var view: FastTerminalView
            lateinit var engine: VtTerminalEngine
            val sent = mutableListOf<String>()
            val resized = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                controller = TerminalController()
                controller.setInputSink(object : TerminalInputSink {
                    override fun send(bytes: ByteArray) { sent += bytes.toString(Charsets.US_ASCII) }
                }, onResize = { _, _ -> resized.countDown() })
                view = FastTerminalView(activity)
                activity.setContentView(view)
                view.attachController(controller)
            }
            assertTrue(resized.await(5, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(controller.terminalColumns > 10)
                assertTrue(controller.terminalRows > 8)
                engine = VtTerminalEngine(controller.terminalColumns, controller.terminalRows)
                controller.publishHerdrSidebarLayout(HerdrSidebarLayout(
                    0, controller.terminalColumns, 2, controller.terminalRows - 2,
                ))
                controller.publishHerdrHistory(HerdrPaneHistory(
                    "default/w1:p1/term1", 0, 2, controller.terminalColumns,
                    controller.terminalRows - 2, 0,
                    (1..1000).map { TerminalLine.plain("row $it") },
                ))
            }
            fun header(live: Boolean) {
                scenario.onActivity {
                    val label = if (live) "│ switch  " else "│    ×    "
                    controller.updateTerminalFrame(engine.accept(
                        "\u001B[?1000;1006h\u001B[2;${controller.terminalColumns - 9}H\u001B[K$label".toByteArray(),
                    ))
                }
                val deadline = SystemClock.uptimeMillis() + 5000L
                var applied = false
                while (!applied && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        val rendered = controller.lineAt(controller.lineCount() - controller.terminalRows + 1)?.text
                        applied = rendered?.trimEnd()?.endsWith(if (live) "switch" else "×") == true &&
                            controller.isHerdrNativeHistoryVisible() == live
                    }
                    if (!applied) SystemClock.sleep(20)
                }
                assertTrue("Header redraw must apply", applied)
            }
            var downTime = SystemClock.uptimeMillis()
            fun event(action: Int, row: Float, delay: Long) = scenario.onActivity {
                val motion = MotionEvent.obtain(downTime, downTime + delay, action,
                    view.width / 2f, row * controller.viewport.lineHeightPx, 0)
                try { view.onTouchEvent(motion) } finally { motion.recycle() }
            }
            header(false)
            scenario.onActivity { sent.clear() }
            event(MotionEvent.ACTION_DOWN, 4f, 0)
            event(MotionEvent.ACTION_MOVE, 7f, 40)
            scenario.onActivity {
                assertFalse(controller.herdrHistoryReading)
                assertTrue("Switcher must receive wheel reports", sent.any { it.startsWith("\u001B[<64;") })
            }
            // A remote redraw must not hand the remainder of this swipe to cached history.
            header(true)
            event(MotionEvent.ACTION_MOVE, 8f, 80)
            event(MotionEvent.ACTION_UP, 8f, 100)
            scenario.onActivity { assertFalse(controller.herdrHistoryReading) }
            downTime = SystemClock.uptimeMillis() + 200
            scenario.onActivity { sent.clear() }
            event(MotionEvent.ACTION_DOWN, 4f, 0)
            event(MotionEvent.ACTION_MOVE, 7f, 40)
            event(MotionEvent.ACTION_UP, 7f, 100)
            scenario.onActivity {
                assertTrue("Next live-pane swipe must use native history", controller.herdrHistoryReading)
                assertTrue("Native history must not send wheels", sent.isEmpty())
            }
            header(false)
            scenario.onActivity { assertFalse("Opening the switcher clears the reader", controller.herdrHistoryReading) }
        }
    }
}
