package com.yanjiyu.terminalspike.settings

import kotlinx.coroutines.delay

/** Plain snippets are literal. Only the explicit header enables key-sequence syntax. */
object SnippetSequence {
    const val HEADER = "#!keys"
    const val CODEX_EXAMPLE = "#!keys\nctrl+c\nwait 500\ntext /clear\nenter\nwait 500\ntext codex --yolo\nenter"

    sealed interface Step {
        data class Text(val value: String) : Step
        data class Key(val value: Byte) : Step
        data class Wait(val millis: Long) : Step
    }

    suspend fun execute(
        steps: List<Step>,
        canContinue: () -> Boolean,
        sendText: (String) -> Boolean,
        sendKey: (Byte) -> Boolean,
    ): Boolean {
        for (step in steps) {
            if (!canContinue()) return false
            val accepted = when (step) {
                is Step.Text -> sendText(step.value)
                is Step.Key -> sendKey(step.value)
                is Step.Wait -> { delay(step.millis); true }
            }
            if (!accepted) return false
        }
        return true
    }

    fun parse(command: String): List<Step>? {
        val lines = command.replace("\r\n", "\n").split('\n')
        if (lines.first() != HEADER) return null
        val steps = lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { index, line ->
            when {
                line.startsWith("text ") -> Step.Text(line.removePrefix("text "))
                line == "enter" -> Step.Key(13)
                line == "tab" -> Step.Key(9)
                line == "escape" -> Step.Key(27)
                line.matches(Regex("ctrl\\+[a-z]")) -> Step.Key((line.last().code - 'a'.code + 1).toByte())
                line.startsWith("wait ") -> Step.Wait(
                    line.removePrefix("wait ").toLongOrNull()?.takeIf { it in 1..10_000 }
                        ?: throw IllegalArgumentException("Wait must be 1–10000 ms (line ${index + 2})."),
                )
                else -> throw IllegalArgumentException("Unknown sequence action on line ${index + 2}.")
            }
        }
        require(steps.isNotEmpty()) { "Add at least one sequence action." }
        require(steps.filterIsInstance<Step.Wait>().sumOf { it.millis } <= 60_000) { "Total wait must be at most 60 seconds." }
        return steps
    }
}
