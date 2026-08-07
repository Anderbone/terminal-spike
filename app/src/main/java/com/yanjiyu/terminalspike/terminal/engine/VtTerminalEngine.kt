package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class TerminalModes(
    val applicationCursorKeys: Boolean = false,
    val bracketedPaste: Boolean = false,
    val focusReporting: Boolean = false,
    val mouseTracking: Boolean = false,
    val sgrMouseEncoding: Boolean = false,
    val cursorVisible: Boolean = true,
)

data class TerminalFrameUpdate(
    val completedScrollback: List<TerminalLine>,
    val screen: List<TerminalLine>,
    val cursor: TerminalCursor,
    val alternateScreen: Boolean,
    val modes: TerminalModes,
    val responses: List<ByteArray> = emptyList(),
)

/**
 * A bounded, Android-independent VT/xterm-compatible screen engine.
 *
 * The engine owns terminal cells and escape-sequence state. It exposes immutable line snapshots to
 * the native renderer and never creates Compose state. Unsupported sequences are ignored after a
 * bounded parse so hostile terminal output cannot grow memory without limit.
 */
class VtTerminalEngine(
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
) {
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var columns = columns.coerceIn(1, MAX_COLUMNS)
    private var rows = rows.coerceIn(1, MAX_ROWS)
    private var primary = Screen(this.columns, this.rows)
    private var alternate = Screen(this.columns, this.rows)
    private var useAlternate = false
    private var style = TerminalStyle()
    private var parserState = ParserState.TEXT
    private val sequence = StringBuilder()
    private var pendingBytes = ByteArray(0)
    private var pendingHighSurrogate: Char? = null
    private var applicationCursorKeys = false
    private var bracketedPaste = false
    private var focusReporting = false
    private val mouseTrackingModes = mutableSetOf<Int>()
    private var sgrMouseEncoding = false
    private var cursorVisible = true
    private var originMode = false
    private var autoWrap = true

    private val active: Screen get() = if (useAlternate) alternate else primary

    @Synchronized
    fun accept(bytes: ByteArray): TerminalFrameUpdate {
        val completed = ArrayList<TerminalLine>()
        val responses = ArrayList<ByteArray>()
        if (bytes.isNotEmpty()) decode(bytes, completed, responses)
        return snapshot(completed, responses)
    }

    @Synchronized
    fun resize(newColumns: Int, newRows: Int): TerminalFrameUpdate {
        val boundedColumns = newColumns.coerceIn(1, MAX_COLUMNS)
        val boundedRows = newRows.coerceIn(1, MAX_ROWS)
        if (boundedColumns == columns && boundedRows == rows) return snapshot()
        columns = boundedColumns
        rows = boundedRows
        primary = primary.resized(columns, rows)
        alternate = alternate.resized(columns, rows)
        return snapshot()
    }

    @Synchronized
    fun reset(): TerminalFrameUpdate {
        decoder.reset()
        pendingBytes.fill(0)
        pendingBytes = ByteArray(0)
        pendingHighSurrogate = null
        primary = Screen(columns, rows)
        alternate = Screen(columns, rows)
        useAlternate = false
        style = TerminalStyle()
        parserState = ParserState.TEXT
        sequence.clear()
        applicationCursorKeys = false
        bracketedPaste = false
        focusReporting = false
        mouseTrackingModes.clear()
        sgrMouseEncoding = false
        cursorVisible = true
        originMode = false
        autoWrap = true
        return snapshot()
    }

    @Synchronized
    fun modes(): TerminalModes = currentModes()

    private fun decode(
        bytes: ByteArray,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
    ) {
        val inputBytes = if (pendingBytes.isEmpty()) bytes else pendingBytes + bytes
        val input = ByteBuffer.wrap(inputBytes)
        val characters = CharBuffer.allocate(inputBytes.size.coerceAtLeast(1))
        decoder.decode(input, characters, false)
        pendingBytes = ByteArray(input.remaining()).also(input::get)
        characters.flip()
        while (characters.hasRemaining()) consume(characters.get(), completed, responses)
    }

    private fun consume(
        character: Char,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
    ) {
        when (parserState) {
            ParserState.TEXT -> consumeText(character, completed)
            ParserState.ESCAPE -> consumeEscape(character, completed)
            ParserState.CSI -> consumeCsi(character, completed, responses)
            ParserState.OSC -> when (character) {
                BEL -> finishControlSequence()
                ESCAPE -> parserState = ParserState.OSC_ESCAPE
                else -> appendBounded(character)
            }
            ParserState.OSC_ESCAPE -> if (character == '\\') {
                finishControlSequence()
            } else {
                parserState = ParserState.OSC
                appendBounded(character)
            }
            ParserState.CHARSET -> parserState = ParserState.TEXT
            ParserState.DISCARD_CSI -> if (character.code in CSI_FINAL_START..CSI_FINAL_END) {
                finishControlSequence()
            }
            ParserState.DISCARD_OSC -> if (character == BEL) {
                finishControlSequence()
            } else if (character == ESCAPE) {
                parserState = ParserState.DISCARD_OSC_ESCAPE
            }
            ParserState.DISCARD_OSC_ESCAPE -> {
                parserState = if (character == '\\') ParserState.TEXT else ParserState.DISCARD_OSC
            }
        }
    }

    private fun consumeText(character: Char, completed: MutableList<TerminalLine>) {
        if (pendingHighSurrogate != null && !Character.isLowSurrogate(character)) {
            writeCodePoint(REPLACEMENT, completed)
            pendingHighSurrogate = null
        }
        when (character) {
            ESCAPE -> parserState = ParserState.ESCAPE
            '\u009B' -> startSequence(ParserState.CSI)
            BEL, '\u0000', '\u000E', '\u000F' -> Unit
            '\b' -> active.cursorColumn = (active.cursorColumn - 1).coerceAtLeast(0)
            '\t' -> active.cursorColumn = nextTabStop(active.cursorColumn)
            '\n', '\u000B', '\u000C' -> lineFeed(completed)
            '\r' -> active.cursorColumn = 0
            else -> when {
                Character.isHighSurrogate(character) -> pendingHighSurrogate = character
                Character.isLowSurrogate(character) -> {
                    val high = pendingHighSurrogate
                    if (high == null) {
                        writeCodePoint(REPLACEMENT, completed)
                    } else {
                        writeCodePoint(String(charArrayOf(high, character)), completed)
                        pendingHighSurrogate = null
                    }
                }
                !Character.isISOControl(character) -> writeCodePoint(character.toString(), completed)
            }
        }
    }

    private fun consumeEscape(character: Char, completed: MutableList<TerminalLine>) {
        when (character) {
            '[' -> startSequence(ParserState.CSI)
            ']' -> startSequence(ParserState.OSC)
            '(', ')', '*', '+' -> parserState = ParserState.CHARSET
            '7' -> {
                active.saveCursor(style)
                parserState = ParserState.TEXT
            }
            '8' -> {
                style = active.restoreCursor()
                parserState = ParserState.TEXT
            }
            'D' -> {
                lineFeed(completed)
                parserState = ParserState.TEXT
            }
            'E' -> {
                active.cursorColumn = 0
                lineFeed(completed)
                parserState = ParserState.TEXT
            }
            'M' -> {
                reverseIndex()
                parserState = ParserState.TEXT
            }
            'c' -> {
                reset()
                parserState = ParserState.TEXT
            }
            else -> parserState = ParserState.TEXT
        }
    }

    private fun consumeCsi(
        character: Char,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
    ) {
        if (character.code in CSI_FINAL_START..CSI_FINAL_END) {
            applyCsi(character, sequence.toString(), completed, responses)
            finishControlSequence()
        } else {
            appendBounded(character)
        }
    }

    private fun applyCsi(
        command: Char,
        raw: String,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
    ) {
        val privatePrefix = raw.firstOrNull()?.takeIf { it == '?' || it == '>' || it == '!' }
        val body = if (privatePrefix == null) raw else raw.drop(1)
        val parameters = parseParameters(body)
        fun parameter(index: Int, default: Int = 1): Int = parameters.getOrNull(index)?.takeIf { it > 0 } ?: default
        when (command) {
            '@' -> active.insertCharacters(parameter(0))
            'A' -> moveCursor(row = active.cursorRow - parameter(0), column = active.cursorColumn)
            'B', 'e' -> moveCursor(row = active.cursorRow + parameter(0), column = active.cursorColumn)
            'C', 'a' -> active.cursorColumn = (active.cursorColumn + parameter(0)).coerceAtMost(columns - 1)
            'D' -> active.cursorColumn = (active.cursorColumn - parameter(0)).coerceAtLeast(0)
            'E' -> moveCursor(row = active.cursorRow + parameter(0), column = 0)
            'F' -> moveCursor(row = active.cursorRow - parameter(0), column = 0)
            'G', '`' -> active.cursorColumn = (parameter(0) - 1).coerceIn(0, columns - 1)
            'H', 'f' -> moveCursor(
                row = rowFromParameter(parameter(0)),
                column = (parameter(1) - 1).coerceIn(0, columns - 1),
            )
            'J' -> eraseDisplay(parameters.firstOrNull() ?: 0)
            'K' -> eraseLine(parameters.firstOrNull() ?: 0)
            'L' -> active.insertLines(parameter(0), style)
            'M' -> active.deleteLines(parameter(0), style)
            'P' -> active.deleteCharacters(parameter(0))
            'S' -> repeat(parameter(0).coerceAtMost(rows)) { scrollUp(completed) }
            'T' -> repeat(parameter(0).coerceAtMost(rows)) { scrollDown() }
            'X' -> active.eraseCharacters(parameter(0), style)
            'd' -> moveCursor(row = rowFromParameter(parameter(0)), column = active.cursorColumn)
            'h' -> setModes(parameters, privatePrefix == '?', true)
            'l' -> setModes(parameters, privatePrefix == '?', false)
            'm' -> applySgr(body)
            'n' -> when (parameters.firstOrNull() ?: 0) {
                5 -> responses += CSI_STATUS_OK
                6 -> responses += "\u001B[${active.cursorRow + 1};${active.cursorColumn + 1}R".toByteArray()
            }
            'r' -> setScrollRegion(parameters)
            's' -> active.saveCursor(style)
            'u' -> style = active.restoreCursor()
            'c' -> responses += if (privatePrefix == '>') SECONDARY_DEVICE_ATTRIBUTES else PRIMARY_DEVICE_ATTRIBUTES
        }
    }

    private fun parseParameters(body: String): List<Int> {
        if (body.isBlank()) return emptyList()
        return body.substringBeforeLast(' ', body)
            .replace(':', ';')
            .split(';')
            .take(MAX_PARAMETERS)
            .map { value -> value.filter(Char::isDigit).toIntOrNull()?.coerceAtMost(MAX_PARAMETER_VALUE) ?: 0 }
    }

    private fun applySgr(raw: String) {
        val values = parseParameters(raw).ifEmpty { listOf(0) }
        var index = 0
        while (index < values.size) {
            when (val value = values[index]) {
                0 -> style = TerminalStyle()
                1 -> style = style.copy(bold = true)
                3 -> style = style.copy(italic = true)
                4, 21 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(foreground = TerminalPalette.xtermColour(value - 30))
                39 -> style = style.copy(foreground = TerminalPalette.FOREGROUND)
                in 40..47 -> style = style.copy(background = TerminalPalette.xtermColour(value - 40))
                49 -> style = style.copy(background = TerminalPalette.BACKGROUND)
                in 90..97 -> style = style.copy(foreground = TerminalPalette.xtermColour(value - 90 + 8))
                in 100..107 -> style = style.copy(background = TerminalPalette.xtermColour(value - 100 + 8))
                38, 48 -> {
                    val foreground = value == 38
                    when (values.getOrNull(index + 1)) {
                        5 -> values.getOrNull(index + 2)?.let { colour ->
                            style = if (foreground) {
                                style.copy(foreground = TerminalPalette.xtermColour(colour))
                            } else {
                                style.copy(background = TerminalPalette.xtermColour(colour))
                            }
                            index += 2
                        }
                        2 -> if (index + 4 < values.size) {
                            val colour = TerminalPalette.rgb(values[index + 2], values[index + 3], values[index + 4])
                            style = if (foreground) style.copy(foreground = colour) else style.copy(background = colour)
                            index += 4
                        }
                    }
                }
            }
            index += 1
        }
    }

    private fun setModes(parameters: List<Int>, private: Boolean, enabled: Boolean) {
        parameters.forEach { mode ->
            if (private) {
                when (mode) {
                    1 -> applicationCursorKeys = enabled
                    6 -> {
                        originMode = enabled
                        moveCursor(if (enabled) active.topMargin else 0, 0)
                    }
                    7 -> autoWrap = enabled
                    25 -> cursorVisible = enabled
                    47, 1047 -> switchAlternate(enabled, clear = enabled)
                    1048 -> if (enabled) active.saveCursor(style) else style = active.restoreCursor()
                    1004 -> focusReporting = enabled
                    9, 1000, 1002, 1003 -> if (enabled) mouseTrackingModes += mode else mouseTrackingModes -= mode
                    1006 -> sgrMouseEncoding = enabled
                    1049 -> {
                        if (enabled) primary.saveCursor(style)
                        switchAlternate(enabled, clear = true)
                        if (!enabled) style = primary.restoreCursor()
                    }
                    2004 -> bracketedPaste = enabled
                }
            }
        }
    }

    private fun switchAlternate(enabled: Boolean, clear: Boolean) {
        if (enabled) {
            if (clear) alternate.clear(TerminalStyle())
            useAlternate = true
        } else {
            useAlternate = false
        }
    }

    private fun setScrollRegion(parameters: List<Int>) {
        val top = ((parameters.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
        val bottom = ((parameters.getOrNull(1) ?: rows) - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            active.topMargin = top
            active.bottomMargin = bottom
            moveCursor(if (originMode) top else 0, 0)
        }
    }

    private fun rowFromParameter(oneBasedRow: Int): Int {
        val base = if (originMode) active.topMargin else 0
        val maximum = if (originMode) active.bottomMargin else rows - 1
        return (base + oneBasedRow - 1).coerceIn(base, maximum)
    }

    private fun moveCursor(row: Int, column: Int) {
        val minimumRow = if (originMode) active.topMargin else 0
        val maximumRow = if (originMode) active.bottomMargin else rows - 1
        active.cursorRow = row.coerceIn(minimumRow, maximumRow)
        active.cursorColumn = column.coerceIn(0, columns - 1)
        active.wrapPending = false
    }

    private fun writeCodePoint(value: String, completed: MutableList<TerminalLine>) {
        val width = CellWidth.of(value.codePointAt(0))
        if (width == 0) {
            active.appendCombining(value)
            return
        }
        if (active.wrapPending && autoWrap) {
            active.cursorColumn = 0
            lineFeed(completed)
            active.wrapPending = false
        }
        if (width == 2 && active.cursorColumn == columns - 1) {
            if (!autoWrap) return
            active.cursorColumn = 0
            lineFeed(completed)
        }
        active.write(value, width, style)
        if (active.cursorColumn >= columns - 1) {
            active.cursorColumn = columns - 1
            active.wrapPending = autoWrap
        } else {
            active.cursorColumn = (active.cursorColumn + width).coerceAtMost(columns - 1)
            active.wrapPending = active.cursorColumn == columns - 1 && width == 2 && autoWrap
        }
    }

    private fun lineFeed(completed: MutableList<TerminalLine>) {
        active.wrapPending = false
        if (active.cursorRow == active.bottomMargin) {
            scrollUp(completed)
        } else {
            active.cursorRow = (active.cursorRow + 1).coerceAtMost(rows - 1)
        }
    }

    private fun reverseIndex() {
        if (active.cursorRow == active.topMargin) scrollDown() else active.cursorRow -= 1
    }

    private fun scrollUp(completed: MutableList<TerminalLine>) {
        val removed = active.scrollUp(style)
        val primaryFullScreenScroll = !useAlternate && active.topMargin == 0 && active.bottomMargin == rows - 1
        val alternateTopAnchoredScroll = useAlternate && active.topMargin == 0
        if (primaryFullScreenScroll || alternateTopAnchoredScroll) {
            completed += removed
        }
    }

    private fun scrollDown() = active.scrollDown(style)

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                active.eraseRange(active.cursorRow, active.cursorColumn, columns, style)
                for (row in active.cursorRow + 1 until rows) active.eraseRange(row, 0, columns, style)
            }
            1 -> {
                for (row in 0 until active.cursorRow) active.eraseRange(row, 0, columns, style)
                active.eraseRange(active.cursorRow, 0, active.cursorColumn + 1, style)
            }
            2, 3 -> active.clear(style)
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> active.eraseRange(active.cursorRow, active.cursorColumn, columns, style)
            1 -> active.eraseRange(active.cursorRow, 0, active.cursorColumn + 1, style)
            2 -> active.eraseRange(active.cursorRow, 0, columns, style)
        }
    }

    private fun nextTabStop(column: Int): Int =
        (((column / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(columns - 1)

    private fun startSequence(state: ParserState) {
        sequence.clear()
        parserState = state
    }

    private fun finishControlSequence() {
        sequence.clear()
        parserState = ParserState.TEXT
    }

    private fun appendBounded(character: Char) {
        if (sequence.length < MAX_CONTROL_SEQUENCE) {
            sequence.append(character)
        } else {
            sequence.clear()
            parserState = when (parserState) {
                ParserState.CSI -> ParserState.DISCARD_CSI
                ParserState.OSC, ParserState.OSC_ESCAPE -> ParserState.DISCARD_OSC
                else -> ParserState.TEXT
            }
        }
    }

    private fun snapshot(
        completed: List<TerminalLine> = emptyList(),
        responses: List<ByteArray> = emptyList(),
    ): TerminalFrameUpdate = TerminalFrameUpdate(
        completedScrollback = completed,
        screen = active.lines(),
        cursor = TerminalCursor(active.cursorRow, active.cursorColumn, cursorVisible),
        alternateScreen = useAlternate,
        modes = currentModes(),
        responses = responses,
    )

    private fun currentModes() = TerminalModes(
        applicationCursorKeys = applicationCursorKeys,
        bracketedPaste = bracketedPaste,
        focusReporting = focusReporting,
        mouseTracking = mouseTrackingModes.isNotEmpty(),
        sgrMouseEncoding = sgrMouseEncoding,
        cursorVisible = cursorVisible,
    )

    private enum class ParserState {
        TEXT,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPE,
        CHARSET,
        DISCARD_CSI,
        DISCARD_OSC,
        DISCARD_OSC_ESCAPE,
    }

    private class Screen(
        val columns: Int,
        val rows: Int,
    ) {
        private var cells = Array(rows) { blankRow(columns) }
        var cursorRow = 0
        var cursorColumn = 0
        var topMargin = 0
        var bottomMargin = rows - 1
        var wrapPending = false
        private var savedRow = 0
        private var savedColumn = 0
        private var savedStyle = TerminalStyle()

        fun write(value: String, width: Int, style: TerminalStyle) {
            clearWideCellAt(cursorRow, cursorColumn)
            cells[cursorRow][cursorColumn] = Cell(value, style, continuation = false)
            if (width == 2 && cursorColumn + 1 < columns) {
                clearWideCellAt(cursorRow, cursorColumn + 1)
                cells[cursorRow][cursorColumn + 1] = Cell("", style, continuation = true)
            }
        }

        fun appendCombining(value: String) {
            var column = (cursorColumn - 1).coerceAtLeast(0)
            if (cells[cursorRow][column].continuation && column > 0) column -= 1
            val cell = cells[cursorRow][column]
            if (cell.content.isNotBlank()) cells[cursorRow][column] = cell.copy(content = cell.content + value)
        }

        fun eraseCharacters(count: Int, style: TerminalStyle) =
            eraseRange(cursorRow, cursorColumn, (cursorColumn + count).coerceAtMost(columns), style)

        fun insertCharacters(count: Int) {
            val amount = count.coerceIn(1, columns - cursorColumn)
            val row = cells[cursorRow]
            for (column in columns - 1 downTo cursorColumn + amount) row[column] = row[column - amount]
            for (column in cursorColumn until cursorColumn + amount) row[column] = Cell.blank()
            normalizeWideCells(row)
        }

        fun deleteCharacters(count: Int) {
            val amount = count.coerceIn(1, columns - cursorColumn)
            val row = cells[cursorRow]
            for (column in cursorColumn until columns - amount) row[column] = row[column + amount]
            for (column in columns - amount until columns) row[column] = Cell.blank()
            normalizeWideCells(row)
        }

        fun insertLines(count: Int, style: TerminalStyle) {
            if (cursorRow !in topMargin..bottomMargin) return
            repeat(count.coerceAtMost(bottomMargin - cursorRow + 1)) {
                for (row in bottomMargin downTo cursorRow + 1) cells[row] = cells[row - 1]
                cells[cursorRow] = blankRow(columns, style)
            }
        }

        fun deleteLines(count: Int, style: TerminalStyle) {
            if (cursorRow !in topMargin..bottomMargin) return
            repeat(count.coerceAtMost(bottomMargin - cursorRow + 1)) {
                for (row in cursorRow until bottomMargin) cells[row] = cells[row + 1]
                cells[bottomMargin] = blankRow(columns, style)
            }
        }

        fun scrollUp(style: TerminalStyle): TerminalLine {
            val removed = toLine(cells[topMargin])
            for (row in topMargin until bottomMargin) cells[row] = cells[row + 1]
            cells[bottomMargin] = blankRow(columns, style)
            return removed
        }

        fun scrollDown(style: TerminalStyle) {
            for (row in bottomMargin downTo topMargin + 1) cells[row] = cells[row - 1]
            cells[topMargin] = blankRow(columns, style)
        }

        fun eraseRange(row: Int, start: Int, endExclusive: Int, style: TerminalStyle) {
            val boundedStart = start.coerceIn(0, columns)
            val boundedEnd = endExclusive.coerceIn(boundedStart, columns)
            for (column in boundedStart until boundedEnd) cells[row][column] = Cell(" ", style, false)
            normalizeWideCells(cells[row])
        }

        fun clear(style: TerminalStyle) {
            cells = Array(rows) { blankRow(columns, style) }
            cursorRow = 0
            cursorColumn = 0
            topMargin = 0
            bottomMargin = rows - 1
            wrapPending = false
        }

        fun saveCursor(style: TerminalStyle) {
            savedRow = cursorRow
            savedColumn = cursorColumn
            savedStyle = style
        }

        fun restoreCursor(): TerminalStyle {
            cursorRow = savedRow.coerceIn(0, rows - 1)
            cursorColumn = savedColumn.coerceIn(0, columns - 1)
            wrapPending = false
            return savedStyle
        }

        fun resized(newColumns: Int, newRows: Int): Screen {
            val resized = Screen(newColumns, newRows)
            val rowsToCopy = minOf(rows, newRows)
            val columnsToCopy = minOf(columns, newColumns)
            for (row in 0 until rowsToCopy) {
                for (column in 0 until columnsToCopy) resized.cells[row][column] = cells[row][column]
                normalizeWideCells(resized.cells[row])
            }
            resized.cursorRow = cursorRow.coerceIn(0, newRows - 1)
            resized.cursorColumn = cursorColumn.coerceIn(0, newColumns - 1)
            resized.savedRow = savedRow.coerceIn(0, newRows - 1)
            resized.savedColumn = savedColumn.coerceIn(0, newColumns - 1)
            resized.savedStyle = savedStyle
            return resized
        }

        fun lines(): List<TerminalLine> = cells.map(::toLine)

        private fun clearWideCellAt(row: Int, column: Int) {
            val target = cells[row][column]
            if (target.continuation && column > 0) cells[row][column - 1] = Cell.blank()
            if (!target.continuation && column + 1 < columns && cells[row][column + 1].continuation) {
                cells[row][column + 1] = Cell.blank()
            }
        }

        companion object {
            private fun blankRow(columns: Int, style: TerminalStyle = TerminalStyle()): Array<Cell> =
                Array(columns) { Cell(" ", style, false) }

            private fun normalizeWideCells(row: Array<Cell>) {
                row.indices.forEach { column ->
                    if (row[column].continuation && (column == 0 || row[column - 1].continuation)) {
                        row[column] = Cell.blank()
                    }
                }
            }

            private fun toLine(row: Array<Cell>): TerminalLine {
                var last = row.lastIndex
                while (
                    last >= 0 &&
                    !row[last].continuation &&
                    row[last].content == " " &&
                    row[last].style == TerminalStyle()
                ) {
                    last -= 1
                }
                if (last < 0) return TerminalLine.plain("")
                val runs = ArrayList<TerminalRun>()
                var currentStyle: TerminalStyle? = null
                val text = StringBuilder()
                for (column in 0..last) {
                    val cell = row[column]
                    if (cell.continuation) continue
                    if (currentStyle != null && currentStyle != cell.style) {
                        runs += TerminalRun(text.toString(), currentStyle)
                        text.clear()
                    }
                    currentStyle = cell.style
                    text.append(cell.content)
                }
                if (text.isNotEmpty()) runs += TerminalRun(text.toString(), currentStyle ?: TerminalStyle())
                return TerminalLine.styled(runs)
            }
        }
    }

    private data class Cell(
        val content: String,
        val style: TerminalStyle,
        val continuation: Boolean,
    ) {
        companion object {
            fun blank() = Cell(" ", TerminalStyle(), false)
        }
    }

    private object CellWidth {
        fun of(codePoint: Int): Int = when {
            codePoint == 0 -> 0
            isCombining(codePoint) -> 0
            isWide(codePoint) -> 2
            else -> 1
        }

        private fun isCombining(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt(),
            -> true
            else -> false
        }

        private fun isWide(codePoint: Int): Boolean =
            codePoint in 0x1100..0x115F ||
                codePoint in 0x2329..0x232A ||
                codePoint in 0x2E80..0xA4CF && codePoint != 0x303F ||
                codePoint in 0xAC00..0xD7A3 ||
                codePoint in 0xF900..0xFAFF ||
                codePoint in 0xFE10..0xFE19 ||
                codePoint in 0xFE30..0xFE6F ||
                codePoint in 0xFF00..0xFF60 ||
                codePoint in 0xFFE0..0xFFE6 ||
                codePoint in 0x1F300..0x1FAFF ||
                codePoint in 0x20000..0x3FFFD
    }

    companion object {
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val MAX_COLUMNS = 500
        private const val MAX_ROWS = 300
        private const val MAX_CONTROL_SEQUENCE = 4_096
        private const val MAX_PARAMETERS = 32
        private const val MAX_PARAMETER_VALUE = 100_000
        private const val TAB_WIDTH = 8
        private const val CSI_FINAL_START = 0x40
        private const val CSI_FINAL_END = 0x7E
        private const val ESCAPE = '\u001B'
        private const val BEL = '\u0007'
        private const val REPLACEMENT = "\uFFFD"
        private val CSI_STATUS_OK = "\u001B[0n".toByteArray()
        private val PRIMARY_DEVICE_ATTRIBUTES = "\u001B[?1;2c".toByteArray()
        private val SECONDARY_DEVICE_ATTRIBUTES = "\u001B[>0;136;0c".toByteArray()
    }
}
