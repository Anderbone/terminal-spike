package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReliabilityResolutionTest {
    @Test
    fun nullHostOverridesInheritGlobalKeepaliveReconnectAndAttemptBound() {
        val resolved = resolveRemoteReliability(30, true, 7, null, null)

        assertEquals(30, resolved.keepaliveIntervalSeconds)
        assertTrue(resolved.reconnectEnabled)
        assertEquals(7, resolved.reconnectMaxAttempts)
    }

    @Test
    fun hostCanTurnKeepaliveAndReconnectOffEvenWhenGlobalsAreEnabled() {
        val resolved = resolveRemoteReliability(
            globalKeepaliveIntervalSeconds = 30,
            globalReconnectEnabled = true,
            globalReconnectMaxAttempts = 5,
            hostKeepaliveIntervalSeconds = 0,
            hostReconnectPolicy = ReconnectPolicy.DISABLED,
        )

        assertEquals(0, resolved.keepaliveIntervalSeconds)
        assertFalse(resolved.reconnectEnabled)
    }

    @Test
    fun hostCanEnableReconnectAndChooseKeepaliveWhenGlobalsAreOff() {
        val resolved = resolveRemoteReliability(
            globalKeepaliveIntervalSeconds = 0,
            globalReconnectEnabled = false,
            globalReconnectMaxAttempts = 3,
            hostKeepaliveIntervalSeconds = 60,
            hostReconnectPolicy = ReconnectPolicy.AUTOMATIC,
        )

        assertEquals(60, resolved.keepaliveIntervalSeconds)
        assertTrue(resolved.reconnectEnabled)
        assertEquals(3, resolved.reconnectMaxAttempts)
    }
}
