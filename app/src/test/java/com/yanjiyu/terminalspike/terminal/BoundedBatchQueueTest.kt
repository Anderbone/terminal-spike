package com.yanjiyu.terminalspike.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedBatchQueueTest {
    @Test
    fun queueIsBoundedAndReportsDroppedItems() {
        val queue = BoundedBatchQueue<Int>(3)

        val dropped = queue.addAll(listOf(1, 2, 3, 4, 5))

        assertEquals(2, dropped)
        assertEquals(3, queue.size)
        assertEquals(listOf(3, 4, 5), queue.drain(10))
    }

    @Test
    fun drainCoalescesUpToFrameLimit() {
        val queue = BoundedBatchQueue<Int>(10)
        queue.addAll(0..7)

        assertEquals(listOf(0, 1, 2), queue.drain(3))
        assertEquals(5, queue.size)
        queue.clear()
        assertTrue(queue.isEmpty)
    }
}
