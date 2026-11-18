package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineAnchor
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace

/** Synthetic stable references for JVM fixtures; production snapshots retain controller anchors. */
internal fun terminalTranscriptSnapshot(
    lines: List<TerminalLine>,
    revision: Long = 0L,
    sourceId: Long = 0L,
): TerminalTranscriptSnapshot {
    require(revision >= 0L)
    require(sourceId >= 0L)
    val first = (lines.size - TerminalTranscriptSnapshot.MAX_LINES).coerceAtLeast(0)
    return TerminalTranscriptSnapshot(
        sourceId = sourceId,
        revision = revision,
        rows = lines.subList(first, lines.size).mapIndexed { index, line ->
            TerminalTranscriptRow(
                anchor = TerminalLineAnchor(
                    space = TerminalLineSpace.VOLATILE_SCREEN,
                    id = revision,
                    volatileRow = first + index,
                ),
                line = line,
            )
        },
        truncatedBefore = first > 0,
    )
}
