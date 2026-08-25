package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.data.repository.ActiveRecentEndpointIdentitySeed
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityBackfillResult
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityGenerationChangedException
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityPersistence
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityKeyState
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityProvider
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityUnavailableException
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalRemoteClipboardDecision
import com.yanjiyu.terminalspike.terminal.decideRemoteClipboardRequest
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Immutable, non-secret process-session metadata consumed by the UI and notification service.
 * Endpoint details deliberately stay behind [SshSessionRepository.connectionSeedFor] so observers
 * such as notification code can only see the caller-provided privacy-safe [workspaceName].
 */
internal data class SshSessionSnapshot(
    val id: Long,
    val title: String,
    val connectionState: ConnectionState,
    val workspaceName: String = title,
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val terminalTitle: String? = null,
    val lastActivityAtEpochMillis: Long = 0L,
    val recentSessionId: String? = null,
    /** Device-local opaque HMAC used only to suppress matching Recent presentation rows. */
    val endpointIdentityToken: String? = null,
    val sourceProfileId: Long? = null,
    /** Null follows the app default; a non-null value is the saved host's explicit override. */
    val terminalProfileId: String? = null,
    /** Null follows the app default; a non-null value is the saved host's explicit override. */
    val keyboardProfileId: String? = null,
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
)

/** The non-secret endpoint/options needed to ask for authentication and start a fresh transport. */
internal data class RemoteSessionConnectionSeed(
    val host: String,
    val port: Int,
    val username: String,
    val sourceProfileId: Long?,
    val protocol: ConnectionProtocol,
    val moshPort: Int? = null,
    val moshPortRange: MoshPortRange? = null,
    val moshServerCommand: String? = null,
    val moshLocale: String? = null,
)

/**
 * A short-lived, secret-bearing transport request. It is never published in a flow or persisted.
 * The repository clears its mutable authentication arrays on every success, cancellation, or error
 * path. Stored-secret loader functions remain the credential store's scoped responsibility.
 */
internal sealed interface RemoteSessionConnectionRequest {
    val protocol: ConnectionProtocol
    val sshConfig: SshConnectionConfig

    data class Ssh(
        override val sshConfig: SshConnectionConfig,
    ) : RemoteSessionConnectionRequest {
        override val protocol: ConnectionProtocol = ConnectionProtocol.SSH
    }

    data class Mosh(
        val bootstrapRequest: MoshBootstrapRequest,
    ) : RemoteSessionConnectionRequest {
        override val protocol: ConnectionProtocol = ConnectionProtocol.MOSH
        override val sshConfig: SshConnectionConfig get() = bootstrapRequest.ssh
    }
}

internal data class RemoteSessionStartRequest(
    val title: String,
    val workspaceName: String,
    val connection: RemoteSessionConnectionRequest,
    val sourceProfileId: Long? = null,
    val hostProfileId: String? = null,
    /** Saved-host overrides remain attached to the process session across UI recreation. */
    val terminalProfileId: String? = null,
    val keyboardProfileId: String? = null,
    val replacementSessionId: Long? = null,
    val terminalConfiguration: RemoteSessionTerminalConfiguration =
        RemoteSessionTerminalConfiguration(),
    val reliabilityPolicy: RemoteSessionReliabilityPolicy = RemoteSessionReliabilityPolicy(),
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
    /** Primarily a deterministic test/import seam; normal callers let the repository create it. */
    val recentSessionId: String? = null,
) {
    init {
        require(replacementSessionId == null || replacementSessionId > 0L)
        require(
            connection.protocol == ConnectionProtocol.MOSH ||
                moshFallbackPolicy == MoshFallbackPolicy.NEVER,
        )
        recentSessionId?.let(::requireCanonicalSessionUuid)
    }
}

/** Non-secret reliability policy captured with an application-owned remote session. */
internal data class RemoteSessionReliabilityPolicy(
    val reconnectEnabled: Boolean = false,
    val reconnectMaxAttempts: Int = DEFAULT_RECONNECT_MAX_ATTEMPTS,
    val initialRetryDelayMillis: Long = DEFAULT_RECONNECT_INITIAL_DELAY_MILLIS,
    val maximumRetryDelayMillis: Long = DEFAULT_RECONNECT_MAXIMUM_DELAY_MILLIS,
) {
    init {
        require(reconnectMaxAttempts in 0..MAX_RECONNECT_ATTEMPTS)
        require(initialRetryDelayMillis > 0L)
        require(maximumRetryDelayMillis >= initialRetryDelayMillis)
    }
}

/** Pure retry decision used by the repository and deterministic JVM tests. */
internal data class ReconnectPlan(
    val policy: RemoteSessionReliabilityPolicy,
) {
    fun next(
        completedAttempts: Int,
        networkAvailable: Boolean,
        intentionallyDisconnected: Boolean,
    ): ReconnectStep {
        if (intentionallyDisconnected) return ReconnectStep.Cancelled
        if (!policy.reconnectEnabled || completedAttempts >= policy.reconnectMaxAttempts) {
            return ReconnectStep.Exhausted
        }
        val attempt = completedAttempts + 1
        if (!networkAvailable) return ReconnectStep.WaitForNetwork(attempt)
        var delayMillis = policy.initialRetryDelayMillis
        repeat((attempt - 1).coerceAtMost(MAX_RECONNECT_EXPONENT)) {
            delayMillis = if (delayMillis >= policy.maximumRetryDelayMillis / 2L) {
                policy.maximumRetryDelayMillis
            } else {
                delayMillis * 2L
            }
        }
        return ReconnectStep.RetryAfter(
            attempt = attempt,
            delayMillis = delayMillis.coerceAtMost(policy.maximumRetryDelayMillis),
        )
    }
}

internal sealed interface ReconnectStep {
    data class WaitForNetwork(val attempt: Int) : ReconnectStep

    data class RetryAfter(val attempt: Int, val delayMillis: Long) : ReconnectStep

    data object Exhausted : ReconnectStep

    data object Cancelled : ReconnectStep
}

internal data class RemoteSessionTerminalConfiguration(
    val scrollbackLines: Int = DEFAULT_REMOTE_SCROLLBACK_LINES,
    val rendererProfile: TerminalRendererProfile = TerminalRendererProfile(),
    val remoteClipboardMode: RemoteClipboardMode = RemoteClipboardMode.ASK,
) {
    init {
        // TerminalBuffer stores the live screen as well as retained history and requires at least
        // one line. A domain profile value of zero is normalized to one at the UI/runtime seam.
        require(scrollbackLines in 1..ModelLimits.MAX_SCROLLBACK_LINES)
    }
}

/**
 * A bounded, one-shot OSC 52 prompt emitted only while its originating transport is live.
 *
 * The public event metadata intentionally contains neither endpoint information nor clipboard
 * text. The UTF-8 payload stays private, is cleared on Allow or Deny, and is not replayed to a
 * later UI collector. Only the repository that issued the event can consume its payload.
 */
internal class RemoteClipboardWriteRequestEvent internal constructor(
    val sessionId: Long,
    val requestId: Long,
    private val runtimeToken: Long,
    private val transportGeneration: Long,
    private val ownerToken: Any,
    text: String,
) {
    private var payload: ByteArray? = text.toByteArray(Charsets.UTF_8)

    internal fun belongsTo(
        ownerToken: Any,
        runtimeToken: Long,
        transportGeneration: Long,
    ): Boolean = synchronized(this) {
        payload != null && this.ownerToken === ownerToken && this.runtimeToken == runtimeToken &&
            this.transportGeneration == transportGeneration
    }

    internal fun consume(ownerToken: Any): String? = synchronized(this) {
        if (this.ownerToken !== ownerToken) return@synchronized null
        val owned = payload ?: return@synchronized null
        payload = null
        try {
            owned.toString(Charsets.UTF_8)
        } finally {
            owned.fill(0)
        }
    }

    internal fun discard() {
        synchronized(this) {
            payload?.fill(0)
            payload = null
        }
    }
}

internal enum class SessionNotificationVisibility {
    VISIBLE,
    LIMITED_BY_PERMISSION,
}

internal sealed interface SessionForegroundStartResult {
    data class Started(
        val notificationVisibility: SessionNotificationVisibility,
    ) : SessionForegroundStartResult

    data object Unavailable : SessionForegroundStartResult
}

internal fun interface SessionForegroundStarter {
    fun startFromVisibleUserAction(): SessionForegroundStartResult
}

/** Compatibility seam for the original SSH-only repository tests and callers. */
internal fun interface SshConnectionFactory {
    fun create(config: SshConnectionConfig): Connection
}

internal fun interface RemoteSessionConnectionFactory {
    fun create(request: RemoteSessionConnectionRequest): Connection
}

internal fun interface RecentSessionWriter {
    suspend fun upsert(session: RecentSession)
}

internal data class RecentEndpointIdentityStartupBackfill(
    val candidatesScanned: Int,
    val tokensStored: Int,
    val batchesAttempted: Int,
    val complete: Boolean,
)

internal sealed interface StartSshSessionResult {
    data class Started(
        val sessionId: Long,
        val notificationVisibility: SessionNotificationVisibility,
    ) : StartSshSessionResult

    data object SessionLimitReached : StartSshSessionResult

    data object ReplacementUnavailable : StartSshSessionResult

    data object ForegroundServiceUnavailable : StartSshSessionResult

    /** Startup was explicitly terminated while a foreground/transport step was in flight. */
    data object Cancelled : StartSshSessionResult
}

/** Result of a tab double-tap duplicate request. No confirmation UI is involved. */
internal sealed interface DuplicateSshSessionResult {
    data class Started(
        val sessionId: Long,
        val notificationVisibility: SessionNotificationVisibility,
    ) : DuplicateSshSessionResult

    /** One-shot authentication was deliberately not retained and must be entered again. */
    data object AuthenticationRequired : DuplicateSshSessionResult

    data object SessionLimitReached : DuplicateSshSessionResult

    data object Unavailable : DuplicateSshSessionResult
}

/**
 * Application-process owner for live SSH and Mosh state. UI clients only observe and issue
 * commands; their lifecycle never owns a transport, parser, controller, or connection coroutine.
 * Reviewable disconnected tabs remain here until explicitly closed.
 */
internal class SshSessionRepository(
    private val applicationScope: CoroutineScope,
    private val foregroundStarter: SessionForegroundStarter,
    private val connectionFactory: RemoteSessionConnectionFactory,
    private val recentSessionWriter: RecentSessionWriter = NoOpRecentSessionWriter,
    private val recentEndpointIdentityProvider: RecentEndpointIdentityProvider? = null,
    private val recentEndpointIdentityPersistence: RecentEndpointIdentityPersistence? = null,
    private val onRecentSessionWriteFailure: (Throwable) -> Unit = {},
    private val terminalFactory: SshSessionTerminalFactory = DefaultSshSessionTerminalFactory,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val recentSessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val networkAvailability: NetworkAvailability = AlwaysOnlineNetworkAvailability,
    private val reconnectStableWindowMillis: Long = DEFAULT_RECONNECT_STABLE_WINDOW_MILLIS,
) {
    /** Keeps the existing SSH-only construction surface source-compatible during the UI cutover. */
    constructor(
        applicationScope: CoroutineScope,
        foregroundStarter: SessionForegroundStarter,
        connectionFactory: SshConnectionFactory,
        terminalFactory: SshSessionTerminalFactory = DefaultSshSessionTerminalFactory,
    ) : this(
        applicationScope = applicationScope,
        foregroundStarter = foregroundStarter,
        connectionFactory = RemoteSessionConnectionFactory { request ->
            require(request is RemoteSessionConnectionRequest.Ssh) {
                "The compatibility connection factory only supports SSH."
            }
            connectionFactory.create(request.sshConfig)
        },
        terminalFactory = terminalFactory,
    )

    private val lock = Any()
    private val recentSessionWriteMutex = Mutex()
    private var recentEndpointIdentityResetRequired = false
    private val runtimes = linkedMapOf<Long, SshSessionRuntime>()
    private val pendingStarts = mutableMapOf<Long, PendingSessionStart>()
    private val _sessions = MutableStateFlow<List<SshSessionSnapshot>>(emptyList())
    val sessions: StateFlow<List<SshSessionSnapshot>> = _sessions.asStateFlow()
    private val remoteClipboardEventOwner = Any()
    private val _remoteClipboardRequests = MutableSharedFlow<RemoteClipboardWriteRequestEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val remoteClipboardRequests: SharedFlow<RemoteClipboardWriteRequestEvent> =
        _remoteClipboardRequests.asSharedFlow()
    private var nextSessionId = 1L
    private var nextRuntimeToken = 1L
    private var nextRemoteClipboardRequestId = 1L

    init {
        require(reconnectStableWindowMillis > 0L) {
            "Reconnect stability window must be positive."
        }
        require(
            (recentEndpointIdentityProvider == null) == (recentEndpointIdentityPersistence == null),
        ) { "Recent endpoint identity provider and persistence must be configured together." }
        applicationScope.launch {
            networkAvailability.state.collect(::dispatchNetworkHint)
        }
    }

    /** Compatibility overload for callers that have not yet moved to the generic request. */
    fun startUserInitiatedSession(
        title: String,
        config: SshConnectionConfig,
    ): StartSshSessionResult = startUserInitiatedSession(
        RemoteSessionStartRequest(
            title = title,
            workspaceName = title,
            connection = RemoteSessionConnectionRequest.Ssh(config),
        ),
    )

    fun startUserInitiatedSession(request: RemoteSessionStartRequest): StartSshSessionResult {
        val prepared = when (val preparation = prepareStart(request)) {
            is SessionPreparation.Ready -> preparation.pending
            SessionPreparation.LimitReached -> {
                request.connection.clearAuthenticationSecrets()
                return StartSshSessionResult.SessionLimitReached
            }
            SessionPreparation.ReplacementUnavailable -> {
                request.connection.clearAuthenticationSecrets()
                return StartSshSessionResult.ReplacementUnavailable
            }
            SessionPreparation.RuntimeUnavailable -> {
                request.connection.clearAuthenticationSecrets()
                return StartSshSessionResult.ForegroundServiceUnavailable
            }
        }
        val runtime = prepared.runtime

        val foreground = runCatching {
            foregroundStarter.startFromVisibleUserAction()
        }.getOrDefault(SessionForegroundStartResult.Unavailable)
        if (foreground !is SessionForegroundStartResult.Started) {
            val abandoned = abandonPendingStart(prepared)
            runtime.clearAuthenticationSecrets()
            return if (abandoned) {
                StartSshSessionResult.ForegroundServiceUnavailable
            } else {
                StartSshSessionResult.Cancelled
            }
        }
        if (!isPending(prepared)) {
            runtime.clearAuthenticationSecrets()
            return StartSshSessionResult.Cancelled
        }

        val connection = runCatching { connectionFactory.create(request.connection) }.getOrElse {
            val abandoned = abandonPendingStart(prepared)
            runtime.clearAuthenticationSecrets()
            return if (abandoned) {
                StartSshSessionResult.ForegroundServiceUnavailable
            } else {
                StartSshSessionResult.Cancelled
            }
        }
        if (!isPending(prepared)) {
            connection.close()
            runtime.clearAuthenticationSecrets()
            return StartSshSessionResult.Cancelled
        }
        val attached = runCatching {
            synchronized(runtime.processingLock) {
                runtime.terminal.attach(
                    connection = connection,
                    onInputAccepted = { recordAcceptedOutboundSessionActivity(runtime) },
                )
            }
        }.isSuccess
        if (!attached) {
            connection.close()
            val abandoned = abandonPendingStart(prepared)
            runtime.clearAuthenticationSecrets()
            if (!abandoned) synchronized(runtime.processingLock) { runtime.terminal.detach() }
            return if (abandoned) {
                StartSshSessionResult.ForegroundServiceUnavailable
            } else {
                StartSshSessionResult.Cancelled
            }
        }

        val retired = commitPendingStart(prepared, connection)
        if (retired === PendingCommitCancelled) {
            connection.close()
            synchronized(runtime.processingLock) { runtime.terminal.detach() }
            runtime.clearAuthenticationSecrets()
            return StartSshSessionResult.Cancelled
        }
        (retired as? SshSessionRuntime)?.let(::retireReplacedRuntime)
        queueRecentSessionPersistence(runtime, ConnectionState.Connecting)

        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            connection.connect(
                columns = runtime.terminal.columns,
                rows = runtime.terminal.rows,
                onBytes = { bytes -> acceptTransportBytes(runtime, connection, bytes) },
                onState = { state -> updateState(runtime, connection, state) },
            )
        }
        job.invokeOnCompletion { error ->
            runtime.clearAuthenticationSecrets()
            if (error != null && error !is kotlinx.coroutines.CancellationException) {
                updateState(runtime, connection, ConnectionState.Failed(TRANSPORT_ENDED_MESSAGE))
            }
        }
        val jobRegistered = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) {
                false
            } else {
                runtime.job = job
                true
            }
        }
        if (!jobRegistered) {
            job.cancel()
            connection.close()
            synchronized(runtime.processingLock) { runtime.terminal.detach() }
            runtime.clearAuthenticationSecrets()
            return StartSshSessionResult.Cancelled
        }
        job.start()

        return StartSshSessionResult.Started(
            sessionId = runtime.id,
            notificationVisibility = foreground.notificationVisibility,
        )
    }

    /**
     * Starts an independent copy immediately when the active session can safely reload its
     * authentication. Transient passwords/passphrases are never retained merely to avoid a prompt.
     */
    fun duplicateUserInitiatedSession(sessionId: Long): DuplicateSshSessionResult {
        val request = synchronized(lock) {
            val source = runtimes[sessionId]
                ?.takeUnless(SshSessionRuntime::terminated)
                ?: return DuplicateSshSessionResult.Unavailable
            val requestFactory = source.reconnectRequestFactory
                ?: return DuplicateSshSessionResult.AuthenticationRequired
            val connection = runCatching(requestFactory).getOrElse {
                return DuplicateSshSessionResult.Unavailable
            }
            RemoteSessionStartRequest(
                title = source.title,
                workspaceName = source.workspaceName,
                connection = connection,
                sourceProfileId = source.sourceProfileId,
                hostProfileId = source.hostProfileId,
                terminalProfileId = source.terminalProfileId,
                keyboardProfileId = source.keyboardProfileId,
                terminalConfiguration = source.terminalConfiguration,
                reliabilityPolicy = source.reliabilityPolicy,
                moshFallbackPolicy = source.moshFallbackPolicy,
            )
        }
        return when (val result = startUserInitiatedSession(request)) {
            is StartSshSessionResult.Started -> DuplicateSshSessionResult.Started(
                sessionId = result.sessionId,
                notificationVisibility = result.notificationVisibility,
            )
            StartSshSessionResult.SessionLimitReached ->
                DuplicateSshSessionResult.SessionLimitReached
            StartSshSessionResult.ReplacementUnavailable,
            StartSshSessionResult.ForegroundServiceUnavailable,
            StartSshSessionResult.Cancelled,
            -> DuplicateSshSessionResult.Unavailable
        }
    }

    fun controllerFor(sessionId: Long): TerminalController? = synchronized(lock) {
        pendingStarts[sessionId]?.runtime?.terminal?.controller
            ?: runtimes[sessionId]?.terminal?.controller
    }

    /** Uploads through the exact live session's authenticated SSH side channel. */
    fun uploadPastedImage(
        sessionId: Long,
        fileName: String,
        source: InputStream,
    ): Result<String> = runCatching {
        val uploader = synchronized(lock) {
            val runtime = runtimes[sessionId]
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.takeIf { it.connectionState is ConnectionState.Connected }
                ?: error("The terminal session is not connected.")
            runtime.connection as? RemoteImageUploadConnection
                ?: error("Image paste requires a live SSH or Mosh session.")
        }
        source.use { input -> uploader.uploadPastedImage(fileName, input) }
    }

    fun connectionSeedFor(sessionId: Long): RemoteSessionConnectionSeed? = synchronized(lock) {
        pendingStarts[sessionId]?.runtime?.connectionSeed
            ?: runtimes[sessionId]?.connectionSeed
    }

    /** Returns the clipboard text once, and only while the exact originating transport is live. */
    fun consumeRemoteClipboardRequest(event: RemoteClipboardWriteRequestEvent): String? =
        synchronized(lock) {
            val runtime = runtimes[event.sessionId]
            if (
                runtime == null || runtime.terminated ||
                runtime.connectionState !is ConnectionState.Connected || runtime.connection == null ||
                !event.belongsTo(
                    ownerToken = remoteClipboardEventOwner,
                    runtimeToken = runtime.runtimeToken,
                    transportGeneration = runtime.transportGeneration,
                )
            ) {
                event.discard()
                null
            } else {
                event.consume(remoteClipboardEventOwner)
            }
        }

    /** Explicit Deny/stale-session path. This never reads or writes Android's clipboard. */
    fun discardRemoteClipboardRequest(event: RemoteClipboardWriteRequestEvent) {
        event.discard()
    }

    fun isRemoteClipboardRequestLive(event: RemoteClipboardWriteRequestEvent): Boolean =
        synchronized(lock) {
            val runtime = runtimes[event.sessionId]
            runtime != null && !runtime.terminated &&
                runtime.connectionState is ConnectionState.Connected && runtime.connection != null &&
                event.belongsTo(
                    ownerToken = remoteClipboardEventOwner,
                    runtimeToken = runtime.runtimeToken,
                    transportGeneration = runtime.transportGeneration,
                )
        }

    fun updateRendererProfile(profile: TerminalRendererProfile) {
        updateRendererProfiles(defaultProfile = profile, overridesById = emptyMap())
    }

    /**
     * Applies live renderer edits without replacing an explicit saved-host profile with the app
     * default. Session buffer capacity deliberately remains fixed for the lifetime of a terminal.
     */
    fun updateRendererProfiles(
        defaultProfile: TerminalRendererProfile,
        overridesById: Map<String, TerminalRendererProfile>,
    ) {
        val targets = synchronized(lock) {
            (runtimes.values.asSequence() + pendingStarts.values.asSequence().map { it.runtime })
                .distinct()
                .mapNotNull { runtime ->
                    runtime.terminal.controller?.let { controller ->
                        controller to (
                            runtime.terminalProfileId?.let(overridesById::get) ?: defaultProfile
                        )
                    }
                }
                .distinctBy { (controller, _) -> controller }
                .toList()
        }
        targets.forEach { (controller, rendererProfile) ->
            controller.updateRendererProfile(rendererProfile)
        }
    }

    fun answerHostIdentityPrompt(
        sessionId: Long,
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        synchronized(lock) {
            val pending = pendingStarts[sessionId]?.runtime
            (pending ?: runtimes[sessionId])
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }?.answerHostIdentityPrompt(promptToken, decision)
    }

    /** Transfers response ownership only to the exact current process-session transport. */
    fun answerKeyboardInteractiveChallenge(
        sessionId: Long,
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        val connection = synchronized(lock) {
            val pending = pendingStarts[sessionId]?.runtime
            (pending ?: runtimes[sessionId])
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }
        if (connection == null) {
            responses.forEach { it.fill('\u0000') }
        } else {
            connection.answerKeyboardInteractiveChallenge(challengeToken, responses)
        }
    }

    fun cancelKeyboardInteractiveChallenge(sessionId: Long, challengeToken: Long) {
        synchronized(lock) {
            val pending = pendingStarts[sessionId]?.runtime
            (pending ?: runtimes[sessionId])
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }?.cancelKeyboardInteractiveChallenge(challengeToken)
    }

    fun answerTmuxSessionPrompt(sessionId: Long, promptToken: Long, tmuxSessionId: String?) {
        synchronized(lock) {
            runtimes[sessionId]
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }?.answerTmuxSessionPrompt(promptToken, tmuxSessionId)
    }

    fun deleteTmuxSession(sessionId: Long, promptToken: Long, tmuxSessionId: String) {
        synchronized(lock) {
            runtimes[sessionId]
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }?.deleteTmuxSession(promptToken, tmuxSessionId)
    }

    fun queryTmuxSessionCatalog(
        sessionId: Long,
        includePreviews: Boolean = false,
    ): TmuxSessionCatalog = synchronized(lock) {
        runtimes[sessionId]
            ?.takeUnless(SshSessionRuntime::terminated)
            ?.connection
    }?.queryTmuxSessionCatalog(includePreviews) ?: TmuxSessionCatalog()

    fun terminateTmuxSession(sessionId: Long, tmuxSessionId: String): TmuxSessionCatalog =
        synchronized(lock) {
            runtimes[sessionId]
                ?.takeUnless(SshSessionRuntime::terminated)
                ?.connection
        }?.terminateTmuxSession(tmuxSessionId) ?: TmuxSessionCatalog(deleteFailed = true)

    fun switchTmuxSession(
        sessionId: Long,
        tmuxSessionId: String,
    ): Boolean = synchronized(lock) {
        runtimes[sessionId]
            ?.takeUnless(SshSessionRuntime::terminated)
            ?.connection
    }?.switchTmuxSession(tmuxSessionId) ?: false

    fun disconnect(sessionId: Long) {
        val termination = terminateOne(sessionId, ConnectionState.Disconnected) ?: return
        finishTermination(termination, clearTerminal = false)
    }

    fun close(sessionId: Long) {
        val removed = synchronized(lock) {
            val pending = pendingStarts.remove(sessionId)
            val current = runtimes.remove(sessionId)
            if (pending == null && current == null) return
            publishSessionsLocked()
            RemovedSession(pending, current)
        }
        removed.pending?.runtime?.clearAuthenticationSecrets()
        val pendingRuntime = removed.pending?.runtime
        val currentRuntime = removed.current
        if (pendingRuntime != null && pendingRuntime !== currentRuntime) {
            pendingRuntime.connectionState = ConnectionState.Disconnected
            pendingRuntime.activity.force(now())
            queueRecentSessionPersistence(pendingRuntime, ConnectionState.Disconnected)
            closeRuntime(pendingRuntime, clearTerminal = true)
        }
        currentRuntime?.let { runtime ->
            runtime.terminated = true
            runtime.connectionState = ConnectionState.Disconnected
            runtime.activity.force(now())
            queueRecentSessionPersistence(runtime, ConnectionState.Disconnected)
            closeRuntime(runtime, clearTerminal = true)
        }
    }

    fun disconnectAll() {
        terminateAll(ConnectionState.Disconnected)
    }

    fun failAllForServiceLoss(message: String) {
        terminateAll(ConnectionState.Failed(message))
    }

    fun requiresForegroundService(): Boolean = synchronized(lock) {
        visibleRuntimesLocked().any { runtime ->
            runtime.connectionState.requiresForegroundService()
        }
    }

    /**
     * Bounded post-cutover backfill. The same mutex orders backfill/key rotation with live Recent
     * writes, so a replaced Keystore generation cannot be mixed with a concurrently persisted row.
     */
    suspend fun backfillRecentEndpointIdentitiesAfterCutover(
        batchSize: Int = 256,
        maximumBatches: Int = 16,
    ): RecentEndpointIdentityStartupBackfill {
        val persistence = requireNotNull(recentEndpointIdentityPersistence) {
            "Recent endpoint identity persistence is not configured."
        }
        return recentSessionWriteMutex.withLock {
            // A prior live write/startup pass may have observed another Keystore generation.
            resetRecentEndpointIdentityGenerationIfNeeded()
            runRecentEndpointIdentityStartupBackfill(
                batchSize = batchSize,
                maximumBatches = maximumBatches,
                backfill = persistence::backfillSavedHosts,
                resetGeneration = {
                    synchronized(lock) { recentEndpointIdentityResetRequired = true }
                    resetRecentEndpointIdentityGenerationIfNeeded()
                },
            )
        }
    }

    private fun prepareStart(request: RemoteSessionStartRequest): SessionPreparation {
        return synchronized(lock) {
        val replacementId = request.replacementSessionId
        val replaced = if (replacementId == null) {
            null
        } else {
            val candidate = runtimes[replacementId]
                ?: return@synchronized SessionPreparation.ReplacementUnavailable
            if (!candidate.connectionState.isTerminal() || pendingStarts.containsKey(replacementId)) {
                return@synchronized SessionPreparation.ReplacementUnavailable
            }
            candidate
        }
        val terminal = runCatching { terminalFactory.create(request.terminalConfiguration) }.getOrNull()
            ?: return@synchronized SessionPreparation.RuntimeUnavailable
        val sessionId = replacementId ?: allocateSessionIdLocked()
        val startedAt = now()
        val recentId = runCatching {
            (request.recentSessionId ?: recentSessionIdFactory()).also(::requireCanonicalSessionUuid)
        }.getOrElse {
            terminal.stopAndClear()
            return@synchronized SessionPreparation.RuntimeUnavailable
        }
        // Resolve only after admission succeeds: a rejected start must never rotate the HMAC key
        // without scheduling the matching Room generation reset.
        val endpointIdentity = resolveRecentEndpointIdentity(request)
        val runtime = SshSessionRuntime(
            id = sessionId,
            runtimeToken = allocateRuntimeTokenLocked(),
            title = normalizeSessionLabel(request.title, request.connection.protocol),
            workspaceName = normalizeSessionLabel(request.workspaceName, request.connection.protocol),
            protocol = request.connection.protocol,
            recentSessionId = recentId,
            endpointIdentityToken = endpointIdentity.token,
            endpointIdentityUnavailable = endpointIdentity.unavailable,
            sourceProfileId = request.sourceProfileId,
            hostProfileId = request.hostProfileId,
            terminalProfileId = request.terminalProfileId,
            keyboardProfileId = request.keyboardProfileId,
            terminalConfiguration = request.terminalConfiguration,
            connectionSeed = request.toConnectionSeed(),
            reliabilityPolicy = request.reliabilityPolicy,
            moshFallbackPolicy = request.moshFallbackPolicy,
            sshFallbackRequestFactory = request.connection.sshFallbackRequestFactoryOrNull(),
            remoteClipboardMode = request.terminalConfiguration.remoteClipboardMode,
            reconnectRequestFactory = request.connection.reconnectRequestFactoryOrNull(),
            authenticationSecretClearer = { request.connection.clearAuthenticationSecrets() },
            startedAtEpochMillis = startedAt,
            activity = SessionActivityClock(startedAt, SESSION_ACTIVITY_PUBLISH_INTERVAL_MILLIS),
            terminal = terminal,
        )
        val pending = PendingSessionStart(
            runtime = runtime,
            replacedRuntime = replaced,
        )
        pendingStarts[sessionId] = pending
        if (replaced == null) runtimes[sessionId] = runtime
        publishSessionsLocked()
        SessionPreparation.Ready(pending)
        }
    }

    private fun resolveRecentEndpointIdentity(
        request: RemoteSessionStartRequest,
    ): ResolvedRecentEndpointIdentity {
        val provider = recentEndpointIdentityProvider ?: return ResolvedRecentEndpointIdentity()
        val ssh = request.connection.sshConfig
        return try {
            val token = provider.create(
                protocol = request.connection.protocol,
                host = ssh.host,
                port = ssh.port,
                username = ssh.username,
            )
            if (token.keyState != RecentEndpointIdentityKeyState.EXISTING) {
                synchronized(lock) { recentEndpointIdentityResetRequired = true }
            }
            ResolvedRecentEndpointIdentity(token = token.value)
        } catch (error: RecentEndpointIdentityUnavailableException) {
            runCatching { onRecentSessionWriteFailure(error) }
            ResolvedRecentEndpointIdentity(unavailable = true)
        } catch (error: Exception) {
            runCatching { onRecentSessionWriteFailure(RecentEndpointIdentityUnavailableException()) }
            ResolvedRecentEndpointIdentity(unavailable = true)
        }
    }

    /** Removes a failed internal start without retiring a still-reviewable reconnect target. */
    private fun abandonPendingStart(pending: PendingSessionStart): Boolean {
        val removed = synchronized(lock) {
            if (pendingStarts[pending.runtime.id] !== pending) return@synchronized false
            pendingStarts.remove(pending.runtime.id)
            if (pending.replacedRuntime == null && runtimes[pending.runtime.id] === pending.runtime) {
                runtimes.remove(pending.runtime.id)
            }
            publishSessionsLocked()
            true
        }
        if (removed) synchronized(pending.runtime.processingLock) {
            pending.runtime.terminal.stopAndClear()
        }
        return removed
    }

    /** Returns the retired runtime, null for a new tab, or [PendingCommitCancelled]. */
    private fun commitPendingStart(
        pending: PendingSessionStart,
        connection: Connection,
    ): Any? = synchronized(lock) {
        if (pendingStarts[pending.runtime.id] !== pending) return@synchronized PendingCommitCancelled
        val runtime = pending.runtime
        val replaced = pending.replacedRuntime
        if (replaced != null && runtimes[runtime.id] !== replaced) {
            pendingStarts.remove(runtime.id)
            publishSessionsLocked()
            return@synchronized PendingCommitCancelled
        }
        if (replaced == null && (runtimes[runtime.id] !== runtime || runtime.terminated)) {
            pendingStarts.remove(runtime.id)
            publishSessionsLocked()
            return@synchronized PendingCommitCancelled
        }
        runtime.connection = connection
        pendingStarts.remove(runtime.id)
        runtimes[runtime.id] = runtime
        replaced
    }

    private fun isPending(pending: PendingSessionStart): Boolean = synchronized(lock) {
        pendingStarts[pending.runtime.id] === pending
    }

    private fun terminateOne(
        sessionId: Long,
        terminalState: ConnectionState,
    ): SessionTermination? = synchronized(lock) {
        val pending = pendingStarts.remove(sessionId)
        val oldRuntime = pending?.replacedRuntime
        val target = pending?.runtime ?: runtimes[sessionId] ?: return@synchronized null
        if (pending != null && oldRuntime != null && runtimes[sessionId] === oldRuntime) {
            runtimes[sessionId] = target
        }
        if (target.terminated && pending == null) return@synchronized null
        target.terminated = true
        target.connectionState = terminalState
        target.activity.force(now())
        publishSessionsLocked()
        SessionTermination(
            target = target,
            pending = pending,
            retiredRuntime = oldRuntime?.takeIf { it !== target },
        )
    }

    private fun terminateAll(terminalState: ConnectionState) {
        val terminations = synchronized(lock) {
            val pending = pendingStarts.values.toList()
            pendingStarts.clear()
            val retired = mutableListOf<SshSessionRuntime>()
            pending.forEach { start ->
                start.replacedRuntime?.let { old ->
                    if (runtimes[start.runtime.id] === old) {
                        runtimes[start.runtime.id] = start.runtime
                        retired += old
                    }
                }
            }
            val now = now()
            val targets = runtimes.values.filterNot(SshSessionRuntime::terminated)
            targets.forEach { runtime ->
                runtime.terminated = true
                runtime.connectionState = terminalState
                runtime.activity.force(now)
            }
            publishSessionsLocked()
            TerminationBatch(pending, targets, retired)
        }
        terminations.pending.forEach { it.runtime.clearAuthenticationSecrets() }
        terminations.retired.forEach(::retireReplacedRuntime)
        terminations.targets.forEach { runtime ->
            queueRecentSessionPersistence(runtime, terminalState)
            closeRuntime(runtime, clearTerminal = false)
        }
    }

    private fun finishTermination(termination: SessionTermination, clearTerminal: Boolean) {
        termination.pending?.runtime?.clearAuthenticationSecrets()
        termination.retiredRuntime?.let(::retireReplacedRuntime)
        queueRecentSessionPersistence(termination.target, termination.target.connectionState)
        closeRuntime(termination.target, clearTerminal)
    }

    private fun retireReplacedRuntime(runtime: SshSessionRuntime) {
        runtime.terminated = true
        runtime.activity.force(now())
        queueRecentSessionPersistence(runtime, runtime.connectionState)
        closeRuntime(runtime, clearTerminal = true)
    }

    private fun closeRuntime(runtime: SshSessionRuntime, clearTerminal: Boolean) {
        runtime.clearAuthenticationSecrets()
        runtime.reconnectJob?.cancel()
        runtime.reconnectJob = null
        runtime.reconnectStabilityJob?.cancel()
        runtime.reconnectStabilityJob = null
        synchronized(runtime.processingLock) {
            runtime.terminal.detach()
            if (runtime.transportClosed.compareAndSet(false, true)) {
                runtime.connection?.cancelPendingPrompts()
                runtime.connection?.close()
                runtime.job?.cancel()
                runtime.job = null
            }
            if (clearTerminal) runtime.terminal.stopAndClear()
        }
    }

    private fun acceptTransportBytes(
        runtime: SshSessionRuntime,
        connection: Connection,
        bytes: ByteArray,
    ) {
        var metadataChanged = false
        synchronized(runtime.processingLock) {
            val mayProcess = synchronized(lock) {
                runtimes[runtime.id] === runtime && !runtime.terminated &&
                    runtime.connection === connection
            }
            if (!mayProcess) return
            runtime.terminal.accept(
                bytes = bytes,
                sendResponse = { response ->
                    val mayRespond = synchronized(lock) {
                        runtimes[runtime.id] === runtime && !runtime.terminated &&
                            runtime.connection === connection
                    }
                    if (mayRespond) connection.send(response)
                },
                onRemoteClipboardRequest = { request ->
                    emitRemoteClipboardRequest(runtime, connection, request)
                },
            )
            val terminalTitle = sanitizeTerminalTitle(
                runtime.terminal.terminalTitle,
                runtime.connectionSeed,
            )
            synchronized(lock) {
                if (
                    runtimes[runtime.id] === runtime && !runtime.terminated &&
                    runtime.connection === connection
                ) {
                    if (runtime.terminalTitle != terminalTitle) {
                        runtime.terminalTitle = terminalTitle
                        metadataChanged = true
                    }
                    runtime.activity.record(now())?.let {
                        metadataChanged = true
                    }
                    if (metadataChanged) publishSessionsLocked()
                }
            }
        }
        if (metadataChanged) queueRecentSessionPersistence(runtime, runtime.connectionState)
    }

    private fun recordAcceptedOutboundSessionActivity(runtime: SshSessionRuntime) {
        val changed = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) return@synchronized false
            val published = runtime.activity.record(now()) != null
            if (published) publishSessionsLocked()
            published
        }
        if (changed) queueRecentSessionPersistence(runtime, runtime.connectionState)
    }

    /** Parser callback invoked before the terminal frame is handed to the renderer controller. */
    private fun emitRemoteClipboardRequest(
        runtime: SshSessionRuntime,
        connection: Connection,
        request: TerminalRemoteClipboardRequest,
    ) {
        val decision = decideRemoteClipboardRequest(runtime.remoteClipboardMode, request)
        if (decision is TerminalRemoteClipboardDecision.Ignore) return
        decision as TerminalRemoteClipboardDecision.Ask

        val event = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.connection !== connection ||
                runtime.connectionState !is ConnectionState.Connected
            ) {
                return@synchronized null
            }
            RemoteClipboardWriteRequestEvent(
                sessionId = runtime.id,
                requestId = allocateRemoteClipboardRequestIdLocked(),
                runtimeToken = runtime.runtimeToken,
                transportGeneration = runtime.transportGeneration,
                ownerToken = remoteClipboardEventOwner,
                text = decision.request.text,
            )
        } ?: return

        // With replay zero, a missing UI observer means the request must be dropped, not queued for
        // a later screen. A full one-event buffer also rejects the newer payload rather than
        // retaining an unbounded stream of remote clipboard writes.
        val delivered = _remoteClipboardRequests.subscriptionCount.value > 0 &&
            _remoteClipboardRequests.tryEmit(event)
        if (!delivered) event.discard()
    }

    /** Fans a changed non-identifying generation only to currently owned Mosh transports. */
    private fun dispatchNetworkHint(snapshot: NetworkAvailabilitySnapshot) {
        val targets = synchronized(lock) {
            runtimes.values.mapNotNull { runtime ->
                val connection = runtime.connection
                if (
                    runtime.terminated || runtime.protocol != ConnectionProtocol.MOSH ||
                    runtime.connectionState !is ConnectionState.Connected ||
                    connection !is MoshNetworkHintReceiver
                ) {
                    null
                } else {
                    runtime to connection
                }
            }
        }
        targets.forEach { (runtime, connection) ->
            dispatchNetworkHintToRuntime(runtime, connection, snapshot)
        }
    }

    private fun dispatchNetworkHintToRuntime(
        runtime: SshSessionRuntime,
        connection: Connection,
        snapshot: NetworkAvailabilitySnapshot,
    ) {
        val receiver = connection as? MoshNetworkHintReceiver ?: return
        val ownsGeneration = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.protocol != ConnectionProtocol.MOSH || runtime.connection !== connection ||
                runtime.connectionState !is ConnectionState.Connected ||
                runtime.lastMoshNetworkGeneration == snapshot.connectivityGeneration
            ) {
                false
            } else {
                runtime.lastMoshNetworkGeneration = snapshot.connectivityGeneration
                true
            }
        }
        if (ownsGeneration) receiver.updateNetworkHint(snapshot)
    }

    private fun updateState(
        runtime: SshSessionRuntime,
        connection: Connection,
        state: ConnectionState,
    ) {
        var closeLostTransport = false
        var scheduleReconnect = false
        var scheduleMoshFallback = false
        var scheduleStableAttemptReset = false
        val publishedState = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.connection !== connection
            ) {
                return@synchronized null
            }
            runtime.activity.force(now())
            if (state.isTerminal()) {
                runtime.reconnectStabilityJob?.cancel()
                runtime.reconnectStabilityJob = null
            }
            when {
                state is ConnectionState.Connected -> {
                    runtime.hasConnected = true
                    runtime.connectionState = state
                    scheduleStableAttemptReset = runtime.reconnectAttemptsStarted > 0
                }
                state is ConnectionState.Failed && runtime.canStartAutomaticMoshFallback(state) -> {
                    runtime.connectionState = ConnectionState.Reconnecting(
                        attempt = 1,
                        maxAttempts = 1,
                        waitingForNetwork = false,
                        retryDelayMillis = 0L,
                    )
                    runtime.connection = null
                    runtime.job = null
                    runtime.transportClosed.set(true)
                    runtime.moshFallbackStarted = true
                    closeLostTransport = true
                    scheduleMoshFallback = true
                }
                state.isTerminal() && runtime.canAutomaticallyReconnect(state) -> {
                    val next = ReconnectPlan(runtime.reliabilityPolicy).next(
                        completedAttempts = runtime.reconnectAttemptsStarted,
                        networkAvailable = networkAvailability.state.value.isOnline,
                        intentionallyDisconnected = runtime.terminated,
                    )
                    when (next) {
                        is ReconnectStep.WaitForNetwork -> {
                            runtime.connectionState = next.toConnectionState(runtime.reliabilityPolicy)
                            scheduleReconnect = true
                        }
                        is ReconnectStep.RetryAfter -> {
                            runtime.connectionState = next.toConnectionState(runtime.reliabilityPolicy)
                            scheduleReconnect = true
                        }
                        ReconnectStep.Cancelled -> runtime.connectionState = ConnectionState.Disconnected
                        ReconnectStep.Exhausted -> {
                            runtime.connectionState = exhaustedReconnectState()
                            runtime.terminated = true
                        }
                    }
                    runtime.connection = null
                    runtime.job = null
                    runtime.transportClosed.set(true)
                    closeLostTransport = true
                }
                state.isTerminal() -> {
                    runtime.connectionState = when {
                        state is ConnectionState.Failed &&
                            state.disposition == ConnectionFailureDisposition.TRANSIENT_TRANSPORT &&
                            runtime.hasConnected && runtime.reliabilityPolicy.reconnectEnabled &&
                            runtime.reconnectRequestFactory == null ->
                            ConnectionState.Failed(FRESH_AUTHENTICATION_REQUIRED_MESSAGE)
                        else -> state
                    }
                    runtime.terminated = true
                    runtime.connection = null
                    runtime.job = null
                    runtime.transportClosed.set(true)
                    closeLostTransport = true
                }
                state is ConnectionState.Connecting && runtime.reconnectAttemptsStarted > 0 -> {
                    // Keep the user-visible bounded retry state until this fresh transport connects.
                }
                else -> runtime.connectionState = state
            }
            publishSessionsLocked()
            runtime.connectionState
        } ?: return

        if (closeLostTransport) {
            synchronized(runtime.processingLock) { runtime.terminal.detach() }
            connection.cancelPendingPrompts()
            connection.close()
        }
        if (state is ConnectionState.Connected || state.isTerminal()) {
            runtime.clearAuthenticationSecrets()
        }
        queueRecentSessionPersistence(runtime, publishedState)
        if (state is ConnectionState.Connected) {
            dispatchNetworkHintToRuntime(runtime, connection, networkAvailability.state.value)
        }
        if (scheduleReconnect) launchReconnectWaiter(runtime)
        if (scheduleMoshFallback) launchAutomaticMoshFallback(runtime)
        if (scheduleStableAttemptReset) launchReconnectStabilityReset(runtime, connection)
    }

    /** A brief Connected flap does not replenish the bounded retry budget. */
    private fun launchReconnectStabilityReset(
        runtime: SshSessionRuntime,
        connection: Connection,
    ) {
        val generation = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.connection !== connection ||
                runtime.connectionState !is ConnectionState.Connected ||
                runtime.reconnectAttemptsStarted <= 0
            ) {
                return
            }
            runtime.transportGeneration
        }
        val stabilityJob = applicationScope.launch(start = CoroutineStart.LAZY) {
            delay(reconnectStableWindowMillis)
            synchronized(lock) {
                if (
                    runtimes[runtime.id] === runtime && !runtime.terminated &&
                    runtime.connection === connection &&
                    runtime.transportGeneration == generation &&
                    runtime.connectionState is ConnectionState.Connected &&
                    runtime.reconnectAttemptsStarted > 0
                ) {
                    runtime.reconnectAttemptsStarted = 0
                }
            }
        }
        stabilityJob.invokeOnCompletion {
            synchronized(lock) {
                if (runtime.reconnectStabilityJob === stabilityJob) {
                    runtime.reconnectStabilityJob = null
                }
            }
        }
        val registered = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.connection !== connection ||
                runtime.transportGeneration != generation ||
                runtime.connectionState !is ConnectionState.Connected ||
                runtime.reconnectAttemptsStarted <= 0
            ) {
                false
            } else {
                runtime.reconnectStabilityJob?.cancel()
                runtime.reconnectStabilityJob = stabilityJob
                true
            }
        }
        if (registered) stabilityJob.start() else stabilityJob.cancel()
    }

    private fun launchReconnectWaiter(runtime: SshSessionRuntime) {
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            runReconnectWaiter(runtime)
        }
        val registered = synchronized(lock) {
            if (
                runtimes[runtime.id] !== runtime || runtime.terminated ||
                runtime.reconnectJob != null
            ) {
                false
            } else {
                runtime.reconnectJob = job
                true
            }
        }
        if (registered) job.start() else job.cancel()
    }

    /** Opens at most one explicitly configured fresh SSH shell after a classified Mosh failure. */
    private fun launchAutomaticMoshFallback(runtime: SshSessionRuntime) {
        applicationScope.launch {
            val request = synchronized(lock) {
                if (
                    runtimes[runtime.id] !== runtime || runtime.terminated ||
                    !runtime.moshFallbackStarted || runtime.protocol != ConnectionProtocol.MOSH
                ) {
                    return@launch
                }
                runtime.sshFallbackRequestFactory
            }?.let { factory -> runCatching(factory).getOrNull() }
            if (request == null) {
                finishMoshFallbackFailure(runtime, FRESH_AUTHENTICATION_REQUIRED_MESSAGE)
                return@launch
            }
            val connection = runCatching { connectionFactory.create(request) }.getOrElse {
                request.clearAuthenticationSecrets()
                finishMoshFallbackFailure(runtime, "A fresh SSH shell could not be started.")
                return@launch
            }
            val attached = runCatching {
                synchronized(runtime.processingLock) {
                    val current = synchronized(lock) {
                        runtimes[runtime.id] === runtime && !runtime.terminated &&
                            runtime.connection == null && runtime.moshFallbackStarted
                    }
                    if (!current) return@runCatching false
                    runtime.terminal.attach(
                        connection = connection,
                        onInputAccepted = { recordAcceptedOutboundSessionActivity(runtime) },
                    )
                    true
                }
            }.getOrDefault(false)
            if (!attached) {
                connection.close()
                request.clearAuthenticationSecrets()
                return@launch
            }
            val job = applicationScope.launch(start = CoroutineStart.LAZY) {
                connection.connect(
                    columns = runtime.terminal.columns,
                    rows = runtime.terminal.rows,
                    onBytes = { bytes -> acceptTransportBytes(runtime, connection, bytes) },
                    onState = { state -> updateState(runtime, connection, state) },
                )
            }
            job.invokeOnCompletion { error ->
                request.clearAuthenticationSecrets()
                if (error != null && error !is kotlinx.coroutines.CancellationException) {
                    updateState(runtime, connection, ConnectionState.Failed(TRANSPORT_ENDED_MESSAGE))
                }
            }
            val registered = synchronized(lock) {
                if (
                    runtimes[runtime.id] !== runtime || runtime.terminated ||
                    runtime.connection != null || !runtime.moshFallbackStarted
                ) {
                    false
                } else {
                    runtime.protocol = ConnectionProtocol.SSH
                    runtime.connectionSeed = runtime.connectionSeed.copy(
                        protocol = ConnectionProtocol.SSH,
                        moshPort = null,
                        moshPortRange = null,
                        moshServerCommand = null,
                        moshLocale = null,
                    )
                    runtime.reconnectRequestFactory = request.reconnectRequestFactoryOrNull()
                    runtime.sshFallbackRequestFactory = null
                    runtime.transportGeneration = runtime.transportGeneration.nextPositiveGeneration()
                    runtime.lastMoshNetworkGeneration = -1L
                    runtime.connection = connection
                    runtime.job = job
                    runtime.transportClosed.set(false)
                    publishSessionsLocked()
                    true
                }
            }
            if (registered) {
                refreshRecentEndpointIdentity(runtime)
                job.start()
            } else {
                job.cancel()
                connection.close()
                request.clearAuthenticationSecrets()
                synchronized(runtime.processingLock) { runtime.terminal.detach() }
            }
        }
    }

    private fun finishMoshFallbackFailure(runtime: SshSessionRuntime, message: String) {
        val changed = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) return@synchronized false
            runtime.connectionState = ConnectionState.Failed(message)
            runtime.terminated = true
            runtime.activity.force(now())
            publishSessionsLocked()
            true
        }
        if (changed) queueRecentSessionPersistence(runtime, runtime.connectionState)
    }

    private fun refreshRecentEndpointIdentity(runtime: SshSessionRuntime) {
        val provider = recentEndpointIdentityProvider ?: return
        val seed = runtime.endpointIdentitySeed()
        val token = runCatching {
            provider.create(seed.protocol, seed.host, seed.port, seed.username)
        }.getOrElse {
            runCatching { onRecentSessionWriteFailure(RecentEndpointIdentityUnavailableException()) }
            return
        }
        if (token.keyState != RecentEndpointIdentityKeyState.EXISTING) {
            synchronized(lock) { recentEndpointIdentityResetRequired = true }
        }
        synchronized(lock) {
            if (runtimes[runtime.id] !== runtime) return
            runtime.endpointIdentityToken = token.value
            publishSessionsLocked()
        }
    }

    private suspend fun runReconnectWaiter(runtime: SshSessionRuntime) {
        val plan = ReconnectPlan(runtime.reliabilityPolicy)
        while (true) {
            val completedAttempts = synchronized(lock) {
                if (runtimes[runtime.id] !== runtime || runtime.terminated) return
                runtime.reconnectAttemptsStarted
            }
            when (
                val step = plan.next(
                    completedAttempts = completedAttempts,
                    networkAvailable = networkAvailability.state.value.isOnline,
                    intentionallyDisconnected = false,
                )
            ) {
                is ReconnectStep.WaitForNetwork -> {
                    publishReconnectStep(runtime, step.toConnectionState(runtime.reliabilityPolicy))
                    networkAvailability.state.filter { it.isOnline }.first()
                }
                is ReconnectStep.RetryAfter -> {
                    publishReconnectStep(runtime, step.toConnectionState(runtime.reliabilityPolicy))
                    delay(step.delayMillis)
                    if (!networkAvailability.state.value.isOnline) continue
                    if (startReconnectAttempt(runtime, step.attempt)) return
                }
                ReconnectStep.Cancelled -> return
                ReconnectStep.Exhausted -> {
                    finishReconnectExhausted(runtime)
                    return
                }
            }
        }
    }

    private fun publishReconnectStep(runtime: SshSessionRuntime, state: ConnectionState.Reconnecting) {
        val changed = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) return@synchronized false
            runtime.connectionState = state
            runtime.activity.force(now())
            publishSessionsLocked()
            true
        }
        if (changed) queueRecentSessionPersistence(runtime, state)
    }

    /** Returns true once a transport job owns the next outcome, false to consume another attempt. */
    private fun startReconnectAttempt(runtime: SshSessionRuntime, attempt: Int): Boolean {
        val requestFactory = runtime.reconnectRequestFactory ?: return false
        val mayStart = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) {
                false
            } else {
                runtime.reconnectAttemptsStarted = attempt
                true
            }
        }
        if (!mayStart) return true

        val request = runCatching(requestFactory).getOrElse { return false }
        val connection = runCatching { connectionFactory.create(request) }.getOrElse {
            request.clearAuthenticationSecrets()
            return false
        }
        val attached = runCatching {
            synchronized(runtime.processingLock) {
                val stillCurrent = synchronized(lock) {
                    runtimes[runtime.id] === runtime && !runtime.terminated &&
                        runtime.connection == null
                }
                if (!stillCurrent) return@runCatching false
                runtime.terminal.attach(
                    connection = connection,
                    onInputAccepted = { recordAcceptedOutboundSessionActivity(runtime) },
                )
                true
            }
        }.getOrDefault(false)
        if (!attached) {
            connection.close()
            request.clearAuthenticationSecrets()
            return synchronized(lock) { runtimes[runtime.id] !== runtime || runtime.terminated }
        }

        val transportJob = applicationScope.launch(start = CoroutineStart.LAZY) {
            connection.connect(
                columns = runtime.terminal.columns,
                rows = runtime.terminal.rows,
                onBytes = { bytes -> acceptTransportBytes(runtime, connection, bytes) },
                onState = { state -> updateState(runtime, connection, state) },
            )
        }
        transportJob.invokeOnCompletion { error ->
            request.clearAuthenticationSecrets()
            if (error != null && error !is kotlinx.coroutines.CancellationException) {
                updateState(runtime, connection, ConnectionState.Failed(TRANSPORT_ENDED_MESSAGE))
            }
        }
        val registered = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) {
                false
            } else {
                runtime.transportGeneration = runtime.transportGeneration.nextPositiveGeneration()
                runtime.lastMoshNetworkGeneration = -1L
                runtime.connection = connection
                runtime.job = transportJob
                runtime.reconnectJob = null
                runtime.transportClosed.set(false)
                true
            }
        }
        if (!registered) {
            transportJob.cancel()
            connection.close()
            request.clearAuthenticationSecrets()
            synchronized(runtime.processingLock) { runtime.terminal.detach() }
            return true
        }
        transportJob.start()
        return true
    }

    private fun finishReconnectExhausted(runtime: SshSessionRuntime) {
        val changed = synchronized(lock) {
            if (runtimes[runtime.id] !== runtime || runtime.terminated) return@synchronized false
            runtime.terminated = true
            runtime.connectionState = exhaustedReconnectState()
            runtime.reconnectJob = null
            runtime.activity.force(now())
            publishSessionsLocked()
            true
        }
        if (changed) queueRecentSessionPersistence(runtime, runtime.connectionState)
    }

    private fun queueRecentSessionPersistence(
        runtime: SshSessionRuntime,
        connectionState: ConnectionState,
    ) {
        if (runtime.endpointIdentityUnavailable && runtime.hostProfileId == null) return
        val record = runCatching {
            RecentSession(
                id = runtime.recentSessionId,
                hostProfileId = runtime.hostProfileId,
                hostDisplayName = runtime.workspaceName,
                protocol = runtime.protocol,
                state = connectionState.toPersistedSessionState(),
                startedAtEpochMillis = runtime.startedAtEpochMillis,
                lastActivityAtEpochMillis = runtime.activity.lastPublishedAtEpochMillis,
                endedAtEpochMillis = if (connectionState.isTerminal()) {
                    runtime.activity.lastPublishedAtEpochMillis
                } else {
                    null
                },
                terminalTitle = runtime.terminalTitle,
                endpointIdentityToken = runtime.endpointIdentityToken,
            )
        }.getOrElse { error ->
            runCatching { onRecentSessionWriteFailure(error) }
            return
        }
        synchronized(runtime.persistenceLock) {
            runtime.pendingPersistence = record
            if (runtime.persistenceJob?.isActive == true) return
            runtime.persistenceJob = applicationScope.launch {
                while (true) {
                    val pending = synchronized(runtime.persistenceLock) {
                        runtime.pendingPersistence?.also { runtime.pendingPersistence = null }
                            ?: run {
                                runtime.persistenceJob = null
                                return@launch
                            }
                    }
                    runCatching {
                        recentSessionWriteMutex.withLock {
                            resetRecentEndpointIdentityGenerationIfNeeded()
                            val currentToken = synchronized(lock) {
                                runtimes[runtime.id]
                                    ?.takeIf { it === runtime }
                                    ?.endpointIdentityToken
                                    ?: runtime.endpointIdentityToken
                            }
                            recentSessionWriter.upsert(
                                pending.copy(endpointIdentityToken = currentToken),
                            )
                        }
                    }
                        .onFailure { error ->
                            runCatching { onRecentSessionWriteFailure(error) }
                        }
                    delay(RECENT_SESSION_WRITE_COALESCE_MILLIS)
                }
            }
        }
    }

    private suspend fun resetRecentEndpointIdentityGenerationIfNeeded() {
        val persistence = recentEndpointIdentityPersistence ?: return
        val provider = recentEndpointIdentityProvider ?: return
        val seeds = synchronized(lock) {
            if (!recentEndpointIdentityResetRequired) return
            visibleRuntimesLocked().map { runtime -> runtime.endpointIdentitySeed() }
        }
        persistence.replaceKeyGeneration(seeds)
        var changedAgain: RecentEndpointIdentityKeyState? = null
        val refreshed = seeds.associate { seed ->
            val token = provider.create(
                protocol = seed.protocol,
                host = seed.host,
                port = seed.port,
                username = seed.username,
            )
            if (token.keyState != RecentEndpointIdentityKeyState.EXISTING) {
                synchronized(lock) { recentEndpointIdentityResetRequired = true }
                changedAgain = token.keyState
            }
            seed.sessionId to token.value
        }
        changedAgain?.let { throw RecentEndpointIdentityGenerationChangedException(it) }
        synchronized(lock) {
            visibleRuntimesLocked().forEach { runtime ->
                refreshed[runtime.recentSessionId]?.let { runtime.endpointIdentityToken = it }
            }
            recentEndpointIdentityResetRequired = false
            publishSessionsLocked()
        }
    }

    private fun allocateSessionIdLocked(): Long {
        while (nextSessionId == 0L || runtimes.containsKey(nextSessionId)) nextSessionId++
        return nextSessionId++
    }

    private fun allocateRuntimeTokenLocked(): Long {
        if (nextRuntimeToken <= 0L) nextRuntimeToken = 1L
        return nextRuntimeToken++
    }

    private fun allocateRemoteClipboardRequestIdLocked(): Long {
        if (nextRemoteClipboardRequestId <= 0L) nextRemoteClipboardRequestId = 1L
        return nextRemoteClipboardRequestId++
    }

    private fun visibleRuntimesLocked(): List<SshSessionRuntime> = runtimes.values.map { runtime ->
        pendingStarts[runtime.id]?.runtime ?: runtime
    }

    private fun publishSessionsLocked() {
        _sessions.value = visibleRuntimesLocked().map(SshSessionRuntime::snapshot)
    }

    private fun now(): Long = nowEpochMillis().coerceAtLeast(0L)

    private companion object {
        const val SESSION_ACTIVITY_PUBLISH_INTERVAL_MILLIS = 15_000L
        const val RECENT_SESSION_WRITE_COALESCE_MILLIS = 250L
    }
}

internal fun ConnectionState.requiresForegroundService(): Boolean = when (this) {
    ConnectionState.Connecting,
    ConnectionState.Connected,
    is ConnectionState.Reconnecting,
    is ConnectionState.AwaitingApproval,
    -> true

    ConnectionState.Disconnected,
    is ConnectionState.Failed,
    -> false
}

private fun ConnectionState.isTerminal(): Boolean = when (this) {
    ConnectionState.Disconnected,
    is ConnectionState.Failed,
    -> true

    ConnectionState.Connecting,
    ConnectionState.Connected,
    is ConnectionState.Reconnecting,
    is ConnectionState.AwaitingApproval,
    -> false
}

private fun ConnectionState.toPersistedSessionState(): SessionState = when (this) {
    ConnectionState.Connecting,
    is ConnectionState.AwaitingApproval,
    -> SessionState.CONNECTING

    ConnectionState.Connected -> SessionState.CONNECTED
    is ConnectionState.Reconnecting -> SessionState.RECONNECTING
    ConnectionState.Disconnected -> SessionState.DISCONNECTED
    is ConnectionState.Failed -> SessionState.FAILED
}

private fun SshSessionRuntime.canAutomaticallyReconnect(state: ConnectionState): Boolean =
    state is ConnectionState.Failed &&
        state.disposition == ConnectionFailureDisposition.TRANSIENT_TRANSPORT &&
        hasConnected && reliabilityPolicy.reconnectEnabled && reconnectRequestFactory != null

private fun ReconnectStep.WaitForNetwork.toConnectionState(
    policy: RemoteSessionReliabilityPolicy,
): ConnectionState.Reconnecting = ConnectionState.Reconnecting(
    attempt = attempt,
    maxAttempts = policy.reconnectMaxAttempts,
    waitingForNetwork = true,
    retryDelayMillis = null,
)

private fun ReconnectStep.RetryAfter.toConnectionState(
    policy: RemoteSessionReliabilityPolicy,
): ConnectionState.Reconnecting = ConnectionState.Reconnecting(
    attempt = attempt,
    maxAttempts = policy.reconnectMaxAttempts,
    waitingForNetwork = false,
    retryDelayMillis = delayMillis,
)

private fun exhaustedReconnectState(): ConnectionState.Failed =
    ConnectionState.Failed(RECONNECT_EXHAUSTED_MESSAGE)

private fun RemoteSessionConnectionRequest.clearAuthenticationSecrets() {
    sshConfig.clearAuthenticationSecrets()
}

/** Fresh SSH is possible only when authentication can be safely reloaded without retained text. */
private fun RemoteSessionConnectionRequest.sshFallbackRequestFactoryOrNull():
    (() -> RemoteSessionConnectionRequest.Ssh)? {
    if (this !is RemoteSessionConnectionRequest.Mosh) return null
    val freshSsh = sshConfig.reconnectConfigFactoryOrNull() ?: return null
    return { RemoteSessionConnectionRequest.Ssh(freshSsh()) }
}

/**
 * Returns a fresh-request factory only when authentication can be reloaded without retaining
 * plaintext. One-shot passwords and in-memory private-key passphrases deliberately require a
 * visible manual reconnect.
 */
private fun RemoteSessionConnectionRequest.reconnectRequestFactoryOrNull():
    (() -> RemoteSessionConnectionRequest)? {
    val freshSsh = sshConfig.reconnectConfigFactoryOrNull() ?: return null
    return when (this) {
        is RemoteSessionConnectionRequest.Ssh -> {
            { RemoteSessionConnectionRequest.Ssh(freshSsh()) }
        }
        is RemoteSessionConnectionRequest.Mosh -> {
            val serverCommand = bootstrapRequest.serverCommand
            val locale = bootstrapRequest.locale
            val udpPort = bootstrapRequest.udpPort
            val udpPortRange = bootstrapRequest.udpPortRange
            {
                RemoteSessionConnectionRequest.Mosh(
                    MoshBootstrapRequest(
                        ssh = freshSsh(),
                        serverCommand = serverCommand,
                        locale = locale,
                        udpPort = udpPort,
                        udpPortRange = udpPortRange,
                    ),
                )
            }
        }
    }
}

private fun SshConnectionConfig.reconnectConfigFactoryOrNull(): (() -> SshConnectionConfig)? {
    val authenticationFactory: (() -> SshAuthentication) = when (val auth = authentication) {
        is SshAuthentication.StoredPassword -> {
            val loadSecret = auth.loadSecret
            { SshAuthentication.StoredPassword(loadSecret) }
        }
        is SshAuthentication.PrivateKey -> {
            if (auth.passphrase != null) return null
            val identityName = auth.identityName
            val loadKey = auth.loadKey
            val loadPassphrase = auth.loadPassphrase
            {
                SshAuthentication.PrivateKey(
                    identityName = identityName,
                    loadKey = loadKey,
                    passphrase = null,
                    loadPassphrase = loadPassphrase,
                )
            }
        }
        is SshAuthentication.Password -> return null
        is SshAuthentication.KeyboardInteractive.SessionOnly -> return null
        is SshAuthentication.KeyboardInteractive.ReusableResponse -> {
            val loadResponse = auth.loadResponse
            { SshAuthentication.KeyboardInteractive.ReusableResponse(loadResponse) }
        }
    }
    val reconnectHost = host
    val reconnectPort = port
    val reconnectUsername = username
    val reconnectKeepalive = keepaliveIntervalSeconds
    val reconnectTerminalType = terminalType
    val reconnectStartupCommand = startupCommand
    val reconnectTmuxSelector = tmuxSessionSelectorEnabled
    return {
        SshConnectionConfig(
            host = reconnectHost,
            port = reconnectPort,
            username = reconnectUsername,
            authentication = authenticationFactory(),
            keepaliveIntervalSeconds = reconnectKeepalive,
            terminalType = reconnectTerminalType,
            startupCommand = reconnectStartupCommand,
            tmuxSessionSelectorEnabled = reconnectTmuxSelector,
        )
    }
}

private fun RemoteSessionStartRequest.toConnectionSeed(): RemoteSessionConnectionSeed {
    val ssh = connection.sshConfig
    return when (val transport = connection) {
        is RemoteSessionConnectionRequest.Ssh -> RemoteSessionConnectionSeed(
            host = ssh.host,
            port = ssh.port,
            username = ssh.username,
            sourceProfileId = sourceProfileId,
            protocol = ConnectionProtocol.SSH,
        )
        is RemoteSessionConnectionRequest.Mosh -> RemoteSessionConnectionSeed(
            host = ssh.host,
            port = ssh.port,
            username = ssh.username,
            sourceProfileId = sourceProfileId,
            protocol = ConnectionProtocol.MOSH,
            moshPort = transport.bootstrapRequest.udpPort,
            moshPortRange = transport.bootstrapRequest.udpPortRange,
            moshServerCommand = transport.bootstrapRequest.serverCommand,
            moshLocale = transport.bootstrapRequest.locale,
        )
    }
}

private fun normalizeSessionLabel(value: String, protocol: ConnectionProtocol): String {
    val normalized = value
        .filterNot { character ->
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
        }
        .trim()
        .replace(SESSION_WHITESPACE, " ")
        .take(MAX_SESSION_LABEL_LENGTH)
    if (normalized.isNotEmpty()) return normalized
    return if (protocol == ConnectionProtocol.MOSH) "Mosh session" else "SSH session"
}

private fun sanitizeTerminalTitle(
    raw: String?,
    seed: RemoteSessionConnectionSeed,
): String? {
    val normalized = raw
        ?.filterNot { character ->
            (character.isISOControl() && character !in "\t\n\r") ||
                Character.getType(character) == Character.FORMAT.toInt()
        }
        ?.trim()
        ?.replace(SESSION_WHITESPACE, " ")
        ?.take(MAX_TERMINAL_TITLE_LENGTH)
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val folded = normalized.lowercase(Locale.ROOT)
    val sensitiveValues = listOf(seed.host, seed.username)
        .map(String::trim)
        .filter { it.length >= MIN_REDACTED_FRAGMENT_LENGTH }
    return normalized.takeUnless { sensitiveValues.any { folded.contains(it.lowercase(Locale.ROOT)) } }
}

private fun requireCanonicalSessionUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Recent-session ID must be a canonical UUID."
    }
}

private fun Long.nextPositiveGeneration(): Long =
    if (this == Long.MAX_VALUE || this <= 0L) 1L else this + 1L

internal fun interface SshSessionTerminalFactory {
    fun create(): SshSessionTerminal

    fun create(configuration: RemoteSessionTerminalConfiguration): SshSessionTerminal = create()
}

internal interface SshSessionTerminal {
    val controller: TerminalController?
    val columns: Int
    val rows: Int
    val terminalTitle: String? get() = null

    fun attach(connection: Connection)

    fun attach(connection: Connection, onInputAccepted: () -> Unit) {
        attach(connection)
    }

    fun accept(bytes: ByteArray, sendResponse: (ByteArray) -> Unit)

    fun accept(
        bytes: ByteArray,
        sendResponse: (ByteArray) -> Unit,
        onRemoteClipboardRequest: (TerminalRemoteClipboardRequest) -> Unit,
    ) {
        accept(bytes, sendResponse)
    }

    fun detach() = Unit

    fun stopAndClear()
}

private data object DefaultSshSessionTerminalFactory : SshSessionTerminalFactory {
    override fun create(): SshSessionTerminal = DefaultSshSessionTerminal()

    override fun create(configuration: RemoteSessionTerminalConfiguration): SshSessionTerminal =
        DefaultSshSessionTerminal(configuration)
}

private class DefaultSshSessionTerminal(
    configuration: RemoteSessionTerminalConfiguration = RemoteSessionTerminalConfiguration(),
) : SshSessionTerminal {
    override val controller = TerminalController(
        TerminalBuffer(configuration.scrollbackLines),
    ).apply {
        updateRendererProfile(configuration.rendererProfile)
    }
    private val engine = VtTerminalEngine(
        columns = controller.terminalColumns,
        rows = controller.terminalRows,
    )

    @Volatile
    override var terminalTitle: String? = null
        private set

    override val columns: Int get() = controller.terminalColumns
    override val rows: Int get() = controller.terminalRows

    override fun attach(connection: Connection) {
        attach(connection, onInputAccepted = {})
    }

    override fun attach(connection: Connection, onInputAccepted: () -> Unit) {
        controller.setInputSink(
            sink = connection,
            onInputAccepted = onInputAccepted,
            onResize = { newColumns, newRows ->
                connection.resize(newColumns, newRows)
                controller.updateTerminalFrame(engine.resize(newColumns, newRows))
            },
        )
    }

    override fun accept(bytes: ByteArray, sendResponse: (ByteArray) -> Unit) {
        accept(bytes, sendResponse, onRemoteClipboardRequest = {})
    }

    override fun accept(
        bytes: ByteArray,
        sendResponse: (ByteArray) -> Unit,
        onRemoteClipboardRequest: (TerminalRemoteClipboardRequest) -> Unit,
    ) {
        val update = engine.accept(bytes)
        update.responses.forEach(sendResponse)
        terminalTitle = update.terminalTitle
        update.remoteClipboardRequests.forEach(onRemoteClipboardRequest)
        controller.updateTerminalFrame(update)
    }

    override fun detach() {
        controller.resetInputSink()
    }

    override fun stopAndClear() {
        detach()
        controller.stop()
        controller.clear()
    }
}

private class SshSessionRuntime(
    val id: Long,
    val runtimeToken: Long,
    val title: String,
    val workspaceName: String,
    var protocol: ConnectionProtocol,
    val recentSessionId: String,
    @Volatile var endpointIdentityToken: String?,
    val endpointIdentityUnavailable: Boolean,
    val sourceProfileId: Long?,
    val hostProfileId: String?,
    val terminalProfileId: String?,
    val keyboardProfileId: String?,
    val terminalConfiguration: RemoteSessionTerminalConfiguration,
    var connectionSeed: RemoteSessionConnectionSeed,
    val reliabilityPolicy: RemoteSessionReliabilityPolicy,
    val moshFallbackPolicy: MoshFallbackPolicy,
    val remoteClipboardMode: RemoteClipboardMode,
    var reconnectRequestFactory: (() -> RemoteSessionConnectionRequest)?,
    var sshFallbackRequestFactory: (() -> RemoteSessionConnectionRequest.Ssh)?,
    authenticationSecretClearer: () -> Unit,
    val startedAtEpochMillis: Long,
    val activity: SessionActivityClock,
    val terminal: SshSessionTerminal,
) {
    @Volatile
    var connectionState: ConnectionState = ConnectionState.Connecting

    @Volatile
    var terminalTitle: String? = null

    @Volatile
    var connection: Connection? = null

    @Volatile
    var job: Job? = null

    @Volatile
    var reconnectJob: Job? = null

    @Volatile
    var reconnectStabilityJob: Job? = null

    @Volatile
    var reconnectAttemptsStarted: Int = 0

    @Volatile
    var transportGeneration: Long = 1L

    @Volatile
    var lastMoshNetworkGeneration: Long = -1L

    @Volatile
    var moshFallbackStarted: Boolean = false

    @Volatile
    var hasConnected: Boolean = false

    /** Once true, neither startup nor a late connection callback may make this runtime live again. */
    @Volatile
    var terminated: Boolean = false

    /** Serializes parser/controller mutation with retirement without holding the repository lock. */
    val processingLock: Any = Any()
    val transportClosed = AtomicBoolean(false)
    private val authenticationLock: Any = Any()
    private var authenticationSecretClearer: (() -> Unit)? = authenticationSecretClearer
    val persistenceLock: Any = Any()
    var pendingPersistence: RecentSession? = null
    var persistenceJob: Job? = null

    fun clearAuthenticationSecrets() {
        val clearer = synchronized(authenticationLock) {
            authenticationSecretClearer.also { authenticationSecretClearer = null }
        }
        clearer?.invoke()
    }

    fun endpointIdentitySeed(): ActiveRecentEndpointIdentitySeed =
        ActiveRecentEndpointIdentitySeed(
            sessionId = recentSessionId,
            protocol = protocol,
            host = connectionSeed.host,
            port = connectionSeed.port,
            username = connectionSeed.username,
        )

    fun canStartAutomaticMoshFallback(failure: ConnectionState.Failed): Boolean =
        protocol == ConnectionProtocol.MOSH &&
            moshFallbackPolicy == MoshFallbackPolicy.AUTOMATIC &&
            failure.moshFallbackFailure != null &&
            !moshFallbackStarted && sshFallbackRequestFactory != null

    fun snapshot(): SshSessionSnapshot = SshSessionSnapshot(
        id = id,
        title = title,
        connectionState = connectionState,
        workspaceName = workspaceName,
        protocol = protocol,
        terminalTitle = terminalTitle,
        lastActivityAtEpochMillis = activity.lastPublishedAtEpochMillis,
        recentSessionId = recentSessionId,
        endpointIdentityToken = endpointIdentityToken,
        sourceProfileId = sourceProfileId,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = keyboardProfileId,
        moshFallbackPolicy = moshFallbackPolicy,
    )
}

private class SessionActivityClock(
    initialActivityAtEpochMillis: Long,
    private val publishIntervalMillis: Long,
) {
    @Volatile
    var lastPublishedAtEpochMillis: Long = initialActivityAtEpochMillis
        private set

    fun record(activityAtEpochMillis: Long): Long? = synchronized(this) {
        val bounded = activityAtEpochMillis.coerceAtLeast(lastPublishedAtEpochMillis)
        if (bounded - lastPublishedAtEpochMillis < publishIntervalMillis) {
            null
        } else {
            lastPublishedAtEpochMillis = bounded
            bounded
        }
    }

    fun force(activityAtEpochMillis: Long): Long = synchronized(this) {
        activityAtEpochMillis.coerceAtLeast(lastPublishedAtEpochMillis).also {
            lastPublishedAtEpochMillis = it
        }
    }
}

private data class PendingSessionStart(
    val runtime: SshSessionRuntime,
    val replacedRuntime: SshSessionRuntime?,
)

private sealed interface SessionPreparation {
    data class Ready(val pending: PendingSessionStart) : SessionPreparation

    data object LimitReached : SessionPreparation

    data object ReplacementUnavailable : SessionPreparation

    data object RuntimeUnavailable : SessionPreparation
}

private data class SessionTermination(
    val target: SshSessionRuntime,
    val pending: PendingSessionStart?,
    val retiredRuntime: SshSessionRuntime?,
)

private data class TerminationBatch(
    val pending: List<PendingSessionStart>,
    val targets: List<SshSessionRuntime>,
    val retired: List<SshSessionRuntime>,
)

private data class RemovedSession(
    val pending: PendingSessionStart?,
    val current: SshSessionRuntime?,
)

private data object PendingCommitCancelled

private data class ResolvedRecentEndpointIdentity(
    val token: String? = null,
    val unavailable: Boolean = false,
)

internal suspend fun runRecentEndpointIdentityStartupBackfill(
    batchSize: Int,
    maximumBatches: Int,
    backfill: suspend (Int) -> RecentEndpointIdentityBackfillResult,
    resetGeneration: suspend () -> Unit,
): RecentEndpointIdentityStartupBackfill {
    require(batchSize in 1..1_024) { "Recent endpoint backfill batch size is invalid." }
    require(maximumBatches in 1..64) { "Recent endpoint backfill bound is invalid." }
    var scanned = 0
    var stored = 0
    repeat(maximumBatches) { batchIndex ->
        val result = try {
            backfill(batchSize)
        } catch (_: RecentEndpointIdentityGenerationChangedException) {
            resetGeneration()
            return@repeat
        }
        scanned += result.candidatesScanned
        stored += result.tokensStored
        if (!result.hasMore) {
            return RecentEndpointIdentityStartupBackfill(
                candidatesScanned = scanned,
                tokensStored = stored,
                batchesAttempted = batchIndex + 1,
                complete = true,
            )
        }
    }
    return RecentEndpointIdentityStartupBackfill(
        candidatesScanned = scanned,
        tokensStored = stored,
        batchesAttempted = maximumBatches,
        complete = false,
    )
}

private data object NoOpRecentSessionWriter : RecentSessionWriter {
    override suspend fun upsert(session: RecentSession) = Unit
}

private val SESSION_WHITESPACE = Regex("\\s+")
private const val MAX_SESSION_LABEL_LENGTH = 96
private const val MAX_TERMINAL_TITLE_LENGTH = 128
private const val MIN_REDACTED_FRAGMENT_LENGTH = 2
private const val DEFAULT_REMOTE_SCROLLBACK_LINES = 20_000
private const val DEFAULT_RECONNECT_MAX_ATTEMPTS = 5
private const val MAX_RECONNECT_ATTEMPTS = 100
private const val DEFAULT_RECONNECT_INITIAL_DELAY_MILLIS = 1_000L
private const val DEFAULT_RECONNECT_MAXIMUM_DELAY_MILLIS = 30_000L
private const val DEFAULT_RECONNECT_STABLE_WINDOW_MILLIS = 30_000L
private const val MAX_RECONNECT_EXPONENT = 62
private const val FRESH_AUTHENTICATION_REQUIRED_MESSAGE =
    "Connection lost. Reconnect manually to start a new shell."
private const val RECONNECT_EXHAUSTED_MESSAGE =
    "Connection lost after automatic reconnect attempts. Reconnect manually to start a new shell."
private const val TRANSPORT_ENDED_MESSAGE = "The remote connection ended unexpectedly."
