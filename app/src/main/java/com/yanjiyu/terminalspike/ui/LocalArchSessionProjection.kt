package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.localarch.LocalSessionSnapshot

internal fun TerminalSpikeUiState.withLocalSessionSnapshots(
    snapshots: List<LocalSessionSnapshot>,
    preferredSessionId: Long? = null,
): TerminalSpikeUiState {
    val tabs = sessions.filterNot { it.isLocalArch } + snapshots.map { snapshot ->
        SessionTabUi(
            id = snapshot.id, title = snapshot.title, connectionState = snapshot.connectionState,
            isLocalArch = true, workspaceName = "Local Arch Linux",
            terminalTitle = snapshot.terminalTitle, lastActivityAtEpochMillis = snapshot.lastActivityAtEpochMillis,
        )
    }
    val active = when {
        preferredSessionId != null && tabs.any { it.id == preferredSessionId } -> preferredSessionId
        tabs.any { it.id == activeSessionId } -> activeSessionId
        else -> tabs.last().id
    }
    return copy(sessions = tabs, activeSessionId = active)
}
