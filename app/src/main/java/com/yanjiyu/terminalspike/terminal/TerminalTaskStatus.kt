package com.yanjiyu.terminalspike.terminal

/** Small session metadata, independent of terminal cells and audible notification preferences. */
data class TerminalTaskStatus(
    val running: Boolean = false,
    val finished: Boolean = false,
    val needsAttention: Boolean = false,
) {
    operator fun plus(other: TerminalTaskStatus) = TerminalTaskStatus(
        running || other.running, finished || other.finished, needsAttention || other.needsAttention,
    )

    fun acknowledged() = copy(finished = false, needsAttention = false)
    fun disconnected() = copy(running = false)
}

/**
 * Reads explicit terminal-title state changes, never traffic volume or elapsed silence. Codex's
 * default title rotates these braille characters while working and removes them when ready.
 * Finished means the observed work ended/returned to ready, not that a command succeeded.
 */
internal class TerminalTaskTracker {
    var status = TerminalTaskStatus()
        private set
    private var workingTitle: String? = null
    private var lastTitle: String? = null

    fun observeTitle(title: String?): TerminalTaskStatus {
        val bounded = title?.take(512)?.trim() ?: return status
        if (bounded == lastTitle) return status
        lastTitle = bounded
        val spinner = bounded.firstOrNull()?.let { it in CODEX_SPINNER } == true
        val explicitWorking = bounded.startsWith("Working ") && bounded.lastOrNull()?.let { it in CODEX_SPINNER } == true
        val titleBody = if (spinner) bounded.drop(1).trim() else bounded
        status = when {
            bounded.startsWith("🔔") || bounded.startsWith("Waiting for ") -> {
                workingTitle = null
                status.copy(running = false, needsAttention = true)
            }
            spinner || explicitWorking -> {
                workingTitle = if (explicitWorking) "Ready" else titleBody
                status.copy(running = true, finished = false)
            }
            workingTitle != null && (bounded == workingTitle || bounded == "Ready") -> {
                workingTitle = null
                status.copy(running = false, finished = true)
            }
            else -> {
                workingTitle = null
                status.copy(running = false)
            }
        }
        return status
    }

    fun attention(): TerminalTaskStatus {
        status = status.copy(needsAttention = true)
        return status
    }

    fun invalidateObservation(): TerminalTaskStatus {
        workingTitle = null
        lastTitle = null
        status = status.disconnected()
        return status
    }

    fun acknowledge(): TerminalTaskStatus {
        status = status.acknowledged()
        return status
    }

    companion object {
        private const val CODEX_SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    }
}
