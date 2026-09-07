package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalBufferRebuilder
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

internal data class TmuxHistoryReconciliationContext(
    val paneId: String?,
    val metadataKnown: Boolean,
    val remoteHistoryRows: Int,
    val capturedStartRow: Int,
    val oldestAvailableRow: Int,
    val archivedRows: Int,
)

internal data class PreparedTmuxHistory(
    /** Null means that the currently published buffer remains authoritative. */
    val replacement: TerminalBuffer?,
    val historyChanged: Boolean,
    val reconciledHistory: Boolean,
    val acceptedOlderPage: Boolean,
    val archivedRows: Int,
    val capturedStartRow: Int,
)

internal data class TmuxReconciliationStep(
    val rowWork: Int,
    val complete: Boolean,
)

/**
 * Incrementally prepares one coherent tmux history replacement.
 *
 * The published [TerminalBuffer] is read but never mutated. Callers give this state machine a
 * hard row-work budget on each display frame, then atomically swap [PreparedTmuxHistory.replacement]
 * only after [TmuxReconciliationStep.complete] is true.
 */
internal class TmuxHistoryReconciliation(
    val snapshot: TmuxLocalHistorySnapshot,
    private val base: TerminalBuffer,
    private val context: TmuxHistoryReconciliationContext,
) {
    private enum class Phase {
        START,
        GROWING_COMPARE,
        RESET_PREFIX,
        RESET_SCAN,
        BUILD,
        COMPLETE,
    }

    private enum class BuildOrder {
        OLD_THEN_NEW,
        PREPENDED_THEN_OLD,
    }

    val paneChanged: Boolean = context.paneId != null && context.paneId != snapshot.paneId
    val replaceHistory: Boolean = snapshot.historyIncluded && (
        snapshot.authoritative || paneChanged || !context.metadataKnown ||
            context.remoteHistoryRows != snapshot.remoteHistoryRows
    )

    private var phase = Phase.START
    private var result: PreparedTmuxHistory? = null

    private var growingLocalStart = 0
    private var growingOverlapCount = 0
    private var growingUnchanged = 0

    private var prefixLengths = IntArray(0)
    private var prefixIndex = 1
    private var prefixMatched = 0
    private var historyScanIndex = 0
    private var historyMatched = 0

    private var builder: TerminalBufferRebuilder? = null
    private var buildOrder = BuildOrder.OLD_THEN_NEW
    private var oldIndex = 0
    private var oldEndExclusive = 0
    private var newIndex = 0
    private var newEndExclusive = 0
    private var buildReconciled = false
    private var buildAcceptedOlderPage = false
    private var buildArchivedRows = 0
    private var buildCapturedStartRow = 0

    fun step(maxRowWork: Int): TmuxReconciliationStep {
        require(maxRowWork > 0) { "tmux reconciliation budget must be positive" }
        var remaining = maxRowWork
        val initial = remaining

        while (phase != Phase.COMPLETE && remaining > 0) {
            when (phase) {
                Phase.START -> remaining -= start()
                Phase.GROWING_COMPARE -> {
                    if (growingUnchanged >= growingOverlapCount) {
                        beginSuffixBuild(
                            fromIndex = growingLocalStart + growingUnchanged,
                            replacementStart = growingUnchanged,
                            reconciled = true,
                            archivedRows = context.archivedRows,
                            capturedStartRow = context.capturedStartRow,
                        )
                    } else {
                        val same = requireNotNull(base.lineAt(growingLocalStart + growingUnchanged))
                            .samePayloadAs(snapshot.lines[growingUnchanged])
                        remaining -= 1
                        if (same) {
                            growingUnchanged += 1
                        } else if (growingUnchanged == 0) {
                            beginReplacementBuild()
                        } else {
                            beginSuffixBuild(
                                fromIndex = growingLocalStart + growingUnchanged,
                                replacementStart = growingUnchanged,
                                reconciled = true,
                                archivedRows = context.archivedRows,
                                capturedStartRow = context.capturedStartRow,
                            )
                        }
                    }
                }
                Phase.RESET_PREFIX -> remaining = prepareResetPrefix(remaining)
                Phase.RESET_SCAN -> remaining = scanResetOverlap(remaining)
                Phase.BUILD -> remaining = buildRows(remaining)
                Phase.COMPLETE -> Unit
            }
        }
        return TmuxReconciliationStep(
            rowWork = initial - remaining,
            complete = phase == Phase.COMPLETE,
        )
    }

    fun completedResult(): PreparedTmuxHistory {
        check(phase == Phase.COMPLETE) { "tmux reconciliation is not complete" }
        return requireNotNull(result)
    }

    private fun start(): Int {
        if (snapshot.olderPage) {
            val pageRows = snapshot.lines.size - 1
            val scalarMatch = !paneChanged && context.metadataKnown &&
                snapshot.remoteHistoryRows == context.remoteHistoryRows &&
                snapshot.oldestAvailableRow == context.oldestAvailableRow &&
                snapshot.capturedStartRow + snapshot.lines.size - 1 == context.capturedStartRow &&
                pageRows >= 0 && base.lineCount() + pageRows <= base.capacity
            if (!scalarMatch || snapshot.lines.isEmpty() || base.lineCount() == 0) {
                completeWithoutMutation(acceptedOlderPage = false)
                return 0
            }
            val overlap = snapshot.lines.last()
            if (!overlap.samePayloadAs(requireNotNull(base.lineAt(0)))) {
                completeWithoutMutation(acceptedOlderPage = false)
                return 1
            }
            beginPrependBuild(pageRows)
            return 1
        }

        if (paneChanged && !snapshot.historyIncluded) {
            beginReplacementBuild()
            return 0
        }
        if (!replaceHistory) {
            completeWithoutMutation(acceptedOlderPage = false)
            return 0
        }

        val samePaneCoordinateReset = !paneChanged && context.metadataKnown &&
            snapshot.remoteHistoryRows < context.remoteHistoryRows
        if (samePaneCoordinateReset &&
            snapshot.lines.size == snapshot.remoteHistoryRows - snapshot.capturedStartRow
        ) {
            if (base.lineCount() == 0 || snapshot.lines.isEmpty()) {
                beginResetBuild(overlap = 0)
            } else {
                prefixLengths = IntArray(snapshot.lines.size)
                prefixIndex = 1
                prefixMatched = 0
                phase = Phase.RESET_PREFIX
            }
            return 0
        }

        val cachedCount = base.lineCount() - context.archivedRows
        val growingShapeValid = context.metadataKnown &&
            snapshot.remoteHistoryRows >= context.remoteHistoryRows &&
            snapshot.oldestAvailableRow == context.oldestAvailableRow &&
            cachedCount == context.remoteHistoryRows - context.capturedStartRow &&
            snapshot.lines.size == snapshot.remoteHistoryRows - snapshot.capturedStartRow &&
            snapshot.capturedStartRow in context.capturedStartRow until context.remoteHistoryRows &&
            snapshot.lines.isNotEmpty()
        if (!growingShapeValid) {
            beginReplacementBuild()
            return 0
        }
        growingLocalStart = context.archivedRows +
            snapshot.capturedStartRow - context.capturedStartRow
        growingOverlapCount = minOf(
            base.lineCount() - growingLocalStart,
            snapshot.lines.size,
        )
        if (growingOverlapCount <= 0) {
            beginReplacementBuild()
            return 0
        }
        growingUnchanged = 0
        phase = Phase.GROWING_COMPARE
        return 0
    }

    private fun prepareResetPrefix(available: Int): Int {
        var remaining = available
        while (prefixIndex < snapshot.lines.size && remaining > 0) {
            val same = snapshot.lines[prefixIndex].samePayloadAs(snapshot.lines[prefixMatched])
            remaining -= 1
            if (same) {
                prefixMatched += 1
                prefixLengths[prefixIndex] = prefixMatched
                prefixIndex += 1
            } else if (prefixMatched > 0) {
                prefixMatched = prefixLengths[prefixMatched - 1]
            } else {
                prefixLengths[prefixIndex] = 0
                prefixIndex += 1
            }
        }
        if (prefixIndex == snapshot.lines.size) {
            historyScanIndex = (base.lineCount() - snapshot.lines.size).coerceAtLeast(0)
            historyMatched = 0
            phase = Phase.RESET_SCAN
        }
        return remaining
    }

    private fun scanResetOverlap(available: Int): Int {
        var remaining = available
        val historyCount = base.lineCount()
        while (historyScanIndex < historyCount && remaining > 0) {
            val line = requireNotNull(base.lineAt(historyScanIndex))
            val same = line.samePayloadAs(snapshot.lines[historyMatched])
            remaining -= 1
            if (same) {
                historyMatched += 1
                historyScanIndex += 1
                if (historyMatched == snapshot.lines.size && historyScanIndex < historyCount) {
                    historyMatched = prefixLengths[historyMatched - 1]
                }
            } else if (historyMatched > 0) {
                historyMatched = prefixLengths[historyMatched - 1]
            } else {
                historyScanIndex += 1
            }
        }
        if (historyScanIndex == historyCount) beginResetBuild(historyMatched)
        return remaining
    }

    private fun beginResetBuild(overlap: Int) {
        val archivedBeforeCapacityTrim = base.lineCount() - overlap
        beginSuffixBuild(
            fromIndex = base.lineCount(),
            replacementStart = overlap,
            reconciled = true,
            archivedRows = archivedBeforeCapacityTrim,
            capturedStartRow = snapshot.capturedStartRow,
        )
    }

    private fun beginSuffixBuild(
        fromIndex: Int,
        replacementStart: Int,
        reconciled: Boolean,
        archivedRows: Int,
        capturedStartRow: Int,
    ) {
        val replacementRows = snapshot.lines.size - replacementStart
        val droppedPrefixRows = (fromIndex + replacementRows - base.capacity).coerceAtLeast(0)
        val droppedArchivedRows = minOf(droppedPrefixRows, archivedRows)
        if (fromIndex == base.lineCount() && replacementRows == 0 && droppedPrefixRows == 0) {
            result = PreparedTmuxHistory(
                replacement = null,
                historyChanged = false,
                reconciledHistory = reconciled,
                acceptedOlderPage = false,
                archivedRows = archivedRows,
                capturedStartRow = capturedStartRow,
            )
            phase = Phase.COMPLETE
            return
        }
        val seed = base.rebuildSeed()
        builder = TerminalBufferRebuilder(
            seed = seed,
            oldestRowOrdinal = seed.oldestRowOrdinal + droppedPrefixRows,
        )
        buildOrder = BuildOrder.OLD_THEN_NEW
        oldIndex = droppedPrefixRows
        oldEndExclusive = fromIndex
        newIndex = replacementStart
        newEndExclusive = snapshot.lines.size
        buildReconciled = reconciled
        buildAcceptedOlderPage = false
        buildArchivedRows = archivedRows - droppedArchivedRows
        buildCapturedStartRow = capturedStartRow + droppedPrefixRows - droppedArchivedRows
        phase = Phase.BUILD
    }

    private fun beginReplacementBuild() {
        val seed = base.rebuildSeed()
        builder = TerminalBufferRebuilder(
            seed = seed,
            oldestRowOrdinal = seed.nextId,
        )
        buildOrder = BuildOrder.OLD_THEN_NEW
        oldIndex = 0
        oldEndExclusive = 0
        newIndex = 0
        newEndExclusive = snapshot.lines.size
        buildReconciled = false
        buildAcceptedOlderPage = false
        buildArchivedRows = 0
        buildCapturedStartRow = snapshot.capturedStartRow
        phase = Phase.BUILD
        if (newEndExclusive == 0) finishBuild()
    }

    private fun beginPrependBuild(pageRows: Int) {
        val seed = base.rebuildSeed()
        builder = TerminalBufferRebuilder(
            seed = seed,
            oldestRowOrdinal = seed.oldestRowOrdinal - pageRows,
            prependedRows = pageRows,
        )
        buildOrder = BuildOrder.PREPENDED_THEN_OLD
        newIndex = 0
        newEndExclusive = pageRows
        oldIndex = 0
        oldEndExclusive = base.lineCount()
        buildReconciled = false
        buildAcceptedOlderPage = true
        buildArchivedRows = context.archivedRows
        buildCapturedStartRow = snapshot.capturedStartRow
        phase = Phase.BUILD
        if (pageRows == 0 && oldEndExclusive == 0) finishBuild()
    }

    private fun buildRows(available: Int): Int {
        var remaining = available
        val currentBuilder = requireNotNull(builder)
        while (remaining > 0) {
            val appended = when (buildOrder) {
                BuildOrder.OLD_THEN_NEW -> when {
                    oldIndex < oldEndExclusive -> {
                        currentBuilder.appendRetained(requireNotNull(base.lineAt(oldIndex++)))
                        true
                    }
                    newIndex < newEndExclusive -> {
                        currentBuilder.appendNew(snapshot.lines[newIndex++])
                        true
                    }
                    else -> false
                }
                BuildOrder.PREPENDED_THEN_OLD -> when {
                    newIndex < newEndExclusive -> {
                        currentBuilder.appendPrepended(snapshot.lines[newIndex++])
                        true
                    }
                    oldIndex < oldEndExclusive -> {
                        currentBuilder.appendRetained(requireNotNull(base.lineAt(oldIndex++)))
                        true
                    }
                    else -> false
                }
            }
            if (!appended) {
                finishBuild()
                break
            }
            remaining -= 1
        }
        if (phase == Phase.BUILD &&
            oldIndex >= oldEndExclusive && newIndex >= newEndExclusive
        ) {
            finishBuild()
        }
        return remaining
    }

    private fun finishBuild() {
        result = PreparedTmuxHistory(
            replacement = requireNotNull(builder).finish(),
            historyChanged = true,
            reconciledHistory = buildReconciled,
            acceptedOlderPage = buildAcceptedOlderPage,
            archivedRows = buildArchivedRows,
            capturedStartRow = buildCapturedStartRow,
        )
        phase = Phase.COMPLETE
    }

    private fun completeWithoutMutation(acceptedOlderPage: Boolean) {
        result = PreparedTmuxHistory(
            replacement = null,
            historyChanged = false,
            reconciledHistory = false,
            acceptedOlderPage = acceptedOlderPage,
            archivedRows = context.archivedRows,
            capturedStartRow = context.capturedStartRow,
        )
        phase = Phase.COMPLETE
    }
}

private fun TerminalLine.samePayloadAs(other: TerminalLine): Boolean =
    text == other.text && runs == other.runs && softWrappedToNext == other.softWrappedToNext
