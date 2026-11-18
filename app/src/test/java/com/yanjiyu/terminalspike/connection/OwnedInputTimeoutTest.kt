package com.yanjiyu.terminalspike.connection

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OwnedInputTimeoutTest {
    @Test
    fun aDeadlineClosesAStalledInputBeforeJoiningItsReader() {
        exerciseStalledReader(cancelParent = false)
    }

    @Test
    fun callerCancellationClosesAStalledInputBeforeJoiningItsReader() {
        exerciseStalledReader(cancelParent = true)
    }

    @Test
    fun successReturnsTheValueAndClosesTheOwnedInput() = runBlocking {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(42)) {
            override fun close() { closed = true; super.close() }
        }

        assertEquals(42, readOwnedInputWithTimeout(input, 1_000) { it.read() })
        assertTrue(closed)
    }

    @Test
    fun readFailureStaysPrimaryWhenClosingAlsoFails() {
        val readFailure = IOException("Owned read failure")
        val closeFailure = IOException("Owned close failure")
        val input = object : InputStream() {
            override fun read(): Int = throw readFailure
            override fun close(): Unit = throw closeFailure
        }

        val caught = assertThrows(IOException::class.java) {
            runBlocking { readOwnedInputWithTimeout(input, 1_000) { it.read() } }
        }

        // Coroutine debug stack recovery may copy the exception, retaining the original
        // as its cause. Both the original read failure and close failure must survive.
        val causes = generateSequence(caught as Throwable?) { it.cause }.toList()
        assertEquals(readFailure.message, caught.message)
        assertTrue(causes.any { it === readFailure })
        assertEquals(listOf(closeFailure), causes.flatMap { it.suppressed.toList() }.distinct())
    }

    private fun exerciseStalledReader(cancelParent: Boolean) {
        val input = BlockingInput()
        val parent = Job()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Throwable?> {
            try {
                runBlocking(parent) {
                    readOwnedInputWithTimeout(input, if (cancelParent) 30_000 else 1_000) { it.read() }
                }
                null
            } catch (error: Throwable) {
                error
            }
        }
        try {
            assertTrue("The owned reader must actually start", input.entered.await(2, TimeUnit.SECONDS))
            if (cancelParent) parent.cancel()
            val failure = try {
                result.get(5, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fail("The coroutine could not release its blocking owned input")
                null
            }
            assertTrue(failure is CancellationException)
            if (!cancelParent) assertTrue(failure is TimeoutCancellationException)
            assertTrue(input.closed.count == 0L)
            assertTrue(input.exited.await(1, TimeUnit.SECONDS))
        } finally {
            // Release even the broken baseline before interrupting/joining the owned caller.
            input.close()
            parent.cancel()
            result.cancel(true)
            executor.shutdownNow()
            assertTrue("The owned caller must be reaped", executor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue("The owned IO worker must leave read", input.exited.await(2, TimeUnit.SECONDS))
        }
    }

    private class BlockingInput : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            try {
                closed.await()
                return -1
            } finally {
                exited.countDown()
            }
        }

        override fun close() { closed.countDown() }
    }
}
