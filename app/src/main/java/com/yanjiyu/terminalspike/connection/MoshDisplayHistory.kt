package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.model.TerminalLine

/**
 * Retains primary-screen rows that Mosh displayed and later replaced with an upward-shifted frame.
 *
 * Mosh's framebuffer renderer can express remote scrolling as cursor-addressed row rewrites. The
 * VT engine correctly applies those rewrites but cannot report scrollback because no VT scroll
 * operation occurred. Comparing consecutive Mosh-only screen snapshots recovers only a stable
 * visual upward shift; arbitrary repaints and alternate-screen applications are never inferred.
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
            frame.completedScrollback.isNotEmpty()
        ) {
            return frame
        }

        val shift = upwardShift(previous, frame.screen) ?: return frame
        val displaced = previous.take(shift)
        return frame.copy(completedScrollback = displaced)
    }

    fun reset(frame: TerminalFrameUpdate) {
        previousPrimaryScreen = frame.screen.takeUnless { frame.alternateScreen }
    }

    private fun upwardShift(
        previous: List<TerminalLine>,
        current: List<TerminalLine>,
    ): Int? {
        val maximumShift = previous.size - MINIMUM_MATCHED_ROWS
        if (maximumShift < 1) return null
        return (1..maximumShift).firstOrNull { shift ->
            var displacedContent = false
            var displacedIndex = 0
            while (!displacedContent && displacedIndex < shift) {
                displacedContent = previous[displacedIndex].text.isNotBlank()
                displacedIndex += 1
            }
            if (!displacedContent) return@firstOrNull false

            var matchedRows = 0
            while (
                shift + matchedRows < previous.size &&
                previous[shift + matchedRows].sameVisualContent(current[matchedRows])
            ) {
                matchedRows += 1
            }
            var trailingRowsAreBlank = true
            var trailingIndex = shift + matchedRows
            while (trailingRowsAreBlank && trailingIndex < previous.size) {
                trailingRowsAreBlank = previous[trailingIndex].text.isBlank()
                trailingIndex += 1
            }
            matchedRows >= MINIMUM_MATCHED_ROWS && trailingRowsAreBlank
        }
    }

    private fun TerminalLine.sameVisualContent(other: TerminalLine): Boolean =
        text == other.text &&
            softWrappedToNext == other.softWrappedToNext &&
            runs == other.runs

    private companion object {
        const val MINIMUM_MATCHED_ROWS = 2
    }
}
