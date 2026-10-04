package com.yanjiyu.terminalspike.connection

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Mosh can outlive its bootstrap TCP connection. Reloadable credentials let each attachment use a
 * short-lived authenticated connection if needed, without replaying terminal startup or a partially
 * read file.
 * Exec operations retain their existing owner; this lease only changes attachment transport.
 */
internal class MoshImageUploadSession(
    private val original: MoshSshExecSession,
    private val config: SshConnectionConfig,
    private val factory: MoshSshExecSessionFactory,
) : MoshSshExecSession by original {
    private val lock = Any()
    private val uploadLock = Any()
    private var closed = false
    private var connecting: AutoCloseable? = null

    override fun uploadPastedImage(fileName: String, source: InputStream): String = synchronized(uploadLock) {
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
            return@synchronized original.uploadPastedImage(fileName, borrowed)
        } catch (error: Exception) {
            if (error is CancellationException || readStarted ||
                !config.authentication.canReloadForImageUpload()
            ) throw error
        }
        // No forwarding listeners, startup commands, or tmux selection on the upload connection.
        val uploadConfig = SshConnectionConfig(
            host = config.host,
            port = config.port,
            username = config.username,
            authentication = config.authentication,
            keepaliveIntervalSeconds = config.keepaliveIntervalSeconds,
        )
        var session: MoshSshExecSession? = null
        try {
            session = runBlocking {
                factory.open(
                    config = uploadConfig,
                    // Background attachment work must never silently accept a new host key or
                    // wait for an interactive prompt that the terminal does not own.
                    onPrompt = { error("Reconnect the terminal to verify the SSH host identity.") },
                    onKeyboardInteractiveChallenge = {
                        error("Reconnect the terminal to answer the SSH authentication challenge.")
                    },
                    onRepositoryReady = {},
                    registerConnectingSession = { resource ->
                        synchronized(lock) {
                            if (closed) false else {
                                connecting = resource
                                true
                            }
                        }
                    },
                )
            }
            checkNotNull(session) { "The Mosh upload connection was cancelled." }
            synchronized(lock) { check(!closed) { "The Mosh session is closed." } }
            session.uploadPastedImage(fileName, source)
        } finally {
            session?.close()
            val pending = synchronized(lock) { connecting.also { connecting = null } }
            if (pending !== session) pending?.close()
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            connecting.also { connecting = null }
        }
        try {
            pending?.close()
        } finally {
            original.close()
        }
    }
}

private fun SshAuthentication.canReloadForImageUpload(): Boolean = when (this) {
    is SshAuthentication.StoredPassword,
    is SshAuthentication.KeyboardInteractive.ReusableResponse,
    -> true
    is SshAuthentication.PrivateKey -> passphrase == null
    is SshAuthentication.Password,
    is SshAuthentication.KeyboardInteractive.SessionOnly,
    -> false
}
