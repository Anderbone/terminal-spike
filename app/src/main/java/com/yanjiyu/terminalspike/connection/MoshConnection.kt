package com.yanjiyu.terminalspike.connection

import android.os.ParcelFileDescriptor
import com.yanjiyu.terminalspike.connection.mosh.MoshClientFailure
import com.yanjiyu.terminalspike.connection.mosh.MoshClientResult
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionClient
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily as ApiMoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshContractLimit
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshOption
import com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Production Mosh transport adapter for the same terminal/session abstraction used by SSH. */
internal class MoshConnection(
    private val bootstrap: MoshConnectionBootstrap,
    private val extension: MoshConnectionExtension,
    private val bootstrapRequest: MoshBootstrapRequest,
    private val optionFlags: Long = 0L,
    private val sessionIdFactory: () -> UUID = UUID::randomUUID,
    private val controlDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writerCapacity: Int = DEFAULT_WRITER_CAPACITY,
    private val writerPollMillis: Long = DEFAULT_WRITER_POLL_MILLIS,
) : Connection, MoshNetworkHintReceiver, RemoteImageUploadConnection {
    constructor(
        bootstrapExecutor: MoshBootstrapExecutor,
        extensionClient: MoshExtensionClient,
        bootstrapRequest: MoshBootstrapRequest,
        optionFlags: Long = 0L,
    ) : this(
        bootstrap = ExecutorMoshConnectionBootstrap(bootstrapExecutor),
        extension = AndroidMoshConnectionExtension(extensionClient),
        bootstrapRequest = bootstrapRequest,
        optionFlags = optionFlags,
    )

    private val lock = Any()
    private var activeAttempt: ActiveMoshConnection? = null

    override val isTmuxSession: Boolean
        get() = synchronized(lock) { activeAttempt?.sshSideChannel?.isTmuxSession == true }

    override val terminalTaskStatus: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
        get() = synchronized(lock) { activeAttempt?.sshSideChannel }?.terminalTaskStatus
            ?: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus()

    override fun acknowledgeTaskStatus() {
        synchronized(lock) { activeAttempt?.sshSideChannel }?.acknowledgeTaskStatus()
    }

    override fun refreshTmuxIdentity(): Boolean = synchronized(lock) {
        activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
    }?.refreshTmuxIdentity() ?: false

    override fun captureTmuxPane(includeHistory: Boolean): TmuxPaneCapture? = synchronized(lock) {
        activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
    }?.captureTmuxPane(includeHistory)

    override fun captureTmuxHistoryPage(request: TmuxHistoryPageRequest): TmuxPaneCapture? =
        synchronized(lock) {
            activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
        }?.captureTmuxHistoryPage(request)

    init {
        require(writerCapacity > 0) { "Mosh writer capacity must be positive." }
        require(writerPollMillis > 0L) { "Mosh writer poll interval must be positive." }
    }

    override suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    ) {
        closeCurrentAttempt(clearAuthentication = false)
        val attempt = ActiveMoshConnection(
            states = ConnectionStatePublisher(onState),
            controlScope = CoroutineScope(SupervisorJob() + controlDispatcher),
        )
        synchronized(lock) { activeAttempt = attempt }

        var bootstrapResult: MoshBootstrapResult? = null
        try {
            attempt.states.publish(ConnectionState.Connecting)
            val extensionStatus = extension.connect()
            extensionStatus.failureOrNull()?.let { failure ->
                throw SafeMoshConnectionException(failure)
            }
            ensureActive(attempt)

            val sessionId = sessionIdFactory()
            attempt.sessionId = sessionId

            val completedBootstrap = bootstrap.bootstrap(bootstrapRequest) { bootstrapState ->
                if (bootstrapState is MoshBootstrapState.AwaitingApproval) {
                    attempt.states.publish(ConnectionState.AwaitingApproval(bootstrapState.prompt))
                }
            }
            bootstrapResult = completedBootstrap
            ensureActive(attempt)

            val startSpec = MoshSessionStartSpec(
                sessionId = sessionId,
                addressFamily = completedBootstrap.addressFamily.toApiAddressFamily(),
                addressBytes = completedBootstrap.addressBytes,
                udpPort = completedBootstrap.udpPort,
                initialColumns = boundedColumns(columns),
                initialRows = boundedRows(rows),
                locale = bootstrapRequest.locale,
                optionFlags = optionFlags,
            )
            val events = extension.sessionEvents(sessionId)
            attempt.eventJob = attempt.controlScope.launch {
                try {
                    events.collect { event -> handleEvent(attempt, event) }
                } catch (_: CancellationException) {
                    // Per-attempt cleanup cancels event collection after closing its byte pipes.
                } catch (_: ConnectionCallbackException) {
                    failTerminal(attempt, "Mosh connection callback failed.")
                } catch (_: Exception) {
                    failTransport(attempt, "Mosh transport event channel failed.")
                }
            }
            val startResult = try {
                extension.startSession(startSpec, completedBootstrap.sessionKey)
            } finally {
                completedBootstrap.clearSessionKey()
            }
            val started = when (startResult) {
                is MoshTransportStartResult.Success -> startResult.value
                is MoshTransportStartResult.Failure -> {
                    throw SafeMoshConnectionException(startResult.reason.connectionFailure())
                }
            }
            attempt.sessionStarted = true

            val writer = BoundedSshWriter(writerCapacity, writerPollMillis)
            val registered = synchronized(lock) {
                if (activeAttempt !== attempt || attempt.explicitCloseRequested || attempt.terminal) {
                    false
                } else {
                    attempt.transport = started
                    attempt.writer = writer
                    attempt.sshSideChannel = completedBootstrap
                    attempt.running = true
                    writer.start(started.terminalInput) {
                        failTransport(attempt, "Mosh connection lost while sending data.")
                    }
                    true
                }
            }
            if (!registered) {
                started.closeQuietly()
                throw CancelledMoshConnectionException()
            }
            bootstrapResult = null
            publishConnectedIfNeeded(attempt, started.initialState)
            scheduleResize(attempt)

            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (isRunning(attempt)) {
                    val count = started.terminalOutput.read(buffer)
                    if (count < 0) break
                    if (count > 0 && isCurrent(attempt)) {
                        deliverConnectionBytes(onBytes, buffer.copyOf(count))
                    }
                }
            } finally {
                buffer.fill(0)
            }
            publishEofIfNeeded(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val explicitClose = synchronized(lock) { attempt.explicitCloseRequested }
            if (explicitClose || error is CancelledMoshConnectionException) {
                attempt.states.publishTerminalAndCleanup(ConnectionState.Disconnected) {
                    closeResources(attempt)
                }
            } else {
                val failure = when (error) {
                    is SafeMoshConnectionException -> error.failure
                    is MoshBootstrapException -> error.safeConnectionFailure()
                    is IOException -> transientTransportFailure("Mosh transport failed.")
                    else -> ConnectionState.Failed("Mosh connection failed.")
                }
                markTerminal(attempt)
                attempt.states.publishTerminalAndCleanup(failure) { closeResources(attempt) }
            }
        } finally {
            runCatching { bootstrapResult?.close() }
            runCatching { bootstrap.close() }
            runCatching { closeResources(attempt) }
            runCatching { attempt.eventJob?.cancel() }
            runCatching { attempt.resizeJob?.cancel() }
            runCatching { attempt.networkHintJob?.cancel() }
            runCatching { stopExtensionSession(attempt) }
            runCatching { attempt.controlScope.cancel() }
            runCatching { bootstrapRequest.ssh.clearAuthenticationSecrets() }
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
                current.running && !current.explicitCloseRequested && !current.terminal
            }
        } ?: return false
        failTerminal(
            attempt,
            "Mosh input queue is full. Connection closed to prevent input loss.",
        )
        return false
    }

    override fun trySend(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return synchronized(lock) {
            val attempt = activeAttempt
            val writer = attempt?.writer
            if (
                attempt == null ||
                !attempt.running ||
                attempt.explicitCloseRequested ||
                attempt.terminal ||
                writer == null
            ) {
                false
            } else {
                writer.offer(bytes)
            }
        }
    }

    override fun uploadPastedImage(fileName: String, source: InputStream): String {
        val sideChannel = synchronized(lock) {
            activeAttempt?.takeIf { attempt ->
                attempt.running && !attempt.explicitCloseRequested && !attempt.terminal
            }?.sshSideChannel ?: error("The Mosh session is not connected.")
        }
        return sideChannel.uploadPastedImage(fileName, source)
    }

    override fun resize(columns: Int, rows: Int) {
        val attempt = synchronized(lock) {
            val current = activeAttempt ?: return
            if (current.explicitCloseRequested || current.terminal) return
            current.pendingResize = TerminalSize(boundedColumns(columns), boundedRows(rows))
            current
        }
        scheduleResize(attempt)
    }

    /** Queues only the latest process-local hint on the exact active Mosh attempt. */
    override fun updateNetworkHint(snapshot: NetworkAvailabilitySnapshot) {
        val job = synchronized(lock) {
            val attempt = activeAttempt ?: return
            if (
                !attempt.running || !attempt.sessionStarted || attempt.explicitCloseRequested ||
                attempt.terminal || attempt.stopIssued || attempt.sessionId == null
            ) {
                return
            }
            attempt.networkHintJob?.cancel()
            attempt.controlScope.launch(start = CoroutineStart.LAZY) {
                val sessionId = synchronized(lock) {
                    if (
                        activeAttempt !== attempt || !attempt.running || !attempt.sessionStarted ||
                        attempt.explicitCloseRequested || attempt.terminal || attempt.stopIssued
                    ) {
                        null
                    } else {
                        attempt.sessionId
                    }
                } ?: return@launch
                val hint = MoshNetworkHint(
                    modelVersion = MoshApi.MODEL_VERSION,
                    connectivityGeneration = snapshot.connectivityGeneration,
                    addressFamily = snapshot.addressFamily,
                    isMetered = snapshot.isMetered,
                )
                try {
                    extension.updateNetworkHint(sessionId, hint)
                } catch (_: CancellationException) {
                    // Attempt replacement/close cancels queued hint ownership.
                } catch (_: Exception) {
                    // A roaming hint is advisory and must not tear down a working transport.
                }
            }.also { attempt.networkHintJob = it }
        }
        job.start()
    }

    override fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        bootstrap.answerHostIdentityPrompt(promptToken, decision)
    }

    override fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        bootstrap.answerKeyboardInteractiveChallenge(challengeToken, responses)
    }

    override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) {
        bootstrap.cancelKeyboardInteractiveChallenge(challengeToken)
    }

    override fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) {
        bootstrap.answerTmuxSessionPrompt(promptToken, sessionId)
    }

    override fun deleteTmuxSession(promptToken: Long, sessionId: String) {
        bootstrap.deleteTmuxSession(promptToken, sessionId)
    }

    override fun queryTmuxSessionCatalog(includePreviews: Boolean): TmuxSessionCatalog = synchronized(lock) {
        activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
    }?.queryTmuxSessionCatalog(includePreviews) ?: TmuxSessionCatalog()

    override fun terminateTmuxSession(sessionId: String): TmuxSessionCatalog = synchronized(lock) {
        activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
    }?.terminateTmuxSession(sessionId) ?: TmuxSessionCatalog(deleteFailed = true)

    override fun switchTmuxSession(sessionId: String): Boolean =
        synchronized(lock) {
            activeAttempt?.sshSideChannel?.takeIf { activeAttempt?.running == true }
        }?.switchTmuxSession(sessionId, ::trySend) ?: false

    override fun cancelPendingPrompts() {
        bootstrap.cancelPendingPrompts()
    }

    override fun close() {
        closeCurrentAttempt(clearAuthentication = true)
    }

    private fun closeCurrentAttempt(clearAuthentication: Boolean) {
        val attempt = synchronized(lock) {
            activeAttempt?.also { current ->
                current.explicitCloseRequested = true
                current.running = false
                current.terminal = true
            }
        }
        try {
            bootstrap.close()
            attempt?.states?.publish(ConnectionState.Disconnected)
        } finally {
            attempt?.eventJob?.cancel()
            attempt?.resizeJob?.cancel()
            attempt?.networkHintJob?.cancel()
            closeResources(attempt)
            if (clearAuthentication) bootstrapRequest.ssh.clearAuthenticationSecrets()
        }
    }

    private fun handleEvent(attempt: ActiveMoshConnection, event: MoshTransportEvent) {
        if (!isCurrent(attempt)) return
        when (event.state) {
            MoshSessionState.CONNECTING -> Unit
            MoshSessionState.CONNECTED,
            MoshSessionState.ROAMING,
            -> publishConnectedIfNeeded(attempt, event.state)
            MoshSessionState.SUSPENDED -> Unit
            MoshSessionState.DISCONNECTED -> {
                markTerminal(attempt)
                attempt.states.publishTerminalAndCleanup(
                    moshDisconnectState(event.disconnectReason),
                ) { closeResources(attempt) }
            }
            MoshSessionState.ERROR -> failConnection(attempt, moshErrorFailure(event.errorCode))
            else -> failTerminal(attempt, "Mosh transport returned an invalid session state.")
        }
    }

    private fun publishConnectedIfNeeded(attempt: ActiveMoshConnection, state: Int) {
        if (state !in setOf(
                MoshSessionState.CONNECTED,
                MoshSessionState.ROAMING,
            )
        ) {
            return
        }
        val publish = synchronized(lock) {
            if (
                activeAttempt !== attempt ||
                attempt.explicitCloseRequested ||
                attempt.terminal ||
                attempt.connectedPublished
            ) {
                false
            } else {
                attempt.connectedPublished = true
                true
            }
        }
        if (publish) attempt.states.publish(ConnectionState.Connected)
    }

    private suspend fun publishEofIfNeeded(attempt: ActiveMoshConnection) {
        // The isolated worker owns the terminal pipe while the broker owns lifecycle callbacks.
        // Process death can therefore close the pipe just before the broker reports EXTENSION_DIED.
        // Give that authoritative terminal event a small bounded window to win the race; a truly
        // eventless clean EOF still becomes Disconnected below.
        withTimeoutOrNull(EOF_EVENT_GRACE_MILLIS) {
            while (isCurrent(attempt) && !attempt.terminal && !attempt.explicitCloseRequested) {
                delay(EOF_EVENT_POLL_MILLIS)
            }
        }
        val publish = synchronized(lock) {
            if (activeAttempt !== attempt || attempt.terminal) {
                false
            } else {
                attempt.running = false
                attempt.terminal = true
                true
            }
        }
        if (publish) {
            attempt.states.publishTerminalAndCleanup(ConnectionState.Disconnected) {
                closeResources(attempt)
            }
        }
    }

    private fun failTransport(attempt: ActiveMoshConnection, message: String) {
        failConnection(attempt, transientTransportFailure(message))
    }

    private fun failTerminal(attempt: ActiveMoshConnection, message: String) {
        failConnection(attempt, ConnectionState.Failed(message))
    }

    private fun failConnection(attempt: ActiveMoshConnection, failure: ConnectionState.Failed) {
        val publish = synchronized(lock) {
            if (
                activeAttempt !== attempt ||
                attempt.explicitCloseRequested ||
                attempt.terminal
            ) {
                false
            } else {
                attempt.running = false
                attempt.terminal = true
                true
            }
        }
        if (!publish) return
        attempt.states.publishTerminalAndCleanup(failure) { closeResources(attempt) }
    }

    private fun markTerminal(attempt: ActiveMoshConnection) {
        synchronized(lock) {
            attempt.running = false
            attempt.terminal = true
        }
    }

    private fun closeResources(attempt: ActiveMoshConnection?) {
        val resources = synchronized(lock) {
            if (attempt != null && activeAttempt !== attempt) return
            val current = MoshConnectionResources(
                writer = attempt?.writer,
                transport = attempt?.transport,
                sshSideChannel = attempt?.sshSideChannel,
            )
            attempt?.writer = null
            attempt?.transport = null
            attempt?.sshSideChannel = null
            attempt?.running = false
            current
        }
        resources.writer?.stop()
        resources.transport?.closeQuietly()
        resources.sshSideChannel?.close()
    }

    private fun scheduleResize(attempt: ActiveMoshConnection) {
        val launch = synchronized(lock) {
            if (
                activeAttempt !== attempt ||
                attempt.explicitCloseRequested ||
                attempt.terminal ||
                !attempt.sessionStarted ||
                attempt.resizeJob != null
            ) {
                false
            } else {
                true
            }
        }
        if (!launch) return
        val job = attempt.controlScope.launch(start = CoroutineStart.LAZY) { drainResizes(attempt) }
        val accepted = synchronized(lock) {
            if (
                activeAttempt === attempt &&
                !attempt.terminal &&
                !attempt.explicitCloseRequested &&
                attempt.resizeJob == null
            ) {
                attempt.resizeJob = job
                true
            } else {
                false
            }
        }
        if (accepted) job.start() else job.cancel()
    }

    private suspend fun drainResizes(attempt: ActiveMoshConnection) {
        while (true) {
            val work = synchronized(lock) {
                if (
                    activeAttempt !== attempt ||
                    attempt.explicitCloseRequested ||
                    attempt.terminal ||
                    !attempt.sessionStarted
                ) {
                    attempt.resizeJob = null
                    return
                }
                val size = attempt.pendingResize
                if (size == null) {
                    attempt.resizeJob = null
                    return
                }
                attempt.pendingResize = null
                attempt.sessionId?.let { id -> id to size }
            } ?: return
            val result = try {
                extension.resizeSession(work.first, work.second.columns, work.second.rows)
            } catch (_: CancellationException) {
                return
            } catch (_: Exception) {
                failTerminal(attempt, "Mosh transport could not resize the session.")
                return
            }
            when (result) {
                is MoshClientResult.Success -> Unit
                is MoshClientResult.Failure -> {
                    failConnection(attempt, result.reason.resizeFailure())
                    return
                }
            }
        }
    }

    private suspend fun stopExtensionSession(attempt: ActiveMoshConnection) {
        val sessionId = synchronized(lock) {
            if (!attempt.sessionStarted || attempt.stopIssued) return
            attempt.stopIssued = true
            attempt.sessionId
        } ?: return
        val reason = if (attempt.explicitCloseRequested) {
            MoshStopReason.USER_REQUESTED
        } else {
            MoshStopReason.ERROR_RECOVERY
        }
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
                    extension.stopSession(sessionId, reason)
                }
            } catch (_: Exception) {
                // Pipe closure is already authoritative; a failed best-effort stop is not retried.
            }
        }
    }

    private fun ensureActive(attempt: ActiveMoshConnection) {
        if (!isCurrent(attempt) || attempt.explicitCloseRequested || attempt.terminal) {
            throw CancelledMoshConnectionException()
        }
    }

    private fun isCurrent(attempt: ActiveMoshConnection): Boolean = synchronized(lock) {
        activeAttempt === attempt
    }

    private fun isRunning(attempt: ActiveMoshConnection): Boolean = synchronized(lock) {
        activeAttempt === attempt && attempt.running && !attempt.explicitCloseRequested && !attempt.terminal
    }

    private companion object {
        const val DEFAULT_WRITER_CAPACITY = 256
        const val DEFAULT_WRITER_POLL_MILLIS = 250L
        const val READ_BUFFER_BYTES = 8 * 1024
        const val STOP_TIMEOUT_MILLIS = 2_000L
        const val EOF_EVENT_GRACE_MILLIS = 1_000L
        const val EOF_EVENT_POLL_MILLIS = 10L
    }
}

internal interface MoshConnectionBootstrap : AutoCloseable {
    suspend fun bootstrap(
        request: MoshBootstrapRequest,
        onState: (MoshBootstrapState) -> Unit,
    ): MoshBootstrapResult

    fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    )

    fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        responses.forEach { it.fill('\u0000') }
    }

    fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit

    fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) = Unit

    fun deleteTmuxSession(promptToken: Long, sessionId: String) = Unit

    fun cancelPendingPrompts()
}

private class ExecutorMoshConnectionBootstrap(
    private val delegate: MoshBootstrapExecutor,
) : MoshConnectionBootstrap {
    override suspend fun bootstrap(
        request: MoshBootstrapRequest,
        onState: (MoshBootstrapState) -> Unit,
    ): MoshBootstrapResult = delegate.bootstrap(request, onState)

    override fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ) = delegate.answerHostIdentityPrompt(promptToken, decision)

    override fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) = delegate.answerKeyboardInteractiveChallenge(challengeToken, responses)

    override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) =
        delegate.cancelKeyboardInteractiveChallenge(challengeToken)

    override fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) =
        delegate.answerTmuxSessionPrompt(promptToken, sessionId)

    override fun deleteTmuxSession(promptToken: Long, sessionId: String) =
        delegate.deleteTmuxSession(promptToken, sessionId)

    override fun cancelPendingPrompts() = delegate.cancelPendingPrompts()

    override fun close() = delegate.close()
}

internal class MoshSessionStartSpec(
    val sessionId: UUID,
    val addressFamily: Int,
    addressBytes: ByteArray,
    val udpPort: Int,
    val initialColumns: Int,
    val initialRows: Int,
    val locale: String,
    val optionFlags: Long,
) {
    private val numericAddress = addressBytes.copyOf()

    val addressBytes: ByteArray get() = numericAddress.copyOf()

    init {
        require(
            (addressFamily == ApiMoshAddressFamily.IPV4 && numericAddress.size == 4) ||
                (addressFamily == ApiMoshAddressFamily.IPV6 && numericAddress.size == 16),
        ) { "Mosh numeric address does not match its family." }
        require(udpPort in 1..65_535) { "Mosh UDP port is outside the valid range." }
        require(initialColumns in 1..MoshContractLimit.MAX_COLUMNS)
        require(initialRows in 1..MoshContractLimit.MAX_ROWS)
        require(
            locale.isNotEmpty() &&
                locale.encodeToByteArray().size <= MoshContractLimit.LOCALE_UTF8_BYTES &&
                MOSH_API_LOCALE.matches(locale),
        ) { "Mosh locale is invalid." }
        val knownOptions = MoshOption.PREDICTION_ALWAYS or
            MoshOption.PREDICTION_NEVER or
            MoshOption.DISPLAY_AMBIGUOUS_WIDTH_WIDE
        require(optionFlags and knownOptions.inv() == 0L) { "Mosh option flags are invalid." }
        require(
            optionFlags and MoshOption.PREDICTION_ALWAYS == 0L ||
                optionFlags and MoshOption.PREDICTION_NEVER == 0L,
        ) { "Mosh prediction flags are mutually exclusive." }
    }
}

internal data class MoshTransportEvent(
    val state: Int,
    val disconnectReason: Int,
    val errorCode: Int,
)

internal interface MoshTerminalTransport : AutoCloseable {
    val terminalInput: OutputStream
    val terminalOutput: InputStream
    val initialState: Int
}

internal sealed interface MoshTransportStartResult {
    data class Success(val value: MoshTerminalTransport) : MoshTransportStartResult

    data class Failure(val reason: MoshClientFailure) : MoshTransportStartResult
}

/** Marker kept off SSH transports so repository hint fan-out cannot call them accidentally. */
internal fun interface MoshNetworkHintReceiver {
    fun updateNetworkHint(snapshot: NetworkAvailabilitySnapshot)
}

internal interface MoshConnectionExtension {
    suspend fun connect(): MoshExtensionStatus

    fun sessionEvents(sessionId: UUID): Flow<MoshTransportEvent>

    /** Takes ownership of [sessionKey] and must zero it on every path. */
    suspend fun startSession(
        spec: MoshSessionStartSpec,
        sessionKey: ByteArray,
    ): MoshTransportStartResult

    suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit>

    suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit> = MoshClientResult.Success(Unit)

    suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit>
}

/** Android PFD ownership adapter around the process-singleton extension client. */
internal class AndroidMoshConnectionExtension(
    private val client: MoshExtensionClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MoshConnectionExtension {
    override suspend fun connect(): MoshExtensionStatus = client.connect()

    override fun sessionEvents(sessionId: UUID): Flow<MoshTransportEvent> =
        client.sessionEvents(sessionId).map(MoshSessionEvent::toTransportEvent)

    override suspend fun startSession(
        spec: MoshSessionStartSpec,
        sessionKey: ByteArray,
    ): MoshTransportStartResult = withContext(ioDispatcher) {
        var keyRead: ParcelFileDescriptor? = null
        var keyWrite: ParcelFileDescriptor? = null
        try {
            if (sessionKey.size != MOSH_SESSION_KEY_BYTES || !sessionKey.all(::isMoshKeyByte)) {
                return@withContext MoshTransportStartResult.Failure(MoshClientFailure.INVALID_REQUEST)
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            keyRead = readEnd
            keyWrite = writeEnd
            val keyOutput = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
            keyWrite = null
            keyOutput.use { output ->
                output.write(sessionKey, 0, sessionKey.size)
                output.flush()
            }
            sessionKey.fill(0)

            val request = MoshSessionRequest(
                modelVersion = MoshApi.MODEL_VERSION,
                sessionId = spec.sessionId.toString(),
                serverAddress = spec.addressBytes,
                addressFamily = spec.addressFamily,
                udpPort = spec.udpPort,
                moshKeyRead = readEnd,
                initialColumns = spec.initialColumns,
                initialRows = spec.initialRows,
                locale = spec.locale,
                optionFlags = spec.optionFlags,
            )
            when (val result = client.startSession(request)) {
                is MoshClientResult.Success -> result.value.toTerminalTransport()
                is MoshClientResult.Failure -> MoshTransportStartResult.Failure(result.reason)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MoshTransportStartResult.Failure(MoshClientFailure.REMOTE_FAILURE)
        } finally {
            sessionKey.fill(0)
            keyWrite?.closeQuietly()
            keyRead?.closeQuietly()
        }
    }

    override suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit> = client.resizeSession(sessionId, columns, rows)

    override suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit> = client.updateNetworkHint(sessionId, hint)

    override suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit> = client.stopSession(sessionId, reason)

    private fun MoshSessionHandle.toTerminalTransport(): MoshTransportStartResult {
        var ownedInput: OutputStream? = null
        var ownedOutput: InputStream? = null
        return try {
            val input = ParcelFileDescriptor.AutoCloseOutputStream(terminalInputWrite)
            ownedInput = input
            val output = ParcelFileDescriptor.AutoCloseInputStream(terminalOutputRead)
            ownedOutput = output
            MoshTransportStartResult.Success(
                PfdMoshTerminalTransport(
                    terminalInput = input,
                    terminalOutput = output,
                    initialState = initialState,
                ),
            ).also {
                ownedInput = null
                ownedOutput = null
            }
        } catch (_: Exception) {
            ownedInput?.closeQuietly()
            ownedOutput?.closeQuietly()
            terminalInputWrite.closeQuietly()
            terminalOutputRead.closeQuietly()
            MoshTransportStartResult.Failure(MoshClientFailure.INVALID_EXTENSION_RESPONSE)
        }
    }

    private companion object {
        const val MOSH_SESSION_KEY_BYTES = 22
    }
}

private class PfdMoshTerminalTransport(
    override val terminalInput: OutputStream,
    override val terminalOutput: InputStream,
    override val initialState: Int,
) : MoshTerminalTransport {
    private val lock = Any()
    private var closed = false

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        terminalInput.closeQuietly()
        terminalOutput.closeQuietly()
    }
}

private class ActiveMoshConnection(
    val states: ConnectionStatePublisher,
    val controlScope: CoroutineScope,
) {
    @Volatile var explicitCloseRequested = false
    @Volatile var terminal = false
    @Volatile var running = false
    @Volatile var connectedPublished = false
    @Volatile var sessionStarted = false
    @Volatile var stopIssued = false
    @Volatile var sessionId: UUID? = null
    @Volatile var transport: MoshTerminalTransport? = null
    @Volatile var writer: BoundedSshWriter? = null
    @Volatile var sshSideChannel: MoshBootstrapResult? = null
    @Volatile var eventJob: Job? = null
    @Volatile var resizeJob: Job? = null
    @Volatile var networkHintJob: Job? = null
    @Volatile var pendingResize: TerminalSize? = null
}

private data class TerminalSize(val columns: Int, val rows: Int)

private data class MoshConnectionResources(
    val writer: BoundedSshWriter?,
    val transport: MoshTerminalTransport?,
    val sshSideChannel: MoshBootstrapResult?,
)

private class SafeMoshConnectionException(
    val failure: ConnectionState.Failed,
) : Exception(failure.message)

private class CancelledMoshConnectionException : Exception("Mosh connection was cancelled.")

private fun moshFallbackFailure(
    message: String,
    kind: MoshFallbackFailure,
    transient: Boolean = false,
): ConnectionState.Failed = ConnectionState.Failed(
    message = message,
    disposition = if (transient) {
        ConnectionFailureDisposition.TRANSIENT_TRANSPORT
    } else {
        ConnectionFailureDisposition.TERMINAL
    },
    moshFallbackFailure = kind,
)

private fun MoshExtensionStatus.failureOrNull(): ConnectionState.Failed? = when (this) {
    is MoshExtensionStatus.Available -> null
    MoshExtensionStatus.Absent -> moshFallbackFailure("The built-in Mosh transport is unavailable.", MoshFallbackFailure.EXTENSION)
    is MoshExtensionStatus.Disabled -> moshFallbackFailure("Mosh transport is disabled.", MoshFallbackFailure.EXTENSION)
    is MoshExtensionStatus.Untrusted ->
        ConnectionState.Failed("The built-in Mosh transport failed its safety checks.")
    is MoshExtensionStatus.Incompatible ->
        moshFallbackFailure("Mosh transport is incompatible with this app.", MoshFallbackFailure.EXTENSION)
    MoshExtensionStatus.Checking -> moshFallbackFailure("Mosh transport is not ready.", MoshFallbackFailure.EXTENSION)
    is MoshExtensionStatus.Error -> when (reason) {
        com.yanjiyu.terminalspike.connection.mosh.MoshExtensionError.CLIENT_CLOSED ->
            ConnectionState.Failed("Mosh transport client is closed.")
        else -> moshFallbackFailure("Mosh transport is unavailable.", MoshFallbackFailure.EXTENSION)
    }
}

private fun MoshClientFailure.connectionFailure(): ConnectionState.Failed = when (this) {
    MoshClientFailure.EXTENSION_UNAVAILABLE ->
        moshFallbackFailure("Mosh transport became unavailable.", MoshFallbackFailure.EXTENSION, transient = true)
    MoshClientFailure.INVALID_REQUEST ->
        ConnectionState.Failed("Mosh transport rejected the session request.")
    MoshClientFailure.CAPABILITY_MISMATCH ->
        moshFallbackFailure("Mosh transport does not support this session.", MoshFallbackFailure.EXTENSION)
    MoshClientFailure.INVALID_EXTENSION_RESPONSE ->
        ConnectionState.Failed("Mosh transport returned an invalid response.")
    MoshClientFailure.REMOTE_FAILURE ->
        moshFallbackFailure("Mosh transport failed while starting the session.", MoshFallbackFailure.EXTENSION, transient = true)
    MoshClientFailure.CLIENT_CLOSED -> ConnectionState.Failed("Mosh transport client is closed.")
}

private fun MoshClientFailure.resizeFailure(): ConnectionState.Failed = when (this) {
    MoshClientFailure.EXTENSION_UNAVAILABLE,
    MoshClientFailure.REMOTE_FAILURE,
    -> transientTransportFailure("Mosh transport connection was lost while resizing.")
    MoshClientFailure.INVALID_REQUEST,
    MoshClientFailure.CAPABILITY_MISMATCH,
    MoshClientFailure.INVALID_EXTENSION_RESPONSE,
    MoshClientFailure.CLIENT_CLOSED,
    -> ConnectionState.Failed("Mosh transport could not resize the session.")
}

internal fun MoshBootstrapException.safeConnectionFailure(): ConnectionState.Failed = when (failure) {
    MoshBootstrapFailure.CANCELLED -> ConnectionState.Failed("Mosh connection was cancelled.")
    MoshBootstrapFailure.INVALID_CONFIGURATION ->
        ConnectionState.Failed("Mosh server settings are invalid.")
    MoshBootstrapFailure.SSH -> {
        val safeMessage = message ?: "Mosh SSH bootstrap failed."
        val causeFailure = (cause as? Exception)?.let { sshFailure(repository = null, error = it) }
        if (causeFailure?.disposition == ConnectionFailureDisposition.TRANSIENT_TRANSPORT) {
            transientTransportFailure(safeMessage)
        } else {
            ConnectionState.Failed(safeMessage)
        }
    }
    MoshBootstrapFailure.ADDRESS_RESOLUTION ->
        moshFallbackFailure("Mosh server address could not be resolved.", MoshFallbackFailure.BOOTSTRAP, transient = true)
    MoshBootstrapFailure.TIMEOUT ->
        moshFallbackFailure("Mosh server startup timed out.", MoshFallbackFailure.BOOTSTRAP, transient = true)
    MoshBootstrapFailure.OUTPUT_LIMIT ->
        moshFallbackFailure("Mosh server startup output exceeded its safety limit.", MoshFallbackFailure.BOOTSTRAP)
    MoshBootstrapFailure.MISSING_CONNECT_LINE ->
        moshFallbackFailure("Mosh server did not return a startup response.", MoshFallbackFailure.BOOTSTRAP)
    MoshBootstrapFailure.MALFORMED_CONNECT_LINE ->
        moshFallbackFailure("Mosh server returned a malformed startup response.", MoshFallbackFailure.BOOTSTRAP)
    MoshBootstrapFailure.AMBIGUOUS_CONNECT_LINE ->
        moshFallbackFailure("Mosh server returned an ambiguous startup response.", MoshFallbackFailure.BOOTSTRAP)
    MoshBootstrapFailure.INTERNAL -> ConnectionState.Failed("Mosh SSH bootstrap failed.")
}

internal fun moshErrorFailure(errorCode: Int): ConnectionState.Failed = when (errorCode) {
    MoshErrorCode.EXTENSION_ABSENT -> moshFallbackFailure("Mosh transport is unavailable.", MoshFallbackFailure.EXTENSION)
    MoshErrorCode.EXTENSION_INCOMPATIBLE ->
        moshFallbackFailure("Mosh transport is incompatible with this app.", MoshFallbackFailure.EXTENSION)
    MoshErrorCode.EXTENSION_UNTRUSTED ->
        ConnectionState.Failed("The built-in Mosh transport failed its safety checks.")
    MoshErrorCode.INVALID_REQUEST ->
        ConnectionState.Failed("Mosh transport rejected the session request.")
    MoshErrorCode.KEY_READ_FAILED ->
        ConnectionState.Failed("Mosh transport could not read its session key.")
    MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL ->
        moshFallbackFailure("Mosh could not reach the server over UDP.", MoshFallbackFailure.UDP, transient = true)
    MoshErrorCode.LOCALE_UNSUPPORTED ->
        ConnectionState.Failed("Mosh server does not support the selected locale.")
    MoshErrorCode.NATIVE_INITIALIZATION_FAILED ->
        ConnectionState.Failed("Mosh transport could not initialize its transport.")
    MoshErrorCode.EXTENSION_DIED ->
        moshFallbackFailure("Mosh transport stopped unexpectedly.", MoshFallbackFailure.EXTENSION, transient = true)
    MoshErrorCode.CANCELLED -> ConnectionState.Failed("Mosh session was cancelled.")
    MoshErrorCode.INTERNAL_REDACTED,
    MoshErrorCode.NONE,
    -> ConnectionState.Failed("Mosh transport failed.")
    else -> ConnectionState.Failed("Mosh transport returned an invalid error code.")
}

internal fun moshDisconnectState(disconnectReason: Int): ConnectionState = when (disconnectReason) {
    MoshDisconnectReason.USER_REQUESTED -> ConnectionState.Disconnected
    MoshDisconnectReason.TRANSPORT_LOST ->
        transientTransportFailure("Mosh transport was lost.")
    MoshDisconnectReason.REMOTE_CLOSED -> ConnectionState.Failed("Mosh session ended remotely.")
    MoshDisconnectReason.EXTENSION_FAILED ->
        ConnectionState.Failed("Mosh transport stopped the session unexpectedly.")
    MoshDisconnectReason.SESSION_REPLACED -> ConnectionState.Failed("Mosh session was replaced.")
    MoshDisconnectReason.NONE -> ConnectionState.Failed("Mosh session ended without a reason.")
    else -> ConnectionState.Failed("Mosh transport returned an invalid disconnect reason.")
}

private fun MoshSessionEvent.toTransportEvent(): MoshTransportEvent = MoshTransportEvent(
    state = state,
    disconnectReason = disconnectReason,
    errorCode = errorCode,
)

private fun MoshAddressFamily.toApiAddressFamily(): Int = when (this) {
    MoshAddressFamily.IPV4 -> ApiMoshAddressFamily.IPV4
    MoshAddressFamily.IPV6 -> ApiMoshAddressFamily.IPV6
}

private fun boundedColumns(value: Int): Int = value.coerceIn(1, MoshContractLimit.MAX_COLUMNS)

private fun boundedRows(value: Int): Int = value.coerceIn(1, MoshContractLimit.MAX_ROWS)

private fun isMoshKeyByte(value: Byte): Boolean = (value.toInt() and 0xff).let { unsigned ->
    unsigned in 'A'.code..'Z'.code ||
        unsigned in 'a'.code..'z'.code ||
        unsigned in '0'.code..'9'.code ||
        unsigned == '/'.code || unsigned == '+'.code
}

private fun AutoCloseable.closeQuietly() {
    runCatching(::close)
}

private val MOSH_API_LOCALE = Regex("[A-Za-z0-9][A-Za-z0-9_.@-]{0,63}")
