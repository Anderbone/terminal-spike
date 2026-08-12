package com.yanjiyu.terminalspike

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.SshSessionSnapshot
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionServiceReliabilityPolicyTest {
    @Test
    fun notificationFriendlyNameRejectsEndpointLikeOrPunctuatedValues() {
        assertEquals("Production API", "  Production   API  ".toNotificationFriendlyName())
        assertNull("user@example.com".toNotificationFriendlyName())
        assertNull("192.168.0.2".toNotificationFriendlyName())
        assertNull("host.example.com".toNotificationFriendlyName())
        assertNull("[2001:db8::1]".toNotificationFriendlyName())
    }

    @Test
    fun cpuLeaseIsHeldOnlyWhileEnabledAndActiveAndReleasesImmediately() {
        val lease = RecordingCpuAwakeLease()
        val policy = SessionCpuAwakePolicy(lease)

        policy.update(enabled = true, hasActiveSession = true)
        policy.update(enabled = true, hasActiveSession = true)
        assertEquals(1, lease.acquireCalls.get())

        policy.update(enabled = false, hasActiveSession = true)
        assertEquals(1, lease.releaseCalls.get())

        policy.update(enabled = true, hasActiveSession = false)
        assertEquals(1, lease.acquireCalls.get())

        policy.update(enabled = true, hasActiveSession = true)
        policy.close()
        assertEquals(2, lease.acquireCalls.get())
        assertEquals(2, lease.releaseCalls.get())
    }

    @Test
    fun transitionNotificationsAreOptInAndDoNotTreatIntentionalDisconnectAsUnexpected() {
        val detector = SessionTransitionDetector()
        detector.update(listOf(snapshot(ConnectionState.Connected)), false, false)

        assertEquals(
            emptyList<SessionTransitionNotification>(),
            detector.update(listOf(snapshot(ConnectionState.Disconnected)), true, true),
        )
    }

    @Test
    fun reconnectNotificationsSayFreshShellOnlyAfterReconnectCycle() {
        val detector = SessionTransitionDetector()
        detector.update(listOf(snapshot(ConnectionState.Connected)), true, true)

        assertEquals(
            listOf(SessionTransitionNotification.UNEXPECTED_DISCONNECT),
            detector.update(
                listOf(
                    snapshot(
                        ConnectionState.Reconnecting(
                            attempt = 1,
                            maxAttempts = 5,
                            waitingForNetwork = true,
                            retryDelayMillis = null,
                        ),
                    ),
                ),
                disconnectEnabled = true,
                reconnectEnabled = true,
            ),
        )
        assertEquals(
            listOf(SessionTransitionNotification.RECONNECTED_WITH_FRESH_SHELL),
            detector.update(listOf(snapshot(ConnectionState.Connected)), true, true),
        )
        assertEquals(
            emptyList<SessionTransitionNotification>(),
            detector.update(listOf(snapshot(ConnectionState.Connected)), true, true),
        )
    }

    private fun snapshot(state: ConnectionState) = SshSessionSnapshot(
        id = 7L,
        title = "Private session",
        connectionState = state,
    )
}

private class RecordingCpuAwakeLease : SessionCpuAwakeLease {
    val acquireCalls = AtomicInteger()
    val releaseCalls = AtomicInteger()

    override fun acquire() {
        acquireCalls.incrementAndGet()
    }

    override fun release() {
        releaseCalls.incrementAndGet()
    }
}
