package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    fun focus(): Pair<String?, String?> {
        val layout = result("layout").getValue("layout") as JsonObject
        return layout.string("tab_id") to layout.string("focused_pane_id")
    }
    val focused = focus()
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
    require(focus() == focused)
    TerminalInputContext("herdr/${choice.sessionName}/$paneId/${pane.string("terminal_id")}", agentForeground)
}.getOrDefault(TerminalInputContext())
