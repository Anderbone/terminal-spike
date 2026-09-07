package com.yanjiyu.terminalspike.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in proof of the Binder/PFD/native UDP path against an already-started Mosh server. */
@RunWith(AndroidJUnit4::class)
class MoshDirectRealEndToEndTest {
    @Test
    fun directServerKeyCarriesInteractiveTerminalBytesThroughTheExtension() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val port = arguments.getString(ARG_PORT)?.toIntOrNull()
        val key = arguments.getString(ARG_KEY).orEmpty()
        assumeTrue(
            "Pass $ARG_HOST, $ARG_PORT and $ARG_KEY to run the direct Mosh fixture.",
            host.isNotBlank() && port in 1..65_535 && key.length == MOSH_KEY_BYTES,
        )

        val address = InetAddress.getByName(host)
        assumeTrue("The direct Mosh fixture requires a numeric IP address.", address.hostAddress == host)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TerminalSpikeApplication
        val client = application.container.moshExtension
        val extensionStatus = client.connect()
        assertTrue(
            "The installed Mosh transport was not available: ${extensionStatus::class.simpleName}",
            extensionStatus is MoshExtensionStatus.Available,
        )
        val extension = AndroidMoshConnectionExtension(client)
        val sessionId = UUID.randomUUID()
        val terminalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                extension.sessionEvents(sessionId).first { event ->
                    event.state == MoshSessionState.CONNECTED ||
                        event.state == MoshSessionState.ERROR ||
                        event.state == MoshSessionState.DISCONNECTED
                }
            }
        }
        val startResult = extension.startSession(
            spec = MoshSessionStartSpec(
                sessionId = sessionId,
                addressFamily = when (address) {
                    is Inet4Address -> MoshAddressFamily.IPV4
                    is Inet6Address -> MoshAddressFamily.IPV6
                    else -> error("Unsupported fixture address family.")
                },
                addressBytes = address.address,
                udpPort = requireNotNull(port),
                initialColumns = 80,
                initialRows = 24,
                locale = "en_US.UTF-8",
                optionFlags = 0,
            ),
            sessionKey = key.encodeToByteArray(),
        )
        val transport = when (startResult) {
            is MoshTransportStartResult.Success -> startResult.value
            is MoshTransportStartResult.Failure -> {
                throw AssertionError("Mosh start failed: ${startResult.reason}")
            }
        }

        try {
            val event = terminalEvent.await()
            assertTrue(
                "Mosh ended before connecting: state=${event.state}, error=${event.errorCode}",
                event.state == MoshSessionState.CONNECTED,
            )

            // Keep the fixture command portable across common login shells. In particular, Fish
            // intentionally does not implement the POSIX-shell `$((...))` arithmetic syntax.
            val command = "printf 'MOSH_DIRECT_42\\n'\n".encodeToByteArray()
            command.forEach { byte ->
                transport.terminalInput.write(byteArrayOf(byte))
            }
            transport.terminalInput.flush()
            val collected = ByteArrayOutputStream()
            val terminal = VtTerminalEngine(columns = 80, rows = 24)
            withContext(Dispatchers.IO) {
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = transport.terminalOutput.read(buffer)
                        check(count >= 0) { "Mosh terminal output closed before the marker arrived." }
                        check(collected.size() + count <= MAX_CAPTURE_BYTES) {
                            "Mosh fixture output exceeded its bounded capture."
                        }
                        collected.write(buffer, 0, count)
                        val frame = terminal.accept(buffer.copyOf(count))
                        val rendered = frame.screen.joinToString("\n") { line -> line.text }
                        if (MARKER in rendered) break
                    }
                    buffer.fill(0)
                }
            }
        } finally {
            transport.close()
            extension.stopSession(sessionId, MoshStopReason.USER_REQUESTED)
        }
    }

    private companion object {
        const val ARG_HOST = "moshDirectHost"
        const val ARG_PORT = "moshDirectPort"
        const val ARG_KEY = "moshDirectKey"
        const val MOSH_KEY_BYTES = 22
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
        const val OUTPUT_TIMEOUT_MILLIS = 30_000L
        const val MAX_CAPTURE_BYTES = 128 * 1024
        const val MARKER = "MOSH_DIRECT_42"
    }
}
