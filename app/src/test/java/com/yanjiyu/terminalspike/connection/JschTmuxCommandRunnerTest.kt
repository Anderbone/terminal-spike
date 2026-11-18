package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelExec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JschTmuxCommandRunnerTest {
    @Test fun completeStdoutAndExitStatusSurviveChunkingAndDiscardedWarnings() {
        val bytes = (1..200).joinToString("\n", postfix = "\n") { "EXEC_ROW_%03d 界🚀".format(it) }.toByteArray()
        for (chunk in listOf(1, 7, 4_096)) {
            val stdout = TrackedInput(bytes, chunk)
            val stderr = TrackedInput("warning\n".repeat(2_000).toByteArray())
            val channel = FakeChannel(stdout, stderr).apply { status = 17 }
            val result = execute(channel, maximumOutputBytes = bytes.size)
            assertArrayEquals(bytes, result.stdout)
            assertEquals(17, result.exitStatus)
            assertEquals("owned-command", channel.commandText)
            assertEquals(false, channel.ptyEnabled)
            assertTrue(channel.stdinWasCleared)
            assertReleased(channel, stdout, stderr)
        }
    }

    @Test fun stdoutGetterFailureStillDisconnectsTheAcquiredChannel() {
        val failure = IOException("owned stdout setup failure")
        val channel = FakeChannel().apply { stdoutFailure = failure }
        assertSame(failure, assertThrows(IOException::class.java) { execute(channel) })
        assertEquals(1, channel.disconnects)
    }

    @Test fun stderrGetterFailureClosesTheAlreadyAcquiredStdout() {
        val stdout = TrackedInput(byteArrayOf())
        val failure = IOException("owned stderr setup failure")
        val channel = FakeChannel(stdout).apply { stderrFailure = failure }
        assertSame(failure, assertThrows(IOException::class.java) { execute(channel) })
        assertTrue(stdout.closed)
        assertEquals(1, channel.disconnects)
    }

    @Test fun commandConfigurationFailureStillDisconnects() {
        val failure = IllegalArgumentException("owned command setup failure")
        val channel = FakeChannel().apply { commandFailure = failure }
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { execute(channel) })
        assertEquals(1, channel.disconnects)
    }

    @Test fun connectFailureClosesBothStreamsAndDisconnects() {
        val stdout = TrackedInput(byteArrayOf())
        val stderr = TrackedInput(byteArrayOf())
        val failure = IOException("owned connect failure")
        val channel = FakeChannel(stdout, stderr).apply { onConnect = { throw failure } }
        assertSame(failure, assertThrows(IOException::class.java) { execute(channel) })
        assertReleased(channel, stdout, stderr)
    }

    @Test fun continuouslyAvailableStdoutCannotBypassTheDeadline() {
        val clock = Clock()
        val stdout = BusyInput { clock.now += 1_000_000L }
        val stderr = TrackedInput(byteArrayOf())
        val channel = FakeChannel(stdout, stderr)
        val failure = assertThrows(IllegalStateException::class.java) { execute(channel, clock) }
        assertEquals("tmux command timed out.", failure.message)
        assertTrue(stdout.reads <= 5)
        assertTrue(stdout.closed)
        assertTrue(stderr.closed)
        assertEquals(1, channel.disconnects)
    }

    @Test fun continuouslyAvailableDiscardedStderrCannotBypassTheDeadline() {
        val clock = Clock()
        val stdout = TrackedInput(byteArrayOf())
        val stderr = BusyInput { clock.now += 1_000_000L }
        val channel = FakeChannel(stdout, stderr)
        val failure = assertThrows(IllegalStateException::class.java) { execute(channel, clock) }
        assertEquals("tmux command timed out.", failure.message)
        assertTrue(stderr.reads <= 5)
        assertTrue(stdout.closed)
        assertTrue(stderr.closed)
        assertEquals(1, channel.disconnects)
    }

    @Test fun interruptionInsideDiscardedStderrStopsWithoutWaitingForItsEnd() {
        val stdout = TrackedInput(byteArrayOf())
        val stderr = BusyInput { Thread.currentThread().interrupt() }
        val channel = FakeChannel(stdout, stderr)
        try {
            assertThrows(InterruptedException::class.java) { execute(channel) }
            assertEquals(1, stderr.reads)
            assertTrue(stdout.closed)
            assertTrue(stderr.closed)
            assertEquals(1, channel.disconnects)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun aClosedChannelCannotReturnOutputAfterTheDeadline() {
        val clock = Clock()
        val stdout = TrackedInput("late".toByteArray()).apply { onRead = { clock.now = 5_000_000L } }
        val stderr = TrackedInput(byteArrayOf())
        val channel = FakeChannel(stdout, stderr)
        assertThrows(IllegalStateException::class.java) { execute(channel, clock) }
        assertReleased(channel, stdout, stderr)
    }

    @Test fun connectTimeoutIsCappedByTheRemainingTotalBudget() {
        val channel = FakeChannel()
        execute(channel)
        assertEquals(5, channel.requestedConnectTimeout)
    }

    @Test fun aSubMillisecondRemainderNeverRequestsAnInfiniteConnectTimeout() {
        val clock = Clock()
        val channel = FakeChannel().apply { onStderr = { clock.now = 4_500_000L } }
        execute(channel, clock)
        assertEquals(1, channel.requestedConnectTimeout)
    }

    @Test fun connectCompletionAfterTheTotalBudgetIsRejectedEvenWhenClosed() {
        val clock = Clock()
        val channel = FakeChannel().apply { onConnect = { clock.now = 6_000_000L } }
        assertThrows(IllegalStateException::class.java) { execute(channel, clock) }
        assertEquals(1, channel.disconnects)
    }

    @Test fun monotonicClockWrapDoesNotCreateAPrematureTimeout() {
        val clock = Clock(Long.MAX_VALUE - 1_000_000L)
        var checks = 0
        val channel = FakeChannel().apply {
            closed = {
                checks += 1
                check(checks <= 2) { "Owned finite close guard" }
                clock.now += if (checks == 1) 500_000L else 1_000_000L
                checks == 2
            }
        }
        assertArrayEquals(byteArrayOf(), execute(channel, clock).stdout)
        assertEquals(2, checks)
        assertEquals(1, channel.disconnects)
    }

    @Test fun scratchIsWipedOnSuccessAndOutputLimitFailure() {
        for (limit in listOf(3, 4)) {
            val stdout = TrackedInput("data".toByteArray())
            val stderr = TrackedInput(byteArrayOf())
            val channel = FakeChannel(stdout, stderr)
            if (limit == 3) assertThrows(IllegalStateException::class.java) { execute(channel, maximumOutputBytes = limit) }
            else assertArrayEquals("data".toByteArray(), execute(channel, maximumOutputBytes = limit).stdout)
            assertTrue(requireNotNull(stdout.lastBuffer).all { it == 0.toByte() })
            assertReleased(channel, stdout, stderr)
        }
    }

    @Test fun oneStreamCloseFailureDoesNotSkipOtherCleanup() {
        val stdout = TrackedInput("ok".toByteArray()).apply { closeFailure = IOException("owned close failure") }
        val stderr = TrackedInput(byteArrayOf())
        val channel = FakeChannel(stdout, stderr)
        assertArrayEquals("ok".toByteArray(), execute(channel).stdout)
        assertReleased(channel, stdout, stderr)
    }

    @Test fun disconnectFailureDoesNotReplaceTheOriginalReadFailure() {
        val failure = IOException("owned read failure")
        val stdout = TrackedInput("x".toByteArray()).apply { readFailure = failure }
        val stderr = TrackedInput(byteArrayOf())
        val cleanupFailure = IllegalStateException("owned disconnect failure")
        val channel = FakeChannel(stdout, stderr).apply { disconnectFailure = cleanupFailure }
        val caught = assertThrows(IOException::class.java) { execute(channel) }
        assertSame(failure, caught)
        assertTrue(caught.suppressed.contains(cleanupFailure))
        assertReleased(channel, stdout, stderr)
    }

    @Test fun aClockFailureDuringSetupStillDisconnects() {
        val failure = IOException("owned clock failure")
        val channel = FakeChannel()
        assertSame(failure, assertThrows(IOException::class.java) {
            runBoundedTmuxExec(channel, "owned-command", TmuxExecLimits(5_000, 5, 128)) { throw failure }
        })
        assertEquals(1, channel.disconnects)
    }

    private fun execute(channel: FakeChannel, clock: Clock = Clock(), maximumOutputBytes: Int = 64 * 1024) =
        runBoundedTmuxExec(channel, "owned-command", TmuxExecLimits(5_000, 5, maximumOutputBytes)) { clock.now }

    private fun assertReleased(channel: FakeChannel, stdout: TrackedInput, stderr: TrackedInput) {
        assertTrue(stdout.closed)
        assertTrue(stderr.closed)
        assertEquals(1, channel.disconnects)
    }

    private class Clock(var now: Long = 0L)

    private class FakeChannel(
        private val stdout: InputStream = TrackedInput(byteArrayOf()),
        private val stderr: InputStream = TrackedInput(byteArrayOf()),
    ) : ChannelExec() {
        var stdoutFailure: Throwable? = null
        var stderrFailure: Throwable? = null
        var commandFailure: Throwable? = null
        var disconnectFailure: Throwable? = null
        var onConnect: () -> Unit = {}
        var onStderr: () -> Unit = {}
        var closed: () -> Boolean = { true }
        var commandText = ""
        var ptyEnabled = true
        var stdinWasCleared = false
        var requestedConnectTimeout = -1
        var disconnects = 0
        var status = 0
        override fun setPty(enable: Boolean) { ptyEnabled = enable }
        override fun setInputStream(input: InputStream?) { stdinWasCleared = input == null }
        override fun setCommand(value: String) { commandFailure?.let { throw it }; commandText = value }
        override fun getInputStream(): InputStream { stdoutFailure?.let { throw it }; return stdout }
        override fun getExtInputStream(): InputStream { stderrFailure?.let { throw it }; onStderr(); return stderr }
        override fun connect(timeout: Int) { requestedConnectTimeout = timeout; onConnect() }
        override fun isClosed() = closed()
        override fun getExitStatus() = status
        override fun disconnect() { disconnects += 1; super.disconnect(); disconnectFailure?.let { throw it } }
    }

    private class TrackedInput(bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        var closed = false
        var onRead: () -> Unit = {}
        var readFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var lastBuffer: ByteArray? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            lastBuffer = buffer
            val count = super.read(buffer, offset, minOf(length, chunk))
            onRead()
            return count
        }
        override fun close() { closed = true; closeFailure?.let { throw it } }
    }

    private class BusyInput(private val onRead: () -> Unit) : InputStream() {
        var reads = 0
        var closed = false
        override fun available() = 1
        override fun read(): Int = error("Owned stream requires bulk reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads += 1
            check(reads <= 32) { "Owned finite busy-stream guard" }
            buffer[offset] = 'x'.code.toByte()
            onRead()
            return 1
        }
        override fun close() { closed = true }
    }
}
