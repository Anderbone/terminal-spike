package com.yanjiyu.terminalspike.connection.mosh

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.mosh.api.IMoshCallback
import com.yanjiyu.terminalspike.mosh.api.IMoshPlugin
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshExtensionClientInstrumentedTest {
    @Test
    fun concurrentConnectUsesOneExplicitBindingAndOneCallback() = runBlocking {
        val service = FakeMoshPlugin()
        val platform = FakeMoshPlatform(service)
        val client = AndroidMoshExtensionClient(platform, Dispatchers.Default, 1_000)

        val states = List(12) { async { client.connect() } }.awaitAll()

        assertTrue(states.all { it is MoshExtensionStatus.Available })
        assertEquals(1, platform.bindCount.get())
        assertEquals(1, service.registerCount.get())
        client.shutdown()
        service.close()
    }

    @Test
    fun absentExtensionNeverAttemptsBinding() = runBlocking {
        val platform = FakeMoshPlatform(FakeMoshPlugin()).apply {
            discovery = MoshDiscoveryDecision.Absent
        }
        val client = AndroidMoshExtensionClient(platform, Dispatchers.Default, 1_000)

        assertEquals(MoshExtensionStatus.Absent, client.connect())
        assertEquals(0, platform.bindCount.get())
        client.shutdown()
        platform.service.close()
    }

    @Test
    fun startTransfersKeyDescriptorAndReturnsValidatedStreamingHandle() = runBlocking {
        val service = FakeMoshPlugin()
        val client = AndroidMoshExtensionClient(
            FakeMoshPlatform(service),
            Dispatchers.Default,
            1_000,
        )
        val sessionId = UUID.randomUUID()
        val keyPipe = ParcelFileDescriptor.createPipe()
        val request = request(sessionId, keyPipe[0])

        val result = client.startSession(request)

        assertTrue(result is MoshClientResult.Success)
        assertFalse(request.moshKeyRead.fileDescriptor.valid())
        val handle = (result as MoshClientResult.Success).value
        assertTrue(handle.terminalInputWrite.fileDescriptor.valid())
        assertTrue(handle.terminalOutputRead.fileDescriptor.valid())
        assertEquals(sessionId.toString(), handle.sessionId)
        keyPipe[1].close()
        handle.terminalInputWrite.close()
        handle.terminalOutputRead.close()
        client.stopSession(sessionId, MoshStopReason.USER_REQUESTED)
        client.shutdown()
        service.close()
    }

    @Test
    fun callbackDispatcherRoutesOnlyToCanonicalSessionKey() = runBlocking {
        val service = FakeMoshPlugin()
        val client = AndroidMoshExtensionClient(
            FakeMoshPlatform(service),
            Dispatchers.Default,
            1_000,
        )
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val firstEvents = client.sessionEvents(firstId)
        val secondEvents = client.sessionEvents(secondId)
        client.connect()

        service.emit(connectedEvent(firstId))

        assertEquals(firstId.toString(), withTimeout(1_000) { firstEvents.first() }.sessionId)
        assertNull(withTimeoutOrNull(100) { secondEvents.first() })
        client.shutdown()
        service.close()
    }

    @Test
    fun invalidHandleIsClosedAndNotPublished() = runBlocking {
        val service = FakeMoshPlugin().apply { returnMismatchedSession = true }
        val client = AndroidMoshExtensionClient(
            FakeMoshPlatform(service),
            Dispatchers.Default,
            1_000,
        )
        val keyPipe = ParcelFileDescriptor.createPipe()

        val result = client.startSession(request(UUID.randomUUID(), keyPipe[0]))

        assertEquals(
            MoshClientFailure.INVALID_EXTENSION_RESPONSE,
            (result as MoshClientResult.Failure).reason,
        )
        assertFalse(service.lastReturnedHandle!!.terminalInputWrite.fileDescriptor.valid())
        assertFalse(service.lastReturnedHandle!!.terminalOutputRead.fileDescriptor.valid())
        keyPipe[1].close()
        client.shutdown()
        service.close()
    }

    @Test
    fun serviceDisconnectFailsSessionsAndAttemptsOnlyOneRebind() = runBlocking {
        val service = FakeMoshPlugin()
        val platform = FakeMoshPlatform(service)
        val client = AndroidMoshExtensionClient(platform, Dispatchers.Default, 1_000)
        val sessionId = UUID.randomUUID()
        val events = client.sessionEvents(sessionId)
        val keyPipe = ParcelFileDescriptor.createPipe()
        assertTrue(client.startSession(request(sessionId, keyPipe[0])) is MoshClientResult.Success)
        platform.allowBind = false

        platform.disconnect()

        val failureEvent = withTimeout(1_000) { events.first { it.state == MoshSessionState.ERROR } }
        assertEquals(MoshErrorCode.EXTENSION_DIED, failureEvent.errorCode)
        withTimeout(1_000) {
            client.status.filterIsInstance<MoshExtensionStatus.Error>().first()
        }
        withTimeout(1_000) {
            while (platform.bindCount.get() < 2) delay(10)
        }
        assertEquals(2, platform.bindCount.get())
        assertTrue(
            client.resizeSession(sessionId, 100, 30) is MoshClientResult.Failure,
        )
        keyPipe[1].close()
        client.shutdown()
        service.close()
    }

    private fun request(
        sessionId: UUID,
        keyRead: ParcelFileDescriptor,
    ) = MoshSessionRequest(
        modelVersion = MoshApi.MODEL_VERSION,
        sessionId = sessionId.toString(),
        serverAddress = byteArrayOf(192.toByte(), 0, 2, 10),
        addressFamily = MoshAddressFamily.IPV4,
        udpPort = 60_001,
        moshKeyRead = keyRead,
        initialColumns = 100,
        initialRows = 30,
        locale = "en_GB.UTF-8",
        optionFlags = 0,
    )

    private fun connectedEvent(sessionId: UUID) = MoshSessionEvent(
        modelVersion = MoshApi.MODEL_VERSION,
        sessionId = sessionId.toString(),
        state = MoshSessionState.CONNECTED,
        disconnectReason = MoshDisconnectReason.NONE,
        errorCode = MoshErrorCode.NONE,
        redactedDetail = "Connected",
        connectivityGeneration = 0,
    )

    private class FakeMoshPlatform(
        val service: FakeMoshPlugin,
    ) : MoshExtensionPlatform {
        var discovery: MoshDiscoveryDecision = MoshDiscoveryDecision.Trusted(VERSION)
        var allowBind: Boolean = true
        val bindCount = AtomicInteger()
        private val connections = CopyOnWriteArrayList<ServiceConnection>()

        override fun discover(): MoshDiscoveryDecision = discovery

        override fun bind(connection: ServiceConnection): Boolean {
            bindCount.incrementAndGet()
            if (!allowBind) return false
            connections += connection
            connection.onServiceConnected(MoshExtensionContract.component, service.asBinder())
            return true
        }

        override fun unbind(connection: ServiceConnection) {
            connections -= connection
        }

        fun disconnect() {
            connections.toList().forEach { connection ->
                connection.onServiceDisconnected(MoshExtensionContract.component)
            }
        }
    }

    private class FakeMoshPlugin : IMoshPlugin.Stub() {
        val registerCount = AtomicInteger()
        var returnMismatchedSession: Boolean = false
        var lastReturnedHandle: MoshSessionHandle? = null
        private var callback: IMoshCallback? = null
        private val retainedDescriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()

        override fun getApiVersion(): Int = MoshApi.PROTOCOL_VERSION

        override fun getCapabilities() = MoshCapabilities(
            modelVersion = MoshApi.MODEL_VERSION,
            minimumApiVersion = 1,
            maximumApiVersion = 1,
            capabilityFlags = MoshCapability.IPV4 or MoshCapability.MULTIPLE_SESSIONS,
            maximumConcurrentSessions = 4,
        )

        override fun startSession(request: MoshSessionRequest): MoshSessionHandle {
            val input = ParcelFileDescriptor.createPipe()
            val output = ParcelFileDescriptor.createPipe()
            retainedDescriptors += input[0]
            retainedDescriptors += output[1]
            return MoshSessionHandle(
                modelVersion = MoshApi.MODEL_VERSION,
                sessionId = if (returnMismatchedSession) UUID.randomUUID().toString() else request.sessionId,
                terminalInputWrite = input[1],
                terminalOutputRead = output[0],
                initialState = MoshSessionState.CONNECTING,
                negotiatedCapabilityFlags = MoshCapability.IPV4,
            ).also { lastReturnedHandle = it }
        }

        override fun resizeSession(sessionId: String, columns: Int, rows: Int) = Unit

        override fun updateNetworkHint(sessionId: String, hint: MoshNetworkHint) = Unit

        override fun stopSession(sessionId: String, reason: Int) = Unit

        override fun registerCallback(callback: IMoshCallback) {
            registerCount.incrementAndGet()
            this.callback = callback
        }

        override fun unregisterCallback(callback: IMoshCallback) {
            if (this.callback === callback) this.callback = null
        }

        fun emit(event: MoshSessionEvent) {
            callback?.onSessionEvent(event)
        }

        fun close() {
            retainedDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            retainedDescriptors.clear()
            lastReturnedHandle?.let { handle ->
                runCatching { handle.terminalInputWrite.close() }
                runCatching { handle.terminalOutputRead.close() }
            }
        }
    }

    private companion object {
        val VERSION = MoshExtensionVersion(1, "1.0")
    }
}
