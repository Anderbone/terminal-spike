package com.yanjiyu.terminalspike.connection

import android.content.ServiceConnection
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.connection.mosh.AndroidMoshExtensionClient
import com.yanjiyu.terminalspike.connection.mosh.MoshClientResult
import com.yanjiyu.terminalspike.connection.mosh.MoshDiscoveryDecision
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionContract
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionPlatform
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.mosh.api.IMoshCallback
import com.yanjiyu.terminalspike.mosh.api.IMoshPlugin
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled Binder seam proving the existing API hint reaches the exact active extension UUID. */
@RunWith(AndroidJUnit4::class)
class MoshNetworkHintInstrumentedTest {
    @Test
    fun activeSessionNetworkHintReachesPluginBinder() = runBlocking {
        val plugin = HintPlugin()
        val client = AndroidMoshExtensionClient(HintPlatform(plugin), Dispatchers.Default, 1_000)
        val sessionId = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val keyPipe = ParcelFileDescriptor.createPipe()
        val request = MoshSessionRequest(
            modelVersion = MoshApi.MODEL_VERSION,
            sessionId = sessionId.toString(),
            serverAddress = byteArrayOf(192.toByte(), 0, 2, 10),
            addressFamily = MoshAddressFamily.IPV4,
            udpPort = 60_001,
            moshKeyRead = keyPipe[0],
            initialColumns = 80,
            initialRows = 24,
            locale = "C.UTF-8",
            optionFlags = 0,
        )
        val started = client.startSession(request)
        assertTrue(started is MoshClientResult.Success)
        val hint = MoshNetworkHint(
            modelVersion = MoshApi.MODEL_VERSION,
            connectivityGeneration = 7,
            addressFamily = MoshAddressFamily.IPV6,
            isMetered = true,
        )

        assertTrue(client.updateNetworkHint(sessionId, hint) is MoshClientResult.Success)

        assertEquals(sessionId.toString(), plugin.hintSessionId)
        assertEquals(7L, plugin.hint?.connectivityGeneration)
        assertEquals(MoshAddressFamily.IPV6, plugin.hint?.addressFamily)
        assertEquals(true, plugin.hint?.isMetered)
        keyPipe[1].close()
        (started as MoshClientResult.Success).value.terminalInputWrite.close()
        started.value.terminalOutputRead.close()
        client.shutdown()
        plugin.close()
    }
}

private class HintPlatform(
    private val plugin: HintPlugin,
) : MoshExtensionPlatform {
    override fun discover(): MoshDiscoveryDecision =
        MoshDiscoveryDecision.Trusted(MoshExtensionVersion(1, "1.0"))

    override fun bind(connection: ServiceConnection): Boolean {
        connection.onServiceConnected(MoshExtensionContract.component, plugin.asBinder())
        return true
    }

    override fun unbind(connection: ServiceConnection) = Unit
}

private class HintPlugin : IMoshPlugin.Stub() {
    var hintSessionId: String? = null
    var hint: MoshNetworkHint? = null
    private val descriptors = mutableListOf<ParcelFileDescriptor>()

    override fun getApiVersion(): Int = MoshApi.PROTOCOL_VERSION

    override fun getCapabilities() = MoshCapabilities(
        modelVersion = MoshApi.MODEL_VERSION,
        minimumApiVersion = 1,
        maximumApiVersion = 1,
        capabilityFlags = MoshCapability.IPV4 or MoshCapability.IPV6 or
            MoshCapability.MULTIPLE_SESSIONS,
        maximumConcurrentSessions = 4,
    )

    override fun startSession(request: MoshSessionRequest): MoshSessionHandle {
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        descriptors += input[0]
        descriptors += output[1]
        return MoshSessionHandle(
            modelVersion = MoshApi.MODEL_VERSION,
            sessionId = request.sessionId,
            terminalInputWrite = input[1],
            terminalOutputRead = output[0],
            initialState = com.yanjiyu.terminalspike.mosh.api.MoshSessionState.CONNECTING,
            negotiatedCapabilityFlags = MoshCapability.IPV4,
        )
    }

    override fun resizeSession(sessionId: String, columns: Int, rows: Int) = Unit

    override fun updateNetworkHint(sessionId: String, hint: MoshNetworkHint) {
        hintSessionId = sessionId
        this.hint = hint
    }

    override fun stopSession(sessionId: String, reason: Int) = Unit

    override fun registerCallback(callback: IMoshCallback) = Unit

    override fun unregisterCallback(callback: IMoshCallback) = Unit

    fun close() {
        descriptors.forEach { runCatching { it.close() } }
    }
}
