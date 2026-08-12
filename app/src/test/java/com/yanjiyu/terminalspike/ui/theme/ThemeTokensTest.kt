package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {
    @Test
    fun systemThemeTracksPlatformWhileExplicitModesDoNot() {
        assertEquals(false, resolveDarkTheme(AppThemeMode.SYSTEM, systemDarkTheme = false))
        assertEquals(true, resolveDarkTheme(AppThemeMode.SYSTEM, systemDarkTheme = true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, systemDarkTheme = true))
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, systemDarkTheme = false))
    }

    @Test
    fun appColourSchemesKeepAccessibleCoreContentPairs() {
        listOf(AppLightColourScheme, AppDarkColourScheme).forEach { scheme ->
            assertContrast(scheme.primary, scheme.onPrimary)
            assertContrast(scheme.primaryContainer, scheme.onPrimaryContainer)
            assertContrast(scheme.secondary, scheme.onSecondary)
            assertContrast(scheme.secondaryContainer, scheme.onSecondaryContainer)
            assertContrast(scheme.tertiary, scheme.onTertiary)
            assertContrast(scheme.tertiaryContainer, scheme.onTertiaryContainer)
            assertContrast(scheme.background, scheme.onBackground)
            assertContrast(scheme.surface, scheme.onSurface)
            assertContrast(scheme.surfaceVariant, scheme.onSurfaceVariant)
            assertContrast(scheme.error, scheme.onError)
            assertContrast(scheme.errorContainer, scheme.onErrorContainer)
        }
    }

    @Test
    fun everySavedAccentKeepsAccessiblePrimaryPairsInBothModes() {
        listOf("mint", "blue", "violet", "amber", "coral").forEach { accent ->
            listOf(false, true).forEach { darkTheme ->
                val scheme = appColourScheme(darkTheme, accent)
                assertContrast(scheme.primary, scheme.onPrimary)
                assertContrast(scheme.primaryContainer, scheme.onPrimaryContainer)
            }
        }
        assertEquals(
            appColourScheme(darkTheme = false, accentPreset = "mint"),
            appColourScheme(darkTheme = false, accentPreset = "unknown"),
        )
    }

    @Test
    fun tokenScalesAreOrderedAndMotionRemainsSubtle() {
        val spacingValues = with(DefaultAppSpacing) {
            listOf(hairline, extraSmall, small, medium, large, extraLarge, section, page)
        }.map { it.value }
        assertEquals(spacingValues.sorted(), spacingValues)
        assertTrue(DefaultAppIconMetrics.minimumTouchTarget.value >= 48f)
        assertTrue(DefaultAppMotion.quickDurationMillis in 1..DefaultAppMotion.standardDurationMillis)
        assertTrue(
            DefaultAppMotion.standardDurationMillis < DefaultAppMotion.emphasizedDurationMillis,
        )
        assertTrue(DefaultAppMotion.emphasizedDurationMillis <= 400)
    }

    @Test
    fun semanticStatusAndFeedbackPairsMeetTextContrast() {
        listOf(LightAppStatusColors, DarkAppStatusColors).forEach { colors ->
            assertContrast(colors.connected, colors.onConnected)
            assertContrast(colors.connectedContainer, colors.onConnectedContainer)
            assertContrast(colors.reconnecting, colors.onReconnecting)
            assertContrast(colors.reconnectingContainer, colors.onReconnectingContainer)
            assertContrast(colors.warning, colors.onWarning)
            assertContrast(colors.warningContainer, colors.onWarningContainer)
            assertContrast(colors.error, colors.onError)
            assertContrast(colors.errorContainer, colors.onErrorContainer)
        }
        listOf(LightAppFeedbackColors, DarkAppFeedbackColors).forEach { colors ->
            assertContrast(colors.success, colors.onSuccess)
            assertContrast(colors.information, colors.onInformation)
            assertContrast(colors.warning, colors.onWarning)
            assertContrast(colors.error, colors.onError)
        }
    }

    private fun assertContrast(background: Color, foreground: Color) {
        val lighter = maxOf(background.luminance(), foreground.luminance())
        val darker = minOf(background.luminance(), foreground.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("Expected 4.5:1 contrast, was $ratio", ratio >= 4.5f)
    }
}
