package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.MoshPortRange
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MoshBootstrapTest {
    @Test
    fun successReturnsNumericEndpointAndMutableKeyThenClosesEverySshResource() = runTest {
        val password = "bootstrap-password".encodeToByteArray()
        val channel = FakeExecChannel(
            stdoutBytes = (
                "server banner\n" +
                    "MOSH CONNECT 60004 $VALID_KEY\r\n" +
                    "[mosh-server detached]\n"
                ).encodeToByteArray(),
        )
        val session = FakeExecSession(ipv4(192, 0, 2, 44), channel)
        val executor = MoshBootstrapExecutor(FakeExecSessionFactory(session))
        val states = mutableListOf<MoshBootstrapState>()

        val result = executor.bootstrap(request(password), states::add)

        assertEquals(MoshAddressFamily.IPV4, result.addressFamily)
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 44), result.addressBytes)
        assertEquals(60_004, result.udpPort)
        assertEquals(VALID_KEY, result.sessionKey.decodeToString())
        assertEquals(
            listOf(MoshBootstrapState.Authenticating, MoshBootstrapState.StartingServer),
            states,
        )
        assertTrue(password.isAllZero())
        assertTrue(channel.closeCalled)
        assertTrue(session.closeCalled)

        val returnedKey = result.sessionKey
        result.close()
        assertTrue(returnedKey.isAllZero())
    }

    @Test
    fun commandUsesPinnedMosh140ShapeAndQuotesEveryRemoteArgument() {
        val request = MoshBootstrapRequest(
            ssh = config(),
            serverCommand = "/opt/O'Brien mosh;echo pwned",
            locale = "en_GB.UTF-8",
            udpPortRange = MoshPortRange(60_000, 60_010),
        )

        assertEquals(
            "'/opt/O'\\''Brien mosh;echo pwned' 'new' '-c' '256' '-s' " +
                "'-p' '60000:60010' '-l' 'LANG=en_GB.UTF-8'",
            buildMoshServerCommand(request),
        )
    }

    @Test
    fun singlePortAndDefaultServerAreEncodedWithoutShellInterpolation() {
        assertEquals(
            "'mosh-server' 'new' '-c' '256' '-s' '-p' '60001' '-l' 'LANG=en_US.UTF-8'",
            buildMoshServerCommand(
                MoshBootstrapRequest(
                    ssh = config(),
                    udpPort = 60_001,
                ),
            ),
        )
    }

    @Test
    fun commandAndLocaleBoundsRejectControlCharactersOrNonUtf8Locale() {
        assertThrows(IllegalArgumentException::class.java) {
            MoshBootstrapRequest(ssh = config(), serverCommand = "mosh-server\nwhoami")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshBootstrapRequest(ssh = config(), locale = "C")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoshBootstrapRequest(
                ssh = config(),
                serverCommand = "x".repeat(513),
            )
        }
    }

    @Test
    fun malformedOfficialResponsesAreRejectedWithoutEchoingTheirContent() = runTest {
        val malformed = listOf(
            "MOSH CONNECT nope $VALID_KEY\n",
            "MOSH CONNECT 0 $VALID_KEY\n",
            "MOSH CONNECT 65536 $VALID_KEY\n",
            "MOSH CONNECT 60001 short\n",
            "MOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQ=\n",
            "MOSH CONNECT 60001 $VALID_KEY extra\n",
        )

        malformed.forEach { output ->
            val error = bootstrapFailure(output)
            assertEquals(MoshBootstrapFailure.MALFORMED_CONNECT_LINE, error.failure)
            assertFalse(error.message.orEmpty().contains(VALID_KEY))
            assertFalse(error.message.orEmpty().contains("60001"))
        }
    }

    @Test
    fun missingAndAmbiguousResponsesHaveDistinctStableFailures() = runTest {
        assertEquals(
            MoshBootstrapFailure.MISSING_CONNECT_LINE,
            bootstrapFailure("mosh-server: command not found\n").failure,
        )
        assertEquals(
            MoshBootstrapFailure.AMBIGUOUS_CONNECT_LINE,
            bootstrapFailure(
                "MOSH CONNECT 60001 $VALID_KEY\n" +
                    "MOSH CONNECT 60002 abcdefghijklmnopqrstuv\n",
            ).failure,
        )
    }

    @Test
    fun totalOutputAndIndividualLinesAreBounded() = runTest {
        val totalError = bootstrapFailure(
            output = "notice\n".repeat(20),
            limits = MoshExecLimits(
                maximumOutputBytes = 64,
                maximumLineBytes = 32,
                maximumLines = 128,
            ),
        )
        assertEquals(MoshBootstrapFailure.OUTPUT_LIMIT, totalError.failure)

        val lineError = bootstrapFailure(
            output = "x".repeat(33),
            limits = MoshExecLimits(
                maximumOutputBytes = 64,
                maximumLineBytes = 32,
                maximumLines = 128,
            ),
        )
        assertEquals(MoshBootstrapFailure.OUTPUT_LIMIT, lineError.failure)
    }

    @Test
    fun stalledExecTimesOutAndDisconnectsWithoutBlockingTheJvmTest() {
        val channel = FakeExecChannel(
            stdoutBytes = byteArrayOf(),
            closesAfterConnect = false,
        )
        val clock = FakeClock()
        val error = try {
            readBoundedExecOutput(
                channel = channel,
                limits = MoshExecLimits(
                    channelConnectTimeoutMillis = 10,
                    totalTimeoutMillis = 3,
                    maximumOutputBytes = 64,
                    maximumLineBytes = 32,
                    maximumLines = 8,
                    pollIntervalMillis = 1,
                ),
                clock = clock,
                waiter = MoshPollWaiter(clock::advanceMillis),
            )
            fail("Expected timeout")
            error("unreachable")
        } catch (expected: MoshBootstrapException) {
            expected
        }

        assertEquals(MoshBootstrapFailure.TIMEOUT, error.failure)
        assertTrue(channel.closeCalled)
        assertEquals(10, channel.connectTimeoutMillis)
    }

    @Test
    fun arbitraryTransportDiagnosticsAndSecretsNeverReachFailureMessage() = runTest {
        val password = "hunter2".encodeToByteArray()
        val session = object : MoshSshExecSession {
            override val numericAddress: InetAddress = ipv4(203, 0, 113, 7)

            override fun openExec(command: String): MoshExecChannel {
                error("password=hunter2 MOSH_KEY=$VALID_KEY ssh://admin@private.example")
            }

            override fun close() = Unit
        }
        val executor = MoshBootstrapExecutor(FakeExecSessionFactory(session))

        val error = expectBootstrapFailure {
            executor.bootstrap(request(password)) {}
        }

        assertEquals(MoshBootstrapFailure.INTERNAL, error.failure)
        listOf("hunter2", VALID_KEY, "admin", "private.example").forEach { sensitive ->
            assertFalse(error.message.orEmpty().contains(sensitive))
        }
        assertTrue(password.isAllZero())
    }

    @Test
    fun malformedResultConstructorWipesCallerKeyBeforeThrowing() {
        val malformedKey = ByteArray(22) { '!'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            MoshBootstrapResult(
                addressFamily = MoshAddressFamily.IPV4,
                addressBytes = byteArrayOf(127, 0, 0, 1),
                udpPort = 60_001,
                sessionKey = malformedKey,
            )
        }

        assertTrue(malformedKey.isAllZero())
    }

    @Test
    fun awaitingHostApprovalCanBeAnsweredWhileBootstrapIsRunning() {
        val directory = Files.createTempDirectory("mosh-bootstrap-host-key-test").toFile()
        try {
            val promptSeen = CountDownLatch(1)
            val states = Collections.synchronizedList(mutableListOf<MoshBootstrapState>())
            val session = FakeExecSession(
                ipv4(198, 51, 100, 8),
                FakeExecChannel("MOSH CONNECT 60003 $VALID_KEY\n".encodeToByteArray()),
            )
            val factory = PromptingExecSessionFactory(
                knownHostsFile = directory.resolve("known_hosts"),
                session = session,
            )
            val executor = MoshBootstrapExecutor(factory)
            val result = AtomicReference<Result<MoshBootstrapResult>>()
            val worker = thread(name = "mosh-bootstrap-approval-test") {
                result.set(
                    runCatching {
                        runBlocking {
                            executor.bootstrap(MoshBootstrapRequest(config())) { state ->
                                states += state
                                if (state is MoshBootstrapState.AwaitingApproval) promptSeen.countDown()
                            }
                        }
                    },
                )
            }

            assertTrue(promptSeen.await(2, TimeUnit.SECONDS))
            val hostPrompt = (states.last { it is MoshBootstrapState.AwaitingApproval } as
                MoshBootstrapState.AwaitingApproval).prompt as HostIdentityPrompt
            executor.answerHostIdentityPrompt(
                hostPrompt.promptToken,
                HostIdentityDecision.TrustAndSave,
            )
            worker.join(2_000)

            assertFalse(worker.isAlive)
            val bootstrap = result.get().getOrThrow()
            assertTrue(states.any { it is MoshBootstrapState.AwaitingApproval })
            assertEquals(60_003, bootstrap.udpPort)
            bootstrap.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitCloseCancelsInFlightBootstrapAndWipesConfigurationSecret() {
        val resourceReady = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val password = "cancelled-secret".encodeToByteArray()
        val factory = object : MoshSshExecSessionFactory {
            override suspend fun open(
                config: SshConnectionConfig,
                onPrompt: (HostIdentityPrompt) -> Unit,
                onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
                onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
                registerConnectingSession: (AutoCloseable) -> Boolean,
            ): MoshSshExecSession? {
                val resource = AutoCloseable { resourceClosed.countDown() }
                assertTrue(registerConnectingSession(resource))
                resourceReady.countDown()
                assertTrue(resourceClosed.await(2, TimeUnit.SECONDS))
                return null
            }
        }
        val executor = MoshBootstrapExecutor(factory)
        val result = AtomicReference<Result<MoshBootstrapResult>>()
        val worker = thread(name = "mosh-bootstrap-cancel-test") {
            result.set(
                runCatching {
                    runBlocking { executor.bootstrap(request(password)) {} }
                },
            )
        }

        assertTrue(resourceReady.await(2, TimeUnit.SECONDS))
        executor.close()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        val error = result.get().exceptionOrNull() as MoshBootstrapException
        assertEquals(MoshBootstrapFailure.CANCELLED, error.failure)
        assertTrue(password.isAllZero())
    }

    private suspend fun bootstrapFailure(
        output: String,
        limits: MoshExecLimits = MoshExecLimits(),
    ): MoshBootstrapException {
        val session = FakeExecSession(
            numericAddress = ipv4(192, 0, 2, 1),
            channel = FakeExecChannel(output.encodeToByteArray()),
        )
        return expectBootstrapFailure {
            MoshBootstrapExecutor(FakeExecSessionFactory(session), limits)
                .bootstrap(MoshBootstrapRequest(config())) {}
        }
    }

    private suspend fun expectBootstrapFailure(
        block: suspend () -> Unit,
    ): MoshBootstrapException = try {
        block()
        fail("Expected MoshBootstrapException")
        error("unreachable")
    } catch (expected: MoshBootstrapException) {
        expected
    }

    private fun request(password: ByteArray) = MoshBootstrapRequest(
        ssh = config(SshAuthentication.Password(password)),
    )

    private fun config(
        authentication: SshAuthentication = SshAuthentication.Password("test".encodeToByteArray()),
    ) = SshConnectionConfig(
        host = "example.test",
        port = 22,
        username = "alice",
        authentication = authentication,
    )

    private companion object {
        const val VALID_KEY = "4NeCCgvZFe2RnPgrcU1PQw"
    }
}

private class FakeExecSessionFactory(
    private val session: MoshSshExecSession,
) : MoshSshExecSessionFactory {
    override suspend fun open(
        config: SshConnectionConfig,
        onPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
        onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
        registerConnectingSession: (AutoCloseable) -> Boolean,
    ): MoshSshExecSession? = session.takeIf { registerConnectingSession(it) }
}

private class PromptingExecSessionFactory(
    knownHostsFile: File,
    private val session: MoshSshExecSession,
) : MoshSshExecSessionFactory {
    private val store = KnownHostStore(knownHostsFile)

    override suspend fun open(
        config: SshConnectionConfig,
        onPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
        onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
        registerConnectingSession: (AutoCloseable) -> Boolean,
    ): MoshSshExecSession? {
        val repository = VerifyingHostKeyRepository(store, config.host, onPrompt)
        onRepositoryReady(repository)
        val result = repository.check(config.host, ed25519Key(7))
        check(result == com.jcraft.jsch.HostKeyRepository.OK)
        return session.takeIf { registerConnectingSession(it) }
    }
}

private class FakeExecSession(
    override val numericAddress: InetAddress,
    private val channel: MoshExecChannel,
) : MoshSshExecSession {
    var closeCalled = false
    var command: String? = null

    override fun openExec(command: String): MoshExecChannel {
        this.command = command
        return channel
    }

    override fun close() {
        closeCalled = true
    }
}

private class FakeExecChannel(
    stdoutBytes: ByteArray,
    stderrBytes: ByteArray = byteArrayOf(),
    private val closesAfterConnect: Boolean = true,
) : MoshExecChannel {
    override val stdout: InputStream = ByteArrayInputStream(stdoutBytes)
    override val stderr: InputStream = ByteArrayInputStream(stderrBytes)
    override val isClosed: Boolean get() = connected && closesAfterConnect
    override val exitStatus: Int = 0
    var connected = false
    var closeCalled = false
    var connectTimeoutMillis: Int? = null

    override fun connect(timeoutMillis: Int) {
        this.connectTimeoutMillis = timeoutMillis
        connected = true
    }

    override fun close() {
        closeCalled = true
        stdout.close()
        stderr.close()
    }
}

private class FakeClock : MoshMonotonicClock {
    private var nanos = 0L

    override fun nowNanos(): Long = nanos

    fun advanceMillis(millis: Long) {
        nanos += millis * 1_000_000L
    }
}

private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress = InetAddress.getByAddress(
    byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()),
)

private fun ed25519Key(fill: Byte): ByteArray {
    val algorithm = "ssh-ed25519".toByteArray(StandardCharsets.US_ASCII)
    val publicKey = ByteArray(32) { fill }
    return ByteBuffer.allocate(4 + algorithm.size + 4 + publicKey.size)
        .putInt(algorithm.size)
        .put(algorithm)
        .putInt(publicKey.size)
        .put(publicKey)
        .array()
}

private fun ByteArray.isAllZero(): Boolean = all { it == 0.toByte() }
