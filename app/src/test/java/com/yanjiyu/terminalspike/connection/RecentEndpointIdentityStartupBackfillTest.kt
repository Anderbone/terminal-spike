package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityBackfillResult
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityGenerationChangedException
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityKeyState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentEndpointIdentityStartupBackfillTest {
    @Test
    fun repeatsBoundedBatchesUntilBackfillIsComplete() = runTest {
        var calls = 0

        val result = runRecentEndpointIdentityStartupBackfill(
            batchSize = 2,
            maximumBatches = 4,
            backfill = { limit ->
                assertEquals(2, limit)
                calls++
                RecentEndpointIdentityBackfillResult(
                    candidatesScanned = if (calls == 1) 2 else 1,
                    tokensStored = if (calls == 1) 2 else 1,
                    hasMore = calls == 1,
                )
            },
            resetGeneration = { error("Generation reset was not expected.") },
        )

        assertTrue(result.complete)
        assertEquals(2, result.batchesAttempted)
        assertEquals(3, result.candidatesScanned)
        assertEquals(3, result.tokensStored)
    }

    @Test
    fun generationChangeResetsThenRetriesWithinTheSameBound() = runTest {
        var calls = 0
        var resets = 0

        val result = runRecentEndpointIdentityStartupBackfill(
            batchSize = 8,
            maximumBatches = 3,
            backfill = {
                calls++
                if (calls == 1) {
                    throw RecentEndpointIdentityGenerationChangedException(
                        RecentEndpointIdentityKeyState.CREATED,
                    )
                }
                RecentEndpointIdentityBackfillResult(1, 1, hasMore = false)
            },
            resetGeneration = { resets++ },
        )

        assertTrue(result.complete)
        assertEquals(1, resets)
        assertEquals(2, result.batchesAttempted)
        assertEquals(1, result.tokensStored)
    }

    @Test
    fun persistentWorkStopsAtTheConfiguredBatchBound() = runTest {
        var calls = 0

        val result = runRecentEndpointIdentityStartupBackfill(
            batchSize = 1,
            maximumBatches = 2,
            backfill = {
                calls++
                RecentEndpointIdentityBackfillResult(1, 1, hasMore = true)
            },
            resetGeneration = { error("Generation reset was not expected.") },
        )

        assertFalse(result.complete)
        assertEquals(2, calls)
        assertEquals(2, result.tokensStored)
    }
}
