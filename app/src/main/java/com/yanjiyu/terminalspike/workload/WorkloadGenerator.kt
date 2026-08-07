package com.yanjiyu.terminalspike.workload

import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import kotlin.random.Random

data class GeneratedScreen(
    val lines: List<TerminalLine>,
    val cursor: TerminalCursor,
)

class WorkloadGenerator(
    private val seed: Int = DEFAULT_SEED,
) {
    fun streamingLine(sequence: Long): TerminalLine {
        val random = Random(seed xor sequence.hashCode())
        val timestamp = "%02d:%02d:%02d.%03d".format(
            (sequence / 3_600_000) % 24,
            (sequence / 60_000) % 60,
            (sequence / 1_000) % 60,
            sequence % 1_000,
        )
        return when ((sequence % SAMPLE_VARIANTS).toInt()) {
            0 -> TerminalLine.plain("$timestamp  $ ./gradlew :app:assembleDebug")
            1 -> styledPrefix("$timestamp INFO ", "Compiled ${random.nextInt(12, 900)} Kotlin files in ${random.nextInt(80, 4000)} ms", TerminalPalette.GREEN)
            2 -> styledPrefix(" M ", "app/src/main/java/com/example/terminal/Renderer.kt", TerminalPalette.YELLOW)
            3 -> styledPrefix("docker ", "terminal-spike | request=${random.nextInt(100_000)} status=healthy", TerminalPalette.CYAN)
            4 -> TerminalLine.plain("/workspace/mobile/src/main/kotlin/render/TerminalViewport.kt:${random.nextInt(10, 450)}:${random.nextInt(1, 100)}")
            5 -> TerminalLine.styled(
                listOf(
                    TerminalRun("PASS ", TerminalStyle(foreground = TerminalPalette.GREEN, bold = true)),
                    TerminalRun("scroll anchor remains stable after ring-buffer trim", TerminalStyle(underline = true)),
                ),
            )
            6 -> TerminalLine.plain("Unicode: terminal 🚀 你好世界 café e\u0301 Z͑͗̓")
            7 -> TerminalLine.plain("Malformed sample is sanitized: \uD800 replacement follows")
            8 -> TerminalLine.plain("warning: cache miss for /home/dev/.gradle/caches/modules-2/files-2.1/${random.nextLong().toString(16)}")
            9 -> TerminalLine.styled(
                listOf(
                    TerminalRun("error ", TerminalStyle(foreground = TerminalPalette.RED, bold = true)),
                    TerminalRun("symbol not found", TerminalStyle(foreground = TerminalPalette.RED, underline = true)),
                    TerminalRun("; continuing benchmark output"),
                ),
            )
            10 -> TerminalLine.plain("[${"#".repeat(random.nextInt(1, 48)).padEnd(48, '.')}] ${random.nextInt(0, 101)}%")
            11 -> TerminalLine.plain("very-long: ${"0123456789abcdef".repeat(256)}")
            12 -> TerminalLine.plain("git status --short    feature/native-renderer-${random.nextInt(1, 99)}")
            13 -> TerminalLine.styled(
                listOf(
                    TerminalRun("tmux ", TerminalStyle(background = TerminalPalette.BLUE, foreground = TerminalPalette.WHITE, bold = true)),
                    TerminalRun(" window=editor pane=2 cpu=${random.nextInt(1, 90)}%"),
                ),
            )
            else -> TerminalLine.plain("$timestamp trace_id=${random.nextLong().toString(16)} completed normally")
        }
    }

    fun fullScreen(update: Long, rows: Int = DEFAULT_SCREEN_ROWS): GeneratedScreen {
        val clampedRows = rows.coerceAtLeast(8)
        val progress = (update % 101).toInt()
        val result = ArrayList<TerminalLine>(clampedRows)
        result += TerminalLine.styled(
            listOf(
                TerminalRun(" Terminal Spike TUI ", TerminalStyle(background = TerminalPalette.BLUE, foreground = TerminalPalette.WHITE, bold = true)),
                TerminalRun(" frame=$update  refresh=${update % 60}  load=${(update * 7) % 100}% ", TerminalStyle(inverse = true)),
            ),
        )
        result += TerminalLine.plain("┌─ local benchmark ─────────────────────────────────────────────────────────────┐")
        result += TerminalLine.plain("│ progress [${"█".repeat(progress / 4).padEnd(25, '░')}] ${progress.toString().padStart(3)}%                                     │")
        result += TerminalLine.plain("├──── PID ─── CPU% ─── MEM% ─── STATE ─── COMMAND ─────────────────────────────┤")
        repeat(clampedRows - 6) { row ->
            val active = row == (update % (clampedRows - 6)).toInt()
            val style = if (active) {
                TerminalStyle(background = TerminalPalette.SELECTION_MUTED, foreground = TerminalPalette.WHITE, bold = true)
            } else {
                TerminalStyle(foreground = if (row % 3 == 0) TerminalPalette.CYAN else TerminalPalette.FOREGROUND)
            }
            result += TerminalLine.plain(
                "│ ${(1200 + row).toString().padStart(6)}   ${((update + row * 13) % 100).toString().padStart(3)}.${row % 10}    ${((row * 7) % 20).toString().padStart(2)}.${update % 10}    ${if (row % 4 == 0) "RUN  " else "SLEEP"}    worker-${row.toString().padStart(2, '0')} --task render │",
                style,
            )
        }
        result += TerminalLine.plain("├──────────────────────────────────────────────────────────────────────────────┤")
        result += TerminalLine.styled(
            listOf(
                TerminalRun(" F1 Help ", TerminalStyle(background = TerminalPalette.BLUE, foreground = TerminalPalette.WHITE)),
                TerminalRun("  q Quit  "),
                TerminalRun("updates=$update  cursor moves without a parser", TerminalStyle(foreground = TerminalPalette.GREEN)),
            ),
        )
        return GeneratedScreen(
            lines = result,
            cursor = TerminalCursor(
                row = 4 + (update % (clampedRows - 6)).toInt(),
                column = 46 + (update % 16).toInt(),
                visible = true,
            ),
        )
    }

    private fun styledPrefix(prefix: String, body: String, colour: Int): TerminalLine =
        TerminalLine.styled(
            listOf(
                TerminalRun(prefix, TerminalStyle(foreground = colour, bold = true)),
                TerminalRun(body),
            ),
        )

    companion object {
        const val DEFAULT_SEED = 0x51A7E
        private const val SAMPLE_VARIANTS = 15L
        private const val DEFAULT_SCREEN_ROWS = 28
    }
}
