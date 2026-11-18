package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelCleanupTest {
    @Test
    fun successfulCleanupKeepsTheDeclaredOrder() {
        val events = mutableListOf<Int>()
        runViewModelCleanup({ events += 1 }, { events += 2 }, { events += 3 })
        assertEquals(listOf(1, 2, 3), events)
    }

    @Test
    fun aFailureAtAnyPositionStillAttemptsEveryOwnedCleanup() {
        for (failedIndex in 0..3) {
            val closed = BooleanArray(4)
            val failure = IllegalStateException("Owned close failure")
            val actions = Array<() -> Unit>(4) { index ->
                {
                    closed[index] = true
                    if (index == failedIndex) throw failure
                }
            }

            val caught = assertThrows(IllegalStateException::class.java) { runViewModelCleanup(*actions) }

            assertSame(failure, caught)
            assertArrayEquals(booleanArrayOf(true, true, true, true), closed)
        }
    }

    @Test
    fun multipleFailuresKeepTheFirstAndAttachLaterFailuresInOrder() {
        val first = IllegalStateException("First owned failure")
        val second = IllegalArgumentException("Second owned failure")
        val final = IllegalStateException("Final owned failure")
        val events = mutableListOf<Int>()

        val caught = assertThrows(IllegalStateException::class.java) {
            runViewModelCleanup(
                { events += 1; throw first },
                { events += 2; throw second },
                { events += 3 },
                { events += 4; throw final },
            )
        }

        assertSame(first, caught)
        assertEquals(listOf(1, 2, 3, 4), events)
        assertEquals(listOf(second, final), caught.suppressed.toList())
    }

    @Test
    fun repeatedFailureIdentityDoesNotPreventTheLastCleanup() {
        val failure = IllegalStateException("Shared owned failure")
        var finalClosed = false

        val caught = assertThrows(IllegalStateException::class.java) {
            runViewModelCleanup({ throw failure }, { throw failure }, { finalClosed = true })
        }

        assertSame(failure, caught)
        assertTrue(finalClosed)
        assertTrue(caught.suppressed.isEmpty())
    }
}
