package com.yanjiyu.terminalspike

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Holds a dispatcher return so ownership tests can cancel before a result is delivered. */
internal class QueuedCallerDispatcher : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    val firstDispatch = CompletableDeferred<Unit>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
        firstDispatch.complete(Unit)
    }

    suspend fun finish(job: Job) = withTimeout(5_000) {
        while (!job.isCompleted) {
            while (true) (tasks.poll() ?: break).run()
            delay(1)
        }
    }
}
