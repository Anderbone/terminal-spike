package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class HerdrTerminalContainerTest {
    @Test fun hiddenSidebarReturnsItsWidthAndAndroidMapsTouchesToTheOriginalGrid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val container = HerdrTerminalContainer(instrumentation.targetContext)
            val terminal = container.terminal
            container.hiddenSidebarColumns = 26
            fun measure() {
                container.measure(
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                )
                container.layout(0, 0, 800, 600)
            }
            measure()
            val hidden = ceil(26 * terminal.terminalCellWidthPx).toInt()
            assertEquals(800, container.width)
            assertEquals(800 + hidden, terminal.width)
            assertEquals(-hidden, terminal.left)
            assertEquals(800, terminal.right)
            var touchedX = -1f
            terminal.setOnTouchListener { _, event -> touchedX = event.x; true }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            assertTrue(container.dispatchTouchEvent(event))
            event.recycle()
            assertEquals(hidden + 20f, touchedX, 0.01f)

            container.hiddenSidebarColumns = 0
            measure()
            assertSame(terminal, container.terminal)
            assertEquals(0, terminal.left)
            assertEquals(800, terminal.width)
        }
    }
}
