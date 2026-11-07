package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry

/** Reconcile a bounded passive snapshot with the confirmed pane's newer visible rows at first drag. */
internal fun herdrHistoryWithLiveTail(source: HerdrPaneHistory, screen: List<TerminalLine>?): HerdrPaneHistory {
    if (source.offsetFromBottom != 0 || source.surface == null || screen == null ||
        source.rows < 3 || source.y < 0 || source.x < 0 || source.y + source.rows > screen.size
    ) return source
    val live = screen.subList(source.y, source.y + source.rows).map {
        TerminalLineGeometry.sliceColumns(it, source.x, source.x + source.columns) ?: return source
    }
    val keys = live.map { it.text.trimEnd() }
    if (keys.filter(String::isNotBlank).distinct().size < 3) return source
    var alignment: Pair<Int, Int>? = null
    // Only an advance smaller than one visible pane can be proved from this frame.
    // Require a unique overlap with three distinct nonblank rows; blanks, repeated
    // prompts, a redraw, or an unrelated pane must not invent a history continuation.
    for (liveStart in 0..live.size - 3) {
        for (start in maxOf(0, source.lines.size - (live.size - liveStart) + 1) until source.lines.size - 2) {
            val distinct = HashSet<String>()
            var matched = 0
            for (row in 0 until minOf(live.size - liveStart, source.lines.size - start)) {
                val key = source.lines[start + row].text.trimEnd()
                if (key != keys[liveStart + row]) break
                matched++
                if (key.isNotBlank()) distinct += key
            }
            if (distinct.size < 3 || keys.drop(liveStart + matched).none(String::isNotBlank)) continue
            val previous = alignment
            if (previous != null && previous.first - previous.second != start - liveStart) return source
            if (previous == null) alignment = start to liveStart
        }
    }
    val (start, liveStart) = alignment ?: return source
    // Keep known rows above the overlap. A transient Herdr warning is not pane
    // history and must not replace them or be inserted into the captured transcript.
    return source.copy(lines = (source.lines.take(start) + live.drop(liveStart)).takeLast(HerdrPaneHistory.ROW_LIMIT))
}
