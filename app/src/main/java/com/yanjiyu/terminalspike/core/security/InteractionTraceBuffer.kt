package com.yanjiyu.terminalspike.core.security

import java.util.ArrayDeque

/** Numeric fields only: no terminal bytes, titles, addresses, paths or credentials. */
internal enum class InteractionTraceEvent(val fields: String) {
    START("version_code,pid"),
    ATTACH("connection,transport_ssh_1_mosh_2_other_0"),
    DETACH(""),
    TOUCH_DOWN("column,row,reader,selection"),
    HISTORY_TOUCH("action,reader,pinned,scroll_px"),
    TAP("column,row,autofollow,mouse"),
    CLICK_ATTEMPT("column,row,autofollow,mouse"),
    CLICK_REJECT_MOUSE(""),
    CLICK_REJECT_HISTORY("scroll_px,reader,pinned"),
    CLICK_REJECT_BOUNDS("columns,rows"),
    CLICK_SENT("accepted"),
    INPUT("bytes,accepted,autofollow,reader"),
    RECEIVED("bytes"),
    PARSED("resize,synchronized,mouse,alternate"),
    PUBLISHED("revision,scroll_px,autofollow,reader"),
    DRAW("revision,scroll_px,autofollow,flags_reader_1_retained_frame_2_selection_4"),
    WRITER_OFFER("bytes,accepted,queued_batches"),
    WRITE_START("bytes,queued_batches"),
    WRITE_END("bytes,duration_ms,queued_batches"),
    WRITE_FAILED(""),
    WRITER_STATUS("queued_batches,operation_idle_0_write_1_resize_2,operation_age_ms,last_complete_age_ms"),
    WRITER_STOP(""),
    RESIZE_START("columns,rows"),
    RESIZE_END("columns,rows"),
    MAIN_HEARTBEAT("main_delay_ms,dropped_events,io_failures"),
    EXPORT(""),
}

/** Bounded pending events; sampling is bounded separately so closed sessions cannot accumulate. */
internal class InteractionTraceBuffer(private val capacity: Int = 2_048) {
    private val pending = ArrayDeque<String>()
    private val samples = object : LinkedHashMap<Pair<InteractionTraceEvent, Int>, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<InteractionTraceEvent, Int>, Long>?) =
            size > 256
    }
    var droppedEvents = 0L
        private set

    init { require(capacity > 0) }

    @Synchronized
    fun record(
        event: InteractionTraceEvent,
        owner: Int,
        uptimeMillis: Long,
        wallMillis: Long,
        a: Long = 0,
        b: Long = 0,
        c: Long = 0,
        d: Long = 0,
        sampleMillis: Long = 0,
    ) {
        if (sampleMillis > 0) {
            val key = event to owner
            val last = samples[key]
            if (last != null && uptimeMillis - last < sampleMillis) return
            samples[key] = uptimeMillis
        }
        if (pending.size == capacity) {
            pending.removeFirst()
            droppedEvents++
        }
        pending.addLast("$wallMillis $uptimeMillis ${event.name} $owner $a $b $c $d")
    }

    @Synchronized
    fun drain(): List<String> = pending.toList().also { pending.clear() }
}
