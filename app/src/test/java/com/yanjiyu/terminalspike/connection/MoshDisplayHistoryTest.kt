package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshDisplayHistoryTest {
    @Test
    fun wrapMetadataDoesNotHideAVisualShiftAndRetainedMetadataIsPreserved() {
        val engine = VtTerminalEngine(columns = 20, rows = 4)
        val history = MoshDisplayHistory()
        val first = engine.accept(rewriteScreen("001", "002", "003", "input"))
        history.retainDisplayedRows(
            first.copy(screen = first.screen.mapIndexed { index, line ->
                TerminalLine.styled(line.runs, softWrappedToNext = index == 0 || index == 2)
            }),
        )
        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("002", "003", "004", "input")),
        )
        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
        assertTrue(retained.completedScrollback.single().softWrappedToNext)
    }

    @Test
    fun blankTopPaddingDoesNotDiscard200DeliveredCodexTableRows() =
        assertPaddedCodexTableHistory(stationaryAnnotation = false)

    @Test
    fun stationaryAnnotationsOnTableRowsDoNotDiscard200DeliveredRows() =
        assertPaddedCodexTableHistory(stationaryAnnotation = true)

    private fun assertPaddedCodexTableHistory(stationaryAnnotation: Boolean) {
        val engine = VtTerminalEngine(columns = 40, rows = 21)
        val history = MoshDisplayHistory()
        val transcript = (1..200).flatMap {
            listOf("------------------", "CODEX_SCROLL_%03d".format(it))
        }
        val retained = mutableListOf<String>()
        var last = engine.accept(byteArrayOf())
        for (end in 18..transcript.size step 2) {
            val visibleTable = transcript.subList(end - 18, end).toMutableList()
            if (stationaryAnnotation) {
                visibleTable[visibleTable.lastIndex - 1] += " stationary annotation"
            }
            last = history.retainDisplayedRows(
                engine.accept(
                    rewriteScreen(
                        *(listOf("") + visibleTable +
                            listOf("Ask Codex to do anything", "status")).toTypedArray(),
                    ),
                ),
            )
            retained += last.completedScrollback.map { it.text.trimEnd() }
            assertTrue(last.screen.first().text.isBlank())
            assertEquals("status", last.screen.last().text.trimEnd())
        }
        retained += last.screen.map { it.text.trimEnd() }
        assertEquals(
            (1..200).map { "CODEX_SCROLL_%03d".format(it) },
            retained.filter { it.startsWith("CODEX_SCROLL_") },
        )
    }

    @Test
    fun explicitInteriorScrollBelowBlankPaddingNeverBecomesGenericHistory() {
        for (operation in listOf("\u001B[S", "\u001B[T", "\u001B[2;1H\u001B[M", "\u001B[2;1H\u001B[L")) {
            val engine = VtTerminalEngine(columns = 20, rows = 6)
            val history = MoshDisplayHistory()
            history.retainDisplayedRows(
                engine.accept(rewriteScreen("", "001", "002", "003", "004", "input")),
            )
            val retained = history.retainDisplayedRows(
                engine.accept("\u001B[2;5r$operation\u001B[r".encodeToByteArray()),
            )
            assertTrue(retained.completedScrollback.isEmpty())
        }
    }

    @Test
    fun explicitMovementSignalDoesNotSuppressLaterFramebufferRecovery() {
        val engine = VtTerminalEngine(columns = 20, rows = 6)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(
            engine.accept(rewriteScreen("", "001", "002", "003", "004", "input")),
        )
        val retained = history.retainDisplayedRows(
            engine.accept("\u001B[2;5r\u001B[S\u001B[r".encodeToByteArray()),
        )
        assertTrue(retained.completedScrollback.isEmpty())
        val rewritten = history.retainDisplayedRows(
            engine.accept(rewriteScreen("", "003", "004", "005", "006", "input")),
        )
        assertEquals(listOf("002"), rewritten.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun fiftyDeliveredTableRowsWithRepeatedSeparatorsSurviveAboveFixedInput() {
        val engine = VtTerminalEngine(columns = 40, rows = 17)
        val history = MoshDisplayHistory()
        val transcript = (1..50).flatMap { listOf("--------", it.toString(), "") }
        val retained = mutableListOf<String>()
        var last = engine.accept(byteArrayOf())
        for (end in 15..transcript.size step 3) {
            last = history.retainDisplayedRows(
                engine.accept(
                    rewriteScreen(
                        *(transcript.subList(end - 15, end) +
                            listOf("Ask Codex to do anything", "status")).toTypedArray(),
                    ),
                ),
            )
            retained += last.completedScrollback.map { it.text.trimEnd() }
        }
        retained += last.screen.map { it.text.trimEnd() }
        assertEquals(transcript, retained.dropLast(2))
    }

    @Test
    fun codexStyleFramebufferWithFixedInputRetainsAll200DeliveredRowsInOrder() {
        for (rowCount in listOf(50, 200)) {
            val engine = VtTerminalEngine(columns = 40, rows = 18)
            val history = MoshDisplayHistory()
            val retained = mutableListOf<String>()
            val footer = listOf("", "Ask Codex to do anything", "context remaining")
            var last = engine.accept(byteArrayOf())
            // Fifteen output rows and three stationary input/status rows. Mosh delivers
            // cursor-addressed rewrites rather than the server's original scroll events.
            for (newest in 15..rowCount) {
                val output = (newest - 14..newest).map { "CODEX_SCROLL_%03d".format(it) }
                last = history.retainDisplayedRows(
                    engine.accept(rewriteScreen(*(output + footer).toTypedArray())),
                )
                retained += last.completedScrollback.map { it.text.trimEnd() }
                assertEquals(footer, last.screen.takeLast(3).map { it.text.trimEnd() })
            }
            retained += last.screen.map { it.text.trimEnd() }
            assertEquals(
                (1..rowCount).map { "CODEX_SCROLL_%03d".format(it) },
                retained.filter { it.startsWith("CODEX_SCROLL_") },
            )
            assertTrue(last.screen.none { it.text.trimEnd() == "CODEX_SCROLL_001" })
        }
    }

    @Test
    fun fixedInputAllowsMultipleDisplacedRowsAndTrailingOutputBlanks() {
        val engine = VtTerminalEngine(columns = 20, rows = 7)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(
            engine.accept(rewriteScreen("001", "002", "003", "004", "", "", "Ask Codex")),
        )
        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("003", "004", "005", "006", "", "", "Ask Codex")),
        )
        assertEquals(listOf("001", "002"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun scrollingBelowAStationaryHeaderDoesNotBecomeGenericHistory() {
        val engine = VtTerminalEngine(columns = 20, rows = 5)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(
            engine.accept(rewriteScreen("header", "001", "002", "003", "input")),
        )
        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("header", "002", "003", "004", "input")),
        )
        assertTrue(retained.completedScrollback.isEmpty())
    }

    @Test
    fun repeatedDecorationsAreNotEnoughToInferScrollingAboveFixedInput() {
        val engine = VtTerminalEngine(columns = 20, rows = 5)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(
            engine.accept(rewriteScreen("old", "---", "---", "---", "input")),
        )
        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("---", "---", "new", "---", "input")),
        )
        assertTrue(retained.completedScrollback.isEmpty())
    }

    @Test
    fun anUnobservedBurstCannotBeReconstructedFromOnlyItsFinalScreen() {
        val engine = VtTerminalEngine(columns = 20, rows = 4)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept(rewriteScreen("ready", "", "", "input")))
        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("048", "049", "050", "input")),
        )
        assertTrue(retained.completedScrollback.isEmpty())
        assertEquals(listOf("048", "049", "050", "input"), retained.screen.map { it.text.trimEnd() })
    }

    @Test
    fun retainsRowsFromAnExactFramebufferStyleUpwardRewrite() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        val first = engine.accept(rewriteScreen("001", "002", "003"))
        val shifted = engine.accept(rewriteScreen("002", "003", "004"))

        assertTrue(shifted.completedScrollback.isEmpty())
        history.retainDisplayedRows(first)

        val retained = history.retainDisplayedRows(shifted)

        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun retainsRowsWhenAFormerTrailingBlankBecomesNewOutput() {
        val engine = VtTerminalEngine(columns = 12, rows = 4)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept(rewriteScreen("001", "002", "003", "")))

        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("002", "003", "004", "")),
        )

        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun doesNotTurnAnArbitraryFramebufferRepaintIntoHistory() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept(rewriteScreen("one", "two", "three")))

        val repainted = history.retainDisplayedRows(
            engine.accept(rewriteScreen("red", "green", "blue")),
        )

        assertTrue(repainted.completedScrollback.isEmpty())
    }

    @Test
    fun doesNotDuplicateScrollbackAlreadyReportedByTheVtEngine() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept("001\r\n002\r\n003".encodeToByteArray()))

        val parsedScroll = engine.accept("\r\n004".encodeToByteArray())
        val retained = history.retainDisplayedRows(parsedScroll)

        assertEquals(parsedScroll.completedScrollback, retained.completedScrollback)
        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    private fun rewriteScreen(vararg rows: String): ByteArray = buildString {
        rows.forEachIndexed { index, row ->
            append("\u001B[${index + 1};1H\u001B[2K$row")
        }
    }.encodeToByteArray()
}
