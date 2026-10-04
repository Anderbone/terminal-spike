package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place where a JSch session receives this application's SSH trust and authentication
 * policy. Interactive shells and short-lived bootstrap commands must both use this factory.
 */
internal class JschAuthenticatedSessionFactory(
    private val knownHostManager: KnownHostManager,
    private val newJsch: () -> JSch = ::JSch,
) {
    suspend fun connect(
        config: SshConnectionConfig,
        onPrompt: (HostIdentityPrompt) -> Unit,
        onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit = {},
        onRepositoryReady: (VerifyingHostKeyRepository) -> Unit = {},
        registerBeforeConnect: (AuthenticatedJschSession) -> Boolean = { true },
        socketFactory: SocketFactory? = null,
    ): AuthenticatedJschSession? {
        var activeJsch: JSch? = null
        var authenticatedSession: AuthenticatedJschSession? = null
        var connected = false
        try {
            val hostAlias = sshHostAlias(config.host, config.port)
            val verifyingRepository = knownHostManager.verifyingRepository(
                host = config.host,
                port = config.port,
                displayHost = hostAlias,
                onPrompt = onPrompt,
            )
            onRepositoryReady(verifyingRepository)

            val jsch = newJsch().apply { setHostKeyRepository(verifyingRepository) }
            activeJsch = jsch
            val keyboardInteractive = when (config.authentication) {
                is SshAuthentication.KeyboardInteractive -> KeyboardInteractiveBridge(
                    onChallenge = onKeyboardInteractiveChallenge,
                )
                else -> null
            }
            val session = jsch.getSession(config.username, config.host, config.port).apply {
                hostKeyAlias = hostAlias
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", config.authentication.preferredAuthentications())
                setServerAliveInterval(config.keepaliveIntervalSeconds * MILLIS_PER_SECOND)
                setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
                keyboardInteractive?.let(::setUserInfo)
                if (socketFactory != null) setSocketFactory(socketFactory)
            }
            val opened = AuthenticatedJschSession(
                jsch = jsch,
                session = session,
                keyboardInteractive = keyboardInteractive,
                onClose = verifyingRepository::retire,
            )
            authenticatedSession = opened

            if (!registerBeforeConnect(opened)) return null
            applyAuthentication(
                config.authentication,
                JschAuthenticationTarget(jsch, session, keyboardInteractive),
            )
            if (opened.isClosed()) return null
            session.connect(CONNECT_TIMEOUT_MS)
            if (opened.isClosed()) {
                session.disconnect()
                return null
            }
            opened.startPortForwards(config.portForwards)
            if (opened.isClosed()) return null
            connected = true
            return opened
        } finally {
            // These are caller/configuration-owned arrays. JSch makes its own bounded internal
            // copies and clears its password copy after authentication; identities are removed
            // when the returned lease closes.
            config.clearAuthenticationSecrets()
            if (!connected) {
                authenticatedSession?.close() ?: runCatching { activeJsch?.removeAllIdentity() }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MILLIS_PER_SECOND = 1_000
        const val SERVER_ALIVE_COUNT_MAX = 3
    }
}

/** Terminal lease on an authenticated transport, retained while shared forwards still use it. */
internal class AuthenticatedJschSession(
    private val jsch: JSch,
    val session: Session,
    private val keyboardInteractive: KeyboardInteractiveBridge? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable, KeyboardInteractivePromptController {
    private val lock = Any()
    private var closed = false
    private var forwards: AutoCloseable? = null
    private val transportReferences = AtomicInteger(1)
    private val forwardingTransport = object : PortForwardTransport {
        override val endpoint = PortForwardEndpoint(session.host, session.port, session.userName)
        override val connected get() = session.isConnected
        override fun retain() { transportReferences.incrementAndGet() }
        override fun release() = releaseTransport()
        override fun start(rule: PortForwardRule) = session.startPortForwards(listOf(rule))
        override fun stop(rule: PortForwardRule) {
            when (rule.direction) {
                PortForwardDirection.LOCAL -> session.delPortForwardingL(rule.bindAddress, rule.listenPort)
                PortForwardDirection.REMOTE -> session.delPortForwardingR(rule.bindAddress, rule.listenPort)
            }
        }
    }

    private fun releaseTransport() {
        if (transportReferences.decrementAndGet() == 0) {
            runCatching { session.disconnect() }
            runCatching { jsch.removeAllIdentity() }
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun startPortForwards(rules: List<PortForwardRule>) = synchronized(lock) {
        // Close cannot race listener creation, including a partially completed remote registration.
        if (!closed && forwards == null) {
            forwards = SharedPortForwards.process.acquire(forwardingTransport, rules)
        }
    }

    override fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean = keyboardInteractive?.answer(challengeToken, responses) ?: run {
        responses.forEach { it.fill('\u0000') }
        false
    }

    override fun cancelKeyboardInteractiveChallenge(challengeToken: Long): Boolean =
        keyboardInteractive?.cancel(challengeToken) ?: false

    override fun cancelPendingKeyboardInteractiveChallenge(): Boolean =
        keyboardInteractive?.cancelActive() ?: false

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        keyboardInteractive?.close()
        runCatching(onClose)
        forwards?.close()
        releaseTransport()
    }
}

/** Small test seam around APIs that copy authentication material into JSch. */
internal interface SshAuthenticationTarget {
    fun setPassword(secret: ByteArray)

    fun addIdentity(
        identityName: String,
        privateKey: ByteArray,
        passphrase: ByteArray?,
    )

    fun setKeyboardInteractiveReusableResponse(secret: ByteArray)
}

private class JschAuthenticationTarget(
    private val jsch: JSch,
    private val session: Session,
    private val keyboardInteractive: KeyboardInteractiveBridge?,
) : SshAuthenticationTarget {
    override fun setPassword(secret: ByteArray) {
        session.setPassword(secret)
    }

    override fun addIdentity(
        identityName: String,
        privateKey: ByteArray,
        passphrase: ByteArray?,
    ) {
        jsch.addIdentity(identityName, privateKey, null, passphrase)
    }

    override fun setKeyboardInteractiveReusableResponse(secret: ByteArray) {
        checkNotNull(keyboardInteractive) {
            "Keyboard-interactive authentication bridge is unavailable."
        }.replaceReusableResponse(secret)
    }
}

/**
 * Loads a credential at most once and clears every application-owned byte array on success and on
 * failure. The target must copy any material it needs after this function returns.
 */
internal suspend fun applyAuthentication(
    authentication: SshAuthentication,
    target: SshAuthenticationTarget,
) {
    var loadedSecret: ByteArray? = null
    var loadedPassphrase: ByteArray? = null
    try {
        when (authentication) {
            is SshAuthentication.Password -> target.setPassword(authentication.secret)
            is SshAuthentication.StoredPassword -> {
                val secret = authentication.loadSecret()
                loadedSecret = secret
                target.setPassword(secret)
            }
            is SshAuthentication.PrivateKey -> {
                val key = authentication.loadKey()
                loadedSecret = key
                val passphrase = authentication.passphrase ?: authentication.loadPassphrase?.invoke()?.also {
                    loadedPassphrase = it
                }
                target.addIdentity(authentication.identityName, key, passphrase)
            }
            is SshAuthentication.KeyboardInteractive.SessionOnly -> {
                authentication.initialResponse?.let(target::setKeyboardInteractiveReusableResponse)
            }
            is SshAuthentication.KeyboardInteractive.ReusableResponse -> {
                val response = authentication.loadResponse()
                loadedSecret = response
                target.setKeyboardInteractiveReusableResponse(response)
            }
        }
    } finally {
        loadedSecret?.fill(0)
        loadedPassphrase?.fill(0)
        when (authentication) {
            is SshAuthentication.Password -> authentication.secret.fill(0)
            is SshAuthentication.PrivateKey -> authentication.passphrase?.fill(0)
            is SshAuthentication.StoredPassword -> Unit
            is SshAuthentication.KeyboardInteractive.SessionOnly ->
                authentication.initialResponse?.fill(0)
            is SshAuthentication.KeyboardInteractive.ReusableResponse -> Unit
        }
    }
}

private fun SshAuthentication.preferredAuthentications(): String = when (this) {
    is SshAuthentication.Password,
    is SshAuthentication.StoredPassword,
    -> "password,keyboard-interactive"
    is SshAuthentication.PrivateKey -> "publickey"
    is SshAuthentication.KeyboardInteractive -> "keyboard-interactive"
}

internal fun sshHostAlias(host: String, port: Int): String =
    if (port == DEFAULT_SSH_PORT) host else "[$host]:$port"

private const val DEFAULT_SSH_PORT = 22
