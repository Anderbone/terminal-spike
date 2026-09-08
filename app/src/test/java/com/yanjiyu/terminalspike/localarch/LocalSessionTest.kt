package com.yanjiyu.terminalspike.localarch

import com.yanjiyu.terminalspike.connection.ConnectionState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionTest {
    @Test
    fun closeDuringLaunchReapsTheNewChildWithoutPublishingConnected() = runBlocking {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = ProbePty()
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val session = LocalSession { _, _ ->
            starting.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            process
        }
        val job = launch(Dispatchers.IO) { session.connect(80, 24, {}, states::add) }
        try {
            assertTrue(starting.await(5, TimeUnit.SECONDS))
            session.close()
        } finally {
            release.countDown()
        }
        withTimeout(5_000) { job.join() }
        assertEquals(1, process.closes.get())
        assertFalse(states.contains(ConnectionState.Connected))
        assertEquals(ConnectionState.Disconnected, states.last())
        assertFalse(session.trySend(byteArrayOf(65)))
    }

    @Test
    fun bytesAndExitStatusAreDeliveredThroughTheConnectionContract() = runBlocking {
        val output = byteArrayOf(27, 91, 51, 49, 109, 65, 13, 10)
        val states = mutableListOf<ConnectionState>()
        val received = mutableListOf<ByteArray>()
        val process = ProbePty(output, 7)
        val session = LocalSession { columns, rows ->
            assertEquals(90, columns)
            assertEquals(30, rows)
            process
        }
        session.connect(90, 30, received::add, states::add)
        assertTrue(output.contentEquals(received.single()))
        assertEquals(ConnectionState.Connecting, states.first())
        assertEquals(ConnectionState.Connected, states[1])
        assertTrue(states.last() is ConnectionState.Failed)
        assertEquals(1, states.count { it is ConnectionState.Failed || it is ConnectionState.Disconnected })
        assertEquals(1, process.closes.get())
        session.close()
        assertEquals(1, process.closes.get())
    }

    @Test
    fun cancelledLaunchStillClosesAChildThatArrivesAfterCancellation() = runBlocking {
        val starting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = ProbePty()
        val session = LocalSession { _, _ ->
            starting.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            process
        }
        val job = launch(Dispatchers.IO) { session.connect(80, 24, {}, {}) }
        try {
            assertTrue(starting.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(5_000) { job.join() }
        assertEquals(1, process.closes.get())
        assertFalse(session.trySend(byteArrayOf(65)))
    }

    private class ProbePty(private var output: ByteArray? = null, private val code: Int = 0) : LocalPty {
        val closes = AtomicInteger()
        override fun read(buffer: ByteArray): Int {
            val bytes = output ?: return -1
            bytes.copyInto(buffer)
            output = null
            return bytes.size
        }
        override fun write(bytes: ByteArray) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun exitCode(): Int = code
        override fun close() { closes.incrementAndGet() }
    }
}
