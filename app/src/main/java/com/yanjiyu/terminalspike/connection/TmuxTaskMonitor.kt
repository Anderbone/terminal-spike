package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
import com.yanjiyu.terminalspike.terminal.TerminalTaskTracker

/** Bounded metadata only; never captures history to determine whether an inactive pane is working. */
internal class TmuxTaskMonitor(private val clockNanos: () -> Long = System::nanoTime) {
    private data class Pane(val sessionId: String, val paneId: String, val tracker: TerminalTaskTracker, var bell: Boolean)
    private val panes = linkedMapOf<String, Pane>()
    private var lastRefreshNanos = 0L
    private var refreshGeneration = 0L

    fun refresh(runner: TmuxCommandRunner, executable: String) {
        val generation = synchronized(this) {
            val now = clockNanos()
            if (lastRefreshNanos != 0L && now - lastRefreshNanos < 1_000_000_000L) return
            lastRefreshNanos = now
            ++refreshGeneration
        }
        val script = """
            ${quotePosixShellArgument(executable)} list-panes -a -F '#{session_id}|#{pane_id}|#{pid}|#{window_bell_flag}|#{pane_tty}|#{pane_title}' 2>/dev/null |
            head -n 256 |
            while IFS='|' read -r session_id pane_id server_pid bell pane_tty title; do
                case "${'$'}pane_tty" in /dev/*) ;; *) continue;; esac
                codex=0
                if ps -t "${'$'}pane_tty" -o comm=,pgid=,tpgid= 2>/dev/null |
                    awk '${'$'}1 == "codex" && ${'$'}2 == ${'$'}3 { found=1 } END { exit !found }'; then codex=1; fi
                printf '%s|%s|%s|%s|%s|%s\n' "${'$'}session_id" "${'$'}pane_id" "${'$'}server_pid" "${'$'}codex" "${'$'}bell" "${'$'}title"
            done
        """.trimIndent()
        val output = runCatching {
            runner.run("/bin/sh -c ${quotePosixShellArgument(script)}")
        }.getOrNull()
        if (output == null || output.exitStatus != 0) {
            synchronized(this) {
                if (generation == refreshGeneration) panes.values.forEach { it.tracker.invalidateObservation() }
            }
            return
        }
        val seen = mutableSetOf<String>()
        synchronized(this) {
            if (generation != refreshGeneration) return
            output.stdout.toString(Charsets.UTF_8).lineSequence().take(256).forEach { line ->
                val fields = line.split('|', limit = 6)
                if (fields.size != 6 || !fields[0].isTmuxSessionId() ||
                    !fields[1].matches(Regex("%[0-9]+")) || fields[2].toLongOrNull()?.let { it > 0 } != true) return@forEach
                val key = "${fields[0]}:${fields[1]}:${fields[2]}"
                seen += key
                val pane = panes.getOrPut(key) { Pane(fields[0], fields[1], TerminalTaskTracker(), false) }
                // A shell or another application must not inherit a Codex spinner from a stale title.
                if (fields[3] == "1") {
                    pane.tracker.observeTitle(fields[5])
                } else {
                    pane.tracker.observeTitle("")
                }
                val bell = fields[4] == "1"
                if (bell && !pane.bell) pane.tracker.attention()
                pane.bell = bell
            }
            panes.keys.retainAll(seen)
        }
    }

    @Synchronized
    fun paneStatus(paneId: String?): TerminalTaskStatus = panes.values
        .firstOrNull { it.paneId == paneId }?.tracker?.status ?: TerminalTaskStatus()

    @Synchronized
    fun sessionStatus(sessionId: String): TerminalTaskStatus = panes.values
        .filter { it.sessionId == sessionId }
        .fold(TerminalTaskStatus()) { status, pane -> status + pane.tracker.status }

    @Synchronized
    fun acknowledge(paneId: String?) {
        panes.values.firstOrNull { it.paneId == paneId }?.tracker?.acknowledge()
    }

    @Synchronized
    fun clear() {
        panes.clear()
        refreshGeneration += 1
        lastRefreshNanos = 0L
    }
}
