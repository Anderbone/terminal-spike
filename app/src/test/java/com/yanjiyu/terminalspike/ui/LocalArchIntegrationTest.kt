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

    @Test fun connectionEntryReusesActiveLocalOrMostRecentlyUsedLiveLocal() {
        val second = local.copy(id = -2, lastActivityAtEpochMillis = 20)
        val state = TerminalSpikeUiState().withLocalSessionSnapshots(listOf(local, second), local.id)
            .withRemoteSessionSnapshots(listOf(remote))
        assertEquals(local.id, state.existingLocalArchSession()?.id)
        assertEquals(second.id, state.copy(activeSessionId = remote.id).existingLocalArchSession()?.id)
        val ended = state.withLocalSessionSnapshots(listOf(
            local.copy(connectionState = ConnectionState.Disconnected),
            second.copy(connectionState = ConnectionState.Failed("ended")),
        ))
        assertNull(ended.existingLocalArchSession())
        val starting = ended.withLocalSessionSnapshots(listOf(local.copy(connectionState = ConnectionState.Connecting)))
        assertEquals(local.id, starting.existingLocalArchSession()?.id)
    }

    @Test fun alternatingRuntimeUpdatesKeepTabsInOpeningOrder() {
        for (localFirst in listOf(true, false)) {
            var state = if (localFirst) {
                TerminalSpikeUiState().withLocalSessionSnapshots(listOf(local), local.id)
                    .withRemoteSessionSnapshots(listOf(remote), remote.id)
            } else {
                TerminalSpikeUiState().withRemoteSessionSnapshots(listOf(remote), remote.id)
                    .withLocalSessionSnapshots(listOf(local), local.id)
            }
            val expected = if (localFirst) listOf(0L, -1L, 1L) else listOf(0L, 1L, -1L)
            assertEquals(expected, state.sessions.map { it.id })
            repeat(3) { iteration ->
                // Selecting a tab must also survive asynchronous title/activity refreshes.
                state = state.copy(activeSessionId = local.id).withRemoteSessionSnapshots(
                    listOf(remote.copy(terminalTitle = "Remote $iteration")),
                )
                assertEquals(expected, state.sessions.map { it.id })
                assertEquals(local.id, state.activeSessionId)
                state = state.copy(activeSessionId = remote.id).withLocalSessionSnapshots(
                    listOf(local.copy(terminalTitle = "root $iteration", lastActivityAtEpochMillis = iteration.toLong())),
                )
                assertEquals(expected, state.sessions.map { it.id })
                assertEquals(remote.id, state.activeSessionId)
                assertEquals("root $iteration", state.sessions.single { it.id == local.id }.terminalTitle)
                assertEquals("Remote $iteration", state.activeSession.terminalTitle)
            }
        }
    }

    @Test fun newTabsAppendAndClosureOrReconnectKeepsSurvivorsInPlace() {
        val localTwo = local.copy(id = -2, title = "Local Arch 2")
        val remoteTwo = remote.copy(id = 2, title = "Remote 2")
        val state = TerminalSpikeUiState().withLocalSessionSnapshots(listOf(local))
            .withRemoteSessionSnapshots(listOf(remote))
            .withLocalSessionSnapshots(listOf(local, localTwo))
            .withRemoteSessionSnapshots(listOf(remote, remoteTwo), remote.id)
        assertEquals(listOf(0L, -1L, 1L, -2L, 2L), state.sessions.map { it.id })

        val reconnecting = state.withStartedSshSession(
            state.activeSession.copy(connectionState = ConnectionState.Connecting), remote.id,
        ).withRemoteSessionSnapshots(listOf(remote.copy(connectionState = ConnectionState.Connecting), remoteTwo))
        assertEquals(state.sessions.map { it.id }, reconnecting.sessions.map { it.id })
        assertEquals(ConnectionState.Connecting, reconnecting.activeSession.connectionState)

        val closed = reconnecting.withLocalSessionSnapshots(listOf(localTwo))
            .withRemoteSessionSnapshots(listOf(remoteTwo))
        assertEquals(listOf(0L, -2L, 2L), closed.sessions.map { it.id })
        assertEquals(remoteTwo.id, closed.activeSessionId)
    }

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
