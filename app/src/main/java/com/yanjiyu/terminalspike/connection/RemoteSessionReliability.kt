package com.yanjiyu.terminalspike.connection

/** Non-secret reliability policy captured with an application-owned remote session. */
internal data class RemoteSessionReliabilityPolicy(
    val reconnectEnabled: Boolean = false,
    val reconnectMaxAttempts: Int = DEFAULT_RECONNECT_MAX_ATTEMPTS,
    val initialRetryDelayMillis: Long = DEFAULT_RECONNECT_INITIAL_DELAY_MILLIS,
    val maximumRetryDelayMillis: Long = DEFAULT_RECONNECT_MAXIMUM_DELAY_MILLIS,
) {
    init {
        require(reconnectMaxAttempts in 0..MAX_RECONNECT_ATTEMPTS)
        require(initialRetryDelayMillis > 0L)
        require(maximumRetryDelayMillis >= initialRetryDelayMillis)
    }
}

/** Pure retry decision used by the repository and deterministic JVM tests. */
internal data class ReconnectPlan(
    val policy: RemoteSessionReliabilityPolicy,
) {
    fun next(
        completedAttempts: Int,
        networkAvailable: Boolean,
        intentionallyDisconnected: Boolean,
    ): ReconnectStep {
        if (intentionallyDisconnected) return ReconnectStep.Cancelled
        if (!policy.reconnectEnabled || completedAttempts >= policy.reconnectMaxAttempts) {
            return ReconnectStep.Exhausted
        }
        val attempt = completedAttempts + 1
        if (!networkAvailable) return ReconnectStep.WaitForNetwork(attempt)
        var delayMillis = policy.initialRetryDelayMillis
        var remainingDoublings = attempt - 1
        while (remainingDoublings > 0 && delayMillis < policy.maximumRetryDelayMillis) {
            delayMillis = if (delayMillis > policy.maximumRetryDelayMillis / 2L) {
                policy.maximumRetryDelayMillis
            } else {
                delayMillis * 2L
            }
            remainingDoublings -= 1
        }
        return ReconnectStep.RetryAfter(
            attempt = attempt,
            delayMillis = delayMillis.coerceAtMost(policy.maximumRetryDelayMillis),
        )
    }
}

internal sealed interface ReconnectStep {
    data class WaitForNetwork(val attempt: Int) : ReconnectStep

    data class RetryAfter(val attempt: Int, val delayMillis: Long) : ReconnectStep

    data object Exhausted : ReconnectStep

    data object Cancelled : ReconnectStep
}

private const val DEFAULT_RECONNECT_MAX_ATTEMPTS = 5
private const val MAX_RECONNECT_ATTEMPTS = 100
private const val DEFAULT_RECONNECT_INITIAL_DELAY_MILLIS = 1_000L
private const val DEFAULT_RECONNECT_MAXIMUM_DELAY_MILLIS = 30_000L
