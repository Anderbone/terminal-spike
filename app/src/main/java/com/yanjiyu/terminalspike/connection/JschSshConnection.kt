package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class JschSshConnection(
    private val knownHostManager: KnownHostManager,
    private val config: SshConnectionConfig,
) : Connection, RemoteImageUploadConnection {
    /** Compatibility constructor for the legacy ViewModel while its owner completes cutover. */
    constructor(
        knownHostsFile: () -> File,
        config: SshConnectionConfig,
    ) : this(KnownHostManager(knownHostsFile), config)

    private val lock = Any()
    private val authenticatedSessionFactory = JschAuthenticatedSessionFactory(knownHostManager)

    @Volatile
    private var running = false

    private var activeAttempt: ConnectionAttempt? = null
    private var authenticatedSession: AuthenticatedJschSession? = null
    private var shell: ChannelShell? = null
    private var output: OutputStream? = null
    private var writer: BoundedSshWriter? = null
    private var startupInputGate: StartupFirstInputGate? = null
    private var tmuxSelector: TmuxSessionSelector? = null
    private var attachedTmuxSessionId: String? = null
    private var startedInTmux = false
    private var discoveredTmuxClient: TmuxClientIdentity? = null
    private var tmuxClientWasDiscovered = false
    private val tmuxTasks = TmuxTaskMonitor()
    private var tmuxExecutable: String? = null
    private var tmuxSessionsBeforeStart: Set<String> = emptySet()
    private var cachedTmuxPaneCapture: TmuxPaneCapture? = null
    private var tmuxLiveHistoryRefreshPending = false
    private val imageUploadLock = Any()
    private var requestedColumns = DEFAULT_TERMINAL_COLUMNS
    private var requestedRows = DEFAULT_TERMINAL_ROWS

    override val isTmuxSession: Boolean
        get() = synchronized(lock) {
            if (tmuxClientWasDiscovered) discoveredTmuxClient != null
            else startedInTmux || attachedTmuxSessionId != null
        }

    override val terminalTaskStatus: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
        get() = tmuxTasks.paneStatus(synchronized(lock) { discoveredTmuxClient?.paneId })

    override fun acknowledgeTaskStatus() {
        tmuxTasks.acknowledge(synchronized(lock) { discoveredTmuxClient?.paneId })
    }

    override fun refreshTmuxIdentity(): Boolean {
        val session = synchronized(lock) {
            authenticatedSession?.session?.takeIf { running && it.isConnected }
        } ?: return false
        val runner = JschTmuxCommandRunner(session)
        val executable = synchronized(lock) { tmuxExecutable } ?: queryTmuxExecutable(runner)
            ?: return false
        tmuxTasks.refresh(runner, executable)
        val observation = discoverTmuxClient(runner, executable)
        if (observation == TmuxClientObservation.Unavailable) return false
        return synchronized(lock) {
            if (authenticatedSession?.session !== session || !running) return false
            tmuxExecutable = executable
            val identity = (observation as? TmuxClientObservation.Attached)?.identity
            if (identity == null && !tmuxClientWasDiscovered) return false
            tmuxClientWasDiscovered = true
            if (discoveredTmuxClient == identity) return false
            discoveredTmuxClient = identity
            attachedTmuxSessionId = identity?.sessionId
            startedInTmux = identity != null
            cachedTmuxPaneCapture = null
            tmuxLiveHistoryRefreshPending = identity != null
            true
        }
    }

    override fun captureTmuxPane(includeHistory: Boolean): TmuxPaneCapture? {
        if (includeHistory) {
            synchronized(lock) {
                cachedTmuxPaneCapture?.let { cached ->
                    cachedTmuxPaneCapture = null
                    return cached
                }
            }
        }
        val context = synchronized(lock) {
            val session = authenticatedSession?.session?.takeIf { running && it.isConnected }
                ?: return null
            val executable = tmuxExecutable ?: return null
            TmuxCaptureContext(
                session = session,
                executable = executable,
                sessionId = attachedTmuxSessionId,
                sessionsBeforeStart = tmuxSessionsBeforeStart,
                authoritative = tmuxLiveHistoryRefreshPending,
            )
        }
        val metadataRunner = JschTmuxCommandRunner(context.session)
        val targetSessionId = context.sessionId ?: resolveNewTmuxSessionId(
            metadataRunner,
            context.sessionsBeforeStart,
        ) ?: return null
        synchronized(lock) {
            if (authenticatedSession?.session !== context.session || !running) return null
            if (attachedTmuxSessionId == null) attachedTmuxSessionId = targetSessionId
        }
        val capture = captureTmuxPane(
            metadataRunner = metadataRunner,
            historyRunner = JschTmuxCommandRunner(context.session, TMUX_HISTORY_EXEC_LIMITS),
            executable = context.executable,
            sessionId = targetSessionId,
            authoritative = context.authoritative && includeHistory,
            includeHistory = includeHistory,
            paneId = synchronized(lock) { discoveredTmuxClient?.paneId },
        ) ?: return null
        return synchronized(lock) {
            capture.takeIf {
                authenticatedSession?.session === context.session && running &&
                    attachedTmuxSessionId == targetSessionId
            }?.also {
                if (context.authoritative && includeHistory) {
                    tmuxLiveHistoryRefreshPending = false
                }
            }
        }
    }

    override fun captureTmuxHistoryPage(request: TmuxHistoryPageRequest): TmuxPaneCapture? {
        val context = synchronized(lock) {
            val session = authenticatedSession?.session?.takeIf { running && it.isConnected }
                ?: return null
            val executable = tmuxExecutable ?: return null
            val sessionId = attachedTmuxSessionId ?: return null
            TmuxCaptureContext(
                session = session,
                executable = executable,
                sessionId = sessionId,
                sessionsBeforeStart = emptySet(),
                authoritative = false,
            )
        }
        val capture = captureTmuxPane(
            metadataRunner = JschTmuxCommandRunner(context.session),
            historyRunner = JschTmuxCommandRunner(context.session, TMUX_HISTORY_EXEC_LIMITS),
            executable = context.executable,
            sessionId = requireNotNull(context.sessionId),
            pageRequest = request,
            paneId = synchronized(lock) { discoveredTmuxClient?.paneId },
        ) ?: return null
        return synchronized(lock) {
            capture.takeIf {
                authenticatedSession?.session === context.session && running &&
                    attachedTmuxSessionId == context.sessionId
            }
        }
    }

    override fun uploadPastedImage(fileName: String, source: InputStream): String {
        val session = synchronized(lock) {
            authenticatedSession?.session?.takeIf { it.isConnected }
                ?: error("The SSH session is not connected.")
        }
        return synchronized(imageUploadLock) {
            uploadPastedImageViaSftp(session, fileName, source)
        }
    }

    override suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    ) {
        closeCurrentAttempt(clearAuthentication = false)
        val attempt = ConnectionAttempt(ConnectionStatePublisher(onState))
        synchronized(lock) {
            activeAttempt = attempt
            requestedColumns = columns.coerceAtLeast(1)
            requestedRows = rows.coerceAtLeast(1)
        }
        var repository: VerifyingHostKeyRepository? = null
        try {
            attempt.states.publish(ConnectionState.Connecting)
            val openedSession = authenticatedSessionFactory.connect(
                config = config,
                onPrompt = { prompt -> attempt.states.publish(ConnectionState.AwaitingApproval(prompt)) },
                onKeyboardInteractiveChallenge = { challenge ->
                    attempt.states.publish(ConnectionState.AwaitingApproval(challenge))
                },
                onRepositoryReady = { verifyingRepository ->
                    repository = verifyingRepository
                    attempt.hostKeyRepository = verifyingRepository
                },
                registerBeforeConnect = { candidate ->
                    synchronized(lock) {
                        if (activeAttempt !== attempt || attempt.explicitCloseRequested) {
                            false
                        } else {
                            authenticatedSession = candidate
                            true
                        }
                    }
                },
            ) ?: return
            val newSession = openedSession.session

            if (!isActive(attempt)) return

            val tmuxChoice = if (config.tmuxSessionSelectorEnabled) {
                TmuxSessionSelector(newSession) { prompt ->
                    attempt.states.publish(ConnectionState.AwaitingApproval(prompt))
                }.also { selector ->
                    synchronized(lock) { tmuxSelector = selector }
                }.awaitChoice()
            } else {
                null
            }
            synchronized(lock) { tmuxSelector = null }
            if (!isActive(attempt)) return
            val initialTmuxCapture = (tmuxChoice as? TmuxStartupChoice.Attach)?.let { choice ->
                captureTmuxPane(
                    metadataRunner = JschTmuxCommandRunner(newSession),
                    historyRunner = JschTmuxCommandRunner(newSession, TMUX_HISTORY_EXEC_LIMITS),
                    executable = choice.executable,
                    sessionId = choice.sessionId,
                    authoritative = true,
                )
            }
            synchronized(lock) {
                attachedTmuxSessionId = (tmuxChoice as? TmuxStartupChoice.Attach)?.sessionId
                startedInTmux = tmuxChoice != null
                tmuxExecutable = tmuxChoice?.executable
                tmuxSessionsBeforeStart =
                    (tmuxChoice as? TmuxStartupChoice.NewSession)?.existingSessionIds.orEmpty()
                cachedTmuxPaneCapture = initialTmuxCapture
                tmuxLiveHistoryRefreshPending = tmuxChoice != null
            }
            val tmuxStartupCommand = tmuxChoice?.let(::tmuxStartupCommand)

            val newShell = newSession.openChannel("shell") as ChannelShell
            val input = newShell.inputStream
            val newOutput = newShell.outputStream
            val channelRegistered = synchronized(lock) {
                if (activeAttempt !== attempt || attempt.explicitCloseRequested) {
                    false
                } else {
                    // Register and configure under the same lock used by resize(). A view can
                    // report its measured size before the SSH shell exists; retaining that latest
                    // size prevents the remote PTY and local VT engine from diverging.
                    configureSshPty(
                        target = JschShellPtyTarget(newShell),
                        terminalType = config.terminalType,
                        columns = requestedColumns,
                        rows = requestedRows,
                    )
                    shell = newShell
                    output = newOutput
                    true
                }
            }
            if (!channelRegistered) {
                runCatching { newOutput.close() }
                newShell.disconnect()
                return
            }
            newShell.connect(CHANNEL_TIMEOUT_MS)
            val newWriter = BoundedSshWriter(
                capacity = OUTGOING_QUEUE_CAPACITY,
                pollIntervalMillis = WRITER_POLL_MS,
            )
            val newStartupInputGate = StartupFirstInputGate(
                capacity = OUTGOING_QUEUE_CAPACITY - STARTUP_RESERVED_WRITER_SLOTS,
                downstreamOffer = newWriter::offer,
            )
            val connectionStillWanted = synchronized(lock) {
                if (activeAttempt !== attempt || attempt.explicitCloseRequested) {
                    false
                } else {
                    writer = newWriter
                    startupInputGate = newStartupInputGate
                    running = true
                    newWriter.start(
                        stream = newOutput,
                        onResize = { columns, rows -> newShell.setPtySize(columns, rows, 0, 0) },
                    ) {
                        failConnection(attempt, transientTransportFailure(WRITER_FAILURE_MESSAGE))
                    }
                    // Include size changes received while the shell channel was connecting.
                    newWriter.resize(requestedColumns, requestedRows)
                    true
                }
            }
            if (!connectionStillWanted) return
            val startupResult = attempt.publishConnectedAndDispatchStartup(
                command = resolveSshStartupCommand(config.startupCommand, tmuxStartupCommand),
                sendOnce = newWriter::offer,
            ) ?: return
            if (startupResult == SshStartupDispatchResult.REJECTED) {
                failConnection(attempt, ConnectionState.Failed(STARTUP_COMMAND_REJECTED_MESSAGE))
                return
            }
            if (!newStartupInputGate.open()) {
                failConnection(attempt, ConnectionState.Failed(QUEUE_REJECTED_MESSAGE))
                return
            }

            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (isRunning(attempt) && newShell.isConnected) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) deliverConnectionBytes(onBytes, buffer.copyOf(count))
            }
            publishTerminalStateAfterTransportEnded(
                attempt = attempt,
                remoteExitStatus = newShell.exitStatus,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val (explicitClose, remoteExitStatus) = synchronized(lock) {
                if (activeAttempt === attempt) running = false
                attempt.explicitCloseRequested to shell?.exitStatus
            }
            attempt.states.publishTerminalAndCleanup(
                terminalConnectionState(
                    explicitCloseRequested = explicitClose,
                    transportFailure = attempt.transportFailure,
                    fallbackFailure = sshFailure(repository, error),
                    remoteExitStatus = remoteExitStatus,
                ),
            ) { closeResources(attempt) }
        } finally {
            config.clearAuthenticationSecrets()
            closeResources(attempt)
            synchronized(lock) {
                if (activeAttempt === attempt) activeAttempt = null
            }
        }
    }

    override fun send(bytes: ByteArray) {
        sendWithAcceptance(bytes)
    }

    override fun sendWithAcceptance(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (trySend(bytes)) return true
        val attempt = synchronized(lock) {
            activeAttempt?.takeIf { current ->
                running && !current.explicitCloseRequested
            }
        } ?: return false
        failConnection(attempt, ConnectionState.Failed(QUEUE_REJECTED_MESSAGE))
        return false
    }

    override fun trySend(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return synchronized(lock) {
            val attempt = activeAttempt
            val inputGate = startupInputGate
            if (
                !running ||
                attempt == null ||
                attempt.explicitCloseRequested ||
                output == null ||
                inputGate == null
            ) {
                false
            } else {
                // Backpressure rejects this write without tearing down an otherwise-live session.
                inputGate.offer(bytes)
            }
        }
    }

    override fun resize(columns: Int, rows: Int) {
        val boundedColumns = columns.coerceAtLeast(1)
        val boundedRows = rows.coerceAtLeast(1)
        synchronized(lock) {
            requestedColumns = boundedColumns
            requestedRows = boundedRows
            // setPtySize writes an SSH packet. Calling it from the view's main-thread size
            // callback can trip the release network policy after JSch advances cipher state.
            writer?.resize(boundedColumns, boundedRows)
        }
    }

    override fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        synchronized(lock) { activeAttempt?.hostKeyRepository }
            ?.answerPrompt(promptToken, decision)
    }

    override fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        val session = synchronized(lock) { authenticatedSession }
        if (session == null) {
            responses.forEach { it.fill('\u0000') }
        } else {
            session.answerKeyboardInteractiveChallenge(challengeToken, responses)
        }
    }

    override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) {
        synchronized(lock) { authenticatedSession }
            ?.cancelKeyboardInteractiveChallenge(challengeToken)
    }

    override fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) {
        synchronized(lock) { tmuxSelector }?.answer(promptToken, sessionId)
    }

    override fun deleteTmuxSession(promptToken: Long, sessionId: String) {
        synchronized(lock) { tmuxSelector }?.delete(promptToken, sessionId)
    }

    override fun queryTmuxSessionCatalog(includePreviews: Boolean): TmuxSessionCatalog {
        val (session, activeSessionId) = synchronized(lock) {
            authenticatedSession?.session?.takeIf { running && it.isConnected } to
                attachedTmuxSessionId
        }
        session ?: return TmuxSessionCatalog()
        return queryTmuxSessionCatalog(JschTmuxCommandRunner(session), includePreviews).copy(
            activeSessionId = activeSessionId,
        ).let { catalog -> catalog.copy(sessions = catalog.sessions.map {
            it.copy(taskStatus = tmuxTasks.sessionStatus(it.id))
        }) }
    }

    override fun terminateTmuxSession(sessionId: String): TmuxSessionCatalog {
        val session = synchronized(lock) {
            authenticatedSession?.session?.takeIf { running && it.isConnected }
        } ?: return TmuxSessionCatalog(deleteFailed = true)
        return terminateTmuxSession(JschTmuxCommandRunner(session), sessionId)
    }

    override fun switchTmuxSession(sessionId: String): Boolean {
        val (session, sourceSessionId, knownExecutable) = synchronized(lock) {
            val connected = authenticatedSession?.session?.takeIf { running && it.isConnected }
            Triple(connected, attachedTmuxSessionId, tmuxExecutable)
        }
        if (session == null) return false
        val runner = JschTmuxCommandRunner(session)
        val executable = knownExecutable ?: queryTmuxExecutable(runner)
        val switched = when (val result = switchOrAttachTmuxSession(
            runner,
            sessionId,
            sourceSessionId,
            clientTty = synchronized(lock) { discoveredTmuxClient?.tty },
        )) {
            TmuxSessionSwitchResult.Switched -> true
            is TmuxSessionSwitchResult.Attach ->
                trySend((result.command + '\r').encodeToByteArray())
            TmuxSessionSwitchResult.Failed -> false
        }
        if (switched) synchronized(lock) {
            attachedTmuxSessionId = sessionId
            startedInTmux = true
            tmuxExecutable = executable
            tmuxSessionsBeforeStart = emptySet()
            cachedTmuxPaneCapture = null
            tmuxLiveHistoryRefreshPending = true
        }
        return switched
    }

    override fun cancelPendingPrompts() {
        synchronized(lock) { tmuxSelector }?.cancel()
        synchronized(lock) { activeAttempt?.hostKeyRepository }?.retire()
        synchronized(lock) { authenticatedSession }
            ?.cancelPendingKeyboardInteractiveChallenge()
    }

    override fun close() {
        closeCurrentAttempt(clearAuthentication = true)
    }

    private fun closeCurrentAttempt(clearAuthentication: Boolean) {
        val attempt = synchronized(lock) {
            running = false
            activeAttempt?.also { it.explicitCloseRequested = true }
        }
        try {
            synchronized(lock) { tmuxSelector }?.cancel()
            attempt?.hostKeyRepository?.retire()
            synchronized(lock) { authenticatedSession }
                ?.cancelPendingKeyboardInteractiveChallenge()
            attempt?.states?.publish(ConnectionState.Disconnected)
        } finally {
            closeResources(attempt)
            if (clearAuthentication) config.clearAuthenticationSecrets()
        }
    }

    private fun isActive(attempt: ConnectionAttempt): Boolean = synchronized(lock) {
        activeAttempt === attempt && !attempt.explicitCloseRequested
    }

    private fun isRunning(attempt: ConnectionAttempt): Boolean = synchronized(lock) {
        activeAttempt === attempt && running && !attempt.explicitCloseRequested
    }

    private fun publishTerminalStateAfterTransportEnded(
        attempt: ConnectionAttempt,
        remoteExitStatus: Int,
    ) {
        val state = synchronized(lock) {
            if (activeAttempt === attempt) running = false
            terminalConnectionState(
                explicitCloseRequested = attempt.explicitCloseRequested,
                transportFailure = attempt.transportFailure,
                fallbackFailure = null,
                remoteExitStatus = remoteExitStatus,
            )
        }
        attempt.states.publishTerminalAndCleanup(state) { closeResources(attempt) }
    }

    private fun failConnection(attempt: ConnectionAttempt, failure: ConnectionState.Failed) {
        val shouldClose = synchronized(lock) {
            if (activeAttempt !== attempt || attempt.explicitCloseRequested || !running) {
                false
            } else {
                attempt.transportFailure = failure
                running = false
                true
            }
        }
        if (!shouldClose) return
        attempt.states.publishTerminalAndCleanup(failure) { closeResources(attempt) }
    }

    private fun closeResources(attempt: ConnectionAttempt?) {
        val resources = synchronized(lock) {
            if (attempt != null && activeAttempt !== attempt) return
            running = false
            val current = ConnectionResources(
                writer = writer,
                startupInputGate = startupInputGate,
                output = output,
                shell = shell,
                authenticatedSession = authenticatedSession,
            )
            tmuxSelector?.cancel()
            tmuxSelector = null
            attachedTmuxSessionId = null
            startedInTmux = false
            tmuxExecutable = null
            tmuxSessionsBeforeStart = emptySet()
            cachedTmuxPaneCapture = null
            tmuxLiveHistoryRefreshPending = false
            discoveredTmuxClient = null
            tmuxClientWasDiscovered = false
            writer = null
            startupInputGate = null
            output = null
            shell = null
            authenticatedSession = null
            current
        }
        tmuxTasks.clear()
        resources.startupInputGate?.close()
        resources.writer?.stop()
        runCatching { resources.output?.close() }
        runCatching { resources.shell?.disconnect() }
        runCatching { resources.authenticatedSession?.close() }
    }

    companion object {
        private const val CHANNEL_TIMEOUT_MS = 10_000
        private const val DEFAULT_TERMINAL_COLUMNS = 80
        private const val DEFAULT_TERMINAL_ROWS = 24
        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val OUTGOING_QUEUE_CAPACITY = 256
        private const val STARTUP_RESERVED_WRITER_SLOTS = 1
        private const val WRITER_POLL_MS = 250L
        private const val QUEUE_REJECTED_MESSAGE =
            "SSH output queue is full. Connection closed to prevent input loss."
        private const val STARTUP_COMMAND_REJECTED_MESSAGE =
            "SSH startup input could not be queued. Connection closed to prevent ambiguous shell state."
        private const val WRITER_FAILURE_MESSAGE = "SSH connection lost while sending data."
    }
}

/** Shared by SSH terminals and the authenticated SSH side channel retained by a Mosh session. */
internal fun uploadPastedImageViaSftp(
    session: Session,
    fileName: String,
    source: InputStream,
): String {
    require(PASTED_IMAGE_FILE_NAME.matches(fileName)) { "Invalid pasted-image file name." }
    require(session.isConnected) { "The SSH image-upload side channel is not connected." }
    val sftp = session.openChannel("sftp") as ChannelSftp
    var remotePath: String? = null
    try {
        sftp.connect(IMAGE_UPLOAD_CHANNEL_TIMEOUT_MS)
        val home = normalizeAbsolutePath(sftp.pwd())
        val cache = sftp.ensureDirectory(home, ".cache")
        val appCache = sftp.ensureDirectory(cache, "terminal-spike")
        sftp.chmod(REMOTE_PRIVATE_DIRECTORY_MODE, appCache)
        val imageCache = sftp.ensureDirectory(appCache, "pasted-images")
        sftp.chmod(REMOTE_PRIVATE_DIRECTORY_MODE, imageCache)
        remotePath = childPath(imageCache, fileName)
        source.use { input -> sftp.put(input, remotePath) }
        sftp.chmod(REMOTE_PRIVATE_FILE_MODE, remotePath)
        return remotePath
    } catch (error: Throwable) {
        remotePath?.let { partial -> runCatching { sftp.rm(partial) } }
        throw error
    } finally {
        runCatching { sftp.disconnect() }
    }
}

private const val IMAGE_UPLOAD_CHANNEL_TIMEOUT_MS = 10_000
private const val REMOTE_PRIVATE_DIRECTORY_MODE = 448 // 0700
private const val REMOTE_PRIVATE_FILE_MODE = 384 // 0600
private val PASTED_IMAGE_FILE_NAME = Regex("[a-f0-9-]{36}\\.(png|jpe?g|webp|gif)")

private fun ChannelSftp.ensureDirectory(parent: String, name: String): String {
    val path = childPath(parent, name)
    val existing = runCatching { lstat(path) }.getOrNull()
    if (existing == null) {
        mkdir(path)
    } else {
        require(existing.isDir) { "Remote image cache path is not a directory." }
    }
    return path
}

/** Maps transport diagnostics to display-safe categories without echoing endpoints or credentials. */
internal fun safeJschFailure(rawMessage: String?): ConnectionState.Failed {
    val normalized = rawMessage.orEmpty().lowercase()
    return when {
        "auth fail" in normalized || "auth cancel" in normalized || "authentication" in normalized ->
            ConnectionState.Failed("SSH authentication failed.")
        "timeout" in normalized || "timed out" in normalized ->
            transientTransportFailure("SSH connection timed out.")
        "unknownhost" in normalized || "unresolved" in normalized ->
            transientTransportFailure("SSH host could not be resolved.")
        "refused" in normalized -> transientTransportFailure("SSH connection was refused.")
        "algorithm negotiation" in normalized || "no matching" in normalized ->
            ConnectionState.Failed("No compatible SSH security algorithm was found.")
        else -> ConnectionState.Failed("SSH connection failed.")
    }
}

internal fun safeJschFailureMessage(rawMessage: String?): String = safeJschFailure(rawMessage).message

internal fun sshFailure(
    repository: VerifyingHostKeyRepository?,
    error: Exception,
): ConnectionState.Failed = when (repository?.failure) {
    HostKeyFailure.CHANGED -> ConnectionState.Failed("Host key changed. Connection blocked.")
    HostKeyFailure.REJECTED -> ConnectionState.Failed("Host key was not trusted.")
    HostKeyFailure.STORE_FAILED -> ConnectionState.Failed("Could not save the trusted host key.")
    null -> when (error) {
        is CredentialStoreException -> ConnectionState.Failed(storedCredentialFailureMessage(error))
        is JSchException -> safeJschFailure(error.message).let { safe ->
            if (
                safe.disposition == ConnectionFailureDisposition.TERMINAL &&
                !isExplicitTerminalJschFailure(error.message) &&
                error.hasIoCause()
            ) {
                transientTransportFailure("SSH connection lost.")
            } else {
                safe
            }
        }
        is IOException -> transientTransportFailure("SSH connection lost.")
        else -> ConnectionState.Failed("SSH connection failed.")
    }
}

/** Compatibility projection for bootstrap boundaries that only carry a redacted message. */
internal fun sshFailureMessage(
    repository: VerifyingHostKeyRepository?,
    error: Exception,
): String = sshFailure(repository, error).message

private fun Throwable.hasIoCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}

private fun isExplicitTerminalJschFailure(rawMessage: String?): Boolean {
    val normalized = rawMessage.orEmpty().lowercase()
    return "auth fail" in normalized ||
        "auth cancel" in normalized ||
        "authentication" in normalized ||
        "algorithm negotiation" in normalized ||
        "no matching" in normalized
}

private fun storedCredentialFailureMessage(error: CredentialStoreException): String = when (error) {
    is CredentialStoreException.KeyUnavailable ->
        "Saved credential encryption is unavailable. Re-enter or re-import it."
    is CredentialStoreException.Missing,
    is CredentialStoreException.Malformed,
    is CredentialStoreException.Tampered,
    -> "Saved credential is missing or damaged. Re-enter or re-import it."
}

internal class ConnectionAttempt(
    val states: ConnectionStatePublisher,
) {
    private val startupDispatcher = OneShotSshStartupDispatcher()
    private val startupLock = Any()
    private var shellConnectedHandled = false

    /** Publishes Connected before the attempt-owned one-shot can offer any startup input. */
    fun publishConnectedAndDispatchStartup(
        command: String?,
        sendOnce: (ByteArray) -> Boolean,
    ): SshStartupDispatchResult? {
        val isFirstCallback = synchronized(startupLock) {
            if (shellConnectedHandled) false else true.also { shellConnectedHandled = true }
        }
        if (!isFirstCallback) return SshStartupDispatchResult.ALREADY_DISPATCHED
        if (!states.publish(ConnectionState.Connected)) return null
        return startupDispatcher.dispatch(command, sendOnce)
    }

    @Volatile
    var explicitCloseRequested: Boolean = false

    @Volatile
    var transportFailure: ConnectionState.Failed? = null

    @Volatile
    var hostKeyRepository: VerifyingHostKeyRepository? = null
}

private data class ConnectionResources(
    val writer: BoundedSshWriter?,
    val startupInputGate: StartupFirstInputGate?,
    val output: OutputStream?,
    val shell: ChannelShell?,
    val authenticatedSession: AuthenticatedJschSession?,
)

private data class TmuxCaptureContext(
    val session: Session,
    val executable: String,
    val sessionId: String?,
    val sessionsBeforeStart: Set<String>,
    val authoritative: Boolean,
)

private class JschShellPtyTarget(
    private val shell: ChannelShell,
) : SshPtyTarget {
    override fun enablePty() = shell.setPty(true)

    override fun setTerminalType(terminalType: String) = shell.setPtyType(terminalType)

    override fun setDimensions(columns: Int, rows: Int) =
        shell.setPtySize(columns, rows, 0, 0)
}

internal class ConnectionStatePublisher(
    private val publishState: (ConnectionState) -> Unit,
) {
    private val lock = Any()
    private var terminalStatePublished = false

    fun publish(state: ConnectionState): Boolean = synchronized(lock) {
        if (terminalStatePublished) return false
        if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
            terminalStatePublished = true
        }
        try {
            publishState(state)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ConnectionCallbackException(error)
        }
        true
    }

    fun publishTerminalAndCleanup(
        state: ConnectionState,
        cleanup: () -> Unit,
    ): Boolean = try {
        require(state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
            "Only a terminal connection state may own terminal cleanup."
        }
        publish(state)
    } finally {
        cleanup()
    }
}

internal class ConnectionCallbackException(cause: Exception) :
    RuntimeException("Connection callback failed.", cause)

internal fun deliverConnectionBytes(
    onBytes: (ByteArray) -> Unit,
    bytes: ByteArray,
) {
    try {
        onBytes(bytes)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ConnectionCallbackException(error)
    }
}

internal fun terminalConnectionState(
    explicitCloseRequested: Boolean,
    transportFailure: ConnectionState.Failed?,
    fallbackFailure: ConnectionState.Failed?,
    remoteExitStatus: Int? = null,
): ConnectionState = when {
    explicitCloseRequested -> ConnectionState.Disconnected
    transportFailure != null -> transportFailure
    fallbackFailure?.disposition == ConnectionFailureDisposition.TERMINAL -> fallbackFailure
    remoteExitStatus != null && remoteExitStatus >= 0 -> ConnectionState.Disconnected
    fallbackFailure != null -> fallbackFailure
    else -> transientTransportFailure("SSH connection lost.")
}

/**
 * Owns bounded input accepted synchronously from Connected observers until startup input is first.
 * Every staged batch is copied and wiped after the downstream writer copies it or rejects it.
 */
internal class StartupFirstInputGate(
    private val capacity: Int,
    private val downstreamOffer: (ByteArray) -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private val staged = ArrayDeque<ByteArray>()
    private var state = StartupInputState.PENDING_STARTUP

    init {
        require(capacity > 0) { "SSH startup staging capacity must be positive." }
    }

    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return synchronized(lock) {
            when (state) {
                StartupInputState.PENDING_STARTUP -> {
                    if (staged.size >= capacity) {
                        false
                    } else {
                        staged.addLast(bytes.copyOf())
                        true
                    }
                }
                StartupInputState.OPEN -> runCatching { downstreamOffer(bytes) }.getOrDefault(false)
                StartupInputState.CLOSED -> false
            }
        }
    }

    /** Opens public input only after startup was accepted or proved absent. */
    fun open(): Boolean = synchronized(lock) {
        if (state != StartupInputState.PENDING_STARTUP) return@synchronized false
        while (staged.isNotEmpty()) {
            val owned = staged.removeFirst()
            val accepted = try {
                downstreamOffer(owned)
            } catch (_: Exception) {
                false
            } finally {
                owned.fill(0)
            }
            if (!accepted) {
                state = StartupInputState.CLOSED
                wipeStaged()
                return@synchronized false
            }
        }
        state = StartupInputState.OPEN
        true
    }

    override fun close() {
        synchronized(lock) {
            state = StartupInputState.CLOSED
            wipeStaged()
        }
    }

    private fun wipeStaged() {
        while (staged.isNotEmpty()) staged.removeFirst().fill(0)
    }

    private enum class StartupInputState {
        PENDING_STARTUP,
        OPEN,
        CLOSED,
    }
}

/** Bounded single-writer pump. Acceptance and stop are serialized so close cannot race an offer. */
internal class BoundedSshWriter(
    capacity: Int,
    private val pollIntervalMillis: Long,
) {
    private val lock = Any()
    private val queue: ArrayBlockingQueue<ByteArray>
    private val workAvailable = Semaphore(0)
    private var pendingSize: Pair<Int, Int>? = null
    private var accepting = false
    private var writerThread: Thread? = null

    init {
        require(capacity > 0) { "SSH writer capacity must be positive." }
        require(pollIntervalMillis > 0L) { "SSH writer poll interval must be positive." }
        queue = ArrayBlockingQueue(capacity)
    }

    fun start(
        stream: OutputStream,
        onResize: (Int, Int) -> Unit = { _, _ -> },
        onFailure: (Exception) -> Unit,
    ) {
        synchronized(lock) {
            check(!accepting && writerThread == null) { "SSH writer is already started." }
            accepting = true
            writerThread = thread(name = "ssh-terminal-writer", isDaemon = true) {
                runWriter(stream, onResize, onFailure)
            }
        }
    }

    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val owned = bytes.copyOf()
        val accepted = synchronized(lock) {
            (accepting && queue.offer(owned)).also { if (it) workAvailable.release() }
        }
        if (!accepted) owned.fill(0)
        return accepted
    }

    /** Coalesces view resize events without using any of the bounded input slots. */
    fun resize(columns: Int, rows: Int): Boolean = synchronized(lock) {
        if (!accepting) return@synchronized false
        val needsWakeup = pendingSize == null
        pendingSize = columns.coerceAtLeast(1) to rows.coerceAtLeast(1)
        if (needsWakeup) workAvailable.release()
        true
    }

    fun stop() {
        val threadToInterrupt = synchronized(lock) {
            accepting = false
            wipeQueuedBytes()
            writerThread.also { writerThread = null }
        }
        threadToInterrupt?.interrupt()
    }

    private fun runWriter(
        stream: OutputStream,
        onResize: (Int, Int) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        try {
            while (isAccepting()) {
                if (!workAvailable.tryAcquire(pollIntervalMillis, TimeUnit.MILLISECONDS)) continue
                val size = synchronized(lock) { pendingSize.also { pendingSize = null } }
                if (size != null) {
                    onResize(size.first, size.second)
                    continue
                }
                val bytes = queue.poll() ?: continue
                try {
                    stream.write(bytes)
                    stream.flush()
                } finally {
                    bytes.fill(0)
                }
            }
        } catch (_: InterruptedException) {
            // stop() interrupts the poll; an explicit close is not a transport failure.
        } catch (error: Exception) {
            val reportFailure = synchronized(lock) {
                if (!accepting) {
                    false
                } else {
                    accepting = false
                    wipeQueuedBytes()
                    writerThread = null
                    true
                }
            }
            if (reportFailure) onFailure(error)
        } finally {
            synchronized(lock) {
                accepting = false
                wipeQueuedBytes()
                if (writerThread === Thread.currentThread()) writerThread = null
            }
        }
    }

    private fun isAccepting(): Boolean = synchronized(lock) { accepting }

    private fun wipeQueuedBytes() {
        pendingSize = null
        workAvailable.drainPermits()
        while (true) queue.poll()?.fill(0) ?: return
    }
}
