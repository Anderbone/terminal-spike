package com.yanjiyu.terminalspike.terminal.view

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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Production capture/routing regression with a fake transport, not real Mosh/Codex acceptance. */
class HerdrRemoteScrollRecoveryTest {
    @Test fun remotelyScrolledPaneKeepsNativeMotionAndLatestInput() = verifyNativeRecovery("w1:p1")

    @Test fun letterPaneNumberKeepsNativeMotionAndLatestInput() = verifyNativeRecovery("w6:pC")

    private fun verifyNativeRecovery(paneId: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = DefaultSshSessionTerminalFactory.create()
            val controller = requireNotNull(terminal.controller)
            val connection = ScrolledHerdr(paneId)
            lateinit var view: FastTerminalView
            try {
                scenario.onActivity { activity ->
                    terminal.attach(connection)
                    view = FastTerminalView(activity)
                    activity.setContentView(view)
                    view.attachController(controller)
                    terminal.accept("\u001B[?1000;1006hLIVE".toByteArray(), {})
                }
                await { connection.reads.get() >= 2 && controller.isMouseTrackingEnabled() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
                            view.width / 2f, row * controller.viewport.lineHeightPx, 0)
                        try { view.onTouchEvent(event) } finally { event.recycle() }
                    }
                }
                // A nonzero remote offset must never force the gesture back to row wheels.
                drag(older = true)
                scenario.onActivity {
                    assertTrue(controller.herdrHistoryReading)
                    assertTrue(controller.herdrHistoryPinned)
                }
                assertTrue("The first history gesture must remain native", connection.sent.isEmpty())
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
                scenario.onActivity { terminal.stopAndClear() }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for capture", condition())
    }

    private class ScrolledHerdr(private val paneId: String) : Connection {
        @Volatile private var columns = 80
        @Volatile private var rows = 24
        @Volatile var latestInput = "INPUT_READY"
        val offset = AtomicInteger(6)
        val reads = AtomicInteger()
        val sent = CopyOnWriteArrayList<String>()
        override val isHerdrSession = true
        override fun captureHerdrHistory(previous: HerdrPaneHistory?, reading: Boolean): HerdrPaneHistory? {
            val result = captureHerdrPaneHistory({ command ->
                val value = when {
                    "'layout'" in command -> """{"result":{"layout":{"focused_pane_id":"$paneId","tab_id":"w1:t1","panes":[{"pane_id":"$paneId","rect":{"x":0,"y":0,"width":$columns,"height":$rows}}]}}}"""
                    "'get'" in command -> """{"result":{"pane":{"pane_id":"$paneId","tab_id":"w1:t1","terminal_id":"term1","agent":"codex","revision":1,"scroll":{"viewport_rows":$rows,"offset_from_bottom":${offset.get()}}}}}"""
                    "'read'" in command -> (1..1000).joinToString("\n", postfix = "\n") { if (it == 1000) latestInput else "row $it" }
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
