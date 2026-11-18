package com.yanjiyu.terminalspike.core.data.db

/** Canonical, bounded local metadata; it is deliberately excluded from portable backups. */
internal object HiddenDefaultSnippetIds {
    private const val MAX_IDS = 256

    fun encode(ids: Set<Long>): String {
        require(ids.size <= MAX_IDS && ids.all { it <= 0 }) { "Invalid hidden default snippet IDs." }
        return ids.sorted().joinToString(",")
    }

    fun decode(encoded: String): Set<Long> {
        if (encoded.isEmpty()) return emptySet()
        require(encoded.length <= MAX_IDS * 21) { "Hidden default snippet metadata is too large." }
        val pieces = encoded.split(',')
        require(pieces.size <= MAX_IDS) { "Too many hidden default snippet IDs." }
        return pieces.map { it.toLong() }.toSet().also { encode(it) }
    }
}
