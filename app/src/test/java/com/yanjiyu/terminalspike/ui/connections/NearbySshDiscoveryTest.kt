package com.yanjiyu.terminalspike.ui.connections

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbySshDiscoveryTest {
    @Test
    fun resolvedServicePrefersHostnameAndRemovesDnsRootDot() {
        val endpoint = ResolvedNearbySshService(
            serviceName = "Build box",
            hostname = "build-box.local.",
            numericAddresses = listOf("192.168.1.50"),
            port = 2222,
        ).toNearbySshEndpoint()

        assertEquals(
            NearbySshEndpoint("Build box", "build-box.local", 2222),
            endpoint,
        )
    }

    @Test
    fun resolvedServiceFallsBackToIpv4AndRejectsUnusableEndpoint() {
        val endpoint = ResolvedNearbySshService(
            serviceName = "Pi",
            hostname = null,
            numericAddresses = listOf("fe80::1%wlan0", "2001:db8::1", "192.168.1.8"),
            port = 22,
        ).toNearbySshEndpoint()

        assertEquals(NearbySshEndpoint("Pi", "192.168.1.8", 22), endpoint)
        assertNull(
            ResolvedNearbySshService(
                serviceName = "Broken",
                hostname = null,
                numericAddresses = listOf("fe80::1%wlan0"),
                port = 0,
            ).toNearbySshEndpoint(),
        )
    }

    @Test
    fun prefillChangesOnlyFriendlyNameHostnameAndPort() {
        val initial = HostEditorDraft(
            displayName = "Old name",
            hostname = "old.example",
            port = "2200",
            username = "keep-me",
            authenticationMethod = HostAuthenticationMethod.PRIVATE_KEY,
            keyIdentityId = "10000000-0000-4000-8000-000000000002",
            isFavourite = true,
            group = "Work",
            startupCommand = "tmux attach",
        )
        val endpoint = NearbySshEndpoint("Discovered", "host.local", 2222)

        assertEquals(
            initial.copy(displayName = "Discovered", hostname = "host.local", port = "2222"),
            initial.prefillFromNearbySsh(endpoint),
        )
    }

    @Test
    fun legacySelectionResolvesOnlyAfterExplicitChoice() = runTest {
        val boundary = FakeNearbySshBoundary(NearbySshPickerMode.IN_APP)
        val controller = DefaultNearbySshDiscoveryController(
            boundary = boundary,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val candidate = NearbySshServiceCandidate("service-1", "Office server")

        controller.start()
        boundary.discoveryListener.onServiceFound(candidate)
        assertEquals(
            NearbySshDiscoveryState.Searching(NearbySshPickerMode.IN_APP, listOf(candidate)),
            controller.state.value,
        )
        assertNull(boundary.resolutionListener)

        controller.select(candidate.id)
        assertEquals(
            NearbySshDiscoveryState.Resolving(NearbySshPickerMode.IN_APP, "Office server"),
            controller.state.value,
        )
        boundary.requireResolutionListener().onResolved(
            ResolvedNearbySshService("Office server", "office.local.", emptyList(), 22),
        )

        assertEquals(
            NearbySshDiscoveryState.Selected(
                NearbySshEndpoint("Office server", "office.local", 22),
            ),
            controller.state.value,
        )
        assertTrue(boundary.stopDiscoveryCount > 0)
        controller.close()
    }

    @Test
    fun systemPickerResultResolvesImmediatelyAfterAndroidSelection() = runTest {
        val boundary = FakeNearbySshBoundary(NearbySshPickerMode.SYSTEM)
        val controller = DefaultNearbySshDiscoveryController(
            boundary = boundary,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val selectedByAndroid = NearbySshServiceCandidate("selected", "Home server")

        controller.start()
        boundary.discoveryListener.onServiceFound(selectedByAndroid)

        assertEquals(
            NearbySshDiscoveryState.Resolving(NearbySshPickerMode.SYSTEM, "Home server"),
            controller.state.value,
        )
        assertTrue(boundary.resolutionListener != null)
        controller.close()
    }

    @Test
    fun discoveryTimeoutStopsBoundaryAndReportsNoResult() = runTest {
        val boundary = FakeNearbySshBoundary(NearbySshPickerMode.IN_APP)
        val controller = DefaultNearbySshDiscoveryController(
            boundary = boundary,
            dispatcher = StandardTestDispatcher(testScheduler),
            timeoutMillis = 100,
        )

        controller.start()
        advanceTimeBy(101)
        runCurrent()

        assertEquals(
            NearbySshDiscoveryState.Unavailable(
                NearbySshPickerMode.IN_APP,
                NearbySshDiscoveryFailure.TIMED_OUT,
            ),
            controller.state.value,
        )
        assertTrue(boundary.stopDiscoveryCount > 0)
        controller.close()
    }
}

private class FakeNearbySshBoundary(
    override val pickerMode: NearbySshPickerMode,
) : NearbySshDiscoveryBoundary {
    lateinit var discoveryListener: NearbySshDiscoveryBoundary.DiscoveryListener
    var resolutionListener: NearbySshDiscoveryBoundary.ResolutionListener? = null
    var stopDiscoveryCount = 0

    override fun start(listener: NearbySshDiscoveryBoundary.DiscoveryListener) {
        discoveryListener = listener
    }

    override fun stopDiscovery() {
        stopDiscoveryCount += 1
    }

    override fun resolve(
        candidateId: String,
        listener: NearbySshDiscoveryBoundary.ResolutionListener,
    ) {
        resolutionListener = listener
    }

    override fun stopResolution() = Unit

    override fun close() = Unit

    fun requireResolutionListener(): NearbySshDiscoveryBoundary.ResolutionListener =
        requireNotNull(resolutionListener)
}
