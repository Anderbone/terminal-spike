package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One atomic metadata snapshot: no pane focus, keys, or terminal output reads. */
internal fun captureHerdrProjectLocations(
    runner: TmuxCommandRunner,
    choice: HerdrStartupChoice,
): List<RemoteProjectLocation> {
    val command = listOf(choice.executable, "--session", choice.sessionName, "api", "snapshot")
        .joinToString(" ", transform = ::quotePosixShellArgument)
    return parseHerdrProjectLocations(runner.run(command))
}

internal fun parseHerdrProjectLocations(output: TmuxExecOutput): List<RemoteProjectLocation> = runCatching {
    require(output.exitStatus == 0 && output.stdout.size <= 1024 * 1024)
    val result = (Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)) as JsonObject)["result"] as JsonObject
    val snapshot = result["snapshot"] as JsonObject
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val focusedPane = snapshot.string("focused_pane_id")
    val workspaces = (snapshot["workspaces"] as? JsonArray).orEmpty().mapNotNull { element ->
        val workspace = element as? JsonObject ?: return@mapNotNull null
        val id = workspace.string("workspace_id") ?: return@mapNotNull null
        id to (workspace.string("label") ?: id)
    }.toMap()
    (snapshot["panes"] as? JsonArray).orEmpty().mapNotNull { element ->
        val pane = element as? JsonObject ?: return@mapNotNull null
        val paneId = pane.string("pane_id") ?: return@mapNotNull null
        val workspaceId = pane.string("workspace_id") ?: return@mapNotNull null
        val path = listOfNotNull(pane.string("foreground_cwd"), pane.string("cwd"))
            .firstOrNull { it.startsWith('/') && it.length <= 4096 && '\u0000' !in it } ?: return@mapNotNull null
        RemoteProjectLocation(path, workspaces[workspaceId] ?: workspaceId, paneId == focusedPane)
    }.sortedByDescending { it.current }.distinctBy { it.path }.take(64)
}.getOrDefault(emptyList())
