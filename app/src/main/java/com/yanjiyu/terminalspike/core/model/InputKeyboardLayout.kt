package com.yanjiyu.terminalspike.core.model

/** A single flexible field surrounded by columns of at most two accessory keys. */
object InputKeyboardLayout {
    const val MAX_KEYS = 6
    fun defaultTokens(onRight: Boolean): List<String> =
        if (onRight) listOf("input", "enter", "toggle") else listOf("enter", "toggle", "input")

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
