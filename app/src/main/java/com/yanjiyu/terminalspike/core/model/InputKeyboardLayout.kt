package com.yanjiyu.terminalspike.core.model

/** A single flexible field surrounded by columns of at most two accessory keys. */
object InputKeyboardLayout {
    const val MAX_KEYS = 8

    /** Written only into a brand-new settings file; existing installs keep their saved layout. */
    const val NEW_INSTALL_LAYOUT = "enter,toggle,tab,shift_left,slash,ai_agents,arrow_up,arrow_down,input"
    fun defaultTokens(onRight: Boolean): List<String> =
        if (onRight) listOf("input", "enter", "toggle") else listOf("enter", "toggle", "input")

    /** Mirrors the bar around its centre: column order reverses, each column keeps top/bottom. */
    fun mirrored(tokens: List<String>): List<String> {
        val field = tokens.indexOf("input")
        fun flip(side: List<String>) = side.chunked(2).reversed().flatten()
        return flip(tokens.drop(field + 1)) + "input" + flip(tokens.take(field))
    }

    fun parse(value: String): List<String>? {
        if (value.isEmpty()) return null
        val tokens = value.split(',')
        val allowed = KeyboardAction.entries.map { it.wireCode }.toSet() + setOf("input", "toggle")
        return tokens.takeIf {
            it.size in 2..MAX_KEYS + 1 &&
                it.count { token -> token == "input" } == 1 &&
                it.any { token -> token == "toggle" } && it.all(allowed::contains)
        }
    }
}
