package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
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

    private fun findPlainTextUrl(
        selectable: TerminalSelectableLine,
        column: Int,
    ): TerminalLinkTarget? {
        val text = selectable.line.text
        if (text.isEmpty()) return null
        val hit = TerminalLineGeometry.positionAtColumn(selectable.line, column).textOffset
            .coerceIn(0, text.length)
        val pivot = if (hit == text.length) hit - 1 else hit
        if (pivot < 0 || isUrlBoundary(text[pivot])) return null
        var start = pivot
        var end = pivot + 1
        while (start > 0 && pivot - start < MAX_PLAIN_URL_SCAN && !isUrlBoundary(text[start - 1])) start -= 1
        while (end < text.length && end - pivot < MAX_PLAIN_URL_SCAN && !isUrlBoundary(text[end])) end += 1
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) end -= 1
        if (end <= start) return null
        val candidate = text.substring(start, end)
        if (!TerminalLinkPolicy.canOpen(candidate)) return null
        return TerminalLinkTarget(
            uri = candidate,
            id = null,
            source = TerminalLinkSource.PLAIN_TEXT,
            line = selectable.anchor,
            startColumn = TerminalLineGeometry.columnAtOffset(selectable.line, start),
            endColumn = TerminalLineGeometry.columnAtOffset(selectable.line, end),
        )
    }

    private fun isUrlBoundary(character: Char): Boolean =
        character.isWhitespace() || character.isISOControl() || character in URL_BOUNDARIES

    private const val MAX_PLAIN_URL_SCAN = 1_024
    private const val URL_BOUNDARIES = "\"'<>[]{}"
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"
}
