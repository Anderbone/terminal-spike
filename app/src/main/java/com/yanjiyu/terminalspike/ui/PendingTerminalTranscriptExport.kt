package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.terminal.TerminalTranscriptSnapshot

/**
 * Activity-recreation-safe, process-memory-only ownership for one explicit transcript export.
 *
 * Transcript text is intentionally not placed in SavedState or a Bundle. The retained ViewModel
 * owns one bounded immutable snapshot while Android's document picker is open, and the result
 * callback consumes it exactly once.
 */
internal class PendingTerminalTranscriptExportOwner {
    private val lock = Any()
    private var pending: TerminalTranscriptSnapshot? = null

    fun offer(snapshot: TerminalTranscriptSnapshot): Boolean = synchronized(lock) {
        if (pending != null) return false
        pending = snapshot
        true
    }

    fun consume(): TerminalTranscriptSnapshot? = synchronized(lock) {
        pending.also { pending = null }
    }

    fun clear() {
        synchronized(lock) { pending = null }
    }
}
