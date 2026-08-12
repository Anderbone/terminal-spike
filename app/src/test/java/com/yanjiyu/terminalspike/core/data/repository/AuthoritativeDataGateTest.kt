package com.yanjiyu.terminalspike.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeDataGateTest {
    @Test
    fun readWaitsUntilStartupBecomesReady() = runTest {
        val gate = AuthoritativeDataGate()
        val read = async { gate.withRead { generation -> generation } }

        runCurrent()
        assertFalse(read.isCompleted)

        gate.resolveStartup(
            hasPendingRecovery = { false },
            recoverPending = { error("Recovery must not run without a marker.") },
        )

        assertEquals(0L, read.await())
        assertEquals(AuthoritativeDataState.Ready(0L), gate.state.value)
    }

    @Test
    fun readWaitsForPendingRecoveryAndObservesItsPublishedGeneration() = runTest {
        val gate = AuthoritativeDataGate()
        val recoveryStarted = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        val startup = launch {
            gate.resolveStartup(
                hasPendingRecovery = { true },
                recoverPending = {
                    recoveryStarted.complete(Unit)
                    finishRecovery.await()
                    true
                },
            )
        }
        recoveryStarted.await()
        assertEquals(AuthoritativeDataState.RecoveryRequired, gate.state.value)

        val read = async { gate.withRead { generation -> generation } }
        runCurrent()
        assertFalse(read.isCompleted)

        finishRecovery.complete(Unit)
        startup.join()

        assertEquals(1L, read.await())
        assertEquals(AuthoritativeDataState.Ready(1L), gate.state.value)
    }

    @Test
    fun mutationPublishesOneGenerationAndCannotCommitAgainOrAfterRelease() = runTest {
        val gate = readyGate()
        lateinit var escapedMutation: AuthoritativeDataGate.Mutation

        val committedGeneration = gate.withMutation {
            escapedMutation = this
            assertEquals(0L, generation)
            val committed = markCommitted()
            assertEquals(1L, generation)
            assertThrows(IllegalStateException::class.java) { markCommitted() }
            committed
        }

        assertEquals(1L, committedGeneration)
        assertEquals(1L, gate.awaitReady())
        assertThrows(IllegalStateException::class.java) { escapedMutation.markCommitted() }
        assertThrows(IllegalStateException::class.java) { escapedMutation.requireRecovery() }
        assertEquals(1L, gate.awaitReady())
    }

    @Test
    fun failureAndCancellationBeforeCommitDoNotAdvanceGeneration() = runTest {
        val gate = readyGate()
        val expected = ExpectedFailure()

        val actual = gate.runCatching {
            withMutation { throw expected }
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(0L, gate.awaitReady())

        val entered = CompletableDeferred<Unit>()
        val cancelled = launch {
            gate.withMutation {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        cancelled.cancelAndJoin()

        assertTrue(cancelled.isCancelled)
        assertEquals(0L, gate.awaitReady())
    }

    @Test
    fun mutationCannotReopenReadyAfterRequiringRecovery() = runTest {
        val gate = readyGate()

        gate.withMutation {
            requireRecovery()
            assertThrows(IllegalStateException::class.java) { markCommitted() }
        }

        assertEquals(AuthoritativeDataState.RecoveryRequired, gate.state.value)
    }

    @Test
    fun concurrentMutationCallersSerialize() = runTest {
        val gate = readyGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val entryOrder = mutableListOf<String>()
        val first = async {
            gate.withMutation {
                entryOrder += "first"
                firstEntered.complete(Unit)
                releaseFirst.await()
                markCommitted()
            }
        }
        firstEntered.await()
        val second = async {
            gate.withMutation {
                entryOrder += "second"
                markCommitted()
            }
        }

        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(listOf("first"), entryOrder)

        releaseFirst.complete(Unit)

        assertEquals(1L, first.await())
        assertEquals(2L, second.await())
        assertEquals(listOf("first", "second"), entryOrder)
    }

    @Test
    fun readCannotOverlapMutationAndSeesItsCommittedGeneration() = runTest {
        val gate = readyGate()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = async {
            gate.withMutation {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                markCommitted()
            }
        }
        mutationEntered.await()
        val read = async { gate.withRead { generation -> generation } }

        runCurrent()
        assertFalse(read.isCompleted)

        releaseMutation.complete(Unit)

        assertEquals(1L, mutation.await())
        assertEquals(1L, read.await())
    }

    private suspend fun readyGate(): AuthoritativeDataGate = AuthoritativeDataGate().also { gate ->
        gate.resolveStartup(
            hasPendingRecovery = { false },
            recoverPending = { error("Recovery must not run without a marker.") },
        )
    }

    private class ExpectedFailure : RuntimeException()
}
