package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.SocketFactory
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException
import kotlin.math.min

/** Non-secret options for one fresh SSH launch of mosh-server. */
internal data class MoshBootstrapRequest(
    val ssh: SshConnectionConfig,
    val serverCommand: String? = null,
    val locale: String = DEFAULT_MOSH_LOCALE,
    val udpPort: Int? = null,
    val udpPortRange: MoshPortRange? = null,
) {
    init {
        require(udpPort == null || udpPort in 1..65_535) {
            "Mosh UDP port must be between 1 and 65535."
        }
        require(udpPort == null || udpPortRange == null) {
            "Select either a Mosh UDP port or range, not both."
        }
        serverCommand?.let(::validateMoshServerCommand)
        require(locale.length in 1..MAX_MOSH_LOCALE_LENGTH && MOSH_LOCALE.matches(locale)) {
            "Mosh locale must be a bounded UTF-8 locale name."
        }
    }
}

internal sealed interface MoshBootstrapState {
    data object Authenticating : MoshBootstrapState

    data class AwaitingApproval(val prompt: ConnectionPrompt) : MoshBootstrapState

    data object StartingServer : MoshBootstrapState
}

internal enum class MoshAddressFamily {
    IPV4,
    IPV6,
}

/**
 * Successful bootstrap material plus the already authenticated SSH image-upload side channel. The
 * caller must [clearSessionKey] immediately after writing the key to the extension pipe, then
 * [close] the side channel when the Mosh attempt ends.
 */
internal class MoshBootstrapResult(
    val addressFamily: MoshAddressFamily,
    addressBytes: ByteArray,
    val udpPort: Int,
    sessionKey: ByteArray,
    private val sshSideChannel: MoshSshExecSession? = null,
    initialTmuxSessionId: String? = null,
    startedInTmux: Boolean = initialTmuxSessionId != null,
    private var tmuxExecutable: String? = null,
    private var tmuxSessionsBeforeStart: Set<String> = emptySet(),
    private var cachedTmuxPaneCapture: TmuxPaneCapture? = null,
    private var tmuxLiveHistoryRefreshPending: Boolean = startedInTmux,
) : AutoCloseable {
    val addressBytes: ByteArray = addressBytes.copyOf()
    val sessionKey: ByteArray = sessionKey
    private var attachedTmuxSessionId: String? = initialTmuxSessionId
    private val startedInTmux = startedInTmux
    private var discoveredTmuxClient: TmuxClientIdentity? = null
    private var tmuxClientWasDiscovered = false
    private val tmuxTasks = TmuxTaskMonitor()

    val isTmuxSession: Boolean
        get() = synchronized(this) {
            if (tmuxClientWasDiscovered) discoveredTmuxClient != null
            else startedInTmux || attachedTmuxSessionId != null
        }

    val terminalTaskStatus: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
        get() = tmuxTasks.paneStatus(synchronized(this) { discoveredTmuxClient?.paneId })

    fun acknowledgeTaskStatus() {
        tmuxTasks.acknowledge(synchronized(this) { discoveredTmuxClient?.paneId })
    }

    fun refreshTmuxIdentity(): Boolean {
        val runner = sshSideChannel?.tmuxCommandRunner() ?: return false
        val executable = synchronized(this) { tmuxExecutable } ?: queryTmuxExecutable(runner)
            ?: return false
        tmuxTasks.refresh(runner, executable)
        val observation = discoverTmuxClient(runner, executable)
        if (observation == TmuxClientObservation.Unavailable) return false
        return synchronized(this) {
            tmuxExecutable = executable
            val identity = (observation as? TmuxClientObservation.Attached)?.identity
            if (identity == null && !tmuxClientWasDiscovered) return false
            tmuxClientWasDiscovered = true
            if (discoveredTmuxClient == identity) return false
            discoveredTmuxClient = identity
            attachedTmuxSessionId = identity?.sessionId
            cachedTmuxPaneCapture = null
            tmuxLiveHistoryRefreshPending = identity != null
            true
        }
    }

    fun captureTmuxPane(includeHistory: Boolean): TmuxPaneCapture? {
        if (includeHistory) {
            synchronized(this) {
                cachedTmuxPaneCapture?.let { cached ->
                    cachedTmuxPaneCapture = null
                    return cached
                }
            }
        }
        val sideChannel = sshSideChannel ?: return null
        val (executable, authoritative) = synchronized(this) {
            tmuxExecutable to tmuxLiveHistoryRefreshPending
        }
        executable ?: return null
        val metadataRunner = sideChannel.tmuxCommandRunner()
        val targetSessionId = synchronized(this) { attachedTmuxSessionId } ?: resolveNewTmuxSessionId(
            metadataRunner,
            synchronized(this) { tmuxSessionsBeforeStart },
        ) ?: return null
        synchronized(this) {
            if (attachedTmuxSessionId == null) attachedTmuxSessionId = targetSessionId
        }
        val capture = captureTmuxPane(
            metadataRunner = metadataRunner,
            historyRunner = sideChannel.tmuxHistoryCommandRunner(),
            executable = executable,
            sessionId = targetSessionId,
            authoritative = authoritative && includeHistory,
            includeHistory = includeHistory,
            paneId = synchronized(this) { discoveredTmuxClient?.paneId },
        ) ?: return null
        return synchronized(this) {
            capture.takeIf { attachedTmuxSessionId == targetSessionId }?.also {
                if (authoritative && includeHistory) tmuxLiveHistoryRefreshPending = false
            }
        }
    }

    fun captureTmuxHistoryPage(request: TmuxHistoryPageRequest): TmuxPaneCapture? {
        val sideChannel = sshSideChannel ?: return null
        val executable = synchronized(this) { tmuxExecutable } ?: return null
        val sessionId = synchronized(this) { attachedTmuxSessionId } ?: return null
        val capture = captureTmuxPane(
            metadataRunner = sideChannel.tmuxCommandRunner(),
            historyRunner = sideChannel.tmuxHistoryCommandRunner(),
            executable = executable,
            sessionId = sessionId,
            pageRequest = request,
            paneId = synchronized(this) { discoveredTmuxClient?.paneId },
        ) ?: return null
        return synchronized(this) {
            capture.takeIf { attachedTmuxSessionId == sessionId }
        }
    }

    init {
        try {
            require(
                (addressFamily == MoshAddressFamily.IPV4 && this.addressBytes.size == IPV4_BYTES) ||
                    (addressFamily == MoshAddressFamily.IPV6 && this.addressBytes.size == IPV6_BYTES),
            ) { "Numeric Mosh address does not match its address family." }
            require(udpPort in 1..65_535) { "Mosh UDP port is outside the valid range." }
            require(this.sessionKey.size == MOSH_KEY_BYTES && this.sessionKey.all(::isMoshKeyByte)) {
                "Mosh session key is malformed."
            }
        } catch (error: IllegalArgumentException) {
            this.sessionKey.fill(0)
            throw error
        }
    }

    fun clearSessionKey() {
        sessionKey.fill(0)
    }

    fun uploadPastedImage(fileName: String, source: InputStream): String =
        sshSideChannel?.uploadPastedImage(fileName, source)
            ?: error("The Mosh SSH image-upload side channel is not connected.")

    fun queryTmuxSessionCatalog(includePreviews: Boolean = false): TmuxSessionCatalog =
        sshSideChannel?.let { sideChannel ->
            queryTmuxSessionCatalog(sideChannel.tmuxCommandRunner(), includePreviews).let { catalog ->
                catalog.copy(sessions = catalog.sessions.map { it.copy(taskStatus = tmuxTasks.sessionStatus(it.id)) })
            }.copy(
                activeSessionId = synchronized(this) { attachedTmuxSessionId },
            )
        } ?: TmuxSessionCatalog()

    fun terminateTmuxSession(sessionId: String): TmuxSessionCatalog =
        sshSideChannel?.let { sideChannel ->
            terminateTmuxSession(sideChannel.tmuxCommandRunner(), sessionId)
        } ?: TmuxSessionCatalog(deleteFailed = true)

    fun switchTmuxSession(sessionId: String, sendInput: (ByteArray) -> Boolean): Boolean {
        val sourceSessionId = synchronized(this) { attachedTmuxSessionId }
        val sideChannel = sshSideChannel
        val runner = sideChannel?.tmuxCommandRunner()
        val executable = synchronized(this) { tmuxExecutable } ?: runner?.let(::queryTmuxExecutable)
        val switched = when (val result = runner?.let {
            switchOrAttachTmuxSession(it, sessionId, sourceSessionId,
                clientTty = synchronized(this) { discoveredTmuxClient?.tty })
        } ?: TmuxSessionSwitchResult.Failed) {
            TmuxSessionSwitchResult.Switched -> true
            is TmuxSessionSwitchResult.Attach ->
                sendInput((result.command + '\r').encodeToByteArray())
            TmuxSessionSwitchResult.Failed -> false
        }
        if (switched) synchronized(this) {
            attachedTmuxSessionId = sessionId
            tmuxExecutable = executable
            tmuxSessionsBeforeStart = emptySet()
            cachedTmuxPaneCapture = null
            tmuxLiveHistoryRefreshPending = true
        }
        return switched
    }

    override fun close() {
        clearSessionKey()
        sshSideChannel?.close()
    }

    private companion object {
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
    }
}

private fun MoshSshExecSession.tmuxCommandRunner(): TmuxCommandRunner =
    TmuxCommandRunner { command ->
        readBoundedExecOutput(openExec(command), TMUX_MOSH_EXEC_LIMITS).use { output ->
            TmuxExecOutput(output.stdout.copyOf(), output.exitStatus)
        }
    }

private fun MoshSshExecSession.tmuxHistoryCommandRunner(): TmuxCommandRunner =
    TmuxCommandRunner { command ->
        readBoundedExecOutput(openExec(command), TMUX_HISTORY_MOSH_EXEC_LIMITS).use { output ->
            TmuxExecOutput(output.stdout.copyOf(), output.exitStatus)
        }
    }

internal enum class MoshBootstrapFailure {
    CANCELLED,
    INVALID_CONFIGURATION,
    SSH,
    ADDRESS_RESOLUTION,
    TIMEOUT,
    OUTPUT_LIMIT,
    MISSING_CONNECT_LINE,
    MALFORMED_CONNECT_LINE,
    AMBIGUOUS_CONNECT_LINE,
    INTERNAL,
}

/** Carries only a stable, display-safe category and message; remote output is never included. */
internal class MoshBootstrapException(
    val failure: MoshBootstrapFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One-at-a-time SSH bootstrap owner. A new call cancels an older call; [close] is an explicit,
 * idempotent cancellation boundary equivalent to closing an SSH [Connection].
 */
internal class MoshBootstrapExecutor(
    private val sessionFactory: MoshSshExecSessionFactory,
    private val limits: MoshExecLimits = MoshExecLimits(),
) : AutoCloseable {
    constructor(knownHostManager: KnownHostManager) : this(
        JschMoshSshExecSessionFactory(
            JschAuthenticatedSessionFactory(knownHostManager),
        ),
    )

    private val lock = Any()
    private var nextOperationId = 1L
    private var activeOperation: ActiveMoshBootstrap? = null

    suspend fun bootstrap(
        request: MoshBootstrapRequest,
        onState: (MoshBootstrapState) -> Unit,
    ): MoshBootstrapResult {
        val operation = ActiveMoshBootstrap(
            id = synchronized(lock) { nextOperationId++ },
        )
        replaceActiveOperation(operation)

        var execSession: MoshSshExecSession? = null
        var output: BoundedExecOutput? = null
        var parsed: ParsedMoshConnect? = null
        try {
            publishIfActive(operation, MoshBootstrapState.Authenticating, onState)
            val session = sessionFactory.open(
                config = request.ssh,
                onPrompt = { prompt ->
                    publishIfActive(operation, MoshBootstrapState.AwaitingApproval(prompt), onState)
                },
                onKeyboardInteractiveChallenge = { challenge ->
                    publishIfActive(operation, MoshBootstrapState.AwaitingApproval(challenge), onState)
                },
                onRepositoryReady = { repository -> registerRepository(operation, repository) },
                registerConnectingSession = { resource -> registerResource(operation, resource) },
            ) ?: throw cancelledFailure()
            execSession = session
            if (!registerResourceIfMissing(operation, session)) throw cancelledFailure()
            ensureActive(operation)
            val tmuxChoice = if (request.ssh.tmuxSessionSelectorEnabled) {
                val runner = TmuxCommandRunner { tmuxCommand ->
                    readBoundedExecOutput(session.openExec(tmuxCommand), TMUX_MOSH_EXEC_LIMITS).use {
                        TmuxExecOutput(it.stdout.copyOf(), it.exitStatus)
                    }
                }
                TmuxSessionSelector(runner) { prompt ->
                    publishIfActive(
                        operation,
                        MoshBootstrapState.AwaitingApproval(prompt),
                        onState,
                    )
                }.also { selector -> operation.tmuxSelector = selector }
                    .awaitChoice()
                    .also { operation.tmuxSelector = null }
            } else {
                null
            }
            ensureActive(operation)
            val initialTmuxCapture = (tmuxChoice as? TmuxStartupChoice.Attach)?.let { choice ->
                captureTmuxPane(
                    metadataRunner = session.tmuxCommandRunner(),
                    historyRunner = session.tmuxHistoryCommandRunner(),
                    executable = choice.executable,
                    sessionId = choice.sessionId,
                    authoritative = true,
                )
            }
            val command = try {
                buildMoshServerCommand(
                    request = request,
                    tmuxSessionId = (tmuxChoice as? TmuxStartupChoice.Attach)?.sessionId,
                    startNewTmuxSession = tmuxChoice is TmuxStartupChoice.NewSession,
                    tmuxExecutable = tmuxChoice?.executable ?: DEFAULT_TMUX_EXECUTABLE,
                )
            } catch (error: IllegalArgumentException) {
                throw MoshBootstrapException(
                    MoshBootstrapFailure.INVALID_CONFIGURATION,
                    "Mosh server settings are invalid.",
                    error,
                )
            }
            publishIfActive(operation, MoshBootstrapState.StartingServer, onState)

            output = readBoundedExecOutput(session.openExec(command), limits)
            ensureActive(operation)
            parsed = parseMoshConnect(output.stdout)
            val result = session.numericAddress.toBootstrapResult(
                port = parsed.port,
                key = parsed.key,
                sshSideChannel = session,
                initialTmuxSessionId = (tmuxChoice as? TmuxStartupChoice.Attach)?.sessionId,
                startedInTmux = tmuxChoice != null,
                tmuxExecutable = tmuxChoice?.executable,
                tmuxSessionsBeforeStart =
                    (tmuxChoice as? TmuxStartupChoice.NewSession)?.existingSessionIds.orEmpty(),
                cachedTmuxPaneCapture = initialTmuxCapture,
            )
            parsed = null // The result now owns the only project-owned key copy.
            if (!finishSuccess(operation)) {
                result.close()
                throw cancelledFailure()
            }
            execSession = null // The result owns the authenticated side channel after success.
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cancelledFailure(error)
        } catch (error: MoshBootstrapException) {
            if (!isActive(operation) && error.failure != MoshBootstrapFailure.CANCELLED) {
                throw cancelledFailure(error)
            }
            throw error
        } catch (error: Exception) {
            if (!isActive(operation)) throw cancelledFailure(error)
            throw MoshBootstrapException(
                MoshBootstrapFailure.INTERNAL,
                "Mosh SSH bootstrap failed.",
                error,
            )
        } finally {
            parsed?.close()
            output?.close()
            runCatching { execSession?.close() }
            request.ssh.clearAuthenticationSecrets()
            finishOperation(operation)
        }
    }

    fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        synchronized(lock) { activeOperation?.hostKeyRepository }
            ?.answerPrompt(promptToken, decision)
    }

    fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        val controller = synchronized(lock) { activeOperation?.keyboardInteractive }
        if (controller == null) {
            responses.forEach { it.fill('\u0000') }
        } else {
            controller.answerKeyboardInteractiveChallenge(challengeToken, responses)
        }
    }

    fun cancelKeyboardInteractiveChallenge(challengeToken: Long) {
        synchronized(lock) { activeOperation?.keyboardInteractive }
            ?.cancelKeyboardInteractiveChallenge(challengeToken)
    }

    fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) {
        synchronized(lock) { activeOperation?.tmuxSelector }?.answer(promptToken, sessionId)
    }

    fun deleteTmuxSession(promptToken: Long, sessionId: String) {
        synchronized(lock) { activeOperation?.tmuxSelector }?.delete(promptToken, sessionId)
    }

    fun cancelPendingPrompts() {
        synchronized(lock) { activeOperation?.tmuxSelector }?.cancel()
        synchronized(lock) { activeOperation?.hostKeyRepository }?.cancelPrompt()
        synchronized(lock) { activeOperation?.keyboardInteractive }
            ?.cancelPendingKeyboardInteractiveChallenge()
    }

    override fun close() {
        val operation = synchronized(lock) {
            activeOperation?.also {
                it.cancelled = true
                activeOperation = null
            }
        } ?: return
        operation.hostKeyRepository?.cancelPrompt()
        operation.tmuxSelector?.cancel()
        operation.keyboardInteractive?.cancelPendingKeyboardInteractiveChallenge()
        runCatching { operation.resource?.close() }
    }

    private fun replaceActiveOperation(operation: ActiveMoshBootstrap) {
        val previous = synchronized(lock) {
            activeOperation.also { current ->
                current?.cancelled = true
                activeOperation = operation
            }
        }
        previous?.hostKeyRepository?.cancelPrompt()
        previous?.tmuxSelector?.cancel()
        previous?.keyboardInteractive?.cancelPendingKeyboardInteractiveChallenge()
        runCatching { previous?.resource?.close() }
    }

    private fun registerRepository(
        operation: ActiveMoshBootstrap,
        repository: VerifyingHostKeyRepository,
    ) {
        val accepted = synchronized(lock) {
            if (activeOperation !== operation || operation.cancelled) {
                false
            } else {
                operation.hostKeyRepository = repository
                true
            }
        }
        if (!accepted) repository.cancelPrompt()
    }

    private fun registerResource(
        operation: ActiveMoshBootstrap,
        resource: AutoCloseable,
    ): Boolean = synchronized(lock) {
        if (activeOperation !== operation || operation.cancelled) {
            false
        } else {
            operation.resource = resource
            operation.keyboardInteractive = resource as? KeyboardInteractivePromptController
            true
        }
    }

    private fun registerResourceIfMissing(
        operation: ActiveMoshBootstrap,
        resource: AutoCloseable,
    ): Boolean = synchronized(lock) {
        if (activeOperation !== operation || operation.cancelled) {
            false
        } else {
            if (operation.resource == null) {
                operation.resource = resource
                operation.keyboardInteractive = resource as? KeyboardInteractivePromptController
            }
            true
        }
    }

    private fun publishIfActive(
        operation: ActiveMoshBootstrap,
        state: MoshBootstrapState,
        publish: (MoshBootstrapState) -> Unit,
    ) {
        ensureActive(operation)
        publish(state)
    }

    private fun ensureActive(operation: ActiveMoshBootstrap) {
        if (!isActive(operation)) throw cancelledFailure()
    }

    private fun isActive(operation: ActiveMoshBootstrap): Boolean = synchronized(lock) {
        activeOperation === operation && !operation.cancelled
    }

    private fun finishSuccess(operation: ActiveMoshBootstrap): Boolean = synchronized(lock) {
        if (activeOperation !== operation || operation.cancelled) {
            false
        } else {
            activeOperation = null
            true
        }
    }

    private fun finishOperation(operation: ActiveMoshBootstrap) {
        synchronized(lock) {
            if (activeOperation === operation) activeOperation = null
        }
        operation.hostKeyRepository?.cancelPrompt()
        operation.tmuxSelector?.cancel()
        operation.keyboardInteractive?.cancelPendingKeyboardInteractiveChallenge()
    }
}

private class ActiveMoshBootstrap(
    @Suppress("unused") val id: Long,
) {
    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var hostKeyRepository: VerifyingHostKeyRepository? = null

    @Volatile
    var keyboardInteractive: KeyboardInteractivePromptController? = null

    @Volatile
    var tmuxSelector: TmuxSessionSelector? = null

    @Volatile
    var resource: AutoCloseable? = null
}

internal interface MoshSshExecSessionFactory {
    suspend fun open(
        config: SshConnectionConfig,
        onPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
        onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
        registerConnectingSession: (AutoCloseable) -> Boolean,
    ): MoshSshExecSession?
}

internal interface MoshSshExecSession : AutoCloseable, RemoteImageUploadConnection {
    val numericAddress: InetAddress

    fun openExec(command: String): MoshExecChannel

    override fun uploadPastedImage(fileName: String, source: InputStream): String =
        error("This Mosh SSH session does not provide image upload.")
}

internal class JschMoshSshExecSessionFactory(
    private val authenticatedSessionFactory: JschAuthenticatedSessionFactory,
) : MoshSshExecSessionFactory {
    override suspend fun open(
        config: SshConnectionConfig,
        onPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
        onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
        registerConnectingSession: (AutoCloseable) -> Boolean,
    ): MoshSshExecSession? {
        var repository: VerifyingHostKeyRepository? = null
        var authenticated: AuthenticatedJschSession? = null
        try {
            val peerCapture = CapturingJschSocketFactory(MOSH_SSH_CONNECT_TIMEOUT_MILLIS)
            val opened = authenticatedSessionFactory.connect(
                config = config,
                onPrompt = onPrompt,
                onKeyboardInteractiveChallenge = onKeyboardInteractiveChallenge,
                onRepositoryReady = {
                    repository = it
                    onRepositoryReady(it)
                },
                registerBeforeConnect = registerConnectingSession,
                socketFactory = peerCapture,
            ) ?: return null
            authenticated = opened
            val address = peerCapture.numericPeerAddress
                ?: throw MoshBootstrapException(
                    MoshBootstrapFailure.ADDRESS_RESOLUTION,
                    "Mosh server address could not be resolved.",
                )
            if (address !is Inet4Address && address !is Inet6Address) {
                throw MoshBootstrapException(
                    MoshBootstrapFailure.ADDRESS_RESOLUTION,
                    "Mosh server address family is unsupported.",
                )
            }
            return JschMoshSshExecSession(opened, address)
        } catch (error: MoshBootstrapException) {
            authenticated?.close()
            throw error
        } catch (error: Exception) {
            authenticated?.close()
            throw MoshBootstrapException(
                MoshBootstrapFailure.SSH,
                sshFailureMessage(repository, error),
                error,
            )
        }
    }
}

/** Captures the exact TCP peer whose host key and credentials JSch authenticated. */
private class CapturingJschSocketFactory(
    private val connectTimeoutMillis: Int,
) : SocketFactory {
    @Volatile
    var numericPeerAddress: InetAddress? = null
        private set

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            numericPeerAddress = InetAddress.getByAddress(socket.inetAddress.address)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class JschMoshSshExecSession(
    private val authenticated: AuthenticatedJschSession,
    override val numericAddress: InetAddress,
) : MoshSshExecSession {
    private val imageUploadLock = Any()

    override fun openExec(command: String): MoshExecChannel {
        val channel = authenticated.session.openChannel("exec") as ChannelExec
        channel.setPty(false)
        channel.setInputStream(null)
        channel.setCommand(command)
        return JschMoshExecChannel(channel)
    }

    override fun uploadPastedImage(fileName: String, source: InputStream): String =
        synchronized(imageUploadLock) {
            uploadPastedImageViaSftp(authenticated.session, fileName, source)
        }

    override fun close() {
        synchronized(imageUploadLock) { authenticated.close() }
    }
}

internal interface MoshExecChannel : AutoCloseable {
    val stdout: InputStream
    val stderr: InputStream
    val isClosed: Boolean
    val exitStatus: Int

    fun connect(timeoutMillis: Int)
}

private class JschMoshExecChannel(
    private val channel: ChannelExec,
) : MoshExecChannel {
    override val stdout: InputStream = channel.inputStream
    override val stderr: InputStream = channel.extInputStream
    override val isClosed: Boolean get() = channel.isClosed
    override val exitStatus: Int get() = channel.exitStatus

    override fun connect(timeoutMillis: Int) {
        channel.connect(timeoutMillis)
    }

    override fun close() {
        runCatching { stdout.close() }
        runCatching { stderr.close() }
        channel.disconnect()
    }
}

internal data class MoshExecLimits(
    val channelConnectTimeoutMillis: Int = 10_000,
    val totalTimeoutMillis: Long = 15_000,
    val maximumOutputBytes: Int = 16 * 1024,
    val maximumLineBytes: Int = 1_024,
    val maximumLines: Int = 128,
    val pollIntervalMillis: Long = 10,
) {
    init {
        require(channelConnectTimeoutMillis in 1..60_000)
        require(totalTimeoutMillis in 1..60_000)
        require(maximumOutputBytes in 1..TmuxPaneCapture.MAX_CAPTURE_BYTES)
        require(maximumLineBytes in 1..maximumOutputBytes)
        require(maximumLines in 1..(ModelLimits.MAX_SCROLLBACK_LINES + 1))
        require(pollIntervalMillis in 1..1_000)
    }
}

internal fun interface MoshMonotonicClock {
    fun nowNanos(): Long
}

internal fun interface MoshPollWaiter {
    fun pause(millis: Long)
}

private object SystemMoshMonotonicClock : MoshMonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

private object ThreadMoshPollWaiter : MoshPollWaiter {
    override fun pause(millis: Long) {
        Thread.sleep(millis)
    }
}

internal class BoundedExecOutput(
    val stdout: ByteArray,
    val exitStatus: Int,
) : AutoCloseable {
    override fun close() {
        stdout.fill(0)
    }
}

internal fun readBoundedExecOutput(
    channel: MoshExecChannel,
    limits: MoshExecLimits,
    clock: MoshMonotonicClock = SystemMoshMonotonicClock,
    waiter: MoshPollWaiter = ThreadMoshPollWaiter,
): BoundedExecOutput {
    val budget = ExecOutputBudget(limits)
    val stdout = ExecOutputCollector(limits.maximumOutputBytes, budget, retainBytes = true)
    val stderr = ExecOutputCollector(limits.maximumOutputBytes, budget, retainBytes = false)
    val scratch = ByteArray(min(EXEC_READ_BUFFER_BYTES, limits.maximumOutputBytes))
    val deadline = clock.nowNanos() + limits.totalTimeoutMillis * NANOS_PER_MILLISECOND
    try {
        channel.connect(limits.channelConnectTimeoutMillis)
        if (clock.nowNanos() >= deadline) moshStartupTimedOut()
        while (true) {
            var progressed = drainAvailable(channel.stdout, stdout, scratch)
            progressed = drainAvailable(channel.stderr, stderr, scratch) || progressed
            if (channel.isClosed) {
                drainAvailable(channel.stdout, stdout, scratch)
                drainAvailable(channel.stderr, stderr, scratch)
                stdout.finish()
                stderr.finish()
                return BoundedExecOutput(stdout.takeBytes(), channel.exitStatus)
            }
            if (clock.nowNanos() >= deadline) {
                moshStartupTimedOut()
            }
            if (!progressed) waiter.pause(limits.pollIntervalMillis)
        }
    } catch (error: MoshBootstrapException) {
        throw error
    } catch (error: InterruptedException) {
        throw error
    } catch (error: Exception) {
        throw MoshBootstrapException(
            MoshBootstrapFailure.SSH,
            "Mosh server command failed over SSH.",
            error,
        )
    } finally {
        scratch.fill(0)
        stdout.close()
        stderr.close()
        runCatching { channel.close() }
    }
}

private fun drainAvailable(
    input: InputStream,
    collector: ExecOutputCollector,
    scratch: ByteArray,
): Boolean {
    var progressed = false
    while (true) {
        val available = input.available()
        if (available <= 0) return progressed
        val count = input.read(scratch, 0, min(available, scratch.size))
        if (count <= 0) return progressed
        try {
            collector.accept(scratch, count)
        } finally {
            scratch.fill(0, 0, count)
        }
        progressed = true
    }
}

private class ExecOutputBudget(
    private val limits: MoshExecLimits,
) {
    private var bytes = 0
    private var lines = 0

    fun addByte() {
        bytes += 1
        if (bytes > limits.maximumOutputBytes) outputLimitExceeded()
    }

    fun finishLine() {
        lines += 1
        if (lines > limits.maximumLines) outputLimitExceeded()
    }

    fun checkLineLength(length: Int) {
        if (length > limits.maximumLineBytes) outputLimitExceeded()
    }
}

private class ExecOutputCollector(
    capacity: Int,
    private val budget: ExecOutputBudget,
    private val retainBytes: Boolean,
) : AutoCloseable {
    private val retained = if (retainBytes) ByteArray(capacity) else null
    private var retainedCount = 0
    private var currentLineBytes = 0
    private var finished = false

    fun accept(bytes: ByteArray, count: Int) {
        check(!finished)
        for (index in 0 until count) {
            val value = bytes[index]
            budget.addByte()
            retained?.set(retainedCount++, value)
            if (value == NEWLINE_BYTE) {
                budget.finishLine()
                currentLineBytes = 0
            } else {
                currentLineBytes += 1
                budget.checkLineLength(currentLineBytes)
            }
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        if (currentLineBytes > 0) budget.finishLine()
    }

    fun takeBytes(): ByteArray {
        check(retainBytes && finished)
        val result = retained!!.copyOf(retainedCount)
        retained.fill(0)
        retainedCount = 0
        return result
    }

    override fun close() {
        retained?.fill(0)
        retainedCount = 0
    }
}

internal fun buildMoshServerCommand(
    request: MoshBootstrapRequest,
    tmuxSessionId: String? = null,
    startNewTmuxSession: Boolean = false,
    tmuxExecutable: String = DEFAULT_TMUX_EXECUTABLE,
): String {
    require(tmuxSessionId == null || !startNewTmuxSession) {
        "A Mosh session cannot both create and attach to tmux."
    }
    require(
        tmuxExecutable == DEFAULT_TMUX_EXECUTABLE ||
            (
                tmuxExecutable.startsWith('/') &&
                    tmuxExecutable.length <= MAX_TMUX_EXECUTABLE_PATH_CHARS &&
                    tmuxExecutable.none(Char::isISOControl)
                )
    ) { "Invalid tmux executable path." }
    val server = request.serverCommand ?: DEFAULT_MOSH_SERVER_COMMAND
    validateMoshServerCommand(server)
    val arguments = buildList {
        add(server)
        add("new")
        add("-c")
        add("256")
        add("-s")
        when {
            request.udpPort != null -> {
                add("-p")
                add(request.udpPort.toString())
            }
            request.udpPortRange != null -> {
                add("-p")
                add("${request.udpPortRange.first}:${request.udpPortRange.last}")
            }
        }
        add("-l")
        add("LANG=${request.locale}")
        if (tmuxSessionId != null || startNewTmuxSession) {
            tmuxSessionId?.let {
                require(it.isTmuxSessionId()) { "Invalid tmux session target." }
            }
            add("--")
            add(tmuxExecutable)
            if (startNewTmuxSession) {
                add("new-session")
            } else {
                add("attach-session")
                add("-t")
                add(requireNotNull(tmuxSessionId))
            }
        }
    }
    return arguments.joinToString(" ", transform = ::quotePosixShellArgument).also { command ->
        require(command.length <= MAX_BUILT_MOSH_COMMAND_LENGTH) { "Built Mosh command is too long." }
    }
}

private const val DEFAULT_TMUX_EXECUTABLE = "tmux"
private const val MAX_TMUX_EXECUTABLE_PATH_CHARS = 1_024

internal fun quotePosixShellArgument(argument: String): String = buildString(argument.length + 2) {
    append('\'')
    argument.forEach { character ->
        if (character == '\'') append("'\\''") else append(character)
    }
    append('\'')
}

private fun validateMoshServerCommand(command: String) {
    require(
        command.length in 1..ModelLimits.MAX_MOSH_SERVER_COMMAND_LENGTH &&
            command.isNotBlank() &&
            command == command.trim() &&
            command.none(Char::isISOControl),
    ) {
        "Mosh server command must be one bounded executable name or path without control characters."
    }
}

private class ParsedMoshConnect(
    val port: Int,
    val key: ByteArray,
) : AutoCloseable {
    override fun close() {
        key.fill(0)
    }
}

private fun parseMoshConnect(output: ByteArray): ParsedMoshConnect {
    var parsed: ParsedMoshConnect? = null
    try {
        var lineStart = 0
        while (lineStart <= output.size) {
            val newline = output.indexOf(NEWLINE_BYTE, startIndex = lineStart).let { index ->
                if (index >= 0) index else output.size
            }
            if (startsWithConnectPrefix(output, lineStart, newline)) {
                val candidate = parseConnectLine(output, lineStart, newline)
                    ?: throw MoshBootstrapException(
                        MoshBootstrapFailure.MALFORMED_CONNECT_LINE,
                        "Mosh server returned a malformed startup response.",
                    )
                if (parsed != null) {
                    candidate.close()
                    throw MoshBootstrapException(
                        MoshBootstrapFailure.AMBIGUOUS_CONNECT_LINE,
                        "Mosh server returned more than one startup response.",
                    )
                }
                parsed = candidate
            }
            if (newline == output.size) break
            lineStart = newline + 1
        }
        return parsed ?: throw MoshBootstrapException(
            MoshBootstrapFailure.MISSING_CONNECT_LINE,
            "Mosh server did not return a startup response.",
        )
    } catch (error: Exception) {
        parsed?.close()
        throw error
    }
}

private fun parseConnectLine(
    output: ByteArray,
    start: Int,
    rawEnd: Int,
): ParsedMoshConnect? {
    var end = rawEnd
    while (end > start && isOfficialTrailingWhitespace(output[end - 1])) end -= 1
    val portStart = start + MOSH_CONNECT_PREFIX.size
    if (portStart >= end) return null
    var separator = portStart
    while (separator < end && output[separator].isAsciiDigit()) separator += 1
    if (separator == portStart || separator >= end || output[separator] != SPACE_BYTE) return null
    val keyStart = separator + 1
    if (end - keyStart != MOSH_KEY_BYTES) return null

    var port = 0
    for (index in portStart until separator) {
        val digit = output[index].toInt() - '0'.code
        if (port > (65_535 - digit) / 10) return null
        port = port * 10 + digit
    }
    if (port !in 1..65_535) return null
    if ((keyStart until end).any { index -> !isMoshKeyByte(output[index]) }) return null
    return ParsedMoshConnect(port, output.copyOfRange(keyStart, end))
}

private fun startsWithConnectPrefix(
    output: ByteArray,
    start: Int,
    end: Int,
): Boolean {
    if (end - start < MOSH_CONNECT_WORDS.size) return false
    return MOSH_CONNECT_WORDS.indices.all { offset ->
        output[start + offset] == MOSH_CONNECT_WORDS[offset]
    }
}

private fun ByteArray.indexOf(value: Byte, startIndex: Int): Int {
    for (index in startIndex until size) if (this[index] == value) return index
    return -1
}

private fun Byte.isAsciiDigit(): Boolean = (toInt() and 0xff) in '0'.code..'9'.code

private fun isMoshKeyByte(value: Byte): Boolean = (value.toInt() and 0xff).let { unsigned ->
    unsigned in 'A'.code..'Z'.code ||
        unsigned in 'a'.code..'z'.code ||
        unsigned in '0'.code..'9'.code ||
        unsigned == '/'.code || unsigned == '+'.code
}

private fun isOfficialTrailingWhitespace(value: Byte): Boolean = when (value.toInt() and 0xff) {
    0x09, 0x0b, 0x0c, 0x0d, 0x20 -> true
    else -> false
}

private fun InetAddress.toBootstrapResult(
    port: Int,
    key: ByteArray,
    sshSideChannel: MoshSshExecSession,
    initialTmuxSessionId: String?,
    startedInTmux: Boolean,
    tmuxExecutable: String?,
    tmuxSessionsBeforeStart: Set<String>,
    cachedTmuxPaneCapture: TmuxPaneCapture?,
): MoshBootstrapResult {
    val family = when (this) {
        is Inet4Address -> MoshAddressFamily.IPV4
        is Inet6Address -> MoshAddressFamily.IPV6
        else -> throw MoshBootstrapException(
            MoshBootstrapFailure.ADDRESS_RESOLUTION,
            "Mosh server address family is unsupported.",
        )
    }
    return MoshBootstrapResult(
        family,
        address,
        port,
        key,
        sshSideChannel,
        initialTmuxSessionId,
        startedInTmux,
        tmuxExecutable,
        tmuxSessionsBeforeStart,
        cachedTmuxPaneCapture,
    )
}

private fun outputLimitExceeded(): Nothing = throw MoshBootstrapException(
    MoshBootstrapFailure.OUTPUT_LIMIT,
    "Mosh server startup output exceeded its safety limit.",
)

private fun moshStartupTimedOut(): Nothing = throw MoshBootstrapException(
    MoshBootstrapFailure.TIMEOUT,
    "Mosh server startup timed out.",
)

private fun cancelledFailure(cause: Throwable? = null) = MoshBootstrapException(
    MoshBootstrapFailure.CANCELLED,
    "Mosh server startup was cancelled.",
    cause,
)

private val MOSH_CONNECT_WORDS = "MOSH CONNECT".encodeToByteArray()
private val MOSH_CONNECT_PREFIX = "MOSH CONNECT ".encodeToByteArray()
private val MOSH_LOCALE = Regex("[A-Za-z0-9_.@+-]+(?:UTF-?8)[A-Za-z0-9_.@+-]*", RegexOption.IGNORE_CASE)

private const val DEFAULT_MOSH_SERVER_COMMAND = "mosh-server"
private val TMUX_MOSH_EXEC_LIMITS = MoshExecLimits(
    channelConnectTimeoutMillis = 5_000,
    totalTimeoutMillis = 5_000,
    maximumOutputBytes = 64 * 1024,
    maximumLineBytes = 2 * 1024,
    maximumLines = 256,
)
private val TMUX_HISTORY_MOSH_EXEC_LIMITS = MoshExecLimits(
    channelConnectTimeoutMillis = 10_000,
    totalTimeoutMillis = 30_000,
    maximumOutputBytes = TmuxPaneCapture.MAX_CAPTURE_BYTES,
    maximumLineBytes = 256 * 1024,
    maximumLines = ModelLimits.MAX_SCROLLBACK_LINES + 1,
)
internal const val DEFAULT_MOSH_LOCALE = "en_US.UTF-8"
private const val MAX_MOSH_LOCALE_LENGTH = 64
private const val MAX_BUILT_MOSH_COMMAND_LENGTH = 1_024
private const val MOSH_KEY_BYTES = 22
private const val EXEC_READ_BUFFER_BYTES = 1_024
private const val MOSH_SSH_CONNECT_TIMEOUT_MILLIS = 15_000
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NEWLINE_BYTE: Byte = 0x0a
private const val SPACE_BYTE: Byte = 0x20
