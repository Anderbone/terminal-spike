package com.yanjiyu.terminalspike.testing

import com.yanjiyu.terminalspike.core.model.MoshPortRange
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendingFixtureCleanupTest {
    @Test fun failedStartupStopsObserversEvenWhenSuspendingStopFails() {
        var stopped = false
        val startup = IOException("start")
        val stop = IOException("stop")
        val caught = assertThrows(IOException::class.java) {
            runBlocking {
                withSuspendingFixtureCleanup { cleanup ->
                    val observer = async(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { stopped = true }
                    }
                    cleanup.onClose { observer.cancelAndJoin() }
                    cleanup.onClose { delay(1); throw stop }
                    throw startup
                }
            }
        }
        assertRetainsFailure(startup, caught)
        val cleanupFailures = causeChain(caught).flatMap { it.suppressed.toList() }.distinct()
        assertEquals(1, cleanupFailures.size)
        assertRetainsFailure(stop, cleanupFailures.single())
        assertTrue(stopped)
    }

    @Test fun cancellationStillFinishesSuspendingCleanupAndWipesSecrets() = runBlocking {
        val secret = byteArrayOf(1, 2)
        val calls = mutableListOf<Int>()
        val fixture = launch(start = CoroutineStart.UNDISPATCHED) {
            withSuspendingFixtureCleanup { cleanup ->
                cleanup.onClose { delay(1); secret.fill(0); calls += 1 }
                cleanup.onClose { delay(1); calls += 2 }
                awaitCancellation()
            }
        }
        fixture.cancelAndJoin()
        assertEquals(listOf(2, 1), calls)
        assertTrue(secret.all { it == 0.toByte() })
    }

    @Test fun oneFailedAttemptCannotSkipTheOtherAttemptOrKeyWipe() {
        val calls = mutableListOf<String>()
        val closeFailure = IOException("first attempt")
        val caught = assertThrows(IOException::class.java) {
            runBlocking {
                withSuspendingFixtureCleanup { cleanup ->
                    cleanup.onClose { delay(1); calls += "key wipe" }
                    cleanup.onClose { delay(1); calls += "recovered" }
                    cleanup.onClose { delay(1); calls += "first"; throw closeFailure }
                }
            }
        }
        assertRetainsFailure(closeFailure, caught)
        assertEquals(listOf("first", "recovered", "key wipe"), calls)
    }

    @Test fun invalidMoshPortSetupStillWipesTheAcquiredFixtureKey() {
        val secret = byteArrayOf(1, 2)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                withSuspendingFixtureCleanup { cleanup ->
                    cleanup.onClose { secret.fill(0) }
                    MoshPortRange(first = 60_001, last = 60_000)
                }
            }
        }
        assertTrue(secret.all { it == 0.toByte() })
    }
    private fun assertRetainsFailure(expected: Throwable, caught: Throwable) {
        assertEquals(expected.javaClass, caught.javaClass)
        assertEquals(expected.message, caught.message)
        assertTrue("Coroutine recovery must retain the original failure as a cause", causeChain(caught).any { it === expected })
    }

    private fun causeChain(failure: Throwable): List<Throwable> =
        generateSequence(failure as Throwable?) { it.cause }.toList()

}
