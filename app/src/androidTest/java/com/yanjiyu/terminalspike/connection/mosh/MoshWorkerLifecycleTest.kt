package com.yanjiyu.terminalspike.connection.mosh

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.mosh.MoshWorker0Service
import com.yanjiyu.terminalspike.mosh.BaseMoshWorkerService
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorker
import com.yanjiyu.terminalspike.mosh.internal.IMoshWorkerCallback
import java.util.UUID
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshWorkerLifecycleTest {
    @Test
    fun cancelledTasksDoNotAccumulateBehindABusyWorkerThread() {
        val pool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
        val worker = WorkerUnderTest(pool)
        val binder = IMoshWorker.Stub.asInterface(worker.onBind(null))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var completed = 0
        val callback = object : IMoshWorkerCallback.Stub() {
            override fun onSessionEvent(id: String, state: Int, disconnectReason: Int, errorCode: Int, detail: String, connectivityGeneration: Long) {
                assertEquals(MoshSessionState.DISCONNECTED, state)
                completed++
            }
        }
        try {
            pool.execute { entered.countDown(); runCatching { release.await(10, TimeUnit.SECONDS) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(64) {
                WorkerRequest().use { request ->
                    binder.startSession(request.id, byteArrayOf(127, 0, 0, 1), MoshAddressFamily.IPV4, 60_001,
                        request.transferred[0], request.transferred[1], request.transferred[2],
                        80, 24, "C.UTF-8", 0L, callback)
                    binder.stopSession(request.id, MoshStopReason.USER_REQUESTED)
                    request.assertClosed()
                    assertEquals("Cancelled sessions must not remain queued", 0, pool.queue.size)
                }
            }
            assertEquals(64, completed)
        } finally {
            release.countDown()
            worker.onDestroy()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedStopCannotCloseOrCompleteTheReplacementSession() {
        WorkerFixture().use { fixture ->
            val first = fixture.request()
            fixture.start(first)
            fixture.binder.stopSession(first.id, MoshStopReason.USER_REQUESTED)
            first.assertClosed()
            assertEquals(listOf(WorkerEvent(first.id, MoshSessionState.DISCONNECTED, MoshDisconnectReason.USER_REQUESTED)), fixture.events)

            val second = fixture.request()
            fixture.start(second)
            assertEquals(2, fixture.executor.pendingCount)
            fixture.executor.runNext()
            second.transferred.forEach { assertTrue(it.fileDescriptor.valid()) }
            assertEquals(1, fixture.events.size)
            fixture.binder.stopSession(second.id, MoshStopReason.SESSION_REPLACED)
            fixture.executor.runNext()
            second.assertClosed()
            assertEquals(WorkerEvent(second.id, MoshSessionState.DISCONNECTED, MoshDisconnectReason.SESSION_REPLACED), fixture.events.last())
            assertEquals(2, fixture.events.size)
            assertTrue(fixture.callbackBinder.recipients.isEmpty())
        }
    }

    @Test
    fun destroyClosesQueuedDescriptorsEvenWhenShutdownDropsTheTask() {
        WorkerFixture().use { fixture ->
            val request = fixture.request()
            fixture.start(request)
            fixture.worker.onDestroy()
            request.assertClosed()
            assertEquals(0, fixture.executor.pendingCount)
            assertEquals(listOf(WorkerEvent(request.id, MoshSessionState.DISCONNECTED, MoshDisconnectReason.EXTENSION_FAILED)), fixture.events)
            assertTrue(fixture.callbackBinder.recipients.isEmpty())
        }
    }

    @Test
    fun rejectedSubmissionReleasesDescriptorsAndAllowsAnotherSession() {
        WorkerFixture().use { fixture ->
            fixture.executor.rejectNext = true
            val first = fixture.request()
            fixture.start(first)
            first.assertClosed()
            assertEquals(listOf(WorkerEvent(first.id, MoshSessionState.ERROR, error = MoshErrorCode.NATIVE_INITIALIZATION_FAILED)), fixture.events)

            val second = fixture.request()
            fixture.start(second)
            assertEquals(1, fixture.executor.pendingCount)
            fixture.binder.stopSession(second.id, MoshStopReason.USER_REQUESTED)
            fixture.executor.runNext()
            second.assertClosed()
            assertEquals(2, fixture.events.size)
            assertEquals(second.id, fixture.events.last().id)
        }
    }

    @Test
    fun callbackDeathDisposesItsQueuedDescriptorLease() {
        WorkerFixture().use { fixture ->
            val request = fixture.request()
            fixture.start(request)
            fixture.callbackBinder.die()
            request.assertClosed()
            fixture.executor.runNext()
            assertTrue(fixture.events.isEmpty())
            assertTrue(fixture.callbackBinder.recipients.isEmpty())
        }
    }

    @Test
    fun normalNativeKeyFailureFinishesTheLeaseAndUnlinksItsCallback() {
        WorkerFixture().use { fixture ->
            val request = fixture.request()
            fixture.start(request)
            fixture.executor.runNext()
            request.assertClosed()
            assertEquals(listOf(WorkerEvent(request.id, MoshSessionState.ERROR, error = MoshErrorCode.KEY_READ_FAILED)), fixture.events)
            assertTrue(fixture.callbackBinder.recipients.isEmpty())
        }
    }

    @Test
    fun startAfterDestroyClosesTransferredDescriptorsAndReportsCancellation() {
        val worker = MoshWorker0Service()
        val binder = IMoshWorker.Stub.asInterface(worker.onBind(null))
        val pipes = List(3) { ParcelFileDescriptor.createPipe() }
        val transferred = listOf(pipes[0][0], pipes[1][0], pipes[2][1])
        val sessionId = UUID.randomUUID().toString()
        val events = mutableListOf<Triple<String, Int, Int>>()
        val callback = object : IMoshWorkerCallback.Stub() {
            override fun onSessionEvent(
                id: String, state: Int, disconnectReason: Int, errorCode: Int,
                detail: String, connectivityGeneration: Long,
            ) {
                events += Triple(id, state, errorCode)
            }
        }
        try {
            worker.onDestroy()
            val failure = runCatching {
                binder.startSession(
                    sessionId, byteArrayOf(127, 0, 0, 1), MoshAddressFamily.IPV4,
                    60_001, transferred[0], transferred[1], transferred[2],
                    80, 24, "C.UTF-8", 0L, callback,
                )
            }.exceptionOrNull()

            transferred.forEach { assertFalse(it.fileDescriptor.valid()) }
            assertNull("Late Binder admission must return a bounded callback, not throw", failure)
            assertEquals(listOf(Triple(sessionId, MoshSessionState.ERROR, MoshErrorCode.CANCELLED)), events)
        } finally {
            pipes.forEach { pipe -> pipe.forEach { runCatching { it.close() } } }
            worker.onDestroy()
        }
    }
}

private class WorkerUnderTest(executor: ExecutorService) : BaseMoshWorkerService(executor)

private data class WorkerEvent(val id: String, val state: Int, val reason: Int = MoshDisconnectReason.NONE, val error: Int = MoshErrorCode.NONE)

private class WorkerRequest : AutoCloseable {
    val id = UUID.randomUUID().toString()
    private val pipes = List(3) { ParcelFileDescriptor.createPipe() }
    val transferred = listOf(pipes[0][0], pipes[1][0], pipes[2][1])

    init { pipes[0][1].close() } // Empty key is rejected immediately, without opening a UDP socket.

    fun assertClosed() = transferred.forEach { assertFalse(it.fileDescriptor.valid()) }
    override fun close() = pipes.forEach { pipe -> pipe.forEach { runCatching { it.close() } } }
}

private class WorkerCallbackBinder : Binder() {
    val recipients = mutableListOf<IBinder.DeathRecipient>()
    var dead = false
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipients += recipient }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)
    fun die() { dead = true; recipients.toList().forEach { it.binderDied() } }
}

private class WorkerFixture : AutoCloseable {
    val executor = QueuedWorkerExecutor()
    val worker = WorkerUnderTest(executor)
    val binder: IMoshWorker = IMoshWorker.Stub.asInterface(worker.onBind(null))
    val events = mutableListOf<WorkerEvent>()
    val callbackBinder = WorkerCallbackBinder()
    private val requests = mutableListOf<WorkerRequest>()
    private val callback = object : IMoshWorkerCallback.Stub() {
        override fun asBinder(): IBinder = callbackBinder
        override fun onSessionEvent(id: String, state: Int, disconnectReason: Int, errorCode: Int, detail: String, connectivityGeneration: Long) {
            if (callbackBinder.dead) throw DeadObjectException()
            events += WorkerEvent(id, state, disconnectReason, errorCode)
        }
    }

    fun request() = WorkerRequest().also(requests::add)
    fun start(request: WorkerRequest) {
        binder.startSession(request.id, byteArrayOf(127, 0, 0, 1), MoshAddressFamily.IPV4, 60_001,
            request.transferred[0], request.transferred[1], request.transferred[2],
            80, 24, "C.UTF-8", 0L, callback)
    }

    override fun close() {
        worker.onDestroy()
        requests.forEach(WorkerRequest::close)
    }
}

private class QueuedWorkerExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var closed = false
    var rejectNext = false
    val pendingCount get() = tasks.size
    override fun execute(command: Runnable) {
        if (closed || rejectNext) {
            rejectNext = false
            throw RejectedExecutionException("Controlled rejection")
        }
        tasks.addLast(command)
    }
    fun runNext() = tasks.removeFirst().run()
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> {
        closed = true
        return tasks.toMutableList().also { tasks.clear() }
    }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}
