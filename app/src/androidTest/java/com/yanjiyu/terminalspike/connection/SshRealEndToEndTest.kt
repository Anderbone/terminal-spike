package com.yanjiyu.terminalspike.connection

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in proof that the real main-app SSH transport works without the separate Mosh APK.
 *
 * The endpoint and password are supplied only as instrumentation arguments. Ordinary connected
 * suites therefore skip this test and no fixture credential or machine-specific address enters an
 * Android source set or packaged artifact.
 */
@RunWith(AndroidJUnit4::class)
class SshRealEndToEndTest {
    @Test
    fun realServerCarriesInteractiveBytesWhileMoshExtensionIsAbsent() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PASSWORD to run the real SSH fixture.",
            host.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
        )
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val requireMoshAbsent = arguments.getString(ARG_REQUIRE_MOSH_ABSENT)
            ?.toBooleanStrictOrNull() ?: false
        if (requireMoshAbsent) assertMoshExtensionAbsent()

        val passwordBytes = password.encodeToByteArray()
        val connection = JschSshConnection(
            knownHostManager = KnownHostManager(SshE2eKnownHostTrustStore()),
            config = SshConnectionConfig(
                host = host,
                port = port,
                username = username,
                authentication = SshAuthentication.Password(passwordBytes),
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
                                promptToken = prompt.promptToken,
                                decision = HostIdentityDecision.TrustOnce,
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
                        ConnectionState.Disconnected ->
                            fail("SSH disconnected before becoming ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        is ConnectionState.AwaitingApproval,
                        -> Unit
                    }
                }
            }

            assertTrue(
                "The connected SSH transport rejected a complete interactive command.",
                connection.sendWithAcceptance(COMMAND.encodeToByteArray()),
            )
            val collected = ByteArrayOutputStream()
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (MARKER !in collected.toString(Charsets.UTF_8.name())) {
                    val chunk = output.receive()
                    check(collected.size() + chunk.size <= MAX_CAPTURE_BYTES) {
                        "SSH fixture output exceeded its bounded capture."
                    }
                    collected.write(chunk)
                }
            }
        } finally {
            connection.close()
            states.close()
            output.close()
            if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { connectionJob.await() } == null) {
                connectionJob.cancelAndJoin()
            }
        }
        assertTrue(
            "Connection-owned password bytes must be wiped after the attempt.",
            passwordBytes.all { it == 0.toByte() },
        )
    }

    private fun assertMoshExtensionAbsent() {
        val packageManager = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageManager
        val extensionInstalled = try {
            packageManager.getPackageInfo(MOSH_EXTENSION_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        assertFalse(
            "The Mosh extension must be absent for this release-isolation proof.",
            extensionInstalled,
        )
    }

    private companion object {
        const val ARG_HOST = "sshE2eHost"
        const val ARG_PORT = "sshE2ePort"
        const val ARG_USERNAME = "sshE2eUsername"
        const val ARG_PASSWORD = "sshE2ePassword"
        const val ARG_REQUIRE_MOSH_ABSENT = "sshE2eRequireMoshAbsent"
        const val DEFAULT_PORT = 22
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
        const val OUTPUT_TIMEOUT_MILLIS = 15_000L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
        const val MAX_CAPTURE_BYTES = 64 * 1024
        const val MOSH_EXTENSION_PACKAGE = "com.yanjiyu.terminalspike.mosh"
        const val MARKER = "SSH_42"
        const val COMMAND = "printf 'SSH_%d\\n' $((6*7)); exit\n"
    }
}

private class SshE2eKnownHostTrustStore : KnownHostTrustStore {
    private val lock = Any()
    private val entries = mutableListOf<TrustedKnownHostKey>()

    override fun check(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): KnownHostTrustCheck = synchronized(lock) {
        val current = entries.filter { it.endpoint.sameEndpointAs(endpoint) }
        when {
            current.any { it.algorithm == algorithm && it.copyKey().contentEquals(key) } ->
                KnownHostTrustCheck.Trusted
            current.isEmpty() -> KnownHostTrustCheck.UnknownEndpoint
            else -> KnownHostTrustCheck.Changed(current)
        }
    }

    override fun trustFirst(
        endpoint: KnownHostEndpoint,
        algorithm: String,
        key: ByteArray,
    ): FirstHostKeyTrustResult = synchronized(lock) {
        val current = entries.filter { it.endpoint.sameEndpointAs(endpoint) }
        when {
            current.any { it.algorithm == algorithm && it.copyKey().contentEquals(key) } ->
                FirstHostKeyTrustResult.ALREADY_TRUSTED
            current.isNotEmpty() -> FirstHostKeyTrustResult.CHANGED
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
        val removed = entries.count { it.endpoint.sameEndpointAs(endpoint) }
        entries.removeAll { it.endpoint.sameEndpointAs(endpoint) }
        entries += TrustedKnownHostKey(endpoint, algorithm, key)
        removed
    }

    override fun replaceEndpointIfUnchanged(
        endpoint: KnownHostEndpoint,
        expectedTrustedKeys: List<TrustedKnownHostKey>,
        algorithm: String,
        key: ByteArray,
    ): ConditionalHostKeyReplacementResult = synchronized(lock) {
        val current = entries.filter { it.endpoint.sameEndpointAs(endpoint) }
        val unchanged = current.size == expectedTrustedKeys.size && current.all { trusted ->
            expectedTrustedKeys.any { expected ->
                expected.algorithm == trusted.algorithm &&
                    expected.copyKey().contentEquals(trusted.copyKey())
            }
        }
        if (!unchanged) return@synchronized ConditionalHostKeyReplacementResult.STALE
        entries.removeAll { it.endpoint.sameEndpointAs(endpoint) }
        entries += TrustedKnownHostKey(endpoint, algorithm, key)
        ConditionalHostKeyReplacementResult.REPLACED
    }

    override fun forgetEndpoint(endpoint: KnownHostEndpoint): Int = synchronized(lock) {
        val removed = entries.count { it.endpoint.sameEndpointAs(endpoint) }
        entries.removeAll { it.endpoint.sameEndpointAs(endpoint) }
        removed
    }

    override fun list(endpoint: KnownHostEndpoint?): List<TrustedKnownHostKey> = synchronized(lock) {
        entries.filter { endpoint == null || it.endpoint.sameEndpointAs(endpoint) }
    }

    private fun KnownHostEndpoint.sameEndpointAs(other: KnownHostEndpoint): Boolean =
        host == other.host && port == other.port
}
