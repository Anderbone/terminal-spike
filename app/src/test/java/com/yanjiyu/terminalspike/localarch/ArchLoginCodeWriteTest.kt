package com.yanjiyu.terminalspike.localarch

import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchLoginCodeWriteTest {
    @Test
    fun successfulWriteReceivesTheWholeCodeAndWipesItsOwnedBuffer() = runTest {
        val bytes = "one-use-code\n".toByteArray()
        var received: ByteArray? = null
        val job = backgroundScope.launchArchLoginCodeWrite(bytes, StandardTestDispatcher(testScheduler)) {
            assertSame(bytes, it)
            received = it.copyOf()
        }
        assertFalse(job.isCompleted)
        runCurrent()
        job.join()

        assertArrayEquals("one-use-code\n".toByteArray(), received)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun failedWriteStillWipesItsOwnedBufferAndReportsTheOriginalFailure() = runTest {
        val expected = IOException("PTY fixture write failed")
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        val bytes = "one-use-code\n".toByteArray()
        try {
            val job = scope.launchArchLoginCodeWrite(bytes, StandardTestDispatcher(testScheduler)) { throw expected }
            runCurrent()
            job.join()
            assertEquals(listOf(expected), failures)
            assertTrue(bytes.all { it == 0.toByte() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationBeforeDispatchWipesTheCodeWithoutWriting() = runTest {
        val bytes = "queued-code\n".toByteArray()
        var writes = 0
        val job = backgroundScope.launchArchLoginCodeWrite(bytes, StandardTestDispatcher(testScheduler)) { writes += 1 }
        job.cancel()
        runCurrent()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, writes)
        assertTrue("Cancelled code must be wiped even when the worker never entered", bytes.all { it == 0.toByte() })
    }

    @Test
    fun alreadyCancelledScopeNeverWritesAndStillWipesTheCode() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        val bytes = "cancelled-scope-code\n".toByteArray()
        var writes = 0
        val job = scope.launchArchLoginCodeWrite(bytes, StandardTestDispatcher(testScheduler)) { writes += 1 }
        runCurrent()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, writes)
        assertTrue("A rejected write must release its code", bytes.all { it == 0.toByte() })
    }
}
