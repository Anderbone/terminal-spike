package com.yanjiyu.terminalspike.terminal.model

class TerminalLine private constructor(
    val id: Long,
    val runs: List<TerminalRun>,
    val text: String,
    /** True only when autowrap, rather than a line feed, continues this row into the next row. */
    val softWrappedToNext: Boolean,
) {
    fun withId(newId: Long): TerminalLine = TerminalLine(newId, runs, text, softWrappedToNext)

    companion object {
        const val UNASSIGNED_ID: Long = -1L

        fun plain(
            text: String,
            style: TerminalStyle = TerminalStyle(),
            softWrappedToNext: Boolean = false,
        ): TerminalLine = create(listOf(TerminalRun(text, style)), softWrappedToNext)

        fun styled(runs: List<TerminalRun>, softWrappedToNext: Boolean = false): TerminalLine =
            create(runs.ifEmpty { listOf(TerminalRun("")) }, softWrappedToNext)

        private fun create(sourceRuns: List<TerminalRun>, softWrappedToNext: Boolean): TerminalLine {
            val sanitizedRuns = sourceRuns.map { run ->
                run.copy(text = sanitizeUnicode(run.text))
            }
            val text = buildString { sanitizedRuns.forEach { append(it.text) } }
            return TerminalLine(UNASSIGNED_ID, sanitizedRuns, text, softWrappedToNext)
        }

        private fun sanitizeUnicode(value: String): String {
            var firstInvalid = -1
            var index = 0
            while (index < value.length) {
                val current = value[index]
                when {
                    Character.isHighSurrogate(current) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                            firstInvalid = index
                            break
                        }
                        index += 2
                    }
                    Character.isLowSurrogate(current) -> {
                        firstInvalid = index
                        break
                    }
                    else -> index += 1
                }
            }
            if (firstInvalid < 0) return value

            return buildString(value.length) {
                index = 0
                while (index < value.length) {
                    val current = value[index]
                    if (
                        Character.isHighSurrogate(current) &&
                        index + 1 < value.length &&
                        Character.isLowSurrogate(value[index + 1])
                    ) {
                        append(current)
                        append(value[index + 1])
                        index += 2
                    } else if (Character.isSurrogate(current)) {
                        append('\uFFFD')
                        index += 1
                    } else {
                        append(current)
                        index += 1
                    }
                }
            }
        }
    }
}
