package com.yanjiyu.terminalspike.ui.connections

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real framework service-info objects and main Looper; platform calls never browse the network. */
@RunWith(AndroidJUnit4::class)
class AndroidNearbySshDiscoveryBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var platform: RecordingPlatform
    private lateinit var boundary: AndroidNearbySshDiscoveryBoundary

    @Before
    fun createBoundary() = onMain {
        platform = RecordingPlatform()
        boundary = AndroidNearbySshDiscoveryBoundary(instrumentation.targetContext, platform = platform)
    }

    @After
    fun closeBoundary() {
        onMain { boundary.close() }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun backgroundDiscoveryEventsAreDeliveredOnMain() {
        val callbacks = RecordingCallbacks()
        val service = service()
        val discovery = onMain { boundary.start(callbacks); platform.discovery }
        inBackground { discovery.onServiceFound(service) }
        inBackground { discovery.onServiceLost(service) }
        inBackground { discovery.onDiscoveryStopped(SSH_DNS_SD_SERVICE_TYPE) }

        assertEquals(1, callbacks.found.size)
        assertEquals(listOf(callbacks.found.single().id), callbacks.lost.toList())
        assertEquals(1, callbacks.stopped)
        assertEquals(listOf(true, true, true), callbacks.mainThreads.toList())
    }

    @Test
    fun backgroundDiscoveryFailuresAreDeliveredOnMain() {
        val callbacks = RecordingCallbacks()
        val first = onMain { boundary.start(callbacks); platform.discovery }
        inBackground { first.onStartDiscoveryFailed(SSH_DNS_SD_SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR) }
        val second = onMain { boundary.start(callbacks); platform.discovery }
        inBackground { second.onStopDiscoveryFailed(SSH_DNS_SD_SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR) }

        assertEquals(listOf(NearbySshBoundaryFailure.START_FAILED, NearbySshBoundaryFailure.START_FAILED), callbacks.failures.toList())
        assertEquals(listOf(true, true), callbacks.mainThreads.toList())
    }

    @Test
    fun backgroundResolutionSuccessAndFailureAreDeliveredOnMain() {
        val callbacks = RecordingCallbacks()
        val id = discover(callbacks)
        val first = onMain { boundary.resolve(id, callbacks); platform.resolution }
        inBackground { first.onServiceResolved(service()) }
        val second = onMain { boundary.resolve(id, callbacks); platform.resolution }
        inBackground { second.onResolveFailed(service(), NsdManager.FAILURE_INTERNAL_ERROR) }

        assertEquals(listOf("192.0.2.8"), callbacks.resolved.single().numericAddresses)
        assertEquals(listOf(NearbySshBoundaryFailure.RESOLVE_FAILED), callbacks.failures.toList())
        assertEquals(listOf(true, true, true), callbacks.mainThreads.toList())
    }

    @Test
    fun retiredDiscoveryCannotOverwriteTheReplacementService() {
        val retired = RecordingCallbacks()
        val current = RecordingCallbacks()
        val old = onMain { boundary.start(retired); platform.discovery }
        val fresh = service(port = 2222)
        onMain {
            boundary.start(current)
            platform.discovery.onServiceFound(fresh)
            old.onServiceFound(service(port = 22))
            boundary.resolve(current.found.single().id, current)
        }

        assertEquals(2222, platform.resolveRequests.single().port)
        assertTrue(retired.found.isEmpty())
    }

    @Test
    fun retiredDiscoveryCannotRemoveTheReplacementCandidate() {
        val retired = RecordingCallbacks()
        val current = RecordingCallbacks()
        val old = onMain { boundary.start(retired); platform.discovery }
        onMain {
            boundary.start(current)
            platform.discovery.onServiceFound(service())
            old.onServiceLost(service())
            boundary.resolve(current.found.single().id, current)
        }

        assertEquals(1, platform.resolveRequests.size)
        assertTrue(current.failures.isEmpty())
        assertTrue(retired.lost.isEmpty())
    }

    @Test
    fun retiredDiscoveryStopAndFailuresDoNotReachEitherOwner() {
        val retired = RecordingCallbacks()
        val current = RecordingCallbacks()
        val old = onMain { boundary.start(retired); platform.discovery }
        onMain {
            boundary.start(current)
            old.onDiscoveryStopped(SSH_DNS_SD_SERVICE_TYPE)
            old.onStartDiscoveryFailed(SSH_DNS_SD_SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR)
            old.onStopDiscoveryFailed(SSH_DNS_SD_SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR)
            platform.discovery.onServiceFound(service())
        }

        assertEquals(0, retired.stopped)
        assertTrue(retired.failures.isEmpty())
        assertEquals(1, current.found.size)
        assertEquals(0, current.stopped)
        assertTrue(current.failures.isEmpty())
    }

    @Test
    fun replacedResolutionAndDuplicateCompletionStayInert() {
        val discovery = RecordingCallbacks()
        val id = discover(discovery)
        val retired = RecordingCallbacks()
        val current = RecordingCallbacks()
        onMain {
            boundary.resolve(id, retired)
            val old = platform.resolution
            boundary.resolve(id, current)
            old.onServiceResolved(service())
            old.onResolveFailed(service(), NsdManager.FAILURE_INTERNAL_ERROR)
            platform.resolution.onServiceResolved(service())
            platform.resolution.onResolveFailed(service(), NsdManager.FAILURE_INTERNAL_ERROR)
            platform.resolution.onServiceResolved(service(port = 22))
        }

        assertTrue(retired.resolved.isEmpty())
        assertTrue(retired.failures.isEmpty())
        assertEquals(1, current.resolved.size)
        assertTrue(current.failures.isEmpty())
        assertEquals(2222, current.resolved.single().port)
    }

    @Test
    fun closingBoundaryDiscardsAlreadyQueuedCallbacks() {
        val executor = QueueExecutor()
        val callbacks = RecordingCallbacks()
        useQueue(executor)
        onMain {
            boundary.start(callbacks)
            platform.discovery.onServiceFound(service())
            boundary.close()
            executor.drain()
            boundary.resolve("nearby-ssh-1", callbacks)
            boundary.start(callbacks)
        }

        assertTrue(callbacks.found.isEmpty())
        assertTrue(callbacks.failures.isEmpty())
        assertTrue(platform.resolveRequests.isEmpty())
        assertEquals(1, platform.discoveryCount)
    }

    @Test
    fun restartingDiscoveryDiscardsCallbacksAlreadyInTheExecutorQueue() {
        val executor = QueueExecutor()
        useQueue(executor)
        val retired = RecordingCallbacks()
        val current = RecordingCallbacks()
        onMain {
            boundary.start(retired)
            platform.discovery.onServiceFound(service(port = 22))
            boundary.start(current)
            platform.discovery.onServiceFound(service(port = 2222))
            executor.drain()
            boundary.resolve(current.found.single().id, current)
        }

        assertTrue(retired.found.isEmpty())
        assertEquals(1, current.found.size)
        assertEquals(2222, platform.resolveRequests.single().port)
    }

    @Test
    fun stoppingResolutionDiscardsQueuedCompletionAndAllowsAnotherResolution() {
        val executor = QueueExecutor()
        useQueue(executor)
        val callbacks = RecordingCallbacks()
        onMain {
            boundary.start(callbacks)
            platform.discovery.onServiceFound(service())
            executor.drain()
            boundary.resolve(callbacks.found.single().id, callbacks)
            platform.resolution.onServiceResolved(service())
            boundary.stopResolution()
            executor.drain()
        }
        assertTrue(callbacks.resolved.isEmpty())

        onMain {
            boundary.resolve(callbacks.found.single().id, callbacks)
            platform.resolution.onServiceResolved(service())
            executor.drain()
        }
        assertEquals(2222, callbacks.resolved.single().port)
    }

    @Test
    fun cacheEvictsOldCandidateIdsAndKeepsTheLatestThirtyTwoResolvable() {
        val callbacks = RecordingCallbacks()
        onMain {
            boundary.start(callbacks)
            repeat(64) { platform.discovery.onServiceFound(service(name = "Server $it")) }
            callbacks.found.take(32).forEach { boundary.resolve(it.id, callbacks) }
        }
        assertEquals(32, callbacks.failures.size)
        assertTrue(platform.resolveRequests.isEmpty())

        onMain { callbacks.found.takeLast(32).forEach { boundary.resolve(it.id, callbacks) } }
        assertEquals((32..63).map { "Server $it" }, platform.resolveRequests.map { it.serviceName })
    }

    @Test
    fun validDiscoveryUpdatesReuseTheirIdAndLostServicesCannotResolve() {
        val callbacks = RecordingCallbacks()
        onMain {
            boundary.start(callbacks)
            platform.discovery.onServiceFound(service(type = "_http._tcp."))
            platform.discovery.onServiceFound(service(port = 22))
            platform.discovery.onServiceFound(service(port = 2222))
        }
        assertEquals(2, callbacks.found.size)
        assertEquals(callbacks.found.first().id, callbacks.found.last().id)
        onMain {
            boundary.resolve(callbacks.found.singleId(), callbacks)
            platform.discovery.onServiceLost(service())
            boundary.resolve(callbacks.found.singleId(), callbacks)
        }
        assertEquals(2222, platform.resolveRequests.single().port)
        assertEquals(listOf(callbacks.found.singleId()), callbacks.lost.toList())
        assertEquals(listOf(NearbySshBoundaryFailure.RESOLVE_FAILED), callbacks.failures.toList())
    }

    @Test
    fun equivalentServiceTypeSpellingsShareTheSameCandidate() {
        val callbacks = RecordingCallbacks()
        onMain {
            boundary.start(callbacks)
            platform.discovery.onServiceFound(service(type = "_SSH._TCP."))
            platform.discovery.onServiceFound(service(type = "_ssh._tcp"))
        }
        assertEquals(2, callbacks.found.size)
        assertEquals(callbacks.found.first().id, callbacks.found.last().id)
        onMain { platform.discovery.onServiceLost(service(type = "_sSh._tCp.")) }
        assertEquals(listOf(callbacks.found.singleId()), callbacks.lost.toList())
    }

    @Test
    fun synchronousPlatformFailuresRetireTheirPartiallyStartedListeners() {
        val callbacks = RecordingCallbacks()
        onMain {
            platform.failDiscovery = true
            assertTrue(runCatching { boundary.start(callbacks) }.exceptionOrNull() is SecurityException)
            platform.discovery.onServiceFound(service())
        }
        assertTrue(callbacks.found.isEmpty())
        val id = onMain {
            platform.failDiscovery = false
            boundary.start(callbacks)
            platform.discovery.onServiceFound(service())
            callbacks.found.single().id
        }
        onMain {
            platform.failResolution = true
            assertTrue(runCatching { boundary.resolve(id, callbacks) }.exceptionOrNull() is SecurityException)
            platform.resolution.onServiceResolved(service())
            platform.resolution.onResolveFailed(service(), NsdManager.FAILURE_INTERNAL_ERROR)
        }
        assertTrue(callbacks.resolved.isEmpty())
        assertTrue(callbacks.failures.isEmpty())
    }

    private fun discover(callbacks: RecordingCallbacks): String = onMain {
        boundary.start(callbacks)
        platform.discovery.onServiceFound(service())
        callbacks.found.single().id
    }

    private fun useQueue(executor: QueueExecutor) = onMain {
        boundary.close()
        boundary = AndroidNearbySshDiscoveryBoundary(
            instrumentation.targetContext, platform = platform, callbackExecutor = executor,
        )
    }

    private fun inBackground(block: () -> Unit) {
        var result: Result<Unit>? = null
        val worker = Thread { result = runCatching(block) }.apply { start() }
        worker.join(5_000)
        assertFalse("Framework callback must not block", worker.isAlive)
        requireNotNull(result).getOrThrow()
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun List<NearbySshServiceCandidate>.singleId(): String = map { it.id }.distinct().single()

    @Suppress("DEPRECATION")
    private fun service(name: String = "Office", type: String = "_ssh._tcp.", port: Int = 2222) =
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            host = InetAddress.getByName("192.0.2.8")
            this.port = port
        }

    private class RecordingCallbacks : NearbySshDiscoveryBoundary.DiscoveryListener,
        NearbySshDiscoveryBoundary.ResolutionListener {
        val found = CopyOnWriteArrayList<NearbySshServiceCandidate>()
        val lost = CopyOnWriteArrayList<String>()
        val resolved = CopyOnWriteArrayList<ResolvedNearbySshService>()
        val failures = CopyOnWriteArrayList<NearbySshBoundaryFailure>()
        val mainThreads = CopyOnWriteArrayList<Boolean>()
        var stopped = 0

        private fun recordThread() { mainThreads += Looper.myLooper() == Looper.getMainLooper() }
        override fun onServiceFound(candidate: NearbySshServiceCandidate) { recordThread(); found += candidate }
        override fun onServiceLost(candidateId: String) { recordThread(); lost += candidateId }
        override fun onDiscoveryStopped() { recordThread(); stopped += 1 }
        override fun onResolved(service: ResolvedNearbySshService) { recordThread(); resolved += service }
        override fun onFailure(failure: NearbySshBoundaryFailure) { recordThread(); failures += failure }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class RecordingPlatform : NearbySshNsdPlatform {
        lateinit var discovery: NsdManager.DiscoveryListener
        lateinit var resolution: NsdManager.ResolveListener
        val resolveRequests = mutableListOf<NsdServiceInfo>()
        var discoveryCount = 0
        var failDiscovery = false
        var failResolution = false

        override fun discover(executor: Executor, listener: NsdManager.DiscoveryListener) {
            discovery = listener
            discoveryCount += 1
            if (failDiscovery) throw SecurityException("Discovery rejected")
        }
        override fun stopDiscovery(listener: NsdManager.DiscoveryListener) = Unit
        override fun resolve(service: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            resolution = listener
            resolveRequests += service
            if (failResolution) throw SecurityException("Resolution rejected")
        }
        override fun stopResolution(listener: NsdManager.ResolveListener) = Unit
    }
}
