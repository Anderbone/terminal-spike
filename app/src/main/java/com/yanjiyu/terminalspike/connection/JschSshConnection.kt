package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class JschSshConnection(
    private val knownHostsFile: () -> File,
    private val config: SshConnectionConfig,
) : Connection {
    private val knownHostStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KnownHostStores.forFile(knownHostsFile())
    }
    private val outgoing = ArrayBlockingQueue<ByteArray>(OUTGOING_QUEUE_CAPACITY)
    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var closeRequested = false

    @Volatile
    private var hostKeyRepository: VerifyingHostKeyRepository? = null

    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var output: OutputStream? = null
    private var writerThread: Thread? = null

    override suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    ) {
        close()
        closeRequested = false
        onState(ConnectionState.Connecting)
        val hostAlias = hostAlias(config.host, config.port)
        val repository = VerifyingHostKeyRepository(
            store = knownHostStore,
            displayHost = hostAlias,
            onPrompt = { prompt -> onState(ConnectionState.AwaitingApproval(prompt)) },
        )
        hostKeyRepository = repository
        val jsch = JSch().apply { setHostKeyRepository(repository) }
        val authentication = config.authentication
        var loadedPassword: ByteArray? = null
        try {
            if (authentication is SshAuthentication.PrivateKey) {
                val keyBytes = authentication.loadKey()
                try {
                    jsch.addIdentity(
                        authentication.identityName,
                        keyBytes,
                        null,
                        authentication.passphrase,
                    )
                } finally {
                    keyBytes.fill(0)
                }
            }
            val newSession = jsch.getSession(config.username, config.host, config.port)
            synchronized(lock) { session = newSession }
            newSession.hostKeyAlias = hostAlias
            when (authentication) {
                is SshAuthentication.Password -> newSession.setPassword(authentication.secret)
                is SshAuthentication.StoredPassword -> {
                    loadedPassword = authentication.loadSecret()
                    newSession.setPassword(loadedPassword)
                }
                is SshAuthentication.PrivateKey -> Unit
            }
            newSession.setConfig("StrictHostKeyChecking", "yes")
            newSession.setConfig(
                "PreferredAuthentications",
                when (authentication) {
                    is SshAuthentication.Password -> "password,keyboard-interactive"
                    is SshAuthentication.StoredPassword -> "password,keyboard-interactive"
                    is SshAuthentication.PrivateKey -> "publickey"
                },
            )
            newSession.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            newSession.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            newSession.connect(CONNECT_TIMEOUT_MS)

            val newShell = newSession.openChannel("shell") as ChannelShell
            newShell.setPty(true)
            newShell.setPtyType(TERMINAL_TYPE)
            newShell.setPtySize(columns.coerceAtLeast(1), rows.coerceAtLeast(1), 0, 0)
            val input = newShell.inputStream
            val newOutput = newShell.outputStream
            synchronized(lock) {
                shell = newShell
                output = newOutput
            }
            newShell.connect(CHANNEL_TIMEOUT_MS)
            running = true
            startWriter(newOutput)
            onState(ConnectionState.Connected)

            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (running && newShell.isConnected) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) onBytes(buffer.copyOf(count))
            }
            if (running) onState(ConnectionState.Disconnected)
        } catch (error: Exception) {
            if (!closeRequested) {
                onState(ConnectionState.Failed(failureMessage(repository, error)))
            }
        } finally {
            when (authentication) {
                is SshAuthentication.Password -> authentication.secret.fill(0)
                is SshAuthentication.StoredPassword -> loadedPassword?.fill(0)
                is SshAuthentication.PrivateKey -> authentication.passphrase?.fill(0)
            }
            closeResources()
        }
    }

    override fun send(bytes: ByteArray) {
        if (!running || bytes.isEmpty()) return
        if (!outgoing.offer(bytes.copyOf())) {
            close()
        }
    }

    override fun resize(columns: Int, rows: Int) {
        synchronized(lock) { shell }?.setPtySize(
            columns.coerceAtLeast(1),
            rows.coerceAtLeast(1),
            0,
            0,
        )
    }

    override fun answerPrompt(accept: Boolean) {
        hostKeyRepository?.answerPrompt(accept)
    }

    override fun close() {
        closeRequested = true
        running = false
        hostKeyRepository?.cancelPrompt()
        closeResources()
    }

    private fun startWriter(stream: OutputStream) {
        writerThread = thread(name = "ssh-terminal-writer", isDaemon = true) {
            try {
                while (running) {
                    val bytes = outgoing.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (_: Exception) {
                close()
            }
        }
    }

    private fun closeResources() {
        running = false
        outgoing.clear()
        val resources = synchronized(lock) {
            val current = Triple(output, shell, session)
            output = null
            shell = null
            session = null
            current
        }
        runCatching { resources.first?.close() }
        runCatching { resources.second?.disconnect() }
        runCatching { resources.third?.disconnect() }
        writerThread?.interrupt()
        writerThread = null
    }

    private fun failureMessage(repository: VerifyingHostKeyRepository, error: Exception): String =
        when (repository.failure) {
            HostKeyFailure.CHANGED -> "Host key changed. Connection blocked."
            HostKeyFailure.REJECTED -> "Host key was not trusted."
            HostKeyFailure.STORE_FAILED -> "Could not save the trusted host key."
            null -> when (error) {
                is JSchException -> error.message?.takeIf { it.isNotBlank() } ?: "SSH connection failed."
                else -> "SSH connection failed."
            }
        }

    private fun hostAlias(host: String, port: Int): String =
        if (port == DEFAULT_SSH_PORT) host else "[$host]:$port"

    companion object {
        private const val DEFAULT_SSH_PORT = 22
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 10_000
        private const val SERVER_ALIVE_INTERVAL_MS = 30_000
        private const val SERVER_ALIVE_COUNT_MAX = 3
        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val OUTGOING_QUEUE_CAPACITY = 256
        private const val WRITER_POLL_MS = 250L
        private const val TERMINAL_TYPE = "xterm-256color"
    }
}
