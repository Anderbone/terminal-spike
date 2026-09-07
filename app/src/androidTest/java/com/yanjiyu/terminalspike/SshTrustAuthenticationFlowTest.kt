package com.yanjiyu.terminalspike

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveQuestion
import com.yanjiyu.terminalspike.connection.RemoteSessionConnectionRequest
import com.yanjiyu.terminalspike.connection.RemoteSessionStartRequest
import com.yanjiyu.terminalspike.connection.SessionForegroundStartResult
import com.yanjiyu.terminalspike.connection.SessionNotificationVisibility
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.SshConnectionFactory
import com.yanjiyu.terminalspike.connection.SshSessionRepository
import com.yanjiyu.terminalspike.connection.SshSessionTerminal
import com.yanjiyu.terminalspike.connection.SshSessionTerminalFactory
import com.yanjiyu.terminalspike.connection.StartSshSessionResult
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest
import com.yanjiyu.terminalspike.ui.KeyboardInteractiveDialogTestTag
import com.yanjiyu.terminalspike.ui.KeyboardInteractiveFieldTestTagPrefix
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi
import com.yanjiyu.terminalspike.ui.prepareHostConnectionTest
import com.yanjiyu.terminalspike.ui.connections.ConnectionsEditorCatalog
import com.yanjiyu.terminalspike.ui.connections.ConnectionTestStage
import com.yanjiyu.terminalspike.ui.connections.HostAuthenticationMethod
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestCoordinator
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestInteraction
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestOperationRunner
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestPromptOwner
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestResult
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestUiState
import com.yanjiyu.terminalspike.ui.connections.HostEditorDraft
import com.yanjiyu.terminalspike.ui.connections.HostEditorDialog
import com.yanjiyu.terminalspike.ui.connections.HostEditorSubmission
import com.yanjiyu.terminalspike.ui.connections.HostEditorHostnameTestTag
import com.yanjiyu.terminalspike.ui.connections.ValidatedHostEditor
import com.yanjiyu.terminalspike.ui.connections.HostEditorTestConnectionTestTag
import com.yanjiyu.terminalspike.ui.connections.HostTestKeyboardInteractiveDialogTestTag
import com.yanjiyu.terminalspike.ui.connections.HostTestKeyboardInteractiveFieldTestTagPrefix
import com.yanjiyu.terminalspike.ui.connections.CatalogProfileOption
import com.yanjiyu.terminalspike.ui.connections.PreparedHostConnectionTest
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SshTrustAuthenticationFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<SshTrustAuthenticationTestActivity>()

    @After
    fun clearTestContent() {
        composeRule.runOnIdle { SshTrustAuthenticationTestContent.content.value = null }
    }

    @Test
    fun firstContactOffersTrustOnceTrustAndSaveAndCancel() {
        val prompt = HostIdentityPrompt.FirstContact(
            endpoint = "[server.example]:2222",
            algorithm = "ssh-ed25519",
            newFingerprint = "SHA256:new-fingerprint",
            promptToken = 101L,
        )
        var answer: Triple<Long, Long, HostIdentityDecision>? = null
        showPrompt(
            prompt = prompt,
            onHostAnswer = { sessionId, token, decision ->
                answer = Triple(sessionId, token, decision)
            },
        )

        composeRule.onNodeWithText("Trust once").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(Triple(9L, 101L, HostIdentityDecision.TrustOnce), answer)
        }
    }

    @Test
    fun changedKeyRequiresReviewBeforeReplaceSavedKey() {
        val prompt = HostIdentityPrompt.Changed(
            endpoint = "server.example",
            algorithm = "ssh-ed25519",
            previousFingerprint = "SHA256:previous-fingerprint",
            newFingerprint = "SHA256:new-fingerprint",
            promptToken = 102L,
        )
        var answer: Triple<Long, Long, HostIdentityDecision>? = null
        showPrompt(
            prompt = prompt,
            onHostAnswer = { sessionId, token, decision ->
                answer = Triple(sessionId, token, decision)
            },
        )

        composeRule.onNodeWithText("Warning: SSH host key changed").assertIsDisplayed()
        composeRule.onNodeWithText("Replace saved key").assertDoesNotExist()
        composeRule.onNodeWithText("Review replacement").performClick()
        composeRule.runOnIdle { assertNull(answer) }
        composeRule.onNodeWithText("Replace saved key").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(Triple(9L, 102L, HostIdentityDecision.ReplaceSavedKey), answer)
        }
    }

    @Test
    fun mixedEchoChallengeTransfersMutableResponsesToExactSessionAndToken() {
        val challenge = challenge(token = 201L)
        var answerSession: Long? = null
        var answerToken: Long? = null
        var answerResponses: List<CharArray>? = null
        showPrompt(
            prompt = challenge,
            onKeyboardAnswer = { sessionId, token, responses ->
                answerSession = sessionId
                answerToken = token
                answerResponses = responses
            },
        )

        composeRule.onNodeWithTag(KeyboardInteractiveDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Visible response").assertIsDisplayed()
        composeRule.onNodeWithText("Hidden response").assertIsDisplayed()
        repeat(2) { index ->
            onView(withTagValue(equalTo("$KeyboardInteractiveFieldTestTagPrefix$index")))
                .check { view, noViewFoundException ->
                    if (noViewFoundException != null) throw noViewFoundException
                    val field = requireNotNull(view as? EditText)
                    assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                    assertTrue(
                        field.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
                    )
                    assertEquals(
                        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                        field.importantForAutofill,
                    )
                }
        }
        onView(withTagValue(equalTo("${KeyboardInteractiveFieldTestTagPrefix}0")))
            .perform(replaceText("alice"))
        onView(withTagValue(equalTo("${KeyboardInteractiveFieldTestTagPrefix}1")))
            .perform(replaceText("123456"))
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.runOnIdle {
            assertEquals(9L, answerSession)
            assertEquals(201L, answerToken)
            assertEquals(
                listOf("alice", "123456"),
                answerResponses?.map { response -> response.concatToString() },
            )
            answerResponses?.forEach { response -> response.fill('\u0000') }
        }
    }

    @Test
    fun testConnectionFirstContactRoutesTypedDecisionAndExactOperationToken() {
        val operationToken = 301L
        val prompt = HostIdentityPrompt.FirstContact(
            endpoint = "[test.example]:2222",
            algorithm = "ssh-ed25519",
            newFingerprint = "SHA256:complete-test-fingerprint",
            promptToken = 302L,
        )
        val state = mutableStateOf<HostConnectionTestUiState>(HostConnectionTestUiState.Idle)
        var answer: Triple<Long, Long, HostIdentityDecision>? = null
        showHostTestEditor(
            state = state,
            onTest = {
                state.value = HostConnectionTestUiState.AwaitingHostIdentity(operationToken, prompt)
                operationToken
            },
            onHostAnswer = { operation, token, decision ->
                answer = Triple(operation, token, decision)
                state.value = HostConnectionTestUiState.Running(operation)
                true
            },
        )

        composeRule.onNodeWithTag(HostEditorTestConnectionTestTag).performClick()
        composeRule.onNodeWithText("SHA256:complete-test-fingerprint", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Trust and save").assertIsDisplayed()
        composeRule.onNodeWithText("Trust once").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(
                Triple(operationToken, prompt.promptToken, HostIdentityDecision.TrustOnce),
                answer,
            )
        }
    }

    @Test
    fun testConnectionChangedKeyRequiresTwoStagesBeforeExactReplacement() {
        val operationToken = 303L
        val prompt = HostIdentityPrompt.Changed(
            endpoint = "test.example",
            algorithm = "ssh-ed25519",
            previousFingerprint = "SHA256:complete-previous",
            newFingerprint = "SHA256:complete-offered",
            promptToken = 304L,
        )
        val state = mutableStateOf<HostConnectionTestUiState>(HostConnectionTestUiState.Idle)
        var answer: Triple<Long, Long, HostIdentityDecision>? = null
        showHostTestEditor(
            state = state,
            onTest = {
                state.value = HostConnectionTestUiState.AwaitingHostIdentity(operationToken, prompt)
                operationToken
            },
            onHostAnswer = { operation, token, decision ->
                answer = Triple(operation, token, decision)
                true
            },
        )

        composeRule.onNodeWithTag(HostEditorTestConnectionTestTag).performClick()
        composeRule.onNodeWithText("SHA256:complete-previous", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("SHA256:complete-offered", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Replace saved key").assertDoesNotExist()
        composeRule.onNodeWithText("Review replacement").performClick()
        composeRule.runOnIdle { assertNull(answer) }
        composeRule.onNodeWithText("Replace saved key").performClick()

        composeRule.runOnIdle {
            assertEquals(
                Triple(operationToken, prompt.promptToken, HostIdentityDecision.ReplaceSavedKey),
                answer,
            )
        }
    }

    @Test
    fun testConnectionFreezesTestedDraftAndMutationInvalidatesCompletedResult() {
        val operationToken = 305L
        val state = mutableStateOf<HostConnectionTestUiState>(HostConnectionTestUiState.Idle)
        var cancelledOperation: Long? = null
        showHostTestEditor(
            state = state,
            onTest = {
                state.value = HostConnectionTestUiState.Running(operationToken)
                operationToken
            },
            onCancelTest = { token ->
                cancelledOperation = token
                if (state.value.operationToken == token) {
                    state.value = HostConnectionTestUiState.Idle
                }
            },
        )

        composeRule.onNodeWithTag(HostEditorTestConnectionTestTag).performClick()
        composeRule.onNodeWithTag(HostEditorHostnameTestTag).assertIsNotEnabled()
        composeRule.runOnIdle {
            state.value = HostConnectionTestUiState.Complete(
                operationToken = operationToken,
                result = HostConnectionTestResult.Success(ConnectionTestStage.entries.toSet()),
            )
        }
        composeRule.onNodeWithTag(HostEditorHostnameTestTag)
            .assertIsEnabled()
            .performTextReplacement("changed.example")

        composeRule.runOnIdle {
            assertEquals(operationToken, cancelledOperation)
            assertEquals(HostConnectionTestUiState.Idle, state.value)
        }
    }

    @Test
    fun testConnectionKbiUsesLiveCoordinatorAcrossRecreationAndReplacement() {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runner = LiveHostTestRunner()
        val coordinator = HostConnectionTestCoordinator(
            scope = ownerScope,
            dispatcher = Dispatchers.Main.immediate,
            runner = runner,
        )
        try {
            showLiveHostTestEditor(coordinator)

            composeRule.onNodeWithTag(HostEditorTestConnectionTestTag).performClick()
            composeRule.onNodeWithTag(HostTestKeyboardInteractiveDialogTestTag).assertIsDisplayed()
            val firstState = coordinator.state.value as
                HostConnectionTestUiState.AwaitingKeyboardInteractive

            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HostTestKeyboardInteractiveDialogTestTag).assertIsDisplayed()

            val staleResponse = charArrayOf('s', 't', 'a', 'l', 'e')
            composeRule.runOnIdle {
                assertFalse(
                    coordinator.answerKeyboardInteractive(
                        operationToken = firstState.operationToken + 99,
                        challengeToken = firstState.challenge.challengeToken,
                        responses = listOf(staleResponse),
                    ),
                )
                assertTrue(staleResponse.all { it == '\u0000' })
                assertEquals(firstState.operationToken, coordinator.state.value.operationToken)
            }

            onView(withTagValue(equalTo("${HostTestKeyboardInteractiveFieldTestTagPrefix}0")))
                .perform(replaceText("alice"))
            onView(withTagValue(equalTo("${HostTestKeyboardInteractiveFieldTestTagPrefix}1")))
                .perform(replaceText("654321"))
            composeRule.onNodeWithText("Continue").performClick()
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertEquals(listOf("alice", "654321"), runner.operations.first().answerText)
                assertTrue(coordinator.state.value is HostConnectionTestUiState.Complete)
            }

            // Start a second operation through the recreated editor, then replace that exact live
            // owner. A retained response for the replaced token must be destroyed and rejected.
            composeRule.onNodeWithTag(HostEditorTestConnectionTestTag).performClick()
            composeRule.onNodeWithTag(HostTestKeyboardInteractiveDialogTestTag).assertIsDisplayed()
            val secondState = coordinator.state.value as
                HostConnectionTestUiState.AwaitingKeyboardInteractive
            val secondOwner = runner.operations.last()
            val replacementSecret = byteArrayOf(9, 8, 7)
            composeRule.runOnIdle {
                coordinator.start(
                    PreparedHostConnectionTest(secondOwner.value, replacementSecret),
                )
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertTrue(secondOwner.closed) }

            val replacedResponse = charArrayOf('o', 'l', 'd')
            composeRule.runOnIdle {
                assertFalse(
                    coordinator.answerKeyboardInteractive(
                        operationToken = secondState.operationToken,
                        challengeToken = secondState.challenge.challengeToken,
                        responses = listOf(replacedResponse),
                    ),
                )
                assertTrue(replacedResponse.all { it == '\u0000' })
                assertTrue(coordinator.state.value.operationToken != secondState.operationToken)
                coordinator.cancel()
                assertTrue(replacementSecret.all { it == 0.toByte() })
            }
        } finally {
            coordinator.close()
            ownerScope.cancel()
        }
    }

    @Test
    fun recreationTargetsRepositoryOwnedTransportAndReplacementRejectsStaleResponse() {
        val firstChallenge = challenge(token = 202L)
        val secondChallenge = challenge(token = 204L)
        val firstConnection = PendingChallengeConnection(firstChallenge)
        val secondConnection = PendingChallengeConnection(secondChallenge)
        val connections = ArrayDeque(listOf(firstConnection, secondConnection))
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = SshConnectionFactory { connections.removeFirst() },
            terminalFactory = SshSessionTerminalFactory { TestSessionTerminal() },
        )

        try {
            val started = repository.startUserInitiatedSession("server", testConfig())
                as StartSshSessionResult.Started
            installTestContent {
                val snapshots by repository.sessions.collectAsState()
                MaterialTheme {
                    SessionChrome(
                        sessions = snapshots.map { snapshot ->
                            SessionTabUi(
                                id = snapshot.id,
                                title = snapshot.title,
                                connectionState = snapshot.connectionState,
                            )
                        },
                        activeSessionId = started.sessionId,
                        notice = null,
                        canAddSession = false,
                        settingsReady = true,
                        onSelectSession = {},
                        onDuplicateSession = {},
                        onCloseSession = {},
                        onDisconnect = {},
                        onHostIdentityAnswer = { sessionId, token, decision ->
                            repository.answerHostIdentityPrompt(sessionId, token, decision)
                        },
                        onNavigateBack = {},
                        onNewSession = {},
                        onOpenConnections = {},
                        onKeyboardInteractiveAnswer =
                            repository::answerKeyboardInteractiveChallenge,
                        onKeyboardInteractiveCancel =
                            repository::cancelKeyboardInteractiveChallenge,
                    )
                }
            }
            composeRule.waitUntil {
                repository.sessions.value.singleOrNull()?.connectionState ==
                    ConnectionState.AwaitingApproval(firstChallenge)
            }
            onView(withTagValue(equalTo("${KeyboardInteractiveFieldTestTagPrefix}1")))
                .perform(replaceText("must-be-dropped"))
            closeSoftKeyboard()

            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            onView(withTagValue(equalTo("${KeyboardInteractiveFieldTestTagPrefix}1")))
                .inRoot(isDialog())
                .perform(replaceText("fresh-response"))
            composeRule.onNodeWithText("Continue").performClick()

            composeRule.runOnIdle {
                assertEquals(1, firstConnection.acceptedResponses.get())
                assertEquals(0, secondConnection.acceptedResponses.get())
                assertEquals(
                    listOf("", "fresh-response"),
                    firstConnection.lastAcceptedResponses.get(),
                )
            }

            repository.disconnect(started.sessionId)
            val replacement = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "server",
                    workspaceName = "server",
                    connection = RemoteSessionConnectionRequest.Ssh(testConfig()),
                    replacementSessionId = started.sessionId,
                ),
            ) as StartSshSessionResult.Started
            assertEquals(started.sessionId, replacement.sessionId)
            composeRule.waitUntil {
                repository.sessions.value.singleOrNull()?.connectionState ==
                    ConnectionState.AwaitingApproval(secondChallenge)
            }

            val staleResponse = "stale-response".toCharArray()
            repository.answerKeyboardInteractiveChallenge(
                sessionId = started.sessionId,
                challengeToken = firstChallenge.challengeToken,
                responses = listOf(staleResponse),
            )

            composeRule.runOnIdle {
                assertEquals(1, secondConnection.rejectedResponses.get())
                assertEquals(0, secondConnection.acceptedResponses.get())
                assertTrue(staleResponse.all { it == '\u0000' })
            }
        } finally {
            composeRule.runOnIdle { SshTrustAuthenticationTestContent.content.value = null }
            repository.sessions.value.forEach { repository.close(it.id) }
            ownerScope.cancel()
        }
    }

    @Test
    fun eightPromptChallengeKeepsActionsReachableAtLargeTextScale() {
        val challenge = KeyboardInteractiveChallenge(
            challengeToken = 203L,
            name = "Multi-step authentication",
            instruction = "Review and answer each bounded server prompt.",
            questions = List(8) { index ->
                KeyboardInteractiveQuestion("Question ${index + 1}", echo = index % 2 == 0)
            },
        )
        installTestContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 640.dp)) {
                        SessionChrome(
                            sessions = listOf(session(challenge)),
                            activeSessionId = 9L,
                            notice = null,
                            canAddSession = false,
                            settingsReady = true,
                            onSelectSession = {},
                            onDuplicateSession = {},
                            onCloseSession = {},
                            onDisconnect = {},
                            onHostIdentityAnswer = { _, _, _ -> },
                            onNavigateBack = {},
                            onNewSession = {},
                            onOpenConnections = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Question 8").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun showPrompt(
        prompt: com.yanjiyu.terminalspike.connection.ConnectionPrompt,
        onHostAnswer: (Long, Long, HostIdentityDecision) -> Unit = { _, _, _ -> },
        onKeyboardAnswer: (Long, Long, List<CharArray>) -> Unit = { _, _, responses ->
            responses.forEach { it.fill('\u0000') }
        },
    ) {
        installTestContent {
            MaterialTheme {
                SessionChrome(
                    sessions = listOf(session(prompt)),
                    activeSessionId = 9L,
                    notice = null,
                    canAddSession = false,
                    settingsReady = true,
                    onSelectSession = {},
                    onDuplicateSession = {},
                    onCloseSession = {},
                    onDisconnect = {},
                    onHostIdentityAnswer = onHostAnswer,
                    onNavigateBack = {},
                    onNewSession = {},
                    onOpenConnections = {},
                    onKeyboardInteractiveAnswer = onKeyboardAnswer,
                )
            }
        }
    }

    private fun showHostTestEditor(
        state: MutableState<HostConnectionTestUiState>,
        onTest: (HostEditorSubmission) -> Long,
        onHostAnswer: (Long, Long, HostIdentityDecision) -> Boolean = { _, _, _ -> false },
        onKeyboardAnswer: (Long, Long, List<CharArray>) -> Boolean =
            { _, _, responses ->
                responses.forEach { it.fill('\u0000') }
                false
            },
        onCancelTest: (Long?) -> Unit = {},
    ) {
        installTestContent {
            MaterialTheme {
                HostEditorDialog(
                    initial = HostEditorDraft(
                        displayName = "Test host",
                        hostname = "test.example",
                        port = "22",
                        username = "alice",
                        authenticationMethod = HostAuthenticationMethod.KEYBOARD_INTERACTIVE,
                        terminalProfileId = "terminal-default",
                    ),
                    savedSecretAvailable = false,
                    catalog = ConnectionsEditorCatalog(
                        terminalProfiles = listOf(
                            CatalogProfileOption("terminal-default", "Default terminal"),
                        ),
                        defaultTerminalProfileId = "terminal-default",
                    ),
                    moshAvailable = false,
                    onDismiss = {},
                    onSave = { submission ->
                        submission.wipe()
                        Result.success(Unit)
                    },
                    onTest = { submission ->
                        try {
                            onTest(submission)
                        } finally {
                            submission.wipe()
                        }
                    },
                    testState = state.value,
                    onAnswerHostIdentity = onHostAnswer,
                    onAnswerKeyboardInteractive = onKeyboardAnswer,
                    onCancelKeyboardInteractive = { operation, _ ->
                        state.value = HostConnectionTestUiState.Running(operation)
                        true
                    },
                    onCancelTest = onCancelTest,
                    onOpenMoshStatus = null,
                )
            }
        }
    }

    private fun showLiveHostTestEditor(coordinator: HostConnectionTestCoordinator) {
        installTestContent {
            val testState by coordinator.state.collectAsState()
            MaterialTheme {
                HostEditorDialog(
                    initial = HostEditorDraft(
                        displayName = "Test host",
                        hostname = "test.example",
                        port = "22",
                        username = "alice",
                        authenticationMethod = HostAuthenticationMethod.KEYBOARD_INTERACTIVE,
                        terminalProfileId = "terminal-default",
                    ),
                    savedSecretAvailable = false,
                    catalog = ConnectionsEditorCatalog(
                        terminalProfiles = listOf(
                            CatalogProfileOption("terminal-default", "Default terminal"),
                        ),
                        defaultTerminalProfileId = "terminal-default",
                    ),
                    moshAvailable = false,
                    onDismiss = {},
                    onSave = { submission ->
                        submission.wipe()
                        Result.success(Unit)
                    },
                    onTest = { submission ->
                        val prepared = submission.prepareHostConnectionTest()
                        var ownershipTransferred = false
                        try {
                            coordinator.start(prepared).also { ownershipTransferred = true }
                        } finally {
                            if (!ownershipTransferred) prepared.wipeSecret()
                        }
                    },
                    testState = testState,
                    onAnswerHostIdentity = coordinator::answerHostIdentity,
                    onAnswerKeyboardInteractive = coordinator::answerKeyboardInteractive,
                    onCancelKeyboardInteractive = coordinator::cancelKeyboardInteractive,
                    onCancelTest = coordinator::cancel,
                    onOpenMoshStatus = null,
                )
            }
        }
    }

    private fun installTestContent(content: @Composable () -> Unit) {
        composeRule.runOnIdle { SshTrustAuthenticationTestContent.content.value = content }
        composeRule.waitForIdle()
    }

    private fun session(prompt: com.yanjiyu.terminalspike.connection.ConnectionPrompt) =
        SessionTabUi(
            id = 9L,
            title = "server",
            connectionState = ConnectionState.AwaitingApproval(prompt),
        )

    private fun challenge(token: Long) = KeyboardInteractiveChallenge(
        challengeToken = token,
        name = "Two-factor authentication",
        instruction = "Enter your account and current code.",
        questions = listOf(
            KeyboardInteractiveQuestion("Account", echo = true),
            KeyboardInteractiveQuestion("One-time code", echo = false),
        ),
    )

    private fun testConfig() = SshConnectionConfig(
        host = "server.example",
        port = 22,
        username = "tester",
        authentication = SshAuthentication.KeyboardInteractive.SessionOnly(),
    )

    private class LiveHostTestRunner : HostConnectionTestOperationRunner {
        val operations = mutableListOf<LiveHostTestOperation>()
        private var nextChallengeToken = 700L

        override suspend fun run(
            prepared: PreparedHostConnectionTest,
            interaction: HostConnectionTestInteraction,
        ): HostConnectionTestResult {
            val operation = LiveHostTestOperation(
                value = prepared.value,
                challenge = KeyboardInteractiveChallenge(
                    challengeToken = ++nextChallengeToken,
                    name = "Two-factor authentication",
                    instruction = "Enter your account and current code.",
                    questions = listOf(
                        KeyboardInteractiveQuestion("Account", echo = true),
                        KeyboardInteractiveQuestion("One-time code", echo = false),
                    ),
                ),
            )
            operations += operation
            check(interaction.registerPromptOwner(operation))
            interaction.showKeyboardInteractiveChallenge(operation.challenge)
            return operation.awaitResult()
        }
    }

    private class LiveHostTestOperation(
        val value: ValidatedHostEditor,
        val challenge: KeyboardInteractiveChallenge,
    ) : HostConnectionTestPromptOwner {
        private val result = CompletableDeferred<HostConnectionTestResult>()
        var answerText: List<String>? = null
        var closed = false

        suspend fun awaitResult(): HostConnectionTestResult = result.await()

        override fun answerHostIdentity(
            promptToken: Long,
            decision: HostIdentityDecision,
        ): Boolean = false

        override fun answerKeyboardInteractive(
            challengeToken: Long,
            responses: List<CharArray>,
        ): Boolean {
            val accepted = !closed && challengeToken == challenge.challengeToken
            if (accepted) answerText = responses.map(CharArray::concatToString)
            responses.forEach { it.fill('\u0000') }
            if (accepted) {
                result.complete(
                    HostConnectionTestResult.Success(ConnectionTestStage.entries.toSet()),
                )
            }
            return accepted
        }

        override fun cancelKeyboardInteractive(challengeToken: Long): Boolean {
            if (closed || challengeToken != challenge.challengeToken) return false
            result.complete(
                HostConnectionTestResult.Failed(
                    stage = ConnectionTestStage.AUTHENTICATION,
                    message = "Authentication cancelled.",
                    completedStages = emptySet(),
                ),
            )
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            result.complete(
                HostConnectionTestResult.Failed(
                    stage = ConnectionTestStage.AUTHENTICATION,
                    message = "Connection test closed.",
                    completedStages = emptySet(),
                ),
            )
        }
    }

    private class PendingChallengeConnection(
        private val challenge: KeyboardInteractiveChallenge,
    ) : Connection {
        private val closed = CompletableDeferred<Unit>()
        val acceptedResponses = AtomicInteger()
        val rejectedResponses = AtomicInteger()
        val lastAcceptedResponses = AtomicReference<List<String>>()

        override suspend fun connect(
            columns: Int,
            rows: Int,
            onBytes: (ByteArray) -> Unit,
            onState: (ConnectionState) -> Unit,
        ) {
            onState(ConnectionState.Connecting)
            onState(ConnectionState.AwaitingApproval(challenge))
            closed.await()
        }

        override fun send(bytes: ByteArray) = Unit

        override fun trySend(bytes: ByteArray): Boolean = false

        override fun resize(columns: Int, rows: Int) = Unit

        override fun answerHostIdentityPrompt(
            promptToken: Long,
            decision: HostIdentityDecision,
        ) = Unit

        override fun answerKeyboardInteractiveChallenge(
            challengeToken: Long,
            responses: List<CharArray>,
        ) {
            if (challengeToken == challenge.challengeToken && !closed.isCompleted) {
                acceptedResponses.incrementAndGet()
                lastAcceptedResponses.set(responses.map(CharArray::concatToString))
            } else {
                rejectedResponses.incrementAndGet()
            }
            responses.forEach { it.fill('\u0000') }
        }

        override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit

        override fun cancelPendingPrompts() = Unit

        override fun close() {
            closed.complete(Unit)
        }
    }

    private class TestSessionTerminal : SshSessionTerminal {
        override val controller = null
        override val columns: Int = 80
        override val rows: Int = 24

        override fun attach(connection: Connection) = Unit

        override fun accept(bytes: ByteArray, sendResponse: (ByteArray) -> Unit) = Unit

        override fun accept(
            bytes: ByteArray,
            sendResponse: (ByteArray) -> Unit,
            onRemoteClipboardRequest: (TerminalRemoteClipboardRequest) -> Unit,
        ) = Unit

        override fun stopAndClear() = Unit
    }
}
