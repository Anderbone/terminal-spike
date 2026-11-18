package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.util.UUID

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
    val taskStatus: TerminalTaskStatus = TerminalTaskStatus(),
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

internal data class TerminalProgramNotificationEvent(
    val sessionId: Long,
    val sessionTitle: String,
    val message: String,
    val task: HerdrAgentState? = null,
) {
    val notificationKey: String get() = task?.let {
        val identity = "$sessionId/${it.paneId}/${it.terminalId}/${it.agentSession}/${it.replyId ?: it.sequence}"
        java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    } ?: sessionId.toString()
}

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
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
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

    data object Unavailable : DuplicateSshSessionResult
}

internal fun requireCanonicalSessionUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Recent-session ID must be a canonical UUID."
    }
}

private const val DEFAULT_REMOTE_SCROLLBACK_LINES = 20_000
