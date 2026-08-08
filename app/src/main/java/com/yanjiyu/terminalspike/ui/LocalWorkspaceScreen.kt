package com.yanjiyu.terminalspike.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile

internal enum class AppDestination {
    WORKSPACE,
    TERMINAL,
    TOOLS,
}

private enum class WorkspaceGlyph {
    WORKSPACE,
    HOSTS,
    KEYCHAIN,
    SNIPPETS,
    SHIELD,
    KEYBOARD,
    LAB,
    TERMINAL,
    TOOLS,
}

private val WorkspaceBackground = Color(0xFF171A2B)
private val WorkspaceSurface = Color(0xFF23273B)
private val WorkspaceSurfacePressed = Color(0xFF2A3047)
private val WorkspaceNav = Color(0xFF202438)
private val WorkspaceAccent = Color(0xFF6BA9F2)
private val WorkspaceText = Color(0xFFF5F6FB)
private val WorkspaceMuted = Color(0xFF9DA5B8)
private val WorkspaceDivider = Color(0xFF34394F)

@Composable
internal fun LocalWorkspaceScreen(
    profiles: List<SavedSshProfile>,
    identities: List<SavedSshIdentity>,
    snippets: List<CommandSnippet>,
    knownHostCount: Int,
    visibleKeyCount: Int,
    settingsReady: Boolean,
    onOpenSection: (ToolSection) -> Unit,
    onOpenTerminal: () -> Unit,
    onQuickConnect: () -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WorkspaceBackground),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            WorkspaceHeader(settingsReady = settingsReady)

            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 8 }),
            ) {
                Column {
                    WorkspaceGroup {
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.HOSTS,
                            title = "Hosts",
                            detail = "Saved SSH endpoints",
                            count = profiles.size,
                            onClick = { onOpenSection(ToolSection.PROFILES) },
                        )
                        WorkspaceDivider()
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.KEYCHAIN,
                            title = "Keychain",
                            detail = "Private identities stored locally",
                            count = identities.size,
                            onClick = { onOpenSection(ToolSection.IDENTITIES) },
                        )
                        WorkspaceDivider()
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.SNIPPETS,
                            title = "Snippets",
                            detail = "Reusable terminal commands",
                            count = snippets.size,
                            onClick = { onOpenSection(ToolSection.SNIPPETS) },
                        )
                        WorkspaceDivider()
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.SHIELD,
                            title = "Known hosts",
                            detail = "Trusted SSH fingerprints",
                            count = knownHostCount,
                            onClick = { onOpenSection(ToolSection.IDENTITIES) },
                        )
                    }

                    Text(
                        text = "TERMINAL",
                        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp),
                        color = WorkspaceMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    WorkspaceGroup {
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.KEYBOARD,
                            title = "Terminal keys",
                            detail = "$visibleKeyCount keys in the quick-access deck",
                            onClick = { onOpenSection(ToolSection.KEYS) },
                        )
                        WorkspaceDivider()
                        WorkspaceRow(
                            glyph = WorkspaceGlyph.LAB,
                            title = "Renderer lab",
                            detail = "Open the native terminal workspace",
                            onClick = onOpenTerminal,
                        )
                    }

                    Surface(
                        onClick = onQuickConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp)
                            .semantics { contentDescription = "Start a new SSH connection" },
                        color = WorkspaceAccent,
                        contentColor = Color(0xFF0B1725),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "  New connection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }

        WorkspaceBottomBar(
            selected = AppDestination.WORKSPACE,
            onWorkspace = {},
            onTerminal = onOpenTerminal,
            onTools = onOpenTools,
        )
    }
}

@Composable
private fun WorkspaceHeader(settingsReady: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = WorkspaceAccent,
                contentColor = Color(0xFF0B1725),
            ) {
                WorkspaceIcon(
                    glyph = WorkspaceGlyph.WORKSPACE,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Text(
                text = "Local workspace",
                color = WorkspaceText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (settingsReady) Color(0xFF70D6A2) else WorkspaceMuted),
            )
            Text(
                text = if (settingsReady) "Encrypted on this device" else "Loading local workspace…",
                color = WorkspaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WorkspaceGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WorkspaceSurface,
        shape = RoundedCornerShape(24.dp),
        content = content,
    )
}

@Composable
private fun WorkspaceRow(
    glyph: WorkspaceGlyph,
    title: String,
    detail: String,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, label = "workspace-row-press")
    val background by animateColorAsState(
        if (pressed) WorkspaceSurfacePressed else WorkspaceSurface,
        label = "workspace-row-colour",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(background)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceIcon(
            glyph = glyph,
            modifier = Modifier.size(30.dp),
            color = WorkspaceText,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 17.dp)) {
            Text(
                text = title,
                color = WorkspaceText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                color = WorkspaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count != null) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(end = 14.dp),
                color = WorkspaceMuted,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = "›",
            color = WorkspaceMuted,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun WorkspaceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 65.dp),
        color = WorkspaceDivider,
    )
}

@Composable
internal fun WorkspaceBottomBar(
    selected: AppDestination,
    onWorkspace: () -> Unit,
    onTerminal: () -> Unit,
    onTools: () -> Unit,
) {
    Surface(color = WorkspaceNav) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            WorkspaceNavigationItem(
                label = "Workspace",
                glyph = WorkspaceGlyph.WORKSPACE,
                selected = selected == AppDestination.WORKSPACE,
                description = "Open local workspace",
                onClick = onWorkspace,
            )
            WorkspaceNavigationItem(
                label = "Terminal",
                glyph = WorkspaceGlyph.TERMINAL,
                selected = selected == AppDestination.TERMINAL,
                description = "Open terminal",
                onClick = onTerminal,
            )
            WorkspaceNavigationItem(
                label = "Tools",
                glyph = WorkspaceGlyph.TOOLS,
                selected = selected == AppDestination.TOOLS,
                description = "Open local tools",
                onClick = onTools,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.WorkspaceNavigationItem(
    label: String,
    glyph: WorkspaceGlyph,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val colour by animateColorAsState(
        if (selected) WorkspaceText else WorkspaceMuted,
        label = "workspace-nav-colour",
    )
    val iconScale by animateFloatAsState(if (selected) 1f else 0.9f, label = "workspace-nav-scale")

    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (selected) WorkspaceAccent.copy(alpha = 0.22f) else Color.Transparent,
            shape = RoundedCornerShape(15.dp),
        ) {
            WorkspaceIcon(
                glyph = glyph,
                modifier = Modifier
                    .padding(horizontal = 19.dp, vertical = 7.dp)
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                color = colour,
            )
        }
        Text(
            text = label,
            modifier = Modifier.padding(top = 3.dp),
            color = colour,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun WorkspaceIcon(
    glyph: WorkspaceGlyph,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val resolvedColor = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = minOf(w, h) * 0.095f
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            WorkspaceGlyph.WORKSPACE -> {
                drawRoundRect(
                    color = resolvedColor,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.13f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.74f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f),
                    style = stroke,
                )
                drawCircle(resolvedColor, w * 0.09f, androidx.compose.ui.geometry.Offset(w * 0.37f, h * 0.5f), style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.42f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.42f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.56f, h * 0.58f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.58f), strokeWidth, StrokeCap.Round)
            }
            WorkspaceGlyph.HOSTS -> {
                listOf(0.18f, 0.56f).forEach { y ->
                    drawRoundRect(
                        resolvedColor,
                        androidx.compose.ui.geometry.Offset(w * 0.08f, h * y),
                        androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.27f),
                        androidx.compose.ui.geometry.CornerRadius(w * 0.07f),
                        style = stroke,
                    )
                    drawCircle(resolvedColor, w * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.23f, h * (y + 0.135f)))
                    drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.39f, h * (y + 0.135f)), androidx.compose.ui.geometry.Offset(w * 0.78f, h * (y + 0.135f)), strokeWidth * 0.7f, StrokeCap.Round)
                }
            }
            WorkspaceGlyph.KEYCHAIN -> {
                drawCircle(resolvedColor, w * 0.19f, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.34f), style = stroke)
                val path = Path().apply {
                    moveTo(w * 0.47f, h * 0.47f)
                    lineTo(w * 0.82f, h * 0.82f)
                    moveTo(w * 0.65f, h * 0.65f)
                    lineTo(w * 0.55f, h * 0.75f)
                    moveTo(w * 0.75f, h * 0.75f)
                    lineTo(w * 0.65f, h * 0.85f)
                }
                drawPath(path, resolvedColor, style = stroke)
            }
            WorkspaceGlyph.SNIPPETS -> {
                val left = Path().apply {
                    moveTo(w * 0.38f, h * 0.12f)
                    cubicTo(w * 0.20f, h * 0.12f, w * 0.28f, h * 0.40f, w * 0.12f, h * 0.5f)
                    cubicTo(w * 0.28f, h * 0.60f, w * 0.20f, h * 0.88f, w * 0.38f, h * 0.88f)
                }
                val right = Path().apply {
                    moveTo(w * 0.62f, h * 0.12f)
                    cubicTo(w * 0.80f, h * 0.12f, w * 0.72f, h * 0.40f, w * 0.88f, h * 0.5f)
                    cubicTo(w * 0.72f, h * 0.60f, w * 0.80f, h * 0.88f, w * 0.62f, h * 0.88f)
                }
                drawPath(left, resolvedColor, style = stroke)
                drawPath(right, resolvedColor, style = stroke)
            }
            WorkspaceGlyph.SHIELD -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.84f, h * 0.23f)
                    lineTo(w * 0.78f, h * 0.65f)
                    quadraticTo(w * 0.7f, h * 0.84f, w * 0.5f, h * 0.94f)
                    quadraticTo(w * 0.3f, h * 0.84f, w * 0.22f, h * 0.65f)
                    lineTo(w * 0.16f, h * 0.23f)
                    close()
                }
                drawPath(path, resolvedColor, style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.51f), androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.64f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.38f), strokeWidth, StrokeCap.Round)
            }
            WorkspaceGlyph.KEYBOARD -> {
                drawRoundRect(
                    resolvedColor,
                    androidx.compose.ui.geometry.Offset(w * 0.07f, h * 0.2f),
                    androidx.compose.ui.geometry.Size(w * 0.86f, h * 0.62f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                for (row in 0..1) for (column in 0..3) {
                    drawCircle(
                        resolvedColor,
                        strokeWidth * 0.36f,
                        androidx.compose.ui.geometry.Offset(w * (0.23f + column * 0.18f), h * (0.38f + row * 0.18f)),
                    )
                }
            }
            WorkspaceGlyph.LAB -> {
                drawArc(resolvedColor, 205f, 130f, false, style = stroke)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.35f), strokeWidth, StrokeCap.Round)
                drawCircle(resolvedColor, strokeWidth * 0.65f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f))
            }
            WorkspaceGlyph.TERMINAL -> {
                drawRoundRect(
                    resolvedColor,
                    androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.16f),
                    androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.68f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.09f),
                    style = stroke,
                )
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.4f), androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.27f, h * 0.6f), strokeWidth, StrokeCap.Round)
                drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.62f), strokeWidth, StrokeCap.Round)
            }
            WorkspaceGlyph.TOOLS -> {
                listOf(0.27f, 0.5f, 0.73f).forEachIndexed { index, y ->
                    drawLine(resolvedColor, androidx.compose.ui.geometry.Offset(w * 0.14f, h * y), androidx.compose.ui.geometry.Offset(w * 0.86f, h * y), strokeWidth, StrokeCap.Round)
                    val x = listOf(0.35f, 0.67f, 0.45f)[index]
                    drawCircle(resolvedColor, w * 0.08f, androidx.compose.ui.geometry.Offset(w * x, h * y), style = stroke)
                }
            }
        }
    }
}
