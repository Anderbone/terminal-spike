package com.yanjiyu.terminalspike.terminal

import android.view.Choreographer
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.DefaultSshSessionTerminalFactory
import com.yanjiyu.terminalspike.connection.HerdrHistoryRead
import com.yanjiyu.terminalspike.connection.HerdrPaneArea
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalControllerHerdrPublicationTest {
    @Test fun acceptedNavigationRejectsReaderFlagsCapturedByAnEarlierAccessibilityAction() {
        Fixture().use { fixture ->
            fixture.controller.herdrHistoryReading = true
            fixture.controller.herdrHistoryPinned = true
            val action = fixture.controller.captureHerdrHistoryState()
            assertTrue(action.reading)
            // This is the exact action interval: eligibility was captured, then genuine navigation
            // invalidated ownership before the UI could finish publishing its moved local reader.
            fixture.clickTab()
            assertFalse(fixture.controller.tryPublishHerdrReaderState(action.generation, reading = true, pinned = true))
            val current = fixture.controller.captureHerdrHistoryState()
            assertNull(current.source)
            assertFalse(current.reading || current.pinned)
            assertEquals(action.generation + 1, current.generation)
        }
    }

    @Test fun currentGenerationPublishesBothReaderFlagsAndOldActionCannotClearTheReplacement() {
        Fixture().use { fixture ->
            val oldAction = fixture.controller.captureHerdrHistoryState()
            fixture.clickTab()
            fixture.controller.publishHerdrHistory(fixture.oldHistory.copy(identity = "replacement-pane"))
            val replacement = fixture.controller.captureHerdrHistoryState()
            assertTrue(fixture.controller.tryPublishHerdrReaderState(replacement.generation, reading = true, pinned = true))
            assertFalse(fixture.controller.tryPublishHerdrReaderState(oldAction.generation, reading = false, pinned = false))
            val pinned = fixture.controller.captureHerdrHistoryState()
            assertTrue(pinned.reading && pinned.pinned)
            assertSame(replacement.source, pinned.source)
            assertTrue(fixture.controller.tryPublishHerdrReaderState(replacement.generation, reading = true, pinned = false))
            assertTrue(fixture.controller.herdrHistoryReading)
            assertFalse(fixture.controller.herdrHistoryPinned)
        }
    }

    @Test fun acceptedNavigationCannotBeUndoneByHistoryAlreadyPastItsGenerationCheck() {
        Fixture().use { fixture ->
            val barrier = Barrier()
            fixture.pauseHistoryProjection(barrier)
            fixture.runPausedPoll("refreshHerdrHistory", barrier) { fixture.clickTab() }
            assertNull("The invalidated pane must stay released after the old poll finishes", fixture.controller.herdrHistory)
        }
    }

    @Test fun acceptedNavigationRejectsContextWithoutContaminatingTheEffortObservation() {
        Fixture().use { fixture ->
            val barrier = Barrier()
            val observation = CodexEffortObservation {
                barrier.pausePollOnce()
                System.nanoTime()
            }
            fixture.controller.field("codexEffortObservation").set(fixture.controller, observation)
            fixture.runPausedPoll("refreshInputContext", barrier) { fixture.clickTab() }
            assertSame("An old-generation context cannot replace the current metadata", fixture.sentinel,
                fixture.controller.inputContext.value)
            fixture.controller.publishInputContext(fixture.lateContext.copy(reasoningEffort = null))
            assertNull("Rejected confirmation must not seed smoothing for the next accepted sample",
                fixture.controller.inputContext.value.reasoningEffort)
        }
    }

    @Test fun historyProjectionCannotPublishAfterOnlyTheReaderPinStateChanges() {
        Fixture().use { fixture ->
            val barrier = Barrier()
            fixture.controller.herdrHistoryReading = true
            fixture.controller.herdrHistoryPinned = false
            fixture.pauseHistoryProjection(barrier)
            fixture.runPausedPoll("refreshHerdrHistory", barrier) {
                fixture.controller.herdrHistoryPinned = true
                assertSame(fixture.oldHistory, fixture.controller.herdrHistory)
                assertTrue(fixture.controller.herdrHistoryReading)
            }
            assertTrue("A read captured in holding-latest mode must retry after the reader pins",
                fixture.oldHistory === fixture.controller.herdrHistory)
            assertTrue(fixture.controller.herdrHistoryReading && fixture.controller.herdrHistoryPinned)
        }
    }

    @Test fun historyProjectionCannotReplaceASourceAdoptedWhileItWasComputing() {
        Fixture().use { fixture ->
            val barrier = Barrier()
            fixture.pauseHistoryProjection(barrier)
            val newer = fixture.oldHistory.copy(revision = "newer-reader-source")
            fixture.runPausedPoll("refreshHerdrHistory", barrier) {
                fixture.controller.publishHerdrHistory(newer)
                fixture.controller.herdrHistoryReading = true
                fixture.controller.herdrHistoryPinned = true
            }
            assertSame("The newer reader source owns publication until a fresh poll", newer, fixture.controller.herdrHistory)
            val state = fixture.controller.captureHerdrHistoryState()
            assertSame(newer, state.source)
            assertTrue(state.reading && state.pinned)
        }
    }

    private class Barrier {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val paused = AtomicBoolean()
        fun pausePollOnce() {
            if (Thread.currentThread().name == POLL_THREAD && paused.compareAndSet(false, true)) {
                entered.countDown()
                assertTrue("Poll barrier must be released", release.await(5, TimeUnit.SECONDS))
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val scheduler = Scheduler()
        val controller = TerminalController(TerminalBuffer(), scheduler)
        val sentinel = TerminalInputContext("fixture/sentinel")
        val lateContext = TerminalInputContext("herdr/default/w1:p1/term1", true,
            agentId = "codex", reasoningEffort = "high", agentProcessId = 42L)
        val oldHistory = HerdrPaneHistory("default/w1:p1/term1", 0, 0, 40, 7, 0,
            List(200) { TerminalLine.plain("fixture row $it") }, surface = HerdrPaneArea(0, 0, 40, 7))
        private val lateHistory = oldHistory.copy(revision = "late")
        private val terminal = DefaultSshSessionTerminalFactory.create()
        private val connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
            arrayOf(Connection::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "readHerdrHistory" -> HerdrHistoryRead.Available(lateHistory)
                "captureInputContext" -> lateContext
                "isHerdrSession", "trySend", "sendWithAcceptance" -> true
                "isTmuxSession" -> false
                "toString" -> "owned-fake-connection"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as Connection

        init {
            controller.setInputSink(object : TerminalInputSink {
                override fun send(bytes: ByteArray) = Unit
            }, onResize = { _, _ -> })
            controller.reportTerminalSize(40, 10)
            controller.herdrSessionActive = true
            val engine = VtTerminalEngine(40, 10)
            controller.updateTerminalFrame(engine.accept((
                "\u001B[?1000;1006h\u001B[H 1  2 \u001B[38;5;245m + \u001B[0m" +
                    "\u001B[10;1Hswitch │ project"
                ).toByteArray()))
            scheduler.drain()
            controller.publishInputContext(sentinel)
            controller.publishHerdrHistory(oldHistory)
            // Do not attach: that would start unrelated scheduled transport work.
            terminal.field("controller").set(terminal, controller)
            terminal.field("attachedConnection").set(terminal, connection)
        }

        fun pauseHistoryProjection(barrier: Barrier) {
            @Suppress("UNCHECKED_CAST")
            val original = controller.field("terminalScreen").get(controller) as List<TerminalLine>
            val controlled = object : AbstractList<TerminalLine>() {
                override val size: Int get() {
                    barrier.pausePollOnce()
                    return original.size
                }
                override fun get(index: Int) = original[index]
            }
            controller.field("terminalScreen").set(controller, controlled)
        }

        fun runPausedPoll(method: String, barrier: Barrier, whilePaused: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            val refresh = terminal.javaClass.getDeclaredMethod(method, Connection::class.java).apply { isAccessible = true }
            val worker = thread(name = POLL_THREAD, isDaemon = true) {
                try { refresh.invoke(terminal, connection) } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue("The real publication path must reach its controlled barrier", barrier.entered.await(5, TimeUnit.SECONDS))
                whilePaused()
            } finally {
                barrier.release.countDown()
                worker.join(5_000)
            }
            assertFalse("Poll must finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Publication fixture failed", it) }
        }

        fun clickTab() {
            val generation = controller.herdrHistoryGeneration
            assertTrue("The actual navigation click must be accepted", controller.sendMouseClick(4, 0))
            assertEquals(generation + 1, controller.herdrHistoryGeneration)
            val state = controller.captureHerdrHistoryState()
            assertNull(state.source)
            assertFalse(state.reading || state.pinned)
        }

        override fun close() {
            (terminal.field("tmuxHistoryExecutor").get(terminal) as ExecutorService).shutdownNow()
            (terminal.field("herdrHistoryExecutor").get(terminal) as ExecutorService).shutdownNow()
        }
    }

    private class Scheduler : TerminalFrameScheduler {
        private val callbacks = ConcurrentLinkedQueue<Choreographer.FrameCallback>()
        override fun postFrame(callback: Choreographer.FrameCallback) { callbacks.add(callback) }
        override fun uptimeMillis() = 0L
        fun drain() {
            while (true) (callbacks.poll() ?: return).doFrame(0L)
        }
    }

    private companion object {
        const val POLL_THREAD = "owned-herdr-publication-poll"
        fun Any.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
}
