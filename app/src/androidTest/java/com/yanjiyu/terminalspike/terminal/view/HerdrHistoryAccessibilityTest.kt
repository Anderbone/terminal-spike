package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFrameScheduler
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native pixels establish what is visible before comparing the real View's accessibility text. */
class HerdrHistoryAccessibilityTest {
    @Test fun fullPaneReaderAccessibilityMatchesActuallyDrawnPinnedRows() = checkReader(partialPane = false)

    @Test fun partialPaneReaderAccessibilityKeepsSidebarAndOmitsCoveredLiveRows() = checkReader(partialPane = true)

    @Suppress("DEPRECATION")
    private fun checkReader(partialPane: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val callbacks = ArrayDeque<Choreographer.FrameCallback>()
            val scheduler = object : TerminalFrameScheduler {
                override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.addLast(callback) }
            }
            val controller = TerminalController(TerminalBuffer(), scheduler)
            controller.updateRendererProfile(controller.rendererProfile.copy(cursorBlinkEnabled = false))
            val view = FastTerminalView(instrumentation.targetContext)
            val width = 1_000
            val height = 600
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            view.attachController(controller)
            val padding = 8f * view.resources.displayMetrics.density
            val columns = ((width - 2 * padding) / view.terminalCellWidthPx).toInt()
            val lineHeight = controller.viewport.lineHeightPx
            val rows = (height / lineHeight).toInt()
            assertTrue("Fixture needs enough screen geometry: ${columns}x$rows", columns >= 24 && rows >= 4)
            controller.reportTerminalSize(columns, rows)
            val paneX = if (partialPane) 10 else 0
            val paneY = if (partialPane) 1 else 0
            val paneRows = if (partialPane) rows - 2 else rows
            val liveRows = (0 until rows).map { row ->
                if (row in paneY until paneY + paneRows) {
                    (if (partialPane) "SIDE_%02d".format(row).padEnd(paneX) else "") +
                        "LIVE_ONLY_%02d".format(row)
                } else "CHROME_%02d".format(row)
            }
            val history = HerdrPaneHistory(
                identity = "accessibility-fixture",
                x = paneX,
                y = paneY,
                columns = columns - paneX,
                rows = paneRows,
                offsetFromBottom = 2,
                lines = (0 until paneRows + 2).map { TerminalLine.plain("PINNED_%02d".format(it)) },
            )
            val expectedRows = liveRows.mapIndexed { row, live ->
                if (row in paneY until paneY + paneRows) {
                    live.take(paneX) + history.lines[row - paneY].text
                } else live
            }
            val engine = VtTerminalEngine(columns, rows)
            val frame = engine.accept(buildString {
                append("\u001b[?25l")
                liveRows.forEachIndexed { row, text -> append("\u001b[${row + 1};1H$text") }
            }.encodeToByteArray())
            val bitmaps = mutableListOf<Bitmap>()
            fun publish() { while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(1L) }
            fun render() = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmaps += it
                view.draw(Canvas(it))
            }
            try {
                controller.updateTerminalFrame(frame)
                publish()
                val liveBitmap = render()
                val scroll = FastTerminalView::class.java.getDeclaredField("herdrScroll").run {
                    isAccessible = true
                    get(view) as HerdrNativeScroll
                }
                scroll.reader.begin(history, lineHeight)
                controller.herdrHistoryReading = true
                assertFalse("The reader must be pinned above live bottom", scroll.reader.holdingLatest)
                val pinnedBitmap = render()
                assertFalse("The pinned rows must actually change visible native pixels", liveBitmap.sameAs(pinnedBitmap))
                assertEquals(history.lines.take(paneRows).map { it.text }, view.herdrVisibleRowsForTesting().take(paneRows))
                val info = AccessibilityNodeInfo.obtain()
                val accessibleText = try {
                    view.onInitializeAccessibilityNodeInfo(info)
                    assertFalse("This test must not take the selected-text branch", info.actionList.any {
                        it.id == AccessibilityNodeInfo.ACTION_COPY
                    })
                    info.text?.toString().orEmpty()
                } finally {
                    info.recycle()
                }

                // Independent pixel reference: draw the expected composite as an ordinary screen.
                // This proves the pinned history, rather than covered live text, was on the Canvas.
                scroll.reader.clear()
                controller.herdrHistoryReading = false
                controller.updateTerminalFrame(frame.copy(screen = expectedRows.map { TerminalLine.plain(it) }))
                publish()
                val expectedBitmap = render()
                assertTrue("Pinned pixels must match history plus uncovered live chrome", pinnedBitmap.sameAs(expectedBitmap))

                assertFalse("Covered live output must not masquerade as visible accessibility text", "LIVE_ONLY_" in accessibleText)
                assertTrue("Pinned output must be readable without a selection", "PINNED_00" in accessibleText)
                if (partialPane) {
                    assertTrue("Sidebar outside the pane remains visible and readable", "SIDE_01" in accessibleText)
                    assertTrue("Chrome above the pane remains visible and readable", "CHROME_00" in accessibleText)
                }
                assertEquals("Accessibility text must describe the rows actually drawn", expectedRows.joinToString("\n"), accessibleText.trimEnd())
            } finally {
                bitmaps.forEach(Bitmap::recycle)
                controller.removeListener(view)
            }
        }
    }
}
