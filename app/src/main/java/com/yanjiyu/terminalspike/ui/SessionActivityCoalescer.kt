package com.yanjiyu.terminalspike.ui

/**
 * Serializes inbound and accepted outbound activity onto one bounded publication cadence.
 * Terminal bytes stay outside Compose state; only the occasional timestamp is published.
 */
internal class SessionActivityCoalescer(
    initialActivityAtEpochMillis: Long,
    private val publishIntervalMillis: Long,
) {
    init {
        require(initialActivityAtEpochMillis >= 0L)
        require(publishIntervalMillis > 0L)
    }

    @Volatile
    var lastPublishedAtEpochMillis: Long = initialActivityAtEpochMillis
        private set

    fun recordInbound(activityAtEpochMillis: Long): Long? = record(activityAtEpochMillis)

    fun recordAcceptedOutbound(activityAtEpochMillis: Long): Long? = record(activityAtEpochMillis)

    fun force(activityAtEpochMillis: Long): Long = synchronized(this) {
        activityAtEpochMillis.coerceAtLeast(lastPublishedAtEpochMillis).also {
            lastPublishedAtEpochMillis = it
        }
    }

    private fun record(activityAtEpochMillis: Long): Long? = synchronized(this) {
        val boundedActivityAt = activityAtEpochMillis.coerceAtLeast(lastPublishedAtEpochMillis)
        if (boundedActivityAt - lastPublishedAtEpochMillis < publishIntervalMillis) {
            null
        } else {
            lastPublishedAtEpochMillis = boundedActivityAt
            boundedActivityAt
        }
    }
}
