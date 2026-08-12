package com.yanjiyu.terminalspike.terminal.model

import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalThemesTest {
    @Test
    fun catalogueHasStableUniqueIdentifiersAndCompleteOpaqueAnsiPalettes() {
        // Deliberately exact: changing this inventory also requires palette provenance and notices.
        assertEquals(
            listOf(
                "current",
                "ayu_dark",
                "one_dark",
                "dracula",
                "nord",
                "solarized_dark",
                "solarized_light",
                "gruvbox_dark",
                "tokyo_night",
                "catppuccin_mocha",
                "high_contrast",
            ),
            TerminalThemes.presets.map(TerminalTheme::id),
        )
        assertEquals(
            TerminalThemes.presets.size,
            TerminalThemes.presets.map(TerminalTheme::id).distinct().size,
        )
        TerminalThemes.presets.forEach { theme ->
            assertEquals(16, theme.ansi16.size)
            assertTrue(theme.ansi16.all { colour -> colour ushr 24 == 0xff })
            assertTrue(
                listOf(theme.background, theme.foreground, theme.cursor, theme.selection)
                    .all { colour -> colour ushr 24 == 0xff },
            )
        }
    }

    @Test
    fun lookupFallsBackWithoutInventingAStoredTheme() {
        assertSame(TerminalThemes.current, TerminalThemes.find("missing-theme"))
        assertSame(TerminalThemes.current, TerminalThemes.find(TerminalThemes.CURRENT_ID))
    }

    @Test
    fun lookupMapsPersistedCustomThemeWithoutChangingPresetIds() {
        val custom = CustomTerminalTheme(
            id = "10000000-0000-4000-8000-000000000001",
            name = "Ocean",
            foregroundArgb = 0xffeeeeee.toInt(),
            backgroundArgb = 0xff101820.toInt(),
            cursorArgb = 0xffffffff.toInt(),
            selectionArgb = 0xff304050.toInt(),
            ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
            boldUsesBrightColours = false,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val resolved = TerminalThemes.find(custom.id, listOf(custom))

        assertEquals(custom.id, resolved.id)
        assertEquals(custom.name, resolved.displayName)
        assertEquals(custom.ansi16Argb, resolved.ansi16)
        assertEquals(false, resolved.boldUsesBrightColours)
        assertSame(TerminalThemes.current, TerminalThemes.find("missing", listOf(custom)))
    }

    @Test
    fun highContrastPaletteKeepsForegroundAndBackgroundDistinct() {
        val theme = TerminalThemes.find(TerminalThemes.HIGH_CONTRAST_ID)
        assertNotEquals(theme.background, theme.foreground)
        assertNotEquals(theme.background, theme.cursor)
    }

    @Test
    fun themeResolutionPreservesTrueColourProvenanceEvenWhenRgbMatchesAnsi() {
        val selected = TerminalThemes.find("dracula")
        val matchingCurrentRed = TerminalPalette.xtermColour(1)
        val direct = TerminalStyle(
            foreground = TerminalColour.Rgb(matchingCurrentRed),
        )
        val indexed = TerminalStyle(
            foreground = TerminalColour.Indexed(1),
        )

        assertEquals(matchingCurrentRed, selected.resolveForeground(direct))
        assertEquals(matchingCurrentRed, selected.resolveForeground(direct.copy(bold = true)))
        assertEquals(selected.ansi16[1], selected.resolveForeground(indexed))
        assertEquals(
            selected.ansi16[9],
            selected.resolveForeground(indexed.copy(bold = true)),
        )
    }

    @Test
    fun boldBrighteningIsLimitedToStandardIndexedForegroundColours() {
        val brightening = TerminalThemes.find("one_dark")
        val noBrightening = brightening.copy(
            id = "one_dark_no_bright",
            boldUsesBrightColours = false,
        )

        assertEquals(
            brightening.ansi16[10],
            brightening.resolveForeground(
                TerminalStyle(foreground = TerminalColour.Indexed(2), bold = true),
            ),
        )
        assertEquals(
            brightening.ansi16[2],
            noBrightening.resolveForeground(
                TerminalStyle(foreground = TerminalColour.Indexed(2), bold = true),
            ),
        )
        assertEquals(
            brightening.ansi16[2],
            brightening.resolveBackground(
                TerminalStyle(background = TerminalColour.Indexed(2), bold = true),
            ),
        )
        assertEquals(
            TerminalPalette.xtermColour(202),
            brightening.resolveForeground(
                TerminalStyle(foreground = TerminalColour.Indexed(202), bold = true),
            ),
        )
    }

    @Test
    fun rendererResolutionUsesThemeDefaultsAndAppliesInverseAfterResolution() {
        val theme = TerminalThemes.find("solarized_light")
        val normal = TerminalStyle()
        val inverse = normal.copy(inverse = true)

        assertEquals(theme.foreground, theme.resolveDrawForeground(normal))
        assertEquals(theme.background, theme.resolveDrawBackground(normal))
        assertEquals(theme.background, theme.resolveDrawForeground(inverse))
        assertEquals(theme.foreground, theme.resolveDrawBackground(inverse))
    }
}
