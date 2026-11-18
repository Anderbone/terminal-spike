package com.yanjiyu.terminalspike.ui

import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshIdentityImportTaskTest {
    @Test
    fun successReleasesOnlyAfterImportCleanupAndExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val job = backgroundScope.launchSshIdentityImport(
            finished = { events += "released" },
            dispatcher = StandardTestDispatcher(testScheduler),
        ) {
            try { events += "imported" } finally { events += "wiped" }
        }
        assertTrue(events.isEmpty())
        runCurrent()
        job.join()
        assertEquals(listOf("imported", "wiped", "released"), events)
    }

    @Test
    fun failureReleasesExactlyOnceAndPreservesTheOriginalFailure() = runTest {
        val expected = IOException("fixture import failed")
        val failures = mutableListOf<Throwable>()
        var releases = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        try {
            val job = scope.launchSshIdentityImport({ releases++ }, StandardTestDispatcher(testScheduler)) {
                throw expected
            }
            runCurrent()
            job.join()
            assertEquals(listOf(expected), failures)
            assertEquals(1, releases)
        } finally { scope.cancel() }
    }

    @Test
    fun cancellationBeforeDispatchReleasesWithoutOpeningAnyInput() = runTest {
        var loads = 0
        var releases = 0
        val job = backgroundScope.launchSshIdentityImport({ releases++ }, StandardTestDispatcher(testScheduler)) {
            loads++
        }
        job.cancel()
        runCurrent()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, loads)
        assertEquals(1, releases)
    }

    @Test
    fun alreadyCancelledScopeStillReleasesWithoutOpeningAnyInput() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        var loads = 0
        var releases = 0
        val job = scope.launchSshIdentityImport({ releases++ }, StandardTestDispatcher(testScheduler)) { loads++ }
        runCurrent()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, loads)
        assertEquals(1, releases)
    }

    @Test
    fun cancellationDuringImportWaitsForSecretCleanupBeforeReleasing() = runTest {
        val bytes = "fixture key bytes".encodeToByteArray()
        var started = false
        var releases = 0
        val job = backgroundScope.launchSshIdentityImport(
            finished = { assertTrue(bytes.all { it == 0.toByte() }); releases++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        ) {
            started = true
            try { awaitCancellation() } finally { bytes.fill(0) }
        }
        runCurrent()
        assertTrue(started)
        assertEquals(0, releases)
        assertFalse(bytes.all { it == 0.toByte() })
        job.cancel()
        runCurrent()
        job.join()
        assertEquals(1, releases)
    }

    @Test
    fun inlineCompletionStillReleasesExactlyOnce() = runTest {
        var loads = 0
        var releases = 0
        val job = backgroundScope.launchSshIdentityImport({ releases++ }, UnconfinedTestDispatcher(testScheduler)) {
            loads++
        }
        job.join()
        assertEquals(1, loads)
        assertEquals(1, releases)
        assertTrue(job.isCompleted)
    }
}
