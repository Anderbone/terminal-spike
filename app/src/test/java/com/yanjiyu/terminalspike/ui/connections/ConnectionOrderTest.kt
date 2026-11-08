package com.yanjiyu.terminalspike.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionOrderTest {
    @Test fun savedOrderSurvivesNewAndDeletedConnections() {
        assertEquals(
            listOf("host:b", "local-arch", "host:new"),
            orderedConnectionIds(listOf("local-arch", "host:b", "host:new"),
                listOf("host:deleted", "host:b", "host:b", "local-arch")),
        )
    }

    @Test fun localAndRemoteConnectionsCanMoveInBothDirections() {
        val initial = listOf("local-arch", "host:a", "host:b")
        val moved = moveConnection(initial, "local-arch", "host:b")
        assertEquals(listOf("host:a", "host:b", "local-arch"), moved)
        assertEquals(initial, moveConnection(moved, "local-arch", "host:a"))
        assertEquals(initial, moveConnection(initial, "deleted", "host:a"))
    }
}
