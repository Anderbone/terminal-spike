package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A deliberately small spacing scale for application chrome and management screens. */
@Immutable
data class AppSpacing(
    val hairline: Dp = 2.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val section: Dp = 32.dp,
    val page: Dp = 40.dp,
)

/** Shared icon geometry and the minimum interactive target used around icons. */
@Immutable
data class AppIconMetrics(
    val compact: Dp = 18.dp,
    val standard: Dp = 24.dp,
    val prominent: Dp = 32.dp,
    val minimumTouchTarget: Dp = 48.dp,
)

/** Semantic connection-state colours, independent from any terminal ANSI palette. */
@Immutable
data class AppStatusColors(
    val connected: Color,
    val onConnected: Color,
    val connectedContainer: Color,
    val onConnectedContainer: Color,
    val reconnecting: Color,
    val onReconnecting: Color,
    val reconnectingContainer: Color,
    val onReconnectingContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val disconnected: Color,
    val onDisconnected: Color,
)

/** Colours for inline messages, banners, and snackbars. */
@Immutable
data class AppFeedbackColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val information: Color,
    val onInformation: Color,
    val informationContainer: Color,
    val onInformationContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

/** Millisecond values suitable for Compose tween-based application-shell transitions. */
@Immutable
data class AppMotion(
    val quickDurationMillis: Int = 100,
    val standardDurationMillis: Int = 200,
    val emphasizedDurationMillis: Int = 300,
)

val TerminalShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

internal val DefaultAppSpacing = AppSpacing()
internal val DefaultAppIconMetrics = AppIconMetrics()
internal val DefaultAppMotion = AppMotion()

internal val LightAppStatusColors = AppStatusColors(
    connected = AppLightPrimary,
    onConnected = AppLightOnPrimary,
    connectedContainer = AppLightPrimaryContainer,
    onConnectedContainer = AppLightOnPrimaryContainer,
    reconnecting = AppLightSecondary,
    onReconnecting = AppLightOnSecondary,
    reconnectingContainer = AppLightSecondaryContainer,
    onReconnectingContainer = AppLightOnSecondaryContainer,
    warning = AppLightTertiary,
    onWarning = AppLightOnTertiary,
    warningContainer = AppLightTertiaryContainer,
    onWarningContainer = AppLightOnTertiaryContainer,
    error = AppLightError,
    onError = AppLightOnError,
    errorContainer = AppLightErrorContainer,
    onErrorContainer = AppLightOnErrorContainer,
    disconnected = AppLightOnSurfaceVariant,
    onDisconnected = AppLightSurface,
)

internal val DarkAppStatusColors = AppStatusColors(
    connected = AppDarkPrimary,
    onConnected = AppDarkOnPrimary,
    connectedContainer = AppDarkPrimaryContainer,
    onConnectedContainer = AppDarkOnPrimaryContainer,
    reconnecting = AppDarkSecondary,
    onReconnecting = AppDarkOnSecondary,
    reconnectingContainer = AppDarkSecondaryContainer,
    onReconnectingContainer = AppDarkOnSecondaryContainer,
    warning = AppDarkTertiary,
    onWarning = AppDarkOnTertiary,
    warningContainer = AppDarkTertiaryContainer,
    onWarningContainer = AppDarkOnTertiaryContainer,
    error = AppDarkError,
    onError = AppDarkOnError,
    errorContainer = AppDarkErrorContainer,
    onErrorContainer = AppDarkOnErrorContainer,
    disconnected = AppDarkOnSurfaceVariant,
    onDisconnected = AppDarkSurface,
)

internal val LightAppFeedbackColors = AppFeedbackColors(
    success = AppLightPrimary,
    onSuccess = AppLightOnPrimary,
    successContainer = AppLightPrimaryContainer,
    onSuccessContainer = AppLightOnPrimaryContainer,
    information = AppLightSecondary,
    onInformation = AppLightOnSecondary,
    informationContainer = AppLightSecondaryContainer,
    onInformationContainer = AppLightOnSecondaryContainer,
    warning = AppLightTertiary,
    onWarning = AppLightOnTertiary,
    warningContainer = AppLightTertiaryContainer,
    onWarningContainer = AppLightOnTertiaryContainer,
    error = AppLightError,
    onError = AppLightOnError,
    errorContainer = AppLightErrorContainer,
    onErrorContainer = AppLightOnErrorContainer,
)

internal val DarkAppFeedbackColors = AppFeedbackColors(
    success = AppDarkPrimary,
    onSuccess = AppDarkOnPrimary,
    successContainer = AppDarkPrimaryContainer,
    onSuccessContainer = AppDarkOnPrimaryContainer,
    information = AppDarkSecondary,
    onInformation = AppDarkOnSecondary,
    informationContainer = AppDarkSecondaryContainer,
    onInformationContainer = AppDarkOnSecondaryContainer,
    warning = AppDarkTertiary,
    onWarning = AppDarkOnTertiary,
    warningContainer = AppDarkTertiaryContainer,
    onWarningContainer = AppDarkOnTertiaryContainer,
    error = AppDarkError,
    onError = AppDarkOnError,
    errorContainer = AppDarkErrorContainer,
    onErrorContainer = AppDarkOnErrorContainer,
)

internal val LocalAppSpacing = staticCompositionLocalOf { DefaultAppSpacing }
internal val LocalAppIconMetrics = staticCompositionLocalOf { DefaultAppIconMetrics }
internal val LocalAppStatusColors = staticCompositionLocalOf { DarkAppStatusColors }
internal val LocalAppFeedbackColors = staticCompositionLocalOf { DarkAppFeedbackColors }
internal val LocalAppMotion = staticCompositionLocalOf { DefaultAppMotion }

val MaterialTheme.spacing: AppSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSpacing.current

val MaterialTheme.iconMetrics: AppIconMetrics
    @Composable
    @ReadOnlyComposable
    get() = LocalAppIconMetrics.current

val MaterialTheme.statusColors: AppStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppStatusColors.current

val MaterialTheme.feedbackColors: AppFeedbackColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppFeedbackColors.current

val MaterialTheme.motion: AppMotion
    @Composable
    @ReadOnlyComposable
    get() = LocalAppMotion.current
