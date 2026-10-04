package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import java.net.URI
import java.util.Locale

enum class TerminalLinkSource {
    OSC8,
    PLAIN_TEXT,
}

data class TerminalLinkTarget(
    val uri: String,
    val id: String?,
    val source: TerminalLinkSource,
    val line: TerminalLineAnchor,
    val startColumn: Int,
    val endColumn: Int,
    val endLine: TerminalLineAnchor = line,
)

enum class TerminalLinkAction {
    OPEN,
    COPY,
}

data class TerminalLinkActionRequest(
    val action: TerminalLinkAction,
    val target: TerminalLinkTarget,
)

fun interface TerminalLinkActionCallback {
    fun onLinkAction(request: TerminalLinkActionRequest)
}

/** External-open allowlist. Remote terminal output never bypasses this check. */
object TerminalLinkPolicy {
    fun canOpen(uri: String): Boolean {
        if (uri.isEmpty() || uri.length > TerminalHyperlink.MAX_URI_LENGTH) return false
        if (
            uri.any { character ->
                character.isISOControl() ||
                    character.isWhitespace() ||
                    character == '\uFFFD' ||
                    Character.getType(character) == Character.FORMAT.toInt()
            }
        ) {
            return false
        }
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme in ALLOWED_SCHEMES && !parsed.host.isNullOrBlank()
    }

    private val ALLOWED_SCHEMES = setOf("http", "https")
}

object TerminalLinkResolver {
    fun find(
        selectable: TerminalSelectableLine,
        column: Int,
        osc8Enabled: Boolean,
        plainTextUrlsEnabled: Boolean,
    ): TerminalLinkTarget? {
        if (osc8Enabled) findOsc8(selectable, column)?.let { return it }
        if (plainTextUrlsEnabled) return findPlainTextUrl(selectable, column)
        return null
    }

    /** Resolves one logical link across terminal rows joined by soft wrapping. */
    fun find(
        source: TerminalSelectionSource,
        row: Int,
        column: Int,
        osc8Enabled: Boolean,
        plainTextUrlsEnabled: Boolean,
    ): TerminalLinkTarget? {
        if (source.selectionLineAt(row) == null) return null
        if (osc8Enabled) findWrappedOsc8(source, row, column)?.let { return it }
        if (plainTextUrlsEnabled) {
            findPaintedPlainTextUrl(source, row, column)?.let { return it }
            return findWrappedPlainTextUrl(source, row, column)
        }
        return null
    }

    private fun findOsc8(selectable: TerminalSelectableLine, column: Int): TerminalLinkTarget? {
        var fallbackColumn = 0
        selectable.line.runs.forEach { run ->
            val columns = TerminalLineGeometry.runColumns(run, fallbackColumn)
            fallbackColumn = maxOf(fallbackColumn, columns.last + 1)
            val hyperlink = run.hyperlink ?: return@forEach
            if (column in columns) {
                return TerminalLinkTarget(
                    uri = hyperlink.uri,
                    id = hyperlink.id,
                    source = TerminalLinkSource.OSC8,
                    line = selectable.anchor,
                    startColumn = columns.first,
                    endColumn = columns.last + 1,
                )
            }
        }
        return null
    }

    private fun findWrappedOsc8(
        source: TerminalSelectionSource,
        row: Int,
        column: Int,
    ): TerminalLinkTarget? {
        val hit = source.selectionLineAt(row) ?: return null
        val hitSpan = osc8SpanAt(hit.line, column) ?: return null
        var firstRow = row
        var firstSpan = hitSpan
        var lastRow = row
        var lastSpan = hitSpan

        while (firstRow > 0 && lastRow - firstRow + 1 < MAX_WRAPPED_LINK_ROWS) {
            val previous = source.selectionLineAt(firstRow - 1)?.line ?: break
            if (!previous.softWrappedToNext || firstSpan.startColumn != 0) break
            val previousSpan = osc8EdgeSpan(previous, hitSpan.hyperlink, fromStart = false) ?: break
            if (previousSpan.endColumn != TerminalLineGeometry.columnCount(previous)) break
            firstRow -= 1
            firstSpan = previousSpan
        }
        while (
            lastRow + 1 < source.selectionLineCount &&
            lastRow - firstRow + 1 < MAX_WRAPPED_LINK_ROWS
        ) {
            val current = source.selectionLineAt(lastRow)?.line ?: break
            if (!current.softWrappedToNext || lastSpan.endColumn != TerminalLineGeometry.columnCount(current)) break
            val next = source.selectionLineAt(lastRow + 1)?.line ?: break
            val nextSpan = osc8EdgeSpan(next, hitSpan.hyperlink, fromStart = true) ?: break
            if (nextSpan.startColumn != 0) break
            lastRow += 1
            lastSpan = nextSpan
        }

        val first = source.selectionLineAt(firstRow) ?: return null
        val last = source.selectionLineAt(lastRow) ?: return null
        return TerminalLinkTarget(
            uri = hitSpan.hyperlink.uri,
            id = hitSpan.hyperlink.id,
            source = TerminalLinkSource.OSC8,
            line = first.anchor,
            startColumn = firstSpan.startColumn,
            endColumn = lastSpan.endColumn,
            endLine = last.anchor,
        )
    }

    /** TUIs repaint wrapped links at explicit coordinates, so VT autowrap is absent.
     * Only join matching underlined tokens at the right/left edges of the content pane.
     * This is bounded hit testing, not work performed by the renderer.
     */
    private fun findPaintedPlainTextUrl(
        source: TerminalSelectionSource,
        row: Int,
        column: Int,
    ): TerminalLinkTarget? {
        val hitLine = source.selectionLineAt(row)?.line ?: return null
        val hit = TerminalLineGeometry.positionAtColumn(hitLine, column).textOffset
        for (startRow in maxOf(0, row - MAX_PAINTED_LINK_ROWS + 1)..row) {
            val first = source.selectionLineAt(startRow) ?: continue
            for (initial in underlinedTokens(first.line)) {
                if (!initial.text.hasHttpSchemeAt(0)) continue
                val text = StringBuilder(initial.text)
                var last = first
                var token = initial
                var currentRow = startRow
                var containsHit = currentRow == row && hit in token.start until token.end
                while (currentRow + 1 < source.selectionLineCount &&
                    currentRow - startRow + 1 < MAX_PAINTED_LINK_ROWS &&
                    text.length < MAX_PLAIN_URL_SCAN &&
                    last.line.text.substring(token.end).isBlank() &&
                    token.text.lastOrNull() != ')'
                ) {
                    val next = source.selectionLineAt(currentRow + 1) ?: break
                    val continuation = underlinedTokens(next.line).firstOrNull { candidate ->
                        val prefix = next.line.text.substring(0, candidate.start)
                        // A full-screen TUI may have an unrelated sidebar to the left.
                        prefix.substringAfterLast('│').substringAfterLast('┃')
                            .substringAfterLast('║').substringAfterLast('|').isBlank()
                    } ?: break
                    if (continuation.style != initial.style ||
                        continuation.text.hasHttpSchemeAt(0) ||
                        TerminalLineGeometry.columnAtOffset(next.line, continuation.start) >
                        TerminalLineGeometry.columnAtOffset(last.line, token.start)
                    ) break
                    text.append(continuation.text)
                    currentRow += 1
                    last = next
                    token = continuation
                    containsHit = containsHit || (currentRow == row && hit in token.start until token.end)
                }
                if (currentRow == startRow || !containsHit || text.length > MAX_PLAIN_URL_SCAN) continue
                val match = plainTextUrlAt(text, 0) ?: continue
                if (match.endOffset <= text.length - token.text.length) continue
                return TerminalLinkTarget(
                    uri = match.uri,
                    id = null,
                    source = TerminalLinkSource.PLAIN_TEXT,
                    line = first.anchor,
                    startColumn = TerminalLineGeometry.columnAtOffset(first.line, initial.start),
                    endColumn = TerminalLineGeometry.columnAtOffset(
                        last.line, token.end - (text.length - match.endOffset),
                    ),
                    endLine = last.anchor,
                )
            }
        }
        return null
    }

    private data class UnderlinedToken(
        val start: Int,
        val end: Int,
        val text: String,
        val style: TerminalStyle,
    )

    private fun underlinedTokens(line: TerminalLine): List<UnderlinedToken> {
        val result = ArrayList<UnderlinedToken>()
        var offset = 0
        for (run in line.runs) {
            if (run.style.underline && run.hyperlink == null) {
                var local = 0
                while (local < run.text.length) {
                    if (isUrlBoundary(run.text[local]) ||
                        (run.text[local] == '(' && run.text.hasHttpSchemeAt(local + 1))
                    ) {
                        local += 1
                        continue
                    }
                    val start = local
                    while (local < run.text.length && !isUrlBoundary(run.text[local])) local += 1
                    val previous = result.lastOrNull()
                    if (previous != null && previous.end == offset + start && previous.style == run.style) {
                        result[result.lastIndex] = previous.copy(
                            end = offset + local, text = previous.text + run.text.substring(start, local),
                        )
                    } else {
                        result += UnderlinedToken(offset + start, offset + local, run.text.substring(start, local), run.style)
                    }
                }
            }
            offset += run.text.length
        }
        return result
    }

    private fun findPlainTextUrl(
        selectable: TerminalSelectableLine,
        column: Int,
    ): TerminalLinkTarget? {
        val line = selectable.line
        val hit = TerminalLineGeometry.positionAtColumn(line, column).textOffset
            .coerceIn(0, line.text.length)
        val match = plainTextUrlAt(line.text, hit) ?: return null
        return TerminalLinkTarget(
            uri = match.uri,
            id = null,
            source = TerminalLinkSource.PLAIN_TEXT,
            line = selectable.anchor,
            startColumn = TerminalLineGeometry.columnAtOffset(line, match.startOffset),
            endColumn = TerminalLineGeometry.columnAtOffset(line, match.endOffset),
        )
    }

    private fun findWrappedPlainTextUrl(
        source: TerminalSelectionSource,
        row: Int,
        column: Int,
    ): TerminalLinkTarget? {
        val hitLine = source.selectionLineAt(row)?.line ?: return null
        val localHit = TerminalLineGeometry.positionAtColumn(hitLine, column).textOffset
            .coerceIn(0, hitLine.text.length)

        var firstRow = row
        var charactersBeforeHit = localHit
        while (
            firstRow > 0 && charactersBeforeHit <= MAX_PLAIN_URL_SCAN &&
            row - firstRow + 1 < MAX_WRAPPED_LINK_ROWS
        ) {
            val previous = source.selectionLineAt(firstRow - 1)?.line ?: break
            if (!previous.softWrappedToNext) break
            firstRow -= 1
            charactersBeforeHit += previous.text.length
        }

        val slices = ArrayList<WrappedLineSlice>()
        val text = StringBuilder()
        var currentRow = firstRow
        var hit = -1
        while (
            currentRow < source.selectionLineCount &&
            slices.size < MAX_WRAPPED_LINK_ROWS
        ) {
            val selectable = source.selectionLineAt(currentRow) ?: break
            val combinedStart = text.length
            slices += WrappedLineSlice(selectable, combinedStart)
            text.append(selectable.line.text)
            if (currentRow == row) hit = combinedStart + localHit
            val scannedAfterHit = if (hit >= 0) text.length - hit else 0
            if (!selectable.line.softWrappedToNext || scannedAfterHit > MAX_PLAIN_URL_SCAN) break
            currentRow += 1
        }
        if (hit < 0 || text.isEmpty()) return null
        val match = plainTextUrlAt(text, hit) ?: return null

        val startPosition = positionAtCombinedOffset(
            slices,
            match.startOffset,
            preferNextAtBoundary = true,
        ) ?: return null
        val endPosition = positionAtCombinedOffset(
            slices,
            match.endOffset,
            preferNextAtBoundary = false,
        ) ?: return null
        return TerminalLinkTarget(
            uri = match.uri,
            id = null,
            source = TerminalLinkSource.PLAIN_TEXT,
            line = startPosition.selectable.anchor,
            startColumn = TerminalLineGeometry.columnAtOffset(
                startPosition.selectable.line,
                startPosition.localOffset,
            ),
            endColumn = TerminalLineGeometry.columnAtOffset(
                endPosition.selectable.line,
                endPosition.localOffset,
            ),
            endLine = endPosition.selectable.anchor,
        )
    }

    private fun plainTextUrlAt(text: CharSequence, hit: Int): PlainTextUrlMatch? {
        if (text.isEmpty()) return null
        val pivot = if (hit == text.length) hit - 1 else hit
        if (pivot < 0 || isUrlBoundary(text[pivot])) return null
        var start = pivot
        while (
            start > 0 && pivot - start < MAX_PLAIN_URL_SCAN &&
            !isUrlLeadingBoundary(text, start - 1)
        ) {
            start -= 1
        }
        var end = start
        var parentheses = 0
        while (
            end < text.length && end - start < MAX_PLAIN_URL_SCAN &&
            !isUrlBoundary(text[end])
        ) {
            when (text[end]) {
                '(' -> parentheses += 1
                ')' -> if (parentheses == 0) break else parentheses -= 1
            }
            end += 1
        }
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) end -= 1
        if (end <= start || pivot !in start until end) return null
        val candidate = text.subSequence(start, end).toString()
        if (!TerminalLinkPolicy.canOpen(candidate)) return null
        return PlainTextUrlMatch(candidate, start, end)
    }

    private fun osc8SpanAt(line: TerminalLine, column: Int): Osc8LineSpan? {
        val runs = measuredRuns(line)
        val hitIndex = runs.indexOfFirst { run -> run.hyperlink != null && column in run.columns }
        if (hitIndex < 0) return null
        return expandOsc8Span(runs, hitIndex)
    }

    private fun osc8EdgeSpan(
        line: TerminalLine,
        hyperlink: TerminalHyperlink,
        fromStart: Boolean,
    ): Osc8LineSpan? {
        val runs = measuredRuns(line)
        val index = if (fromStart) {
            runs.indexOfFirst { it.hyperlink == hyperlink && it.columns.first == 0 }
        } else {
            val lineEnd = TerminalLineGeometry.columnCount(line)
            runs.indexOfLast { it.hyperlink == hyperlink && it.columns.last + 1 == lineEnd }
        }
        if (index < 0) return null
        return expandOsc8Span(runs, index)
    }

    private fun measuredRuns(line: TerminalLine): List<MeasuredLinkRun> {
        var fallbackColumn = 0
        return line.runs.map { run ->
            val columns = TerminalLineGeometry.runColumns(run, fallbackColumn)
            fallbackColumn = maxOf(fallbackColumn, columns.last + 1)
            MeasuredLinkRun(run.hyperlink, columns)
        }
    }

    private fun expandOsc8Span(runs: List<MeasuredLinkRun>, hitIndex: Int): Osc8LineSpan {
        val hyperlink = requireNotNull(runs[hitIndex].hyperlink)
        var first = hitIndex
        var last = hitIndex
        while (
            first > 0 && runs[first - 1].hyperlink == hyperlink &&
            runs[first - 1].columns.last + 1 == runs[first].columns.first
        ) {
            first -= 1
        }
        while (
            last + 1 < runs.size && runs[last + 1].hyperlink == hyperlink &&
            runs[last].columns.last + 1 == runs[last + 1].columns.first
        ) {
            last += 1
        }
        return Osc8LineSpan(
            hyperlink = hyperlink,
            startColumn = runs[first].columns.first,
            endColumn = runs[last].columns.last + 1,
        )
    }

    private fun positionAtCombinedOffset(
        slices: List<WrappedLineSlice>,
        offset: Int,
        preferNextAtBoundary: Boolean,
    ): WrappedLinePosition? {
        slices.forEachIndexed { index, slice ->
            val end = slice.combinedStart + slice.selectable.line.text.length
            val ownsBoundary = !preferNextAtBoundary || index == slices.lastIndex
            if (offset < end || (offset == end && ownsBoundary)) {
                return WrappedLinePosition(slice.selectable, offset - slice.combinedStart)
            }
        }
        return null
    }

    private fun isUrlBoundary(character: Char): Boolean =
        character.isWhitespace() || character.isISOControl() || character in URL_BOUNDARIES

    private fun isUrlLeadingBoundary(text: CharSequence, index: Int): Boolean {
        val character = text[index]
        return isUrlBoundary(character) ||
            character == '(' && text.hasHttpSchemeAt(index + 1)
    }

    private fun CharSequence.hasHttpSchemeAt(index: Int): Boolean =
        regionMatches(index, "http://", 0, 7, ignoreCase = true) ||
            regionMatches(index, "https://", 0, 8, ignoreCase = true)

    private const val MAX_PAINTED_LINK_ROWS = 16
    private const val MAX_PLAIN_URL_SCAN = 1_024
    private const val MAX_WRAPPED_LINK_ROWS = 4_096
    private const val URL_BOUNDARIES = "\"'<>[]{}，。；：！？、（）【】《》“”‘’"
    private const val TRAILING_PUNCTUATION = ".,;:!?]}"
}

private data class MeasuredLinkRun(
    val hyperlink: TerminalHyperlink?,
    val columns: IntRange,
)

private data class Osc8LineSpan(
    val hyperlink: TerminalHyperlink,
    val startColumn: Int,
    val endColumn: Int,
)

private data class WrappedLineSlice(
    val selectable: TerminalSelectableLine,
    val combinedStart: Int,
)

private data class WrappedLinePosition(
    val selectable: TerminalSelectableLine,
    val localOffset: Int,
)

private data class PlainTextUrlMatch(
    val uri: String,
    val startOffset: Int,
    val endOffset: Int,
)
