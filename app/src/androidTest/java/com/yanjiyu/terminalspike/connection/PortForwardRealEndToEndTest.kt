package com.yanjiyu.terminalspike.connection

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in against the disposable forwarding-enabled OpenSSH/Mosh fixture, on the USB old phone. */
@RunWith(AndroidJUnit4::class)
class PortForwardRealEndToEndTest {
    @Test(timeout = 90_000)
    fun sshTransfersBothDirectionsAndReleasesListeners() = exercise(mosh = false)

    @Test(timeout = 90_000)
    fun moshTerminalAndCompanionSshTransferBothDirectionsAndReleaseListeners() = exercise(mosh = true)

    private fun exercise(mosh: Boolean) = runBlocking {
        val fixture = fixture()
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 8, loopback).use { echoServer ->
            echoServer.soTimeout = 30_000
            val localPort = freePort()
            val bannerPort = freePort()
            val remotePort = 43127
            val rules = listOf(
                PortForwardRule(listenPort = localPort, destinationPort = remotePort),
                PortForwardRule(PortForwardDirection.REMOTE, listenPort = remotePort,
                    destinationPort = echoServer.localPort),
                PortForwardRule(listenPort = bannerPort, destinationPort = 22),
            )
            // A second fresh session reuses both local and remote ports, proving server-side cancellation too.
            repeat(2) {
                val states = Channel<ConnectionState>(Channel.UNLIMITED)
                val output = Channel<ByteArray>(Channel.UNLIMITED)
                val config = fixture.config(rules)
                val connection: Connection = if (mosh) {
                    val application = InstrumentationRegistry.getInstrumentation().targetContext
                        .applicationContext as TerminalSpikeApplication
                    MoshConnection(
                        MoshBootstrapExecutor(KnownHostManager(EphemeralKnownHostTrustStore())),
                        application.container.moshExtension,
                        MoshBootstrapRequest(config, udpPortRange = MoshPortRange(62000, 62010)),
                    )
                } else JschSshConnection(KnownHostManager(EphemeralKnownHostTrustStore()), config)
                val job = async(Dispatchers.IO) {
                    connection.connect(80, 24, { output.trySend(it) }, { state ->
                        if (state is ConnectionState.AwaitingApproval) {
                            (state.prompt as? HostIdentityPrompt)?.let {
                                connection.answerHostIdentityPrompt(it.promptToken, HostIdentityDecision.TrustOnce)
                            }
                        }
                        states.trySend(state)
                    })
                }
                try {
                    awaitConnected(states)
                    // A split marker excludes the terminal's echo of our command as passing evidence.
                    assertTrue(connection.trySend("printf 'FORWARD_%s\\n' 'TERMINAL_READY'\r".encodeToByteArray()))
                    withTimeout(20_000) {
                        val text = StringBuilder()
                        while (!text.contains("FORWARD_TERMINAL_READY")) text.append(output.receive().decodeToString())
                    }
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(loopback, bannerPort), 5_000)
                        socket.soTimeout = 5_000
                        assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("SSH-2.0-"))
                    }
                    val payload = ByteArray(256 * 1024) { index -> (index * 31 + 17).toByte() }
                    val echo = async(Dispatchers.IO) {
                        echoServer.accept().use { peer ->
                            peer.soTimeout = 15_000
                            val bytes = peer.getInputStream().readNBytesCompat(payload.size)
                            assertArrayEquals(payload, bytes)
                            peer.getOutputStream().write(bytes.reversedArray())
                        }
                    }
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(loopback, localPort), 5_000)
                        socket.soTimeout = 15_000
                        socket.getOutputStream().write(payload)
                        assertArrayEquals(payload.reversedArray(), socket.getInputStream().readNBytesCompat(payload.size))
                    }
                    echo.await()
                    assertTrue(connection.trySend("printf 'FORWARD_%s\\n' 'STILL_ALIVE'\r".encodeToByteArray()))
                    withTimeout(10_000) {
                        val text = StringBuilder()
                        while (!text.contains("FORWARD_STILL_ALIVE")) text.append(output.receive().decodeToString())
                    }
                } finally {
                    connection.close()
                    withTimeout(10_000) { job.cancelAndJoin() }
                    states.close()
                    output.close()
                }
                assertPortClosed(localPort)
                assertPortClosed(bannerPort)
            }
        }
    }

    @Test(timeout = 60_000)
    fun occupiedPortFailsClearlyAndRollsBackEarlierRules() = runBlocking {
        val fixture = fixture()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val firstPort = freePort()
            val config = fixture.config(listOf(
                PortForwardRule(listenPort = firstPort, destinationPort = 22),
                PortForwardRule(listenPort = occupied.localPort, destinationPort = 22),
            ))
            val connection = JschSshConnection(KnownHostManager(EphemeralKnownHostTrustStore()), config)
            val states = CopyOnWriteArrayList<ConnectionState>()
            try {
                withTimeout(30_000) {
                    connection.connect(80, 24, {}, { state ->
                        states.add(state)
                        (state as? ConnectionState.AwaitingApproval)?.prompt?.let { prompt ->
                            if (prompt is HostIdentityPrompt) connection.answerHostIdentityPrompt(
                                prompt.promptToken, HostIdentityDecision.TrustOnce,
                            )
                        }
                    })
                }
                assertFalse(states.contains(ConnectionState.Connected))
                val failure = states.filterIsInstance<ConnectionState.Failed>().single()
                assertTrue(failure.message.startsWith("Port forward 2 could not start."))
                assertEquals(ConnectionFailureDisposition.TERMINAL, failure.disposition)
                assertPortClosed(firstPort)
                assertFalse(occupied.isClosed)
            } finally { connection.close() }
        }
    }

    private suspend fun awaitConnected(states: Channel<ConnectionState>) = withTimeout(30_000) {
        while (true) when (val state = states.receive()) {
            ConnectionState.Connected -> return@withTimeout
            is ConnectionState.Failed -> fail(state.message)
            ConnectionState.Disconnected -> fail("Disconnected before forwarding was ready")
            else -> Unit
        }
    }

    private fun fixture(): Fixture {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the explicit real forwarding fixture", args.getString("portForwardE2e") == "true")
        assertEquals("Real forwarding acceptance is restricted to the old phone", "SM-S911B", Build.MODEL)
        return Fixture(requireNotNull(args.getString("forwardHost")))
    }

    private class Fixture(val host: String) {
        fun config(rules: List<PortForwardRule>) = SshConnectionConfig(
            host, 22222, "terminal", SshAuthentication.Password("terminal-spike-test-only".encodeToByteArray()),
            portForwards = rules,
        )
    }

    private fun freePort(): Int = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun assertPortClosed(port: Int) {
        assertTrue("Listener $port survived close", runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }.isFailure)
    }

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            check(read > 0) { "Tunnel closed after $offset of $count bytes" }
            offset += read
        }
        return result
    }
}
