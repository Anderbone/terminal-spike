package com.yanjiyu.terminalspike

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.connection.AndroidMoshConnectionExtension
import com.yanjiyu.terminalspike.connection.MoshSessionStartSpec
import com.yanjiyu.terminalspike.connection.MoshTransportStartResult
import com.yanjiyu.terminalspike.connection.mosh.MoshClientFailure
import com.yanjiyu.terminalspike.connection.mosh.MoshClientResult
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionClient
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.connection.mosh.MoshNegotiatedProtocol
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshConnectionPfdTest {
    @Test
    fun oneShotKeyPipeDeliversExactly22BytesThenClosesAndWipesSameArray() = runBlocking {
        val client = RecordingPfdMoshClient(returnTransport = false)
        val extension = AndroidMoshConnectionExtension(client)
        val key = VALID_KEY.encodeToByteArray()

        val result = extension.startSession(startSpec(), key)

        assertTrue(result is MoshTransportStartResult.Failure)
        assertArrayEquals(VALID_KEY.encodeToByteArray(), client.receivedKey)
        assertTrue(key.all { it == 0.toByte() })
        assertFalse(client.keyReadWasValidAfterRead)
        assertEquals(0, client.shutdownCalls)
    }

    @Test
    fun terminalPipeEndsMoveBytesAndTransportCloseReleasesReturnedDescriptors() = runBlocking {
        val client = RecordingPfdMoshClient(returnTransport = true)
        val extension = AndroidMoshConnectionExtension(client)
        val key = VALID_KEY.encodeToByteArray()

        val result = extension.startSession(startSpec(), key)
        val transport = (result as MoshTransportStartResult.Success).value
        transport.terminalInput.write(byteArrayOf(1, 2, 3))
        transport.terminalInput.flush()

        val inputBuffer = client.remoteTerminalInput!!.readExactly(3)
        assertArrayEquals(byteArrayOf(1, 2, 3), inputBuffer)

        client.remoteTerminalOutput!!.write(byteArrayOf(4, 5, 6))
        client.remoteTerminalOutput!!.flush()
        val outputBuffer = transport.terminalOutput.readExactly(3)
        assertArrayEquals(byteArrayOf(4, 5, 6), outputBuffer)

        transport.close()
        transport.close()

        assertFalse(client.returnedTerminalInputWrite!!.isValidSafely())
        assertFalse(client.returnedTerminalOutputRead!!.isValidSafely())
        assertTrue(key.all { it == 0.toByte() })
        assertEquals(0, client.shutdownCalls)
        client.closeRemoteEnds()
    }

    private fun startSpec() = MoshSessionStartSpec(
        sessionId = SESSION_ID,
        addressFamily = MoshAddressFamily.IPV4,
        addressBytes = byteArrayOf(192.toByte(), 0, 2, 10),
        udpPort = 60_001,
        initialColumns = 80,
        initialRows = 24,
        locale = "en_US.UTF-8",
        optionFlags = 0L,
    )

    private companion object {
        const val VALID_KEY = "4NeCCgvZFe2RnPgrcU1PQw"
        val SESSION_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
    }
}

private class RecordingPfdMoshClient(
    private val returnTransport: Boolean,
) : MoshExtensionClient {
    override val status = MutableStateFlow<MoshExtensionStatus>(availableMoshStatus())
    var receivedKey: ByteArray? = null
    var keyReadWasValidAfterRead = true
    var remoteTerminalInput: ParcelFileDescriptor.AutoCloseInputStream? = null
    var remoteTerminalOutput: ParcelFileDescriptor.AutoCloseOutputStream? = null
    var returnedTerminalInputWrite: ParcelFileDescriptor? = null
    var returnedTerminalOutputRead: ParcelFileDescriptor? = null
    var shutdownCalls = 0

    override suspend fun connect(): MoshExtensionStatus = status.value

    override suspend fun refresh(): MoshExtensionStatus = status.value

    override fun sessionEvents(sessionId: UUID): Flow<MoshSessionEvent> = emptyFlow()

    override suspend fun startSession(
        request: MoshSessionRequest,
    ): MoshClientResult<MoshSessionHandle> {
        ParcelFileDescriptor.AutoCloseInputStream(request.moshKeyRead).use { input ->
            receivedKey = input.readBytes()
        }
        keyReadWasValidAfterRead = request.moshKeyRead.isValidSafely()
        if (!returnTransport) return MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)

        val terminalInputPipe = ParcelFileDescriptor.createPipe()
        val terminalOutputPipe = ParcelFileDescriptor.createPipe()
        remoteTerminalInput = ParcelFileDescriptor.AutoCloseInputStream(terminalInputPipe[0])
        returnedTerminalInputWrite = terminalInputPipe[1]
        returnedTerminalOutputRead = terminalOutputPipe[0]
        remoteTerminalOutput = ParcelFileDescriptor.AutoCloseOutputStream(terminalOutputPipe[1])
        return MoshClientResult.Success(
            MoshSessionHandle(
                modelVersion = MoshApi.MODEL_VERSION,
                sessionId = request.sessionId,
                terminalInputWrite = terminalInputPipe[1],
                terminalOutputRead = terminalOutputPipe[0],
                initialState = MoshSessionState.CONNECTED,
                negotiatedCapabilityFlags = MoshCapability.IPV4,
            ),
        )
    }

    override suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit> = MoshClientResult.Success(Unit)

    override suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit> = MoshClientResult.Success(Unit)

    override suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit> = MoshClientResult.Success(Unit)

    override suspend fun shutdown() {
        shutdownCalls += 1
    }

    fun closeRemoteEnds() {
        runCatching { remoteTerminalInput?.close() }
        runCatching { remoteTerminalOutput?.close() }
    }
}

private fun availableMoshStatus(): MoshExtensionStatus.Available = MoshExtensionStatus.Available(
    version = MoshExtensionVersion(1, "1.0"),
    protocol = MoshNegotiatedProtocol(
        extensionApiVersion = 1,
        negotiatedApiVersion = 1,
        capabilityFlags = MoshCapability.IPV4,
        maximumConcurrentSessions = 1,
    ),
)

private fun java.io.InputStream.readExactly(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(result, offset, size - offset)
        check(count >= 0) { "Pipe closed before $size bytes were read." }
        offset += count
    }
    return result
}

private fun ParcelFileDescriptor.isValidSafely(): Boolean =
    runCatching { fileDescriptor.valid() }.getOrDefault(false)
