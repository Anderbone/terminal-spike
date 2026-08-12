package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest

sealed interface TerminalRemoteClipboardDecision {
    data object Ignore : TerminalRemoteClipboardDecision

    data class Ask(val request: TerminalRemoteClipboardRequest) : TerminalRemoteClipboardDecision
}

/** Remote clipboard writes are never silently granted. */
fun decideRemoteClipboardRequest(
    mode: RemoteClipboardMode,
    request: TerminalRemoteClipboardRequest,
): TerminalRemoteClipboardDecision = when (mode) {
    RemoteClipboardMode.DISABLED -> TerminalRemoteClipboardDecision.Ignore
    RemoteClipboardMode.ASK -> TerminalRemoteClipboardDecision.Ask(request)
}
