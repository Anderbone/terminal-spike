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
    @Test fun lateHistoryUsesTheOriginalDownAndKeepsSubRowMotionFlingAndCatch() = onMain {
        val gesture = Gesture()
        gesture.availableSource = null
        gesture.down()
        gesture.availableSource = gesture.source
        assertTrue(gesture.move(200f + gesture.distance))
        val before = gesture.scroll.reader.viewport.scrollY
        gesture.move(203.25f + gesture.distance)
        assertEquals(before - 3.25f, gesture.scroll.reader.viewport.scrollY, 0.01f)
        gesture.move(300f + gesture.distance)
        gesture.up(340f + gesture.distance)
        val released = gesture.scroll.reader.viewport.scrollY
        android.os.SystemClock.sleep(32)
        assertTrue(gesture.scroll.animate())
        assertTrue(gesture.scroll.reader.viewport.scrollY < released)
        gesture.down()
        val caught = gesture.scroll.reader.viewport.scrollY
        android.os.SystemClock.sleep(32)
        assertFalse(gesture.scroll.animate())
        assertEquals(caught, gesture.scroll.reader.viewport.scrollY, 0f)
        gesture.cancel()
    }

    @Test fun aLateSourceCannotCaptureADownOutsideItsPaneOrAnEndedGesture() = onMain {
        val gesture = Gesture()
        gesture.availableSource = null
        gesture.down()
        gesture.availableSource = gesture.source.copy(x = 20)
        assertFalse(gesture.move(200f + gesture.distance))
        assertNull(gesture.scroll.reader.snapshot)
        gesture.up(200f + gesture.distance)
        gesture.availableSource = gesture.source
        assertFalse(gesture.move(400f))
        assertNull(gesture.scroll.reader.snapshot)
    }

    @Test fun stationaryHistoryTapDispatchesLocallyButDragAndFlingCatchDoNot() = onMain {
        val gesture = Gesture()
        gesture.scroll.reader.begin(gesture.source, 20f)
        gesture.scroll.reader.scrollBy(-500f)
        gesture.down()
        gesture.move(201f)
        gesture.up(201f)
        assertEquals(listOf(100f to 201f), gesture.taps)
        gesture.down()
        gesture.move(200f + gesture.distance)
        gesture.move(300f + gesture.distance)
        gesture.up(340f + gesture.distance)
        gesture.down() // Catch must not open whichever link passed beneath the finger.
        gesture.up(200f)
        assertEquals(1, gesture.taps.size)
        gesture.down()
        gesture.cancel()
        assertEquals(1, gesture.taps.size)
    }

    @Test fun cancellationPreservesPinnedPixelsAndNextDragUntilAnActualSourceInvalidation() = onMain {
        val gesture = Gesture()
        gesture.down()
        gesture.move(200f + gesture.distance + 3.25f)
        val anchor = gesture.scroll.reader.viewport.scrollY
        gesture.cancel()
        assertSame(gesture.source, gesture.scroll.reader.snapshot)
        assertEquals(anchor, gesture.scroll.reader.viewport.scrollY, 0f)
        assertFalse(gesture.scroll.animate())
        gesture.down()
        gesture.move(200f + gesture.distance)
        val moved = gesture.scroll.reader.viewport.scrollY
        gesture.move(203.25f + gesture.distance)
        assertEquals(moved - 3.25f, gesture.scroll.reader.viewport.scrollY, 0.01f)
        gesture.scroll.updateSource(null)
        assertNull(gesture.scroll.reader.snapshot)
    }

    @Test fun repeatedTapsWithSubSlopMotionKeepTheFractionalHistoryAnchor() = onMain {
        for (offset in listOf(0, 6)) {
            val gesture = Gesture(offset)
            gesture.down()
            gesture.move(200f + gesture.distance + 3.25f)
            gesture.up(200f + gesture.distance + 3.25f)
            gesture.down() // Stop the preceding fling before testing stationary reading.
            val reader = gesture.scroll.reader
            val anchor = reader.viewport.scrollY
            repeat(5) {
                assertTrue(gesture.move(201f)) // Finger jitter below touch slop.
                assertTrue(gesture.up(201f))
                assertSame(gesture.source, reader.snapshot)
                assertEquals(anchor, reader.viewport.scrollY, 0f)
                assertFalse(reader.viewport.autoFollow)
                assertFalse(gesture.scroll.animate())
                gesture.scroll.updateSource(gesture.source.copy(
                    lines = List(1000) { TerminalLine.plain("new $it") },
                ))
                assertSame(gesture.source, reader.snapshot)
                assertEquals(anchor, reader.viewport.scrollY, 0f)
                if (it < 4) assertTrue(gesture.down())
            }
        }
    }

    @Test fun liftingTheFingerAfterCatchingAFlingKeepsHistoryAndAllowsAnotherDrag() = onMain {
        val gesture = Gesture()
        gesture.down()
        gesture.move(200f + gesture.distance)
        gesture.move(300f + gesture.distance)
        gesture.up(340f + gesture.distance)
        android.os.SystemClock.sleep(32)
        assertTrue(gesture.scroll.animate())
        assertTrue(gesture.down())
        val reader = gesture.scroll.reader
        val anchor = reader.viewport.scrollY
        assertTrue(gesture.up(200f))
        assertSame(gesture.source, reader.snapshot)
        assertEquals(anchor, reader.viewport.scrollY, 0f)
        assertFalse(gesture.scroll.animate())
        assertTrue(gesture.down())
        gesture.move(200f + gesture.distance)
        val dragged = reader.viewport.scrollY
        gesture.move(203.25f + gesture.distance)
        assertEquals(dragged - 3.25f, reader.viewport.scrollY, 0.01f)
        gesture.move(-30000f)
        assertNull("Dragging to live bottom must still release history", reader.snapshot)
        gesture.up(-30000f)
    }

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

    @Test fun remoteOffsetStillHasSubRowDragReleaseFlingAndTouchCatch() = onMain {
        val gesture = Gesture(offset = 6)
        gesture.down()
        gesture.move(200f + gesture.distance)
        val viewport = gesture.scroll.reader.viewport
        val before = viewport.scrollY
        gesture.move(203.25f + gesture.distance)
        assertEquals(before - 3.25f, viewport.scrollY, 0.01f)
        gesture.move(300f + gesture.distance)
        gesture.up(340f + gesture.distance)
        val released = viewport.scrollY
        android.os.SystemClock.sleep(32)
        assertTrue(gesture.scroll.animate())
        assertTrue("Motion must continue after release", viewport.scrollY < released)
        gesture.down()
        val caught = viewport.scrollY
        android.os.SystemClock.sleep(32)
        assertFalse(gesture.scroll.animate())
        assertEquals(caught, viewport.scrollY, 0f)
        gesture.move(-30000f)
        assertTrue(gesture.scroll.reader.holdingLatest)
        gesture.up(-30000f)
        assertNotNull("Bottom cannot fall back to the older remote screen", gesture.scroll.reader.snapshot)
        gesture.cancel()
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class Gesture(offset: Int = 0) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scroll = HerdrNativeScroll(context)
        val taps = mutableListOf<Pair<Float, Float>>()
        val distance = ViewConfiguration.get(context).scaledTouchSlop + 40f
        val source = HerdrPaneHistory("default/w1:p1/term1", 0, 0, 80, 24, offset,
            (1..1000).map { TerminalLine.plain("row $it") })
        var availableSource: HerdrPaneHistory? = source
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
                scroll.touch(event, availableSource, 10f, 20f, 0f, 0f) { x, y -> taps += x to y }
            } finally {
                event.recycle()
            }
        }
    }
}
