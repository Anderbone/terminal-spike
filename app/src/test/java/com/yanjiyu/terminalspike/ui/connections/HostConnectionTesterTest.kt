package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveQuestion
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.prepareHostConnectionTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConnectionTesterTest {
    @Test
    fun successCompletesAllSixStagesAndWipesPassword() = runTest {
        val secret = byteArrayOf(1, 2, 3)
        val probe = FakeProbe()

        val result = StagedHostConnectionTester(probe).test(spec(secret))

        assertEquals(ConnectionTestStage.entries.toSet(), (result as HostConnectionTestResult.Success).completedStages)
        assertArrayEquals(byteArrayOf(0, 0, 0), secret)
        assertTrue(probe.leaseClosed)
    }

    @Test
    fun eachFailureIsAttributedToItsExactStageAndWipesPassword() = runTest {
        ConnectionTestStage.entries.forEach { failedStage ->
            val secret = byteArrayOf(4, 5, 6)
            val result = StagedHostConnectionTester(FakeProbe(failedStage)).test(spec(secret))

            assertEquals(failedStage, (result as HostConnectionTestResult.Failed).stage)
            assertArrayEquals("secret at $failedStage", byteArrayOf(0, 0, 0), secret)
            assertEquals(completedBefore(failedStage), result.completedStages)
        }
    }

    @Test
    fun approvalIsNotPersistedAndIncludesOnlyCompletedNetworkStages() = runTest {
        val secret = byteArrayOf(7, 8, 9)
        val prompt = HostIdentityPrompt.FirstContact(
            endpoint = "server.example",
            algorithm = "ssh-ed25519",
            newFingerprint = "SHA256:test",
            promptToken = 1L,
        )
        val result = StagedHostConnectionTester(FakeProbe(approval = prompt)).test(spec(secret))

        assertEquals(prompt, (result as HostConnectionTestResult.HostKeyApprovalRequired).prompt)
        assertEquals(setOf(ConnectionTestStage.DNS, ConnectionTestStage.TCP), result.completedStages)
        assertArrayEquals(byteArrayOf(0, 0, 0), secret)
    }

    @Test
    fun cancellationIsRethrownAndWipesPrivateKeyPassphrase() = runTest {
        val passphrase = byteArrayOf(9, 8, 7)
        val tester = StagedHostConnectionTester(FakeProbe(cancelAt = ConnectionTestStage.TCP))
        var cancelled = false

        try {
            tester.test(
                HostConnectionTestSpec(
                    host = "server.example",
                    port = 22,
                    username = "alice",
                    authentication = SshAuthentication.PrivateKey(
                        identityName = "work key",
                        loadKey = { byteArrayOf(1) },
                        passphrase = passphrase,
                    ),
                ),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertArrayEquals(byteArrayOf(0, 0, 0), passphrase)
    }

    @Test
    fun cancellationBeforeIoBlockEntryStillWipesPassword() = runTest {
        val secret = byteArrayOf(9, 8, 7)
        val probe = FakeProbe()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            StagedHostConnectionTester(probe).test(spec(secret))
        }
        job.join()

        assertArrayEquals(byteArrayOf(0, 0, 0), secret)
        assertFalse(probe.resolveCalled)
    }

    @Test
    fun editorSubmissionTransfersSynchronouslyAndDestroysCharacters() {
        val characters = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val prepared = HostEditorSubmission(
            value = validatedHost(),
            secret = characters,
            savePassword = false,
        ).prepareHostConnectionTest()

        assertArrayEquals(CharArray(characters.size), characters)
        assertArrayEquals(byteArrayOf(115, 101, 99, 114, 101, 116), prepared.borrowSecret())

        prepared.wipeSecret()
        assertArrayEquals(ByteArray(6), prepared.borrowSecret())
    }

    @Test
    fun coordinatorSurvivesObserverReplacementAndRejectsStaleResponses() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owners = mutableListOf<RecordingPromptOwner>()
        var nextChallenge = 100L
        var nextOperation = 10L
        val coordinator = HostConnectionTestCoordinator(
            scope = this,
            dispatcher = dispatcher,
            nextOperationToken = { ++nextOperation },
            runner = { _, interaction ->
                val challenge = challenge(++nextChallenge)
                val owner = RecordingPromptOwner(challenge.challengeToken)
                owners += owner
                assertTrue(interaction.registerPromptOwner(owner))
                interaction.showKeyboardInteractiveChallenge(challenge)
                awaitCancellation()
            },
        )

        val firstSecret = byteArrayOf(1, 2, 3)
        val first = coordinator.start(PreparedHostConnectionTest(validatedHost(), firstSecret))
        testScheduler.runCurrent()
        val firstChallenge = (
            coordinator.state.value as HostConnectionTestUiState.AwaitingKeyboardInteractive
        ).challenge.challengeToken

        // A newly-created observer reads the same coordinator state; replacing the operation
        // closes the original live owner and retains only the replacement token.
        val recreatedObserver = coordinator.state
        val secondSecret = byteArrayOf(4, 5, 6)
        val second = coordinator.start(PreparedHostConnectionTest(validatedHost(), secondSecret))
        testScheduler.runCurrent()
        val secondPresentation =
            recreatedObserver.value as HostConnectionTestUiState.AwaitingKeyboardInteractive
        assertEquals(second, secondPresentation.operationToken)
        assertTrue(owners.first().closed)
        assertArrayEquals(ByteArray(3), firstSecret)

        val staleResponse = charArrayOf('o', 'l', 'd')
        assertFalse(
            coordinator.answerKeyboardInteractive(
                operationToken = first,
                challengeToken = firstChallenge,
                responses = listOf(staleResponse),
            ),
        )
        assertArrayEquals(CharArray(3), staleResponse)
        assertEquals(second, coordinator.state.value.operationToken)

        val currentResponse = charArrayOf('n', 'e', 'w')
        assertTrue(
            coordinator.answerKeyboardInteractive(
                operationToken = second,
                challengeToken = secondPresentation.challenge.challengeToken,
                responses = listOf(currentResponse),
            ),
        )
        assertArrayEquals(CharArray(3), currentResponse)
        assertEquals(HostConnectionTestUiState.Running(second), coordinator.state.value)

        coordinator.cancel(second)
        testScheduler.runCurrent()
        assertArrayEquals(ByteArray(3), secondSecret)
    }

    @Test
    fun exactCompletedTokenCanDismissButStaleTokenCannot() = runTest {
        var nextOperation = 20L
        val coordinator = HostConnectionTestCoordinator(
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            nextOperationToken = { ++nextOperation },
            runner = { _, _ ->
                HostConnectionTestResult.Success(ConnectionTestStage.entries.toSet())
            },
        )
        val operation = coordinator.start(
            PreparedHostConnectionTest(validatedHost(), byteArrayOf(1)),
        )
        testScheduler.runCurrent()

        assertTrue(coordinator.state.value is HostConnectionTestUiState.Complete)
        coordinator.cancel(operation - 1)
        assertTrue(coordinator.state.value is HostConnectionTestUiState.Complete)
        coordinator.cancel(operation)
        assertEquals(HostConnectionTestUiState.Idle, coordinator.state.value)
    }

    @Test
    fun cancelledScopeWipesPreparedBytesWhenLazyBodyNeverEnters() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = Job().also { it.cancel() }
        val secret = byteArrayOf(7, 7, 7)
        val coordinator = HostConnectionTestCoordinator(
            scope = CoroutineScope(parent + dispatcher),
            dispatcher = dispatcher,
            runner = { _, _ -> error("cancelled scope must not enter runner") },
        )

        coordinator.start(PreparedHostConnectionTest(validatedHost(), secret))
        testScheduler.runCurrent()

        assertArrayEquals(ByteArray(3), secret)
        coordinator.close()
    }

    private fun spec(secret: ByteArray) = HostConnectionTestSpec(
        host = "server.example",
        port = 22,
        username = "alice",
        authentication = SshAuthentication.Password(secret),
    )

    private fun validatedHost() = ValidatedHostEditor(
        persistentId = null,
        displayName = "Server",
        protocol = ConnectionProtocol.SSH,
        hostname = "server.example",
        port = 22,
        username = "alice",
        authenticationMethod = HostAuthenticationMethod.KEYBOARD_INTERACTIVE,
        keyIdentityId = null,
        retainSavedSecret = false,
        terminalProfileId = null,
        keyboardProfileId = null,
        isFavourite = false,
        group = null,
        tag = null,
        startupCommand = null,
        keepaliveIntervalSeconds = null,
        reconnectPolicy = null,
        moshPort = null,
        moshPortRange = null,
        moshServerCommand = null,
    )

    private fun challenge(token: Long) = KeyboardInteractiveChallenge(
        challengeToken = token,
        name = "Authentication",
        instruction = "Enter the response.",
        questions = listOf(KeyboardInteractiveQuestion("Code", echo = false)),
    )

    private class RecordingPromptOwner(
        private val challengeToken: Long,
    ) : HostConnectionTestPromptOwner {
        var closed = false

        override fun answerHostIdentity(
            promptToken: Long,
            decision: HostIdentityDecision,
        ): Boolean = false

        override fun answerKeyboardInteractive(
            challengeToken: Long,
            responses: List<CharArray>,
        ): Boolean {
            if (challengeToken != this.challengeToken) {
                responses.forEach { it.fill('\u0000') }
                return false
            }
            responses.forEach { it.fill('\u0000') }
            return true
        }

        override fun cancelKeyboardInteractive(challengeToken: Long): Boolean =
            challengeToken == this.challengeToken

        override fun close() {
            closed = true
        }
    }

    private fun completedBefore(stage: ConnectionTestStage): Set<ConnectionTestStage> = when (stage) {
        ConnectionTestStage.DNS -> emptySet()
        ConnectionTestStage.TCP -> setOf(ConnectionTestStage.DNS)
        ConnectionTestStage.SSH_NEGOTIATION,
        ConnectionTestStage.HOST_KEY,
        ConnectionTestStage.AUTHENTICATION,
        -> setOf(ConnectionTestStage.DNS, ConnectionTestStage.TCP)
        ConnectionTestStage.SHELL -> setOf(
            ConnectionTestStage.DNS,
            ConnectionTestStage.TCP,
            ConnectionTestStage.SSH_NEGOTIATION,
            ConnectionTestStage.HOST_KEY,
            ConnectionTestStage.AUTHENTICATION,
        )
    }

    private class FakeProbe(
        private val failedStage: ConnectionTestStage? = null,
        private val cancelAt: ConnectionTestStage? = null,
        private val approval: HostIdentityPrompt? = null,
    ) : HostConnectionTestProbe {
        var leaseClosed = false
        var resolveCalled = false

        override suspend fun resolve(host: String) {
            resolveCalled = true
            failOrCancel(ConnectionTestStage.DNS)
        }

        override suspend fun connectTcp(host: String, port: Int) = failOrCancel(ConnectionTestStage.TCP)

        override suspend fun authenticate(
            spec: HostConnectionTestSpec,
            interaction: HostConnectionTestInteraction,
        ): HostConnectionTestLease {
            cancelAt?.takeIf {
                it in setOf(
                    ConnectionTestStage.SSH_NEGOTIATION,
                    ConnectionTestStage.HOST_KEY,
                    ConnectionTestStage.AUTHENTICATION,
                )
            }?.let { throw CancellationException("cancelled") }
            failedStage?.takeIf {
                it in setOf(
                    ConnectionTestStage.SSH_NEGOTIATION,
                    ConnectionTestStage.HOST_KEY,
                    ConnectionTestStage.AUTHENTICATION,
                )
            }?.let { throw HostConnectionStageFailure(it, "failed at $it") }
            approval?.let { throw HostConnectionApprovalRequired(it) }
            return object : HostConnectionTestLease {
                override suspend fun openShell() = failOrCancel(ConnectionTestStage.SHELL)

                override fun close() {
                    leaseClosed = true
                }
            }
        }

        private fun failOrCancel(stage: ConnectionTestStage) {
            if (cancelAt == stage) throw CancellationException("cancelled")
            if (failedStage == stage) throw HostConnectionStageFailure(stage, "failed at $stage")
        }
    }
}
