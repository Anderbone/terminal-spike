package com.yanjiyu.terminalspike.connection

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Host directory history; null means the host did not provide a trustworthy access time. */
data class RecentHerdrProject(val path: String, val lastUsedAtEpochMillis: Long? = null)

/**
 * Read-only adapter for zoxide database v3 (fixed-width little-endian bincode).
 * Specification: https://github.com/ajeetdsouza/zoxide/tree/v0.10.0/src/db
 * Unknown formats or partial snapshots supply no dates; directory candidates still come from CLI.
 */
internal fun zoxideLastUsedTimes(bytes: ByteArray): Map<String, Long> = runCatching {
    require(bytes.size in 12..MAX_ZOXIDE_METADATA_BYTES)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(buffer.int == 3)
    val count = buffer.long
    require(count in 0..(buffer.remaining() / 24).toLong())
    val times = mutableMapOf<String, Long>()
    repeat(count.toInt()) {
        val length = buffer.long
        require(length in 1..4096 && length <= buffer.remaining() - 16L)
        val pathBytes = ByteArray(length.toInt()).also(buffer::get)
        val path = pathBytes.toString(Charsets.UTF_8)
        require(path.toByteArray(Charsets.UTF_8).contentEquals(pathBytes))
        val rank = buffer.double
        require(rank.isFinite() && rank >= 0.0)
        val seconds = buffer.long
        require(seconds in 0..Long.MAX_VALUE / 1_000L)
        if (validHerdrProjectPath(path) && seconds > 0) {
            val normalized = path.trimEnd('/')
            times[normalized] = maxOf(times[normalized] ?: 0L, seconds * 1_000L)
        }
    }
    require(!buffer.hasRemaining())
    times
}.getOrDefault(emptyMap())

internal const val MAX_ZOXIDE_METADATA_BYTES = 128 * 1024
