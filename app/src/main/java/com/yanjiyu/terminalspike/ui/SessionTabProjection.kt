package com.yanjiyu.terminalspike.ui

/** Runtime refreshes update existing tabs in place; only newly opened tabs append. */
internal fun List<SessionTabUi>.inExistingTabOrder(
    existing: List<SessionTabUi>,
): List<SessionTabUi> {
    val remaining = associateByTo(linkedMapOf(), SessionTabUi::id)
    return buildList {
        existing.forEach { tab -> remaining.remove(tab.id)?.let { add(it) } }
        addAll(remaining.values)
    }
}
