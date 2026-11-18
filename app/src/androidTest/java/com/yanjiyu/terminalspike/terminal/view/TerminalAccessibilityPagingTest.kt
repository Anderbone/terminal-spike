package com.yanjiyu.terminalspike.terminal.view

import android.view.Choreographer
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFrameScheduler
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionModel
import com.yanjiyu.terminalspike.terminal.selection.TerminalSelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real node info/actions, local reader and native View; no network or gesture routing fixture. */
class TerminalAccessibilityPagingTest {
    @Test fun pinnedReaderOwnsAccessibilityPagingAndFractionalTextWhileLiveViewportIsFixed() = fixture { f ->
        assertEquals(0f, f.controller.viewport.maximumScrollY, 0f)
        val initial = f.scroll.reader.viewport.scrollY
        f.scroll.reader.scrollBy(-3.25f)
        val fractional = f.scroll.reader.viewport.scrollY
        assertEquals(initial - 3.25f, fractional, 0.001f)
        val first = kotlin.math.floor(fractional / f.lineHeight).toInt()
        f.info { info ->
            assertTrue(info.isScrollable)
            assertTrue(info.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            assertTrue(info.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            assertTrue(info.text.toString().contains("PINNED_%03d".format(first)))
            assertFalse(info.text.toString().contains("LIVE_ONLY"))
        }
        assertTrue(f.view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(fractional - f.scroll.reader.viewport.viewportHeightPx,
            f.scroll.reader.viewport.scrollY, 0.001f)
        assertEquals(0f, f.controller.viewport.scrollY, 0f)
        f.scroll.reader.scrollTo(0f)
        f.info { assertFalse(it.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) }
        assertTrue(f.view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(f.scroll.reader.viewport.viewportHeightPx.toFloat(), f.scroll.reader.viewport.scrollY, 0.001f)
        assertEquals(0f, f.controller.viewport.scrollY, 0f)
        f.scroll.reader.scrollTo(f.scroll.reader.viewport.maximumScrollY)
        f.info { assertFalse(it.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) }
    }

    @Test fun invalidatedReaderCannotOwnAccessibilityOrReviveBeforeTheViewFrameCatchesUp() = fixture { f ->
        val stale = requireNotNull(f.scroll.reader.snapshot)
        val readerY = f.scroll.reader.viewport.scrollY
        f.invalidateReaderBeforeViewFrame()
        assertTrue("The view callback is intentionally still pending", f.scroll.reader.snapshot === stale)
        assertFalse(f.controller.herdrHistoryReading)
        f.info { info ->
            assertFalse(info.isScrollable)
            assertFalse(info.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            assertFalse(info.hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            assertTrue(info.text.toString().contains("LIVE_ONLY"))
            assertFalse(info.text.toString().contains("PINNED_"))
        }
        assertTrue(f.view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(readerY, f.scroll.reader.viewport.scrollY, 0f)
        assertFalse("Accessibility must not republish ownership of the invalidated pane", f.controller.herdrHistoryReading)
        assertEquals(0f, f.controller.viewport.scrollY, 0f)
    }

    @Test fun frozenSelectionKeepsItsTextAndOwnsPagingWithoutMovingTheReaderOrLiveViewport() = fixture { f ->
        val reader = f.scroll.reader
        val frozen = requireNotNull(TerminalSelectionSnapshot.capture(reader, 92))
        f.field("selectionSnapshot").set(f.view, frozen)
        f.field("selectionPane").set(f.view, reader.snapshot)
        val selection = f.field("selection").get(f.view) as TerminalSelectionModel
        assertTrue(selection.beginWord(frozen, 92, 0))
        val selected = requireNotNull(selection.selectedText(frozen))
        val selectionViewport = f.field("selectionViewport").get(f.view) as TerminalViewport
        selectionViewport.updateGeometry(reader.viewport.viewportHeightPx, f.lineHeight)
        selectionViewport.updateContent(frozen.selectionLineCount, null)
        selectionViewport.scrollTo(92 * f.lineHeight)
        val selectionY = selectionViewport.scrollY
        val readerY = reader.viewport.scrollY
        f.invalidateReaderBeforeViewFrame()
        assertFalse(f.controller.herdrHistoryReading)
        f.info {
            assertEquals(selected, it.text.toString())
            assertTrue(it.hasAction(AccessibilityNodeInfo.ACTION_COPY))
            assertTrue(it.isScrollable)
        }
        assertTrue(f.view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(selectionY - selectionViewport.viewportHeightPx, selectionViewport.scrollY, 0.001f)
        assertEquals(readerY, reader.viewport.scrollY, 0f)
        assertEquals(0f, f.controller.viewport.scrollY, 0f)
        f.info { assertEquals(selected, it.text.toString()) }
        assertFalse("The independently owned selection must not revive the invalidated reader", f.controller.herdrHistoryReading)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            try { block(f) } finally { f.controller.removeListener(f.view) }
        }
    }

    private class Fixture {
        private val callbacks = ArrayDeque<Choreographer.FrameCallback>()
        val controller = TerminalController(TerminalBuffer(), object : TerminalFrameScheduler {
            override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.addLast(callback) }
        })
        val view = FastTerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
        val scroll: HerdrNativeScroll
        val lineHeight: Float

        init {
            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 800, 400)
            view.attachController(controller)
            lineHeight = controller.viewport.lineHeightPx
            val columns = (600 / view.terminalCellWidthPx).toInt().coerceAtLeast(12)
            val rows = (controller.viewport.viewportHeightPx / lineHeight).toInt().coerceAtLeast(2)
            controller.reportTerminalSize(columns, rows)
            val frame = VtTerminalEngine(columns, rows).accept("\u001b[?25lLIVE_ONLY".encodeToByteArray())
            controller.updateTerminalFrame(frame)
            while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(1L)
            scroll = field("herdrScroll").get(view) as HerdrNativeScroll
            scroll.reader.begin(HerdrPaneHistory("a11y-paging", 0, 0, columns, rows, 4,
                List(100) { TerminalLine.plain("PINNED_%03d".format(it)) }), lineHeight)
            controller.herdrHistoryReading = true
        }

        fun invalidateReaderBeforeViewFrame() {
            controller.publishHerdrHistory(requireNotNull(scroll.reader.snapshot))
            controller.publishInputContext(TerminalInputContext(identity = "herdr/a-different-pane"))
            check(controller.herdrHistory == null)
            // Do not drain callbacks or draw: the native View still holds the obsolete snapshot.
        }

        fun field(name: String) = FastTerminalView::class.java.getDeclaredField(name).apply { isAccessible = true }

        @Suppress("DEPRECATION")
        fun info(block: (AccessibilityNodeInfo) -> Unit) {
            val info = AccessibilityNodeInfo.obtain()
            try { view.onInitializeAccessibilityNodeInfo(info); block(info) } finally { info.recycle() }
        }
    }

    private fun AccessibilityNodeInfo.hasAction(action: Int) = actionList.any { it.id == action }
}
