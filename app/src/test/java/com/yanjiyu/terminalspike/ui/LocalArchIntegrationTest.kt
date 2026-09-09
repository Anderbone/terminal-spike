package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.SessionNotificationState
import com.yanjiyu.terminalspike.withLocalRuntime
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.SshSessionSnapshot
import com.yanjiyu.terminalspike.localarch.LocalRuntimeState
import com.yanjiyu.terminalspike.localarch.LocalSessionSnapshot
import org.junit.Assert.*
import org.junit.Test

class LocalArchIntegrationTest {
    private val local = LocalSessionSnapshot(-1, "Local Arch 1", ConnectionState.Connected)
    private val remote = SshSessionSnapshot(1, "Remote", ConnectionState.Connected)

    @Test fun remoteUpdatesRetainTheActiveLocalTabAndItsDistinctIdentity() {
        val state = TerminalSpikeUiState().withRemoteSessionSnapshots(listOf(remote))
            .withLocalSessionSnapshots(listOf(local), -1)
            .withRemoteSessionSnapshots(listOf(remote.copy(connectionState = ConnectionState.Disconnected)))
        assertEquals(-1L, state.activeSessionId)
        assertTrue(state.activeSession.isLocalArch)
        assertFalse(state.activeSession.isLocalTerminal)
        assertEquals(setOf(0L, -1L, 1L), state.sessions.map { it.id }.toSet())
        assertNull(state.activeSession.sourceProfileId)
        assertNull(state.activeSession.recentSessionId)
    }

    @Test fun localClosureKeepsRemoteTabsAndDoesNotRetargetToAMissingController() {
        val state = TerminalSpikeUiState().withRemoteSessionSnapshots(listOf(remote))
            .withLocalSessionSnapshots(listOf(local), -1)
            .withLocalSessionSnapshots(emptyList())
        assertEquals(1L, state.activeSessionId)
        assertEquals(setOf(0L, 1L), state.sessions.map { it.id }.toSet())
    }

    @Test fun localProcessesAndInstallationKeepServiceAliveWhenRemoteSessionsEnd() {
        val idle = SessionNotificationState(0, 0)
        val running = idle.withLocalRuntime(LocalRuntimeState(listOf(local)))
        assertTrue(running.requiresForegroundService)
        assertEquals(1, running.connectedSessionCount)
        assertTrue(idle.withLocalRuntime(LocalRuntimeState(installationActive = true)).requiresForegroundService)
        assertTrue(idle.withLocalRuntime(LocalRuntimeState(loginActive = true)).requiresForegroundService)
        assertEquals(idle, idle.withLocalRuntime(LocalRuntimeState(listOf(local.copy(connectionState = ConnectionState.Disconnected)))))
        assertEquals(3, SessionNotificationState(2, 2).withLocalRuntime(LocalRuntimeState(listOf(local))).activeSessionCount)
    }
}
