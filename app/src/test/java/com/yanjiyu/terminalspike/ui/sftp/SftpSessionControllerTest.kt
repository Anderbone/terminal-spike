package com.yanjiyu.terminalspike.ui.sftp

import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveQuestion
import com.yanjiyu.terminalspike.connection.SftpDownloadDestination
import com.yanjiyu.terminalspike.connection.SftpFile
import com.yanjiyu.terminalspike.connection.SftpSession
import com.yanjiyu.terminalspike.connection.SftpUploadEntry
import com.yanjiyu.terminalspike.connection.SftpUploadSource
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SftpSessionControllerTest {
    @Test
    fun authenticationRequestCarriesOnlyNonSecretMetadataAndCloseClearsIt() = runTest {
        val controller = controller { error("No client expected") }

        controller.requestAuthentication(
            hostId = "host-id",
            hostName = "Production",
            kind = SftpSecretKind.PASSWORD,
        )

        assertEquals(
            SftpUiState.AuthenticationRequired("host-id", "Production", SftpSecretKind.PASSWORD),
            controller.state.value,
        )
        controller.close()
        assertSame(SftpUiState.Closed, controller.state.value)
    }

    @Test
    fun lateConnectionCompletionCannotReplaceNewerBrowsingState() = runTest {
        val first = FakeSftpSession(ignoreCancellation = true)
        val second = FakeSftpSession(completedPath = "/new")
        val clients = ArrayDeque(listOf(first, second))
        val controller = controller { clients.removeFirst() }

        controller.connect("Old", config("old.example"))
        runCurrent()
        controller.connect("New", config("new.example"))
        runCurrent()

        assertEquals("New", (controller.state.value as SftpUiState.Browsing).hostName)
        first.complete("/stale")
        runCurrent()

        assertEquals(
            SftpUiState.Browsing("New", "/new", emptyList()),
            controller.state.value,
        )
        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun latePromptCannotAttachToNewerConnection() = runTest {
        val stalePrompt = HostIdentityPrompt.FirstContact(
            endpoint = "old.example",
            algorithm = "ssh-ed25519",
            newFingerprint = "SHA256:old",
            promptToken = 1,
        )
        val first = FakeSftpSession(ignoreCancellation = true, promptAfterGate = stalePrompt)
        val second = FakeSftpSession(completedPath = "/new")
        val clients = ArrayDeque(listOf(first, second))
        val controller = controller { clients.removeFirst() }

        controller.connect("Old", config("old.example"))
        runCurrent()
        controller.connect("New", config("new.example"))
        runCurrent()
        first.complete("/stale")
        runCurrent()

        val state = controller.state.value as SftpUiState.Browsing
        assertEquals("New", state.hostName)
        assertEquals("/new", state.path)
    }

    @Test
    fun lateSuccessAndFailureCannotReplaceAuthenticationRequest() = runTest {
        listOf(Result.success("/stale"), Result.failure(IllegalStateException("late"))).forEach {
                completion ->
            val first = FakeSftpSession(ignoreCancellation = true)
            val controller = controller { first }

            controller.connect("Old", config("old.example"))
            runCurrent()
            controller.requestAuthentication("new-id", "New", SftpSecretKind.PASSPHRASE)
            first.complete(completion)
            runCurrent()

            assertEquals(
                SftpUiState.AuthenticationRequired("new-id", "New", SftpSecretKind.PASSPHRASE),
                controller.state.value,
            )
        }
    }

    @Test
    fun lateSuccessAndFailureCannotReopenClosedController() = runTest {
        listOf(Result.success("/stale"), Result.failure(IllegalStateException("late"))).forEach {
                completion ->
            val client = FakeSftpSession(ignoreCancellation = true)
            val controller = controller { client }
            controller.connect("Old", config("old.example"))
            runCurrent()

            controller.close()
            client.complete(completion)
            runCurrent()

            assertSame(SftpUiState.Closed, controller.state.value)
        }
    }

    @Test
    fun cooperativeCancellationIsNeverPresentedAsFailure() = runTest {
        val first = FakeSftpSession()
        val second = FakeSftpSession(completedPath = "/new")
        val clients = ArrayDeque(listOf(first, second))
        val controller = controller { clients.removeFirst() }

        controller.connect("Old", config("old.example"))
        runCurrent()
        controller.connect("New", config("new.example"))
        runCurrent()

        assertEquals(
            SftpUiState.Browsing("New", "/new", emptyList()),
            controller.state.value,
        )
    }

    @Test
    fun staleKeyboardInteractiveAnswerKeepsTheCurrentChallenge() = runTest {
        val firstChallenge = challenge(1L, "First")
        val currentChallenge = challenge(2L, "Current")
        val client = FakeSftpSession(
            initialKeyboardChallenges = listOf(firstChallenge, currentChallenge),
            acceptedKeyboardToken = currentChallenge.challengeToken,
        )
        val controller = controller { client }
        controller.connect("Server", config("server.example"))
        runCurrent()

        val staleResponse = "stale".toCharArray()
        controller.answerKeyboardInteractive(firstChallenge.challengeToken, listOf(staleResponse))

        assertEquals(
            currentChallenge,
            (controller.state.value as SftpUiState.Connecting).keyboardInteractiveChallenge,
        )
        assertTrue(staleResponse.all { it == '\u0000' })

        controller.answerKeyboardInteractive(
            currentChallenge.challengeToken,
            listOf("current".toCharArray()),
        )
        assertEquals(
            null,
            (controller.state.value as SftpUiState.Connecting).keyboardInteractiveChallenge,
        )
        controller.close()
    }

    @Test
    fun newerChallengePublishedDuringAcceptedAnswerIsNotDismissed() = runTest {
        val answered = challenge(3L, "Answered")
        val newer = challenge(4L, "Newer")
        val client = FakeSftpSession(
            initialKeyboardChallenges = listOf(answered),
            acceptedKeyboardToken = answered.challengeToken,
        )
        val controller = controller { client }
        controller.connect("Server", config("server.example"))
        runCurrent()
        client.beforeAcceptedKeyboardAnswer = { client.publishKeyboardChallenge(newer) }

        controller.answerKeyboardInteractive(
            answered.challengeToken,
            listOf("answer".toCharArray()),
        )

        assertEquals(
            newer,
            (controller.state.value as SftpUiState.Connecting).keyboardInteractiveChallenge,
        )
        controller.close()
    }

    @Test
    fun missingClientWipesKeyboardInteractiveResponses() = runTest {
        val controller = controller { error("No client expected") }
        val first = "one".toCharArray()
        val second = "two".toCharArray()

        controller.answerKeyboardInteractive(1L, listOf(first, second))

        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        factory: () -> SftpSession,
    ): SftpSessionController = SftpSessionController(
        scope = this,
        operationContext = StandardTestDispatcher(testScheduler),
        newClient = factory,
    )

    private fun config(host: String) = SshConnectionConfig(
        host = host,
        port = 22,
        username = "tester",
        authentication = SshAuthentication.Password(byteArrayOf(1)),
    )

    private fun challenge(token: Long, prompt: String) = KeyboardInteractiveChallenge(
        challengeToken = token,
        name = "Authentication",
        instruction = "",
        questions = listOf(KeyboardInteractiveQuestion(prompt, echo = false)),
    )
}

private class FakeSftpSession(
    private val ignoreCancellation: Boolean = false,
    completedPath: String? = null,
    private val promptAfterGate: HostIdentityPrompt? = null,
    private val initialKeyboardChallenges: List<KeyboardInteractiveChallenge> = emptyList(),
    private val acceptedKeyboardToken: Long? = null,
) : SftpSession {
    private val connection = CompletableDeferred<Result<String>>()
    var closed = false
        private set
    var beforeAcceptedKeyboardAnswer: (() -> Unit)? = null
    private var keyboardChallengeCallback: ((KeyboardInteractiveChallenge) -> Unit)? = null

    init {
        completedPath?.let { connection.complete(Result.success(it)) }
    }

    fun complete(path: String) = complete(Result.success(path))

    fun fail(error: Throwable) = complete(Result.failure(error))

    fun complete(result: Result<String>) {
        connection.complete(result)
    }

    fun publishKeyboardChallenge(challenge: KeyboardInteractiveChallenge) {
        requireNotNull(keyboardChallengeCallback)(challenge)
    }

    override suspend fun connect(
        config: SshConnectionConfig,
        onHostIdentityPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
    ): String {
        keyboardChallengeCallback = onKeyboardInteractiveChallenge
        initialKeyboardChallenges.forEach(onKeyboardInteractiveChallenge)
        val result = if (ignoreCancellation) {
            withContext(NonCancellable) { connection.await() }
        } else {
            connection.await()
        }
        promptAfterGate?.let(onHostIdentityPrompt)
        return result.getOrThrow()
    }

    override fun list(path: String): List<SftpFile> = emptyList()
    override fun createDirectory(parent: String, name: String) = Unit
    override fun rename(path: String, newName: String) = Unit
    override suspend fun delete(path: String) = Unit
    override fun move(path: String, destinationDirectory: String) = Unit
    override suspend fun copy(path: String, destinationDirectory: String) = Unit
    override fun upload(parent: String, name: String, source: InputStream) = Unit
    override fun download(path: String, destination: OutputStream) = Unit
    override suspend fun downloadRecursively(
        path: String,
        destination: SftpDownloadDestination,
    ) = Unit
    override suspend fun uploadRecursively(parent: String, source: SftpUploadSource) = Unit

    override fun answerHostIdentityPrompt(token: Long, decision: HostIdentityDecision) = Unit
    override fun answerKeyboardInteractive(token: Long, responses: List<CharArray>): Boolean {
        val accepted = acceptedKeyboardToken == null || token == acceptedKeyboardToken
        if (accepted) {
            beforeAcceptedKeyboardAnswer?.invoke()
        } else {
            responses.forEach { response -> response.fill('\u0000') }
        }
        return accepted
    }
    override fun cancelKeyboardInteractive(token: Long) = true

    override fun close() {
        closed = true
    }
}
