package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFrameScheduler
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/** Draw through the production View, controller and parser; inspect native pixels. */
class FastTerminalUnicodeRenderingTest {
    @Test fun asciiPrefixKeepsTheFollowingMarkerVisible() = checkMarker("M")

    @Test fun combiningPrefixKeepsTheFollowingMarkerVisible() = checkMarker("M\u0301")

    @Test fun supplementaryPrefixKeepsTheFollowingMarkerVisible() = checkMarker("\uD834\uDD1E")

    @Test fun widePrefixKeepsTheFollowingMarkerVisible() = checkMarker("界", 2)

    @Test fun joinedEmojiPrefixKeepsTheFollowingMarkerVisible() = checkMarker("\uD83D\uDC69\u200D\uD83D\uDCBB", 2)

    @Test fun flagPrefixKeepsTheFollowingMarkerVisible() = checkMarker("\uD83C\uDDEC\uD83C\uDDE7", 2)

    @Test fun oversizedCombiningPrefixKeepsTheFollowingMarkerVisible() = checkMarker("M" + "\u0301".repeat(128))

    @Test fun legacySymbolsAfterFlagsUseTheirActualTerminalColumns() =
        checkMarker("\uD83C\uDDEC\uD83C\uDDE7", 2, "\uE0B0".repeat(3))

    private fun checkMarker(cell: String, cellColumns: Int = 1, marker: String = "ZZZ") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val callbacks = ArrayDeque<Choreographer.FrameCallback>()
            val scheduler = object : TerminalFrameScheduler {
                override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.addLast(callback) }
            }
            val controller = TerminalController(TerminalBuffer(), scheduler)
            controller.updateRendererProfile(controller.rendererProfile.copy(cursorBlinkEnabled = false))
            val view = FastTerminalView(instrumentation.targetContext)
            val width = 600
            val height = 240
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            view.attachController(controller)
            val padding = 8f * view.resources.displayMetrics.density
            val cellWidth = view.terminalCellWidthPx
            val lineHeight = controller.viewport.lineHeightPx
            val columns = ((width - 2 * padding) / cellWidth).toInt()
            val rows = (height / lineHeight).toInt()
            assertTrue("Fixture needs enough cells: $columns", columns > 12)
            controller.reportTerminalSize(columns, rows)
            val engine = VtTerminalEngine(columns, rows)
            fun publish() { while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(1L) }
            val bitmaps = mutableListOf<Bitmap>()
            fun render() = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmaps.add(it)
                view.draw(Canvas(it))
            }
            try {
                controller.updateTerminalFrame(engine.accept("\u001b[?25l".toByteArray()))
                publish()
                val prefixCount = (columns - 5) / cellColumns
                val prefixCells = prefixCount * cellColumns
                val prefix = cell.repeat(prefixCount)
                controller.updateTerminalFrame(engine.accept(prefix.toByteArray()))
                publish()
                val beforeMarker = render()
                val text = prefix + marker
                val frame = engine.accept(marker.toByteArray())
                assertTrue("Model must retain all text", frame.screen.first().text == text)
                controller.updateTerminalFrame(frame)
                publish()
                val actual = render()
                // Independent grid reference: the same marker alone at its explicit terminal column.
                controller.updateTerminalFrame(frame.copy(screen = frame.screen.mapIndexed { row, line ->
                    if (row == 0) TerminalLine.styled(listOf(TerminalRun(marker,
                        startColumn = prefixCells, columnWidth = 3))) else line
                }))
                publish()
                val expected = render()
                val changed = IntArray(3)
                var misplaced = 0
                for (markerCell in changed.indices) {
                    val left = ceil(padding + (prefixCells + markerCell) * cellWidth).toInt()
                    val right = floor(padding + (prefixCells + markerCell + 1) * cellWidth).toInt().coerceAtMost(width)
                    for (y in 0 until ceil(lineHeight).toInt().coerceAtMost(height)) {
                        for (x in left until right) {
                            if (actual.getPixel(x, y) != beforeMarker.getPixel(x, y)) changed[markerCell]++
                            if (actual.getPixel(x, y) != expected.getPixel(x, y)) misplaced++
                        }
                    }
                }
                assertTrue("Each marker cell needs visible ink: cell=$cell columns=$columns changed=${changed.toList()}",
                    changed.all { it > 10 })
                assertTrue("Marker must match its explicit grid position: cell=$cell misplaced=$misplaced", misplaced == 0)
            } finally {
                bitmaps.forEach(Bitmap::recycle)
                controller.removeListener(view)
            }
        }
    }
}
