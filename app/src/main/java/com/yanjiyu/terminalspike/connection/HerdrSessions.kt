package com.yanjiyu.terminalspike.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal sealed interface StartupSessionChoice {
    val executable: String
}

internal data class HerdrStartupChoice(
    override val executable: String,
    val sessionName: String,
) : StartupSessionChoice {
    init {
        require(executable.isHerdrExecutablePath())
        require(sessionName.isHerdrSessionName())
    }

    fun command(): String = listOf(executable, "session", "attach", sessionName)
        .joinToString(" ", transform = ::quotePosixShellArgument)
}

internal fun startupSessionCommand(choice: StartupSessionChoice): String = when (choice) {
    is TmuxStartupChoice -> tmuxStartupCommand(choice)
    is HerdrStartupChoice -> choice.command()
}

internal data class HerdrSession(val name: String, val isDefault: Boolean = false) {
    init { require(name.isHerdrSessionName()) }
    val selectionId: String get() = HERDR_SELECTION_PREFIX + name
}

internal enum class HerdrAvailability { AVAILABLE, NOT_INSTALLED, CHECK_FAILED }

internal data class HerdrSessionCatalog(
    val sessions: List<HerdrSession> = emptyList(),
    val availability: HerdrAvailability = HerdrAvailability.CHECK_FAILED,
    val executable: String? = null,
)

/** Runs only during authenticated startup selection, never on the input/render path. */
internal fun inspectHerdrSessions(runner: TmuxCommandRunner): HerdrSessionCatalog = runCatching {
    parseHerdrSessionCatalog(runner.run(HERDR_LIST_COMMAND))
}.getOrElse { HerdrSessionCatalog() }

internal fun parseHerdrSessionCatalog(output: TmuxExecOutput): HerdrSessionCatalog {
    if (output.exitStatus == 127) return HerdrSessionCatalog(availability = HerdrAvailability.NOT_INSTALLED)
    if (output.exitStatus != 0 || output.stdout.size > 64 * 1024) return HerdrSessionCatalog()
    return runCatching {
        val lines = output.stdout.toString(Charsets.UTF_8).lines()
        val marker = lines.indexOf(HERDR_AVAILABLE_MARKER)
        if (marker < 0) return HerdrSessionCatalog()
        val executable = lines.getOrNull(marker + 1)?.takeIf { it.isHerdrExecutablePath() }
            ?: return HerdrSessionCatalog()
        val root = Json.parseToJsonElement(lines.drop(marker + 2).joinToString("\n")) as? JsonObject
            ?: return HerdrSessionCatalog()
        val entries = root["sessions"] as? JsonArray ?: return HerdrSessionCatalog()
        val sessions = entries.asSequence().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if ((item["running"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
            val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isHerdrSessionName() } ?: return@mapNotNull null
            HerdrSession(name, (item["default"] as? JsonPrimitive)?.booleanOrNull == true)
        }.distinctBy { it.name }.take(MAX_HERDR_SESSIONS)
            .sortedWith(compareByDescending<HerdrSession> { it.isDefault }.thenBy { it.name })
            .toList()
        HerdrSessionCatalog(sessions, HerdrAvailability.AVAILABLE, executable)
    }.getOrElse { HerdrSessionCatalog() }
}

internal fun String.isHerdrSelectionId(): Boolean =
    startsWith(HERDR_SELECTION_PREFIX) && removePrefix(HERDR_SELECTION_PREFIX).isHerdrSessionName()

private fun String.isHerdrSessionName(): Boolean =
    length in 1..128 && this == trim() && first() != '-' && this != "." && this != ".." &&
        none { it.isISOControl() || it == '/' || it == '\\' }

private fun String.isHerdrExecutablePath(): Boolean =
    length in 2..1_024 && startsWith('/') && none(Char::isISOControl)

internal const val MAX_HERDR_SESSIONS = 128
private const val HERDR_SELECTION_PREFIX = "herdr:"
private const val HERDR_AVAILABLE_MARKER = "__TERMINAL_SPIKE_HERDR__"
private val HERDR_PROBE_SCRIPT = """
    terminal_spike_herdr_bin=${'$'}(command -v herdr 2>/dev/null || true)
    if [ ! -x "${'$'}terminal_spike_herdr_bin" ]; then
        for terminal_spike_candidate in "${'$'}HOME/.local/bin/herdr" "${'$'}HOME/.cargo/bin/herdr" "${'$'}HOME/bin/herdr" "${'$'}HOME/.nix-profile/bin/herdr" /usr/local/bin/herdr /usr/bin/herdr /opt/homebrew/bin/herdr; do
            if [ -x "${'$'}terminal_spike_candidate" ]; then terminal_spike_herdr_bin=${'$'}terminal_spike_candidate; break; fi
        done
    fi
    if [ ! -x "${'$'}terminal_spike_herdr_bin" ] && [ -x "${'$'}SHELL" ]; then
        terminal_spike_herdr_bin=${'$'}("${'$'}SHELL" -lic 'command -v herdr' 2>/dev/null | while IFS= read -r terminal_spike_candidate; do
            case "${'$'}terminal_spike_candidate" in /*) [ -x "${'$'}terminal_spike_candidate" ] && printf '%s\n' "${'$'}terminal_spike_candidate";; esac
        done | tail -n 1)
    fi
    [ -x "${'$'}terminal_spike_herdr_bin" ] || exit 127
    printf '$HERDR_AVAILABLE_MARKER\n%s\n' "${'$'}terminal_spike_herdr_bin"
    "${'$'}terminal_spike_herdr_bin" session list --json
""".trimIndent()
internal val HERDR_LIST_COMMAND = "/bin/sh -c ${quotePosixShellArgument(HERDR_PROBE_SCRIPT)}"
