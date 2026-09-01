package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.TmuxPaneCapture
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.io.ByteArrayOutputStream

/** Immutable, renderer-ready history for one exact tmux pane. */
internal data class TmuxLocalHistorySnapshot(
    val sessionId: String,
    val paneId: String,
    val lines: List<TerminalLine>,
    val remoteHistoryRows: Int,
    val remoteMousePassthrough: Boolean,
    val mouseTrackingActive: Boolean = remoteMousePassthrough,
    val paneInMode: Boolean = false,
    val historyIncluded: Boolean,
    val authoritative: Boolean,
    val truncatedBefore: Boolean,
    /** False when terminal output arrived after this capture was requested. */
    val interactionMetadataFresh: Boolean = true,
)

/**
 * Converts tmux's SGR-preserving capture into the same line model as live terminal output. Tmux
 * joins only rows that it knows were wrapped; the dedicated one-row engine restores their physical
 * geometry and soft-wrap markers off the renderer path.
 */
internal fun parseTmuxHistoryCapture(capture: TmuxPaneCapture): TmuxLocalHistorySnapshot? {
    val remoteMousePassthrough = capture.mouseTrackingActive || capture.paneInMode
    if (capture.historyRows == 0 || !capture.historyIncluded) {
        return TmuxLocalHistorySnapshot(
            sessionId = capture.sessionId,
            paneId = capture.paneId,
            lines = emptyList(),
            remoteHistoryRows = capture.historyRows,
            remoteMousePassthrough = remoteMousePassthrough,
            mouseTrackingActive = capture.mouseTrackingActive,
            paneInMode = capture.paneInMode,
            historyIncluded = capture.historyIncluded,
            authoritative = capture.authoritative,
            truncatedBefore = capture.truncatedBefore,
        )
    }
    if (capture.content.isEmpty()) return null

    val terminalBytes = tmuxCaptureRowsAsTerminalBytes(capture.content) ?: return null
    val update = VtTerminalEngine(columns = capture.columns, rows = 1).accept(terminalBytes)
    val parsed = ArrayList<TerminalLine>(update.completedScrollback.size + update.screen.size)
    parsed.addAll(update.completedScrollback)
    parsed.addAll(update.screen)
    if (parsed.size != capture.historyRows) return null

    return TmuxLocalHistorySnapshot(
        sessionId = capture.sessionId,
        paneId = capture.paneId,
        lines = parsed,
        remoteHistoryRows = capture.historyRows,
        remoteMousePassthrough = remoteMousePassthrough,
        mouseTrackingActive = capture.mouseTrackingActive,
        paneInMode = capture.paneInMode,
        historyIncluded = true,
        authoritative = capture.authoritative,
        truncatedBefore = capture.truncatedBefore,
    )
}

/** Tmux writes LF-delimited logical lines and one final LF; a terminal LF also needs a CR. */
private fun tmuxCaptureRowsAsTerminalBytes(content: ByteArray): ByteArray? {
    var end = content.size
    if (end == 0 || content[end - 1] != NEWLINE) return null
    end -= 1
    if (end > 0 && content[end - 1] == CARRIAGE_RETURN) end -= 1

    val output = ByteArrayOutputStream(end + end / 16)
    var previous = -1
    for (index in 0 until end) {
        val value = content[index].toInt() and 0xff
        if (value == NEWLINE.toInt() && previous != CARRIAGE_RETURN.toInt()) {
            output.write(CARRIAGE_RETURN.toInt())
        }
        output.write(value)
        previous = value
    }
    return output.toByteArray()
}

private const val NEWLINE: Byte = 0x0a
private const val CARRIAGE_RETURN: Byte = 0x0d
