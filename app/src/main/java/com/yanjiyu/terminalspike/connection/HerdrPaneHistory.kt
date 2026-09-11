package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** An immutable, bounded pane read. Coordinates are cells in the outer Herdr display. */
data class HerdrPaneHistory(
    val identity: String,
    val x: Int,
    val y: Int,
    val columns: Int,
    val rows: Int,
    val offsetFromBottom: Int,
    val lines: List<TerminalLine>,
    val revision: String = "",
) {
    fun samePane(other: HerdrPaneHistory): Boolean = identity == other.identity &&
        x == other.x && y == other.y && columns == other.columns && rows == other.rows

    companion object {
        const val ROW_LIMIT = 1_000
        const val BYTE_LIMIT = 4 * 1024 * 1024
    }
}

/** Only app-selected sessions are queried, through their existing authenticated SSH channel. */
internal fun captureHerdrPaneHistory(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
    previous: HerdrPaneHistory?,
    reading: Boolean,
): HerdrPaneHistory? = runCatching {
    fun command(vararg arguments: String): String =
        (listOf(choice.executable, "--session", choice.sessionName) + arguments)
            .joinToString(" ", transform = ::quotePosixShellArgument)
    fun run(vararg arguments: String): ByteArray {
        val output = runner.run(command(*arguments))
        require(output.exitStatus == 0 && output.stdout.size <= HerdrPaneHistory.BYTE_LIMIT)
        return output.stdout
    }
    fun result(vararg arguments: String): JsonObject =
        (Json.parseToJsonElement(run(*arguments).toString(Charsets.UTF_8)) as JsonObject)
            .getValue("result") as JsonObject

    val layout = result("pane", "layout").getValue("layout") as JsonObject
    val paneId = layout.string("focused_pane_id")
    require(paneId.matches(Regex("[A-Za-z0-9_-]+:p[0-9]+")))
    val paneLayout = (layout.getValue("panes") as JsonArray).map { it as JsonObject }
        .single { it.string("pane_id") == paneId }
    val rect = paneLayout.getValue("rect") as JsonObject
    val x = rect.integer("x")
    val y = rect.integer("y")
    val columns = rect.integer("width")
    val rows = rect.integer("height")
    require(x in 0..500 && y in 0..500 && columns in 1..500 && rows in 1..500)
    val before = result("pane", "get", paneId).getValue("pane") as JsonObject
    require(before.string("pane_id") == paneId && before.string("tab_id") == layout.string("tab_id"))
    // Limit automatic native ownership to the requested Codex terminal, not arbitrary mouse apps.
    require((before["agent"] as? JsonPrimitive)?.content == "codex")
    val scroll = before.getValue("scroll") as JsonObject
    require(scroll.integer("viewport_rows") == rows)
    val offset = scroll.integer("offset_from_bottom")
    val terminalId = before.string("terminal_id")
    val revision = (before.getValue("revision") as JsonPrimitive).content
    val identity = "${choice.sessionName}/$paneId/$terminalId"
    if (previous != null && previous.identity == identity &&
        previous.x == x && previous.y == y && previous.columns == columns && previous.rows == rows &&
        (reading || previous.revision == revision && previous.offsetFromBottom == offset)
    ) return previous

    val content = run("pane", "read", paneId, "--source", "recent", "--lines", "1000", "--format", "ansi")
    val after = result("pane", "get", paneId).getValue("pane") as JsonObject
    val finalLayout = result("pane", "layout").getValue("layout") as JsonObject
    require(layout == finalLayout && before["revision"] == after["revision"] &&
        before["terminal_id"] == after["terminal_id"] && before["scroll"] == after["scroll"])
    val lines = parseHerdrHistoryRows(content, columns, rows) ?: return null
    require(offset >= 0 && offset <= (lines.size - rows).coerceAtLeast(0))
    HerdrPaneHistory(identity, x, y, columns, rows, offset, lines, revision)
}.getOrNull()

/** Parse physical rows once off-thread. Snapshot escape sequences never reach the live controller. */
internal fun parseHerdrHistoryRows(content: ByteArray, columns: Int, rows: Int): List<TerminalLine>? {
    if (content.isEmpty() || content.size > HerdrPaneHistory.BYTE_LIMIT || columns !in 1..500 ||
        rows !in 1..500
    ) return null
    val text = content.toString(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
    if (text.count { it == '\n' } >= HerdrPaneHistory.ROW_LIMIT) return null
    val bytes = text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray()
    val update = VtTerminalEngine(columns = columns, rows = 1).accept(bytes)
    val lines = update.completedScrollback + update.screen
    if (lines.size > HerdrPaneHistory.ROW_LIMIT) return null
    // Herdr omits blank rows at the end of a read; preserve the visible grid's height.
    return if (lines.size < rows) lines + List(rows - lines.size) { TerminalLine.plain("") } else lines
}

private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive)
    .also { require(it.isString) }.content
private fun JsonObject.integer(key: String): Int = requireNotNull((getValue(key) as JsonPrimitive).intOrNull)
