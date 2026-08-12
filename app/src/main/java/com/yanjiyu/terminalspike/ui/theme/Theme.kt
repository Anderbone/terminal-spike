package com.yanjiyu.terminalspike.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

internal val AppLightColourScheme = lightColorScheme(
    primary = AppLightPrimary,
    onPrimary = AppLightOnPrimary,
    primaryContainer = AppLightPrimaryContainer,
    onPrimaryContainer = AppLightOnPrimaryContainer,
    inversePrimary = AppDarkPrimary,
    secondary = AppLightSecondary,
    onSecondary = AppLightOnSecondary,
    secondaryContainer = AppLightSecondaryContainer,
    onSecondaryContainer = AppLightOnSecondaryContainer,
    tertiary = AppLightTertiary,
    onTertiary = AppLightOnTertiary,
    tertiaryContainer = AppLightTertiaryContainer,
    onTertiaryContainer = AppLightOnTertiaryContainer,
    background = AppLightBackground,
    onBackground = AppLightOnBackground,
    surface = AppLightSurface,
    onSurface = AppLightOnSurface,
    surfaceVariant = AppLightSurfaceVariant,
    onSurfaceVariant = AppLightOnSurfaceVariant,
    surfaceTint = AppLightPrimary,
    inverseSurface = AppLightInverseSurface,
    inverseOnSurface = AppLightInverseOnSurface,
    error = AppLightError,
    onError = AppLightOnError,
    errorContainer = AppLightErrorContainer,
    onErrorContainer = AppLightOnErrorContainer,
    outline = AppLightOutline,
    outlineVariant = AppLightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = AppLightSurfaceBright,
    surfaceContainer = AppLightSurfaceContainer,
    surfaceContainerHigh = AppLightSurfaceContainerHigh,
    surfaceContainerHighest = AppLightSurfaceContainerHighest,
    surfaceContainerLow = AppLightSurfaceContainerLow,
    surfaceContainerLowest = AppLightSurfaceContainerLowest,
    surfaceDim = AppLightSurfaceDim,
)

internal val AppDarkColourScheme = darkColorScheme(
    primary = AppDarkPrimary,
    onPrimary = AppDarkOnPrimary,
    primaryContainer = AppDarkPrimaryContainer,
    onPrimaryContainer = AppDarkOnPrimaryContainer,
    inversePrimary = AppLightPrimary,
    secondary = AppDarkSecondary,
    onSecondary = AppDarkOnSecondary,
    secondaryContainer = AppDarkSecondaryContainer,
    onSecondaryContainer = AppDarkOnSecondaryContainer,
    tertiary = AppDarkTertiary,
    onTertiary = AppDarkOnTertiary,
    tertiaryContainer = AppDarkTertiaryContainer,
    onTertiaryContainer = AppDarkOnTertiaryContainer,
    background = AppDarkBackground,
    onBackground = AppDarkOnBackground,
    surface = AppDarkSurface,
    onSurface = AppDarkOnSurface,
    surfaceVariant = AppDarkSurfaceVariant,
    onSurfaceVariant = AppDarkOnSurfaceVariant,
    surfaceTint = AppDarkPrimary,
    inverseSurface = AppDarkInverseSurface,
    inverseOnSurface = AppDarkInverseOnSurface,
    error = AppDarkError,
    onError = AppDarkOnError,
    errorContainer = AppDarkErrorContainer,
    onErrorContainer = AppDarkOnErrorContainer,
    outline = AppDarkOutline,
    outlineVariant = AppDarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = AppDarkSurfaceBright,
    surfaceContainer = AppDarkSurfaceContainer,
    surfaceContainerHigh = AppDarkSurfaceContainerHigh,
    surfaceContainerHighest = AppDarkSurfaceContainerHighest,
    surfaceContainerLow = AppDarkSurfaceContainerLow,
    surfaceContainerLowest = AppDarkSurfaceContainerLowest,
    surfaceDim = AppDarkSurfaceDim,
)

internal fun resolveDarkTheme(mode: AppThemeMode, systemDarkTheme: Boolean): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> systemDarkTheme
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

internal fun appColourScheme(
    darkTheme: Boolean,
    accentPreset: String,
): ColorScheme {
    val base = if (darkTheme) AppDarkColourScheme else AppLightColourScheme
    val accent = AppAccentPalette.fromWireCode(accentPreset)
    if (accent == AppAccentPalette.MINT) return base
    val colors = if (darkTheme) accent.dark else accent.light
    return base.copy(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer,
        inversePrimary = colors.inversePrimary,
        surfaceTint = colors.primary,
    )
}

@Composable
fun TerminalSpikeTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColorEnabled: Boolean = false,
    accentPreset: String = "mint",
    systemDarkTheme: Boolean = isSystemInDarkTheme(),
    updateSystemBarIcons: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode, systemDarkTheme)
    val context = LocalContext.current
    val colourScheme = when {
        dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicLightColorScheme(context)
        }
        else -> appColourScheme(darkTheme, accentPreset)
    }
    if (updateSystemBarIcons) {
        SystemBarIconAppearance(useDarkIcons = !darkTheme)
    }

    CompositionLocalProvider(
        LocalAppSpacing provides DefaultAppSpacing,
        LocalAppIconMetrics provides DefaultAppIconMetrics,
        LocalAppStatusColors provides if (darkTheme) DarkAppStatusColors else LightAppStatusColors,
        LocalAppFeedbackColors provides if (darkTheme) DarkAppFeedbackColors else LightAppFeedbackColors,
        LocalAppMotion provides DefaultAppMotion,
    ) {
        MaterialTheme(
            colorScheme = colourScheme,
            shapes = TerminalShapes,
            typography = TerminalTypography,
            content = content,
        )
    }
}

private data class AccentColours(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
)

private enum class AppAccentPalette(
    val wireCode: String,
    val light: AccentColours,
    val dark: AccentColours,
) {
    MINT(
        wireCode = "mint",
        light = AccentColours(
            AppLightPrimary,
            AppLightOnPrimary,
            AppLightPrimaryContainer,
            AppLightOnPrimaryContainer,
            AppDarkPrimary,
        ),
        dark = AccentColours(
            AppDarkPrimary,
            AppDarkOnPrimary,
            AppDarkPrimaryContainer,
            AppDarkOnPrimaryContainer,
            AppLightPrimary,
        ),
    ),
    BLUE(
        wireCode = "blue",
        light = AccentColours(
            Color(0xFF005FAF),
            Color.White,
            Color(0xFFD4E3FF),
            Color(0xFF001C3A),
            Color(0xFFA7C8FF),
        ),
        dark = AccentColours(
            Color(0xFFA7C8FF),
            Color(0xFF00315C),
            Color(0xFF004785),
            Color(0xFFD4E3FF),
            Color(0xFF005FAF),
        ),
    ),
    VIOLET(
        wireCode = "violet",
        light = AccentColours(
            Color(0xFF6750A4),
            Color.White,
            Color(0xFFEADDFF),
            Color(0xFF21005D),
            Color(0xFFD0BCFF),
        ),
        dark = AccentColours(
            Color(0xFFD0BCFF),
            Color(0xFF381E72),
            Color(0xFF4F378B),
            Color(0xFFEADDFF),
            Color(0xFF6750A4),
        ),
    ),
    AMBER(
        wireCode = "amber",
        light = AccentColours(
            Color(0xFF765A00),
            Color.White,
            Color(0xFFFFE082),
            Color(0xFF251A00),
            Color(0xFFE8C35C),
        ),
        dark = AccentColours(
            Color(0xFFE8C35C),
            Color(0xFF3D2E00),
            Color(0xFF594400),
            Color(0xFFFFE082),
            Color(0xFF765A00),
        ),
    ),
    CORAL(
        wireCode = "coral",
        light = AccentColours(
            Color(0xFF9C4146),
            Color.White,
            Color(0xFFFFDADB),
            Color(0xFF40000A),
            Color(0xFFFFB2B4),
        ),
        dark = AccentColours(
            Color(0xFFFFB2B4),
            Color(0xFF5F131B),
            Color(0xFF7D2930),
            Color(0xFFFFDADB),
            Color(0xFF9C4146),
        ),
    ),
    ;

    companion object {
        fun fromWireCode(value: String): AppAccentPalette = entries.firstOrNull {
            it.wireCode == value
        } ?: MINT
    }
}

@Composable
private fun SystemBarIconAppearance(useDarkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
