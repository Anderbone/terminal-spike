package com.yanjiyu.terminalspike.ui.terminal

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.ui.SessionTabUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionActionsPolicyTest {
    @Test
    fun connectedRemoteCanDisconnectAndDuplicateButNotReconnect() {
        val actions = terminalSessionActionAvailability(
            session = session(ConnectionState.Connected),
            canAddSession = true,
        )

        assertFalse(actions.reconnect)
        assertTrue(actions.duplicate)
        assertTrue(actions.disconnect)
    }

    @Test
    fun terminalRemoteCanReconnectButCannotDisconnect() {
        val actions = terminalSessionActionAvailability(
            session = session(ConnectionState.Disconnected),
            canAddSession = false,
        )

        assertTrue(actions.reconnect)
        assertFalse(actions.duplicate)
        assertFalse(actions.disconnect)
    }

    @Test
    fun localTerminalNeverOffersRemoteTransportActions() {
        val actions = terminalSessionActionAvailability(
            session = session(ConnectionState.Connected, local = true),
            canAddSession = true,
        )

        assertFalse(actions.reconnect)
        assertFalse(actions.duplicate)
        assertFalse(actions.disconnect)
    }

    private fun session(state: ConnectionState, local: Boolean = false) = SessionTabUi(
        id = if (local) 0L else 7L,
        title = if (local) "Local" else "Prod",
        connectionState = state,
        isLocalTerminal = local,
    )
}
