package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
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
