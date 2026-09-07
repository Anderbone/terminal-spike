package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test
    fun android17RequestsOnlyForExplicitlyLocalEndpointsWhilePermissionIsMissing() {
        listOf(
            "localhost",
            "printer.local.",
            "10.1.2.3",
            "172.31.2.3",
            "192.168.4.5",
            "169.254.1.2",
            "127.0.0.1",
            "[::1]",
            "fd12::1",
            "fe80::1234%wlan0",
        ).forEach { endpoint ->
            assertTrue(
                endpoint,
                shouldRequestLocalNetworkPermission(37, permissionGranted = false, endpoint),
            )
        }
    }

    @Test
    fun publicOrUnresolvedEndpointsRemainUsableWithoutBroadLocalPermission() {
        listOf("example.com", "203.0.113.9", "2001:db8::1", "not-local.invalid").forEach {
            endpoint ->
            assertFalse(
                endpoint,
                shouldRequestLocalNetworkPermission(37, permissionGranted = false, endpoint),
            )
        }
        assertFalse(shouldRequestLocalNetworkPermission(36, permissionGranted = false, "10.0.0.1"))
        assertFalse(shouldRequestLocalNetworkPermission(37, permissionGranted = true, "10.0.0.1"))
    }

    @Test
    fun malformedNumericLookalikesAreNotClassifiedByDnsOnTheCallingThread() {
        listOf("192.168.1", "192.168.1.999", "10.example.com", "").forEach { endpoint ->
            assertFalse(isClearlyLocalNetworkEndpoint(endpoint))
        }
    }

    @Test
    fun hostnameResolutionClassifiesAnyPrivateAnswerWithoutRunningOnTheUiHelper() {
        assertTrue(
            resolvedEndpointIsLocalNetwork("build.example") {
                listOf("203.0.113.8", "192.168.1.42")
            } == true,
        )
        assertFalse(
            resolvedEndpointIsLocalNetwork("public.example") {
                listOf("203.0.113.8", "2001:db8::8")
            } == true,
        )
    }

    @Test
    fun failedHostnameResolutionDoesNotBecomeABroadPermissionRequest() {
        assertNull(
            resolvedEndpointIsLocalNetwork("offline.example") {
                throw java.net.UnknownHostException("offline")
            },
        )
    }

    @Test
    fun pendingActionSurvivesItsCallerAndGrantConsumesItExactlyOnce() {
        val owner = LocalNetworkPendingActionOwner()
        val expected = LocalNetworkActionEffect(clearConnectDialog = true)
        var starts = 0
        var rejectedSecretWipes = 0

        assertTrue(owner.stage(cancel = {}, proceed = { starts += 1; expected }))
        assertFalse(
            owner.stage(
                cancel = { rejectedSecretWipes += 1 },
                proceed = { error("Duplicate action must not run") },
            ),
        )
        assertTrue(owner.hasPendingAction())
        assertSame(expected, owner.resolve(granted = true))
        assertNull(owner.resolve(granted = true))
        assertFalse(owner.hasPendingAction())
        assertTrue(starts == 1)
        assertTrue(rejectedSecretWipes == 1)
    }

    @Test
    fun denialAndOwnerTeardownWipePendingActionsWithoutStartingThem() {
        val owner = LocalNetworkPendingActionOwner()
        var starts = 0
        var wipes = 0
        fun stage() = owner.stage(
            cancel = { wipes += 1 },
            proceed = { starts += 1; LocalNetworkActionEffect() },
        )

        assertTrue(stage())
        assertNull(owner.resolve(granted = false))
        assertTrue(stage())
        owner.cancel()
        owner.cancel()

        assertTrue(starts == 0)
        assertTrue(wipes == 2)
        assertFalse(owner.hasPendingAction())
    }
}
