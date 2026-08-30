package com.yanjiyu.terminalspike.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in proof of the complete SSH-bootstrap -> Binder/PFD -> native UDP Mosh path.
 *
 * The fixture password is supplied only as an instrumentation argument. The test is skipped during
 * ordinary connected suites so no credential or machine-specific endpoint is committed to source.
 */
@RunWith(AndroidJUnit4::class)
class MoshRealEndToEndTest {
    @Test
    fun realServerCarriesInteractiveTerminalBytesThroughTheExtension() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PASSWORD to run the real Mosh fixture.",
            host.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
        )
        val sshPort = arguments.getString(ARG_SSH_PORT)?.toIntOrNull() ?: DEFAULT_SSH_PORT
        val udpFirst = arguments.getString(ARG_UDP_FIRST)?.toIntOrNull() ?: DEFAULT_UDP_FIRST
        val udpLast = arguments.getString(ARG_UDP_LAST)?.toIntOrNull() ?: DEFAULT_UDP_LAST
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TerminalSpikeApplication
        val connection = MoshConnection(
            bootstrapExecutor = MoshBootstrapExecutor(
                KnownHostManager(EphemeralKnownHostTrustStore()),
            ),
            extensionClient = application.container.moshExtension,
            bootstrapRequest = MoshBootstrapRequest(
                ssh = SshConnectionConfig(
                    host = host,
                    port = sshPort,
                    username = username,
                    authentication = SshAuthentication.Password(password.encodeToByteArray()),
                ),
                udpPortRange = MoshPortRange(udpFirst, udpLast),
            ),
        )
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        val connectionJob = async(Dispatchers.IO) {
            connection.connect(
                columns = 80,
                rows = 24,
                onBytes = { bytes -> output.trySend(bytes.copyOf()) },
                onState = { state ->
                    if (state is ConnectionState.AwaitingApproval) {
                        val prompt = state.prompt as? HostIdentityPrompt
                        if (prompt != null) {
                            connection.answerHostIdentityPrompt(
                                prompt.promptToken,
                                HostIdentityDecision.TrustAndSave,
                            )
                        }
                    }
                    states.trySend(state)
                },
            )
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = states.receive()) {
                        ConnectionState.Connected -> break
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected -> fail("Mosh disconnected before becoming ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        is ConnectionState.AwaitingApproval,
                        -> Unit
                    }
                }
            }

            val command = COMMAND.encodeToByteArray()
            command.forEach { byte ->
                assertTrue(
                    "The connected Mosh input pipe rejected a typed command byte.",
                    connection.sendWithAcceptance(byteArrayOf(byte)),
                )
            }
            val collected = ByteArrayOutputStream()
            val terminal = VtTerminalEngine(columns = 80, rows = 24)
            val displayHistory = MoshDisplayHistory()
            val retainedHistory = mutableListOf<com.yanjiyu.terminalspike.terminal.model.TerminalLine>()
            var latestScreen = emptyList<com.yanjiyu.terminalspike.terminal.model.TerminalLine>()
            var frameCount = 0
            var firstRowWasDisplayed = false
            var lastObservedRange: String? = null
            val observedRanges = mutableListOf<String>()
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (true) {
                    val chunk = output.receive()
                    check(collected.size() + chunk.size <= MAX_CAPTURE_BYTES) {
                        "Mosh fixture output exceeded its bounded capture."
                    }
                    collected.write(chunk)
                    val parsed = terminal.accept(chunk)
                    frameCount += 1
                    firstRowWasDisplayed = firstRowWasDisplayed ||
                        parsed.screen.any { it.text.trimEnd() == firstExpectedRow() }
                    val visibleMoshRows = parsed.screen.map { it.text.trimEnd() }
                        .filter { it.startsWith("MOSH_SCROLL_") }
                    val observedRange = visibleMoshRows.takeIf { it.isNotEmpty() }
                        ?.let { "${it.first()}..${it.last()}" }
                    if (
                        observedRange != null && observedRange != lastObservedRange &&
                        observedRanges.size < MAX_OBSERVED_RANGES
                    ) {
                        observedRanges += observedRange
                        lastObservedRange = observedRange
                    }
                    val frame = displayHistory.retainDisplayedRows(parsed)
                    retainedHistory += frame.completedScrollback
                    latestScreen = frame.screen
                    val rendered = frame.screen.joinToString("\n") { line -> line.text }
                    if (MARKER in rendered) break
                }
            }
            assertTrue(
                "The first of 200 real Mosh rows must survive in reconstructed scrollback. " +
                    "frameCount=$frameCount, firstRowWasDisplayed=$firstRowWasDisplayed, " +
                    "observedRanges=$observedRanges, " +
                    "retainedHead=${retainedHistory.take(6).map { it.text.trimEnd() }}, " +
                    "retainedTail=${retainedHistory.takeLast(6).map { it.text.trimEnd() }}, " +
                    "finalScreen=${latestScreen.map { it.text.trimEnd() }}",
                retainedHistory.any { it.text.trimEnd() == firstExpectedRow() },
            )
            assertTrue(
                "The last of 200 real Mosh rows must remain on the final terminal screen.",
                latestScreen.any { it.text.trimEnd() == lastExpectedRow() },
            )
        } finally {
            connection.close()
            states.close()
            output.close()
            if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { connectionJob.await() } == null) {
                connectionJob.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val ARG_HOST = "moshE2eHost"
        const val ARG_SSH_PORT = "moshE2eSshPort"
        const val ARG_USERNAME = "moshE2eUsername"
        const val ARG_PASSWORD = "moshE2ePassword"
        const val ARG_UDP_FIRST = "moshE2eUdpFirst"
        const val ARG_UDP_LAST = "moshE2eUdpLast"
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_UDP_FIRST = 60_000
        const val DEFAULT_UDP_LAST = 61_000
        const val CONNECT_TIMEOUT_MILLIS = 60_000L
        const val OUTPUT_TIMEOUT_MILLIS = 30_000L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
        const val MAX_CAPTURE_BYTES = 512 * 1024
        const val OUTPUT_ROW_COUNT = 200
        const val MAX_OBSERVED_RANGES = 16
        const val COMMAND =
            "i=1; while [ \"\$i\" -le $OUTPUT_ROW_COUNT ]; do " +
                "printf 'MOSH_SCROLL_%03d\\n' \"\$i\"; sleep 0.03; i=\$((i+1)); done; " +
                "printf 'MOSH_SCROLL_%s\\n' DONE\n"
        const val MARKER = "MOSH_SCROLL_DONE"

        fun firstExpectedRow(): String = "MOSH_SCROLL_001"

        fun lastExpectedRow(): String = "MOSH_SCROLL_200"
    }
}

private class EphemeralKnownHostTrustStore : KnownHostTrustStore {
    private val lock = Any()
    private val entries = mutableListOf<TrustedKnownHostKey>()

    override fun check(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): KnownHostTrustCheck = synchronized(lock) {
        val matchingEndpoint = entries.filter { it.endpoint.matches(endpoint) }
        when {
            matchingEndpoint.any { it.algorithm == algorithm && it.copyKey().contentEquals(key) } ->
                KnownHostTrustCheck.Trusted
            matchingEndpoint.isEmpty() -> KnownHostTrustCheck.UnknownEndpoint
            else -> KnownHostTrustCheck.Changed(matchingEndpoint)
        }
    }

    override fun trustFirst(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult = synchronized(lock) {
        val matchingEndpoint = entries.filter { it.endpoint.matches(endpoint) }
        when {
            matchingEndpoint.any { it.algorithm == algorithm && it.copyKey().contentEquals(key) } ->
                FirstHostKeyTrustResult.ALREADY_TRUSTED
            matchingEndpoint.isNotEmpty() -> FirstHostKeyTrustResult.CHANGED
            else -> {
                entries += TrustedKnownHostKey(endpoint, algorithm, key)
                FirstHostKeyTrustResult.STORED
            }
        }
    }

    override fun replaceEndpoint(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): Int = synchronized(lock) {
        val removed = entries.count { it.endpoint.matches(endpoint) }
        entries.removeAll { it.endpoint.matches(endpoint) }
        entries += TrustedKnownHostKey(endpoint, algorithm, key)
        removed
    }

    override fun replaceEndpointIfUnchanged(
        endpoint: KnownHostEndpoint,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        algorithm: String,
        key: ByteArray,
    ): ConditionalHostKeyReplacementResult = synchronized(lock) {
        val current = entries.filter { it.endpoint.matches(endpoint) }
        val unchanged = current.size == expectedTrustedKeys.size && current.all { trusted ->
            expectedTrustedKeys.any { expected ->
                expected.algorithm == trusted.algorithm &&
                    expected.copyKey().contentEquals(trusted.copyKey())
            }
        }
        if (!unchanged) return@synchronized ConditionalHostKeyReplacementResult.STALE
        entries.removeAll { it.endpoint.matches(endpoint) }
        entries += TrustedKnownHostKey(endpoint, algorithm, key)
        ConditionalHostKeyReplacementResult.REPLACED
    }

    override fun forgetEndpoint(endpoint: KnownHostEndpoint): Int = synchronized(lock) {
        val removed = entries.count { it.endpoint.matches(endpoint) }
        entries.removeAll { it.endpoint.matches(endpoint) }
        removed
    }

    override fun list(endpoint: KnownHostEndpoint?): List<TrustedKnownHostKey> = synchronized(lock) {
        entries.filter { endpoint == null || it.endpoint.matches(endpoint) }
    }

    private fun KnownHostEndpoint.matches(other: KnownHostEndpoint): Boolean =
        host == other.host && port == other.port
}
