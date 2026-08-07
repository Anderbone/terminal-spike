package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TerminalColourScheme = darkColorScheme(
    primary = TerminalGreen,
    secondary = TerminalBlue,
    tertiary = TerminalAmber,
    background = TerminalBackground,
    surface = TerminalSurface,
    surfaceVariant = TerminalSurfaceRaised,
    onPrimary = TerminalBackground,
    onBackground = TerminalText,
    onSurface = TerminalText,
    onSurfaceVariant = TerminalMuted,
)

@Composable
fun TerminalSpikeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalColourScheme,
        typography = TerminalTypography,
        content = content,
    )
}
