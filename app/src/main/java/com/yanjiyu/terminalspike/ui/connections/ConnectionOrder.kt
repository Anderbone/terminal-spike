package com.yanjiyu.terminalspike.ui.connections

/** Unknown/new connections retain their incoming order after explicitly arranged connections. */
internal fun orderedConnectionIds(ids: List<String>, saved: List<String>): List<String> =
    saved.filter { it in ids }.distinct() + ids.filter { it !in saved }

internal fun moveConnection(ids: List<String>, from: String, to: String): List<String> {
    val source = ids.indexOf(from)
    val target = ids.indexOf(to)
    if (source < 0 || target < 0 || source == target) return ids
    return ids.toMutableList().apply { add(target, removeAt(source)) }
}
