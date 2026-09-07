package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TmuxHistoryReconciliationTest {
    @Test
    fun randomizedGrowingSnapshotsMatchTheReferenceSuffixReconciliation() {
        val random = Random(0x544d5558)
        repeat(200) { iteration ->
            val capacity = random.nextInt(24, 65)
            val baseCount = random.nextInt(8, capacity + 1)
            val base = TerminalBuffer(capacity = capacity)
            val baseText = List(baseCount) { "base-$iteration-$it" }
            base.append(baseText.map(TerminalLine::plain))
            val added = random.nextInt(0, 9)
            val remoteText = baseText + List(added) { "added-$iteration-$it" }
            val capturedStart = random.nextInt(0, baseCount)
            val snapshotText = remoteText.drop(capturedStart).toMutableList()
            if (snapshotText.size > 1 && random.nextBoolean()) {
                val changed = random.nextInt(1, snapshotText.size)
                snapshotText[changed] = "changed-$iteration-$changed"
            }
            val firstMismatch = snapshotText.indices.firstOrNull { index ->
                val localIndex = capturedStart + index
                localIndex < baseText.size && baseText[localIndex] != snapshotText[index]
            }
            val overlapRows = minOf(baseCount - capturedStart, snapshotText.size)
            val expected = if (firstMismatch == 0) {
                snapshotText
            } else {
                val unchanged = firstMismatch ?: overlapRows
                baseText.take(capturedStart + unchanged) + snapshotText.drop(unchanged)
            }.takeLast(capacity)
            val reconciliation = TmuxHistoryReconciliation(
                snapshot = snapshot(
                    lines = snapshotText.map(TerminalLine::plain),
                    remoteRows = remoteText.size,
                    capturedStart = capturedStart,
                ),
                base = base,
                context = context(remoteRows = baseCount),
            )

            drain(reconciliation, budget = random.nextInt(1, 8))
            val result = reconciliation.completedResult()
            val actual = result.replacement?.snapshot()?.map { it.text } ?: baseText

            assertEquals("iteration $iteration", expected, actual)
            assertEquals("iteration $iteration base changed", baseText, base.snapshot().map { it.text })
        }
    }

    @Test
    fun randomizedCoordinateResetsMatchTheReferenceEpochJoin() {
        val random = Random(0x52455345)
        repeat(200) { iteration ->
            val capacity = random.nextInt(24, 65)
            val baseCount = random.nextInt(16, capacity + 1)
            val baseText = List(baseCount) { "old-$iteration-$it" }
            val overlap = random.nextInt(0, minOf(10, baseCount - 6) + 1)
            val newRows = random.nextInt(5, minOf(10, baseCount - overlap - 1) + 1)
            val incomingText = baseText.takeLast(overlap) +
                List(newRows) { "new-$iteration-$it" }
            check(incomingText.size < baseCount)
            val base = TerminalBuffer(capacity = capacity)
            base.append(baseText.map(TerminalLine::plain))
            val reconciliation = TmuxHistoryReconciliation(
                snapshot = snapshot(
                    lines = incomingText.map(TerminalLine::plain),
                    remoteRows = incomingText.size,
                ),
                base = base,
                context = context(remoteRows = baseCount),
            )

            drain(reconciliation, budget = random.nextInt(1, 8))
            val actual = requireNotNull(reconciliation.completedResult().replacement)
                .snapshot().map { it.text }

            assertEquals(
                "iteration $iteration",
                (baseText + incomingText.drop(overlap)).takeLast(capacity),
                actual,
            )
            assertEquals("iteration $iteration base changed", baseText, base.snapshot().map { it.text })
        }
    }

    @Test
    fun capacityTrimPreservesRetainedIdsAndAdvancesRemoteCoordinates() {
        val base = TerminalBuffer(capacity = 5)
        val stored = base.append((0..4).map { TerminalLine.plain("row-$it") })
        val reconciliation = TmuxHistoryReconciliation(
            snapshot = snapshot(
                lines = (3..7).map { TerminalLine.plain("row-$it") },
                remoteRows = 8,
                capturedStart = 3,
            ),
            base = base,
            context = context(remoteRows = 5),
        )

        val frameWork = drain(reconciliation, budget = 2)
        val result = reconciliation.completedResult()
        val replacement = requireNotNull(result.replacement)

        assertTrue(frameWork.size > 1)
        assertTrue(frameWork.all { it in 1..2 })
        assertEquals((3..7).map { "row-$it" }, replacement.snapshot().map { it.text })
        assertEquals(stored[3].id, replacement.lineAt(0)?.id)
        assertEquals(stored[4].id, replacement.lineAt(1)?.id)
        assertEquals(3, result.capturedStartRow)
        assertEquals(3L, replacement.oldestRowOrdinal())
        assertEquals((0..4).map { "row-$it" }, base.snapshot().map { it.text })
    }

    @Test
    fun fullBufferRejectsAnOlderPageWithoutMutationOrCrash() {
        val base = TerminalBuffer(capacity = 5)
        base.append((5..9).map { TerminalLine.plain("row-$it") })
        val reconciliation = TmuxHistoryReconciliation(
            snapshot = snapshot(
                lines = (0..5).map { TerminalLine.plain("row-$it") },
                remoteRows = 10,
                capturedStart = 0,
                olderPage = true,
            ),
            base = base,
            context = context(remoteRows = 10, capturedStart = 5),
        )

        val step = reconciliation.step(maxRowWork = 2)
        val result = reconciliation.completedResult()

        assertTrue(step.complete)
        assertEquals(0, step.rowWork)
        assertFalse(result.acceptedOlderPage)
        assertNull(result.replacement)
        assertEquals((5..9).map { "row-$it" }, base.snapshot().map { it.text })
    }

    @Test
    fun adversarialResetNeverExceedsItsComparisonAndBuildBudget() {
        val base = TerminalBuffer(capacity = 64)
        base.append(List(40) { TerminalLine.plain(if (it == 39) "b" else "a") })
        val incoming = List(24) { TerminalLine.plain(if (it == 23) "c" else "a") }
        val reconciliation = TmuxHistoryReconciliation(
            snapshot = snapshot(lines = incoming, remoteRows = incoming.size),
            base = base,
            context = context(remoteRows = 40),
        )

        val frameWork = drain(reconciliation, budget = 3)

        assertTrue(frameWork.size > 1)
        assertTrue(frameWork.all { it in 1..3 })
        assertTrue(reconciliation.completedResult().reconciledHistory)
        assertEquals(64, reconciliation.completedResult().replacement?.lineCount())
    }

    private fun drain(
        reconciliation: TmuxHistoryReconciliation,
        budget: Int,
    ): List<Int> {
        val frameWork = mutableListOf<Int>()
        while (true) {
            val step = reconciliation.step(budget)
            if (step.rowWork > 0) frameWork += step.rowWork
            if (step.complete) return frameWork
        }
    }

    private fun snapshot(
        lines: List<TerminalLine>,
        remoteRows: Int,
        capturedStart: Int = 0,
        olderPage: Boolean = false,
    ) = TmuxLocalHistorySnapshot(
        sessionId = "\$1",
        paneId = "%2",
        lines = lines,
        remoteHistoryRows = remoteRows,
        capturedStartRow = capturedStart,
        olderPage = olderPage,
        remoteMousePassthrough = false,
        historyIncluded = true,
        authoritative = !olderPage,
        truncatedBefore = capturedStart > 0,
    )

    private fun context(
        remoteRows: Int,
        capturedStart: Int = 0,
    ) = TmuxHistoryReconciliationContext(
        paneId = "%2",
        metadataKnown = true,
        remoteHistoryRows = remoteRows,
        capturedStartRow = capturedStart,
        oldestAvailableRow = 0,
        archivedRows = 0,
    )
}
