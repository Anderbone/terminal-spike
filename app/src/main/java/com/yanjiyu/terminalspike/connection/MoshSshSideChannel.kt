package com.yanjiyu.terminalspike.connection

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Mosh can outlive its bootstrap TCP connection. Reloadable credentials let each attachment use a
 * short-lived authenticated connection if needed, without replaying terminal startup or a partially
 * read file.
 * User-requested project operations can also acquire a fresh connection before doing any work.
 * Background exec operations retain their existing owner.
 */
internal class MoshSshSideChannel(
    private val original: MoshSshExecSession,
    private val config: SshConnectionConfig,
    private val factory: MoshSshExecSessionFactory,
) : MoshSshExecSession by original {
    private val lock = Any()
    private var closed = false
    private val connecting = mutableSetOf<AutoCloseable>()

    override fun <T> withLiveProjectSession(operation: (MoshSshExecSession) -> T): T {
        synchronized(lock) { check(!closed) { "The Mosh session is closed." } }
        try {
            // Only this harmless probe may be retried. Never replay workspace creation after
            // a lost response: the host may already have applied it.
            readBoundedExecOutput(original.openExec("/bin/sh -c :"), PROJECT_PROBE_LIMITS).use {
                check(it.exitStatus == 0) { "The SSH side channel is unavailable." }
            }
        } catch (error: Exception) {
            if (error is CancellationException || error is InterruptedException ||
                !config.authentication.canReloadForSideChannel()
            ) throw error
            return withFreshSession(operation)
        }
        synchronized(lock) { check(!closed) { "The Mosh session is closed." } }
        return operation(original)
    }

    override fun uploadPastedImage(fileName: String, source: InputStream): String {
        synchronized(lock) { check(!closed) { "The Mosh session is closed." } }
        // Keep healthy connections (including one-time host trust) working. A failed attempt
        // may only fall back before it reads the file; the caller still owns the source stream.
        var readStarted = false
        val borrowed = object : FilterInputStream(source) {
            override fun read(): Int {
                readStarted = true
                return `in`.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length > 0) readStarted = true
                return `in`.read(bytes, offset, length)
            }

            override fun close() = Unit
        }
        try {
            return original.uploadPastedImage(fileName, borrowed)
        } catch (error: Exception) {
            if (error is CancellationException || error is InterruptedException || readStarted ||
                !config.authentication.canReloadForSideChannel()
            ) throw error
        }
        return withFreshSession { it.uploadPastedImage(fileName, borrowed) }
    }

    private fun <T> withFreshSession(operation: (MoshSshExecSession) -> T): T {
        // No forwarding listeners, startup commands, or session selection on this connection.
        val reconnectConfig = SshConnectionConfig(
            host = config.host,
            port = config.port,
            username = config.username,
            authentication = config.authentication,
            keepaliveIntervalSeconds = config.keepaliveIntervalSeconds,
        )
        var session: MoshSshExecSession? = null
        var pending: AutoCloseable? = null
        return AutoCloseable {
            // Remove tracking before close: a failing close must not retain the lease until Mosh ends.
            val registered = synchronized(lock) {
                pending?.also(connecting::remove)
            }
            if (registered === session) {
                session?.close()
            } else {
                // These may wrap the same transport, but each independently returned handle owns
                // its cleanup. use preserves the primary failure and attempts both closes.
                registered.use { session?.close() }
            }
        }.use {
            session = runBlocking {
                factory.open(
                    config = reconnectConfig,
                    // Side-channel work must never silently accept a new host key or
                    // wait for an interactive prompt that the terminal does not own.
                    onPrompt = { error("Reconnect the terminal to verify the SSH host identity.") },
                    onKeyboardInteractiveChallenge = {
                        error("Reconnect the terminal to answer the SSH authentication challenge.")
                    },
                    onRepositoryReady = {},
                    registerConnectingSession = { resource ->
                        synchronized(lock) {
                            if (closed) false else {
                                pending = resource
                                connecting.add(resource)
                                true
                            }
                        }
                    },
                )
            }
            val fresh = checkNotNull(session) { "The Mosh SSH connection was cancelled." }
            synchronized(lock) { check(!closed) { "The Mosh session is closed." } }
            operation(fresh)
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            connecting.toList().also { connecting.clear() }
        }
        try {
            pending.forEach { runCatching { it.close() } }
        } finally {
            original.close()
        }
    }

    private companion object {
        val PROJECT_PROBE_LIMITS = MoshExecLimits(
            channelConnectTimeoutMillis = 5_000,
            totalTimeoutMillis = 5_000,
            maximumOutputBytes = 1_024,
        )
    }
}

private fun SshAuthentication.canReloadForSideChannel(): Boolean = when (this) {
    is SshAuthentication.StoredPassword,
    is SshAuthentication.KeyboardInteractive.ReusableResponse,
    -> true
    is SshAuthentication.PrivateKey -> passphrase == null
    is SshAuthentication.Password,
    is SshAuthentication.KeyboardInteractive.SessionOnly,
    -> false
}
