package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** App-owned dialogs share sizing, typography and actions; platform permission pickers stay native. */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    AppDialogFrame(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text, shape, securePolicy = securePolicy)
}

@Composable
internal fun AppDialogFrame(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    maxWidth: Dp = 400.dp,
    scrollState: ScrollState? = null,
    dense: Boolean = false,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = securePolicy),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        DisposableEffect(window, light) {
            window?.let {
                val controller = WindowCompat.getInsetsController(it, view)
                controller.isAppearanceLightStatusBars = light
                controller.isAppearanceLightNavigationBars = light
                it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            onDispose { }
        }
        MaterialTheme(
            typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyMedium),
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(10.dp)),
        ) {
            var panelBounds by remember { mutableStateOf(Rect.Zero) }
            BoxWithConstraints(
                Modifier.fillMaxSize().pointerInput(onDismissRequest) {
                    detectTapGestures { point ->
                        if (!panelBounds.isEmpty && !panelBounds.contains(point)) onDismissRequest()
                    }
                }.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight)
                        .onGloballyPositioned { panelBounds = it.boundsInRoot() },
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    shadowElevation = 16.dp,
                ) {
                    Column {
                        if (icon != null || title != null) Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (dense) 12.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            icon?.invoke()
                            title?.let {
                                Box(Modifier.semantics { heading() }) {
                                    ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), it)
                                }
                            }
                        }
                        if (text != null) Box(
                            Modifier.weight(1f, fill = false).fillMaxWidth()
                                .then(if (scrollState != null) Modifier.verticalScroll(scrollState) else Modifier)
                                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
