package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.connection.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSpikeUiStateTest {
    @Test
    fun activeSshTabSelectsSshWorkspace() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isBenchmark = true),
                SessionTabUi(1, "dev@example", ConnectionState.Connected),
            ),
            activeSessionId = 1,
        )

        assertTrue(state.sshMode)
        assertTrue(state.connectionState is ConnectionState.Connected)
        assertTrue(state.canAddSshSession)
    }

    @Test
    fun fourSshTabsEnforceTheBound() {
        val state = TerminalSpikeUiState(
            sessions = listOf(
                SessionTabUi(0, "Bench", ConnectionState.Disconnected, isBenchmark = true),
                SessionTabUi(1, "one", ConnectionState.Connected),
                SessionTabUi(2, "two", ConnectionState.Connecting),
                SessionTabUi(3, "three", ConnectionState.Disconnected),
                SessionTabUi(4, "four", ConnectionState.Failed("failed")),
            ),
        )

        assertFalse(state.canAddSshSession)
    }
}
