package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.SshSessionSnapshot
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the ViewModel-to-application-owner projection boundary. */
class SshSessionRuntimeLifecycleTest {
    @Test
    fun repositorySnapshotsReplaceRemoteTabsByIdWithoutOwningRuntimeResources() {
        val original = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(
                    id = LOCAL_TERMINAL_SESSION_ID,
                    title = "Bench",
                    connectionState = ConnectionState.Disconnected,
                    isLocalTerminal = true,
                ),
                SessionTabUi(
                    id = 17L,
                    title = "Old",
                    connectionState = ConnectionState.Failed("redacted failure"),
                ),
            ),
            activeSessionId = 17L,
        )
        val replacement = snapshot(
            id = 17L,
            title = "Production",
            state = ConnectionState.Connecting,
            protocol = ConnectionProtocol.MOSH,
        )

        val projected = original.withRemoteSessionSnapshots(listOf(replacement))

        assertEquals(listOf(LOCAL_TERMINAL_SESSION_ID, 17L), projected.sessions.map { it.id })
        assertEquals(17L, projected.activeSessionId)
        assertEquals("Production", projected.activeSession.title)
        assertEquals(ConnectionProtocol.MOSH, projected.activeSession.protocol)
        assertEquals("terminal-profile", projected.activeSession.terminalProfileId)
        assertEquals("keyboard-profile", projected.activeSession.keyboardProfileId)
        assertTrue(projected.sessions.first().isLocalTerminal)
        assertFalse(projected.activeSession.isLocalTerminal)
    }

    @Test
    fun restoredRemoteSelectionWinsOverTheDefaultLocalTerminal() {
        val projected = TerminalSpikeUiState().withRemoteSessionSnapshots(
            remoteSessions = listOf(
                snapshot(11L, "One", ConnectionState.Connected),
                snapshot(12L, "Two", ConnectionState.Connected),
            ),
            preferredSessionId = 11L,
        )

        assertEquals(11L, projected.activeSessionId)
        assertEquals("One", projected.activeSession.title)
        assertFalse(projected.activeSession.isLocalTerminal)
    }

    @Test
    fun terminalEntryUsesRememberedRemoteTabInsteadOfTheBlankLocalTab() {
        val state = TerminalSpikeUiState().withRemoteSessionSnapshots(
            remoteSessions = listOf(
                snapshot(11L, "One", ConnectionState.Connected),
                snapshot(12L, "Two", ConnectionState.Connected),
            ),
        ).copy(activeSessionId = LOCAL_TERMINAL_SESSION_ID)

        assertEquals(11L, state.preferredTerminalEntrySessionId(lastActiveRemoteSessionId = 11L))
    }

    @Test
    fun terminalEntryUsesMostRecentAvailableRemoteWhenRememberedTabIsGone() {
        val state = TerminalSpikeUiState().withRemoteSessionSnapshots(
            remoteSessions = listOf(
                snapshot(11L, "One", ConnectionState.Connected),
                snapshot(12L, "Two", ConnectionState.Connected),
            ),
        ).copy(activeSessionId = LOCAL_TERMINAL_SESSION_ID)

        assertEquals(12L, state.preferredTerminalEntrySessionId(lastActiveRemoteSessionId = 99L))
        assertEquals(
            null,
            TerminalSpikeUiState().preferredTerminalEntrySessionId(
                lastActiveRemoteSessionId = 11L,
            ),
        )
    }

    @Test
    fun removingActiveRepositorySessionFallsBackToLastRemainingRemoteThenLocal() {
        val state = TerminalSpikeUiState().withRemoteSessionSnapshots(
            listOf(
                snapshot(11L, "One", ConnectionState.Connected),
                snapshot(12L, "Two", ConnectionState.Connected),
            ),
            preferredSessionId = 12L,
        )

        val oneRemaining = state.withRemoteSessionSnapshots(
            listOf(snapshot(11L, "One", ConnectionState.Connected)),
        )
        val localOnly = oneRemaining.withRemoteSessionSnapshots(emptyList())

        assertEquals(11L, oneRemaining.activeSessionId)
        assertEquals(LOCAL_TERMINAL_SESSION_ID, localOnly.activeSessionId)
        assertEquals(1, localOnly.sessions.size)
        assertTrue(localOnly.sessions.single().isLocalTerminal)
    }

    @Test
    fun remoteClipboardPromptSurvivesOnlyWhileItsTaggedSessionIsConnected() {
        val prompt = RemoteClipboardPromptUi(sessionId = 11L, requestId = 4L)
        val state = TerminalSpikeUiState(remoteClipboardPrompt = prompt)

        val connected = state.withRemoteSessionSnapshots(
            listOf(snapshot(11L, "One", ConnectionState.Connected)),
        )
        val reconnecting = connected.withRemoteSessionSnapshots(
            listOf(
                snapshot(
                    11L,
                    "One",
                    ConnectionState.Reconnecting(
                        attempt = 1,
                        maxAttempts = 3,
                        waitingForNetwork = false,
                        retryDelayMillis = 1_000L,
                    ),
                ),
            ),
        )

        assertEquals(prompt, connected.remoteClipboardPrompt)
        assertEquals(null, reconnecting.remoteClipboardPrompt)
    }

    private fun snapshot(
        id: Long,
        title: String,
        state: ConnectionState,
        protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    ) = SshSessionSnapshot(
        id = id,
        title = title,
        workspaceName = title,
        protocol = protocol,
        connectionState = state,
        terminalProfileId = "terminal-profile",
        keyboardProfileId = "keyboard-profile",
    )
}
