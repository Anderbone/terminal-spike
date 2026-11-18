package com.yanjiyu.terminalspike.terminal

/** Only a native model/status row in the focused pane's live footer is evidence of current effort. */
internal fun codexEffortFromLiveFooter(text: String): String? {
    if (text.length > 8192) return null
    val footer = Regex(
        "^\\s*(?:[│┃]\\s*)?gpt-[a-z0-9.-]+\\s+(low|medium|high|xhigh|max|ultra)\\s*(?:[·•|].*)?$",
        RegexOption.IGNORE_CASE,
    )
    val lines = text.lineSequence().toList().takeLast(12)
    val prompt = lines.indexOfLast { it.trimStart().startsWith("›") }
    if (prompt < 0) {
        val last = lines.lastOrNull { it.isNotBlank() } ?: return null
        if (!Regex("[·•]\\s*Context\\s+\\d+%\\s+left", RegexOption.IGNORE_CASE).containsMatchIn(last)) return null
        return footer.matchEntire(last)?.groupValues?.get(1)?.lowercase()
    }
    return lines.drop(prompt + 1).mapNotNull {
        footer.matchEntire(it)?.groupValues?.get(1)?.lowercase()
    }.lastOrNull()
}

/** Keep presentation through failed inspections; confirmed identity changes always replace it. */
internal class CodexEffortObservation(private val now: () -> Long = System::nanoTime) {
    private var previous = TerminalInputContext()
    private var confirmedAt = 0L
    fun captureTime(): Long = now()

    fun observe(next: TerminalInputContext): TerminalInputContext = observe(next, captureTime())

    fun observe(next: TerminalInputContext, time: Long): TerminalInputContext {
        if (!next.observationAvailable) return previous.copy(observationAvailable = false, herdrPiScrollPane = null)
        val same = next.agentId == "codex" && next.identity.isNotEmpty() &&
            next.identity == previous.identity && next.agentProcessId == previous.agentProcessId
        val result = if (same && next.reasoningEffort == null && (next.agentProcessId != null || time - confirmedAt <= 3_000_000_000L))
            next.copy(reasoningEffort = previous.reasoningEffort) else next
        if (next.reasoningEffort != null) confirmedAt = time
        previous = result
        return result
    }
}
