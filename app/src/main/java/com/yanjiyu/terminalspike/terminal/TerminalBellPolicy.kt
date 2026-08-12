package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.core.model.BellSettings
import java.util.ArrayDeque

/** A parser-originated BEL batch. It contains no platform side effects. */
data class TerminalBellEvent(
    val sequence: Long,
    val count: Int,
) {
    init {
        require(sequence > 0L)
        require(count in 1..MAX_COALESCED_BELL_COUNT)
    }
}

/** Foreground effects allowed by the active profile. */
data class TerminalBellEffects(
    val visual: Boolean = false,
    val haptic: Boolean = false,
    val audible: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !visual && !haptic && !audible
}

/** Background owners never receive sound, haptic, visual, notification, or wake-up work. */
fun resolveTerminalBellEffects(
    settings: BellSettings,
    foregroundUiOwnsSession: Boolean,
): TerminalBellEffects = if (!foregroundUiOwnsSession) {
    TerminalBellEffects()
} else {
    TerminalBellEffects(
        visual = settings.visualBellEnabled,
        haptic = settings.vibrationBellEnabled,
        audible = settings.audibleBellEnabled,
    )
}

sealed interface TerminalBellRateDecision {
    data class Present(val event: TerminalBellEvent) : TerminalBellRateDecision
    data class Deferred(val nextEligibleAtMillis: Long) : TerminalBellRateDecision
    data object None : TerminalBellRateDecision
}

/**
 * Pure, per-session BEL limiter.
 *
 * At most one presentation is allowed every 250 ms and at most four are allowed in a rolling
 * second. Suppressed batches are reduced to one bounded pending event. Explicit reset methods keep
 * a pending event from leaking into a replacement session or a recreated foreground owner.
 */
class TerminalBellRateLimiter(
    private val minimumIntervalMillis: Long = MINIMUM_BELL_INTERVAL_MILLIS,
    private val rollingWindowMillis: Long = BELL_ROLLING_WINDOW_MILLIS,
    private val maximumPresentationsPerWindow: Int = MAX_BELL_PRESENTATIONS_PER_WINDOW,
) {
    private val presentedAt = ArrayDeque<Long>(maximumPresentationsPerWindow)
    private var pending: TerminalBellEvent? = null
    private var lastSeenSequence: Long? = null
    private var suppressThroughSequence: Long? = null
    private var lastObservedTimeMillis: Long = 0L

    init {
        require(minimumIntervalMillis > 0L)
        require(rollingWindowMillis >= minimumIntervalMillis)
        require(maximumPresentationsPerWindow > 0)
    }

    fun offer(event: TerminalBellEvent, nowMillis: Long): TerminalBellRateDecision {
        val now = monotonicTime(nowMillis)
        suppressThroughSequence?.let { suppressed ->
            if (event.sequence <= suppressed) return TerminalBellRateDecision.None
            suppressThroughSequence = null
        }
        if (lastSeenSequence == event.sequence) return pendingDecision(now)
        lastSeenSequence = event.sequence
        pending = pending.coalesce(event)
        return presentPendingIfEligible(now)
    }

    fun poll(nowMillis: Long): TerminalBellRateDecision =
        presentPendingIfEligible(monotonicTime(nowMillis))

    fun hasPendingEvent(): Boolean = pending != null

    /** Drops pending work/history and accepts a fresh sequence namespace for a replacement session. */
    fun resetForSessionReplacement() {
        pending = null
        presentedAt.clear()
        lastSeenSequence = null
        suppressThroughSequence = null
        lastObservedTimeMillis = 0L
    }

    /** Drops pending work and suppresses state-like replay through the last already-seen sequence. */
    fun suppressThrough(sequence: Long) {
        require(sequence >= 0L)
        pending = null
        presentedAt.clear()
        lastSeenSequence = sequence.takeIf { it > 0L }
        suppressThroughSequence = sequence
        lastObservedTimeMillis = 0L
    }

    private fun presentPendingIfEligible(now: Long): TerminalBellRateDecision {
        val candidate = pending ?: return TerminalBellRateDecision.None
        removeExpiredPresentations(now)
        val eligibleAt = nextEligibleAt(now)
        if (eligibleAt > now) return TerminalBellRateDecision.Deferred(eligibleAt)
        pending = null
        presentedAt.addLast(now)
        return TerminalBellRateDecision.Present(candidate)
    }

    private fun pendingDecision(now: Long): TerminalBellRateDecision =
        if (pending == null) TerminalBellRateDecision.None else {
            removeExpiredPresentations(now)
            TerminalBellRateDecision.Deferred(nextEligibleAt(now))
        }

    private fun removeExpiredPresentations(now: Long) {
        while (presentedAt.isNotEmpty() && now - presentedAt.first() >= rollingWindowMillis) {
            presentedAt.removeFirst()
        }
    }

    private fun nextEligibleAt(now: Long): Long {
        val intervalEligibleAt = presentedAt.lastOrNull()
            ?.let { saturatedAdd(it, minimumIntervalMillis) }
            ?: now
        val windowEligibleAt = if (presentedAt.size >= maximumPresentationsPerWindow) {
            saturatedAdd(presentedAt.first(), rollingWindowMillis)
        } else {
            now
        }
        return maxOf(now, intervalEligibleAt, windowEligibleAt)
    }

    private fun monotonicTime(candidate: Long): Long {
        val nonNegative = candidate.coerceAtLeast(0L)
        val monotonic = maxOf(lastObservedTimeMillis, nonNegative)
        lastObservedTimeMillis = monotonic
        return monotonic
    }
}

private fun TerminalBellEvent?.coalesce(next: TerminalBellEvent): TerminalBellEvent {
    val current = this ?: return next
    return TerminalBellEvent(
        sequence = next.sequence,
        count = (current.count.toLong() + next.count.toLong())
            .coerceAtMost(MAX_COALESCED_BELL_COUNT.toLong())
            .toInt(),
    )
}

private fun saturatedAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

const val MINIMUM_BELL_INTERVAL_MILLIS = 250L
const val BELL_ROLLING_WINDOW_MILLIS = 1_000L
const val MAX_BELL_PRESENTATIONS_PER_WINDOW = 4
const val MAX_COALESCED_BELL_COUNT = 1_024
