package com.yanjiyu.terminalspike.terminal.view

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface TerminalClipboardClearTarget {
    fun clearIfCurrent(token: TerminalClipboardToken): Boolean
}

/**
 * Process-owned timer for sensitive terminal clipboard writes.
 *
 * The scheduler never reads clipboard contents. A newer accepted write replaces the older timer,
 * and the target still verifies the opaque ownership token at expiry. Closing the process scope
 * cancels the timer; no durable worker attempts to clear an unverifiable clip after restart.
 */
class TerminalClipboardClearScheduler internal constructor(
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val lock = Any()
    private var delaySeconds = 0
    private var generation = 0L
    private var pending: Job? = null
    private var closed = false

    internal fun updateDelaySeconds(value: Int) {
        require(value in 0..MAX_DELAY_SECONDS)
        synchronized(lock) {
            if (closed || value == delaySeconds) return
            delaySeconds = value
            generation += 1L
            pending?.cancel()
            pending = null
        }
    }

    internal fun schedule(target: TerminalClipboardClearTarget, token: TerminalClipboardToken) {
        synchronized(lock) {
            if (closed) return
            generation += 1L
            pending?.cancel()
            pending = null
            if (delaySeconds == 0) return

            val scheduledGeneration = generation
            val delayMillis = delaySeconds * 1_000L
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMillis)
                val stillCurrent = synchronized(lock) {
                    !closed && generation == scheduledGeneration
                }
                if (stillCurrent) target.clearIfCurrent(token)
                synchronized(lock) {
                    if (generation == scheduledGeneration && pending === job) pending = null
                }
            }
            pending = job
            job.start()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            pending?.cancel()
            pending = null
        }
    }

    private companion object {
        const val MAX_DELAY_SECONDS = 86_400
    }
}
