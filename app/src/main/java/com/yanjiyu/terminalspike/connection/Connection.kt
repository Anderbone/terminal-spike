package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.requireIdentifier
import com.yanjiyu.terminalspike.core.model.requireOptionalCommand
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import java.io.InputStream

interface Connection : TerminalInputSink {
    /** True while this transport has an app-selected or side-channel-verified tmux client. */
    val isTmuxSession: Boolean
        get() = false

    val terminalTaskStatus: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus
        get() = com.yanjiyu.terminalspike.terminal.TerminalTaskStatus()

    fun acknowledgeTaskStatus() = Unit

    /** Background-only identity refresh; true means the attached client/pane changed. */
    fun refreshTmuxIdentity(): Boolean = false

    /**
     * Captures the confirmed app-owned tmux pane through its authenticated side channel.
     * Implementations may block; callers must invoke this away from the Android main thread.
     */
    fun captureTmuxPane(includeHistory: Boolean = true): TmuxPaneCapture? = null

    /** Captures the bounded history page immediately before [request.beforeRow]. */
    fun captureTmuxHistoryPage(request: TmuxHistoryPageRequest): TmuxPaneCapture? = null

    /** Returns false when the bounded transport queue cannot accept the complete input batch. */
    override fun trySend(bytes: ByteArray): Boolean

    suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    )

    fun resize(columns: Int, rows: Int)
    /** Resolves only the repository-owned host-identity prompt identified by [promptToken]. */
    fun answerHostIdentityPrompt(
        promptToken: Long,
        decision: HostIdentityDecision,
    )

    /** Transfers ownership of mutable responses for one exact interactive challenge. */
    fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    )

    /** Cancels only the identified interactive challenge; stale dialog actions are ignored. */
    fun cancelKeyboardInteractiveChallenge(challengeToken: Long)

    /** Resolves only the identified tmux chooser; null opens the ordinary remote shell. */
    fun answerTmuxSessionPrompt(promptToken: Long, sessionId: String?) = Unit

    /** Requests deletion from the exact live chooser; completion is reflected by a new prompt. */
    fun deleteTmuxSession(promptToken: Long, sessionId: String) = Unit

    /** Queries tmux through the authenticated side channel without touching terminal output. */
    fun queryTmuxSessionCatalog(includePreviews: Boolean = false): TmuxSessionCatalog =
        TmuxSessionCatalog()

    /** Terminates one validated tmux session and returns the refreshed bounded catalogue. */
    fun terminateTmuxSession(sessionId: String): TmuxSessionCatalog =
        TmuxSessionCatalog(deleteFailed = true)

    /** Switches an attached tmux client or attaches the interactive shell to the chosen session. */
    fun switchTmuxSession(sessionId: String): Boolean = false

    /** Cancels every currently owned prompt when this transport is retired or closed. */
    fun cancelPendingPrompts()

    fun close()
}

/** Stable coordinates for an older-page request against one exact tmux pane snapshot. */
data class TmuxHistoryPageRequest(
    val paneId: String,
    val beforeRow: Int,
    val remoteHistoryRows: Int,
) {
    init {
        require(paneId.matches(Regex("%[0-9]+")))
        require(beforeRow > 0)
        require(remoteHistoryRows >= beforeRow)
    }
}

/** Bounded physical tmux history rows plus the metadata needed to parse them safely. */
class TmuxPaneCapture(
    val sessionId: String,
    val paneId: String,
    val columns: Int,
    val rows: Int,
    /** Number of rows carried in [content], rather than tmux's complete retained history. */
    val historyRows: Int,
    /** Complete retained history coordinate at capture time, including a saved primary grid. */
    val remoteHistoryRows: Int = historyRows,
    /** Absolute row coordinate of the first row carried in [content]. */
    val capturedStartRow: Int = 0,
    /** Earliest row this bounded local cache is allowed to request. */
    val oldestAvailableRow: Int = 0,
    /** True only for a disjoint page intended to be prepended to an existing cache. */
    val olderPage: Boolean = false,
    val alternateScreenActive: Boolean,
    /** True only when the pane application enabled a terminal mouse-tracking mode. */
    val mouseTrackingActive: Boolean,
    val paneInMode: Boolean,
    /** False for a lightweight mode/freshness probe that deliberately omits pane history bytes. */
    val historyIncluded: Boolean,
    val truncatedBefore: Boolean,
    val authoritative: Boolean,
    content: ByteArray,
) {
    val content: ByteArray = content

    init {
        require(sessionId.isTmuxSessionId())
        require(paneId.matches(Regex("%[0-9]+")))
        require(columns in 1..MAX_CAPTURE_COLUMNS)
        require(rows in 1..MAX_CAPTURE_ROWS)
        require(historyRows in 0..ModelLimits.MAX_SCROLLBACK_LINES)
        require(remoteHistoryRows >= historyRows)
        require(oldestAvailableRow in 0..capturedStartRow)
        require(capturedStartRow <= remoteHistoryRows - historyRows)
        require(this.content.size <= MAX_CAPTURE_BYTES)
        require(historyIncluded || this.content.isEmpty())
        require(historyRows > 0 || this.content.isEmpty())
    }

    companion object {
        const val MAX_CAPTURE_BYTES = 16 * 1024 * 1024
        private const val MAX_CAPTURE_COLUMNS = 500
        private const val MAX_CAPTURE_ROWS = 16_384
    }
}

/** Optional SFTP side channel provided by a live SSH transport. */
internal fun interface RemoteImageUploadConnection {
    /** Uploads one generated image name and returns its absolute path on the remote host. */
    fun uploadPastedImage(fileName: String, source: InputStream): String
}

class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authentication: SshAuthentication,
    /** Zero disables SSH protocol keepalives; otherwise this is the server-alive interval. */
    val keepaliveIntervalSeconds: Int = DEFAULT_SSH_KEEPALIVE_INTERVAL_SECONDS,
    /** Validated TERM value negotiated for an interactive SSH PTY. */
    val terminalType: String = TerminalProfile.DEFAULT_TERM_VALUE,
    /** Optional user-authored input dispatched once after each fresh interactive shell connects. */
    val startupCommand: String? = null,
    /** Shows the authenticated tmux session chooser before opening an SSH shell. */
    val tmuxSessionSelectorEnabled: Boolean = false,
) {
    init {
        require(
            keepaliveIntervalSeconds == 0 ||
                keepaliveIntervalSeconds in
                ModelLimits.MIN_KEEPALIVE_SECONDS..ModelLimits.MAX_KEEPALIVE_SECONDS,
        ) {
            "SSH keepalive must be off or within the supported interval range."
        }
        requireIdentifier(terminalType, "SSH terminal type", ModelLimits.MAX_TERM_LENGTH)
        requireOptionalCommand(
            startupCommand,
            "SSH startup command",
            ModelLimits.MAX_COMMAND_LENGTH,
        )
    }

    private companion object {
        const val DEFAULT_SSH_KEEPALIVE_INTERVAL_SECONDS = 30
    }
}

sealed interface SshAuthentication {
    class Password(val secret: ByteArray) : SshAuthentication

    class StoredPassword(val loadSecret: suspend () -> ByteArray) : SshAuthentication

    class PrivateKey(
        val identityName: String,
        val loadKey: suspend () -> ByteArray,
        /** Session-owned one-shot bytes. Mutually exclusive with [loadPassphrase]. */
        val passphrase: ByteArray?,
        /** Reloads an explicitly saved encrypted passphrase for each fresh transport. */
        val loadPassphrase: (suspend () -> ByteArray)? = null,
    ) : SshAuthentication {
        init {
            require(passphrase == null || loadPassphrase == null) {
                "A private-key passphrase must be either session-only or reloadable, not both."
            }
        }
    }

    /** Genuine keyboard-interactive authentication with only session-owned mutable material. */
    sealed interface KeyboardInteractive : SshAuthentication {
        class SessionOnly(
            /** Optional one-shot response for a supported single hidden-prompt challenge. */
            val initialResponse: ByteArray? = null,
        ) : KeyboardInteractive

        /** Reloads an explicitly saved response for a supported single hidden prompt only. */
        class ReusableResponse(
            val loadResponse: suspend () -> ByteArray,
        ) : KeyboardInteractive
    }
}

/** Idempotently clears the mutable authentication material owned by this configuration. */
internal fun SshConnectionConfig.clearAuthenticationSecrets() {
    when (val auth = authentication) {
        is SshAuthentication.Password -> auth.secret.fill(0)
        is SshAuthentication.PrivateKey -> auth.passphrase?.fill(0)
        is SshAuthentication.StoredPassword -> Unit
        is SshAuthentication.KeyboardInteractive.SessionOnly -> auth.initialResponse?.fill(0)
        is SshAuthentication.KeyboardInteractive.ReusableResponse -> Unit
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** A lost transport is waiting for connectivity/backoff before opening a fresh remote shell. */
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
        val waitingForNetwork: Boolean,
        val retryDelayMillis: Long?,
    ) : ConnectionState {
        init {
            require(attempt > 0)
            require(maxAttempts >= attempt)
            require(retryDelayMillis == null || retryDelayMillis >= 0L)
            require(waitingForNetwork == (retryDelayMillis == null))
        }
    }
    data class AwaitingApproval(val prompt: ConnectionPrompt) : ConnectionState
    data class Failed(
        val message: String,
        /** Fail closed: callers must explicitly identify the narrow transport-loss retry case. */
        val disposition: ConnectionFailureDisposition = ConnectionFailureDisposition.TERMINAL,
        /** Non-null only for reviewed Mosh failures that may start a fresh SSH shell. */
        val moshFallbackFailure: MoshFallbackFailure? = null,
    ) : ConnectionState
}

/** Authentication, host-key trust, cancellation, and unknown failures deliberately have no value. */
enum class MoshFallbackFailure {
    EXTENSION,
    BOOTSTRAP,
    UDP,
}

/** Whether a terminal failure may open a fresh transport without another visible user action. */
enum class ConnectionFailureDisposition {
    /** A bounded reconnect may be attempted because only the live transport was lost. */
    TRANSIENT_TRANSPORT,

    /** Trust, authentication, configuration, local safety, and unknown failures stop here. */
    TERMINAL,
}

internal fun transientTransportFailure(message: String): ConnectionState.Failed =
    ConnectionState.Failed(
        message = message,
        disposition = ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
    )

sealed interface ConnectionPrompt

/** Public, non-secret metadata for one exact host-identity decision owned by the transport. */
sealed interface HostIdentityPrompt : ConnectionPrompt {
    val promptToken: Long
    val endpoint: String
    val algorithm: String
    val newFingerprint: String

    data class FirstContact(
        override val endpoint: String,
        override val algorithm: String,
        override val newFingerprint: String,
        override val promptToken: Long,
    ) : HostIdentityPrompt {
        init {
            require(promptToken > 0L) { "Host-identity prompt token must be positive." }
        }
    }

    data class Changed(
        override val endpoint: String,
        override val algorithm: String,
        val previousFingerprint: String,
        override val newFingerprint: String,
        override val promptToken: Long,
    ) : HostIdentityPrompt {
        init {
            require(promptToken > 0L) { "Host-identity prompt token must be positive." }
        }
    }
}

/** Exhaustive decisions; the verifier rejects choices that are invalid for the prompt kind. */
sealed interface HostIdentityDecision {
    data object Reject : HostIdentityDecision
    data object TrustOnce : HostIdentityDecision
    data object TrustAndSave : HostIdentityDecision
    data object ReplaceSavedKey : HostIdentityDecision
}
