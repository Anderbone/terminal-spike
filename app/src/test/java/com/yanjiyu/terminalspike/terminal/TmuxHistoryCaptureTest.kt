package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.TmuxPaneCapture
import com.yanjiyu.terminalspike.terminal.model.TerminalColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxHistoryCaptureTest {
    @Test
    fun allFiveThousandPhysicalHistoryRowsSurviveInOrder() {
        val content = buildString {
            for (index in 1..5_000) append("TMUX_HISTORY_%04d\n".format(index))
        }.encodeToByteArray()

        val snapshot = parseTmuxHistoryCapture(
            capture(historyRows = 5_000, content = content),
        )

        requireNotNull(snapshot)
        assertEquals(5_000, snapshot.lines.size)
        snapshot.lines.forEachIndexed { index, line ->
            assertEquals("TMUX_HISTORY_%04d".format(index + 1), line.text)
        }
    }

    @Test
    fun sgrCaptureBecomesPhysicalRendererRowsWithoutJoiningHistory() {
        val snapshot = parseTmuxHistoryCapture(
            capture(
                historyRows = 3,
                content = "\u001B[31mred\u001B[0m\nplain\nlast\n".encodeToByteArray(),
            ),
        )

        requireNotNull(snapshot)
        assertEquals(listOf("red", "plain", "last"), snapshot.lines.map { it.text })
        assertEquals(TerminalColour.Indexed(1), snapshot.lines.first().runs.first().style.foreground)
        assertFalse(snapshot.remoteMousePassthrough)
        assertTrue(snapshot.authoritative)
    }

    @Test
    fun joinedTmuxWrapsAreRestoredAsSoftWrappedPhysicalRows() {
        val snapshot = parseTmuxHistoryCapture(
            capture(
                historyRows = 3,
                columns = 20,
                content = "https://example.test/a/very/long/path?q=1\n".encodeToByteArray(),
            ),
        )

        requireNotNull(snapshot)
        assertEquals(
            listOf("https://example.test", "/a/very/long/path?q=", "1"),
            snapshot.lines.map { it.text },
        )
        assertEquals(listOf(true, true, false), snapshot.lines.map { it.softWrappedToNext })
    }

    @Test
    fun alternatePaneWithoutMouseTrackingKeepsHistoryLocal() {
        val snapshot = parseTmuxHistoryCapture(
            capture(
                historyRows = 1,
                alternateScreenActive = true,
                content = "codex output\n".encodeToByteArray(),
            ),
        )

        requireNotNull(snapshot)
        assertFalse(snapshot.remoteMousePassthrough)
        assertEquals(listOf("codex output"), snapshot.lines.map { it.text })
    }

    @Test
    fun paneApplicationMouseTrackingKeepsScrollingRemote() {
        val snapshot = parseTmuxHistoryCapture(
            capture(
                historyRows = 0,
                content = byteArrayOf(),
                alternateScreenActive = true,
                mouseTrackingActive = true,
            ),
        )

        requireNotNull(snapshot)
        assertTrue(snapshot.remoteMousePassthrough)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test
    fun paneApplicationMouseTrackingStillParsesHistoryForTheLocalViewport() {
        val snapshot = parseTmuxHistoryCapture(
            capture(
                historyRows = 2,
                content = "old one\nold two\n".encodeToByteArray(),
                mouseTrackingActive = true,
            ),
        )

        requireNotNull(snapshot)
        assertTrue(snapshot.remoteMousePassthrough)
        assertEquals(listOf("old one", "old two"), snapshot.lines.map { it.text })
    }

    @Test
    fun lightweightSafeProbePublishesFreshnessWithoutInventingRows() {
        val snapshot = parseTmuxHistoryCapture(
            TmuxPaneCapture(
                sessionId = "\$1",
                paneId = "%2",
                columns = 80,
                rows = 24,
                historyRows = 5_000,
                alternateScreenActive = false,
                mouseTrackingActive = false,
                paneInMode = false,
                historyIncluded = false,
                truncatedBefore = false,
                authoritative = false,
                content = byteArrayOf(),
            ),
        )

        requireNotNull(snapshot)
        assertFalse(snapshot.historyIncluded)
        assertFalse(snapshot.remoteMousePassthrough)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test
    fun incompleteOrShortCaptureIsRejectedInsteadOfPublishingPartialHistory() {
        assertNull(
            parseTmuxHistoryCapture(
                capture(historyRows = 1, content = "missing final newline".encodeToByteArray()),
            ),
        )
        assertNull(
            parseTmuxHistoryCapture(
                capture(historyRows = 3, content = "one\ntwo\n".encodeToByteArray()),
            ),
        )
    }

    private fun capture(
        historyRows: Int,
        content: ByteArray,
        columns: Int = 80,
        alternateScreenActive: Boolean = false,
        mouseTrackingActive: Boolean = false,
    ) = TmuxPaneCapture(
        sessionId = "\$1",
        paneId = "%2",
        columns = columns,
        rows = 24,
        historyRows = historyRows,
        alternateScreenActive = alternateScreenActive,
        mouseTrackingActive = mouseTrackingActive,
        paneInMode = false,
        historyIncluded = true,
        truncatedBefore = false,
        authoritative = true,
        content = content,
    )
}
