package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.HerdrPiScrollPane
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Bounded metadata only. Explicit ids avoid inheriting HERDR_PANE_ID from a login shell. */
internal fun captureHerdrInputContext(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
): TerminalInputContext = runCatching {
    fun result(vararg arguments: String): JsonObject {
        val command = (listOf(choice.executable, "--session", choice.sessionName, "pane") + arguments)
            .joinToString(" ", transform = ::quotePosixShellArgument)
        val output = runner.run(command)
        require(output.exitStatus == 0 && output.stdout.size <= 64 * 1024)
        return (Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)) as JsonObject)
            .getValue("result") as JsonObject
    }
    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    fun layout() = result("layout").getValue("layout") as JsonObject
    fun focus(layout: JsonObject) = layout.string("tab_id") to layout.string("focused_pane_id")
    val initialLayout = layout()
    val focused = focus(initialLayout)
    val paneId = requireNotNull(focused.second)
    // Herdr public pane numbers include uppercase letters (for example w6:pC).
    require(paneId.matches(Regex("[A-Za-z0-9_-]+:p[0-9A-Z]+")))
    val pane = result("get", paneId).getValue("pane") as JsonObject
    require(pane.string("pane_id") == paneId && pane.string("tab_id") == focused.first)
    val process = result("process-info", "--pane", paneId).getValue("process_info") as JsonObject
    require(process.string("pane_id") == paneId)
    val names = (process.getValue("foreground_processes") as JsonArray).mapNotNull {
        (it as? JsonObject)?.string("name")
    }
    val agent = pane.string("agent")?.takeUnless { it.isBlank() || it == "shell" || it == "unknown" }
    // A shell must not inherit a previous agent label after that process exits.
    val agentForeground = names.any { it == "codex" || agent != null && it == agent }
    val piScrollPane = if (agent == "pi" && names.any { it == "pi" } &&
        ((pane["scroll"] as? JsonObject)?.get("max_offset_from_bottom") as? JsonPrimitive)?.intOrNull == 0
    ) {
        val panes = initialLayout["panes"] as? JsonArray
        val rect = panes?.mapNotNull { it as? JsonObject }?.singleOrNull { it.string("pane_id") == paneId }
            ?.get("rect") as? JsonObject
        fun integer(key: String) = (rect?.get(key) as? JsonPrimitive)?.intOrNull
        val x = integer("x")
        val y = integer("y")
        val columns = integer("width")
        val rows = integer("height")
        if (x != null && y != null && columns != null && rows != null &&
            x in 0..500 && y in 0..500 && columns in 1..500 && rows in 1..500 &&
            x + columns <= 500 && y + rows <= 500
        ) HerdrPiScrollPane(x, y, columns, rows) else null
    } else null
    require(layout() == initialLayout)
    TerminalInputContext("herdr/${choice.sessionName}/$paneId/${pane.string("terminal_id")}", agentForeground, piScrollPane)
}.getOrDefault(TerminalInputContext())
