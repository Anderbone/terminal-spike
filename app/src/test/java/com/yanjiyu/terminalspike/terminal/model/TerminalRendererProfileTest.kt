package com.yanjiyu.terminalspike.terminal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRendererProfileTest {
    @Test
    fun terminalSafeFontBehaviourDefaultsAreExplicit() {
        val profile = TerminalRendererProfile()

        assertEquals(TerminalRendererProfile.JETBRAINS_MONO_FONT_ID, profile.fontId)
        assertEquals(
            TerminalRendererProfile.JETBRAINS_MONO_FONT_ID,
            TerminalRendererProfile.DEFAULT_FONT_ID,
        )
        assertTrue(profile.boldRenderingEnabled)
        assertFalse(profile.ligaturesEnabled)
        assertTrue(profile.pinchZoomEnabled)
        assertFalse(profile.copyOnSelection)
    }

    @Test
    fun bundledFontIdsAreStableAndDistinct() {
        assertTrue(TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID in TerminalRendererProfile.BUNDLED_FONT_IDS)
        assertTrue(TerminalRendererProfile.SOURCE_CODE_PRO_FONT_ID in TerminalRendererProfile.BUNDLED_FONT_IDS)
        assertTrue(TerminalRendererProfile.JETBRAINS_MONO_FONT_ID in TerminalRendererProfile.BUNDLED_FONT_IDS)
        assertTrue(TerminalRendererProfile.IBM_PLEX_MONO_FONT_ID in TerminalRendererProfile.BUNDLED_FONT_IDS)
        assertTrue(TerminalRendererProfile.CASCADIA_MONO_FONT_ID in TerminalRendererProfile.BUNDLED_FONT_IDS)
        assertTrue(TerminalRendererProfile.BUNDLED_FONT_IDS.size == 5)
    }
}
