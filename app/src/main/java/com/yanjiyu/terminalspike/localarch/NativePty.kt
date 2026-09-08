package com.yanjiyu.terminalspike.localarch

import androidx.annotation.Keep
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Original Android PTY bridge. All blocking methods must run away from the UI thread. */
internal interface LocalPty : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun resize(columns: Int, rows: Int)
    fun exitCode(): Int?
}

internal class NativePty private constructor(handle: Long) : LocalPty {
    private val handle = AtomicLong(handle)

    /** Returns bytes read, zero after a short observation timeout, or -1 at terminal EOF. */
    override fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        val current = handle.get()
        return if (current == 0L) -1 else PtyNative.read(current, buffer)
    }

    /** Writes one bounded frame completely, or throws if it cannot be delivered. */
    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_WRITE_BYTES)
        val current = handle.get()
        if (current == 0L) throw IOException("Local terminal is closed")
        PtyNative.write(current, bytes)
    }

    override fun resize(columns: Int, rows: Int) {
        require(columns in 1..500 && rows in 1..500)
        handle.get().takeIf { it != 0L }?.let { PtyNative.resize(it, columns, rows) }
    }

    /** Returns null while running, or a shell-style exit status after reaping. */
    override fun exitCode(): Int? = handle.get().takeIf { it != 0L }
        ?.let(PtyNative::exitCode)?.takeIf { it >= 0 }

    override fun close() {
        handle.getAndSet(0L).takeIf { it != 0L }?.let(PtyNative::close)
    }

    companion object {
        const val MAX_WRITE_BYTES = 64 * 1024

        fun start(
            executable: String,
            arguments: List<String>,
            environment: Map<String, String>,
            workingDirectory: String,
            columns: Int,
            rows: Int,
        ): NativePty {
            require(executable.startsWith('/') && workingDirectory.startsWith('/'))
            require(columns in 1..500 && rows in 1..500)
            require((listOf(executable, workingDirectory) + arguments).all { '\u0000' !in it })
            require(environment.all { (key, value) ->
                key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value
            })
            return NativePty(
                PtyNative.start(
                    (listOf(executable) + arguments).toTypedArray(),
                    environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
                    workingDirectory, columns, rows,
                ),
            )
        }
    }
}

@Keep
internal object PtyNative {
    init { System.loadLibrary("localpty") }

    external fun start(argv: Array<String>, env: Array<String>, cwd: String, columns: Int, rows: Int): Long
    external fun read(handle: Long, buffer: ByteArray): Int
    external fun write(handle: Long, bytes: ByteArray)
    external fun resize(handle: Long, columns: Int, rows: Int)
    external fun exitCode(handle: Long): Int
    external fun close(handle: Long)
}
