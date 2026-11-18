package com.yanjiyu.terminalspike.connection

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only an owned Android pipe; no Mosh server, app preferences or user connection. */
@RunWith(AndroidJUnit4::class)
class OwnedInputTimeoutInstrumentedTest {
    @Test(timeout = 15_000)
    fun stalledAndroidPipeDeadlineClosesReaderAndLeavesWriterOpen() {
        exercisePipe(cancelParent = false)
    }

    @Test(timeout = 15_000)
    fun cancellingAndroidPipeReadClosesReaderBeforeJoiningChild() {
        exercisePipe(cancelParent = true)
    }

    private fun exercisePipe(cancelParent: Boolean) {
        val pipe = ParcelFileDescriptor.createPipe()
        val pipeInput = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
        val writer = pipe[1]
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                try {
                    return pipeInput.read()
                } finally {
                    exited.countDown()
                }
            }

            override fun close() {
                pipeInput.close()
                closed.set(true)
            }
        }
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
            assertTrue("The actual PFD read must start", entered.await(2, TimeUnit.SECONDS))
            if (cancelParent) parent.cancel()
            val failure = try {
                result.get(5, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fail("The owned Android pipe reader did not leave its blocking read")
                null
            }
            assertTrue(failure is CancellationException)
            if (cancelParent) assertFalse(failure is TimeoutCancellationException)
            else assertTrue(failure is TimeoutCancellationException)
            assertTrue("The input owner must close the PFD", closed.get())
            assertTrue("The writer must remain open during this proof", writer.fileDescriptor.valid())
            assertTrue("The actual read must have exited", exited.await(1, TimeUnit.SECONDS))
        } finally {
            // Closing the writer first also releases the broken baseline, so failed
            // assertions cannot strand the owned caller or an IO worker in a read.
            try {
                writer.close()
            } finally {
                try {
                    input.close()
                } finally {
                    parent.cancel()
                    result.cancel(true)
                    executor.shutdownNow()
                    assertTrue("The owned caller must be reaped", executor.awaitTermination(2, TimeUnit.SECONDS))
                    assertTrue("The owned IO worker must exit", exited.await(2, TimeUnit.SECONDS))
                }
            }
        }
    }
}
