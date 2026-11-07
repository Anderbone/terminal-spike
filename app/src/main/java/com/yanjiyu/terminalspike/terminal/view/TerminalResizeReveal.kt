package com.yanjiyu.terminalspike.terminal.view

/** Wait for a short pause in the remote repaint, with a hard limit even for continuous output. */
internal class TerminalResizeReveal(
    reportedAtMillis: Long,
    private var outputRevision: Long,
) {
    private val deadlineMillis = reportedAtMillis + 500L
    private var lastOutputAtMillis: Long? = null
    private var completeFrame = false

    fun onOutputPublished(revision: Long, nowMillis: Long, synchronizedFrame: Boolean = false) {
        if (revision <= outputRevision) return
        outputRevision = revision
        lastOutputAtMillis = nowMillis
        completeFrame = synchronizedFrame
    }

    fun delayMillis(nowMillis: Long): Long {
        val quietDeadline = lastOutputAtMillis?.plus(if (completeFrame) 0L else 48L) ?: deadlineMillis
        return (minOf(deadlineMillis, quietDeadline) - nowMillis).coerceAtLeast(0L)
    }
}
