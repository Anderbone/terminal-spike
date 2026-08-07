package com.yanjiyu.terminalspike.terminal

class BoundedBatchQueue<T>(
    private val capacity: Int,
) {
    private val queue = ArrayDeque<T>(capacity.coerceAtMost(256))

    init {
        require(capacity > 0)
    }

    val size: Int get() = queue.size
    val isEmpty: Boolean get() = queue.isEmpty()

    fun addAll(items: Iterable<T>): Int {
        var dropped = 0
        items.forEach { item ->
            if (queue.size == capacity) {
                queue.removeFirst()
                dropped += 1
            }
            queue.addLast(item)
        }
        return dropped
    }

    fun drain(maxItems: Int): List<T> {
        val count = minOf(maxItems, queue.size)
        if (count == 0) return emptyList()
        return ArrayList<T>(count).also { result ->
            repeat(count) { result.add(queue.removeFirst()) }
        }
    }

    fun clear() = queue.clear()
}
