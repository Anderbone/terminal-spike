package com.yanjiyu.terminalspike.core.data.repository

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMutationCoordinatorTest {
    @Test
    fun settingsWriteWaitsForRollbackAndSurvivesIt() = runTest {
        val gate = readyGate()
        val settings = SettingsMutationCoordinator(gate)
        var stored = "original"
        val restoring = CompletableDeferred<Unit>()
        val rollback = CompletableDeferred<Unit>()
        val restore = launch {
            gate.withMutation {
                val snapshot = stored
                stored = "imported"
                restoring.complete(Unit)
                rollback.await()
                stored = snapshot
                markCommitted()
            }
        }
        restoring.await()
        val edit = async { settings.mutate { write { stored = "user edit" } } }
        runCurrent()
        val overlappedRestore = edit.isCompleted
        rollback.complete(Unit)
        restore.join()
        edit.await()

        assertFalse("Settings writes must wait for the entire restore/rollback interval", overlappedRestore)
        assertEquals("user edit", stored)
        assertEquals(2L, gate.awaitReady())
    }

    @Test
    fun firstCommitInvalidatesCatalogEvenWhenALaterStoreFails() = runTest {
        val gate = readyGate()
        val settings = SettingsMutationCoordinator(gate)
        var roomValue = "old"
        val failure = runCatching {
            settings.mutate {
                write { roomValue = "saved" }
                assertEquals(1L, gate.awaitReady())
                write { throw IOException("DataStore failed after Room committed") }
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("saved", roomValue)
        assertEquals(1L, gate.awaitReady())
    }

    @Test
    fun multipleWritesPublishOneGenerationButNoOpAndFailedWritesDoNot() = runTest {
        val gate = readyGate()
        val settings = SettingsMutationCoordinator(gate)
        settings.mutate { }
        runCatching { settings.mutate { write { throw IOException("not committed") } } }
        assertEquals(0L, gate.awaitReady())

        settings.mutate {
            write { Unit }
            write { Unit }
        }
        assertEquals(1L, gate.awaitReady())
    }

    @Test
    fun cancellationFinishesStartedCommitAndDoesNotStartTheNextWrite() = runTest {
        val gate = readyGate()
        val settings = SettingsMutationCoordinator(gate)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var committed = false
        var secondWriteStarted = false
        val edit = launch {
            settings.mutate {
                write {
                    started.complete(Unit)
                    finish.await()
                    committed = true
                }
                write { secondWriteStarted = true }
            }
        }
        started.await()
        edit.cancel()
        finish.complete(Unit)
        edit.join()

        assertTrue(committed)
        assertFalse(secondWriteStarted)
        assertEquals(1L, gate.awaitReady())
    }

    @Test
    fun failedStartupAndEscapedScopeCannotWrite() = runTest {
        val failedGate = AuthoritativeDataGate()
        failedGate.resolveStartup({ true }, { false })
        var writes = 0
        val failure = runCatching {
            SettingsMutationCoordinator(failedGate).mutate { write { writes++ } }
        }.exceptionOrNull()
        assertTrue(failure is AuthoritativeDataUnavailableException)
        assertEquals(0, writes)

        val ready = readyGate()
        lateinit var escaped: SettingsMutationScope
        SettingsMutationCoordinator(ready).mutate { escaped = this }
        val lateFailure = runCatching { escaped.write { writes++ } }.exceptionOrNull()
        assertTrue(lateFailure is IllegalStateException)
        assertEquals(0, writes)
        assertEquals(0L, ready.awaitReady())
    }

    @Test
    fun cancellationWhileWaitingForAuthorityDoesNotRunPersistence() = runTest {
        val gate = readyGate()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { gate.withRead { held.complete(Unit); release.await() } }
        held.await()
        var writes = 0
        val edit = launch { SettingsMutationCoordinator(gate).mutate { write { writes++ } } }
        runCurrent()
        edit.cancelAndJoin()
        release.complete(Unit)
        owner.join()
        assertEquals(0, writes)
        assertEquals(0L, gate.awaitReady())
    }

    private suspend fun readyGate() = AuthoritativeDataGate().also {
        it.resolveStartup({ false }, { error("No recovery expected") })
    }
}
