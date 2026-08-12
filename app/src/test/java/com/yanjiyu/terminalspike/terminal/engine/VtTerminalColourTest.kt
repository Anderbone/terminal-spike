package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalColour
import org.junit.Assert.assertEquals
import org.junit.Test

class VtTerminalColourTest {
    @Test
    fun sgrRetainsDefaultIndexedAndTrueColourProvenance() {
        val engine = VtTerminalEngine(columns = 12, rows = 2)

        val update = engine.accept(
            "\u001B[31mA\u001B[91mB\u001B[38;5;202mC" +
                "\u001B[38;2;224;108;117mD\u001B[39mE",
        )

        assertEquals(TerminalColour.Indexed(1), update.screen[0].runs[0].style.foreground)
        assertEquals(TerminalColour.Indexed(9), update.screen[0].runs[1].style.foreground)
        assertEquals(TerminalColour.Indexed(202), update.screen[0].runs[2].style.foreground)
        assertEquals(
            TerminalColour.Rgb(0xFFE06C75.toInt()),
            update.screen[0].runs[3].style.foreground,
        )
        assertEquals(TerminalColour.Default, update.screen[0].runs[4].style.foreground)
    }

    @Test
    fun boldSgrRetainsTheIndexedColourRatherThanPrecomputingBrightArgb() {
        val engine = VtTerminalEngine(columns = 4, rows = 1)

        val update = engine.accept("\u001B[1;32mA")
        val style = update.screen[0].runs.single().style

        assertEquals(TerminalColour.Indexed(2), style.foreground)
        assertEquals(true, style.bold)
    }

    private fun VtTerminalEngine.accept(text: String) =
        accept(text.toByteArray(Charsets.UTF_8))
}
