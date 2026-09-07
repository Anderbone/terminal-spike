package com.yanjiyu.terminalspike.connection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real API 37 proof against the emulator's RFC1918 host gateway and a disposable OpenSSH server. */
@RunWith(AndroidJUnit4::class)
class LocalNetworkSocketRuntimeTest {
    @Test
    fun deniedPermissionBlocksRawTcpAndProductionSshBeforeProtocolTraffic() = runBlocking {
        val fixture = requireFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(
            "The denied phase requires ACCESS_LOCAL_NETWORK to be absent.",
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                PackageManager.PERMISSION_GRANTED,
        )

        val socketFailure = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(fixture.host, fixture.port), SOCKET_TIMEOUT_MILLIS)
                }
            }.exceptionOrNull()
        }
        assertTrue(
            "API 37 did not classify ${fixture.host} as permission-protected LAN traffic: " +
                socketFailure?.javaClass?.simpleName,
            socketFailure.isExpectedDeniedLanFailure(),
        )

        val knownHosts = context.cacheDir.resolve("api37-denied-lan-known-hosts").apply { delete() }
        val connection = JschSshConnection(
            KnownHostManager { knownHosts },
            fixture.sshConfig(),
        )
        val terminalState = CompletableDeferred<ConnectionState>()
        val connectJob = launch(Dispatchers.IO) {
            connection.connect(
                columns = 80,
                rows = 24,
                onBytes = { terminalState.completeExceptionally(
                    AssertionError("Denied LAN SSH produced protocol bytes."),
                ) },
                onState = { state ->
                    when (state) {
                        ConnectionState.Connected,
                        is ConnectionState.AwaitingApproval,
                        -> terminalState.completeExceptionally(
                            AssertionError("Denied LAN SSH reached protocol state $state."),
                        )
                        is ConnectionState.Failed -> terminalState.complete(state)
                        else -> Unit
                    }
                },
            )
        }
        try {
            assertTrue(withTimeout(CONNECT_TIMEOUT_MILLIS) { terminalState.await() } is ConnectionState.Failed)
        } finally {
            connection.close()
            connectJob.join()
            knownHosts.delete()
        }
    }

    @Test
    fun grantedPermissionCompletesProductionSshAndSftpAgainstTheSameLanServer() = runBlocking {
        val fixture = requireFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK),
        )

        val knownHosts = context.cacheDir.resolve("api37-granted-lan-known-hosts").apply { delete() }
        val connection = JschSshConnection(KnownHostManager { knownHosts }, fixture.sshConfig())
        val connected = CompletableDeferred<Unit>()
        val output = ByteArrayOutputStream()
        val connectJob = launch(Dispatchers.IO) {
            connection.connect(
                columns = 80,
                rows = 24,
                onBytes = { bytes -> synchronized(output) { output.write(bytes) } },
                onState = { state ->
                    when (state) {
                        is ConnectionState.AwaitingApproval -> {
                            val prompt = state.prompt as? HostIdentityPrompt
                            if (prompt == null) {
                                connected.completeExceptionally(
                                    AssertionError("Unexpected SSH prompt: ${state.prompt}"),
                                )
                            } else {
                                connection.answerHostIdentityPrompt(
                                    prompt.promptToken,
                                    HostIdentityDecision.TrustOnce,
                                )
                            }
                        }
                        ConnectionState.Connected -> connected.complete(Unit)
                        is ConnectionState.Failed -> connected.completeExceptionally(
                            AssertionError(state.message),
                        )
                        else -> Unit
                    }
                },
            )
        }
        val marker = "API37_LAN_SSH_${UUID.randomUUID().toString().replace("-", "")}"
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { connected.await() }
            assertTrue(connection.trySend("printf '$marker\\n'\n".encodeToByteArray()))
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (synchronized(output) { marker !in output.toString(Charsets.UTF_8.name()) }) {
                    delay(10L)
                }
            }

            val client = SftpClient(KnownHostManager { knownHosts })
            val payload = "API 37 LAN SFTP ${UUID.randomUUID()}".encodeToByteArray()
            try {
                val home = client.connect(
                    config = fixture.sshConfig(),
                    onHostIdentityPrompt = {
                        client.answerHostIdentityPrompt(it.promptToken, HostIdentityDecision.TrustOnce)
                    },
                    onKeyboardInteractiveChallenge = { error("Unexpected SFTP challenge: $it") },
                )
                val directoryName = "terminal-spike-api37-${System.currentTimeMillis()}"
                client.createDirectory(home, directoryName)
                val directory = childPath(home, directoryName)
                val remoteFile = childPath(directory, "payload.txt")
                client.upload(directory, "payload.txt", ByteArrayInputStream(payload))
                val downloaded = ByteArrayOutputStream()
                client.download(remoteFile, downloaded)
                assertArrayEquals(payload, downloaded.toByteArray())
                client.delete(directory)
                assertFalse(client.list(home).any { it.name == directoryName })
            } finally {
                client.close()
            }
        } finally {
            connection.close()
            connectJob.join()
            knownHosts.delete()
        }
    }

    private fun requireFixture(): LanFixture {
        assumeTrue("This real LAN test is opt-in.", Build.VERSION.SDK_INT >= 37)
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val port = arguments.getString(ARG_PORT)?.toIntOrNull()
        assumeTrue(host.isNotBlank() && username.isNotBlank() && password.isNotEmpty())
        assumeTrue(port in 1..65_535)
        return LanFixture(host, requireNotNull(port), username, password)
    }

    private data class LanFixture(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
    ) {
        fun sshConfig() = SshConnectionConfig(
            host = host,
            port = port,
            username = username,
            authentication = SshAuthentication.Password(password.encodeToByteArray()),
        )
    }

    private fun Throwable?.isExpectedDeniedLanFailure(): Boolean {
        var current = this
        while (current != null) {
            // The stable API 37 Google APIs image silently drops this protected TCP SYN and the
            // socket reaches its explicit timeout. Other platform/network paths may surface the
            // kernel denial directly. The granted companion test uses this exact live endpoint.
            if (current is SocketTimeoutException) return true
            val description = "${current.javaClass.name}: ${current.message.orEmpty()}"
            if (
                description.contains("EPERM", ignoreCase = true) ||
                description.contains("EACCES", ignoreCase = true) ||
                description.contains("Operation not permitted", ignoreCase = true) ||
                description.contains("Permission denied", ignoreCase = true)
            ) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        const val ARG_ENABLED = "terminalSpikeRunApi37Lan"
        const val ARG_HOST = "terminalSpikeLanHost"
        const val ARG_PORT = "terminalSpikeLanPort"
        const val ARG_USERNAME = "terminalSpikeLanUsername"
        const val ARG_PASSWORD = "terminalSpikeLanPassword"
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val CONNECT_TIMEOUT_MILLIS = 20_000L
        const val OUTPUT_TIMEOUT_MILLIS = 10_000L
    }
}
