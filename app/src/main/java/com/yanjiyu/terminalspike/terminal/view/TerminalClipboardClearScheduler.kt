package com.yanjiyu.terminalspike.terminal.view

import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface TerminalClipboardClearTarget {
    fun tryClearIfCurrent(token: TerminalClipboardToken): TerminalClipboardClearResult
}

internal enum class TerminalClipboardClearResult {
    CLEARED,
    NO_LONGER_CURRENT,
    TEMPORARILY_UNAVAILABLE,
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
    private val clearDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val lock = Any()
    private var delaySeconds = 0
    private var generation = 0L
    private var pending: Job? = null
    private var pendingClear: PendingClear? = null
    private var currentTarget = WeakReference<TerminalClipboardClearTarget>(null)
    private var closed = false

    internal fun updateDelaySeconds(value: Int) {
        require(value in 0..MAX_DELAY_SECONDS)
        synchronized(lock) {
            if (closed || value == delaySeconds) return
            delaySeconds = value
            generation += 1L
            pending?.cancel()
            pending = null
            pendingClear = null
        }
    }

    internal fun schedule(target: TerminalClipboardClearTarget, token: TerminalClipboardToken) {
        synchronized(lock) {
            if (closed) return
            generation += 1L
            pending?.cancel()
            pending = null
            pendingClear = null
            currentTarget = WeakReference(target)
            if (delaySeconds == 0) return

            val scheduledGeneration = generation
            val delayMillis = delaySeconds * 1_000L
            pendingClear = PendingClear(token, scheduledGeneration, expired = false)
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMillis)
                val targetAtExpiry = synchronized(lock) {
                    val current = pendingClear
                    if (
                        closed || generation != scheduledGeneration ||
                        current?.generation != scheduledGeneration
                    ) {
                        null
                    } else {
                        pendingClear = current.copy(expired = true)
                        currentTarget.get()
                    }
                }
                synchronized(lock) {
                    if (generation == scheduledGeneration && pending === job) pending = null
                }
                targetAtExpiry?.let { attemptClear(scheduledGeneration, it) }
            }
            pending = job
            job.start()
        }
    }

    /** Registers the current Activity-backed target and retries only an already-expired token. */
    internal fun retryExpiredClear(target: TerminalClipboardClearTarget) {
        val scheduledGeneration = synchronized(lock) {
            if (closed) return
            currentTarget = WeakReference(target)
            pendingClear?.takeIf(PendingClear::expired)?.generation
        } ?: return
        scope.launch { attemptClear(scheduledGeneration, target) }
    }

    private suspend fun attemptClear(
        scheduledGeneration: Long,
        target: TerminalClipboardClearTarget,
    ) {
        val token = synchronized(lock) {
            pendingClear?.takeIf { current ->
                !closed && current.expired && current.generation == scheduledGeneration &&
                    generation == scheduledGeneration
            }?.token
        } ?: return
        val result = withContext(clearDispatcher) { target.tryClearIfCurrent(token) }
        if (result == TerminalClipboardClearResult.TEMPORARILY_UNAVAILABLE) return
        synchronized(lock) {
            val current = pendingClear
            if (current?.generation == scheduledGeneration && generation == scheduledGeneration) {
                pendingClear = null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            pending?.cancel()
            pending = null
            pendingClear = null
            currentTarget.clear()
        }
    }

    private data class PendingClear(
        val token: TerminalClipboardToken,
        val generation: Long,
        val expired: Boolean,
    )

    private companion object {
        const val MAX_DELAY_SECONDS = 86_400
    }
}
