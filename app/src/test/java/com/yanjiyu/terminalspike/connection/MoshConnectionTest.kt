package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.connection.mosh.MoshClientFailure
import com.yanjiyu.terminalspike.connection.mosh.MoshClientResult
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionVersion
import com.yanjiyu.terminalspike.connection.mosh.MoshNegotiatedProtocol
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily as ApiMoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement
import org.junit.runners.model.MultipleFailureException

class MoshConnectionTest {
    private val connectionClosers = mutableListOf<() -> Unit>()
    private val connectionWorkers = mutableListOf<Thread>()

    @get:Rule
    val connectionResources = object : TestWatcher() {
        override fun finished(description: Description) = releaseFixtureResources()
    }

    @Test
    fun assertionFailureStillClosesTheConnectedPipesAndJoinsTheWorker() {
        val fixture = MoshConnectionTest()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(startResult = MoshTransportStartResult.Success(transport))
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val expected = AssertionError("controlled assertion after connection")
        var owned: MoshConnection? = null
        var worker: Thread? = null
        try {
            val result = runFixture(fixture) {
                owned = fixture.connection(FakeConnectionBootstrap(), extension)
                worker = fixture.connectOnThread(requireNotNull(owned), states)
                assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
                assertTrue(fixture.awaitState(states) { it is ConnectionState.Connected })
                assertTrue(requireNotNull(worker).isAlive)
                throw expected
            }
            assertEquals(1, result.failureCount)
            assertSame(expected, result.failures.single().exception)
            assertTrue("JUnit teardown must close the connected pipes after an assertion", transport.closed)
            assertFalse("JUnit teardown must join its connect worker", requireNotNull(worker).isAlive)
            assertFalse("Daemon workers must not substitute for teardown", requireNotNull(worker).isDaemon)
        } finally {
            // The negative probe must also be safe on the original, leaking fixture.
            owned?.close()
            transport.close()
            worker?.join(2_000)
        }
    }

    @Test
    fun assertionFailureBeforeConnectingStillWipesThePreparedPassword() {
        val fixture = MoshConnectionTest()
        val password = "prepared fixture password".encodeToByteArray()
        val expected = AssertionError("controlled assertion before connection")
        var owned: MoshConnection? = null
        try {
            val result = runFixture(fixture) {
                owned = fixture.connection(FakeConnectionBootstrap(), FakeConnectionExtension(), password)
                throw expected
            }
            assertEquals(1, result.failureCount)
            assertSame(expected, result.failures.single().exception)
            assertTrue("JUnit teardown must wipe authentication even before connect", password.all { it == 0.toByte() })
        } finally {
            owned?.close()
            password.fill(0)
        }
    }

    /** Use JUnit's actual rule/after lifecycle while replacing one test body with a controlled failure. */
    private fun runFixture(fixture: MoshConnectionTest, body: () -> Unit): org.junit.runner.Result {
        val runner = object : BlockJUnit4ClassRunner(MoshConnectionTest::class.java) {
            override fun createTest(): Any = fixture
            override fun methodInvoker(method: FrameworkMethod, test: Any): Statement = object : Statement() {
                override fun evaluate() = body()
            }
        }
        val request = Request.runner(runner).filterWith(Description.createTestDescription(
            MoshConnectionTest::class.java, "cleanTerminalPipeEofPublishesDisconnectedWithoutFailure",
        ))
        return JUnitCore().run(request)
    }

    @Test
    fun bootstrapCancellationPropagatesAfterCleanupWithoutTerminalMapping() = runTest {
        val cancellation = CancellationException("expected cancellation")
        val password = "must-clear-on-cancel".encodeToByteArray()
        val bootstrap = FakeConnectionBootstrap(bootstrapFailure = cancellation)
        val states = mutableListOf<ConnectionState>()

        var thrown: Throwable? = null
        try {
            connection(
                bootstrap = bootstrap,
                extension = FakeConnectionExtension(),
                password = password,
            ).connect(80, 24, {}, states::add)
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown === cancellation)
        assertTrue(password.all { it == 0.toByte() })
        assertEquals(2, bootstrap.closeCalls)
        assertFalse(states.any { it is ConnectionState.Failed })
        assertFalse(states.any { it is ConnectionState.Disconnected })
    }

    @Test
    fun cleanTerminalPipeEofPublishesDisconnectedWithoutFailure() {
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        transport.finishOutput()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(transport.closed)
        assertEquals(1, states.count { it is ConnectionState.Disconnected })
        assertEquals(0, states.count { it is ConnectionState.Failed })
    }

    @Test
    fun extensionErrorImmediatelyAfterTerminalPipeEofTakesPrecedence() {
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        transport.finishOutput()
        assertTrue(transport.outputEofObserved.await(2, TimeUnit.SECONDS))
        extension.events.tryEmit(
            MoshTransportEvent(
                state = MoshSessionState.ERROR,
                disconnectReason = MoshDisconnectReason.NONE,
                errorCode = MoshErrorCode.EXTENSION_DIED,
            ),
        )
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(transport.closed)
        assertEquals(0, states.count { it is ConnectionState.Disconnected })
        assertEquals(1, states.count { it is ConnectionState.Failed })
        assertEquals(
            "Mosh transport stopped unexpectedly.",
            (states.last() as ConnectionState.Failed).message,
        )
    }

    @Test
    fun terminalStateCallbackFailureStillClosesMoshPipesAndStopsTheSession() {
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val callbackFailed = CountDownLatch(1)
        val worker = connectOnThread(
            connection = connection,
            states = states,
            onState = { state ->
                states += state
                if (state is ConnectionState.Failed) {
                    callbackFailed.countDown()
                    throw IllegalStateException("expected callback failure")
                }
            },
        )

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        extension.events.tryEmit(
            MoshTransportEvent(
                state = MoshSessionState.ERROR,
                disconnectReason = MoshDisconnectReason.NONE,
                errorCode = MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL,
            ),
        )
        assertTrue(callbackFailed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(transport.closed)
        assertEquals(1, extension.stopCalls.size)
        assertEquals(1, states.count { it is ConnectionState.Failed })
    }

    @Test
    fun successfulTransportMovesBytesResizesStopsOnceAndWipesBootstrapKey() {
        val password = "ssh-bootstrap-password".encodeToByteArray()
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension, password = password)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val received = Collections.synchronizedList(mutableListOf<ByteArray>())
        val receivedOutput = CountDownLatch(1)
        val worker = connectOnThread(
            connection = connection,
            states = states,
            onBytes = { bytes ->
                received.add(bytes)
                receivedOutput.countDown()
            },
        )

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        assertTrue(bootstrap.lastReturnedKey!!.all { it == 0.toByte() })
        assertTrue(password.all { it == 0.toByte() })

        assertEquals(
            "/home/alice/.cache/terminal-spike/pasted-images/image.png",
            connection.uploadPastedImage(
                "00000000-0000-0000-0000-000000000001.png",
                byteArrayOf(7, 8, 9).inputStream(),
            ),
        )
        assertArrayEquals(byteArrayOf(7, 8, 9), bootstrap.uploadedBytes)

        assertTrue(connection.trySend(byteArrayOf(1, 2, 3)))
        assertTrue(transport.input.received.await(2, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(1, 2, 3), transport.input.bytes())

        transport.sendOutput("hello".encodeToByteArray())
        assertTrue(receivedOutput.await(2, TimeUnit.SECONDS))
        assertEquals("hello", received.single().decodeToString())

        connection.resize(91, 37)
        assertTrue(extension.resizeSeen.await(2, TimeUnit.SECONDS))
        assertEquals(TerminalResize(91, 37), extension.resizes.last())

        connection.close()
        connection.close()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(1, extension.stopCalls.size)
        assertTrue(transport.closed)
        assertEquals(1, bootstrap.sideChannelCloseCalls)
        assertEquals(1, states.count { it is ConnectionState.Disconnected })
        assertEquals(0, states.count { it is ConnectionState.Failed })
    }

    @Test
    fun activeAttemptForwardsNetworkHintAndCloseRejectsStaleOwnership() {
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(FakeConnectionBootstrap(), extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        connection.updateNetworkHint(
            NetworkAvailabilitySnapshot(
                isOnline = true,
                connectivityGeneration = 7L,
                addressFamily = ApiMoshAddressFamily.IPV6,
                isMetered = true,
            ),
        )
        assertTrue(extension.networkHintSeen.await(2, TimeUnit.SECONDS))
        assertEquals(SESSION_ID, extension.networkHints.single().first)
        assertEquals(7L, extension.networkHints.single().second.connectivityGeneration)
        assertEquals(ApiMoshAddressFamily.IPV6, extension.networkHints.single().second.addressFamily)
        assertTrue(extension.networkHints.single().second.isMetered)

        connection.close()
        connection.updateNetworkHint(
            NetworkAvailabilitySnapshot(false, 8L, ApiMoshAddressFamily.UNSPECIFIED, false),
        )
        worker.join(2_000)

        assertEquals(1, extension.networkHints.size)
    }

    @Test
    fun unavailableExtensionFailsBeforeSshBootstrapAndClearsAuthentication() = runTest {
        val password = "must-clear".encodeToByteArray()
        val bootstrap = FakeConnectionBootstrap()
        val extension = FakeConnectionExtension(status = MoshExtensionStatus.Absent)
        val states = mutableListOf<ConnectionState>()

        connection(bootstrap, extension, password = password).connect(80, 24, {}, states::add)

        assertEquals(0, bootstrap.calls)
        assertTrue(password.all { it == 0.toByte() })
        assertEquals(
            "The built-in Mosh transport is unavailable.",
            (states.last() as ConnectionState.Failed).message,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            (states.last() as ConnectionState.Failed).disposition,
        )
    }

    @Test
    fun hostKeyPromptCanBeAnsweredWhileBootstrapIsRunning() {
        val bootstrap = FakeConnectionBootstrap(requireApproval = true)
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(bootstrap.promptPublished.await(2, TimeUnit.SECONDS))
        assertTrue(states.any { it is ConnectionState.AwaitingApproval })
        connection.answerHostIdentityPrompt(
            FakeConnectionBootstrap.PROMPT_TOKEN,
            HostIdentityDecision.TrustAndSave,
        )
        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))

        connection.close()
        worker.join(2_000)
        assertEquals(listOf(HostIdentityDecision.TrustAndSave), bootstrap.answers)
    }

    @Test
    fun keyboardInteractiveChallengeRoutesMutableResponseToRunningBootstrap() {
        val bootstrap = FakeConnectionBootstrap(requireKeyboardChallenge = true)
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(bootstrap.promptPublished.await(2, TimeUnit.SECONDS))
        val awaiting = synchronized(states) {
            states.last { it is ConnectionState.AwaitingApproval }
        } as ConnectionState.AwaitingApproval
        assertTrue(awaiting.prompt is KeyboardInteractiveChallenge)
        val response = "123456".toCharArray()
        connection.answerKeyboardInteractiveChallenge(
            FakeConnectionBootstrap.KEYBOARD_PROMPT_TOKEN,
            listOf(response),
        )
        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))

        connection.close()
        worker.join(2_000)
        assertEquals(listOf("123456"), bootstrap.keyboardResponses)
        assertTrue(response.all { it == '\u0000' })
    }

    @Test
    fun startFailureWipesKeyAndReportsOnlyStableCategory() = runTest {
        val bootstrap = FakeConnectionBootstrap()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Failure(MoshClientFailure.REMOTE_FAILURE),
        )
        val states = mutableListOf<ConnectionState>()

        connection(bootstrap, extension).connect(80, 24, {}, states::add)

        assertTrue(bootstrap.lastReturnedKey!!.all { it == 0.toByte() })
        assertEquals(0, extension.stopCalls.size)
        assertEquals(
            "Mosh transport failed while starting the session.",
            (states.last() as ConnectionState.Failed).message,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            (states.last() as ConnectionState.Failed).disposition,
        )
    }

    @Test
    fun fullInputQueueRejectsTrySendAndSendPublishesOneTerminalFailure() {
        val bootstrap = FakeConnectionBootstrap()
        val blockingInput = BlockingFirstWriteOutputStream()
        val transport = FakeMoshTerminalTransport(input = blockingInput)
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(
            bootstrap,
            extension,
            writerCapacity = 1,
            writerPollMillis = 10,
        )
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)

        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(awaitState(states) { it is ConnectionState.Connected })
        assertTrue(connection.trySend(byteArrayOf(1)))
        assertTrue(blockingInput.firstWriteStarted.await(2, TimeUnit.SECONDS))
        assertTrue(connection.trySend(byteArrayOf(2)))
        assertFalse(connection.trySend(byteArrayOf(3)))
        connection.send(byteArrayOf(4))

        assertTrue(awaitState(states) { it is ConnectionState.Failed })
        blockingInput.release()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(1, states.count { it is ConnectionState.Failed })
        assertEquals(
            "Mosh input queue is full. Connection closed to prevent input loss.",
            (states.last { it is ConnectionState.Failed } as ConnectionState.Failed).message,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            (states.last { it is ConnectionState.Failed } as ConnectionState.Failed).disposition,
        )
        assertEquals(1, extension.stopCalls.size)
    }

    @Test
    fun resizeRpcCoalescesAnInflightBurstToFirstAndLatestDimensions() {
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
            blockFirstResize = true,
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)
        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))

        connection.resize(81, 25)
        assertTrue(extension.firstResizeStarted.await(2, TimeUnit.SECONDS))
        connection.resize(82, 26)
        connection.resize(90, 30)
        connection.resize(99, 40)
        extension.releaseFirstResize.countDown()
        assertTrue(extension.twoResizesSeen.await(2, TimeUnit.SECONDS))

        assertEquals(
            listOf(TerminalResize(81, 25), TerminalResize(99, 40)),
            extension.resizes,
        )
        connection.close()
        worker.join(2_000)
    }

    @Test
    fun extensionErrorClosesPipesStopsOnceAndIgnoresLateTerminalEvents() {
        val bootstrap = FakeConnectionBootstrap()
        val transport = FakeMoshTerminalTransport()
        val extension = FakeConnectionExtension(
            startResult = MoshTransportStartResult.Success(transport),
        )
        val connection = connection(bootstrap, extension)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val worker = connectOnThread(connection, states)
        assertTrue(extension.sessionStarted.await(2, TimeUnit.SECONDS))

        extension.events.tryEmit(
            MoshTransportEvent(
                state = MoshSessionState.ERROR,
                disconnectReason = 0,
                errorCode = MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL,
            ),
        )
        assertTrue(awaitState(states) { it is ConnectionState.Failed })
        worker.join(2_000)
        extension.events.tryEmit(
            MoshTransportEvent(
                state = MoshSessionState.ERROR,
                disconnectReason = 0,
                errorCode = MoshErrorCode.INTERNAL_REDACTED,
            ),
        )

        assertFalse(worker.isAlive)
        assertTrue(transport.closed)
        assertEquals(1, extension.stopCalls.size)
        assertEquals(1, states.count { it is ConnectionState.Failed })
        assertEquals(
            "Mosh could not reach the server over UDP.",
            (states.last { it is ConnectionState.Failed } as ConnectionState.Failed).message,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            (states.last { it is ConnectionState.Failed } as ConnectionState.Failed).disposition,
        )
    }

    @Test
    fun onlyExplicitMoshTransportLossCategoriesAreRetryable() {
        assertEquals(
            MoshFallbackFailure.UDP,
            moshErrorFailure(MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL).moshFallbackFailure,
        )
        assertEquals(
            MoshFallbackFailure.EXTENSION,
            moshErrorFailure(MoshErrorCode.EXTENSION_DIED).moshFallbackFailure,
        )
        assertEquals(
            null,
            moshErrorFailure(MoshErrorCode.EXTENSION_UNTRUSTED).moshFallbackFailure,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            moshErrorFailure(MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL).disposition,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            moshErrorFailure(MoshErrorCode.EXTENSION_DIED).disposition,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            moshErrorFailure(MoshErrorCode.INVALID_REQUEST).disposition,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            (moshDisconnectState(MoshDisconnectReason.TRANSPORT_LOST) as ConnectionState.Failed)
                .disposition,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            (moshDisconnectState(MoshDisconnectReason.REMOTE_CLOSED) as ConnectionState.Failed)
                .disposition,
        )
        assertTrue(
            moshDisconnectState(MoshDisconnectReason.USER_REQUESTED) is ConnectionState.Disconnected,
        )
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            MoshBootstrapException(
                failure = MoshBootstrapFailure.SSH,
                message = "Mosh SSH transport failed.",
                cause = IOException("socket closed"),
            ).safeConnectionFailure().disposition,
        )
        assertEquals(
            null,
            MoshBootstrapException(
                failure = MoshBootstrapFailure.SSH,
                message = "Authentication failed.",
            ).safeConnectionFailure().moshFallbackFailure,
        )
        assertEquals(
            MoshFallbackFailure.BOOTSTRAP,
            MoshBootstrapException(
                failure = MoshBootstrapFailure.ADDRESS_RESOLUTION,
                message = "Resolution failed.",
            ).safeConnectionFailure().moshFallbackFailure,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            MoshBootstrapException(
                failure = MoshBootstrapFailure.SSH,
                message = "Mosh SSH bootstrap failed.",
            ).safeConnectionFailure().disposition,
        )
    }

    private fun releaseFixtureResources() {
        val failures = mutableListOf<Throwable>()
        var interrupted = false
        try {
            connectionClosers.asReversed().forEach { close ->
                try {
                    close()
                } catch (error: Throwable) {
                    failures += error
                }
            }
            connectionWorkers.forEach { worker ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (worker.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    try {
                        worker.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L))
                    } catch (error: InterruptedException) {
                        interrupted = true
                        failures += error
                    }
                }
                if (worker.isAlive) failures += AssertionError("Fixture connect worker did not stop: ${worker.name}")
            }
        } finally {
            connectionClosers.clear()
            connectionWorkers.clear()
            if (interrupted) Thread.currentThread().interrupt()
        }
        MultipleFailureException.assertEmpty(failures)
    }

    private fun connection(
        bootstrap: FakeConnectionBootstrap,
        extension: FakeConnectionExtension,
        password: ByteArray = "password".encodeToByteArray(),
        writerCapacity: Int = 256,
        writerPollMillis: Long = 10,
    ): MoshConnection = MoshConnection(
        bootstrap = bootstrap,
        extension = extension,
        bootstrapRequest = MoshBootstrapRequest(
            ssh = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.Password(password),
            ),
        ),
        sessionIdFactory = { SESSION_ID },
        controlDispatcher = Dispatchers.IO,
        writerCapacity = writerCapacity,
        writerPollMillis = writerPollMillis,
    ).also { owned ->
        connectionClosers += {
            extension.releaseFirstResize.countDown()
            owned.close()
        }
    }

    private fun connectOnThread(
        connection: MoshConnection,
        states: MutableList<ConnectionState>,
        onBytes: (ByteArray) -> Unit = {},
        onState: (ConnectionState) -> Unit = states::add,
    ): Thread = thread(start = false, name = "mosh-connection-test") {
        runBlocking {
            connection.connect(80, 24, onBytes, onState)
        }
    }.also { worker ->
        connectionWorkers += worker
        worker.start()
    }

    private fun awaitState(
        states: List<ConnectionState>,
        predicate: (ConnectionState) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(states) { states.any(predicate) }) return true
            Thread.yield()
        }
        return synchronized(states) { states.any(predicate) }
    }

    private companion object {
        val SESSION_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
    }
}

private class FakeConnectionBootstrap(
    private val requireApproval: Boolean = false,
    private val requireKeyboardChallenge: Boolean = false,
    private val bootstrapFailure: Exception? = null,
) : MoshConnectionBootstrap {
    var calls = 0
    var closeCalls = 0
    val answers = Collections.synchronizedList(mutableListOf<HostIdentityDecision>())
    val keyboardResponses = Collections.synchronizedList(mutableListOf<String>())
    val promptPublished = CountDownLatch(1)
    private val approval = CountDownLatch(1)
    var lastReturnedKey: ByteArray? = null
    var uploadedBytes: ByteArray? = null
    var sideChannelCloseCalls = 0

    override suspend fun bootstrap(
        request: MoshBootstrapRequest,
        onState: (MoshBootstrapState) -> Unit,
    ): MoshBootstrapResult {
        calls += 1
        bootstrapFailure?.let { throw it }
        if (requireApproval) {
            onState(
                MoshBootstrapState.AwaitingApproval(
                    HostIdentityPrompt.FirstContact(
                        endpoint = "example.test",
                        algorithm = "ssh-ed25519",
                        newFingerprint = "SHA256:test",
                        promptToken = PROMPT_TOKEN,
                    ),
                ),
            )
            promptPublished.countDown()
            check(approval.await(2, TimeUnit.SECONDS))
        }
        if (requireKeyboardChallenge) {
            onState(
                MoshBootstrapState.AwaitingApproval(
                    KeyboardInteractiveChallenge(
                        challengeToken = KEYBOARD_PROMPT_TOKEN,
                        name = "OTP",
                        instruction = "",
                        questions = listOf(
                            KeyboardInteractiveQuestion("Code:", echo = false),
                        ),
                    ),
                ),
            )
            promptPublished.countDown()
            check(approval.await(2, TimeUnit.SECONDS))
        }
        request.ssh.clearAuthenticationSecrets()
        val key = VALID_MOSH_KEY.encodeToByteArray()
        lastReturnedKey = key
        return MoshBootstrapResult(
            addressFamily = MoshAddressFamily.IPV4,
            addressBytes = byteArrayOf(192.toByte(), 0, 2, 10),
            udpPort = 60_001,
            sessionKey = key,
            sshSideChannel = object : MoshSshExecSession {
                override val numericAddress = java.net.InetAddress.getByAddress(
                    byteArrayOf(192.toByte(), 0, 2, 10),
                )

                override fun openExec(command: String): MoshExecChannel =
                    error("No further bootstrap command expected.")

                override fun uploadPastedImage(
                    fileName: String,
                    source: InputStream,
                ): String {
                    uploadedBytes = source.readBytes()
                    return "/home/alice/.cache/terminal-spike/pasted-images/image.png"
                }

                override fun close() {
                    sideChannelCloseCalls += 1
                }
            },
        )
    }

    override fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        if (promptToken != PROMPT_TOKEN) return
        answers += decision
        approval.countDown()
    }

    override fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        if (challengeToken == KEYBOARD_PROMPT_TOKEN) {
            keyboardResponses += responses.map(CharArray::concatToString)
            approval.countDown()
        }
        responses.forEach { it.fill('\u0000') }
    }

    override fun cancelPendingPrompts() {
        approval.countDown()
    }

    override fun close() {
        closeCalls += 1
        approval.countDown()
    }

    companion object {
        const val PROMPT_TOKEN = 91L
        const val KEYBOARD_PROMPT_TOKEN = 92L
    }
}

private class FakeConnectionExtension(
    private val status: MoshExtensionStatus = availableStatus(),
    private val startResult: MoshTransportStartResult =
        MoshTransportStartResult.Failure(MoshClientFailure.REMOTE_FAILURE),
    private val blockFirstResize: Boolean = false,
) : MoshConnectionExtension {
    val events = MutableSharedFlow<MoshTransportEvent>(replay = 1, extraBufferCapacity = 7)
    val sessionStarted = CountDownLatch(1)
    val resizeSeen = CountDownLatch(1)
    val firstResizeStarted = CountDownLatch(1)
    val releaseFirstResize = CountDownLatch(1)
    val twoResizesSeen = CountDownLatch(2)
    val networkHintSeen = CountDownLatch(1)
    val resizes = Collections.synchronizedList(mutableListOf<TerminalResize>())
    val networkHints = Collections.synchronizedList(mutableListOf<Pair<UUID, MoshNetworkHint>>())
    val stopCalls = Collections.synchronizedList(mutableListOf<Pair<UUID, Int>>())
    var capturedStartSpec: MoshSessionStartSpec? = null
    var capturedKey: ByteArray? = null

    override suspend fun connect(): MoshExtensionStatus = status

    override fun sessionEvents(sessionId: UUID): Flow<MoshTransportEvent> = events

    override suspend fun startSession(
        spec: MoshSessionStartSpec,
        sessionKey: ByteArray,
    ): MoshTransportStartResult {
        capturedStartSpec = spec
        capturedKey = sessionKey
        sessionStarted.countDown()
        return try {
            startResult
        } finally {
            sessionKey.fill(0)
        }
    }

    override suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit> {
        val resize = TerminalResize(columns, rows)
        resizes += resize
        resizeSeen.countDown()
        twoResizesSeen.countDown()
        if (blockFirstResize && resizes.size == 1) {
            firstResizeStarted.countDown()
            check(releaseFirstResize.await(2, TimeUnit.SECONDS))
        }
        return MoshClientResult.Success(Unit)
    }

    override suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit> {
        networkHints += sessionId to hint
        networkHintSeen.countDown()
        return MoshClientResult.Success(Unit)
    }

    override suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit> {
        stopCalls += sessionId to reason
        return MoshClientResult.Success(Unit)
    }
}

private data class TerminalResize(val columns: Int, val rows: Int)

private class FakeMoshTerminalTransport(
    val input: RecordingOutputStream = RecordingOutputStream(),
    override val initialState: Int = MoshSessionState.CONNECTED,
) : MoshTerminalTransport {
    private val remoteOutput = PipedOutputStream()
    private val outputDelegate = PipedInputStream(remoteOutput)
    val outputEofObserved = CountDownLatch(1)
    override val terminalOutput: InputStream = object : InputStream() {
        override fun read(): Int = outputDelegate.read().also(::recordEof)

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            outputDelegate.read(bytes, offset, length).also(::recordEof)

        override fun close() = outputDelegate.close()

        private fun recordEof(count: Int) {
            if (count < 0) outputEofObserved.countDown()
        }
    }
    override val terminalInput: OutputStream = input
    @Volatile var closed = false

    fun sendOutput(bytes: ByteArray) {
        remoteOutput.write(bytes)
        remoteOutput.flush()
    }

    fun finishOutput() {
        remoteOutput.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
        remoteOutput.close()
        terminalOutput.close()
    }
}

private open class RecordingOutputStream : OutputStream() {
    private val delegate = ByteArrayOutputStream()
    val received = CountDownLatch(1)

    override fun write(value: Int) {
        delegate.write(value)
        received.countDown()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
        received.countDown()
    }

    fun bytes(): ByteArray = delegate.toByteArray()
}

private class BlockingFirstWriteOutputStream : RecordingOutputStream() {
    val firstWriteStarted = CountDownLatch(1)
    private val release = CountDownLatch(1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        firstWriteStarted.countDown()
        if (!release.await(2, TimeUnit.SECONDS)) throw IOException("test write timed out")
        super.write(bytes, offset, length)
    }

    override fun close() {
        release.countDown()
    }

    fun release() {
        release.countDown()
    }
}

private fun availableStatus(): MoshExtensionStatus.Available = MoshExtensionStatus.Available(
    version = MoshExtensionVersion(1, "1.0"),
    protocol = MoshNegotiatedProtocol(
        extensionApiVersion = 1,
        negotiatedApiVersion = 1,
        capabilityFlags = MoshCapability.IPV4,
        maximumConcurrentSessions = 1,
    ),
)

private const val VALID_MOSH_KEY = "4NeCCgvZFe2RnPgrcU1PQw"
