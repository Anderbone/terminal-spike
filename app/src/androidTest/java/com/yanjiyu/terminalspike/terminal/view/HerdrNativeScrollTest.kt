package com.yanjiyu.terminalspike.terminal.view

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.*
import org.junit.Test

/** Native event regressions; these are not actual-Codex or physical smoothness acceptance. */
class HerdrNativeScrollTest {
    @Test fun repeatedSwipesPastLiveBottomNeverOpenHistoryOrStartFling() = onMain {
        val gesture = Gesture()
        repeat(5) {
            gesture.down()
            assertTrue(gesture.move(200f - gesture.distance))
            assertNull(gesture.scroll.reader.snapshot)
            assertTrue(gesture.up(200f - gesture.distance * 2))
            assertFalse(gesture.scroll.animate())
            assertNull(gesture.scroll.reader.snapshot)
        }
    }

    @Test fun returningLiveDuringDragCannotReopenHistoryOnReversalOrRelease() = onMain {
        val gesture = Gesture()
        gesture.down()
        gesture.move(200f + gesture.distance)
        val reader = gesture.scroll.reader
        assertNotNull(reader.snapshot)
        val before = reader.viewport.scrollY
        gesture.move(200f + gesture.distance + 3.25f)
        assertEquals(before - 3.25f, reader.viewport.scrollY, 0.01f)
        gesture.scroll.updateSource(gesture.source.copy(lines = List(1000) { TerminalLine.plain("new $it") }))
        assertSame(gesture.source, reader.snapshot)
        assertEquals(before - 3.25f, reader.viewport.scrollY, 0.01f)
        gesture.move(100f)
        assertNull(reader.snapshot) // Return live before ACTION_UP.
        gesture.move(300f) // Same gesture reverses toward history.
        assertNull(reader.snapshot)
        assertTrue(gesture.up(400f))
        assertFalse(gesture.scroll.animate())
        assertNull(reader.snapshot)
        gesture.down()
        gesture.move(200f + gesture.distance)
        assertNotNull(reader.snapshot) // A new older-history gesture still works.
        gesture.cancel()
    }

    @Test fun repeatedOldestBoundaryGesturesRetainEveryRowAndCatchBoundaryFling() = onMain {
        val gesture = Gesture()
        repeat(5) {
            gesture.down()
            assertTrue(gesture.move(30000f))
            assertEquals(0f, gesture.scroll.reader.viewport.scrollY, 0f)
            gesture.up(31000f)
            gesture.scroll.animate()
            assertSame(gesture.source, gesture.scroll.reader.snapshot)
            assertEquals(0f, gesture.scroll.reader.viewport.scrollY, 0f)
            gesture.scroll.updateSource(gesture.source.copy(lines = List(1000) { TerminalLine.plain("new $it") }))
        }
        assertEquals((1..1000).map { "row $it" }, gesture.scroll.reader.snapshot!!.lines.map { it.text })
        gesture.down() // Catch any remaining animation without losing the reader.
        assertFalse(gesture.scroll.animate())
        assertSame(gesture.source, gesture.scroll.reader.snapshot)
        gesture.cancel()
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Gesture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scroll = HerdrNativeScroll(context)
        val distance = ViewConfiguration.get(context).scaledTouchSlop + 40f
        val source = HerdrPaneHistory("default/w1:p1/term1", 0, 0, 80, 24, 0,
            (1..1000).map { TerminalLine.plain("row $it") })
        private var time = 1000L
        private var downTime = time

        fun down(): Boolean {
            downTime = time + 10
            return event(MotionEvent.ACTION_DOWN, 200f)
        }
        fun move(y: Float) = event(MotionEvent.ACTION_MOVE, y)
        fun up(y: Float) = event(MotionEvent.ACTION_UP, y)
        fun cancel() = event(MotionEvent.ACTION_CANCEL, 200f)

        private fun event(action: Int, y: Float): Boolean {
            time += 10
            val event = MotionEvent.obtain(downTime, time, action, 100f, y, 0)
            return try {
                scroll.touch(event, source, 10f, 20f, 0f, 0f)
            } finally {
                event.recycle()
            }
        }
    }
}
