package com.yanjiyu.terminalspike.terminal

/** Remaining percentages from the identified thread's most recent usage snapshot. */
data class CodexInputStatus(
    val contextRemaining: Int? = null,
    val weeklyRemaining: Int? = null,
) {
    init {
        require(listOfNotNull(contextRemaining, weeklyRemaining).all { it in 0..100 })
    }

    fun compactText(): String {
        fun percent(value: Int?) = value?.let { "$it%" } ?: "—"
        return "Context ${percent(contextRemaining)} · Week ${percent(weeklyRemaining)}"
    }
}
