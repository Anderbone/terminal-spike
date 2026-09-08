package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

/**
 * Retains primary-screen rows that Mosh displayed and later replaced with an upward-shifted frame.
 *
 * Mosh's framebuffer renderer can express remote scrolling as cursor-addressed row rewrites. The
 * VT engine correctly applies those rewrites but cannot report scrollback because no VT scroll
 * operation occurred. Comparing consecutive Mosh-only screen snapshots recovers only a stable
 * visual upward shift, including one above a stationary input/status area. This is conservative
 * visual inference, not a replacement for server history: unobserved Mosh states are unrecoverable.
 */
internal class MoshDisplayHistory {
    private var previousPrimaryScreen: List<TerminalLine>? = null

    fun retainDisplayedRows(frame: TerminalFrameUpdate): TerminalFrameUpdate {
        if (frame.alternateScreen) {
            previousPrimaryScreen = null
            return frame
        }

        val previous = previousPrimaryScreen
        previousPrimaryScreen = frame.screen
        if (
            previous == null ||
            previous.size != frame.screen.size ||
            frame.clearScrollbackRequested ||
            frame.hasExplicitVerticalMovement ||
            frame.completedScrollback.isNotEmpty()
        ) {
            return frame
        }

        val displacedRows = upwardShift(previous, frame.screen) ?: return frame
        val displaced = previous.slice(displacedRows)
        return frame.copy(completedScrollback = displaced)
    }

    fun reset(frame: TerminalFrameUpdate) {
        previousPrimaryScreen = frame.screen.takeUnless { frame.alternateScreen }
    }

    private fun upwardShift(
        previous: List<TerminalLine>,
        current: List<TerminalLine>,
    ): IntRange? {
        findUpwardShift(previous, current, 0, previous.size)?.let { return 0 until it }

        // Inline TUIs leave their input at the same screen coordinates while output above it
        // scrolls. Those nonblank footer rows are not part of the shifted output comparison.
        var outputEnd = previous.size
        while (
            outputEnd > 0 &&
            previous[outputEnd - 1].sameVisualContent(current[outputEnd - 1])
        ) {
            outputEnd -= 1
        }
        if (outputEnd == previous.size) return null
        // Mosh can leave blank top padding in place while rewriting the entire output below it.
        // Skip only blank padding; a stationary text header still prevents generic recovery.
        var outputStart = 0
        while (
            outputStart < outputEnd && previous[outputStart].text.isBlank() &&
            current[outputStart].text.isBlank()
        ) {
            outputStart += 1
        }
        val shift = findUpwardShift(previous, current, outputStart, outputEnd) ?: return null
        return outputStart until outputStart + shift
    }

    private fun findUpwardShift(
        previous: List<TerminalLine>,
        current: List<TerminalLine>,
        outputStart: Int,
        outputEnd: Int,
    ): Int? {
        val maximumShift = outputEnd - outputStart - MINIMUM_MATCHED_ROWS
        if (maximumShift < 1) return null
        return (1..maximumShift).firstOrNull { shift ->
            var displacedContent = false
            var displacedIndex = outputStart
            while (!displacedContent && displacedIndex < outputStart + shift) {
                displacedContent = previous[displacedIndex].text.isNotBlank()
                displacedIndex += 1
            }
            if (!displacedContent) return@firstOrNull false

            var comparedRows = 0
            var matchedRows = 0
            var skippedStationaryRows = false
            var firstMovingText: String? = null
            var distinctMovingContent = false
            while (outputStart + shift + comparedRows < outputEnd) {
                val source = outputStart + shift + comparedRows
                val destination = outputStart + comparedRows
                if (previous[source].sameVisualContent(current[destination])) {
                    matchedRows += 1
                    val text = current[destination].text
                    if (text.isNotBlank() && text != previous[destination].text) {
                        if (firstMovingText == null) firstMovingText = text
                        else if (text != firstMovingText) distinctMovingContent = true
                    }
                } else if (
                    matchedRows >= MINIMUM_MATCHED_ROWS &&
                    previous[source].sameVisualContent(current[source]) &&
                    previous[destination].sameVisualContent(current[destination])
                ) {
                    // Mosh can keep decorations/annotations at fixed coordinates inside a
                    // scrolling table. Neither unchanged row is evidence of movement; require
                    // independent moving text rows below before accepting the inferred shift.
                    skippedStationaryRows = true
                } else {
                    break
                }
                comparedRows += 1
            }
            var trailingRowsAreBlank = true
            var trailingIndex = outputStart + shift + comparedRows
            while (trailingRowsAreBlank && trailingIndex < outputEnd) {
                trailingRowsAreBlank = previous[trailingIndex].text.isBlank()
                trailingIndex += 1
            }
            if (matchedRows < MINIMUM_MATCHED_ROWS || !trailingRowsAreBlank) {
                return@firstOrNull false
            }
            // Stationary separators and blank padding must never be the scroll evidence.
            if ((outputEnd < previous.size || skippedStationaryRows) && !distinctMovingContent) {
                return@firstOrNull false
            }
            true
        }
    }

    // Cursor-addressed Mosh redraws can leave a logical wrap link from older content on a row.
    // It is not a visual difference. Preserve the original metadata on retained rows, but compare
    // their text, styling, hyperlinks, and cell geometry when recognizing screen movement.
    private fun TerminalLine.sameVisualContent(other: TerminalLine): Boolean =
        text == other.text && runs == other.runs

    private companion object {
        const val MINIMUM_MATCHED_ROWS = 2
    }
}
