package com.yanjiyu.terminalspike.terminal.model

class TerminalBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lines = arrayOfNulls<TerminalLine>(capacity)
    private var head = 0
    private var size = 0
    private var nextId = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun append(line: TerminalLine): TerminalLine {
        val stored = line.withId(nextId++)
        appendAssigned(stored)
        return stored
    }

    @Synchronized
    fun append(linesToAppend: List<TerminalLine>): List<TerminalLine> {
        if (linesToAppend.isEmpty()) return emptyList()
        val stored = ArrayList<TerminalLine>(linesToAppend.size)
        linesToAppend.forEach { line ->
            val assigned = line.withId(nextId++)
            appendAssigned(assigned)
            stored.add(assigned)
        }
        return stored
    }

    @Synchronized
    fun lineAt(index: Int): TerminalLine? {
        if (index !in 0 until size) return null
        return lines[(head + index) % capacity]
    }

    @Synchronized
    fun indexOfId(id: Long): Int? {
        val oldest = oldestLineId() ?: return null
        val candidate = id - oldest
        return if (candidate in 0 until size.toLong()) candidate.toInt() else null
    }

    @Synchronized
    fun clear() {
        repeat(size) { offset ->
            lines[(head + offset) % capacity] = null
        }
        head = 0
        size = 0
    }

    @Synchronized
    fun lineCount(): Int = size

    /** Immutable reference snapshot for transcript/search work off the renderer thread. */
    @Synchronized
    fun snapshot(): List<TerminalLine> = List(size) { offset ->
        requireNotNull(lines[(head + offset) % capacity])
    }

    @Synchronized
    fun oldestLineId(): Long? = if (size == 0) null else lines[head]?.id

    @Synchronized
    fun newestLineId(): Long? =
        if (size == 0) null else lines[(head + size - 1) % capacity]?.id

    private fun appendAssigned(line: TerminalLine) {
        if (size < capacity) {
            lines[(head + size) % capacity] = line
            size += 1
        } else {
            lines[head] = line
            head = (head + 1) % capacity
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 100_000
    }
}
