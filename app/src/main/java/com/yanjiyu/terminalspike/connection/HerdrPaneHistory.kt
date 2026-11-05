package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** An immutable, bounded pane read. Coordinates belong to the API's layout surface. */
data class HerdrPaneHistory(
    val identity: String,
    val x: Int,
    val y: Int,
    val columns: Int,
    val rows: Int,
    val offsetFromBottom: Int,
    val lines: List<TerminalLine>,
    val revision: String = "",
    val surface: HerdrPaneArea? = null,
) {
    fun samePane(other: HerdrPaneHistory): Boolean = identity == other.identity &&
        x == other.x && y == other.y && columns == other.columns && rows == other.rows && surface == other.surface

    companion object {
        const val ROW_LIMIT = 1_000
        const val BYTE_LIMIT = 4 * 1024 * 1024
    }
}

/** Herdr 0.9 clients place this server surface inside their own navigation chrome. */
data class HerdrPaneArea(val x: Int, val y: Int, val columns: Int, val rows: Int)

/** A failed read is different from authoritative evidence that history is unavailable. */
sealed interface HerdrHistoryRead {
    data class Available(val history: HerdrPaneHistory) : HerdrHistoryRead
    data class Retry(val target: HerdrPaneTarget? = null) : HerdrHistoryRead
    data object Unavailable : HerdrHistoryRead
}

data class HerdrPaneTarget(
    val identity: String, val x: Int, val y: Int, val columns: Int, val rows: Int,
    val surface: HerdrPaneArea?,
) {
    fun matches(history: HerdrPaneHistory): Boolean = identity == history.identity &&
        x == history.x && y == history.y && columns == history.columns && rows == history.rows && surface == history.surface
}

private class RetryHerdrHistoryRead : RuntimeException()

private fun isHerdrMissingPane(bytes: ByteArray): Boolean = runCatching {
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    val error = root?.get("error") as? JsonObject
    (error?.get("code") as? JsonPrimitive)?.content in setOf("pane_not_found", "session_not_found", "workspace_not_found")
}.getOrDefault(false)

/** Only app-selected sessions are queried, through their existing authenticated SSH channel. */
internal fun captureHerdrPaneHistory(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
    previous: HerdrPaneHistory?,
    reading: Boolean,
): HerdrPaneHistory? = (readHerdrPaneHistory(runner, choice, previous, reading) as? HerdrHistoryRead.Available)?.history

internal fun readHerdrPaneHistory(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
    previous: HerdrPaneHistory?,
    reading: Boolean,
): HerdrHistoryRead {
    var target: HerdrPaneTarget? = null
    return try {
        val history = run {
            fun command(vararg arguments: String): String =
                (listOf(choice.executable, "--session", choice.sessionName) + arguments)
                    .joinToString(" ", transform = ::quotePosixShellArgument)
            fun run(vararg arguments: String): ByteArray {
                val output = try { runner.run(command(*arguments)) } catch (_: Exception) { throw RetryHerdrHistoryRead() }
                require(output.stdout.size <= HerdrPaneHistory.BYTE_LIMIT)
                if (output.exitStatus != 0) {
                    require(!isHerdrMissingPane(output.stdout))
                    throw RetryHerdrHistoryRead()
                }
                return output.stdout
            }
            fun result(vararg arguments: String): JsonObject {
                val bytes = run(*arguments)
                require(!isHerdrMissingPane(bytes))
                return try {
                    (Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject).getValue("result") as JsonObject
                } catch (_: Exception) { throw RetryHerdrHistoryRead() }
            }

            val layout = result("pane", "layout").getValue("layout") as JsonObject
            val paneId = layout.string("focused_pane_id")
            // Herdr public pane numbers include uppercase letters (for example w6:pC).
            require(paneId.matches(Regex("[A-Za-z0-9_-]+:p[0-9A-Z]+")))
            val paneLayout = (layout.getValue("panes") as JsonArray).map { it as JsonObject }
                .single { it.string("pane_id") == paneId }
            val rect = paneLayout.getValue("rect") as JsonObject
            val x = rect.integer("x")
            val y = rect.integer("y")
            val columns = rect.integer("width")
            val rows = rect.integer("height")
            require(x in 0..500 && y in 0..500 && columns in 1..500 && rows in 1..500)
            val surface = (layout["area"] as? JsonObject)?.let { area ->
                HerdrPaneArea(area.integer("x"), area.integer("y"), area.integer("width"), area.integer("height")).also {
                    require(it.x in 0..500 && it.y in 0..500 && it.columns in 1..500 && it.rows in 1..500 &&
                        it.x + it.columns <= 500 && it.y + it.rows <= 500 &&
                        x >= it.x && y >= it.y && x + columns <= it.x + it.columns && y + rows <= it.y + it.rows)
                }
            }
            val before = result("pane", "get", paneId).getValue("pane") as JsonObject
            require(before.string("pane_id") == paneId && before.string("tab_id") == layout.string("tab_id"))
            // Only supported transcript UIs may hand passive history to the native reader.
            require((before["agent"] as? JsonPrimitive)?.content in setOf("codex", "pi"))
            val scroll = before.getValue("scroll") as JsonObject
            val viewportRows = scroll.integer("viewport_rows")
            require(viewportRows > 0)
            // Another client or a resize can temporarily report a layout and
            // terminal height from different frames. This is not history loss.
            if (viewportRows != rows) throw RetryHerdrHistoryRead()
            // Wrapped visible content can exceed the pane height without any real history.
            // Fullscreen UIs must retain wheel ownership even when their ANSI read looks longer.
            val maximumOffset = (scroll["max_offset_from_bottom"] as? JsonPrimitive)?.intOrNull
            require(maximumOffset == null || maximumOffset >= 0)
            val offset = scroll.integer("offset_from_bottom")
            require(offset >= 0)
            val terminalId = before.string("terminal_id")
            val revision = (before.getValue("revision") as JsonPrimitive).content
            val identity = "${choice.sessionName}/$paneId/$terminalId"
            val paneTarget = HerdrPaneTarget(identity, x, y, columns, rows, surface)
            target = paneTarget
            // A pinned snapshot owns historical rows until live return. A reset of the
            // live terminal's history cannot erase those already captured reader rows.
            if (reading && previous != null && paneTarget.matches(previous)) {
                return@run if (previous.offsetFromBottom == offset) previous else previous.copy(offsetFromBottom = offset)
            }
            require(maximumOffset == null || maximumOffset > 0)
            if (previous != null && paneTarget.matches(previous) &&
                previous.revision == revision && previous.offsetFromBottom == offset
            ) return@run previous

            val content = run("pane", "read", paneId, "--source", "recent", "--lines", "1000", "--format", "ansi")
            val after = result("pane", "get", paneId).getValue("pane") as JsonObject
            val finalLayout = result("pane", "layout").getValue("layout") as JsonObject
            require(before["terminal_id"] == after["terminal_id"] &&
                (after["agent"] as? JsonPrimitive)?.content in setOf("codex", "pi"))
            if (layout != finalLayout) {
                val finalPane = (finalLayout["panes"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.singleOrNull { it["pane_id"] == paneLayout["pane_id"] }
                require(finalLayout["focused_pane_id"] == layout["focused_pane_id"] &&
                    finalLayout["tab_id"] == layout["tab_id"])
                if (finalLayout["area"] != layout["area"]) {
                    target = null // Resize/client handoff; no consistent geometry from this read.
                    throw RetryHerdrHistoryRead()
                }
                require(finalPane?.get("rect") == rect)
                throw RetryHerdrHistoryRead()
            }
            if (before["revision"] != after["revision"] || before["scroll"] != after["scroll"]) throw RetryHerdrHistoryRead()
            val lines = parseHerdrHistoryRows(content, columns, rows) ?: return@run null
            // Full-screen Codex can own its transcript while Herdr's ANSI read contains only
            // the visible grid. An empty native scroll range would swallow its mouse gestures.
            require(lines.size > rows)
            require(offset >= 0 && offset <= (lines.size - rows).coerceAtLeast(0))
            HerdrPaneHistory(identity, x, y, columns, rows, offset, lines, revision, surface)
        }
        history?.let { HerdrHistoryRead.Available(it) } ?: HerdrHistoryRead.Unavailable
    } catch (_: RetryHerdrHistoryRead) {
        HerdrHistoryRead.Retry(target)
    } catch (_: Exception) {
        HerdrHistoryRead.Unavailable
    }
}

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
