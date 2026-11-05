package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun HerdrStartupChoice.projectCommand(vararg args: String) =
    (listOf(executable, "--session", sessionName) + args).joinToString(" ", transform = ::quotePosixShellArgument)

private fun validProjectPath(path: String) =
    path.startsWith('/') && path.length in 2..4096 && path.none(Char::isISOControl)

/** Fail closed if the snapshot is unavailable: an empty open set must never mean a failed read. */
internal fun herdrOpenProjectPaths(output: TmuxExecOutput): Set<String> {
    require(output.exitStatus == 0 && output.stdout.size <= 1024 * 1024)
    val root = Json.parseToJsonElement(output.stdout.toString(Charsets.UTF_8)) as JsonObject
    val snapshot = (root.getValue("result") as JsonObject).getValue("snapshot") as JsonObject
    val panes = snapshot.getValue("panes") as JsonArray
    return panes.flatMap { element ->
        val pane = element as JsonObject
        listOf("cwd", "foreground_cwd").mapNotNull { key ->
            (pane[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf(::validProjectPath)?.trimEnd('/')
        }
    }.toSet()
}

internal fun filterHerdrRecentProjects(output: TmuxExecOutput, open: Set<String>): List<String> {
    require(output.exitStatus == 0 && output.stdout.size <= 1024 * 1024)
    return output.stdout.toString(Charsets.UTF_8).lineSequence()
        .filter(::validProjectPath).map { it.trimEnd('/') }
        .filter { it !in open }.distinct().take(256).toList()
}

/** zoxide keeps the user's existing directory ranking; no new database or shell keystrokes. */
internal fun recentHerdrProjects(runner: TmuxCommandRunner, choice: HerdrStartupChoice): List<String>? = runCatching {
    val open = herdrOpenProjectPaths(runner.run(choice.projectCommand("api", "snapshot")))
    filterHerdrRecentProjects(runner.run(HERDR_RECENT_PROJECTS_COMMAND), open)
}.getOrNull()

internal fun openHerdrProject(runner: TmuxCommandRunner, choice: HerdrStartupChoice, path: String): Boolean = runCatching {
    require(validProjectPath(path))
    // Refresh before creating: another client may have opened the selected directory meanwhile.
    require(path.trimEnd('/') !in herdrOpenProjectPaths(runner.run(choice.projectCommand("api", "snapshot"))))
    val create = choice.projectCommand("workspace", "create", "--cwd", path, "--focus")
    val script = "test -d ${quotePosixShellArgument(path)} && exec $create"
    val output = runner.run("/bin/sh -c ${quotePosixShellArgument(script)}")
    require(output.exitStatus == 0)
    true
}.getOrDefault(false)

internal val HERDR_RECENT_PROJECTS_COMMAND = "/bin/sh -c " + quotePosixShellArgument("""
    terminal_spike_zoxide=${'$'}(command -v zoxide 2>/dev/null || true)
    if [ ! -x "${'$'}terminal_spike_zoxide" ]; then
        for terminal_spike_candidate in "${'$'}HOME/.local/bin/zoxide" "${'$'}HOME/.cargo/bin/zoxide" /usr/local/bin/zoxide /usr/bin/zoxide /opt/homebrew/bin/zoxide; do
            if [ -x "${'$'}terminal_spike_candidate" ]; then terminal_spike_zoxide=${'$'}terminal_spike_candidate; break; fi
        done
    fi
    [ -x "${'$'}terminal_spike_zoxide" ] || exit 127
    exec "${'$'}terminal_spike_zoxide" query --list
""".trimIndent())
