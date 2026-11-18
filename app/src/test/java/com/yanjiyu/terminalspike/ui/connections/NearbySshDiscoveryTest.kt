package com.yanjiyu.terminalspike.ui.connections

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    @Test
    fun timedOutDiscoveryIgnoresEveryLateCallback() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            advanceTimeBy(101)
            runCurrent()
            val terminal = controller.state.value
            assertEquals(
                NearbySshDiscoveryState.Unavailable(NearbySshPickerMode.IN_APP, NearbySshDiscoveryFailure.TIMED_OUT),
                terminal,
            )

            boundary.discoveryListener.onDiscoveryStopped()
            assertEquals(terminal, controller.state.value)
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            assertEquals(terminal, controller.state.value)
            boundary.discoveryListener.onServiceLost(CANDIDATE.id)
            assertEquals(terminal, controller.state.value)
            boundary.discoveryListener.onFailure(NearbySshBoundaryFailure.PERMISSION_DENIED)
            assertEquals(terminal, controller.state.value)
        }
    }

    @Test
    fun timeoutResultsRemainStableAndCanStillBeSelected() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            advanceTimeBy(101)
            runCurrent()
            val results = NearbySshDiscoveryState.Results(listOf(CANDIDATE))
            assertEquals(results, controller.state.value)

            boundary.discoveryListener.onServiceFound(CANDIDATE.copy(id = "late", displayName = "Late server"))
            assertEquals(results, controller.state.value)
            boundary.discoveryListener.onServiceLost(CANDIDATE.id)
            assertEquals(results, controller.state.value)
            boundary.discoveryListener.onFailure(NearbySshBoundaryFailure.START_FAILED)
            assertEquals(results, controller.state.value)
            boundary.discoveryListener.onDiscoveryStopped()
            assertEquals(results, controller.state.value)

            controller.select(CANDIDATE.id)
            boundary.requireResolutionListener().onResolved(RESOLVED)
            assertEquals(SELECTED, controller.state.value)
        }
    }

    @Test
    fun failedDiscoveryDoesNotResolveItsOldCandidates() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            boundary.discoveryListener.onFailure(NearbySshBoundaryFailure.PERMISSION_DENIED)
            val failure = controller.state.value
            assertEquals(
                NearbySshDiscoveryState.Unavailable(NearbySshPickerMode.IN_APP, NearbySshDiscoveryFailure.PERMISSION_DENIED),
                failure,
            )

            controller.select(CANDIDATE.id)
            assertNull(boundary.resolutionListener)
            assertEquals(failure, controller.state.value)
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            boundary.discoveryListener.onDiscoveryStopped()
            assertEquals(failure, controller.state.value)
        }
    }

    @Test
    fun timedOutResolutionRejectsLateSuccessAndFailure() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val resolution = boundary.requireResolutionListener()
            advanceTimeBy(101)
            runCurrent()
            val timeout = controller.state.value
            assertEquals(
                NearbySshDiscoveryState.Unavailable(NearbySshPickerMode.IN_APP, NearbySshDiscoveryFailure.TIMED_OUT),
                timeout,
            )

            resolution.onResolved(RESOLVED)
            assertEquals(timeout, controller.state.value)
            resolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            assertEquals(timeout, controller.state.value)
        }
    }

    @Test
    fun successfulResolutionRejectsLateCallbacksAndRepeatedSelection() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val resolution = boundary.requireResolutionListener()
            resolution.onResolved(RESOLVED)
            assertEquals(SELECTED, controller.state.value)

            resolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            assertEquals(SELECTED, controller.state.value)
            resolution.onResolved(RESOLVED.copy(hostname = "different.local"))
            assertEquals(SELECTED, controller.state.value)
            controller.select(CANDIDATE.id)
            assertEquals(1, boundary.resolveCount)
            assertEquals(SELECTED, controller.state.value)
        }
    }

    @Test
    fun failedResolutionRejectsLateSuccessAndRepeatedSelection() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val resolution = boundary.requireResolutionListener()
            resolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            val failure = controller.state.value
            assertEquals(
                NearbySshDiscoveryState.Unavailable(NearbySshPickerMode.IN_APP, NearbySshDiscoveryFailure.RESOLVE_FAILED),
                failure,
            )

            resolution.onResolved(RESOLVED)
            assertEquals(failure, controller.state.value)
            controller.select(CANDIDATE.id)
            assertEquals(1, boundary.resolveCount)
            assertEquals(failure, controller.state.value)
        }
    }

    @Test
    fun stoppedDiscoveryRetainsSelectableResultsWithoutAcceptingNewCallbacks() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            boundary.discoveryListener.onDiscoveryStopped()
            val results = NearbySshDiscoveryState.Results(listOf(CANDIDATE))
            assertEquals(results, controller.state.value)

            boundary.discoveryListener.onServiceFound(CANDIDATE.copy(id = "late"))
            assertEquals(results, controller.state.value)
            boundary.discoveryListener.onServiceLost(CANDIDATE.id)
            assertEquals(results, controller.state.value)
            controller.select(CANDIDATE.id)
            boundary.requireResolutionListener().onResolved(RESOLVED)
            assertEquals(SELECTED, controller.state.value)
        }
    }

    @Test
    fun restartingDiscoveryStopsItsPreviousResolution() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val oldResolution = boundary.requireResolutionListener()
            val stoppedBefore = boundary.stopResolutionCount

            controller.start()
            assertEquals(stoppedBefore + 1, boundary.stopResolutionCount)
            val searching = NearbySshDiscoveryState.Searching(NearbySshPickerMode.IN_APP)
            assertEquals(searching, controller.state.value)
            oldResolution.onResolved(RESOLVED)
            oldResolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            assertEquals(searching, controller.state.value)
        }
    }

    @Test
    fun duplicateSelectionDoesNotReplaceAnInFlightResolution() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val resolution = boundary.requireResolutionListener()
            controller.select(CANDIDATE.id)
            assertEquals(1, boundary.resolveCount)
            resolution.onResolved(RESOLVED)
            assertEquals(SELECTED, controller.state.value)
        }
    }

    @Test
    fun cancelAndCloseKeepLateCallbacksInert() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            controller.select(CANDIDATE.id)
            val resolution = boundary.requireResolutionListener()
            controller.cancel()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            resolution.onResolved(RESOLVED)
            assertEquals(NearbySshDiscoveryState.Idle, controller.state.value)

            controller.close()
            controller.start()
            controller.select(CANDIDATE.id)
            resolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            assertEquals(NearbySshDiscoveryState.Idle, controller.state.value)
            assertEquals(1, boundary.resolveCount)
        }
    }

    @Test
    fun timeoutRetiresDiscoveryBeforeBoundaryStopCanDeliverMoreServices() = runTest {
        withDiscovery { boundary, controller ->
            controller.start()
            boundary.onStopDiscovery = { boundary.discoveryListener.onServiceFound(CANDIDATE) }
            advanceTimeBy(101)
            runCurrent()

            assertEquals(
                NearbySshDiscoveryState.Unavailable(NearbySshPickerMode.IN_APP, NearbySshDiscoveryFailure.TIMED_OUT),
                controller.state.value,
            )
            assertNull(boundary.resolutionListener)
        }
    }

    @Test
    fun dismissedSystemPickerRejectsItsLateSelection() = runTest {
        withDiscovery(NearbySshPickerMode.SYSTEM) { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onDiscoveryStopped()
            val dismissed = NearbySshDiscoveryState.Unavailable(
                NearbySshPickerMode.SYSTEM,
                NearbySshDiscoveryFailure.NO_SELECTION,
            )
            assertEquals(dismissed, controller.state.value)
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            assertEquals(dismissed, controller.state.value)
            assertNull(boundary.resolutionListener)
        }
    }

    @Test
    fun systemPickerAutomaticallyResolvesOnceAndKeepsItsSelectedEndpoint() = runTest {
        withDiscovery(NearbySshPickerMode.SYSTEM) { boundary, controller ->
            controller.start()
            boundary.discoveryListener.onServiceFound(CANDIDATE)
            assertEquals(1, boundary.resolveCount)
            val resolution = boundary.requireResolutionListener()
            resolution.onResolved(RESOLVED)
            assertEquals(SELECTED, controller.state.value)
            resolution.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            assertEquals(SELECTED, controller.state.value)
        }
    }

    private fun TestScope.withDiscovery(
        mode: NearbySshPickerMode = NearbySshPickerMode.IN_APP,
        test: (FakeNearbySshBoundary, DefaultNearbySshDiscoveryController) -> Unit,
    ) {
        val boundary = FakeNearbySshBoundary(mode)
        val controller = DefaultNearbySshDiscoveryController(
            boundary,
            StandardTestDispatcher(testScheduler),
            timeoutMillis = 100,
        )
        try {
            test(boundary, controller)
        } finally {
            controller.close()
        }
    }

    private companion object {
        val CANDIDATE = NearbySshServiceCandidate("office", "Office server")
        val RESOLVED = ResolvedNearbySshService("Office server", "office.local.", emptyList(), 22)
        val SELECTED = NearbySshDiscoveryState.Selected(NearbySshEndpoint("Office server", "office.local", 22))
    }
}

private class FakeNearbySshBoundary(
    override val pickerMode: NearbySshPickerMode,
) : NearbySshDiscoveryBoundary {
    lateinit var discoveryListener: NearbySshDiscoveryBoundary.DiscoveryListener
    var resolutionListener: NearbySshDiscoveryBoundary.ResolutionListener? = null
    var stopDiscoveryCount = 0
    var onStopDiscovery: (() -> Unit)? = null
    var stopResolutionCount = 0
    var resolveCount = 0

    override fun start(listener: NearbySshDiscoveryBoundary.DiscoveryListener) {
        discoveryListener = listener
    }

    override fun stopDiscovery() {
        stopDiscoveryCount += 1
        onStopDiscovery?.also { onStopDiscovery = null }?.invoke()
    }

    override fun resolve(
        candidateId: String,
        listener: NearbySshDiscoveryBoundary.ResolutionListener,
    ) {
        resolveCount += 1
        resolutionListener = listener
    }

    override fun stopResolution() {
        stopResolutionCount += 1
    }

    override fun close() = Unit

    fun requireResolutionListener(): NearbySshDiscoveryBoundary.ResolutionListener =
        requireNotNull(resolutionListener)
}
