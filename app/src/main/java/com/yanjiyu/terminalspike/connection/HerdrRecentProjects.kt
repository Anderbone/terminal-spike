package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun HerdrStartupChoice.projectCommand(vararg args: String) =
    (listOf(executable, "--session", sessionName) + args).joinToString(" ", transform = ::quotePosixShellArgument)

internal fun validHerdrProjectPath(path: String) =
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
                ?.takeIf(::validHerdrProjectPath)?.trimEnd('/')
        }
    }.toSet()
}

internal fun filterHerdrRecentProjects(output: TmuxExecOutput, open: Set<String>): List<RecentHerdrProject> {
    require(output.exitStatus == 0 && output.stdout.size <= 1024 * 1024)
    val header = (HERDR_RECENT_PROJECTS_HEADER + "\n").toByteArray()
    val enriched = header.indices.all { output.stdout.getOrNull(it) == header[it] }
    val separator = if (enriched) output.stdout.indexOf(0) else -1
    require(!enriched || separator >= header.size)
    val paths = if (separator >= header.size) output.stdout.copyOfRange(header.size, separator) else output.stdout
    val times = if (separator >= 0) {
        zoxideLastUsedTimes(output.stdout.copyOfRange(separator + 1, output.stdout.size))
    } else emptyMap()
    return paths.toString(Charsets.UTF_8).lineSequence()
        .filter(::validHerdrProjectPath).map { it.trimEnd('/') }
        .filter { validHerdrProjectPath(it) && it !in open }.distinct()
        .map { RecentHerdrProject(it, times[it]) }
        .sortedWith(compareByDescending<RecentHerdrProject> { it.lastUsedAtEpochMillis }.thenBy { it.path })
        .take(256).toList()
}

/** Candidates come from zoxide; its recorded access times define recency, independent of score. */
internal fun recentHerdrProjects(runner: TmuxCommandRunner, choice: HerdrStartupChoice): List<RecentHerdrProject>? = runCatching {
    val open = herdrOpenProjectPaths(runner.run(choice.projectCommand("api", "snapshot")))
    filterHerdrRecentProjects(runner.run(HERDR_RECENT_PROJECTS_COMMAND), open)
}.getOrNull()

internal fun openHerdrProject(runner: TmuxCommandRunner, choice: HerdrStartupChoice, path: String): Boolean = runCatching {
    require(validHerdrProjectPath(path))
    // Refresh before creating: another client may have opened the selected directory meanwhile.
    require(path.trimEnd('/') !in herdrOpenProjectPaths(runner.run(choice.projectCommand("api", "snapshot"))))
    val create = choice.projectCommand("workspace", "create", "--cwd", path, "--focus")
    // Only a confirmed successful creation records a visit; no retry of the create command.
    val script = """
        test -d ${quotePosixShellArgument(path)} && $create || exit ${'$'}?
        $HERDR_ZOXIDE_PROBE_SCRIPT
        if [ -x "${'$'}terminal_spike_zoxide" ]; then
            "${'$'}terminal_spike_zoxide" add -- ${quotePosixShellArgument(path)} >/dev/null 2>&1 || :
        fi
    """.trimIndent()
    val output = runner.run("/bin/sh -c ${quotePosixShellArgument(script)}")
    require(output.exitStatus == 0)
    true
}.getOrDefault(false)

private val HERDR_ZOXIDE_PROBE_SCRIPT = """
    terminal_spike_zoxide=${'$'}(command -v zoxide 2>/dev/null || true)
    if [ ! -x "${'$'}terminal_spike_zoxide" ]; then
        for terminal_spike_candidate in "${'$'}HOME/.local/bin/zoxide" "${'$'}HOME/.cargo/bin/zoxide" /usr/local/bin/zoxide /usr/bin/zoxide /opt/homebrew/bin/zoxide; do
            if [ -x "${'$'}terminal_spike_candidate" ]; then terminal_spike_zoxide=${'$'}terminal_spike_candidate; break; fi
        done
    fi
""".trimIndent()

internal val HERDR_RECENT_PROJECTS_COMMAND = "/bin/sh -c " + quotePosixShellArgument("""
    $HERDR_ZOXIDE_PROBE_SCRIPT
    [ -x "${'$'}terminal_spike_zoxide" ] || exit 127
    printf '$HERDR_RECENT_PROJECTS_HEADER\n'
    "${'$'}terminal_spike_zoxide" query --list || exit ${'$'}?
    printf '\000'
    if [ -n "${'$'}_ZO_DATA_DIR" ]; then
        terminal_spike_data_dir=${'$'}_ZO_DATA_DIR
    elif [ "${'$'}(uname -s)" = Darwin ]; then
        terminal_spike_data_dir="${'$'}HOME/Library/Application Support/zoxide"
    else
        terminal_spike_data_dir="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}/zoxide"
    fi
    # Optional, bounded metadata only. Never parse a database while it is being written in place.
    # zoxide itself replaces its database atomically; head opens one immutable file snapshot.
    if [ -r "${'$'}terminal_spike_data_dir/db.zo" ]; then
        head -c ${MAX_ZOXIDE_METADATA_BYTES + 1} "${'$'}terminal_spike_data_dir/db.zo" 2>/dev/null || :
    fi
""".trimIndent())

internal const val HERDR_RECENT_PROJECTS_HEADER = "__TERMINAL_SPIKE_RECENT_PROJECTS_1__"
