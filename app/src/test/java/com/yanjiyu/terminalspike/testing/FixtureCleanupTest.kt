package com.yanjiyu.terminalspike.testing

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureCleanupTest {
    @Test fun startupFailureCancelsAnAlreadySubscribedObserver() = runBlocking {
        var observerStopped = false
        val observer = async(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { observerStopped = true }
        }
        val startupFailure = IOException("start failed")
        val caught = assertThrows(IOException::class.java) {
            FixtureCleanup().use { cleanup ->
                cleanup.onClose { observer.cancel() }
                throw startupFailure
            }
        }
        withTimeout(1_000) { observer.join() }
        assertSame(startupFailure, caught)
        assertTrue(observerStopped)
    }

    @Test fun allResourcesCloseInReverseOrderEvenWhenCleanupFails() {
        val calls = mutableListOf<Int>()
        val first = IOException("first")
        val second = IOException("second")
        val cleanup = FixtureCleanup().apply {
            onClose { calls += 1 }
            onClose { calls += 2; throw second }
            onClose { calls += 3; throw first }
        }
        assertSame(first, assertThrows(IOException::class.java) { cleanup.close() })
        assertEquals(listOf(3, 2, 1), calls)
        assertEquals(listOf(second), first.suppressed.toList())
        cleanup.close()
        assertEquals(listOf(3, 2, 1), calls)
    }

    @Test fun setupFailureRemainsPrimaryAndSecretsAreWipedWhenCleanupAlsoFails() {
        val secret = byteArrayOf(1, 2, 3)
        val setupFailure = IOException("setup")
        val closeFailure = IOException("close")
        val caught = assertThrows(IOException::class.java) {
            FixtureCleanup().use { cleanup ->
                cleanup.onClose { secret.fill(0) }
                cleanup.onClose { throw closeFailure }
                throw setupFailure
            }
        }
        assertSame(setupFailure, caught)
        assertEquals(listOf(closeFailure), caught.suppressed.toList())
        assertTrue(secret.all { it == 0.toByte() })
    }
}
