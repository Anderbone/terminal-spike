package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.terminal.TerminalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun createTerminalBuildFeature(
    scope: CoroutineScope,
    controller: TerminalController,
): TerminalBuildFeature = DisabledTerminalBuildFeature

private data object DisabledTerminalBuildFeature : TerminalBuildFeature {
    override val keepScreenOn: StateFlow<Boolean> = MutableStateFlow(false)

    override fun stop() = Unit

    override fun onAppVisible(visible: Boolean) = Unit
}

internal fun initialTerminalTabs(): List<SessionTabUi> = listOf(
    SessionTabUi(
        id = LOCAL_TERMINAL_SESSION_ID,
        title = "",
        connectionState = ConnectionState.Disconnected,
        isLocalTerminal = true,
    ),
)
