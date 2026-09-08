package com.yanjiyu.terminalspike.connection

/** Identity is scoped to this SSH transport, not whichever tmux session was most recently used. */
internal data class TmuxClientIdentity(
    val tty: String,
    val sessionId: String,
    val paneId: String,
    val serverPid: Long,
)

internal sealed interface TmuxClientObservation {
    data class Attached(val identity: TmuxClientIdentity) : TmuxClientObservation
    data object Detached : TmuxClientObservation
    data object Unavailable : TmuxClientObservation
}

/** Runs only on the existing background side channel. No remote environment values leave the host. */
internal fun discoverTmuxClient(runner: TmuxCommandRunner, executable: String): TmuxClientObservation {
    val script = """
        [ -n "${'$'}SSH_CONNECTION" ] && [ -d /proc/self ] || exit 2
        printf 'TERMINAL_SPIKE_CLIENTS\n'
        ${quotePosixShellArgument(executable)} list-clients -F '#{client_pid}|#{client_tty}|#{session_id}|#{pane_id}|#{pid}' 2>/dev/null |
        head -n 128 |
        while IFS='|' read -r client_pid client_tty session_id pane_id server_pid; do
            case "${'$'}client_pid" in ''|*[!0-9]*) continue;; esac
            [ -r "/proc/${'$'}client_pid/environ" ] || continue
            if tr '\000' '\n' < "/proc/${'$'}client_pid/environ" 2>/dev/null | grep -Fxq -- "SSH_CONNECTION=${'$'}SSH_CONNECTION"; then
                printf '%s|%s|%s|%s\n' "${'$'}client_tty" "${'$'}session_id" "${'$'}pane_id" "${'$'}server_pid"
            fi
        done
    """.trimIndent()
    val output = runCatching {
        runner.run("/bin/sh -c ${quotePosixShellArgument(script)}")
    }.getOrNull() ?: return TmuxClientObservation.Unavailable
    if (output.exitStatus != 0) return TmuxClientObservation.Unavailable
    return parseTmuxClientObservation(output.stdout.toString(Charsets.UTF_8))
}

internal fun parseTmuxClientObservation(output: String): TmuxClientObservation {
    val lines = output.lineSequence().filter(String::isNotBlank).toList()
    if (lines.firstOrNull() != "TERMINAL_SPIKE_CLIENTS") return TmuxClientObservation.Unavailable
    if (lines.size == 1) return TmuxClientObservation.Detached
    // Nested clients or more than one PTY on the transport are ambiguous; never guess.
    if (lines.size != 2) return TmuxClientObservation.Unavailable
    val fields = lines[1].split('|')
    if (fields.size != 4) return TmuxClientObservation.Unavailable
    val tty = fields[0].takeIf { it.matches(Regex("/dev/[A-Za-z0-9/_-]{1,120}")) }
        ?: return TmuxClientObservation.Unavailable
    val session = fields[1].takeIf(String::isTmuxSessionId)
        ?: return TmuxClientObservation.Unavailable
    val pane = fields[2].takeIf { it.matches(Regex("%[0-9]+")) }
        ?: return TmuxClientObservation.Unavailable
    val server = fields[3].toLongOrNull()?.takeIf { it > 0 }
        ?: return TmuxClientObservation.Unavailable
    return TmuxClientObservation.Attached(TmuxClientIdentity(tty, session, pane, server))
}
