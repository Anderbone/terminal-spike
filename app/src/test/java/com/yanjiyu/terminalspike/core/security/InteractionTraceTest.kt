package com.yanjiyu.terminalspike.core.security

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InteractionTraceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun recordingStaysBoundedAndReportsDiscardedEvents() {
        val buffer = InteractionTraceBuffer(capacity = 3)
        repeat(10) { buffer.record(InteractionTraceEvent.INPUT, 42, it.toLong(), 100, a = it.toLong()) }
        val lines = buffer.drain()
        assertEquals(3, lines.size)
        assertEquals(7L, buffer.droppedEvents)
        assertEquals(listOf(7L, 8L, 9L), lines.map { it.split(' ')[4].toLong() })
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun transportSamplingDoesNotHideTapsOrAnotherSessionsProgress() {
        val buffer = InteractionTraceBuffer()
        buffer.record(InteractionTraceEvent.RECEIVED, 1, 0, 100, sampleMillis = 1_000)
        buffer.record(InteractionTraceEvent.RECEIVED, 1, 999, 101, sampleMillis = 1_000)
        buffer.record(InteractionTraceEvent.RECEIVED, 2, 999, 101, sampleMillis = 1_000)
        buffer.record(InteractionTraceEvent.CLICK_REJECT_HISTORY, 1, 999, 101)
        buffer.record(InteractionTraceEvent.RECEIVED, 1, 1_000, 102, sampleMillis = 1_000)
        val events = buffer.drain().map { it.split(' ')[2] }
        assertEquals(listOf("RECEIVED", "RECEIVED", "CLICK_REJECT_HISTORY", "RECEIVED"), events)
    }

    @Test
    fun concurrentTransportAndUiEventsRemainCompleteAndNumeric() {
        val buffer = InteractionTraceBuffer(capacity = 500)
        val executor = Executors.newFixedThreadPool(4)
        repeat(4) { owner ->
            executor.execute {
                repeat(100) { buffer.record(InteractionTraceEvent.INPUT, owner, 1, 2, it.toLong()) }
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        val lines = buffer.drain()
        assertEquals(400, lines.size)
        assertEquals(400, lines.toSet().size)
        assertTrue(lines.all { it.matches(Regex("\\d+ \\d+ INPUT \\d+ \\d+ 0 0 0")) })
        assertEquals(0L, buffer.droppedEvents)
    }

    @Test
    fun rotatingFilesStayBoundedAndSurviveReconnectOrProcessRestart() {
        val directory = temporary.newFolder()
        val store = InteractionTraceStore(directory, maxFileBytes = 32)
        store.append(listOf("before-reconnect"))
        val restarted = InteractionTraceStore(directory, maxFileBytes = 32)
        restarted.append(listOf("after-reconnect"))
        assertEquals("before-reconnect\nafter-reconnect\n", restarted.snapshot())
        restarted.append(listOf("newest-event-123"))
        assertTrue(restarted.snapshot().contains("after-reconnect"))
        assertTrue(restarted.snapshot().contains("newest-event-123"))
        assertFalse(restarted.snapshot().contains("before-reconnect"))
        assertEquals(2, requireNotNull(directory.listFiles()).size)
        assertTrue(requireNotNull(directory.listFiles()).all { it.length() <= 32 })
    }

    @Test
    fun cancelledPickerCannotRotateAwayAFrozenIncident() {
        val directory = temporary.newFolder()
        val store = InteractionTraceStore(directory, maxFileBytes = 16)
        store.append(listOf("stuck-event"))
        assertEquals("incident\nstuck-event\n", store.freezeIncident("incident\n"))
        repeat(10) { store.append(listOf("event-$it")) }
        assertEquals("incident\nstuck-event\n", InteractionTraceStore(directory, maxFileBytes = 16).readIncident())
        assertFalse(store.snapshot().contains("stuck-event"))
    }

    @Test
    fun offlineOutboxIsBoundedAndDoesNotChangeWhenANewIncidentIsCaptured() {
        val directory = temporary.newFolder()
        val store = InteractionTraceStore(directory)
        store.append(listOf("original"))
        store.freezeIncident("header\n")
        val id = store.queueIncident()
        store.append(listOf("later"))
        store.freezeIncident("new header\n")
        assertEquals("header\noriginal\n", File(directory, "outbox/$id.txt").readText())
        repeat(3) { store.queueIncident() }
        assertTrue(runCatching { store.queueIncident() }.isFailure)
        assertEquals(4, File(directory, "outbox").listFiles().orEmpty().count { it.extension == "txt" })
        assertEquals("header\noriginal\n", File(directory, "outbox/$id.txt").readText())
    }

    @Test
    fun acknowledgementsAreSpecificToEachQueuedReportAndSurviveRestart() {
        val directory = temporary.newFolder()
        val store = InteractionTraceStore(directory)
        store.freezeIncident("header\n")
        val first = store.queueIncident()
        val second = store.queueIncident()
        File(directory, "delivery").mkdirs()
        File(directory, "delivery/$first.status").writeText("$first\nanalysis_started\n")
        assertEquals("analysis_started", InteractionTraceStore(directory).deliveryStatus(first))
        assertEquals(null, store.deliveryStatus(second))
        File(directory, "delivery/$second.status").writeText("$first\nanalyzed\n")
        assertEquals(null, store.deliveryStatus(second))
    }
}
