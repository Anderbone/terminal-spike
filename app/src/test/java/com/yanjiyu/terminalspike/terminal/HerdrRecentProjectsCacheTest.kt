package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.RecentHerdrProject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrRecentProjectsCacheTest {
    private val rows = listOf(RecentHerdrProject("/projects/one"), RecentHerdrProject("/projects/two"))

    @Test fun prefetchWarmsTheFirstOpenAndRepeatedOpensKeepRowsWhileRefreshing() = runTest {
        var reads = 0
        val cache = HerdrRecentProjectsCache({ reads++; rows }, backgroundScope)
        cache.refresh()
        runCurrent()
        assertEquals(rows, cache.state.value.projects)
        assertFalse(cache.state.value.refreshing)

        repeat(5) { cache.refresh(force = true) }
        assertEquals(rows, cache.state.value.projects)
        assertTrue(cache.state.value.refreshing)
        assertEquals(1, reads)
        runCurrent()
        assertEquals(2, reads)
        assertFalse(cache.state.value.refreshing)
    }

    @Test fun prefetchIsThrottledIncludingFailuresAndExplicitOpenCanRetryImmediately() = runTest {
        var reads = 0
        var now = 0L
        val cache = HerdrRecentProjectsCache({ reads++; null }, backgroundScope, { now })
        cache.refresh()
        runCurrent()
        assertNull(cache.state.value.projects)
        assertFalse(cache.state.value.refreshing)
        now = 29_999
        repeat(20) { cache.refresh() }
        runCurrent()
        assertEquals(1, reads)
        now = 30_000
        cache.refresh()
        runCurrent()
        assertEquals(2, reads)
        cache.refresh(force = true)
        runCurrent()
        assertEquals(3, reads)
    }

    @Test fun failedRefreshRetainsGoodRowsButSuccessfulEmptyReadClearsThem() = runTest {
        var result: List<RecentHerdrProject>? = rows
        var fail = false
        val cache = HerdrRecentProjectsCache({ if (fail) error("SSH unavailable") else result }, backgroundScope)
        cache.refresh()
        runCurrent()
        result = null
        cache.refresh(force = true)
        runCurrent()
        assertEquals(rows, cache.state.value.projects)
        fail = true
        cache.refresh(force = true)
        runCurrent()
        assertEquals(rows, cache.state.value.projects)
        assertFalse(cache.state.value.refreshing)
        fail = false
        result = emptyList()
        cache.refresh(force = true)
        runCurrent()
        assertEquals(emptyList<RecentHerdrProject>(), cache.state.value.projects)
    }

    @Test fun confirmedOpeningRemovesTheRowImmediatelyAndDiscardsAnOlderSnapshot() = runTest {
        var reads = 0
        lateinit var cache: HerdrRecentProjectsCache
        cache = HerdrRecentProjectsCache({
            reads++
            if (reads == 2) {
                // A blocking remote read may finish after another operation invalidates it.
                cache.projectOpened(rows.first().path)
                assertEquals(rows.drop(1), cache.state.value.projects)
                rows
            } else if (reads > 2) rows.drop(1) else rows
        }, backgroundScope)
        cache.refresh()
        runCurrent()
        cache.refresh(force = true)
        runCurrent()
        assertEquals(3, reads)
        assertEquals(rows.drop(1), cache.state.value.projects)
        assertFalse(cache.state.value.refreshing)
    }

    @Test fun detachClearsRowsAndRejectsLateResultsAndFurtherRequests() = runTest {
        var reads = 0
        lateinit var cache: HerdrRecentProjectsCache
        cache = HerdrRecentProjectsCache({
            reads++
            if (reads == 2) cache.close()
            rows
        }, backgroundScope)
        cache.refresh()
        runCurrent()
        assertEquals(rows, cache.state.value.projects)
        cache.refresh(force = true)
        runCurrent()
        cache.refresh(force = true)
        runCurrent()
        assertEquals(2, reads)
        assertNull(cache.state.value.projects)
        assertFalse(cache.state.value.refreshing)
    }

    @Test fun differentConnectionsNeverShareRows() = runTest {
        val first = HerdrRecentProjectsCache({ rows }, backgroundScope)
        first.refresh()
        runCurrent()
        first.close()
        val second = HerdrRecentProjectsCache({ rows.drop(1) }, backgroundScope)
        assertNull(second.state.value.projects)
        second.refresh()
        runCurrent()
        assertNull(first.state.value.projects)
        assertEquals(rows.drop(1), second.state.value.projects)
    }
}
