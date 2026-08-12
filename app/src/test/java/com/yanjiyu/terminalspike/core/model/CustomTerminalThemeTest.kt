package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class CustomTerminalThemeTest {
    @Test
    fun rejectsTransparentOrIncompletePalettes() {
        assertThrows(IllegalArgumentException::class.java) {
            validTheme().copy(backgroundArgb = 0x00101820)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validTheme().copy(ansi16Argb = List(15) { 0xff000000.toInt() })
        }
    }

    private fun validTheme() = CustomTerminalTheme(
        id = "10000000-0000-4000-8000-000000000001",
        name = "Ocean",
        foregroundArgb = 0xffeeeeee.toInt(),
        backgroundArgb = 0xff101820.toInt(),
        cursorArgb = 0xffffffff.toInt(),
        selectionArgb = 0xff304050.toInt(),
        ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )
}
