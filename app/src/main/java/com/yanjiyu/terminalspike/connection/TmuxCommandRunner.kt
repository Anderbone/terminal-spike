package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun interface TmuxCommandRunner {
    fun run(command: String): TmuxExecOutput
}

internal data class TmuxExecOutput(val stdout: ByteArray, val exitStatus: Int)

internal class JschTmuxCommandRunner(
    private val session: Session,
    private val limits: TmuxExecLimits = DEFAULT_TMUX_EXEC_LIMITS,
) : TmuxCommandRunner {
    override fun run(command: String): TmuxExecOutput = runBoundedTmuxExec(session.openChannel("exec") as ChannelExec, command, limits)
}

internal data class TmuxExecLimits(
    val channelConnectTimeoutMillis: Int,
    val totalTimeoutMillis: Long,
    val maximumOutputBytes: Int,
) {
    init {
        require(channelConnectTimeoutMillis in 1..60_000)
        require(totalTimeoutMillis in 1..60_000)
        require(maximumOutputBytes in 1..TmuxPaneCapture.MAX_CAPTURE_BYTES)
    }
}

internal fun runBoundedTmuxExec(
    channel: ChannelExec,
    command: String,
    limits: TmuxExecLimits,
    nanoTime: () -> Long = System::nanoTime,
): TmuxExecOutput = java.io.Closeable { channel.disconnect() }.use {
    executeTmuxChannel(channel, command, limits, nanoTime)
}

private fun executeTmuxChannel(
    channel: ChannelExec,
    command: String,
    limits: TmuxExecLimits,
    nanoTime: () -> Long,
): TmuxExecOutput {
    var stdout: InputStream? = null
    var stderr: InputStream? = null
    var scratch: ByteArray? = null
    try {
        val startedAt = nanoTime()
        val timeoutNanos = limits.totalTimeoutMillis * 1_000_000L
        fun remainingNanos(): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("tmux command interrupted.")
            val elapsed = nanoTime() - startedAt
            check(elapsed >= 0L && elapsed < timeoutNanos) { "tmux command timed out." }
            return timeoutNanos - elapsed
        }
        val checkDeadline: () -> Unit = { remainingNanos() }
        checkDeadline()
        channel.setPty(false)
        channel.setInputStream(null)
        channel.setCommand(command)
        val stdoutStream = channel.inputStream.also { stdout = it }
        val stderrStream = channel.extInputStream.also { stderr = it }
        val scratchBuffer = ByteArray(TMUX_READ_BUFFER_BYTES).also { scratch = it }
        val output = ByteArrayOutputStream()
        // JSch interprets zero as no connect timeout. Round a positive remainder up to one ms.
        val connectMillis = (remainingNanos() + 999_999L) / 1_000_000L
        channel.connect(minOf(limits.channelConnectTimeoutMillis.toLong(), connectMillis).toInt())
        checkDeadline()
        while (true) {
            drainTmuxInput(stdoutStream, output, scratchBuffer, true, limits.maximumOutputBytes, checkDeadline)
            drainTmuxInput(stderrStream, output, scratchBuffer, false, limits.maximumOutputBytes, checkDeadline)
            if (channel.isClosed) {
                drainTmuxInput(stdoutStream, output, scratchBuffer, true, limits.maximumOutputBytes, checkDeadline)
                checkDeadline()
                return TmuxExecOutput(output.toByteArray(), channel.exitStatus)
            }
            val pollMillis = (remainingNanos() + 999_999L) / 1_000_000L
            Thread.sleep(minOf(TMUX_POLL_MILLIS, pollMillis))
        }
    } finally {
        scratch?.fill(0)
        runCatching { stdout?.close() }
        runCatching { stderr?.close() }
    }
}

private fun drainTmuxInput(
    input: InputStream,
    output: ByteArrayOutputStream,
    scratch: ByteArray,
    retain: Boolean,
    maximumOutputBytes: Int,
    checkDeadline: () -> Unit,
) {
    while (true) {
        checkDeadline()
        val available = input.available()
        if (available <= 0) return
        val count = input.read(scratch, 0, minOf(available, scratch.size))
        checkDeadline()
        if (count <= 0) return
        if (retain) {
            check(output.size() + count <= maximumOutputBytes) { "tmux output exceeded its limit." }
            output.write(scratch, 0, count)
        }
    }
}

private const val TMUX_READ_BUFFER_BYTES = 2 * 1024
private const val TMUX_POLL_MILLIS = 10L
private val DEFAULT_TMUX_EXEC_LIMITS = TmuxExecLimits(
    channelConnectTimeoutMillis = 5_000,
    totalTimeoutMillis = 5_000L,
    maximumOutputBytes = 64 * 1024,
)
internal val TMUX_HISTORY_EXEC_LIMITS = TmuxExecLimits(
    channelConnectTimeoutMillis = 10_000,
    totalTimeoutMillis = 30_000L,
    maximumOutputBytes = TmuxPaneCapture.MAX_CAPTURE_BYTES,
)
