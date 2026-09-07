package com.yanjiyu.terminalspike.connection

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.terminal.view.TerminalScrollDecisionReason
import com.yanjiyu.terminalspike.terminal.view.TerminalScrollDestination
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Opt-in proof of the complete SSH-bootstrap -> Binder/PFD -> native UDP Mosh path.
 *
 * The fixture password is supplied only as an instrumentation argument. The test is skipped during
 * ordinary connected suites so no credential or machine-specific endpoint is committed to source.
 */
@RunWith(AndroidJUnit4::class)
class MoshRealEndToEndTest {
    @Test(timeout = TMUX_RECREATE_TEST_TIMEOUT_MILLIS)
    fun appSelectedTmuxLocalScrollAndReaderAnchorSurviveActivityRecreation() = runBlocking {
        val fixture = fixtureOrSkip("app-selected tmux Activity recreation")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val repository = application.container.sshSessionRepository
        val passwordBytes = fixture.password.takeIf(String::isNotEmpty)?.encodeToByteArray()
        repository.disconnectAll()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedSessionId: Long? = null

        try {
            val result = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Real Mosh tmux recreation",
                    workspaceName = "Real Mosh tmux recreation",
                    connection = RemoteSessionConnectionRequest.Mosh(
                        MoshBootstrapRequest(
                            ssh = SshConnectionConfig(
                                host = fixture.host,
                                port = fixture.sshPort,
                                username = fixture.username,
                                authentication = fixture.privateKey?.let { keyBytes ->
                                    SshAuthentication.PrivateKey(
                                        identityName = "real-mosh-tmux-recreation",
                                        loadKey = { keyBytes.copyOf() },
                                        passphrase = null,
                                    )
                                } ?: SshAuthentication.Password(requireNotNull(passwordBytes)),
                                tmuxSessionSelectorEnabled = true,
                            ),
                            udpPortRange = MoshPortRange(fixture.udpFirst, fixture.udpLast),
                        ),
                    ),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = TMUX_RECREATE_ROW_COUNT + 256,
                    ),
                ),
            )
            val started = result as? StartSshSessionResult.Started
                ?: throw AssertionError("Production Mosh/tmux session did not start: $result")
            startedSessionId = started.sessionId
            val controller = repository.controllerFor(started.sessionId)
                ?: throw AssertionError("Production Mosh/tmux controller is missing.")
            scenario.onActivity { it.openTerminalSession(started.sessionId) }
            var mountedTerminal = awaitMountedTerminal(scenario)

            withTimeout(CONCURRENT_CONNECT_TIMEOUT_MILLIS) {
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
                            fail("Mosh disconnected before app-selected tmux became ready.")
                        ConnectionState.Connecting,
                        is ConnectionState.Reconnecting,
                        null,
                        -> Unit
                    }
                    delay(10L)
                }
            }
            delay(TMUX_ATTACH_SETTLE_MILLIS)
            assertTrue(
                "Production controller rejected the real Mosh/tmux history workload.",
                controller.sendPaste(TMUX_RECREATE_ROWS_COMMAND, appendEnter = true),
            )
            val workloadCompleted = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfExactLine(TMUX_RECREATE_DONE_MARKER) < 0) delay(20L)
                true
            }
            assertTrue(
                "The real Mosh/tmux workload completion marker did not reach the terminal.",
                workloadCompleted == true,
            )
            delay(TMUX_HISTORY_SETTLE_MILLIS)

            controller.updateRendererProfile(
                TerminalRendererProfile(
                    jumpToBottomOnKeyboardInput = false,
                    keepViewportPositionOnOutput = true,
                ),
            )
            controller.jumpToBottom()
            val initialBottom = controller.viewport.visibleRows(overscan = 0)
            val firstRow = controller.indexOfExactLine(firstExpectedTmuxRecreateRow())
            val lastRow = controller.lastIndexOfExactLine(lastExpectedTmuxRecreateRow())
            assertTrue("The real Mosh/tmux workload did not retain its first row.", firstRow >= 0)
            assertFalse(
                "The oldest real Mosh/tmux row was visible before the first gesture.",
                firstRow in initialBottom.first until initialBottom.lastExclusive,
            )
            assertTrue(
                "The newest real Mosh/tmux row was not visible at live bottom.",
                lastRow in initialBottom.first until initialBottom.lastExclusive,
            )

            val initialScrollY = controller.viewport.scrollY
            dispatchMoshTmuxSwipeDown(scenario, instrumentation, mountedTerminal)
            val firstGesture = mountedTerminal.tmuxScrollGestureDiagnostic()
            assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, firstGesture.destination)
            assertEquals(TerminalScrollDecisionReason.AUTO_TMUX_LOCAL_READY, firstGesture.reason)
            assertEquals(0, firstGesture.remoteWheelReports)
            assertTrue(firstGesture.localScrollUpdates > 0)
            assertTrue(
                "The first physical Mosh/tmux gesture did not move toward older local history.",
                controller.viewport.scrollY < initialScrollY,
            )

            // ACTION_UP starts a native fling. Measure a resting reader before recreation,
            // otherwise animation frames between this snapshot and detach change the anchor.
            // This wait happens only after the first-gesture route and movement assertions.
            withTimeout(10_000L) {
                var previousY = Float.NaN
                var stableSince = SystemClock.uptimeMillis()
                while (true) {
                    var currentY = Float.NaN
                    scenario.onActivity { currentY = controller.viewport.scrollY }
                    if (currentY != previousY) stableSince = SystemClock.uptimeMillis()
                    if (SystemClock.uptimeMillis() - stableSince >= 300L) break
                    previousY = currentY
                    delay(25L)
                }
            }
            val visibleBeforeRecreate = controller.viewport.visibleRows(overscan = 0)
            val anchorBeforeRecreate = controller.selectionLineAt(visibleBeforeRecreate.first)?.anchor
                ?: throw AssertionError("The Mosh/tmux reader had no stable top anchor.")
            val scrollBeforeRecreate = controller.viewport.scrollY
            val originalView = mountedTerminal
            scenario.recreate()
            scenario.onActivity { it.openTerminalSession(started.sessionId) }
            mountedTerminal = awaitMountedTerminal(scenario)
            assertTrue("Activity recreation reused the detached terminal view.", mountedTerminal !== originalView)
            assertTrue(
                "Activity recreation replaced the application-owned terminal controller.",
                repository.controllerFor(started.sessionId) === controller,
            )
            val visibleAfterRecreate = controller.viewport.visibleRows(overscan = 0)
            assertEquals(
                "Activity recreation changed the Mosh/tmux reader anchor.",
                anchorBeforeRecreate,
                controller.selectionLineAt(visibleAfterRecreate.first)?.anchor,
            )
            assertEquals(
                "Activity recreation changed the Mosh/tmux reader pixel position.",
                scrollBeforeRecreate,
                controller.viewport.scrollY,
                0.5f,
            )

            assertTrue(
                "The recreated Mosh/tmux session rejected later live output.",
                controller.sendPaste(TMUX_RECREATE_REFRESH_COMMAND, appendEnter = true),
            )
            val refreshCompleted = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (controller.indexOfExactLine(TMUX_RECREATE_REFRESH_MARKER) < 0) delay(20L)
                true
            }
            assertTrue(
                "Post-recreation live Mosh/tmux output did not reach the terminal.",
                refreshCompleted == true,
            )
            delay(TMUX_HISTORY_SETTLE_MILLIS)
            val visibleAfterRefresh = controller.viewport.visibleRows(overscan = 0)
            assertEquals(
                "A post-recreation tmux history refresh changed the reader anchor.",
                anchorBeforeRecreate,
                controller.selectionLineAt(visibleAfterRefresh.first)?.anchor,
            )
            assertEquals(
                "A post-recreation tmux history refresh changed the reader pixel position.",
                scrollBeforeRecreate,
                controller.viewport.scrollY,
                0.5f,
            )
            assertFalse("Post-recreation output pulled the reader to live bottom.", controller.viewport.autoFollow)

            val numberedRowPattern = Regex("${TMUX_RECREATE_PREFIX}[0-9]{4}")
            val retainedRows = (0 until controller.lineCount()).mapNotNull { index ->
                controller.lineAt(index)?.text?.trimEnd()
                    ?.takeIf(numberedRowPattern::matches)
            }
            assertEquals(
                "The real Mosh/tmux transcript must retain exactly the generated row count.",
                TMUX_RECREATE_ROW_COUNT,
                retainedRows.size,
            )
            retainedRows.forEachIndexed { index, row ->
                assertEquals(
                    "The real Mosh/tmux transcript diverged at one-based row ${index + 1}.",
                    "$TMUX_RECREATE_PREFIX%04d".format(index + 1),
                    row,
                )
            }

            controller.jumpToBottom()
            val recreatedBottom = controller.viewport.scrollY
            dispatchMoshTmuxSwipeDown(scenario, instrumentation, mountedTerminal)
            val recreatedGesture = mountedTerminal.tmuxScrollGestureDiagnostic()
            assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, recreatedGesture.destination)
            assertEquals(TerminalScrollDecisionReason.AUTO_TMUX_LOCAL_READY, recreatedGesture.reason)
            assertEquals(0, recreatedGesture.remoteWheelReports)
            assertTrue(recreatedGesture.localScrollUpdates > 0)
            assertTrue(controller.viewport.scrollY < recreatedBottom)

            controller.jumpToBottom()
            assertTrue(
                "The real tmux fixture rejected its mouse-mode setup.",
                controller.sendPaste(TMUX_ENABLE_MOUSE_COMMAND, appendEnter = true),
            )
            val mouseTrackingEnabled = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (!controller.isMouseTrackingEnabled()) delay(TMUX_METADATA_POLL_MILLIS)
                true
            }
            assertTrue(
                "The real tmux fixture did not negotiate terminal mouse tracking.",
                mouseTrackingEnabled == true,
            )
            controller.send(byteArrayOf(TMUX_PREFIX_CONTROL_BYTE))
            delay(TMUX_PREFIX_SETTLE_MILLIS)
            controller.send(byteArrayOf(TMUX_COPY_MODE_KEY.code.toByte()))
            val copyModeEntered = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) {
                while (!controller.isTmuxPaneInMode()) {
                    controller.requestTmuxHistoryRefresh()
                    delay(TMUX_METADATA_POLL_MILLIS)
                }
                true
            }
            assertTrue("The real tmux fixture did not enter copy mode.", copyModeEntered == true)

            controller.updateRendererProfile(TerminalRendererProfile(touchScrollMode = TouchScrollMode.AUTO))
            controller.jumpToBottom()
            dispatchMoshTmuxSwipeDown(scenario, instrumentation, mountedTerminal)
            val autoCopyModeGesture = mountedTerminal.tmuxScrollGestureDiagnostic()
            assertEquals(TerminalScrollDestination.LOCAL_SCROLLBACK, autoCopyModeGesture.destination)
            assertEquals(
                TerminalScrollDecisionReason.AUTO_TMUX_COPY_MODE_LOCAL_READY,
                autoCopyModeGesture.reason,
            )
            assertEquals(0, autoCopyModeGesture.remoteWheelReports)
            assertTrue(autoCopyModeGesture.localScrollUpdates > 0)
            controller.requestTmuxHistoryRefresh()
            delay(TMUX_HISTORY_SETTLE_MILLIS)
            assertTrue(
                "Auto local scrolling unexpectedly exited or drove pre-existing tmux copy mode.",
                controller.isTmuxPaneInMode(),
            )

            controller.updateRendererProfile(
                TerminalRendererProfile(touchScrollMode = TouchScrollMode.REMOTE_MOUSE),
            )
            dispatchMoshTmuxSwipeDown(scenario, instrumentation, mountedTerminal)
            val explicitCopyModeGesture = mountedTerminal.tmuxScrollGestureDiagnostic()
            assertEquals(TerminalScrollDestination.REMOTE_MOUSE, explicitCopyModeGesture.destination)
            assertEquals(
                TerminalScrollDecisionReason.EXPLICIT_REMOTE_MOUSE,
                explicitCopyModeGesture.reason,
            )
            assertTrue(explicitCopyModeGesture.remoteWheelReports > 0)
            assertEquals(0, explicitCopyModeGesture.localScrollUpdates)
            controller.send("q".encodeToByteArray())
        } finally {
            startedSessionId?.let(repository::close)
            scenario.close()
            passwordBytes?.let { secret ->
                assertTrue("Repository Mosh ownership must wipe its password.", secret.all { it == 0.toByte() })
            }
            fixture.privateKey?.fill(0)
        }
    }

    @Test
    fun realServerCarriesInteractiveTerminalBytesThroughTheExtension() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and either $ARG_PASSWORD or " +
                "$ARG_PRIVATE_KEY_BASE64 to run the real Mosh fixture.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotEmpty() || privateKeyBytes != null),
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
                    authentication = privateKeyBytes?.let { keyBytes ->
                        SshAuthentication.PrivateKey(
                            identityName = "real-mosh-e2e",
                            loadKey = { keyBytes.copyOf() },
                            passphrase = null,
                        )
                    } ?: SshAuthentication.Password(password.encodeToByteArray()),
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
            privateKeyBytes?.fill(0)
            connection.close()
            states.close()
            output.close()
            if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { connectionJob.await() } == null) {
                connectionJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun fourConcurrentSessionsResizeIndependentlyAndOneCloseDoesNotStopTheOthers() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass $ARG_HOST, $ARG_USERNAME and either $ARG_PASSWORD or " +
                "$ARG_PRIVATE_KEY_BASE64 to run the concurrent real Mosh fixture.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotEmpty() || privateKeyBytes != null),
        )
        val sshPort = arguments.getString(ARG_SSH_PORT)?.toIntOrNull() ?: DEFAULT_SSH_PORT
        val udpFirst = arguments.getString(ARG_UDP_FIRST)?.toIntOrNull() ?: DEFAULT_UDP_FIRST
        val udpLast = arguments.getString(ARG_UDP_LAST)?.toIntOrNull() ?: DEFAULT_UDP_LAST
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TerminalSpikeApplication
        val passwordBytes = MutableList(CONCURRENT_SESSION_COUNT) {
            password.takeIf(String::isNotEmpty)?.encodeToByteArray()
        }
        val connections = List(CONCURRENT_SESSION_COUNT) { index ->
            MoshConnection(
                bootstrapExecutor = MoshBootstrapExecutor(
                    KnownHostManager(EphemeralKnownHostTrustStore()),
                ),
                extensionClient = application.container.moshExtension,
                bootstrapRequest = MoshBootstrapRequest(
                    ssh = SshConnectionConfig(
                        host = host,
                        port = sshPort,
                        username = username,
                        authentication = privateKeyBytes?.let { keyBytes ->
                            SshAuthentication.PrivateKey(
                                identityName = "real-mosh-concurrent-${index + 1}",
                                loadKey = { keyBytes.copyOf() },
                                passphrase = null,
                            )
                        } ?: SshAuthentication.Password(requireNotNull(passwordBytes[index])),
                    ),
                    udpPortRange = MoshPortRange(udpFirst, udpLast),
                ),
            )
        }
        val states = List(CONCURRENT_SESSION_COUNT) { Channel<ConnectionState>(Channel.UNLIMITED) }
        val outputs = List(CONCURRENT_SESSION_COUNT) { Channel<ByteArray>(Channel.UNLIMITED) }
        val jobs = connections.indices.map { index ->
            async(Dispatchers.IO) {
                connections[index].connect(
                    columns = INITIAL_COLUMNS,
                    rows = INITIAL_ROWS,
                    onBytes = { bytes -> outputs[index].trySend(bytes.copyOf()) },
                    onState = { state ->
                        if (state is ConnectionState.AwaitingApproval) {
                            (state.prompt as? HostIdentityPrompt)?.let { prompt ->
                                connections[index].answerHostIdentityPrompt(
                                    prompt.promptToken,
                                    HostIdentityDecision.TrustAndSave,
                                )
                            }
                        }
                        states[index].trySend(state)
                    },
                )
            }
        }

        try {
            states.map { channel -> async { awaitConnected(channel) } }.awaitAll()

            val resizedMarkers = connections.indices.map { index ->
                val columns = RESIZED_COLUMNS + index
                val rows = RESIZED_ROWS + index
                val marker = "MOSH_MULTI_${index + 1}_RESIZED"
                connections[index].resize(columns, rows)
                assertTrue(
                    "Concurrent Mosh session ${index + 1} rejected its resize probe.",
                    connections[index].sendWithAcceptance(
                        resizeProbeCommand(rows, columns, marker).encodeToByteArray(),
                    ),
                )
                Triple(index, marker, columns to rows)
            }
            resizedMarkers.map { (index, marker, dimensions) ->
                async {
                    awaitExactTerminalLine(
                        output = outputs[index],
                        marker = marker,
                        columns = dimensions.first,
                        rows = dimensions.second,
                    )
                }
            }.awaitAll()

            connections.first().close()
            withTimeout(CLOSE_TIMEOUT_MILLIS) { jobs.first().await() }

            connections.indices.drop(1).map { index ->
                val marker = "MOSH_MULTI_${index + 1}_SURVIVED"
                assertTrue(
                    "Closing the first Mosh worker stopped session ${index + 1}.",
                    connections[index].sendWithAcceptance(markerCommand(marker).encodeToByteArray()),
                )
                async {
                    awaitExactTerminalLine(
                        output = outputs[index],
                        marker = marker,
                        columns = RESIZED_COLUMNS + index,
                        rows = RESIZED_ROWS + index,
                    )
                }
            }.awaitAll()
        } finally {
            connections.forEach(MoshConnection::close)
            states.forEach { it.close() }
            outputs.forEach { it.close() }
            jobs.forEach { job ->
                if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { job.await() } == null) {
                    job.cancelAndJoin()
                }
            }
            privateKeyBytes?.fill(0)
        }
        passwordBytes.filterNotNull().forEach { secret ->
            assertTrue("Mosh connection ownership must wipe every password.", secret.all { it == 0.toByte() })
        }
    }

    @Test
    fun realWorkerDeathFailsOnlyItsSessionAndTheReleasedSlotIsReusable() = runBlocking {
        val fixture = fixtureOrSkip("worker-death")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TerminalSpikeApplication
        val first = startRealAttempt(application, fixture, "worker-death-first")
        var recovered: RealMoshAttempt? = null

        try {
            awaitConnected(first.states)
            val workerPid = awaitProcessPid("$MOSH_EXTENSION_PACKAGE:mosh_session_0")
            killExtensionProcess(workerPid)
            val failure = awaitFailure(first.states)
            assertTrue(
                "Real worker death must be reported as an extension failure, not a clean EOF: ${failure.message}",
                failure.message == "Mosh transport stopped unexpectedly.",
            )
            withTimeout(CLOSE_TIMEOUT_MILLIS) { first.job.await() }

            recovered = startRealAttempt(application, fixture, "worker-death-recovery")
            awaitConnected(recovered.states)
            assertMarkerRoundTrip(recovered, "MOSH_WORKER_SLOT_REUSED")
        } finally {
            closeAttempt(first)
            recovered?.let { closeAttempt(it) }
            fixture.privateKey?.fill(0)
        }
    }

    @Test
    fun realBrokerDeathFailsTheSessionThenClientRebindsForFreshTraffic() = runBlocking {
        val fixture = fixtureOrSkip("broker-death")
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as TerminalSpikeApplication
        val client = application.container.moshExtension
        val first = startRealAttempt(application, fixture, "broker-death-first")
        var recovered: RealMoshAttempt? = null

        try {
            awaitConnected(first.states)
            val observedError = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(REBIND_TIMEOUT_MILLIS) {
                    client.status.first { it is com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus.Error }
                }
            }
            val observedRecovery = async(start = CoroutineStart.UNDISPATCHED) {
                observedError.await()
                withTimeout(REBIND_TIMEOUT_MILLIS) {
                    client.status.first { it is com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus.Available }
                }
            }
            val brokerPid = awaitProcessPid("$MOSH_EXTENSION_PACKAGE:mosh_broker")
            killExtensionProcess(brokerPid)
            val failure = awaitFailure(first.states)
            assertTrue(
                "Real broker death must be reported as a bounded extension failure: ${failure.message}",
                failure.message == "Mosh transport stopped unexpectedly.",
            )
            withTimeout(CLOSE_TIMEOUT_MILLIS) { first.job.await() }
            observedError.await()
            observedRecovery.await()

            recovered = startRealAttempt(application, fixture, "broker-death-recovery")
            awaitConnected(recovered.states)
            assertMarkerRoundTrip(recovered, "MOSH_BROKER_REBOUND")
        } finally {
            closeAttempt(first)
            recovered?.let { closeAttempt(it) }
            fixture.privateKey?.fill(0)
        }
    }

    private fun fixtureOrSkip(label: String): RealMoshFixture {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString(ARG_HOST).orEmpty()
        val username = arguments.getString(ARG_USERNAME).orEmpty()
        val password = arguments.getString(ARG_PASSWORD).orEmpty()
        val privateKeyBytes = arguments.getString(ARG_PRIVATE_KEY_BASE64)
            ?.takeIf(String::isNotBlank)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        assumeTrue(
            "Pass the real Mosh fixture arguments to run $label acceptance.",
            host.isNotBlank() && username.isNotBlank() &&
                (password.isNotEmpty() || privateKeyBytes != null),
        )
        return RealMoshFixture(
            host = host,
            sshPort = arguments.getString(ARG_SSH_PORT)?.toIntOrNull() ?: DEFAULT_SSH_PORT,
            username = username,
            password = password,
            privateKey = privateKeyBytes,
            udpFirst = arguments.getString(ARG_UDP_FIRST)?.toIntOrNull() ?: DEFAULT_UDP_FIRST,
            udpLast = arguments.getString(ARG_UDP_LAST)?.toIntOrNull() ?: DEFAULT_UDP_LAST,
        )
    }

    private fun CoroutineScope.startRealAttempt(
        application: TerminalSpikeApplication,
        fixture: RealMoshFixture,
        identityName: String,
    ): RealMoshAttempt {
        val passwordBytes = fixture.password.takeIf(String::isNotEmpty)?.encodeToByteArray()
        val connection = MoshConnection(
            bootstrapExecutor = MoshBootstrapExecutor(
                KnownHostManager(EphemeralKnownHostTrustStore()),
            ),
            extensionClient = application.container.moshExtension,
            bootstrapRequest = MoshBootstrapRequest(
                ssh = SshConnectionConfig(
                    host = fixture.host,
                    port = fixture.sshPort,
                    username = fixture.username,
                    authentication = fixture.privateKey?.let { keyBytes ->
                        SshAuthentication.PrivateKey(
                            identityName = identityName,
                            loadKey = { keyBytes.copyOf() },
                            passphrase = null,
                        )
                    } ?: SshAuthentication.Password(requireNotNull(passwordBytes)),
                ),
                udpPortRange = MoshPortRange(fixture.udpFirst, fixture.udpLast),
            ),
        )
        val states = Channel<ConnectionState>(Channel.UNLIMITED)
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        val job = async(Dispatchers.IO) {
            connection.connect(
                columns = INITIAL_COLUMNS,
                rows = INITIAL_ROWS,
                onBytes = { bytes -> output.trySend(bytes.copyOf()) },
                onState = { state ->
                    if (state is ConnectionState.AwaitingApproval) {
                        (state.prompt as? HostIdentityPrompt)?.let { prompt ->
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
        return RealMoshAttempt(connection, states, output, job, passwordBytes)
    }

    private suspend fun awaitFailure(states: Channel<ConnectionState>): ConnectionState.Failed =
        withTimeout(PROCESS_DEATH_TIMEOUT_MILLIS) {
            while (true) {
                when (val state = states.receive()) {
                    is ConnectionState.Failed -> return@withTimeout state
                    ConnectionState.Disconnected -> fail("Process death was incorrectly reported as a clean disconnect.")
                    ConnectionState.Connecting,
                    ConnectionState.Connected,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.AwaitingApproval,
                    -> Unit
                }
            }
            error("Unreachable")
        }

    private suspend fun assertMarkerRoundTrip(attempt: RealMoshAttempt, marker: String) {
        assertTrue(
            "Recovered Mosh session rejected terminal input.",
            attempt.connection.sendWithAcceptance(markerCommand(marker).encodeToByteArray()),
        )
        awaitExactTerminalLine(
            output = attempt.output,
            marker = marker,
            columns = INITIAL_COLUMNS,
            rows = INITIAL_ROWS,
        )
    }

    private suspend fun awaitProcessPid(processName: String): String =
        withTimeout(PROCESS_DEATH_TIMEOUT_MILLIS) {
            while (true) {
                val pid = executeShellCommand("pidof $processName").trim()
                if (pid.matches(Regex("[1-9][0-9]*"))) return@withTimeout pid
                delay(PROCESS_POLL_MILLIS)
            }
            error("Unreachable")
        }

    private fun killExtensionProcess(pid: String) {
        require(pid.matches(Regex("[1-9][0-9]*")))
        executeShellCommand("run-as $MOSH_EXTENSION_PACKAGE kill -9 $pid")
    }

    private fun executeShellCommand(command: String): String {
        val descriptor: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader -> reader.readText() }
        }
    }

    private suspend fun closeAttempt(attempt: RealMoshAttempt) {
        attempt.connection.close()
        if (withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { attempt.job.await() } == null) {
            attempt.job.cancelAndJoin()
        }
        attempt.states.close()
        attempt.output.close()
        attempt.passwordBytes?.let { secret ->
            assertTrue("Mosh connection ownership must wipe its password.", secret.all { it == 0.toByte() })
        }
    }

    private suspend fun awaitConnected(states: Channel<ConnectionState>) {
        withTimeout(CONCURRENT_CONNECT_TIMEOUT_MILLIS) {
            while (true) {
                when (val state = states.receive()) {
                    ConnectionState.Connected -> return@withTimeout
                    is ConnectionState.Failed -> fail(state.message)
                    ConnectionState.Disconnected -> fail("Concurrent Mosh session disconnected before ready.")
                    ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.AwaitingApproval,
                    -> Unit
                }
            }
        }
    }

    private suspend fun awaitExactTerminalLine(
        output: Channel<ByteArray>,
        marker: String,
        columns: Int,
        rows: Int,
    ) {
        val terminal = VtTerminalEngine(columns = columns, rows = rows)
        var capturedBytes = 0
        withTimeout(OUTPUT_TIMEOUT_MILLIS) {
            while (true) {
                val chunk = output.receive()
                capturedBytes += chunk.size
                check(capturedBytes <= MAX_CAPTURE_BYTES) {
                    "Concurrent Mosh fixture output exceeded its bounded capture."
                }
                val frame = terminal.accept(chunk)
                if (frame.screen.any { it.text.trimEnd() == marker }) return@withTimeout
            }
        }
    }

    private fun resizeProbeCommand(rows: Int, columns: Int, marker: String): String =
        "stty -echo; i=0; while [ \"\$i\" -lt 100 ]; do " +
            "size=\$(stty size); if [ \"\$size\" = \"$rows $columns\" ]; then " +
            "printf '${marker.octalEscapes()}\\n'; break; fi; i=\$((i+1)); sleep 0.05; done\n"

    private fun markerCommand(marker: String): String =
        "printf '${marker.octalEscapes()}\\n'\n"

    private fun String.octalEscapes(): String = encodeToByteArray().joinToString(separator = "") { byte ->
        "\\%03o".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val ARG_HOST = "moshE2eHost"
        const val ARG_SSH_PORT = "moshE2eSshPort"
        const val ARG_USERNAME = "moshE2eUsername"
        const val ARG_PASSWORD = "moshE2ePassword"
        const val ARG_PRIVATE_KEY_BASE64 = "moshE2ePrivateKeyBase64"
        const val ARG_UDP_FIRST = "moshE2eUdpFirst"
        const val ARG_UDP_LAST = "moshE2eUdpLast"
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_UDP_FIRST = 60_000
        const val DEFAULT_UDP_LAST = 61_000
        const val CONNECT_TIMEOUT_MILLIS = 60_000L
        const val OUTPUT_TIMEOUT_MILLIS = 30_000L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
        const val CONCURRENT_CONNECT_TIMEOUT_MILLIS = 120_000L
        const val MAX_CAPTURE_BYTES = 512 * 1024
        const val CONCURRENT_SESSION_COUNT = 4
        const val INITIAL_COLUMNS = 80
        const val INITIAL_ROWS = 24
        const val RESIZED_COLUMNS = 91
        const val RESIZED_ROWS = 31
        const val PROCESS_DEATH_TIMEOUT_MILLIS = 15_000L
        const val REBIND_TIMEOUT_MILLIS = 30_000L
        const val PROCESS_POLL_MILLIS = 50L
        const val TMUX_RECREATE_TEST_TIMEOUT_MILLIS = 180_000L
        const val TMUX_ATTACH_SETTLE_MILLIS = 1_000L
        const val TMUX_HISTORY_SETTLE_MILLIS = 1_500L
        const val TMUX_PREFIX_SETTLE_MILLIS = 100L
        const val TMUX_METADATA_POLL_MILLIS = 100L
        const val TMUX_PREFIX_CONTROL_BYTE: Byte = 0x02
        const val TMUX_COPY_MODE_KEY = '['
        const val TMUX_ENABLE_MOUSE_COMMAND = "tmux set-option -g mouse on"
        const val MOSH_EXTENSION_PACKAGE = "com.yanjiyu.terminalspike"
        const val TMUX_RECREATE_ROW_COUNT = 500
        const val TMUX_RECREATE_PREFIX = "MOSH_TMUX_RECREATE_"
        const val TMUX_RECREATE_DONE_MARKER = "MOSH_TMUX_RECREATE_DONE"
        const val TMUX_RECREATE_ROWS_COMMAND =
            "awk 'BEGIN { for (i = 1; i <= $TMUX_RECREATE_ROW_COUNT; i++) { " +
                "printf \"${TMUX_RECREATE_PREFIX}%04d\\n\", i; system(\"sleep 0.01\") } " +
                "print \"$TMUX_RECREATE_DONE_MARKER\" }'"
        const val TMUX_RECREATE_REFRESH_MARKER = "MOSH_TMUX_AFTER_RECREATE"
        const val TMUX_RECREATE_REFRESH_COMMAND =
            "printf '${TMUX_RECREATE_REFRESH_MARKER}\\n'"
        const val OUTPUT_ROW_COUNT = 200
        const val MAX_OBSERVED_RANGES = 16
        const val COMMAND =
            "awk 'BEGIN { for (i = 1; i <= $OUTPUT_ROW_COUNT; i++) { " +
                "printf \"MOSH_SCROLL_%03d\\n\", i; system(\"sleep 0.03\") } " +
                "print \"MOSH_SCROLL_\" \"DONE\" }'\n"
        const val MARKER = "MOSH_SCROLL_DONE"

        fun firstExpectedRow(): String = "MOSH_SCROLL_001"

        fun lastExpectedRow(): String = "MOSH_SCROLL_200"

        fun firstExpectedTmuxRecreateRow(): String = "${TMUX_RECREATE_PREFIX}0001"

        fun lastExpectedTmuxRecreateRow(): String = "${TMUX_RECREATE_PREFIX}0500"
    }

    private data class RealMoshFixture(
        val host: String,
        val sshPort: Int,
        val username: String,
        val password: String,
        val privateKey: ByteArray?,
        val udpFirst: Int,
        val udpLast: Int,
    )

    private data class RealMoshAttempt(
        val connection: MoshConnection,
        val states: Channel<ConnectionState>,
        val output: Channel<ByteArray>,
        val job: Deferred<Unit>,
        val passwordBytes: ByteArray?,
    )
}

private suspend fun awaitMountedTerminal(
    scenario: ActivityScenario<MainActivity>,
): FastTerminalView {
    var terminal: FastTerminalView? = null
    withTimeout(10_000L) {
        while (terminal == null) {
            scenario.onActivity { terminal = findMoshTerminalView(it.window.decorView) }
            if (terminal == null) delay(10L)
        }
    }
    return requireNotNull(terminal)
}

private fun findMoshTerminalView(view: View): FastTerminalView? {
    if (view is FastTerminalView) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
        findMoshTerminalView(view.getChildAt(index))?.let { return it }
    }
    return null
}

private suspend fun dispatchMoshTmuxSwipeDown(
    scenario: ActivityScenario<MainActivity>,
    instrumentation: android.app.Instrumentation,
    terminal: FastTerminalView,
) {
    var x = 0f
    var startY = 0f
    var endY = 0f
    scenario.onActivity {
        val location = IntArray(2).also(terminal::getLocationOnScreen)
        x = location[0] + terminal.width / 2f
        startY = location[1] + terminal.height * 0.25f
        endY = location[1] + terminal.height * 0.85f
    }
    val downTime = SystemClock.uptimeMillis()
    listOf(
        MotionEvent.ACTION_DOWN to startY,
        MotionEvent.ACTION_MOVE to (startY * 0.75f + endY * 0.25f),
        MotionEvent.ACTION_MOVE to ((startY + endY) / 2f),
        MotionEvent.ACTION_MOVE to (startY * 0.25f + endY * 0.75f),
        MotionEvent.ACTION_UP to endY,
    ).forEachIndexed { index, (action, y) ->
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

private fun com.yanjiyu.terminalspike.terminal.TerminalController.indexOfExactLine(
    expected: String,
): Int = (0 until lineCount()).firstOrNull { index ->
    lineAt(index)?.text?.trimEnd() == expected
} ?: -1

private fun com.yanjiyu.terminalspike.terminal.TerminalController.lastIndexOfExactLine(
    expected: String,
): Int = (lineCount() - 1 downTo 0).firstOrNull { index ->
    lineAt(index)?.text?.trimEnd() == expected
} ?: -1

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
