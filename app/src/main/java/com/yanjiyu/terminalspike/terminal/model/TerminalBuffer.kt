package com.yanjiyu.terminalspike.terminal.model

class TerminalBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
    initialNextId: Long = 0L,
) {
    private val lines = arrayOfNulls<TerminalLine>(capacity)
    private var head = 0
    private var size = 0
    private var nextId = initialNextId
    private var nextPrependedId = initialNextId - 1L
    private var oldestRowOrdinal = initialNextId

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(initialNextId >= 0L) { "initialNextId must not be negative" }
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

    /** Prepends a bounded page while retaining the IDs of every row already in the buffer. */
    @Synchronized
    fun prepend(linesToPrepend: List<TerminalLine>): List<TerminalLine> {
        if (linesToPrepend.isEmpty()) return emptyList()
        if (size == 0) return append(linesToPrepend)
        require(linesToPrepend.size <= capacity - size) { "prepended page exceeds capacity" }
        val firstId = nextPrependedId - linesToPrepend.size + 1L
        require(firstId >= 0L) { "prepended page exhausted stable line IDs" }
        nextPrependedId = firstId - 1L
        head = Math.floorMod(head - linesToPrepend.size, capacity)
        val stored = ArrayList<TerminalLine>(linesToPrepend.size)
        linesToPrepend.forEachIndexed { index, line ->
            val assigned = line.withId(firstId + index)
            lines[(head + index) % capacity] = assigned
            stored += assigned
        }
        size += linesToPrepend.size
        oldestRowOrdinal -= linesToPrepend.size
        return stored
    }

    /**
     * Replaces a changing tail while retaining IDs for the unchanged prefix. Returns the number
     * of prefix rows evicted only when the reconciled result reaches [capacity].
     */
    @Synchronized
    fun replaceSuffix(fromIndex: Int, replacement: List<TerminalLine>): Int {
        require(fromIndex in 0..size) { "suffix index is outside the buffer" }
        require(replacement.size <= capacity) { "replacement exceeds capacity" }
        for (offset in fromIndex until size) {
            lines[(head + offset) % capacity] = null
        }
        size = fromIndex

        val droppedPrefixRows = (size + replacement.size - capacity).coerceAtLeast(0)
        repeat(droppedPrefixRows) { offset ->
            lines[(head + offset) % capacity] = null
        }
        head = (head + droppedPrefixRows) % capacity
        size -= droppedPrefixRows
        oldestRowOrdinal += droppedPrefixRows
        append(replacement)
        return droppedPrefixRows
    }

    @Synchronized
    fun lineAt(index: Int): TerminalLine? {
        if (index !in 0 until size) return null
        return lines[(head + index) % capacity]
    }

    @Synchronized
    fun indexOfId(id: Long): Int? {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val candidate = requireNotNull(lines[(head + middle) % capacity]).id
            when {
                candidate < id -> low = middle + 1
                candidate > id -> high = middle - 1
                else -> return middle
            }
        }
        return null
    }

    @Synchronized
    fun clear() {
        repeat(size) { offset ->
            lines[(head + offset) % capacity] = null
        }
        head = 0
        size = 0
        oldestRowOrdinal = nextId
    }

    @Synchronized
    fun lineCount(): Int = size

    /** Immutable reference snapshot for transcript/search work off the renderer thread. */
    @Synchronized
    fun snapshot(): List<TerminalLine> = List(size) { offset ->
        requireNotNull(lines[(head + offset) % capacity])
    }

    /**
     * Captures only the scalar allocation state needed to construct a replacement buffer.
     * Rows are copied incrementally by [TerminalBufferRebuilder], so the renderer thread never
     * has to materialize a full reference snapshot in one frame.
     */
    @Synchronized
    internal fun rebuildSeed(): TerminalBufferRebuildSeed = TerminalBufferRebuildSeed(
        capacity = capacity,
        nextId = nextId,
        nextPrependedId = nextPrependedId,
        oldestRowOrdinal = oldestRowOrdinal,
    )

    @Synchronized
    internal fun configureUnpublishedRebuild(
        rebuiltNextPrependedId: Long,
        rebuiltOldestRowOrdinal: Long,
    ) {
        check(size == 0) { "only an empty unpublished buffer can be configured" }
        nextPrependedId = rebuiltNextPrependedId
        oldestRowOrdinal = rebuiltOldestRowOrdinal
    }

    @Synchronized
    internal fun appendAssignedForRebuild(line: TerminalLine) {
        require(line.id >= 0L) { "rebuilt rows must already have stable IDs" }
        check(size < capacity) { "rebuilt buffer exceeds capacity" }
        lines[size] = line
        size += 1
    }

    @Synchronized
    fun oldestLineId(): Long? = if (size == 0) null else lines[head]?.id

    /** Monotonic row position used by the viewport independently of sparse stable selection IDs. */
    @Synchronized
    fun oldestRowOrdinal(): Long? = oldestRowOrdinal.takeIf { size > 0 }

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
            oldestRowOrdinal += 1L
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 100_000
    }
}

internal data class TerminalBufferRebuildSeed(
    val capacity: Int,
    val nextId: Long,
    val nextPrependedId: Long,
    val oldestRowOrdinal: Long,
)

/** Builds an unpublished buffer a bounded number of rows at a time. */
internal class TerminalBufferRebuilder(
    seed: TerminalBufferRebuildSeed,
    oldestRowOrdinal: Long,
    prependedRows: Int = 0,
) {
    private val target = TerminalBuffer(seed.capacity, seed.nextId)
    private val firstPrependedId = seed.nextPrependedId - prependedRows + 1L
    private var prependedCount = 0
    private var finished = false

    init {
        require(prependedRows >= 0) { "prepended row count must not be negative" }
        require(firstPrependedId >= 0L) { "prepended rows exhausted stable line IDs" }
        target.configureUnpublishedRebuild(
            rebuiltNextPrependedId = firstPrependedId - 1L,
            rebuiltOldestRowOrdinal = oldestRowOrdinal,
        )
    }

    fun appendPrepended(line: TerminalLine) {
        check(!finished) { "buffer rebuild is already finished" }
        appendAssigned(line.withId(firstPrependedId + prependedCount))
        prependedCount += 1
    }

    fun appendRetained(line: TerminalLine) {
        check(!finished) { "buffer rebuild is already finished" }
        require(line.id >= 0L) { "retained rows must already have stable IDs" }
        appendAssigned(line)
    }

    fun appendNew(line: TerminalLine) {
        check(!finished) { "buffer rebuild is already finished" }
        target.append(line)
    }

    fun finish(): TerminalBuffer {
        check(!finished) { "buffer rebuild is already finished" }
        finished = true
        return target
    }

    private fun appendAssigned(line: TerminalLine) {
        target.appendAssignedForRebuild(line)
    }
}
