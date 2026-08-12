package com.yanjiyu.terminalspike.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomTerminalThemeEditorTest {
    @Test
    fun opaqueHexParsingAndFormattingAreCanonical() {
        assertEquals(0xff12abef.toInt(), parseOpaqueArgb("#12AbEf"))
        assertEquals("#12ABEF", formatOpaqueArgb(0xff12abef.toInt()))
        assertNull(parseOpaqueArgb("12ABEF"))
        assertNull(parseOpaqueArgb("#8012ABEF"))
        assertNull(parseOpaqueArgb("#XYZXYZ"))
    }

    @Test
    fun previewMappingPreservesEveryColourAndBoldBrightChoice() {
        val draft = CustomTerminalThemeDraft(
            name = "Ocean",
            foregroundArgb = 0xffeeeeee.toInt(),
            backgroundArgb = 0xff101820.toInt(),
            cursorArgb = 0xffffffff.toInt(),
            selectionArgb = 0xff304050.toInt(),
            ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
            boldUsesBrightColours = false,
        )

        val preview = draft.toPreviewTheme()

        assertEquals(draft.foregroundArgb, preview.foreground)
        assertEquals(draft.backgroundArgb, preview.background)
        assertEquals(draft.cursorArgb, preview.cursor)
        assertEquals(draft.selectionArgb, preview.selection)
        assertEquals(draft.ansi16Argb, preview.ansi16)
        assertEquals(false, preview.boldUsesBrightColours)
    }
}
