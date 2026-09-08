package com.yanjiyu.terminalspike.localarch

import com.yanjiyu.terminalspike.connection.BoundedSshWriter
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.ConnectionStatePublisher
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One local process, not the persistent Arch environment. Never reconnects over a network. */
internal class LocalSession(private val launch: (Int, Int) -> LocalPty) : Connection {
    constructor(command: ProotCommand) : this(command::start)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var process: LocalPty? = null
    private var writer: BoundedSshWriter? = null

    override suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "A local shell session can only start once" }
        val states = ConnectionStatePublisher(onState)
        var owned: LocalPty? = null
        try {
            if (closed.get()) return@withContext
            states.publish(ConnectionState.Connecting)
            currentCoroutineContext().ensureActive()
            owned = launch(columns, rows)
            currentCoroutineContext().ensureActive()
            if (closed.get()) return@withContext
            val pty = owned
            val pump = BoundedSshWriter(capacity = 64, pollIntervalMillis = 25)
            synchronized(lock) {
                process = pty
                writer = pump
            }
            pump.start(
                object : OutputStream() {
                    override fun write(value: Int) = pty.write(byteArrayOf(value.toByte()))
                    override fun write(bytes: ByteArray, offset: Int, length: Int) =
                        pty.write(if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length))
                },
                onFailure = {
                    if (!closed.get()) states.publish(ConnectionState.Failed("Local terminal input failed"))
                    close()
                },
            )
            if (closed.get()) return@withContext
            states.publish(ConnectionState.Connected)
            val buffer = ByteArray(32 * 1024)
            while (!closed.get()) {
                currentCoroutineContext().ensureActive()
                val count = pty.read(buffer)
                if (count < 0) break
                if (count > 0) onBytes(buffer.copyOf(count))
            }
            val exitCode = pty.exitCode()
            states.publish(
                if (!closed.get() && exitCode != null && exitCode != 0) {
                    ConnectionState.Failed("Local shell exited with status $exitCode")
                } else {
                    ConnectionState.Disconnected
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!closed.get()) states.publish(ConnectionState.Failed("Could not run the local Arch shell"))
        } finally {
            closed.set(true)
            synchronized(lock) {
                writer?.stop()
                writer = null
                process = null
            }
            owned?.close()
            states.publish(ConnectionState.Disconnected)
        }
    }

    override fun trySend(bytes: ByteArray): Boolean = synchronized(lock) {
        !closed.get() && bytes.size <= NativePty.MAX_WRITE_BYTES && writer?.offer(bytes) == true
    }

    override fun send(bytes: ByteArray) {
        sendWithAcceptance(bytes)
    }

    override fun sendWithAcceptance(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val accepted = trySend(bytes)
        if (!accepted) close()
        return accepted
    }

    override fun resize(columns: Int, rows: Int) {
        synchronized(lock) {
            if (!closed.get()) process?.resize(columns.coerceIn(1, 500), rows.coerceIn(1, 500))
        }
    }

    override fun close() {
        closed.set(true)
        synchronized(lock) { writer?.stop() }
        // The read has a bounded observation timeout; connect's IO-thread finally
        // owns native termination/reaping, never the Android main thread.
    }

    override fun answerHostIdentityPrompt(promptToken: Long, decision: HostIdentityDecision) = Unit
    override fun answerKeyboardInteractiveChallenge(challengeToken: Long, responses: List<CharArray>) {
        responses.forEach { it.fill('\u0000') }
    }
    override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit
    override fun cancelPendingPrompts() = Unit
}
