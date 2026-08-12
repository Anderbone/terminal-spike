package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineAnchor
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import java.io.OutputStream

data class TerminalTranscriptWriteResult(
    val lineCount: Int,
    val byteCount: Long,
    val truncated: Boolean,
)

/** Streams an explicit user-requested transcript without closing the document-provider stream. */
object TerminalTranscriptWriter {
    const val DEFAULT_MAX_BYTES: Long = 32L * 1024L * 1024L

    fun write(
        lines: Iterable<TerminalLine>,
        output: OutputStream,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): TerminalTranscriptWriteResult {
        require(maxBytes > 0L)
        var bytesWritten = 0L
        var linesWritten = 0
        var truncated = false
        for (line in lines) {
            val encoded = line.text.toByteArray(Charsets.UTF_8)
            try {
                val separator = if (line.softWrappedToNext) EMPTY else NEWLINE
                val required = encoded.size.toLong() + separator.size
                if (required > maxBytes - bytesWritten) {
                    truncated = true
                    break
                }
                output.write(encoded)
                output.write(separator)
                bytesWritten += required
                linesWritten += 1
            } finally {
                encoded.fill(0)
            }
        }
        output.flush()
        return TerminalTranscriptWriteResult(linesWritten, bytesWritten, truncated)
    }

    private val NEWLINE = byteArrayOf('\n'.code.toByte())
    private val EMPTY = ByteArray(0)
}

/** One immutable row reference captured without publishing terminal text into Compose state. */
data class TerminalTranscriptRow(
    val anchor: TerminalLineAnchor,
    val line: TerminalLine,
)

/**
 * A bounded point-in-time terminal snapshot.
 *
 * The rows hold immutable engine/buffer line objects. Consumers may do transcript or find work on
 * an IO/default dispatcher, then retain only the revision and stable match references.
 */
data class TerminalTranscriptSnapshot(
    val sourceId: Long,
    val revision: Long,
    val rows: List<TerminalTranscriptRow>,
    val truncatedBefore: Boolean = false,
) : Iterable<TerminalLine> {
    init {
        require(sourceId >= 0L)
        require(revision >= 0L)
        require(rows.size <= MAX_LINES)
    }

    override fun iterator(): Iterator<TerminalLine> = rows.asSequence().map { it.line }.iterator()

    companion object {
        const val MAX_LINES = 100_000

        /** Test/local-workload adapter. Controller snapshots use their actual stable anchors. */
        fun fromLines(
            lines: List<TerminalLine>,
            revision: Long = 0L,
            sourceId: Long = 0L,
        ): TerminalTranscriptSnapshot {
            require(revision >= 0L)
            require(sourceId >= 0L)
            val first = (lines.size - MAX_LINES).coerceAtLeast(0)
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
    }
}

/** Stable native-renderer cell range. */
data class TerminalFindSegment(
    val line: TerminalLineAnchor,
    val startColumn: Int,
    val endColumnExclusive: Int,
) {
    init {
        require(startColumn >= 0)
        require(endColumnExclusive >= startColumn)
    }
}

/** One literal match, possibly spanning several soft-wrapped terminal rows. */
data class TerminalFindMatch(
    val segments: List<TerminalFindSegment>,
) {
    init {
        require(segments.isNotEmpty())
        require(segments.size <= DEFAULT_MAX_SEGMENTS)
    }
}

data class TerminalFindResult(
    val sourceId: Long,
    val revision: Long,
    val matches: List<TerminalFindMatch>,
    val truncated: Boolean = false,
    val queryTooLong: Boolean = false,
) {
    init {
        require(sourceId >= 0L)
        require(revision >= 0L)
        require(matches.size <= DEFAULT_MAX_MATCHES)
        require(matches.sumOf { it.segments.size } <= DEFAULT_MAX_SEGMENTS)
    }
}

/**
 * Bounded literal local search over immutable row references.
 *
 * Hard line breaks are search boundaries. Rows marked [TerminalLine.softWrappedToNext] are treated
 * as one logical line, including matches which cross a wrap. Only stable cell references escape the
 * function; no transcript copy needs to live in Compose state.
 */
fun findTerminalText(
    snapshot: TerminalTranscriptSnapshot,
    query: String,
    caseSensitive: Boolean = false,
    maxMatches: Int = DEFAULT_MAX_MATCHES,
    maxQueryLength: Int = DEFAULT_MAX_QUERY_LENGTH,
    maxScannedCharacters: Int = DEFAULT_MAX_SCANNED_CHARACTERS,
): TerminalFindResult {
    val boundedMatches = maxMatches.coerceIn(0, DEFAULT_MAX_MATCHES)
    val boundedQueryLength = maxQueryLength.coerceIn(1, DEFAULT_MAX_QUERY_LENGTH)
    val boundedScannedCharacters = maxScannedCharacters.coerceIn(1, DEFAULT_MAX_SCANNED_CHARACTERS)
    if (query.isEmpty() || boundedMatches == 0) {
        return TerminalFindResult(
            sourceId = snapshot.sourceId,
            revision = snapshot.revision,
            matches = emptyList(),
            truncated = snapshot.truncatedBefore,
        )
    }
    if (query.length > boundedQueryLength) {
        return TerminalFindResult(
            sourceId = snapshot.sourceId,
            revision = snapshot.revision,
            matches = emptyList(),
            truncated = snapshot.truncatedBefore,
            queryTooLong = true,
        )
    }

    val matches = ArrayList<TerminalFindMatch>(minOf(boundedMatches, 32))
    var totalSegments = 0
    var scannedCharacters = 0
    var truncated = snapshot.truncatedBefore
    var tailText = ""
    var tailSlices = emptyList<SearchSlice>()

    for (row in snapshot.rows) {
        if (scannedCharacters >= boundedScannedCharacters) {
            truncated = true
            break
        }
        val remaining = boundedScannedCharacters - scannedCharacters
        val rowWasTruncated = row.line.text.length > remaining
        val rowText = if (row.line.text.length <= remaining) {
            row.line.text
        } else {
            truncated = true
            row.line.text.substring(0, safeUtf16End(row.line.text, remaining))
        }
        scannedCharacters += rowText.length

        val combined = tailText + rowText
        val slices = ArrayList<SearchSlice>(tailSlices.size + 1).apply {
            addAll(tailSlices)
            if (rowText.isNotEmpty()) {
                add(
                    SearchSlice(
                        row = row,
                        combinedStart = tailText.length,
                        localStart = 0,
                        length = rowText.length,
                    ),
                )
            }
        }
        var fromIndex = 0
        while (fromIndex <= combined.length - query.length && matches.size < boundedMatches) {
            val index = combined.indexOf(query, startIndex = fromIndex, ignoreCase = !caseSensitive)
            if (index < 0) break
            val end = index + query.length
            if (end > tailText.length) {
                val segments = findSegments(slices, index, end)
                if (segments.isNotEmpty()) {
                    if (totalSegments + segments.size > DEFAULT_MAX_SEGMENTS) {
                        truncated = true
                        return TerminalFindResult(
                            sourceId = snapshot.sourceId,
                            revision = snapshot.revision,
                            matches = matches,
                            truncated = true,
                        )
                    }
                    matches += TerminalFindMatch(segments)
                    totalSegments += segments.size
                }
            }
            fromIndex = end
        }
        if (matches.size == boundedMatches) {
            return TerminalFindResult(
                sourceId = snapshot.sourceId,
                revision = snapshot.revision,
                matches = matches,
                truncated = true,
            )
        }

        if (row.line.softWrappedToNext && !rowWasTruncated) {
            val tail = takeSearchTail(combined, slices, query.length - 1)
            tailText = tail.text
            tailSlices = tail.slices
        } else {
            tailText = ""
            tailSlices = emptyList()
        }
        if (rowWasTruncated) break
    }
    return TerminalFindResult(
        sourceId = snapshot.sourceId,
        revision = snapshot.revision,
        matches = matches,
        truncated = truncated,
    )
}

/** Backwards-compatible convenience for bounded engine/unit-test line lists. */
fun findTerminalText(
    lines: List<TerminalLine>,
    query: String,
    caseSensitive: Boolean = false,
    maxMatches: Int = DEFAULT_MAX_MATCHES,
): List<TerminalFindMatch> = findTerminalText(
    snapshot = TerminalTranscriptSnapshot.fromLines(lines),
    query = query,
    caseSensitive = caseSensitive,
    maxMatches = maxMatches,
).matches

private data class SearchSlice(
    val row: TerminalTranscriptRow,
    val combinedStart: Int,
    val localStart: Int,
    val length: Int,
)

private data class SearchTail(
    val text: String,
    val slices: List<SearchSlice>,
)

private fun takeSearchTail(
    text: String,
    slices: List<SearchSlice>,
    maximumCharacters: Int,
): SearchTail {
    if (maximumCharacters <= 0 || text.isEmpty()) return SearchTail("", emptyList())
    var start = (text.length - maximumCharacters).coerceAtLeast(0)
    if (start > 0 && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) {
        start -= 1
    }
    val retainedSlices = slices.mapNotNull { slice ->
        val sliceEnd = slice.combinedStart + slice.length
        val retainedStart = maxOf(start, slice.combinedStart)
        if (retainedStart >= sliceEnd) {
            null
        } else {
            SearchSlice(
                row = slice.row,
                combinedStart = retainedStart - start,
                localStart = slice.localStart + retainedStart - slice.combinedStart,
                length = sliceEnd - retainedStart,
            )
        }
    }
    return SearchTail(text.substring(start), retainedSlices)
}

private fun findSegments(
    slices: List<SearchSlice>,
    matchStart: Int,
    matchEnd: Int,
): List<TerminalFindSegment> = slices.mapNotNull { slice ->
    val sliceEnd = slice.combinedStart + slice.length
    val overlapStart = maxOf(matchStart, slice.combinedStart)
    val overlapEnd = minOf(matchEnd, sliceEnd)
    if (overlapStart >= overlapEnd) return@mapNotNull null

    val localStart = slice.localStart + overlapStart - slice.combinedStart
    val localEnd = slice.localStart + overlapEnd - slice.combinedStart
    val startColumn = TerminalLineGeometry.columnAtOffset(slice.row.line, localStart)
    val rawEndColumn = TerminalLineGeometry.columnAtOffset(slice.row.line, localEnd)
    val maximumColumn = TerminalLineGeometry.columnCount(slice.row.line)
    val endColumn = when {
        rawEndColumn > startColumn -> rawEndColumn
        startColumn < maximumColumn -> startColumn + 1
        else -> startColumn
    }
    TerminalFindSegment(slice.row.anchor, startColumn, endColumn)
}

private fun safeUtf16End(text: String, requestedEnd: Int): Int {
    var end = requestedEnd.coerceIn(0, text.length)
    if (
        end in 1 until text.length &&
        Character.isHighSurrogate(text[end - 1]) &&
        Character.isLowSurrogate(text[end])
    ) {
        end -= 1
    }
    return end
}

const val DEFAULT_MAX_QUERY_LENGTH = 256
const val DEFAULT_MAX_MATCHES = 500
const val DEFAULT_MAX_SEGMENTS = 2_048
const val DEFAULT_MAX_SCANNED_CHARACTERS = 4 * 1024 * 1024
