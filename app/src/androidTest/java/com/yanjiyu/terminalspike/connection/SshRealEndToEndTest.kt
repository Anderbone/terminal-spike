package com.yanjiyu.terminalspike.connection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.backup.LocalBackupDocumentsProvider
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.terminal.view.TerminalScrollDecisionReason
import com.yanjiyu.terminalspike.terminal.view.TerminalScrollDestination
import com.yanjiyu.terminalspike.terminal.view.TmuxScrollGestureDiagnostic
import com.yanjiyu.terminalspike.ui.TerminalSpikeViewModel
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.HostAuthenticationMethod
import com.yanjiyu.terminalspike.ui.connections.HostConnectRequest
import com.yanjiyu.terminalspike.ui.connections.HostEditorDraft
import com.yanjiyu.terminalspike.ui.connections.HostEditorSubmission
import com.yanjiyu.terminalspike.ui.connections.validateHostEditor
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
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
    @Test(timeout = SAVED_KEY_TEST_TIMEOUT_MILLIS)
    fun importedPrivateKeySelectedBySavedHostAuthenticatesToRealServer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PRIVATE_KEY_BASE64 to run the saved-key E2E.",
            host.isNotBlank() && username.isNotBlank() && privateKeyBytes != null,
        )
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val application = targetContext.applicationContext as TerminalSpikeApplication
        val sessionRepository = application.container.sshSessionRepository
        val terminalData = application.container.terminalDataRepository
        sessionRepository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var viewModel: TerminalSpikeViewModel? = null
        var importedIdentityId: String? = null
        var savedHostId: String? = null
        var startedSessionId: Long? = null
        var keyDocument: android.net.Uri? = null

        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[TerminalSpikeViewModel::class.java]
            }
            val model = requireNotNull(viewModel)
            withTimeout(APP_UI_TIMEOUT_MILLIS) {
                while (!model.uiState.value.settingsReady) delay(25L)
                while (model.connectionsUiState.value.loadState !is ConnectionsLoadState.Ready) {
                    delay(25L)
                }
            }
            val identitiesBefore = (model.connectionsUiState.value.loadState as ConnectionsLoadState.Ready)
                .keys
                .mapTo(mutableSetOf()) { it.id }
            val root = DocumentsContract.buildDocumentUri(
                LocalBackupDocumentsProvider.AUTHORITY,
                LocalBackupDocumentsProvider.ROOT_DOCUMENT_ID,
            )
            keyDocument = requireNotNull(
                DocumentsContract.createDocument(
                    targetContext.contentResolver,
                    root,
                    LocalBackupDocumentsProvider.PRIVATE_KEY_MIME_TYPE,
                    "real-saved-key${LocalBackupDocumentsProvider.PRIVATE_KEY_FILE_EXTENSION}",
                ),
            )
            requireNotNull(targetContext.contentResolver.openOutputStream(keyDocument, "w")).use {
                it.write(requireNotNull(privateKeyBytes))
            }
            scenario.onActivity { model.importSshIdentity(requireNotNull(keyDocument)) }

            val importedKey = withTimeout(APP_UI_TIMEOUT_MILLIS) {
                while (true) {
                    val ready = model.connectionsUiState.value.loadState as? ConnectionsLoadState.Ready
                    val key = ready?.keys?.singleOrNull { it.id !in identitiesBefore }
                    if (key != null) return@withTimeout key
                    delay(25L)
                }
                error("unreachable")
            }
            importedIdentityId = importedKey.id
            assertTrue(importedKey.algorithm.contains("ed25519", ignoreCase = true))

            val validation = validateHostEditor(
                HostEditorDraft(
                    displayName = SAVED_KEY_HOST_NAME,
                    protocol = ConnectionProtocol.SSH,
                    hostname = host,
                    port = port.toString(),
                    username = username,
                    authenticationMethod = HostAuthenticationMethod.PRIVATE_KEY,
                    keyIdentityId = importedKey.id,
                ),
                availableKeyIds = setOf(importedKey.id),
            )
            assertTrue("Saved-host validation failed: ${validation.errors}", validation.errors.isEmpty)
            val saveResult = model.saveConnectionsHost(
                HostEditorSubmission(
                    value = requireNotNull(validation.value),
                    secret = CharArray(0),
                    savePassword = false,
                ),
            )
            assertTrue("The private-key host was not saved: $saveResult", saveResult.isSuccess)
            val hostRow = withTimeout(APP_UI_TIMEOUT_MILLIS) {
                while (true) {
                    val ready = model.connectionsUiState.value.loadState as? ConnectionsLoadState.Ready
                    val row = ready?.hosts?.singleOrNull { it.displayName == SAVED_KEY_HOST_NAME }
                    if (row != null) return@withTimeout row
                    delay(25L)
                }
                error("unreachable")
            }
            savedHostId = hostRow.id

            var accepted = false
            scenario.onActivity {
                accepted = model.connectConnectionsHost(HostConnectRequest(hostRow.id))
            }
            assertTrue("The saved host did not accept the connect action.", accepted)
            val sessionId = withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (sessionRepository.sessions.value.isEmpty()) delay(10L)
                sessionRepository.sessions.value.single().id
            }
            startedSessionId = sessionId
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (
                        val state = sessionRepository.sessions.value
                            .firstOrNull { it.id == sessionId }
                            ?.connectionState
                    ) {
                        ConnectionState.Connected -> break
                        is ConnectionState.AwaitingApproval -> {
                            when (val prompt = state.prompt) {
                                is HostIdentityPrompt -> sessionRepository.answerHostIdentityPrompt(
                                    sessionId,
                                    prompt.promptToken,
                                    HostIdentityDecision.TrustOnce,
                                )
                                is TmuxSessionPrompt -> sessionRepository.answerTmuxSessionPrompt(
                                    sessionId,
                                    prompt.promptToken,
                                    null,
                                )
                                else -> fail("Unexpected saved-key connection prompt: $prompt")
                            }
                        }
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected -> fail("Saved-key SSH disconnected before ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            val controller = requireNotNull(sessionRepository.controllerFor(sessionId))
            assertTrue(
                "Saved-key SSH rejected input.",
                controller.sendPaste("printf '$SAVED_KEY_MARKER\\n'", appendEnter = true),
            )
            val markerReceived = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(SAVED_KEY_MARKER) < 0) delay(10L)
                true
            }
            assertTrue(
                controller.diagnostic("Saved/imported private-key session did not return its marker."),
                markerReceived == true,
            )
        } finally {
            privateKeyBytes?.fill(0)
            startedSessionId?.let(sessionRepository::close)
            scenario.close()
            savedHostId?.let { runCatching { terminalData.deleteHostProfile(it) } }
            importedIdentityId?.let { runCatching { terminalData.deleteIdentity(it) } }
            keyDocument?.let {
                runCatching { DocumentsContract.deleteDocument(targetContext.contentResolver, it) }
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun changedSavedHostKeyIsBlockedByRealServer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PASSWORD to run the changed-key E2E.",
            host.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
        )
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val endpoint = KnownHostEndpoint.create(host, port)
        val staleKey = ByteArray(32) { index -> (index + 1).toByte() }
        val trustStore = SshE2eKnownHostTrustStore().apply {
            replaceEndpoint(endpoint, "ssh-ed25519", staleKey)
        }
        val passwordBytes = password.encodeToByteArray()
        val connection = JschSshConnection(
            knownHostManager = KnownHostManager(trustStore),
            config = SshConnectionConfig(
                host = host,
                port = port,
                username = username,
                authentication = SshAuthentication.Password(passwordBytes),
            ),
        )
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val connectionJob = async(Dispatchers.IO) {
            connection.connect(
                columns = TERMINAL_COLUMNS,
                rows = TERMINAL_ROWS,
                onBytes = {},
                onState = { state -> states.trySend(state) },
            )
        }
        var changedPromptSeen = false

        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = states.receive()) {
                        is ConnectionState.AwaitingApproval -> {
                            val prompt = state.prompt
                            assertTrue(
                                "A saved wrong key must produce the changed-key prompt.",
                                prompt is HostIdentityPrompt.Changed,
                            )
                            val changed = prompt as HostIdentityPrompt.Changed
                            assertFalse(changed.previousFingerprint == changed.newFingerprint)
                            changedPromptSeen = true
                            connection.answerHostIdentityPrompt(
                                changed.promptToken,
                                HostIdentityDecision.Reject,
                            )
                        }
                        is ConnectionState.Failed -> {
                            assertEquals("Host key changed. Connection blocked.", state.message)
                            break
                        }
                        ConnectionState.Connected ->
                            fail("A real server with a changed saved key must not connect.")
                        ConnectionState.Disconnected,
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        -> Unit
                    }
                }
            }
            assertTrue("The real server never exercised changed-key handling.", changedPromptSeen)
            val retained = trustStore.list(endpoint).single()
            assertEquals("ssh-ed25519", retained.algorithm)
            assertTrue(staleKey.contentEquals(retained.copyKey()))
        } finally {
            connection.close()
            states.close()
            if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { connectionJob.await() } == null) {
                connectionJob.cancelAndJoin()
            }
        }
        assertTrue(
            "Connection-owned password bytes must be wiped after a changed-key rejection.",
            passwordBytes.all { it == 0.toByte() },
        )
    }

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
        val engine = VtTerminalEngine(columns = TERMINAL_COLUMNS, rows = TERMINAL_ROWS)
        val completedScrollback = mutableListOf<TerminalLine>()
        var latestScreen = emptyList<TerminalLine>()
        val connectionJob = async(Dispatchers.IO) {
            connection.connect(
                columns = TERMINAL_COLUMNS,
                rows = TERMINAL_ROWS,
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
                connection.sendWithAcceptance("$COMMAND\n".encodeToByteArray()),
            )
            val collected = ByteArrayOutputStream()
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (MARKER !in collected.toString(Charsets.UTF_8.name())) {
                    val chunk = output.receive()
                    check(collected.size() + chunk.size <= MAX_CAPTURE_BYTES) {
                        "SSH fixture output exceeded its bounded capture."
                    }
                    collected.write(chunk)
                    val frame = engine.accept(chunk)
                    completedScrollback += frame.completedScrollback
                    latestScreen = frame.screen
                }
            }
            val retainedLines = (completedScrollback + latestScreen).map { it.text.trimEnd() }
            assertTrue(
                "The first of 200 real SSH PTY rows must survive in parser scrollback.",
                completedScrollback.any { it.text.trimEnd() == firstExpectedRow() },
            )
            assertTrue(
                "The last of 200 real SSH PTY rows must remain in the retained terminal frame.",
                retainedLines.any { it == lastExpectedRow() },
            )
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

    @Test(timeout = REAL_TMUX_SMOOTH_TEST_TIMEOUT_MILLIS)
    fun appSelectedTmuxActualCodexFirstGestureUsesLocalPixelScroll() = runBlocking {
        checkpoint("tmux smooth test started")
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val codexCommand = arguments.getString(ARG_CODEX_COMMAND_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let {
                android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    .toString(Charsets.UTF_8)
            }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME, $ARG_PRIVATE_KEY_BASE64 and " +
                "$ARG_CODEX_COMMAND_BASE64 to run the real tmux/Codex fixture.",
            host.isNotBlank() && username.isNotBlank() && privateKeyBytes != null &&
                codexCommand != null,
        )
        val fixturePrivateKey = requireNotNull(privateKeyBytes)
        val actualCodexCommand = requireNotNull(codexCommand)
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val application = targetContext.applicationContext as TerminalSpikeApplication
        val repository = application.container.sshSessionRepository
        repository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedSessionId: Long? = null

        try {
            val result = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Real tmux smooth scroll",
                    workspaceName = "Real tmux smooth scroll",
                    connection = RemoteSessionConnectionRequest.Ssh(
                        SshConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            authentication = SshAuthentication.PrivateKey(
                                identityName = "real-tmux-smooth-e2e",
                                loadKey = { fixturePrivateKey.copyOf() },
                                passphrase = null,
                            ),
                            tmuxSessionSelectorEnabled = true,
                        ),
                    ),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = TMUX_SMOOTH_ROW_COUNT + 256,
                    ),
                ),
            )
            val started = result as? StartSshSessionResult.Started
                ?: throw AssertionError("Production tmux session did not start: $result")
            startedSessionId = started.sessionId
            val controller = repository.controllerFor(started.sessionId)
                ?: throw AssertionError("Production tmux session did not expose its controller.")
            scenario.onActivity { activity -> activity.openTerminalSession(started.sessionId) }

            var terminalView: FastTerminalView? = null
            withTimeout(VIEW_ATTACH_TIMEOUT_MILLIS) {
                while (terminalView == null) {
                    scenario.onActivity {
                        terminalView = findFastTerminalView(it.window.decorView)
                    }
                    delay(10L)
                }
            }
            val mountedTerminal = requireNotNull(terminalView)

            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = repository.sessions.value
                        .firstOrNull { it.id == started.sessionId }
                        ?.connectionState
                    ) {
                        ConnectionState.Connected -> break
                        is ConnectionState.AwaitingApproval -> when (val prompt = state.prompt) {
                            is HostIdentityPrompt -> repository.answerHostIdentityPrompt(
                                sessionId = started.sessionId,
                                promptToken = prompt.promptToken,
                                decision = HostIdentityDecision.TrustOnce,
                            )
                            is TmuxSessionPrompt -> repository.answerTmuxSessionPrompt(
                                sessionId = started.sessionId,
                                promptToken = prompt.promptToken,
                                tmuxSessionId = TMUX_NEW_SESSION_SELECTION,
                            )
                            else -> Unit
                        }
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected ->
                            fail("SSH disconnected before app-selected tmux became ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            checkpoint("app-selected tmux connected")

            delay(TMUX_CLIENT_ATTACH_SETTLE_MILLIS)
            assertTrue(
                "Production controller rejected the 5,000-row tmux workload.",
                controller.sendPaste(TMUX_SMOOTH_ROWS_COMMAND, appendEnter = true),
            )
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(lastExpectedSmoothTmuxRow()) < 0) {
                    delay(10L)
                }
            }

            controller.updateRendererProfile(
                com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile(
                    keepViewportPositionOnOutput = false,
                ),
            )
            assertTrue(
                "Production controller rejected the real Codex startup command inside tmux.",
                controller.sendPaste(actualCodexCommand, appendEnter = true),
            )
            val codexOpened = withTimeoutOrNull(CODEX_START_TIMEOUT_MILLIS) {
                while (
                    (0 until controller.lineCount()).none { index ->
                        controller.lineAt(index)?.text?.contains("OpenAI Codex") == true
                    }
                ) {
                    delay(25L)
                }
                true
            }
            assertTrue(
                controller.diagnostic("The actual Codex TUI did not open inside app-selected tmux."),
                codexOpened == true,
            )
            val codexInputReady = withTimeoutOrNull(CODEX_START_TIMEOUT_MILLIS) {
                while (
                    (0 until controller.lineCount()).none { index ->
                        controller.lineAt(index)?.text?.contains("Ask Codex to do anything") == true
                    }
                ) {
                    delay(25L)
                }
                true
            }
            assertTrue(
                controller.diagnostic("The actual Codex input box did not become ready inside tmux."),
                codexInputReady == true,
            )
            delay(CODEX_INPUT_READY_SETTLE_MILLIS)
            assertTrue(
                "Production controller rejected the real Codex 200-row prompt.",
                controller.sendPaste(CODEX_200_LINE_PROMPT),
            )
            delay(CODEX_PASTE_SETTLE_MILLIS)
            assertTrue(
                "The mounted terminal view did not submit the real Codex prompt inside tmux.",
                dispatchEnterThroughWindow(scenario, mountedTerminal),
            )
            val codexOutputComplete = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(lastExpectedCodexRow()) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("Actual Codex inside tmux did not produce all requested rows."),
                codexOutputComplete == true,
            )
            checkpoint(
                controller.diagnostic(
                    "actual Codex output stable before first gesture; " +
                        controller.tmuxScrollDiagnostic(),
                ),
            )

            // Codex generation deliberately followed live output above. Restore the production
            // reader policy before touch so an asynchronous tmux snapshot cannot force a gesture
            // back to the new bottom merely because the fixture left follow-on-output enabled.
            controller.updateRendererProfile(
                com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile(
                    keepViewportPositionOnOutput = true,
                ),
            )
            controller.jumpToBottom()
            val firstCodexRowBeforeGesture = controller.indexOfLineContaining(firstExpectedCodexRow())
            val lastCodexRowBeforeGesture = controller.indexOfLineContaining(lastExpectedCodexRow())
            val bottomBeforeGesture = controller.viewport.visibleRows(overscan = 0)
            assertTrue(
                controller.diagnostic("The first Codex row was not retained before the first tmux gesture."),
                firstCodexRowBeforeGesture >= 0,
            )
            assertFalse(
                controller.diagnostic("The first Codex row was already visible before the first tmux gesture."),
                firstCodexRowBeforeGesture in bottomBeforeGesture.first until bottomBeforeGesture.lastExclusive,
            )
            assertTrue(
                controller.diagnostic("The last Codex row was not visible before the first tmux gesture."),
                lastCodexRowBeforeGesture in bottomBeforeGesture.first until bottomBeforeGesture.lastExclusive,
            )
            val subRow = dispatchSubRowDragThroughWindow(
                scenario = scenario,
                instrumentation = instrumentation,
                terminal = mountedTerminal,
                controller = controller,
            )
            var firstGesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity {
                firstGesture = mountedTerminal.tmuxScrollGestureDiagnostic()
            }
            val gesture = requireNotNull(firstGesture)
            checkpoint(
                controller.diagnostic(
                    "first actual-Codex tmux gesture=$gesture; " +
                        controller.tmuxScrollDiagnostic(),
                ),
            )
            assertEquals(
                "The first real Codex/tmux gesture selected the row-based remote path: $gesture",
                TerminalScrollDestination.LOCAL_SCROLLBACK,
                gesture.destination,
            )
            assertEquals(
                "The first real Codex/tmux gesture was not backed by ready local history: $gesture",
                TerminalScrollDecisionReason.AUTO_TMUX_LOCAL_READY,
                gesture.reason,
            )
            assertEquals(
                "The first real Codex/tmux gesture emitted remote wheel reports: $gesture",
                0,
                gesture.remoteWheelReports,
            )
            assertTrue(
                "The first real Codex/tmux gesture never updated the local viewport: $gesture",
                gesture.localScrollUpdates > 0,
            )
            assertTrue(
                "A real tmux drag did not move toward older local history: $subRow",
                subRow.afterFirstMoveScrollY > subRow.afterSecondMoveScrollY &&
                    subRow.afterSecondMoveDistanceFromBottomPx >
                    subRow.startDistanceFromBottomPx,
            )
            assertTrue(
                "A real tmux move remained quantized to rows: $subRow",
                listOf(
                    subRow.startScrollY - subRow.afterFirstMoveScrollY,
                    subRow.afterFirstMoveScrollY - subRow.afterSecondMoveScrollY,
                ).any { movement ->
                    movement > MIN_SUB_ROW_MOVEMENT_PX && movement < subRow.lineHeightPx
                },
            )

            var pagingSwipeCount = 0
            while (
                controller.indexOfLine(firstExpectedSmoothTmuxRow()) < 0 &&
                pagingSwipeCount < MAX_TMUX_PAGING_SWIPE_ATTEMPTS
            ) {
                dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
                pagingSwipeCount += 1
                delay(SWIPE_SETTLE_MILLIS)
            }
            val oldestPageArrived = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (controller.indexOfLine(firstExpectedSmoothTmuxRow()) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("Bounded tmux paging did not fetch the oldest retained row."),
                oldestPageArrived == true,
            )

            val retainedTmuxRows = (0 until controller.lineCount()).mapNotNull { index ->
                controller.lineAt(index)?.text?.trimEnd()
                    ?.takeIf { it.startsWith(TMUX_SMOOTH_PREFIX) }
            }
            val expectedTmuxRows = (1..TMUX_SMOOTH_ROW_COUNT).map { number ->
                "$TMUX_SMOOTH_PREFIX%04d".format(number)
            }
            assertEquals(
                controller.diagnostic("The full ordered tmux history was not retained."),
                expectedTmuxRows,
                retainedTmuxRows,
            )
            var pagedGesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity { pagedGesture = mountedTerminal.tmuxScrollGestureDiagnostic() }
            assertEquals(
                "Paged tmux traversal emitted remote wheel reports: $pagedGesture",
                0,
                requireNotNull(pagedGesture).remoteWheelReports,
            )

            controller.jumpToBottom()
            var readerSetupFlingCount = 0
            var readerHistoryFling: FlingInterruptEvidence? = null
            while (
                controller.lineAt(controller.viewport.visibleRows(overscan = 0).first)
                    ?.text?.startsWith(TMUX_SMOOTH_PREFIX) != true &&
                readerSetupFlingCount < MAX_READER_SETUP_FLINGS
            ) {
                readerHistoryFling = dispatchFlingAndInterruptThroughWindow(
                    scenario = scenario,
                    instrumentation = instrumentation,
                    terminal = mountedTerminal,
                    controller = controller,
                )
                readerSetupFlingCount += 1
            }
            val completedReaderHistoryFling = requireNotNull(readerHistoryFling)
            assertTrue(
                "The reader setup did not move beyond the live screen: $completedReaderHistoryFling",
                completedReaderHistoryFling.afterFlingScrollY <
                    completedReaderHistoryFling.releaseScrollY - MIN_FLING_TRAVEL_PX,
            )
            assertTrue(
                controller.diagnostic("Real flings did not reach persistent tmux fixture history."),
                controller.lineAt(controller.viewport.visibleRows(overscan = 0).first)
                    ?.text?.startsWith(TMUX_SMOOTH_PREFIX) == true,
            )
            val readerDrag = dispatchSubRowDragThroughWindow(
                scenario = scenario,
                instrumentation = instrumentation,
                terminal = mountedTerminal,
                controller = controller,
            )
            delay(SWIPE_SETTLE_MILLIS)
            val readerVisibleBeforeOutput = controller.viewport.visibleRows(overscan = 0)
            val readerTopBeforeOutput = controller.selectionLineAt(readerVisibleBeforeOutput.first)
                ?.anchor
                ?: throw AssertionError(
                    controller.diagnostic("The scrolled reader did not expose a stable top anchor."),
                )
            assertTrue(
                controller.diagnostic("The reader setup stopped in the volatile live screen."),
                readerTopBeforeOutput.space != TerminalLineSpace.VOLATILE_SCREEN,
            )
            val readerScrollYBeforeOutput = controller.viewport.scrollY
            val readerFractionBeforeOutput =
                readerScrollYBeforeOutput % controller.viewport.lineHeightPx
            assertFalse(
                controller.diagnostic("The reader remained at live bottom before new output."),
                controller.viewport.autoFollow,
            )
            assertTrue(
                "The real reader gesture did not establish a fractional pixel position: $readerDrag",
                readerFractionBeforeOutput > MIN_SUB_ROW_MOVEMENT_PX &&
                    readerFractionBeforeOutput <
                    controller.viewport.lineHeightPx - MIN_SUB_ROW_MOVEMENT_PX,
            )
            checkpoint(
                "actual Codex reader position established " +
                    "autoFollow=${controller.viewport.autoFollow} " +
                    "historyAnchor=${readerTopBeforeOutput.space != TerminalLineSpace.VOLATILE_SCREEN} " +
                    "fractional=true flings=$readerSetupFlingCount",
            )
            controller.updateRendererProfile(
                TerminalRendererProfile(
                    jumpToBottomOnKeyboardInput = false,
                    keepViewportPositionOnOutput = true,
                ),
            )
            assertTrue(
                "Production controller rejected the actual-Codex reader-anchor prompt.",
                controller.sendPaste(CODEX_READER_ANCHOR_PROMPT),
            )
            delay(CODEX_PASTE_SETTLE_MILLIS)
            assertTrue(
                "The mounted terminal view did not submit the reader-anchor prompt.",
                dispatchEnterThroughWindow(scenario, mountedTerminal),
            )
            val readerOutputArrived = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(CODEX_READER_ANCHOR_MARKER) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("Actual Codex did not produce the reader-anchor marker."),
                readerOutputArrived == true,
            )
            delay(TMUX_CLIENT_ATTACH_SETTLE_MILLIS)
            checkpoint(
                "actual Codex reader output observed " +
                    "autoFollow=${controller.viewport.autoFollow}; " +
                    controller.tmuxScrollDiagnostic(),
            )
            val readerVisibleAfterOutput = controller.viewport.visibleRows(overscan = 0)
            val readerTopAfterOutput = controller.selectionLineAt(readerVisibleAfterOutput.first)
                ?.anchor
            val readerScrollYAfterOutput = controller.viewport.scrollY
            val readerFractionAfterOutput =
                readerScrollYAfterOutput % controller.viewport.lineHeightPx
            assertFalse(
                controller.diagnostic("New actual-Codex output pulled the reader to live bottom."),
                controller.viewport.autoFollow,
            )
            assertEquals(
                controller.diagnostic("New actual-Codex output changed the reader's top row."),
                readerTopBeforeOutput,
                readerTopAfterOutput,
            )
            assertEquals(
                "New actual-Codex output changed the reader's exact pixel position.",
                readerScrollYBeforeOutput,
                readerScrollYAfterOutput,
                READER_ANCHOR_TOLERANCE_PX,
            )
            assertEquals(
                "New actual-Codex output changed the reader's fractional row remainder.",
                readerFractionBeforeOutput,
                readerFractionAfterOutput,
                READER_ANCHOR_TOLERANCE_PX,
            )
            checkpoint(
                "actual Codex new output preserved reader anchor " +
                    "autoFollow=${controller.viewport.autoFollow} " +
                    "pixelDelta=${readerScrollYAfterOutput - readerScrollYBeforeOutput} " +
                    "fractional=${readerFractionAfterOutput > MIN_SUB_ROW_MOVEMENT_PX}",
            )

            val jumpDescription = targetContext.getString(R.string.terminal_jump_to_latest_description)
            val jumpToLatestSucceeded = withTimeoutOrNull(APP_UI_TIMEOUT_MILLIS) {
                while (!clickAccessibilityNodeWithDescription(instrumentation, jumpDescription)) {
                    delay(25L)
                }
                while (
                    !controller.viewport.autoFollow ||
                    !controller.visibleContainsText(CODEX_READER_ANCHOR_MARKER)
                ) {
                    delay(25L)
                }
                true
            }
            assertTrue(
                controller.diagnostic(
                    "The production Jump to latest action did not restore the actual-Codex live bottom.",
                ),
                jumpToLatestSucceeded == true,
            )
            checkpoint(
                "actual Codex live bottom restored autoFollow=${controller.viewport.autoFollow} " +
                    "markerVisible=${controller.visibleContainsText(CODEX_READER_ANCHOR_MARKER)}",
            )

            controller.jumpToBottom()
            val fling = dispatchFlingAndInterruptThroughWindow(
                scenario = scenario,
                instrumentation = instrumentation,
                terminal = mountedTerminal,
                controller = controller,
            )
            assertTrue(
                "tmux did not continue moving after finger release: $fling",
                fling.afterFlingScrollY < fling.releaseScrollY - MIN_FLING_TRAVEL_PX,
            )
            assertEquals(
                "Touch-down did not catch the tmux fling immediately: $fling",
                fling.caughtScrollY,
                fling.afterCatchWaitScrollY,
                FLING_CATCH_TOLERANCE_PX,
            )
            assertEquals(
                "The real tmux fling gesture emitted a remote wheel report: " +
                    fling.gestureDiagnostic,
                0,
                fling.gestureDiagnostic.remoteWheelReports,
            )
            checkpoint(controller.diagnostic("tmux sub-row drag and fling passed"))
        } finally {
            fixturePrivateKey.fill(0)
            startedSessionId?.let(repository::close)
            scenario.close()
        }
    }

    @Test(timeout = REAL_TMUX_SMOOTH_TEST_TIMEOUT_MILLIS)
    fun appSelectedTmuxExplicitRemoteMouseScrollsRealLessVimAndHtop() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PRIVATE_KEY_BASE64 to run real tmux mouse-app input.",
            host.isNotBlank() && username.isNotBlank() && privateKeyBytes != null,
        )
        val fixturePrivateKey = requireNotNull(privateKeyBytes)
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val repository = application.container.sshSessionRepository
        repository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedSessionId: Long? = null

        try {
            val result = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Real tmux mouse application",
                    workspaceName = "Real tmux mouse application",
                    connection = RemoteSessionConnectionRequest.Ssh(
                        SshConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            authentication = SshAuthentication.PrivateKey(
                                identityName = "real-tmux-mouse-e2e",
                                loadKey = { fixturePrivateKey.copyOf() },
                                passphrase = null,
                            ),
                            tmuxSessionSelectorEnabled = true,
                        ),
                    ),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = REAL_SCROLLBACK_CAPACITY,
                    ),
                ),
            )
            val started = result as? StartSshSessionResult.Started
                ?: throw AssertionError("Production tmux mouse session did not start: $result")
            startedSessionId = started.sessionId
            val controller = repository.controllerFor(started.sessionId)
                ?: throw AssertionError("Production tmux mouse session did not expose its controller.")
            scenario.onActivity { it.openTerminalSession(started.sessionId) }

            var terminalView: FastTerminalView? = null
            withTimeout(VIEW_ATTACH_TIMEOUT_MILLIS) {
                while (terminalView == null) {
                    scenario.onActivity { terminalView = findFastTerminalView(it.window.decorView) }
                    delay(10L)
                }
            }
            val mountedTerminal = requireNotNull(terminalView)
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = repository.sessions.value
                        .firstOrNull { it.id == started.sessionId }
                        ?.connectionState
                    ) {
                        ConnectionState.Connected -> break
                        is ConnectionState.AwaitingApproval -> when (val prompt = state.prompt) {
                            is HostIdentityPrompt -> repository.answerHostIdentityPrompt(
                                started.sessionId,
                                prompt.promptToken,
                                HostIdentityDecision.TrustOnce,
                            )
                            is TmuxSessionPrompt -> repository.answerTmuxSessionPrompt(
                                started.sessionId,
                                prompt.promptToken,
                                TMUX_NEW_SESSION_SELECTION,
                            )
                            else -> Unit
                        }
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected ->
                            fail("SSH disconnected before the tmux mouse application became ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            delay(TMUX_CLIENT_ATTACH_SETTLE_MILLIS)
            controller.updateRendererProfile(
                TerminalRendererProfile(touchScrollMode = TouchScrollMode.REMOTE_MOUSE),
            )
            assertTrue(
                "Production controller rejected the real less mouse fixture.",
                controller.sendPaste(TMUX_MOUSE_LESS_COMMAND, appendEnter = true),
            )
            val lessReady = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (
                    !controller.isMouseTrackingEnabled() ||
                    !controller.visibleContains(MOUSE_APP_FIRST_ROW)
                ) {
                    delay(25L)
                }
                true
            }
            assertTrue(
                controller.diagnostic("GNU less did not expose its first row with mouse tracking enabled."),
                lessReady == true,
            )
            val initialVisibleRows = controller.visibleNumberedRows(MOUSE_APP_PREFIX)
            assertTrue(initialVisibleRows.isNotEmpty())
            val initialTopRow = initialVisibleRows.first()
            checkpoint(
                "real less ready mouseTracking=${controller.isMouseTrackingEnabled()} " +
                    "visibleCount=${initialVisibleRows.size} top=$initialTopRow",
            )

            dispatchSwipeUpThroughWindow(scenario, instrumentation, mountedTerminal)
            val lessAdvanced = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (
                    controller.visibleNumberedRows(MOUSE_APP_PREFIX)
                        .firstOrNull()
                        ?.let { it <= initialTopRow } != false
                ) {
                    delay(25L)
                }
                true
            }
            var gesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity { gesture = mountedTerminal.tmuxScrollGestureDiagnostic() }
            val mouseGesture = requireNotNull(gesture)
            val advancedVisibleRows = controller.visibleNumberedRows(MOUSE_APP_PREFIX)
            checkpoint(
                "real less gesture destination=${mouseGesture.destination} reason=${mouseGesture.reason} " +
                    "reports=${mouseGesture.remoteWheelReports} local=${mouseGesture.localScrollUpdates} " +
                    "visibleCount=${advancedVisibleRows.size} top=${advancedVisibleRows.firstOrNull()}",
            )
            assertTrue(
                controller.diagnostic("A real touch gesture did not advance GNU less inside tmux."),
                lessAdvanced == true,
            )
            assertEquals(TerminalScrollDestination.REMOTE_MOUSE, mouseGesture.destination)
            assertEquals(TerminalScrollDecisionReason.EXPLICIT_REMOTE_MOUSE, mouseGesture.reason)
            assertTrue(
                "The explicit remote-mouse gesture emitted no wheel reports: $mouseGesture",
                mouseGesture.remoteWheelReports > 0,
            )
            assertEquals(
                "The explicit remote-mouse gesture changed Android-owned scrollback: $mouseGesture",
                0,
                mouseGesture.localScrollUpdates,
            )
            checkpoint(
                "real less remote mouse passed destination=${mouseGesture.destination} " +
                    "reason=${mouseGesture.reason} reports=${mouseGesture.remoteWheelReports}",
            )
            controller.send("q".toByteArray(Charsets.UTF_8))
            val lessExited = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(MOUSE_LESS_EXIT_MARKER) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("GNU less did not exit cleanly before starting Vim."),
                lessExited == true,
            )
            checkpoint("real less exited before vim")

            assertTrue(
                "Production controller rejected the real Vim mouse fixture.",
                controller.sendPaste(TMUX_MOUSE_VIM_COMMAND, appendEnter = true),
            )
            val vimReady = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (
                    !controller.isMouseTrackingEnabled() ||
                    !controller.visibleContains(VIM_MOUSE_FIRST_ROW)
                ) {
                    delay(25L)
                }
                true
            }
            checkpoint(
                "real vim readiness ready=${vimReady == true} " +
                    "mouseTracking=${controller.isMouseTrackingEnabled()} " +
                    "visibleCount=${controller.visibleNumberedRows(VIM_MOUSE_PREFIX).size} " +
                    "exitSeen=${controller.indexOfLineContaining(VIM_MOUSE_EXIT_MARKER) >= 0}",
            )
            assertTrue(
                controller.diagnostic("Vim did not expose its first row with mouse tracking enabled."),
                vimReady == true,
            )
            val initialVimRows = controller.visibleNumberedRows(VIM_MOUSE_PREFIX)
            assertTrue(initialVimRows.isNotEmpty())
            val initialVimTopRow = initialVimRows.first()
            checkpoint(
                "real vim ready mouseTracking=${controller.isMouseTrackingEnabled()} " +
                    "visibleCount=${initialVimRows.size} top=$initialVimTopRow",
            )

            dispatchSwipeUpThroughWindow(scenario, instrumentation, mountedTerminal)
            val vimAdvanced = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (
                    controller.visibleNumberedRows(VIM_MOUSE_PREFIX)
                        .firstOrNull()
                        ?.let { it <= initialVimTopRow } != false
                ) {
                    delay(25L)
                }
                true
            }
            var vimGesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity { vimGesture = mountedTerminal.tmuxScrollGestureDiagnostic() }
            val resolvedVimGesture = requireNotNull(vimGesture)
            val advancedVimRows = controller.visibleNumberedRows(VIM_MOUSE_PREFIX)
            checkpoint(
                "real vim gesture destination=${resolvedVimGesture.destination} " +
                    "reason=${resolvedVimGesture.reason} " +
                    "reports=${resolvedVimGesture.remoteWheelReports} " +
                    "local=${resolvedVimGesture.localScrollUpdates} " +
                    "visibleCount=${advancedVimRows.size} top=${advancedVimRows.firstOrNull()}",
            )
            assertTrue(
                controller.diagnostic("A real touch gesture did not advance Vim inside tmux."),
                vimAdvanced == true,
            )
            assertEquals(TerminalScrollDestination.REMOTE_MOUSE, resolvedVimGesture.destination)
            assertEquals(
                TerminalScrollDecisionReason.EXPLICIT_REMOTE_MOUSE,
                resolvedVimGesture.reason,
            )
            assertTrue(
                "The explicit Vim remote-mouse gesture emitted no wheel reports: " +
                    resolvedVimGesture,
                resolvedVimGesture.remoteWheelReports > 0,
            )
            assertEquals(
                "The explicit Vim remote-mouse gesture changed Android-owned scrollback: " +
                    resolvedVimGesture,
                0,
                resolvedVimGesture.localScrollUpdates,
            )
            checkpoint(
                "real vim remote mouse passed destination=${resolvedVimGesture.destination} " +
                    "reason=${resolvedVimGesture.reason} " +
                    "reports=${resolvedVimGesture.remoteWheelReports}",
            )
            controller.send(":q!\r".toByteArray(Charsets.UTF_8))
            val vimExited = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(VIM_MOUSE_EXIT_MARKER) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("Vim did not exit and clean its temporary fixture."),
                vimExited == true,
            )

            assertTrue(
                "Production controller rejected the real htop mouse fixture.",
                controller.sendPaste(TMUX_MOUSE_HTOP_COMMAND, appendEnter = true),
            )
            val htopReady = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (
                    !controller.isMouseTrackingEnabled() ||
                    controller.visibleNumberedRows(HTOP_MOUSE_PREFIX).size < MIN_HTOP_VISIBLE_ROWS
                ) {
                    delay(25L)
                }
                true
            }
            val initialHtopRows = controller.visibleNumberedRows(HTOP_MOUSE_PREFIX)
            checkpoint(
                "real htop readiness ready=${htopReady == true} " +
                    "mouseTracking=${controller.isMouseTrackingEnabled()} " +
                    "visibleCount=${initialHtopRows.size} top=${initialHtopRows.firstOrNull()} " +
                    "exitSeen=${controller.indexOfLineContaining(HTOP_MOUSE_EXIT_MARKER) >= 0}",
            )
            assertTrue(
                controller.diagnostic("htop did not show enough controlled processes for scrolling."),
                htopReady == true,
            )
            val initialHtopTopRow = initialHtopRows.first()
            var htopSwipeCount = 0
            while (
                controller.visibleNumberedRows(HTOP_MOUSE_PREFIX)
                    .firstOrNull()
                    ?.let { it <= initialHtopTopRow } != false &&
                htopSwipeCount < MAX_HTOP_SWIPE_ATTEMPTS
            ) {
                dispatchSwipeUpThroughWindow(scenario, instrumentation, mountedTerminal)
                htopSwipeCount += 1
                delay(HTOP_GESTURE_SETTLE_MILLIS)
            }
            var htopGesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity { htopGesture = mountedTerminal.tmuxScrollGestureDiagnostic() }
            val resolvedHtopGesture = requireNotNull(htopGesture)
            val advancedHtopRows = controller.visibleNumberedRows(HTOP_MOUSE_PREFIX)
            checkpoint(
                "real htop gesture destination=${resolvedHtopGesture.destination} " +
                    "reason=${resolvedHtopGesture.reason} " +
                    "reports=${resolvedHtopGesture.remoteWheelReports} " +
                    "local=${resolvedHtopGesture.localScrollUpdates} " +
                    "swipes=$htopSwipeCount visibleCount=${advancedHtopRows.size} " +
                    "top=${advancedHtopRows.firstOrNull()}",
            )
            assertTrue(
                controller.diagnostic("Real touch gestures did not advance htop inside tmux."),
                advancedHtopRows.firstOrNull()?.let { it > initialHtopTopRow } == true,
            )
            assertEquals(TerminalScrollDestination.REMOTE_MOUSE, resolvedHtopGesture.destination)
            assertEquals(
                TerminalScrollDecisionReason.EXPLICIT_REMOTE_MOUSE,
                resolvedHtopGesture.reason,
            )
            assertTrue(
                "The explicit htop remote-mouse gesture emitted no wheel reports: " +
                    resolvedHtopGesture,
                resolvedHtopGesture.remoteWheelReports > 0,
            )
            assertEquals(
                "The explicit htop remote-mouse gesture changed Android-owned scrollback: " +
                    resolvedHtopGesture,
                0,
                resolvedHtopGesture.localScrollUpdates,
            )
            checkpoint(
                "real htop remote mouse passed destination=${resolvedHtopGesture.destination} " +
                    "reason=${resolvedHtopGesture.reason} " +
                    "reports=${resolvedHtopGesture.remoteWheelReports} swipes=$htopSwipeCount",
            )
            controller.send("q".toByteArray(Charsets.UTF_8))
            val htopExited = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(HTOP_MOUSE_EXIT_MARKER) < 0) delay(25L)
                true
            }
            assertTrue(
                controller.diagnostic("htop did not exit and clean its controlled processes."),
                htopExited == true,
            )
        } finally {
            fixturePrivateKey.fill(0)
            startedSessionId?.let(repository::close)
            scenario.close()
        }
    }

    @Test(timeout = REAL_TMUX_SMOOTH_TEST_TIMEOUT_MILLIS)
    fun appSelectedTmuxPagesFiveThousandRowsThroughProductionUi() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and $ARG_PRIVATE_KEY_BASE64 to run real tmux paging.",
            host.isNotBlank() && username.isNotBlank() && privateKeyBytes != null,
        )
        val fixturePrivateKey = requireNotNull(privateKeyBytes)
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        seedTmuxPagingFixture(host, port, username, fixturePrivateKey)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val repository = (targetContext.applicationContext as TerminalSpikeApplication)
            .container.sshSessionRepository
        repository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedSessionId: Long? = null

        try {
            val started = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Real tmux paging",
                    workspaceName = "Real tmux paging",
                    connection = RemoteSessionConnectionRequest.Ssh(
                        SshConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            authentication = SshAuthentication.PrivateKey(
                                identityName = "real-tmux-paging-e2e",
                                loadKey = { fixturePrivateKey.copyOf() },
                                passphrase = null,
                            ),
                            tmuxSessionSelectorEnabled = true,
                        ),
                    ),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = TMUX_SMOOTH_ROW_COUNT + 256,
                    ),
                ),
            ) as? StartSshSessionResult.Started
                ?: throw AssertionError("Production tmux paging session did not start.")
            startedSessionId = started.sessionId
            val controller = repository.controllerFor(started.sessionId)
                ?: throw AssertionError("Production tmux paging controller is missing.")
            scenario.onActivity { it.openTerminalSession(started.sessionId) }

            var terminalView: FastTerminalView? = null
            withTimeout(VIEW_ATTACH_TIMEOUT_MILLIS) {
                while (terminalView == null) {
                    scenario.onActivity { terminalView = findFastTerminalView(it.window.decorView) }
                    delay(10L)
                }
            }
            val mountedTerminal = requireNotNull(terminalView)
            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = repository.sessions.value
                        .firstOrNull { it.id == started.sessionId }
                        ?.connectionState
                    ) {
                        ConnectionState.Connected -> break
                        is ConnectionState.AwaitingApproval -> when (val prompt = state.prompt) {
                            is HostIdentityPrompt -> repository.answerHostIdentityPrompt(
                                started.sessionId,
                                prompt.promptToken,
                                HostIdentityDecision.TrustOnce,
                            )
                            is TmuxSessionPrompt -> {
                                val selectedSessionId = prompt.sessions
                                    .singleOrNull { it.name == TMUX_PAGING_SESSION_NAME }
                                    ?.id
                                if (selectedSessionId == null) {
                                    fail(
                                    "Seeded tmux session is missing; sessions=" +
                                        prompt.sessions.map { it.name },
                                    )
                                } else {
                                    repository.answerTmuxSessionPrompt(
                                        started.sessionId,
                                        prompt.promptToken,
                                        selectedSessionId,
                                    )
                                }
                            }
                            else -> Unit
                        }
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected -> fail("SSH disconnected during tmux paging setup.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            delay(TMUX_CLIENT_ATTACH_SETTLE_MILLIS)
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(lastExpectedSmoothTmuxRow()) < 0) delay(10L)
            }
            controller.requestTmuxHistoryRefresh()
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (!controller.isTmuxLocalScrollAvailable()) delay(10L)
            }
            controller.jumpToBottom()
            val bottom = controller.viewport.visibleRows(overscan = 0)
            val lastRow = controller.indexOfLine(lastExpectedSmoothTmuxRow())
            assertTrue(
                controller.diagnostic("Newest tmux row is not visible at the initial bottom."),
                lastRow in bottom.first until bottom.lastExclusive,
            )
            assertEquals(
                controller.diagnostic("The initial bounded page unexpectedly contains the oldest row."),
                -1,
                controller.indexOfLine(firstExpectedSmoothTmuxRow()),
            )

            val initialScrollY = controller.viewport.scrollY
            dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
            assertTrue(
                controller.diagnostic("The first paging gesture did not move local pixels."),
                controller.viewport.scrollY < initialScrollY,
            )
            var swipeCount = 1
            while (
                !controller.visibleContainsPrefix(firstExpectedSmoothTmuxRow()) &&
                swipeCount < MAX_TMUX_PAGING_SWIPE_ATTEMPTS
            ) {
                dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
                swipeCount += 1
                delay(SWIPE_SETTLE_MILLIS)
            }
            val reachedOldest = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                while (!controller.visibleContainsPrefix(firstExpectedSmoothTmuxRow())) delay(25L)
                true
            }
            var gesture: TmuxScrollGestureDiagnostic? = null
            scenario.onActivity { gesture = mountedTerminal.tmuxScrollGestureDiagnostic() }
            assertTrue(
                controller.diagnostic("Real MotionEvent traversal did not reach the oldest tmux row."),
                reachedOldest == true,
            )
            assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, requireNotNull(gesture).destination)
            assertEquals(0, requireNotNull(gesture).remoteWheelReports)
            val retained = (0 until controller.lineCount()).mapNotNull { index ->
                controller.lineAt(index)?.text?.trimEnd()?.takeIf { it.startsWith(TMUX_SMOOTH_PREFIX) }
            }
            assertEquals(
                (1..TMUX_SMOOTH_ROW_COUNT).map { "$TMUX_SMOOTH_PREFIX%04d".format(it) },
                retained,
            )
            checkpoint(
                controller.diagnostic(
                    "real tmux paging passed after $swipeCount MotionEvent drags; gesture=$gesture; " +
                        controller.tmuxScrollDiagnostic(),
                ),
            )
        } finally {
            fixturePrivateKey.fill(0)
            startedSessionId?.let(repository::close)
            scenario.close()
        }
    }

    private suspend fun seedTmuxPagingFixture(
        host: String,
        port: Int,
        username: String,
        privateKey: ByteArray,
    ) {
        val connection = JschSshConnection(
            knownHostManager = KnownHostManager(SshE2eKnownHostTrustStore()),
            config = SshConnectionConfig(
                host = host,
                port = port,
                username = username,
                authentication = SshAuthentication.PrivateKey(
                    identityName = "tmux-paging-seed",
                    loadKey = { privateKey.copyOf() },
                    passphrase = null,
                ),
            ),
        )
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            connection.connect(
                TERMINAL_COLUMNS,
                TERMINAL_ROWS,
                onBytes = { output.trySend(it.copyOf()) },
                onState = { state ->
                    if (state is ConnectionState.AwaitingApproval) {
                        (state.prompt as? HostIdentityPrompt)?.let { prompt ->
                            connection.answerHostIdentityPrompt(
                                prompt.promptToken,
                                HostIdentityDecision.TrustOnce,
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
                        ConnectionState.Disconnected -> fail("SSH disconnected while seeding tmux history.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        is ConnectionState.AwaitingApproval,
                        -> Unit
                    }
                }
            }
            assertTrue(connection.sendWithAcceptance("$TMUX_PAGING_SEED_COMMAND\n".encodeToByteArray()))
            val captured = ByteArrayOutputStream()
            withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                while (
                    captured.toString(Charsets.UTF_8.name()).lineSequence()
                        .none { it.trim() == TMUX_PAGING_READY }
                ) {
                    captured.write(output.receive())
                    check(captured.size() <= MAX_CAPTURE_BYTES)
                }
            }
        } finally {
            connection.close()
            output.close()
            states.close()
            if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { job.await() } == null) {
                job.cancelAndJoin()
            }
        }
    }

    @Test(timeout = REAL_SCROLL_TEST_TIMEOUT_MILLIS)
    fun realServerScrollsToFirstRowThroughProductionSessionAndComposeView() = runBlocking {
        checkpoint("test started")
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val codexCommand = arguments.getString(ARG_CODEX_COMMAND_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let {
                android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    .toString(Charsets.UTF_8)
            }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and either $ARG_PASSWORD or " +
                "$ARG_PRIVATE_KEY_BASE64 to run the real SSH fixture.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotEmpty() || privateKeyBytes != null),
        )
        val port = arguments.getString(ARG_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        val passwordBytes = password.encodeToByteArray()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val application = targetContext.applicationContext as TerminalSpikeApplication
        val repository = application.container.sshSessionRepository
        repository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedSessionId: Long? = null

        try {
            val terminalDestinationOpened = withTimeoutOrNull(APP_UI_TIMEOUT_MILLIS) {
                val description = targetContext.getString(R.string.navigation_open_terminal)
                while (!clickAccessibilityNodeWithDescription(instrumentation, description)) {
                    delay(25L)
                }
                true
            }
            assertTrue(
                "The real MainActivity did not expose its Terminal navigation action. " +
                    accessibilitySummary(instrumentation),
                terminalDestinationOpened == true,
            )
            checkpoint("actual Terminal destination opened")

            val result = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Real SSH scrollback",
                    workspaceName = "Real SSH scrollback",
                    connection = RemoteSessionConnectionRequest.Ssh(
                        SshConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            authentication = privateKeyBytes?.let { keyBytes ->
                                SshAuthentication.PrivateKey(
                                    identityName = "real-codex-e2e",
                                    loadKey = { keyBytes.copyOf() },
                                    passphrase = null,
                                )
                            } ?: SshAuthentication.Password(passwordBytes),
                            startupCommand = if (codexCommand != null) {
                                "exec /bin/bash --noprofile --norc\n"
                            } else {
                                null
                            },
                        ),
                    ),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = REAL_SCROLLBACK_CAPACITY,
                    ),
                ),
            )
            val started = result as? StartSshSessionResult.Started
                ?: throw AssertionError("Production SSH session did not start: $result")
            startedSessionId = started.sessionId
            checkpoint("session started")
            val controller = repository.controllerFor(started.sessionId)
                ?: throw AssertionError("Production SSH session did not expose its controller.")

            scenario.onActivity { activity -> activity.openTerminalSession(started.sessionId) }

            var terminalView: FastTerminalView? = null
            withTimeout(VIEW_ATTACH_TIMEOUT_MILLIS) {
                while (true) {
                    scenario.onActivity {
                        findFastTerminalView(it.window.decorView)?.let { view ->
                            terminalView = view
                        }
                    }
                    if (terminalView != null) break
                    delay(10L)
                }
            }
            val mountedTerminal = requireNotNull(terminalView)
            checkpoint("view attached")

            withTimeout(CONNECT_TIMEOUT_MILLIS) {
                while (true) {
                    when (val state = repository.sessions.value
                        .firstOrNull { it.id == started.sessionId }
                        ?.connectionState
                    ) {
                        ConnectionState.Connected -> break
                        is ConnectionState.AwaitingApproval -> {
                            val prompt = state.prompt as? HostIdentityPrompt
                            if (prompt != null) {
                                repository.answerHostIdentityPrompt(
                                    sessionId = started.sessionId,
                                    promptToken = prompt.promptToken,
                                    decision = HostIdentityDecision.TrustOnce,
                                )
                            }
                        }
                        is ConnectionState.Failed -> fail(state.message)
                        ConnectionState.Disconnected ->
                            fail("Production SSH session disconnected before becoming ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            checkpoint("SSH connected")

            val submittedRows = if (codexCommand != null) {
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    while (
                        (0 until controller.lineCount()).none { index ->
                            controller.lineAt(index)?.text?.contains("bash-5.3$") == true
                        }
                    ) {
                        delay(25L)
                    }
                }
                controller.updateRendererProfile(
                    com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile(
                        keepViewportPositionOnOutput = false,
                    ),
                )
                assertTrue(
                    "Production controller rejected the real Codex startup command.",
                    controller.sendPaste(codexCommand, appendEnter = true),
                )
                val codexOpened = withTimeoutOrNull(CODEX_START_TIMEOUT_MILLIS) {
                    while (
                        (0 until controller.lineCount()).none { index ->
                            controller.lineAt(index)?.text?.contains("OpenAI Codex") == true
                        }
                    ) {
                        delay(25L)
                    }
                    true
                }
                assertTrue(
                    controller.diagnostic("The actual Codex TUI did not open over real SSH."),
                    codexOpened == true,
                )
                val codexInputReady = withTimeoutOrNull(CODEX_START_TIMEOUT_MILLIS) {
                    while (
                        (0 until controller.lineCount()).none { index ->
                            controller.lineAt(index)?.text?.contains("Ask Codex to do anything") == true
                        }
                    ) {
                        delay(25L)
                    }
                    true
                }
                assertTrue(
                    controller.diagnostic("The actual Codex input box did not become ready."),
                    codexInputReady == true,
                )
                delay(CODEX_INPUT_READY_SETTLE_MILLIS)
                val promptPasted = controller.sendPaste(CODEX_200_LINE_PROMPT)
                delay(CODEX_PASTE_SETTLE_MILLIS)
                assertTrue("Production controller rejected the Codex prompt paste.", promptPasted)
                assertTrue(
                    "The mounted terminal view did not handle Android KEYCODE_ENTER.",
                    dispatchEnterThroughWindow(scenario, mountedTerminal),
                )
                val codexStartedWorking = withTimeoutOrNull(CODEX_SUBMIT_TIMEOUT_MILLIS) {
                    while (
                        (0 until controller.lineCount()).none { index ->
                            val text = controller.lineAt(index)?.text.orEmpty()
                            "Working" in text || firstExpectedCodexRow() in text
                        }
                    ) {
                        delay(25L)
                    }
                    true
                }
                assertTrue(
                    controller.diagnostic("The Android Enter key did not submit the Codex prompt."),
                    codexStartedWorking == true,
                )
                true
            } else {
                controller.sendPaste(COMMAND, appendEnter = true)
            }
            assertTrue("Production controller rejected the 200-row request.", submittedRows)
            val expectedFirstRow = if (codexCommand != null) {
                firstExpectedCodexRow()
            } else {
                firstExpectedRow()
            }
            val expectedLastRow = if (codexCommand != null) {
                lastExpectedCodexRow()
            } else {
                lastExpectedRow()
            }
            val receivedLastRow = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfLineContaining(expectedLastRow) < 0) delay(10L)
                true
            }
            assertTrue(
                controller.diagnostic(
                    "Production controller did not receive the last requested row; " +
                        "state=${repository.sessions.value.firstOrNull { it.id == started.sessionId }?.connectionState}.",
                ),
                receivedLastRow == true,
            )
            checkpoint(controller.diagnostic("200 rows retained"))

            if (codexCommand != null) {
                val codexRowIndices = (1..OUTPUT_ROW_COUNT).map { number ->
                    controller.indexOfLineContaining("CODEX_SCROLL_%03d".format(number))
                }
                val missingRows = codexRowIndices.mapIndexedNotNull { index, lineIndex ->
                    if (lineIndex < 0) index + 1 else null
                }
                assertTrue(
                    controller.diagnostic(
                        "Actual Codex output is incomplete; missing=${missingRows.take(40)} " +
                            "missingCount=${missingRows.size}.",
                    ),
                    missingRows.isEmpty(),
                )
                assertTrue(
                    controller.diagnostic("Actual Codex output rows are not retained in order."),
                    codexRowIndices.zipWithNext().all { (first, second) -> first < second },
                )
            }

            val firstRowIndex = controller.indexOfLineContaining(expectedFirstRow)
            val lastRowIndex = controller.indexOfLineContaining(expectedLastRow)
            assertTrue(
                controller.diagnostic("Production controller lost the first requested row."),
                firstRowIndex >= 0,
            )
            assertTrue(
                controller.diagnostic("Production controller lost the last requested row."),
                lastRowIndex >= firstRowIndex,
            )
            assertTrue(
                controller.diagnostic("Production viewport has no scrollable range."),
                controller.viewport.maximumScrollY > 0f,
            )
            val bottomVisible = controller.viewport.visibleRows(overscan = 0)
            assertFalse(
                controller.diagnostic("The first requested row was already visible at the bottom."),
                firstRowIndex in bottomVisible.first until bottomVisible.lastExclusive,
            )
            assertTrue(
                controller.diagnostic("The last requested row was not visible at the bottom."),
                lastRowIndex in bottomVisible.first until bottomVisible.lastExclusive,
            )
            val bottomScrollY = controller.viewport.scrollY
            assertTrue(
                controller.diagnostic("Production viewport did not follow output to the bottom."),
                bottomScrollY > 0f,
            )

            dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
            assertTrue(
                controller.diagnostic("One real SSH swipe did not move toward older history."),
                controller.viewport.scrollY < bottomScrollY,
            )
            var localSwipeCount = 1
            while (
                firstRowIndex !in controller.viewport.visibleRows(overscan = 0).let {
                    it.first until it.lastExclusive
                } && localSwipeCount < MAX_SWIPE_ATTEMPTS
            ) {
                dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
                localSwipeCount += 1
                delay(SWIPE_SETTLE_MILLIS)
            }
            checkpoint(controller.diagnostic("SSH gestures dispatched"))

            val visible = controller.viewport.visibleRows(overscan = 0)
            assertTrue(
                controller.diagnostic("The first requested row is retained but not visible after swiping."),
                firstRowIndex in visible.first until visible.lastExclusive,
            )
            assertTrue(controller.lineAt(firstRowIndex)?.text?.contains(expectedFirstRow) == true)

            controller.jumpToBottom()
            if (codexCommand != null) {
                checkpoint(controller.diagnostic("real Codex output scrolled back to its first row"))
            } else {
                assertTrue(
                    "Production controller rejected the tmux attach command.",
                    controller.sendPaste(TMUX_ATTACH_COMMAND, appendEnter = true),
                )
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    while (!controller.isMouseTrackingEnabled()) delay(10L)
                }
                assertTrue(
                    controller.diagnostic("tmux did not negotiate terminal mouse tracking."),
                    controller.isMouseTrackingEnabled(),
                )
                delay(TMUX_ATTACH_SETTLE_MILLIS)
                assertTrue(
                    "Production controller rejected the tmux 200-row request.",
                    controller.sendPaste(TMUX_ROWS_COMMAND, appendEnter = true),
                )
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    while (controller.indexOfLine(lastExpectedTmuxRow()) < 0) delay(10L)
                }
                var tmuxSwipeCount = 0
                while (
                    !controller.visibleContainsPrefix(firstExpectedTmuxRow()) &&
                    tmuxSwipeCount < MAX_SWIPE_ATTEMPTS
                ) {
                    dispatchSwipeDownThroughWindow(scenario, instrumentation, mountedTerminal)
                    tmuxSwipeCount += 1
                    delay(TMUX_SWIPE_SETTLE_MILLIS)
                }
                val tmuxOutputDrained = withTimeoutOrNull(TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS) {
                    while (!controller.visibleContainsPrefix(firstExpectedTmuxRow())) delay(25L)
                    true
                }
                assertTrue(
                    controller.diagnostic("Real tmux mouse scroll did not reveal its first retained row."),
                    tmuxOutputDrained == true && controller.visibleContainsPrefix(firstExpectedTmuxRow()),
                )
                checkpoint(controller.diagnostic("tmux remote scroll reached its first row"))
            }
        } finally {
            privateKeyBytes?.fill(0)
            startedSessionId?.let(repository::close)
            scenario.close()
        }
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
            "The Mosh transport must be absent for this release-isolation proof.",
            extensionInstalled,
        )
    }

    private companion object {
        const val ARG_HOST = "sshE2eHost"
        const val ARG_PORT = "sshE2ePort"
        const val ARG_USERNAME = "sshE2eUsername"
        const val ARG_PASSWORD = "sshE2ePassword"
        const val ARG_PRIVATE_KEY_BASE64 = "sshE2ePrivateKeyBase64"
        const val ARG_CODEX_COMMAND_BASE64 = "sshE2eCodexCommandBase64"
        const val ARG_REQUIRE_MOSH_ABSENT = "sshE2eRequireMoshAbsent"
        const val DEFAULT_PORT = 22
        const val TERMINAL_COLUMNS = 80
        const val TERMINAL_ROWS = 24
        const val OUTPUT_ROW_COUNT = 200
        const val CONNECT_TIMEOUT_MILLIS = 30_000L
        const val OUTPUT_TIMEOUT_MILLIS = 180_000L
        const val VIEW_ATTACH_TIMEOUT_MILLIS = 5_000L
        const val APP_UI_TIMEOUT_MILLIS = 15_000L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
        const val MAX_CAPTURE_BYTES = 64 * 1024
        const val REAL_SCROLLBACK_CAPACITY = 512
        const val MAX_SWIPE_ATTEMPTS = 64
        const val MAX_TMUX_PAGING_SWIPE_ATTEMPTS = 320
        const val SWIPE_SETTLE_MILLIS = 16L
        const val TMUX_ATTACH_SETTLE_MILLIS = 500L
        const val TMUX_SWIPE_SETTLE_MILLIS = 150L
        const val TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS = 10_000L
        const val CODEX_START_TIMEOUT_MILLIS = 30_000L
        const val CODEX_INPUT_READY_SETTLE_MILLIS = 1_000L
        const val CODEX_PASTE_SETTLE_MILLIS = 250L
        const val CODEX_SUBMIT_TIMEOUT_MILLIS = 10_000L
        const val REAL_SCROLL_TEST_TIMEOUT_MILLIS = 360_000L
        const val REAL_TMUX_SMOOTH_TEST_TIMEOUT_MILLIS = 360_000L
        const val SAVED_KEY_TEST_TIMEOUT_MILLIS = 120_000L
        const val MOSH_EXTENSION_PACKAGE = "com.yanjiyu.terminalspike.mosh"
        const val MARKER = "SSH_SCROLL_DONE"
        const val SAVED_KEY_HOST_NAME = "Real imported-key E2E"
        const val SAVED_KEY_MARKER = "SAVED_KEY_E2E_OK"
        const val COMMAND =
            "i=1; while [ \"\$i\" -le $OUTPUT_ROW_COUNT ]; do " +
                "printf 'SSH_SCROLL_%03d\\n' \"\$i\"; i=\$((i+1)); done; " +
                "printf 'SSH_SCROLL_%s\\n' DONE"
        const val CODEX_200_LINE_PROMPT =
            "Reply with exactly 200 lines and no other text. For each integer N from 1 through " +
                "200, line N must be CODEX_SCROLL_NNN where NNN is zero-padded to three digits."
        const val CODEX_READER_ANCHOR_MARKER = "CODEX_READER_ANCHOR_PASS"
        const val CODEX_READER_ANCHOR_PROMPT =
            "Reply with exactly one line and no other text. Concatenate CODEX_READER_ANCHOR_ " +
                "and PASS with no space, punctuation, quotation marks, or Markdown."
        const val TMUX_SESSION_NAME = "terminal-spike-e2e"
        const val TMUX_ATTACH_COMMAND =
            "tmux kill-session -t $TMUX_SESSION_NAME 2>/dev/null; " +
                "tmux new-session -d -s $TMUX_SESSION_NAME " +
                "'exec /bin/sh'; " +
                "tmux set-option -t $TMUX_SESSION_NAME -g mouse on; " +
                "tmux attach-session -t $TMUX_SESSION_NAME"
        const val TMUX_ROWS_COMMAND =
            "i=1; while [ \"\$i\" -le $OUTPUT_ROW_COUNT ]; do " +
                "printf 'TMUX_SCROLL_%03d\\n' \"\$i\"; i=\$((i+1)); done"
        const val TMUX_SMOOTH_ROW_COUNT = 5_000
        const val TMUX_SMOOTH_PREFIX = "TMUX_SMOOTH_"
        const val TMUX_CLIENT_ATTACH_SETTLE_MILLIS = 1_000L
        const val TMUX_SMOOTH_ROWS_COMMAND =
            "seq -f '${TMUX_SMOOTH_PREFIX}%04g' 1 $TMUX_SMOOTH_ROW_COUNT"
        const val MOUSE_APP_PREFIX = "MOUSE_APP_"
        const val MOUSE_APP_FIRST_ROW = "${MOUSE_APP_PREFIX}001"
        const val MOUSE_LESS_EXIT_MARKER = "MOUSE_LESS_EXIT"
        const val TMUX_MOUSE_LESS_COMMAND =
            "seq -f '${MOUSE_APP_PREFIX}%03g' 1 200 | " +
                "less --mouse --wheel-lines=3 --no-init; printf 'MOUSE_%s_EXIT\\n' LESS"
        const val VIM_MOUSE_PREFIX = "VIM_MOUSE_"
        const val VIM_MOUSE_FIRST_ROW = "${VIM_MOUSE_PREFIX}001"
        const val VIM_MOUSE_EXIT_MARKER = "VIM_MOUSE_EXIT_0"
        const val TMUX_MOUSE_VIM_COMMAND =
            "sh -c 'f=\"\$(mktemp /tmp/terminal-spike-vim-mouse.XXXXXX)\" || exit; " +
                "trap \"rm -f -- \\\"\$f\\\"\" EXIT; " +
                "seq -f \"${VIM_MOUSE_PREFIX}%03g\" 1 200 >\"\$f\"; " +
                "vim -Nu NONE -n -i NONE -c \"set mouse=a\" \"\$f\"; rc=\$?; " +
                "rm -f -- \"\$f\"; trap - EXIT; " +
                "printf \"VIM_MOUSE_EXIT_%d\\n\" \"\$rc\"'"
        const val HTOP_MOUSE_PREFIX = "HTOP_MOUSE_"
        const val HTOP_MOUSE_EXIT_MARKER = "HTOP_MOUSE_EXIT_0"
        const val HTOP_PROCESS_COUNT = 80
        const val MIN_HTOP_VISIBLE_ROWS = 8
        const val MAX_HTOP_SWIPE_ATTEMPTS = 12
        const val HTOP_GESTURE_SETTLE_MILLIS = 100L
        const val TMUX_MOUSE_HTOP_COMMAND =
            "sh -c 'pids=\"\"; " +
                "cfg=\"\$(mktemp /tmp/terminal-spike-htoprc.XXXXXX)\" || exit; " +
                "cleanup() { kill \$pids 2>/dev/null || :; wait \$pids 2>/dev/null || :; " +
                "rm -f -- \"\$cfg\"; }; trap cleanup EXIT INT TERM; " +
                "printf \"%s\\n\" \"htop_version=3.5.3\" \"config_reader_min_version=3\" " +
                "\"fields=0 1\" \"enable_mouse=1\" \"delay=100\" " +
                "\"hide_function_bar=2\" \"header_layout=two_50_50\" " +
                "\"column_meters_0=\" \"column_meter_modes_0=\" " +
                "\"column_meters_1=\" \"column_meter_modes_1=\" " +
                "\"screen:Main=PID Command\" \".sort_key=PID\" " +
                "\".sort_direction=1\" >\"\$cfg\"; i=1; " +
                "while [ \"\$i\" -le $HTOP_PROCESS_COUNT ]; do " +
                "n=\$(printf \"%03d\" \"\$i\"); " +
                "bash -c \"exec -a ${HTOP_MOUSE_PREFIX}\$n sleep 300\" & " +
                "pids=\"\$pids \$!\"; i=\$((i+1)); done; " +
                "HTOPRC=\"\$cfg\" htop -C --readonly -d 100 " +
                "-F $HTOP_MOUSE_PREFIX; rc=\$?; cleanup; trap - EXIT INT TERM; " +
                "printf \"HTOP_MOUSE_EXIT_%d\\n\" \"\$rc\"'"
        const val TMUX_PAGING_SESSION_NAME = "terminal-spike-paging-e2e"
        const val TMUX_PAGING_READY = "TMUX_PAGING_READY"
        const val TMUX_PAGING_SEED_COMMAND =
            "tmux kill-session -t $TMUX_PAGING_SESSION_NAME 2>/dev/null; " +
                "tmux new-session -d -s $TMUX_PAGING_SESSION_NAME /bin/sh -c '" +
                "sleep 1; i=1; while [ \"\$i\" -le $TMUX_SMOOTH_ROW_COUNT ]; do " +
                "printf \"${TMUX_SMOOTH_PREFIX}%04d\\n\" \"\$i\"; " +
                "i=\$((i+1)); done; while :; do sleep 3600; done'; " +
                "tmux set-option -t $TMUX_PAGING_SESSION_NAME history-limit 10000; " +
                "until [ \"\$(tmux display-message -p -t $TMUX_PAGING_SESSION_NAME " +
                "'#{history_size}')\" -ge 4900 ]; do sleep 1; done; " +
                "printf '$TMUX_PAGING_READY\\n'"
        const val MIN_SUB_ROW_MOVEMENT_PX = 0.5f
        const val READER_ANCHOR_TOLERANCE_PX = 0.05f
        const val MAX_READER_SETUP_FLINGS = 4
        const val MIN_FLING_TRAVEL_PX = 4f
        const val FLING_CATCH_TOLERANCE_PX = 1.5f

        fun firstExpectedRow(): String = "SSH_SCROLL_001"

        fun lastExpectedRow(): String = "SSH_SCROLL_200"

        fun firstExpectedCodexRow(): String = "CODEX_SCROLL_001"

        fun lastExpectedCodexRow(): String = "CODEX_SCROLL_200"

        fun firstExpectedTmuxRow(): String = "TMUX_SCROLL_001"

        fun lastExpectedTmuxRow(): String = "TMUX_SCROLL_200"

        fun firstExpectedSmoothTmuxRow(): String = "TMUX_SMOOTH_0001"

        fun lastExpectedSmoothTmuxRow(): String = "TMUX_SMOOTH_5000"

        fun checkpoint(message: String) {
            Log.i("SshRealScrollE2E", message)
        }
    }
}

private fun TerminalController.indexOfLine(expected: String): Int =
    (0 until lineCount()).firstOrNull { lineAt(it)?.text?.trimEnd() == expected } ?: -1

private fun TerminalController.indexOfLineContaining(expected: String): Int =
    (0 until lineCount()).firstOrNull { lineAt(it)?.text?.contains(expected) == true } ?: -1

private fun TerminalController.diagnostic(message: String): String {
    val visible = viewport.visibleRows(overscan = 0)
    val visibleTexts = (visible.first until visible.lastExclusive)
        .map { lineAt(it)?.text?.trimEnd().orEmpty() }
    val visibleTmuxRows = visibleTexts.filter { it.startsWith("TMUX_SCROLL_") }
    val screenStart = (lineCount() - terminalRows).coerceAtLeast(0)
    val screenHead = (screenStart until minOf(lineCount(), screenStart + 8))
        .map { lineAt(it)?.text?.trimEnd().orEmpty() }
    return "$message lineCount=${lineCount()}, historyLines=${buffer.lineCount()}, " +
        "maximumScrollY=${viewport.maximumScrollY}, scrollY=${viewport.scrollY}, " +
        "pendingLineCount=${pendingLineCount()}, mouseTracking=${isMouseTrackingEnabled()}, " +
        "terminalSize=${terminalColumns}x${terminalRows}, screenStart=$screenStart, " +
        "visibleRows=$visible, " +
        "visibleHead=${visibleTexts.take(8)}, visibleTail=${visibleTexts.takeLast(8)}, " +
        "visibleTmuxRange=${visibleTmuxRows.firstOrNull()}..${visibleTmuxRows.lastOrNull()}, " +
        "screenHead=$screenHead, " +
        "head=${(0 until minOf(lineCount(), 4)).map { lineAt(it)?.text?.trimEnd() }}, " +
        "tail=${(maxOf(0, lineCount() - 6) until lineCount()).map { lineAt(it)?.text?.trimEnd() }}"
}

private fun TerminalController.visibleContains(expected: String): Boolean {
    val visible = viewport.visibleRows(overscan = 0)
    return (visible.first until visible.lastExclusive).any { index ->
        lineAt(index)?.text?.trimEnd() == expected
    }
}

private fun TerminalController.visibleContainsText(expected: String): Boolean {
    val visible = viewport.visibleRows(overscan = 0)
    return (visible.first until visible.lastExclusive).any { index ->
        lineAt(index)?.text?.contains(expected) == true
    }
}

private fun TerminalController.visibleContainsPrefix(expected: String): Boolean {
    val visible = viewport.visibleRows(overscan = 0)
    return (visible.first until visible.lastExclusive).any { index ->
        lineAt(index)?.text?.trimEnd()?.startsWith(expected) == true
    }
}

private fun TerminalController.visibleNumberedRows(prefix: String): List<Int> {
    val visible = viewport.visibleRows(overscan = 0)
    return (visible.first until visible.lastExclusive).mapNotNull { index ->
        val text = lineAt(index)?.text.orEmpty()
        val markerStart = text.indexOf(prefix).takeIf { it >= 0 } ?: return@mapNotNull null
        text.substring(markerStart + prefix.length)
            .take(3)
            .takeIf { it.length == 3 }
            ?.toIntOrNull()
    }
}

private fun findFastTerminalView(view: View): FastTerminalView? {
    if (view is FastTerminalView) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
        findFastTerminalView(view.getChildAt(index))?.let { return it }
    }
    return null
}

private fun clickAccessibilityNodeWithDescription(
    instrumentation: android.app.Instrumentation,
    description: String,
): Boolean {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
    val node = findAccessibilityNodeWithDescription(root, description) ?: return false
    val bounds = Rect().also(node::getBoundsInScreen)
    if (bounds.isEmpty) return false
    val eventTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(
        eventTime,
        eventTime,
        MotionEvent.ACTION_DOWN,
        bounds.exactCenterX(),
        bounds.exactCenterY(),
        0,
    )
    val up = MotionEvent.obtain(
        eventTime,
        eventTime + 50L,
        MotionEvent.ACTION_UP,
        bounds.exactCenterX(),
        bounds.exactCenterY(),
        0,
    )
    return try {
        instrumentation.sendPointerSync(down)
        instrumentation.sendPointerSync(up)
        true
    } finally {
        down.recycle()
        up.recycle()
    }
}

private fun findAccessibilityNodeWithDescription(
    node: AccessibilityNodeInfo,
    description: String,
): AccessibilityNodeInfo? {
    if (node.contentDescription?.toString() == description) return node
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        findAccessibilityNodeWithDescription(child, description)?.let { return it }
    }
    return null
}

private fun accessibilitySummary(instrumentation: android.app.Instrumentation): String {
    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "accessibilityRoot=null"
    val nodes = mutableListOf<String>()
    fun collect(node: AccessibilityNodeInfo) {
        if (nodes.size >= 30) return
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() || description.isNotBlank()) {
            nodes += "${node.className}(text=$text, description=$description)"
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(::collect)
        }
    }
    collect(root)
    return "package=${root.packageName}, nodes=$nodes"
}

private suspend fun dispatchSwipeDownThroughWindow(
    scenario: ActivityScenario<MainActivity>,
    instrumentation: android.app.Instrumentation,
    terminal: FastTerminalView,
) {
    var x = 0f
    var startY = 0f
    var endY = 0f
    scenario.onActivity {
        val terminalLocation = IntArray(2).also(terminal::getLocationOnScreen)
        x = terminalLocation[0] + terminal.width / 2f
        startY = terminalLocation[1] + terminal.height * 0.25f
        endY = terminalLocation[1] + terminal.height * 0.85f
    }
    val downTime = SystemClock.uptimeMillis()
    val points = listOf(
        MotionEvent.ACTION_DOWN to startY,
        MotionEvent.ACTION_MOVE to startY * 0.75f + endY * 0.25f,
        MotionEvent.ACTION_MOVE to (startY + endY) / 2f,
        MotionEvent.ACTION_MOVE to startY * 0.25f + endY * 0.75f,
        MotionEvent.ACTION_UP to endY,
    )
    points.forEachIndexed { index, (action, y) ->
        if (index > 0) delay(25L)
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            x,
            y,
            0,
        )
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }
}

private suspend fun dispatchSwipeUpThroughWindow(
    scenario: ActivityScenario<MainActivity>,
    instrumentation: android.app.Instrumentation,
    terminal: FastTerminalView,
) {
    var x = 0f
    var startY = 0f
    var endY = 0f
    scenario.onActivity {
        val terminalLocation = IntArray(2).also(terminal::getLocationOnScreen)
        x = terminalLocation[0] + terminal.width / 2f
        startY = terminalLocation[1] + terminal.height * 0.85f
        endY = terminalLocation[1] + terminal.height * 0.25f
    }
    val downTime = SystemClock.uptimeMillis()
    val points = listOf(
        MotionEvent.ACTION_DOWN to startY,
        MotionEvent.ACTION_MOVE to startY * 0.75f + endY * 0.25f,
        MotionEvent.ACTION_MOVE to (startY + endY) / 2f,
        MotionEvent.ACTION_MOVE to startY * 0.25f + endY * 0.75f,
        MotionEvent.ACTION_UP to endY,
    )
    points.forEachIndexed { index, (action, y) ->
        if (index > 0) delay(25L)
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            x,
            y,
            0,
        )
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }
}

private data class SubRowDragEvidence(
    val lineHeightPx: Float,
    val startScrollY: Float,
    val startMaximumScrollY: Float,
    val afterFirstMoveScrollY: Float,
    val afterFirstMoveMaximumScrollY: Float,
    val afterSecondMoveScrollY: Float,
    val afterSecondMoveMaximumScrollY: Float,
) {
    val startDistanceFromBottomPx: Float
        get() = startMaximumScrollY - startScrollY
    val afterSecondMoveDistanceFromBottomPx: Float
        get() = afterSecondMoveMaximumScrollY - afterSecondMoveScrollY

}

private suspend fun dispatchSubRowDragThroughWindow(
    scenario: ActivityScenario<MainActivity>,
    instrumentation: android.app.Instrumentation,
    terminal: FastTerminalView,
    controller: TerminalController,
): SubRowDragEvidence {
    var x = 0f
    var startY = 0f
    var lineHeightPx = 0f
    var startScrollY = 0f
    var startMaximumScrollY = 0f
    scenario.onActivity {
        val location = IntArray(2).also(terminal::getLocationOnScreen)
        x = location[0] + terminal.width / 2f
        startY = location[1] + terminal.height * 0.35f
        lineHeightPx = controller.viewport.lineHeightPx
        startScrollY = controller.viewport.scrollY
        startMaximumScrollY = controller.viewport.maximumScrollY
    }
    val downTime = SystemClock.uptimeMillis()
    dispatchPointer(instrumentation, downTime, MotionEvent.ACTION_DOWN, x, startY)
    delay(80L)
    dispatchPointer(
        instrumentation,
        downTime,
        MotionEvent.ACTION_MOVE,
        x,
        startY + lineHeightPx * 0.85f,
    )
    delay(32L)
    var afterFirstMoveScrollY = 0f
    var afterFirstMoveMaximumScrollY = 0f
    scenario.onActivity {
        afterFirstMoveScrollY = controller.viewport.scrollY
        afterFirstMoveMaximumScrollY = controller.viewport.maximumScrollY
    }
    dispatchPointer(
        instrumentation,
        downTime,
        MotionEvent.ACTION_MOVE,
        x,
        startY + lineHeightPx * 1.10f,
    )
    delay(32L)
    var afterSecondMoveScrollY = 0f
    var afterSecondMoveMaximumScrollY = 0f
    scenario.onActivity {
        afterSecondMoveScrollY = controller.viewport.scrollY
        afterSecondMoveMaximumScrollY = controller.viewport.maximumScrollY
    }
    delay(120L)
    dispatchPointer(
        instrumentation,
        downTime,
        MotionEvent.ACTION_UP,
        x,
        startY + lineHeightPx * 1.10f,
    )
    return SubRowDragEvidence(
        lineHeightPx = lineHeightPx,
        startScrollY = startScrollY,
        startMaximumScrollY = startMaximumScrollY,
        afterFirstMoveScrollY = afterFirstMoveScrollY,
        afterFirstMoveMaximumScrollY = afterFirstMoveMaximumScrollY,
        afterSecondMoveScrollY = afterSecondMoveScrollY,
        afterSecondMoveMaximumScrollY = afterSecondMoveMaximumScrollY,
    )
}

private data class FlingInterruptEvidence(
    val releaseScrollY: Float,
    val afterFlingScrollY: Float,
    val caughtScrollY: Float,
    val afterCatchWaitScrollY: Float,
    val gestureDiagnostic: TmuxScrollGestureDiagnostic,
)

private suspend fun dispatchFlingAndInterruptThroughWindow(
    scenario: ActivityScenario<MainActivity>,
    instrumentation: android.app.Instrumentation,
    terminal: FastTerminalView,
    controller: TerminalController,
): FlingInterruptEvidence {
    var x = 0f
    var startY = 0f
    var endY = 0f
    scenario.onActivity {
        val location = IntArray(2).also(terminal::getLocationOnScreen)
        x = location[0] + terminal.width / 2f
        startY = location[1] + terminal.height * 0.25f
        endY = location[1] + terminal.height * 0.82f
    }
    val downTime = SystemClock.uptimeMillis()
    dispatchPointer(instrumentation, downTime, MotionEvent.ACTION_DOWN, x, startY)
    listOf(0.25f, 0.50f, 0.75f, 1f).forEach { progress ->
        delay(12L)
        dispatchPointer(
            instrumentation,
            downTime,
            MotionEvent.ACTION_MOVE,
            x,
            startY + (endY - startY) * progress,
        )
    }
    delay(12L)
    dispatchPointer(instrumentation, downTime, MotionEvent.ACTION_UP, x, endY)
    var releaseScrollY = 0f
    scenario.onActivity { releaseScrollY = controller.viewport.scrollY }
    delay(120L)
    var afterFlingScrollY = 0f
    var gestureDiagnostic: TmuxScrollGestureDiagnostic? = null
    scenario.onActivity {
        afterFlingScrollY = controller.viewport.scrollY
        gestureDiagnostic = terminal.tmuxScrollGestureDiagnostic()
    }

    val catchTime = SystemClock.uptimeMillis()
    dispatchPointer(instrumentation, catchTime, MotionEvent.ACTION_DOWN, x, endY)
    var caughtScrollY = 0f
    scenario.onActivity { caughtScrollY = controller.viewport.scrollY }
    delay(180L)
    var afterCatchWaitScrollY = 0f
    scenario.onActivity { afterCatchWaitScrollY = controller.viewport.scrollY }
    dispatchPointer(instrumentation, catchTime, MotionEvent.ACTION_UP, x, endY)
    return FlingInterruptEvidence(
        releaseScrollY = releaseScrollY,
        afterFlingScrollY = afterFlingScrollY,
        caughtScrollY = caughtScrollY,
        afterCatchWaitScrollY = afterCatchWaitScrollY,
        gestureDiagnostic = requireNotNull(gestureDiagnostic),
    )
}

private fun dispatchPointer(
    instrumentation: android.app.Instrumentation,
    downTime: Long,
    action: Int,
    x: Float,
    y: Float,
) {
    val event = MotionEvent.obtain(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        x,
        y,
        0,
    )
    try {
        instrumentation.sendPointerSync(event)
    } finally {
        event.recycle()
    }
}

private fun dispatchEnterThroughWindow(
    scenario: ActivityScenario<MainActivity>,
    terminal: FastTerminalView,
): Boolean {
    var handled = false
    scenario.onActivity {
        terminal.requestFocus()
        handled = terminal.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        terminal.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
    return handled
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
