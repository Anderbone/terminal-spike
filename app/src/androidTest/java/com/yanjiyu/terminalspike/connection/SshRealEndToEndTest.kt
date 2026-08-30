package com.yanjiyu.terminalspike.connection

import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
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

            if (codexCommand != null) {
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    while (
                        (0 until controller.lineCount()).none { index ->
                            controller.lineAt(index)?.text?.contains("bash-5.3$") == true
                        }
                    ) {
                        delay(25L)
                    }
                }
            }

            val submittedRows = if (codexCommand != null) {
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
                controller.sendPaste(COMMAND)
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
                    "Production controller rejected the tmux scrollback command.",
                    controller.sendPaste(TMUX_COMMAND),
                )
                withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                    while (
                        controller.indexOfLine(lastExpectedTmuxRow()) < 0 ||
                        !controller.isMouseTrackingEnabled()
                    ) {
                        delay(10L)
                    }
                }
                assertTrue(
                    controller.diagnostic("tmux did not negotiate terminal mouse tracking."),
                    controller.isMouseTrackingEnabled(),
                )
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
            "The Mosh extension must be absent for this release-isolation proof.",
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
        const val SWIPE_SETTLE_MILLIS = 16L
        const val TMUX_SWIPE_SETTLE_MILLIS = 150L
        const val TMUX_OUTPUT_DRAIN_TIMEOUT_MILLIS = 10_000L
        const val CODEX_START_TIMEOUT_MILLIS = 30_000L
        const val CODEX_INPUT_READY_SETTLE_MILLIS = 1_000L
        const val CODEX_PASTE_SETTLE_MILLIS = 250L
        const val CODEX_SUBMIT_TIMEOUT_MILLIS = 10_000L
        const val REAL_SCROLL_TEST_TIMEOUT_MILLIS = 360_000L
        const val MOSH_EXTENSION_PACKAGE = "com.yanjiyu.terminalspike.mosh"
        const val MARKER = "SSH_SCROLL_DONE"
        const val COMMAND =
            "i=1; while [ \"\$i\" -le $OUTPUT_ROW_COUNT ]; do " +
                "printf 'SSH_SCROLL_%03d\\n' \"\$i\"; i=\$((i+1)); done; " +
                "printf 'SSH_SCROLL_%s\\n' DONE\n"
        const val CODEX_200_LINE_PROMPT =
            "Reply with exactly 200 lines and no other text. For each integer N from 1 through " +
                "200, line N must be CODEX_SCROLL_NNN where NNN is zero-padded to three digits."
        const val TMUX_SESSION_NAME = "terminal-spike-e2e"
        const val TMUX_COMMAND =
            "tmux kill-session -t $TMUX_SESSION_NAME 2>/dev/null; " +
                "tmux new-session -d -s $TMUX_SESSION_NAME " +
                "\"i=1; while [ \\\$i -le $OUTPUT_ROW_COUNT ]; do " +
                "printf 'TMUX_SCROLL_%03d\\n' \\\$i; i=\\\$((i+1)); done; exec sh\"; " +
                "tmux set-option -t $TMUX_SESSION_NAME -g mouse on; " +
                "tmux attach-session -t $TMUX_SESSION_NAME\n"

        fun firstExpectedRow(): String = "SSH_SCROLL_001"

        fun lastExpectedRow(): String = "SSH_SCROLL_200"

        fun firstExpectedCodexRow(): String = "CODEX_SCROLL_001"

        fun lastExpectedCodexRow(): String = "CODEX_SCROLL_200"

        fun firstExpectedTmuxRow(): String = "TMUX_SCROLL_001"

        fun lastExpectedTmuxRow(): String = "TMUX_SCROLL_200"

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

private fun TerminalController.visibleContainsPrefix(expected: String): Boolean {
    val visible = viewport.visibleRows(overscan = 0)
    return (visible.first until visible.lastExclusive).any { index ->
        lineAt(index)?.text?.trimEnd()?.startsWith(expected) == true
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
