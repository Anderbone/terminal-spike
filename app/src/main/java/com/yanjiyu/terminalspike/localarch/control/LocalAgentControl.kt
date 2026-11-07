package com.yanjiyu.terminalspike.localarch.control

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Abstract Unix socket: no TCP listener, Android component export, or remote-shell capability. */
internal class LocalAgentControl(
    private val scope: CoroutineScope,
    private val handler: suspend (String) -> String,
) : Closeable {
    private val enabledState = MutableStateFlow(false)
    val enabled = enabledState.asStateFlow()
    private val closed = AtomicBoolean()
    private var server: LocalServerSocket? = null
    private var job: Job? = null
    private val address = "terminal-spike-${Process.myUid()}-${UUID.randomUUID()}"

    fun setEnabled(value: Boolean) { enabledState.value = value }

    /** The per-process address is refreshed when a local shell is opened. Not an auth credential. */
    @Synchronized
    fun connectionInfo(): String {
        check(!closed.get())
        if (server == null) {
            val listener = LocalServerSocket(address)
            server = listener
            job = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && !closed.get()) {
                        val client = listener.accept()
                        serve(client)
                    }
                } catch (_: IOException) {
                    // Process lifetime only; no payload or socket/peer data enters logs.
                } finally {
                    listener.close()
                }
            }
        }
        return buildJsonObject { put("api", 1); put("abstractSocket", address) }.toString()
    }

    private suspend fun serve(client: LocalSocket) {
        // Close also interrupts a blocked peer read/write. One client at a time bounds resources.
        val deadline = scope.launch(Dispatchers.IO) { delay(15_000); runCatching { client.close() } }
        try {
            client.use {
                if (it.peerCredentials.uid != Process.myUid()) return
                it.soTimeout = 5_000
                val response = try {
                    val request = readControlFrame(it.inputStream)
                    withTimeout(10_000) { handler(request) }
                } catch (failure: ControlFailure) {
                    controlError(failure.code, requireNotNull(failure.message)).toString()
                }
                val bytes = response.toByteArray(Charsets.UTF_8)
                val bounded = if (bytes.size <= CONTROL_MAX_RESPONSE_BYTES) bytes else {
                    controlError("RESPONSE_TOO_LARGE", "Response exceeds 1 MiB. Request an individual record.").toString().toByteArray()
                }
                it.outputStream.write(bounded)
                it.outputStream.write(10)
                it.outputStream.flush()
            }
        } catch (_: Exception) {
            // A timed-out or disconnected client must not kill the accept loop.
        } finally {
            deadline.cancel()
            runCatching { client.close() }
        }
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        enabledState.value = false
        runCatching { server?.close() }
        job?.cancel()
    }
}
