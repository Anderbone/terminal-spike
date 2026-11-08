package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.localarch.LocalSessionSnapshot

internal fun TerminalSpikeUiState.withLocalSessionSnapshots(
    snapshots: List<LocalSessionSnapshot>,
    preferredSessionId: Long? = null,
): TerminalSpikeUiState {
    val localTabs = snapshots.map { snapshot ->
        SessionTabUi(
            id = snapshot.id, title = snapshot.title, connectionState = snapshot.connectionState,
            isLocalArch = true, workspaceName = "Local Arch Linux",
            terminalTitle = snapshot.terminalTitle, lastActivityAtEpochMillis = snapshot.lastActivityAtEpochMillis,
        )
    }
    val tabs = (sessions.filterNot { it.isLocalArch } + localTabs).inExistingTabOrder(sessions)
    val active = when {
        preferredSessionId != null && tabs.any { it.id == preferredSessionId } -> preferredSessionId
        tabs.any { it.id == activeSessionId } -> activeSessionId
        else -> tabs.last().id
    }
    return copy(sessions = tabs, activeSessionId = active)
}

internal fun TerminalSpikeUiState.existingLocalArchSession(): SessionTabUi? {
    val candidates = sessions.filter {
        it.isLocalArch && (it.connectionState is com.yanjiyu.terminalspike.connection.ConnectionState.Connected ||
            it.connectionState is com.yanjiyu.terminalspike.connection.ConnectionState.Connecting)
    }
    return candidates.firstOrNull { it.id == activeSessionId }
        ?: candidates.maxByOrNull { it.lastActivityAtEpochMillis }
}
