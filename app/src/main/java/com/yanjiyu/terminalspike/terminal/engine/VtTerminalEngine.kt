package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.terminal.MAX_COALESCED_BELL_COUNT
import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.model.TerminalColour
import com.yanjiyu.terminalspike.terminal.model.TerminalCellWidth
import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64

data class TerminalModes(
    val applicationCursorKeys: Boolean = false,
    val applicationKeypad: Boolean = false,
    val insertMode: Boolean = false,
    val bracketedPaste: Boolean = false,
    val focusReporting: Boolean = false,
    val mouseTracking: Boolean = false,
    val mouseTrackingMode: TerminalMouseTrackingMode = TerminalMouseTrackingMode.OFF,
    val sgrMouseEncoding: Boolean = false,
    val cursorVisible: Boolean = true,
)

enum class TerminalMouseTrackingMode {
    OFF,
    X10,
    NORMAL,
    BUTTON_EVENT,
    ANY_EVENT,
}

data class TerminalFrameUpdate(
    val completedScrollback: List<TerminalLine>,
    val screen: List<TerminalLine>,
    val cursor: TerminalCursor,
    val alternateScreen: Boolean,
    val modes: TerminalModes,
    val responses: List<ByteArray> = emptyList(),
    val terminalTitle: String? = null,
    val remoteClipboardRequests: List<TerminalRemoteClipboardRequest> = emptyList(),
    /** Monotonic parser-local sequence of the latest text-mode BEL. */
    val bellSequence: Long = 0L,
    /** Bounded BEL count emitted by this update; OSC terminator BELs are not alerts. */
    val bellCount: Int = 0,
    /** One-shot ED3 request for the controller-owned history of [alternateScreen]. */
    val clearScrollbackRequested: Boolean = false,
    /** Rows whose immutable line snapshots changed since the preceding frame. */
    val dirtyRows: IntArray = IntArray(0),
) {
    init {
        require(bellSequence >= 0L)
        require(bellCount in 0..MAX_COALESCED_BELL_COUNT)
        require(bellCount == 0 || bellSequence > 0L)
        require(dirtyRows.indices.all { index ->
            dirtyRows[index] in screen.indices && (index == 0 || dirtyRows[index - 1] < dirtyRows[index])
        }) { "Dirty terminal rows must be sorted, unique, and inside the published screen." }
    }
}

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
    private var applicationKeypad = false
    private var insertMode = false
    private var bracketedPaste = false
    private var focusReporting = false
    private val mouseTrackingModes = mutableSetOf<Int>()
    private var sgrMouseEncoding = false
    private var cursorVisible = true
    private var cursorStyleOverride: CursorStyle? = null
    private var cursorBlinkOverride: Boolean? = null
    private var originMode = false
    private var autoWrap = true
    private var terminalTitle: String? = null
    private var activeHyperlink: TerminalHyperlink? = null
    private var bellSequence = 0L
    private val deferredPrimaryResizeScrollback = ArrayDeque<TerminalLine>()
    private val deferredAlternateResizeScrollback = ArrayDeque<TerminalLine>()

    private val active: Screen get() = if (useAlternate) alternate else primary

    @Synchronized
    fun accept(bytes: ByteArray): TerminalFrameUpdate {
        val completed = ArrayList<TerminalLine>()
        val responses = ArrayList<ByteArray>()
        val remoteClipboardRequests = ArrayList<TerminalRemoteClipboardRequest>()
        val bells = BellAccumulator()
        val signals = FrameSignals()
        if (bytes.isNotEmpty()) {
            decode(bytes, completed, responses, remoteClipboardRequests, bells, signals)
        }
        return snapshot(
            completed = completed,
            responses = responses,
            remoteClipboardRequests = remoteClipboardRequests,
            bellCount = bells.count,
            clearScrollbackRequested = signals.clearScrollbackRequested,
        )
    }

    @Synchronized
    fun resize(newColumns: Int, newRows: Int): TerminalFrameUpdate {
        val boundedColumns = newColumns.coerceIn(1, MAX_COLUMNS)
        val boundedRows = newRows.coerceIn(1, MAX_ROWS)
        if (boundedColumns == columns && boundedRows == rows) return snapshot()
        val primaryResize = primary.resized(boundedColumns, boundedRows)
        val alternateResize = alternate.resized(boundedColumns, boundedRows)
        columns = boundedColumns
        rows = boundedRows
        primary = primaryResize.screen
        alternate = alternateResize.screen
        val completed = ArrayList<TerminalLine>()
        if (useAlternate) {
            deferResizeScrollback(deferredPrimaryResizeScrollback, primaryResize.scrolledRows)
            drainResizeScrollback(deferredAlternateResizeScrollback, completed)
            completed += alternateResize.scrolledRows
        } else {
            deferResizeScrollback(deferredAlternateResizeScrollback, alternateResize.scrolledRows)
            drainResizeScrollback(deferredPrimaryResizeScrollback, completed)
            completed += primaryResize.scrolledRows
        }
        return snapshot(completed = completed)
    }

    @Synchronized
    fun reset(): TerminalFrameUpdate {
        resetState()
        return snapshot()
    }

    private fun resetState() {
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
        applicationKeypad = false
        insertMode = false
        bracketedPaste = false
        focusReporting = false
        mouseTrackingModes.clear()
        sgrMouseEncoding = false
        cursorVisible = true
        cursorStyleOverride = null
        cursorBlinkOverride = null
        originMode = false
        autoWrap = true
        terminalTitle = null
        activeHyperlink = null
        deferredPrimaryResizeScrollback.clear()
        deferredAlternateResizeScrollback.clear()
    }

    @Synchronized
    fun modes(): TerminalModes = currentModes()

    private fun decode(
        bytes: ByteArray,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
        remoteClipboardRequests: MutableList<TerminalRemoteClipboardRequest>,
        bells: BellAccumulator,
        signals: FrameSignals,
    ) {
        val inputBytes = if (pendingBytes.isEmpty()) bytes else pendingBytes + bytes
        val input = ByteBuffer.wrap(inputBytes)
        val characters = CharBuffer.allocate(inputBytes.size.coerceAtLeast(1))
        decoder.decode(input, characters, false)
        pendingBytes = ByteArray(input.remaining()).also(input::get)
        characters.flip()
        while (characters.hasRemaining()) {
            consume(characters.get(), completed, responses, remoteClipboardRequests, bells, signals)
        }
    }

    private fun consume(
        character: Char,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
        remoteClipboardRequests: MutableList<TerminalRemoteClipboardRequest>,
        bells: BellAccumulator,
        signals: FrameSignals,
    ) {
        when (parserState) {
            ParserState.TEXT -> consumeText(character, completed, bells)
            ParserState.ESCAPE -> consumeEscape(character, completed)
            ParserState.CSI -> consumeCsi(character, completed, responses, signals)
            ParserState.OSC -> when (character) {
                BEL, STRING_TERMINATOR -> finishOsc(remoteClipboardRequests)
                ESCAPE -> parserState = ParserState.OSC_ESCAPE
                else -> if (isSafeOscCharacter(character)) {
                    appendBounded(character)
                } else {
                    discardOsc()
                }
            }
            ParserState.OSC_ESCAPE -> if (character == '\\') {
                finishOsc(remoteClipboardRequests)
            } else {
                discardOsc(character)
            }
            ParserState.CHARSET -> parserState = ParserState.TEXT
            ParserState.DISCARD_CSI -> if (character.code in CSI_FINAL_START..CSI_FINAL_END) {
                finishControlSequence()
            }
            ParserState.DISCARD_OSC -> if (character == BEL || character == STRING_TERMINATOR) {
                finishControlSequence()
            } else if (character == ESCAPE) {
                parserState = ParserState.DISCARD_OSC_ESCAPE
            }
            ParserState.DISCARD_OSC_ESCAPE -> {
                parserState = when (character) {
                    '\\', BEL, STRING_TERMINATOR -> ParserState.TEXT
                    ESCAPE -> ParserState.DISCARD_OSC_ESCAPE
                    else -> ParserState.DISCARD_OSC
                }
            }
        }
    }

    private fun consumeText(
        character: Char,
        completed: MutableList<TerminalLine>,
        bells: BellAccumulator,
    ) {
        if (pendingHighSurrogate != null && !Character.isLowSurrogate(character)) {
            writeCodePoint(REPLACEMENT, completed)
            pendingHighSurrogate = null
        }
        when (character) {
            ESCAPE -> parserState = ParserState.ESCAPE
            '\u009B' -> startSequence(ParserState.CSI)
            '\u009D' -> startSequence(ParserState.OSC)
            BEL -> recordBell(bells)
            '\u0000', '\u000E', '\u000F' -> Unit
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
            '=' -> {
                applicationKeypad = true
                parserState = ParserState.TEXT
            }
            '>' -> {
                applicationKeypad = false
                parserState = ParserState.TEXT
            }
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
                // Do not take a nested snapshot here: doing so would consume the new screens'
                // dirty rows before the enclosing accept() publishes the RIS update.
                resetState()
                parserState = ParserState.TEXT
            }
            else -> parserState = ParserState.TEXT
        }
    }

    private fun consumeCsi(
        character: Char,
        completed: MutableList<TerminalLine>,
        responses: MutableList<ByteArray>,
        signals: FrameSignals,
    ) {
        if (character.code in CSI_FINAL_START..CSI_FINAL_END) {
            applyCsi(character, sequence.toString(), completed, responses, signals)
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
        signals: FrameSignals,
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
            'J' -> eraseDisplay(parameters.firstOrNull() ?: 0, completed, signals)
            'K' -> eraseLine(parameters.firstOrNull() ?: 0)
            'L' -> active.insertLines(parameter(0), style)
            'M' -> active.deleteLines(parameter(0), style)
            'P' -> active.deleteCharacters(parameter(0))
            'S' -> repeat(parameter(0).coerceAtMost(rows)) { scrollUp(completed) }
            'T' -> repeat(parameter(0).coerceAtMost(rows)) { scrollDown() }
            'X' -> active.eraseCharacters(parameter(0), style)
            'd' -> moveCursor(row = rowFromParameter(parameter(0)), column = active.cursorColumn)
            'h' -> setModes(parameters, privatePrefix, body, true, completed)
            'l' -> setModes(parameters, privatePrefix, body, false, completed)
            'm' -> applySgr(body)
            'n' -> when (parameters.firstOrNull() ?: 0) {
                5 -> responses += CSI_STATUS_OK
                6 -> responses += "\u001B[${active.cursorRow + 1};${active.cursorColumn + 1}R".toByteArray()
            }
            'r' -> setScrollRegion(parameters)
            's' -> active.saveCursor(style)
            'u' -> style = active.restoreCursor()
            'q' -> parseCursorStyleParameter(raw, privatePrefix)?.let(::applyCursorStyle)
            'c' -> responses += if (privatePrefix == '>') SECONDARY_DEVICE_ATTRIBUTES else PRIMARY_DEVICE_ATTRIBUTES
        }
    }

    private fun applyCursorStyle(parameter: Int) {
        when (parameter) {
            0, 1 -> {
                cursorStyleOverride = CursorStyle.BLOCK
                cursorBlinkOverride = true
            }
            2 -> {
                cursorStyleOverride = CursorStyle.BLOCK
                cursorBlinkOverride = false
            }
            3 -> {
                cursorStyleOverride = CursorStyle.UNDERLINE
                cursorBlinkOverride = true
            }
            4 -> {
                cursorStyleOverride = CursorStyle.UNDERLINE
                cursorBlinkOverride = false
            }
            5 -> {
                cursorStyleOverride = CursorStyle.BEAM
                cursorBlinkOverride = true
            }
            6 -> {
                cursorStyleOverride = CursorStyle.BEAM
                cursorBlinkOverride = false
            }
        }
    }

    /** Parses the single Ps + space intermediate accepted by DECSCUSR. */
    private fun parseCursorStyleParameter(raw: String, privatePrefix: Char?): Int? {
        if (privatePrefix != null || raw.lastOrNull() != ' ') return null
        val value = raw.dropLast(1)
        if (value.isEmpty()) return 0
        if (value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()?.takeIf { it in DECSCUSR_MIN..DECSCUSR_MAX }
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
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4, 21 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                8 -> style = style.copy(conceal = true)
                9 -> style = style.copy(strikethrough = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                28 -> style = style.copy(conceal = false)
                29 -> style = style.copy(strikethrough = false)
                in 30..37 -> style = style.copy(foreground = indexedColour(value - 30))
                39 -> style = style.copy(foreground = TerminalColour.Default)
                in 40..47 -> style = style.copy(background = indexedColour(value - 40))
                49 -> style = style.copy(background = TerminalColour.Default)
                in 90..97 -> style = style.copy(foreground = indexedColour(value - 90 + 8))
                in 100..107 -> style = style.copy(background = indexedColour(value - 100 + 8))
                38, 48 -> {
                    val foreground = value == 38
                    when (values.getOrNull(index + 1)) {
                        5 -> values.getOrNull(index + 2)?.let { colour ->
                            style = if (foreground) {
                                style.copy(foreground = indexedColour(colour))
                            } else {
                                style.copy(background = indexedColour(colour))
                            }
                            index += 2
                        }
                        2 -> if (index + 4 < values.size) {
                            val colour = TerminalPalette.rgb(values[index + 2], values[index + 3], values[index + 4])
                            style = if (foreground) {
                                style.copy(foreground = TerminalColour.Rgb(colour))
                            } else {
                                style.copy(background = TerminalColour.Rgb(colour))
                            }
                            index += 4
                        }
                    }
                }
            }
            index += 1
        }
    }

    private fun indexedColour(index: Int): TerminalColour =
        TerminalColour.Indexed(index.coerceIn(0, 255))

    private fun setModes(
        parameters: List<Int>,
        privatePrefix: Char?,
        body: String,
        enabled: Boolean,
        completed: MutableList<TerminalLine>,
    ) {
        if (privatePrefix != null && privatePrefix != '?') return
        if (body.any { it !in '0'..'9' && it != ';' }) return
        parameters.forEach { mode ->
            if (privatePrefix == '?') {
                when (mode) {
                    1 -> applicationCursorKeys = enabled
                    6 -> {
                        originMode = enabled
                        moveCursor(if (enabled) active.topMargin else 0, 0)
                    }
                    7 -> autoWrap = enabled
                    25 -> cursorVisible = enabled
                    47, 1047 -> switchAlternate(enabled, clear = enabled, completed)
                    1048 -> if (enabled) active.saveCursor(style) else style = active.restoreCursor()
                    1004 -> focusReporting = enabled
                    9, 1000, 1002, 1003 -> if (enabled) mouseTrackingModes += mode else mouseTrackingModes -= mode
                    1006 -> sgrMouseEncoding = enabled
                    1049 -> {
                        if (enabled) primary.saveCursor(style)
                        switchAlternate(enabled, clear = true, completed)
                        if (!enabled) style = primary.restoreCursor()
                    }
                    2004 -> bracketedPaste = enabled
                }
            } else if (privatePrefix == null && mode == 4) {
                insertMode = enabled
            }
        }
    }

    private fun switchAlternate(
        enabled: Boolean,
        clear: Boolean,
        completed: MutableList<TerminalLine>,
    ) {
        if (enabled) {
            if (clear) alternate.clear(TerminalStyle())
            useAlternate = true
            drainResizeScrollback(deferredAlternateResizeScrollback, completed)
        } else {
            useAlternate = false
            drainResizeScrollback(deferredPrimaryResizeScrollback, completed)
        }
        active.markAllDirty()
    }

    private fun deferResizeScrollback(
        target: ArrayDeque<TerminalLine>,
        lines: List<TerminalLine>,
    ) {
        lines.forEach { line ->
            if (target.size == MAX_DEFERRED_RESIZE_SCROLLBACK) target.removeFirst()
            target.addLast(line)
        }
    }

    private fun drainResizeScrollback(
        source: ArrayDeque<TerminalLine>,
        destination: MutableList<TerminalLine>,
    ) {
        while (source.isNotEmpty()) destination += source.removeFirst()
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
        val codePoint = value.codePointAt(0)
        if (active.shouldJoinGrapheme(codePoint)) {
            active.appendCombining(value)
            return
        }
        val width = TerminalCellWidth.of(codePoint)
        if (width == 0) {
            active.appendCombining(value)
            return
        }
        if (active.wrapPending && autoWrap) {
            active.cursorColumn = 0
            lineFeed(completed, softWrapped = true)
            active.wrapPending = false
        }
        if (width == 2 && active.cursorColumn == columns - 1) {
            if (!autoWrap) return
            active.cursorColumn = 0
            lineFeed(completed, softWrapped = true)
        }
        if (insertMode) active.insertCharacters(width)
        val writeColumn = active.cursorColumn
        active.write(value, width, style, activeHyperlink)
        val nextColumn = writeColumn + width
        if (nextColumn >= columns) {
            active.cursorColumn = columns - 1
            active.wrapPending = autoWrap
        } else {
            active.cursorColumn = nextColumn
            active.wrapPending = false
        }
    }

    private fun lineFeed(
        completed: MutableList<TerminalLine>,
        softWrapped: Boolean = false,
    ) {
        active.wrapPending = false
        active.setSoftWrapped(active.cursorRow, softWrapped)
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

    private fun eraseDisplay(
        mode: Int,
        completed: MutableList<TerminalLine>,
        signals: FrameSignals,
    ) {
        when (mode) {
            0 -> {
                active.eraseRange(active.cursorRow, active.cursorColumn, columns, style)
                for (row in active.cursorRow + 1 until rows) active.eraseRange(row, 0, columns, style)
            }
            1 -> {
                for (row in 0 until active.cursorRow) active.eraseRange(row, 0, columns, style)
                active.eraseRange(active.cursorRow, 0, active.cursorColumn + 1, style)
            }
            2 -> active.clear(style)
            3 -> {
                // ED3 targets saved lines only. Rows scrolled earlier in this parser batch belong
                // to the history being erased, while the visible screen and cursor stay intact.
                completed.clear()
                signals.clearScrollbackRequested = true
            }
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

    private fun finishOsc(remoteClipboardRequests: MutableList<TerminalRemoteClipboardRequest>) {
        applyOsc(sequence.toString(), remoteClipboardRequests)
        finishControlSequence()
    }

    private fun applyOsc(
        raw: String,
        remoteClipboardRequests: MutableList<TerminalRemoteClipboardRequest>,
    ) {
        val separator = raw.indexOf(';')
        if (separator <= 0) return
        when (raw.substring(0, separator)) {
            "0", "2" -> terminalTitle = raw.substring(separator + 1).takeIf(String::isNotEmpty)
            "8" -> applyOscHyperlink(raw.substring(separator + 1))
            "52" -> if (remoteClipboardRequests.size < MAX_REMOTE_CLIPBOARD_REQUESTS_PER_ACCEPT) {
                decodeOsc52(raw.substring(separator + 1))?.let(remoteClipboardRequests::add)
            }
        }
    }

    private fun decodeOsc52(payload: String): TerminalRemoteClipboardRequest? {
        val separator = payload.indexOf(';')
        if (separator < 0) return null
        val selection = payload.substring(0, separator)
        if (selection.isNotEmpty() && selection != "c") return null
        val encoded = payload.substring(separator + 1)
        if (encoded.isEmpty() || encoded == "?" || encoded.length > MAX_OSC52_BASE64_LENGTH) return null
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return try {
            if (decoded.isEmpty() || decoded.size > TerminalRemoteClipboardRequest.MAX_TEXT_BYTES) return null
            val text = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString()
            }.getOrNull() ?: return null
            if (!text.isSafeRemoteClipboardText()) return null
            TerminalRemoteClipboardRequest(text)
        } finally {
            decoded.fill(0)
        }
    }

    private fun String.isSafeRemoteClipboardText(): Boolean {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (
                codePoint == REPLACEMENT_CHARACTER.code ||
                Character.getType(codePoint) == Character.FORMAT.toInt() ||
                Character.isISOControl(codePoint) && codePoint !in SAFE_CLIPBOARD_CONTROLS
            ) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun applyOscHyperlink(payload: String) {
        val uriSeparator = payload.indexOf(';')
        if (uriSeparator < 0) {
            activeHyperlink = null
            return
        }
        val parameters = payload.substring(0, uriSeparator)
        val rawUri = payload.substring(uriSeparator + 1)
        if (rawUri.isEmpty()) {
            activeHyperlink = null
            return
        }
        if (rawUri.length > TerminalHyperlink.MAX_URI_LENGTH) {
            activeHyperlink = null
            return
        }
        val parsed = runCatching { URI(rawUri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme !in ALLOWED_HYPERLINK_SCHEMES) {
            activeHyperlink = null
            return
        }
        val id = parameters.split(':')
            .firstOrNull { parameter -> parameter.startsWith("id=") }
            ?.removePrefix("id=")
            ?.takeIf { candidate ->
                candidate.isNotEmpty() &&
                    candidate.length <= TerminalHyperlink.MAX_ID_LENGTH &&
                    candidate.all(::isSafeOscCharacter)
            }
        activeHyperlink = TerminalHyperlink(rawUri, id)
    }

    private fun discardOsc(character: Char? = null) {
        sequence.clear()
        parserState = when (character) {
            BEL, STRING_TERMINATOR, '\\' -> ParserState.TEXT
            ESCAPE -> ParserState.DISCARD_OSC_ESCAPE
            else -> ParserState.DISCARD_OSC
        }
    }

    private fun isSafeOscCharacter(character: Char): Boolean =
        !character.isISOControl() &&
            Character.getType(character) != Character.FORMAT.toInt() &&
            character != REPLACEMENT_CHARACTER

    private fun appendBounded(character: Char) {
        val maximumLength = when (parserState) {
            ParserState.OSC, ParserState.OSC_ESCAPE -> MAX_OSC_SEQUENCE
            else -> MAX_CONTROL_SEQUENCE
        }
        if (sequence.length < maximumLength) {
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
        remoteClipboardRequests: List<TerminalRemoteClipboardRequest> = emptyList(),
        bellCount: Int = 0,
        clearScrollbackRequested: Boolean = false,
    ): TerminalFrameUpdate {
        val screenSnapshot = active.snapshotLines()
        return TerminalFrameUpdate(
            completedScrollback = completed,
            screen = screenSnapshot.lines,
            cursor = TerminalCursor(
                row = active.cursorRow,
                column = active.cursorColumn,
                visible = cursorVisible,
                styleOverride = cursorStyleOverride,
                blinkOverride = cursorBlinkOverride,
            ),
            alternateScreen = useAlternate,
            modes = currentModes(),
            responses = responses,
            terminalTitle = terminalTitle,
            remoteClipboardRequests = remoteClipboardRequests,
            bellSequence = bellSequence,
            bellCount = bellCount,
            clearScrollbackRequested = clearScrollbackRequested,
            dirtyRows = screenSnapshot.dirtyRows,
        )
    }

    private fun recordBell(accumulator: BellAccumulator) {
        if (bellSequence < Long.MAX_VALUE) bellSequence += 1L
        if (accumulator.count < MAX_COALESCED_BELL_COUNT) accumulator.count += 1
    }

    private fun currentModes() = TerminalModes(
        applicationCursorKeys = applicationCursorKeys,
        applicationKeypad = applicationKeypad,
        insertMode = insertMode,
        bracketedPaste = bracketedPaste,
        focusReporting = focusReporting,
        mouseTracking = mouseTrackingModes.isNotEmpty(),
        mouseTrackingMode = currentMouseTrackingMode(),
        sgrMouseEncoding = sgrMouseEncoding,
        cursorVisible = cursorVisible,
    )

    private fun currentMouseTrackingMode(): TerminalMouseTrackingMode = when {
        1003 in mouseTrackingModes -> TerminalMouseTrackingMode.ANY_EVENT
        1002 in mouseTrackingModes -> TerminalMouseTrackingMode.BUTTON_EVENT
        1000 in mouseTrackingModes -> TerminalMouseTrackingMode.NORMAL
        9 in mouseTrackingModes -> TerminalMouseTrackingMode.X10
        else -> TerminalMouseTrackingMode.OFF
    }

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

    private class BellAccumulator(var count: Int = 0)

    private class FrameSignals(var clearScrollbackRequested: Boolean = false)

    private data class ScreenLineSnapshot(
        val lines: List<TerminalLine>,
        val dirtyRows: IntArray,
    )

    private data class ScreenResizeResult(
        val screen: Screen,
        val scrolledRows: List<TerminalLine>,
    )

    private data class ResizedScreenRow(
        val cells: Array<Cell>,
        val softWrappedToNext: Boolean,
    )

    private data class ResizePosition(
        val row: Int,
        val column: Int,
        val wrapPending: Boolean = false,
    )

    private data class RewrappedScreen(
        val rows: List<ResizedScreenRow>,
        val cursor: ResizePosition,
        val savedCursor: ResizePosition,
    )

    private data class RewrappedRow(
        val rows: List<ResizedScreenRow>,
        val cellPositions: Array<ResizePosition?>,
        val boundaries: Array<ResizePosition?>,
    )

    private class Screen(
        val columns: Int,
        val rows: Int,
    ) {
        private var cells = Array(rows) { blankRow(columns) }
        private var softWrappedRows = BooleanArray(rows)
        private var cachedLines = arrayOfNulls<TerminalLine>(rows)
        private var dirtyRows = BooleanArray(rows) { true }
        var cursorRow = 0
        var cursorColumn = 0
        var topMargin = 0
        var bottomMargin = rows - 1
        var wrapPending = false
        private var savedRow = 0
        private var savedColumn = 0
        private var savedStyle = TerminalStyle()

        fun write(
            value: String,
            width: Int,
            style: TerminalStyle,
            hyperlink: TerminalHyperlink?,
        ) {
            clearWideCellAt(cursorRow, cursorColumn)
            cells[cursorRow][cursorColumn] = Cell(value, style, hyperlink, continuation = false)
            if (width == 2 && cursorColumn + 1 < columns) {
                clearWideCellAt(cursorRow, cursorColumn + 1)
                cells[cursorRow][cursorColumn + 1] = Cell("", style, hyperlink, continuation = true)
            }
            markDirty(cursorRow)
        }

        fun setSoftWrapped(row: Int, wrapped: Boolean) {
            if (softWrappedRows[row] == wrapped) return
            softWrappedRows[row] = wrapped
            markDirty(row)
        }

        fun appendCombining(value: String) {
            val column = previousGraphicColumn() ?: return
            val cell = cells[cursorRow][column]
            if (cell.content.isNotBlank()) {
                cells[cursorRow][column] = cell.copy(content = cell.content + value)
                markDirty(cursorRow)
            }
        }

        fun shouldJoinGrapheme(codePoint: Int): Boolean {
            val column = previousGraphicColumn() ?: return false
            val content = cells[cursorRow][column].content
            if (content.isBlank()) return false
            val lastCodePoint = content.codePointBefore(content.length)
            if (lastCodePoint == ZERO_WIDTH_JOINER) return true
            return TerminalCellWidth.isRegionalIndicator(codePoint) &&
                regionalIndicatorCount(content) % 2 == 1
        }

        private fun regionalIndicatorCount(content: String): Int {
            var index = 0
            var count = 0
            while (index < content.length) {
                val codePoint = content.codePointAt(index)
                if (TerminalCellWidth.isRegionalIndicator(codePoint)) count += 1
                index += Character.charCount(codePoint)
            }
            return count
        }

        private fun previousGraphicColumn(): Int? {
            var column = if (wrapPending) cursorColumn else cursorColumn - 1
            if (column !in 0 until columns) return null
            if (cells[cursorRow][column].continuation) column -= 1
            return column.takeIf { it in 0 until columns }
        }

        fun eraseCharacters(count: Int, style: TerminalStyle) =
            eraseRange(cursorRow, cursorColumn, (cursorColumn + count).coerceAtMost(columns), style)

        fun insertCharacters(count: Int) {
            val amount = count.coerceIn(1, columns - cursorColumn)
            val row = cells[cursorRow]
            for (column in columns - 1 downTo cursorColumn + amount) row[column] = row[column - amount]
            for (column in cursorColumn until cursorColumn + amount) row[column] = Cell.blank()
            normalizeWideCells(row)
            markDirty(cursorRow)
        }

        fun deleteCharacters(count: Int) {
            val amount = count.coerceIn(1, columns - cursorColumn)
            val row = cells[cursorRow]
            for (column in cursorColumn until columns - amount) row[column] = row[column + amount]
            for (column in columns - amount until columns) row[column] = Cell.blank()
            normalizeWideCells(row)
            markDirty(cursorRow)
        }

        fun insertLines(count: Int, style: TerminalStyle) {
            if (cursorRow !in topMargin..bottomMargin) return
            repeat(count.coerceAtMost(bottomMargin - cursorRow + 1)) {
                for (row in bottomMargin downTo cursorRow + 1) {
                    cells[row] = cells[row - 1]
                    softWrappedRows[row] = softWrappedRows[row - 1]
                }
                cells[cursorRow] = blankRow(columns, style)
                softWrappedRows[cursorRow] = false
            }
            markDirty(cursorRow, bottomMargin)
        }

        fun deleteLines(count: Int, style: TerminalStyle) {
            if (cursorRow !in topMargin..bottomMargin) return
            repeat(count.coerceAtMost(bottomMargin - cursorRow + 1)) {
                for (row in cursorRow until bottomMargin) {
                    cells[row] = cells[row + 1]
                    softWrappedRows[row] = softWrappedRows[row + 1]
                }
                cells[bottomMargin] = blankRow(columns, style)
                softWrappedRows[bottomMargin] = false
            }
            markDirty(cursorRow, bottomMargin)
        }

        fun scrollUp(style: TerminalStyle): TerminalLine {
            val removed = toLine(cells[topMargin], softWrappedRows[topMargin])
            for (row in topMargin until bottomMargin) {
                cells[row] = cells[row + 1]
                softWrappedRows[row] = softWrappedRows[row + 1]
            }
            cells[bottomMargin] = blankRow(columns, style)
            softWrappedRows[bottomMargin] = false
            markDirty(topMargin, bottomMargin)
            return removed
        }

        fun scrollDown(style: TerminalStyle) {
            for (row in bottomMargin downTo topMargin + 1) {
                cells[row] = cells[row - 1]
                softWrappedRows[row] = softWrappedRows[row - 1]
            }
            cells[topMargin] = blankRow(columns, style)
            softWrappedRows[topMargin] = false
            markDirty(topMargin, bottomMargin)
        }

        fun eraseRange(row: Int, start: Int, endExclusive: Int, style: TerminalStyle) {
            val boundedStart = start.coerceIn(0, columns)
            val boundedEnd = endExclusive.coerceIn(boundedStart, columns)
            val erasedStyle = style.forErasedCell()
            for (column in boundedStart until boundedEnd) {
                cells[row][column] = Cell(" ", erasedStyle, null, false)
            }
            if (boundedStart == 0 && boundedEnd == columns) softWrappedRows[row] = false
            normalizeWideCells(cells[row])
            if (boundedStart < boundedEnd) markDirty(row)
        }

        fun clear(style: TerminalStyle) {
            cells = Array(rows) { blankRow(columns, style) }
            softWrappedRows = BooleanArray(rows)
            cachedLines = arrayOfNulls(rows)
            dirtyRows = BooleanArray(rows) { true }
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

        fun resized(newColumns: Int, newRows: Int): ScreenResizeResult {
            val rewrapped = rewrapRows(newColumns)
            val resized = Screen(newColumns, newRows)
            val firstVisibleRow = (rewrapped.cursor.row - (newRows - 1)).coerceAtLeast(0)
            val visibleRows = rewrapped.rows.drop(firstVisibleRow).take(newRows)
            visibleRows.forEachIndexed { row, source ->
                resized.cells[row] = source.cells
                resized.softWrappedRows[row] = source.softWrappedToNext
            }
            resized.cursorRow = (rewrapped.cursor.row - firstVisibleRow).coerceIn(0, newRows - 1)
            resized.cursorColumn = rewrapped.cursor.column.coerceIn(0, newColumns - 1)
            resized.wrapPending = rewrapped.cursor.wrapPending
            resized.savedRow = (rewrapped.savedCursor.row - firstVisibleRow).coerceIn(0, newRows - 1)
            resized.savedColumn = rewrapped.savedCursor.column.coerceIn(0, newColumns - 1)
            resized.savedStyle = savedStyle
            return ScreenResizeResult(
                screen = resized,
                scrolledRows = rewrapped.rows.take(firstVisibleRow).map { row ->
                    toLine(row.cells, row.softWrappedToNext)
                },
            )
        }

        /**
         * Rewraps each physical row independently when width shrinks. Existing hard/soft row
         * boundaries stay intact, but no visible cell or active cursor tail is silently truncated.
         * This deliberately avoids trying to rebuild controller-owned history into logical lines.
         */
        private fun rewrapRows(newColumns: Int): RewrappedScreen {
            val output = ArrayList<ResizedScreenRow>()
            var mappedCursor: ResizePosition? = null
            var mappedSavedCursor: ResizePosition? = null

            cells.indices.forEach { sourceRow ->
                val meaningfulEnd = meaningfulEndExclusive(cells[sourceRow])
                val cursorBoundary = when {
                    sourceRow != cursorRow -> null
                    wrapPending -> (cursorColumn + 1).coerceAtMost(columns)
                    cursorColumn >= meaningfulEnd -> cursorColumn
                    else -> null
                }
                val savedBoundary = savedColumn.takeIf {
                    sourceRow == savedRow && savedColumn >= meaningfulEnd
                }
                val requiredEnd = maxOf(
                    meaningfulEnd,
                    cursorBoundary ?: 0,
                    savedBoundary ?: 0,
                ).coerceIn(0, columns)
                val rowResult = rewrapRow(
                    source = cells[sourceRow],
                    sourceEndExclusive = requiredEnd,
                    newColumns = newColumns,
                    finalSoftWrap = softWrappedRows[sourceRow],
                    outputRowOffset = output.size,
                )
                output += rowResult.rows

                if (sourceRow == cursorRow) {
                    mappedCursor = if (cursorBoundary != null) {
                        requireNotNull(rowResult.boundaries[cursorBoundary])
                    } else {
                        requireNotNull(rowResult.cellPositions[cursorColumn])
                    }
                }
                if (sourceRow == savedRow) {
                    mappedSavedCursor = if (savedBoundary != null) {
                        requireNotNull(rowResult.boundaries[savedBoundary])
                    } else {
                        requireNotNull(rowResult.cellPositions[savedColumn])
                    }
                }
            }

            return RewrappedScreen(
                rows = output,
                cursor = requireNotNull(mappedCursor),
                savedCursor = requireNotNull(mappedSavedCursor),
            )
        }

        private fun rewrapRow(
            source: Array<Cell>,
            sourceEndExclusive: Int,
            newColumns: Int,
            finalSoftWrap: Boolean,
            outputRowOffset: Int,
        ): RewrappedRow {
            val output = ArrayList<ResizedScreenRow>()
            val cellPositions = arrayOfNulls<ResizePosition>(columns)
            val boundaries = arrayOfNulls<ResizePosition>(columns + 1)
            var current = blankRow(newColumns)
            var outputColumn = 0

            fun currentPosition(): ResizePosition = if (outputColumn >= newColumns) {
                ResizePosition(
                    row = outputRowOffset + output.size,
                    column = newColumns - 1,
                    wrapPending = true,
                )
            } else {
                ResizePosition(
                    row = outputRowOffset + output.size,
                    column = outputColumn,
                )
            }

            fun finishCurrent(softWrappedToNext: Boolean) {
                normalizeWideCells(current)
                output += ResizedScreenRow(current, softWrappedToNext)
                current = blankRow(newColumns)
                outputColumn = 0
            }

            var sourceColumn = 0
            while (sourceColumn < sourceEndExclusive) {
                val sourceCell = source[sourceColumn]
                if (sourceCell.continuation) {
                    boundaries[sourceColumn] = currentPosition()
                    cellPositions[sourceColumn] = currentPosition()
                    sourceColumn += 1
                    continue
                }
                val sourceWidth = if (
                    sourceColumn + 1 < source.size && source[sourceColumn + 1].continuation
                ) {
                    2
                } else {
                    1
                }
                val outputWidth = if (sourceWidth == 2 && newColumns > 1) 2 else 1
                if (outputColumn >= newColumns || outputColumn + outputWidth > newColumns) {
                    finishCurrent(softWrappedToNext = true)
                }

                val start = currentPosition()
                boundaries[sourceColumn] = start
                cellPositions[sourceColumn] = start
                current[outputColumn] = sourceCell.copy(continuation = false)
                if (outputWidth == 2) {
                    current[outputColumn + 1] = source[sourceColumn + 1]
                    cellPositions[sourceColumn + 1] = start.copy(column = outputColumn + 1)
                    boundaries[sourceColumn + 1] = start.copy(column = outputColumn + 1)
                } else if (sourceWidth == 2) {
                    // A one-column terminal cannot represent the continuation cell separately.
                    cellPositions[sourceColumn + 1] = start
                    boundaries[sourceColumn + 1] = start
                }
                outputColumn += outputWidth
                sourceColumn += sourceWidth
                boundaries[sourceColumn] = currentPosition()
            }

            if (sourceEndExclusive == 0) boundaries[0] = currentPosition()
            finishCurrent(softWrappedToNext = finalSoftWrap)
            return RewrappedRow(output, cellPositions, boundaries)
        }

        private fun meaningfulEndExclusive(row: Array<Cell>): Int {
            var last = row.lastIndex
            while (last >= 0 && row[last] == Cell.blank()) last -= 1
            return last + 1
        }

        fun snapshotLines(): ScreenLineSnapshot {
            val changed = IntArray(dirtyRows.count { it })
            var changedIndex = 0
            cells.indices.forEach { row ->
                if (dirtyRows[row] || cachedLines[row] == null) {
                    cachedLines[row] = toLine(cells[row], softWrappedRows[row])
                    dirtyRows[row] = false
                    changed[changedIndex++] = row
                }
            }
            return ScreenLineSnapshot(
                lines = List(rows) { row -> requireNotNull(cachedLines[row]) },
                dirtyRows = if (changedIndex == changed.size) changed else changed.copyOf(changedIndex),
            )
        }

        fun markAllDirty() {
            dirtyRows.fill(true)
        }

        private fun markDirty(row: Int) {
            if (row in dirtyRows.indices) dirtyRows[row] = true
        }

        private fun markDirty(firstRow: Int, lastRow: Int) {
            for (row in firstRow.coerceAtLeast(0)..lastRow.coerceAtMost(rows - 1)) markDirty(row)
        }

        private fun clearWideCellAt(row: Int, column: Int) {
            val target = cells[row][column]
            if (target.continuation && column > 0) cells[row][column - 1] = Cell.blank()
            if (!target.continuation && column + 1 < columns && cells[row][column + 1].continuation) {
                cells[row][column + 1] = Cell.blank()
            }
        }

        companion object {
            private fun blankRow(columns: Int, style: TerminalStyle = TerminalStyle()): Array<Cell> {
                val erasedStyle = style.forErasedCell()
                return Array(columns) { Cell(" ", erasedStyle, null, false) }
            }

            private fun normalizeWideCells(row: Array<Cell>) {
                row.indices.forEach { column ->
                    if (row[column].continuation && (column == 0 || row[column - 1].continuation)) {
                        row[column] = Cell.blank()
                    }
                }
            }

            private fun toLine(row: Array<Cell>, softWrappedToNext: Boolean): TerminalLine {
                var last = row.lastIndex
                while (
                    last >= 0 &&
                    !row[last].continuation &&
                    row[last].content == " " &&
                    row[last].style == TerminalStyle()
                ) {
                    last -= 1
                }
                if (last < 0) return TerminalLine.plain("", softWrappedToNext = softWrappedToNext)
                val runs = ArrayList<TerminalRun>()
                var currentStyle: TerminalStyle? = null
                var currentHyperlink: TerminalHyperlink? = null
                var runStartColumn = -1
                val text = StringBuilder()
                for (column in 0..last) {
                    val cell = row[column]
                    if (cell.continuation) continue
                    if (
                        currentStyle != null &&
                        (currentStyle != cell.style || currentHyperlink != cell.hyperlink)
                    ) {
                        runs += TerminalRun(
                            text = text.toString(),
                            style = currentStyle,
                            hyperlink = currentHyperlink,
                            startColumn = runStartColumn,
                            columnWidth = column - runStartColumn,
                        )
                        text.clear()
                        runStartColumn = column
                    }
                    if (runStartColumn < 0) runStartColumn = column
                    currentStyle = cell.style
                    currentHyperlink = cell.hyperlink
                    text.append(cell.content)
                }
                if (text.isNotEmpty()) {
                    runs += TerminalRun(
                        text = text.toString(),
                        style = currentStyle ?: TerminalStyle(),
                        hyperlink = currentHyperlink,
                        startColumn = runStartColumn,
                        columnWidth = last + 1 - runStartColumn,
                    )
                }
                return TerminalLine.styled(runs, softWrappedToNext)
            }
        }
    }

    private data class Cell(
        val content: String,
        val style: TerminalStyle,
        val hyperlink: TerminalHyperlink?,
        val continuation: Boolean,
    ) {
        companion object {
            fun blank() = Cell(" ", TerminalStyle(), null, false)
        }
    }

    companion object {
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val MAX_COLUMNS = 500
        private const val MAX_ROWS = 300
        private const val MAX_CONTROL_SEQUENCE = 4_096
        private const val MAX_OSC_SEQUENCE = 1_024
        private const val MAX_OSC52_BASE64_LENGTH = 1_024
        private const val MAX_REMOTE_CLIPBOARD_REQUESTS_PER_ACCEPT = 4
        private const val MAX_DEFERRED_RESIZE_SCROLLBACK = 20_000
        private const val MAX_PARAMETERS = 32
        private const val MAX_PARAMETER_VALUE = 100_000
        private const val DECSCUSR_MIN = 0
        private const val DECSCUSR_MAX = 6
        private const val TAB_WIDTH = 8
        private const val CSI_FINAL_START = 0x40
        private const val CSI_FINAL_END = 0x7E
        private const val ESCAPE = '\u001B'
        private const val BEL = '\u0007'
        private const val STRING_TERMINATOR = '\u009C'
        private const val REPLACEMENT_CHARACTER = '\uFFFD'
        private const val ZERO_WIDTH_JOINER = 0x200D
        private const val REPLACEMENT = "\uFFFD"
        private val ALLOWED_HYPERLINK_SCHEMES = setOf("http", "https")
        private val SAFE_CLIPBOARD_CONTROLS = setOf('\t'.code, '\n'.code, '\r'.code)
        private val CSI_STATUS_OK = "\u001B[0n".toByteArray()
        private val PRIMARY_DEVICE_ATTRIBUTES = "\u001B[?1;2c".toByteArray()
        private val SECONDARY_DEVICE_ATTRIBUTES = "\u001B[>0;136;0c".toByteArray()
    }
}

/**
 * Background-colour erase retains the selected background, but erased cells are not printable
 * spaces and must not inherit text renditions such as underline or strike-through.
 */
private fun TerminalStyle.forErasedCell(): TerminalStyle = TerminalStyle(background = background)
