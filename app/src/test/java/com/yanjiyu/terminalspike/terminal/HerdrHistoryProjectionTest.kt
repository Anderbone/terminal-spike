package com.yanjiyu.terminalspike.terminal

import android.view.Choreographer
import com.yanjiyu.terminalspike.connection.HerdrPaneArea
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.view.HerdrHistoryViewport
import org.junit.Assert.*
import org.junit.Test

class HerdrHistoryProjectionTest {
    @Test fun groupedSidebarStillRequiresTheTabStripAndExactSurfaceSize() {
        val fixture = Fixture(88, 34)
        fixture.controller.publishHerdrHistory(fixture.history(62, 33))
        fixture.draw("\u001B[2J\u001B[H spaces\u001B[1;26H\u001B[38;5;244m«\u001B[0m")
        assertNull("A collapse-like glyph alone is not enough", fixture.controller.herdrHistoryForDisplay())
        fixture.draw("\u001B[1;27H tab \u001B[38;5;245m + \u001B[0m")
        assertNotNull(fixture.controller.herdrHistoryForDisplay())
        fixture.controller.publishHerdrHistory(fixture.history(61, 33))
        assertNull("Another client's pane must not be stretched", fixture.controller.herdrHistoryForDisplay())
    }

    @Test fun groupedSidebarUsesItsCollapseControlAndTabBarWithoutAVerticalRule() {
        for (header in listOf("spaces", "machines", "agents · active", "a scrolled workspace")) {
            for (bottom in listOf(false, true)) {
                val fixture = Fixture(88, 34)
                fixture.draw(buildString {
                    append("\u001B[2J\u001B[H$header\u001B[1;26H\u001B[38;5;244m«\u001B[0m")
                    append("\u001B[${if (bottom) 34 else 1};27H tab \u001B[38;5;245m + \u001B[0m")
                })
                fixture.controller.publishHerdrHistory(fixture.history(62, 33))
                val projected = requireNotNull(fixture.controller.herdrHistoryForDisplay())
                assertEquals(26, projected.x)
                assertEquals(if (bottom) 0 else 1, projected.y)
                assertEquals(62, projected.columns)
                assertEquals(33, projected.rows)
            }
        }
    }

    @Test fun anIncompatibleClientReadDuringChromeRedrawCannotReplaceTheEstablishedSource() {
        val fixture = Fixture()
        fixture.desktop(0)
        val source = fixture.history(62, 30)
        fixture.controller.publishHerdrHistory(source)
        val projected = requireNotNull(fixture.controller.herdrHistoryForDisplay())
        fixture.controller.herdrHistoryReading = true
        fixture.controller.herdrHistoryPinned = true
        fixture.draw("\u001B[1;1H\u001B[2K")
        val foreign = source.copy(rows = 15, surface = HerdrPaneArea(0, 0, 62, 15))
        fixture.controller.publishHerdrHistoryRead(com.yanjiyu.terminalspike.connection.HerdrHistoryRead.Available(foreign), source)
        assertSame(source, fixture.controller.herdrHistory)
        assertEquals(projected, fixture.controller.herdrHistoryForDisplay())
    }

    @Test fun incompleteChromeFrameKeepsOnlyAnAlreadyPinnedSamePaneProjection() {
        val fixture = Fixture()
        fixture.desktop(0)
        fixture.controller.publishHerdrHistory(fixture.history(62, 30))
        val source = requireNotNull(fixture.controller.herdrHistoryForDisplay())
        fixture.controller.herdrHistoryReading = true
        fixture.controller.herdrHistoryPinned = true
        fixture.draw("\u001B[1;1H\u001B[2K")
        assertEquals(source, fixture.controller.herdrHistoryForDisplay())
        fixture.controller.herdrHistoryReading = false
        fixture.controller.herdrHistoryPinned = false
        assertNull("An idle pane cannot infer chrome from an earlier reader", fixture.controller.herdrHistoryForDisplay())
    }

    @Test fun pinnedProjectionStillRejectsPaneReplacementResizeAndIncompatibleCapture() {
        for (change in listOf("pane", "geometry", "grid", "removed")) {
            val fixture = Fixture()
            fixture.desktop(0)
            val source = fixture.history(62, 30)
            fixture.controller.publishHerdrHistory(source)
            assertNotNull(fixture.controller.herdrHistoryForDisplay())
            fixture.controller.herdrHistoryReading = true
            fixture.controller.herdrHistoryPinned = true
            fixture.draw("\u001B[1;1H\u001B[2K")
            when (change) {
                "pane" -> fixture.controller.publishHerdrHistory(source.copy(identity = "default/w1:p2/term2"))
                "geometry" -> fixture.controller.publishHerdrHistory(source.copy(rows = 15, surface = HerdrPaneArea(0, 0, 62, 15)))
                "grid" -> fixture.controller.reportTerminalSize(44, 30)
                "removed" -> fixture.controller.publishHerdrHistory(null)
            }
            assertNull("A $change change must invalidate the pinned projection", fixture.controller.herdrHistoryForDisplay())
        }
    }

    @Test fun anotherClientsSuccessfulReadDoesNotReplaceThisClientsWorkingHistory() {
        val fixture = Fixture()
        fixture.desktop(0)
        val source = fixture.history(62, 30)
        fixture.controller.publishHerdrHistory(source)
        val reader = HerdrHistoryViewport()
        reader.begin(requireNotNull(fixture.controller.herdrHistoryForDisplay()), 20f)
        reader.scrollBy(-123.25f)
        val anchor = reader.viewport.scrollY
        for ((columns, rows) in listOf(62 to 15, 80 to 30)) {
            val foreign = source.copy(columns = columns, rows = rows, surface = HerdrPaneArea(0, 0, columns, rows))
            fixture.controller.publishHerdrHistoryRead(com.yanjiyu.terminalspike.connection.HerdrHistoryRead.Available(foreign), source)
            assertSame(source, fixture.controller.herdrHistory)
            assertTrue(reader.retainSource(fixture.controller.herdrHistoryForDisplay()))
            assertEquals(anchor, reader.viewport.scrollY, 0f)
        }
        val replacement = source.copy(identity = "default/w1:p2/term2")
        fixture.controller.publishHerdrHistoryRead(com.yanjiyu.terminalspike.connection.HerdrHistoryRead.Available(replacement), source)
        assertSame(replacement, fixture.controller.herdrHistory)
        assertFalse(reader.retainSource(fixture.controller.herdrHistoryForDisplay()))
    }

    @Test fun desktopHistoryOccupiesOnlyTheRightHandOutputIncludingItsLastColumn() {
        for (tabRow in listOf(0, 30, null)) {
            val fixture = Fixture()
            fixture.desktop(tabRow)
            val rows = if (tabRow == null) 31 else 30
            val source = fixture.history(62, rows)
            fixture.controller.publishHerdrHistory(source)
            val displayed = requireNotNull(fixture.controller.herdrHistoryForDisplay())
            assertEquals(26, displayed.x)
            assertEquals(if (tabRow == 0) 1 else 0, displayed.y)
            assertEquals(88, displayed.x + displayed.columns)
            assertSame(source.lines, displayed.lines)
            assertSame("Reuse the projection between touch events", displayed, fixture.controller.herdrHistoryForDisplay())
            assertSame("API coordinates stay raw for side-channel reuse", source, fixture.controller.herdrHistory)
        }
    }

    @Test fun splitPaneOffsetsAreAddedOnceWithoutExpandingTheFocusedPane() {
        val fixture = Fixture()
        fixture.desktop(0)
        val source = fixture.history(62, 30).copy(x = 32, y = 7, columns = 30, rows = 23)
        fixture.controller.publishHerdrHistory(source)
        val displayed = requireNotNull(fixture.controller.herdrHistoryForDisplay())
        assertEquals(58, displayed.x)
        assertEquals(8, displayed.y)
        assertEquals(30, displayed.columns)
        assertEquals(23, displayed.rows)
    }

    @Test fun legacyOuterCoordinatesAreNotTranslatedAgain() {
        val fixture = Fixture()
        fixture.desktop(0)
        for (surface in listOf(null, HerdrPaneArea(26, 1, 62, 30))) {
            val source = fixture.history(62, 30).copy(x = 26, y = 1, surface = surface)
            fixture.controller.publishHerdrHistory(source)
            assertSame(source, fixture.controller.herdrHistoryForDisplay())
        }
    }

    @Test fun mobileHistoryStartsBelowTheLiveHeaderAndNeverCoversTheSwitcher() {
        val fixture = Fixture(44, 30)
        val source = fixture.history(44, 28)
        fixture.controller.publishHerdrHistory(source)
        fixture.mobile("switch")
        assertEquals(2, requireNotNull(fixture.controller.herdrHistoryForDisplay()).y)
        for (button in listOf("close", "×")) {
            fixture.mobile(button)
            assertNull("The switcher covers the pane despite unchanged API geometry", fixture.controller.herdrHistoryForDisplay())
        }
        fixture.mobile("switch")
        assertEquals(2, requireNotNull(fixture.controller.herdrHistoryForDisplay()).y)
    }

    @Test fun mobileHistoryRespectsAllFourNavigationCorners() {
        val fixture = Fixture(44, 30)
        fixture.controller.publishHerdrHistory(fixture.history(44, 28))
        for (bottom in listOf(false, true)) {
            for (left in listOf(false, true)) {
                val row = if (bottom) 30 else 2
                val button = if (left) "switch │ status" else "status                            │ switch"
                fixture.draw("\u001B[2J\u001B[${row};1H$button")
                val history = requireNotNull(fixture.controller.herdrHistoryForDisplay())
                assertEquals(if (bottom) 0 else 2, history.y)
                assertEquals(28, history.rows)
            }
        }
    }

    @Test fun mobileTopTabsRemainOutsideHistoryAtEverySwitchCorner() {
        val fixture = Fixture(44, 30)
        fixture.controller.publishHerdrHistory(fixture.history(44, 27))
        for (bottom in listOf(false, true)) {
            for (left in listOf(false, true)) {
                val row = if (bottom) 30 else 3
                val button = if (left) "switch │ status" else "status                            │ switch"
                fixture.draw("\u001B[2J\u001B[H tab one \u001B[1;16H\u001B[32m + \u001B[0m\u001B[${row};1H$button")
                val history = requireNotNull(fixture.controller.herdrHistoryForDisplay())
                assertEquals(if (bottom) 1 else 3, history.y)
                assertEquals(27, history.rows)
            }
        }
    }

    @Test fun unknownGeometryAndBrokenChromeDoNotPlaceHistoryAtTheScreenOrigin() {
        val fixture = Fixture()
        fixture.controller.publishHerdrHistory(fixture.history(62, 30))
        assertNull(fixture.controller.herdrHistoryForDisplay())
        fixture.desktop(0)
        assertNotNull(fixture.controller.herdrHistoryForDisplay())
        fixture.draw("\u001B[12;26H ")
        assertNull(fixture.controller.herdrHistoryForDisplay())
        fixture.desktop(0)
        fixture.controller.publishHerdrHistory(fixture.history(61, 30))
        assertNull("Another client's width must not be stretched or guessed", fixture.controller.herdrHistoryForDisplay())
        fixture.controller.publishHerdrHistory(fixture.history(88, 31))
        assertNull("Another client's surface can coincide with this client's outer grid", fixture.controller.herdrHistoryForDisplay())
        fixture.controller.publishHerdrHistory(fixture.history(62, 30).copy(x = 50, columns = 30))
        assertNull("A pane must fit its API surface", fixture.controller.herdrHistoryForDisplay())
    }

    @Test fun foldingUnfoldingAndTabPositionChangesInvalidateThePinnedReader() {
        val fixture = Fixture()
        fixture.desktop(0)
        fixture.controller.publishHerdrHistory(fixture.history(62, 30))
        val reader = HerdrHistoryViewport()
        reader.begin(requireNotNull(fixture.controller.herdrHistoryForDisplay()), 20f)
        reader.scrollBy(-123.25f)
        val anchor = reader.viewport.scrollY
        fixture.draw("\u001B[9;40Hnew output")
        assertTrue(reader.retainSource(fixture.controller.herdrHistoryForDisplay()))
        assertEquals(anchor, reader.viewport.scrollY, 0f)
        fixture.desktop(30)
        assertFalse(reader.retainSource(fixture.controller.herdrHistoryForDisplay()))
        assertNull(reader.snapshot)
        fixture.controller.reportTerminalSize(44, 30)
        assertNull("Do not display the old desktop capture on a folded phone", fixture.controller.herdrHistoryForDisplay())
    }

    private class Fixture(val columns: Int = 88, val rows: Int = 31) {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(), scheduler).apply {
            reportTerminalSize(columns, rows)
            herdrSessionActive = true
        }
        private val engine = VtTerminalEngine(columns, rows)

        fun history(width: Int, height: Int) = HerdrPaneHistory(
            "default/w1:p1/term1", 0, 0, width, height, 0,
            (1..1000).map { TerminalLine.plain("row $it") },
            surface = HerdrPaneArea(0, 0, width, height),
        )

        fun draw(output: String) {
            controller.updateTerminalFrame(engine.accept(output.toByteArray()))
            scheduler.drainAll()
        }

        fun desktop(tabRow: Int?) = draw(buildString {
            append("\u001B[2J\u001B[H spaces")
            for (row in 1..rows) append("\u001B[$row;26H│")
            if (tabRow != null) append("\u001B[${tabRow + 1};27H tab \u001B[32m + \u001B[0m")
        })

        fun mobile(button: String) = draw("\u001B[2J\u001B[Hworkspace\u001B[2;1Htab\u001B[2;35H│ $button")
    }

    private class ManualFrameScheduler : TerminalFrameScheduler {
        private val callbacks = ArrayDeque<Choreographer.FrameCallback>()
        override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.addLast(callback) }
        fun drainAll() {
            while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(1L)
        }
    }
}
