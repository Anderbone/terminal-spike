package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon

@Composable
internal fun AppSessionSwitcherDialog(
    sessions: List<SessionTabUi>,
    previews: Map<Long, List<String>>,
    activeSessionId: Long,
    canAddSession: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewSession: () -> Unit,
) {
    WindowSwitcherDialog(
        title = stringResource(R.string.app_session_switcher_title),
        summary = null,
        onDismiss = onDismiss,
        action = {
            Button(
                onClick = {
                    onDismiss()
                    onNewSession()
                },
                enabled = canAddSession,
            ) { Text(stringResource(R.string.app_session_switcher_new)) }
        },
    ) {
        SwitcherGrid {
            items(sessions, key = SessionTabUi::id) { session ->
                TerminalTabTile(
                    session = session,
                    previewLines = previews[session.id].orEmpty(),
                    selected = session.id == activeSessionId,
                    closeDescription = stringResource(
                        R.string.session_close_tab_description,
                        session.displayedTerminalTabTitle(),
                    ).takeUnless { session.isLocalTerminal },
                    onClose = if (session.isLocalTerminal) null else {
                        { onClose(session.id) }
                    },
                    onClick = {
                        onDismiss()
                        onSelect(session.id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ActiveTmuxSessionSwitcherDialog(
    catalog: TmuxSessionCatalog?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    WindowSwitcherDialog(
        title = stringResource(R.string.tmux_session_switcher_title),
        summary = when {
            catalog == null -> stringResource(R.string.tmux_session_switcher_loading)
            catalog.availability == TmuxAvailability.NOT_INSTALLED ->
                stringResource(R.string.tmux_selector_not_installed)
            catalog.availability == TmuxAvailability.CHECK_FAILED ->
                stringResource(R.string.tmux_selector_check_failed)
            catalog.sessions.isEmpty() -> stringResource(R.string.tmux_session_switcher_empty)
            else -> null
        },
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.tmux_session_switcher_refresh))
            }
        },
    ) {
        if (catalog == null) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (catalog.deleteFailed) {
                    Text(
                        text = stringResource(R.string.tmux_selector_delete_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SwitcherGrid {
                    items(catalog.sessions, key = { it.id }) { session ->
                        TmuxSessionTile(
                            title = session.name,
                            previewLines = session.previewLines,
                            selected = catalog.activeSessionId == session.id,
                            closeDescription = stringResource(
                                R.string.tmux_session_switcher_close_description,
                                session.name,
                            ),
                            onClose = { onDelete(session.id) },
                            onClick = { onSelect(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitcherGrid(content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(124.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun WindowSwitcherDialog(
    title: String,
    summary: String?,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title)
                if (summary != null) {
                    Text(
                        text = summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        text = content,
        confirmButton = action,
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun TerminalTabTile(
    session: SessionTabUi,
    previewLines: List<String>,
    selected: Boolean,
    closeDescription: String?,
    onClose: (() -> Unit)?,
    onClick: () -> Unit,
) {
    SwitcherTile(
        title = session.displayedTerminalTabTitle(),
        selected = selected,
        closeDescription = closeDescription,
        onClose = onClose,
        onClick = onClick,
        footer = session.workspaceName.takeUnless {
            it.equals(session.displayedTerminalTabTitle(), ignoreCase = true)
        },
    ) {
        TerminalThumbnail(previewLines)
    }
}

@Composable
private fun TmuxSessionTile(
    title: String,
    previewLines: List<String>,
    selected: Boolean,
    closeDescription: String,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    SwitcherTile(
        title = title,
        selected = selected,
        closeDescription = closeDescription,
        onClose = onClose,
        onClick = onClick,
        footer = null,
    ) {
        TerminalThumbnail(previewLines)
    }
}

@Composable
private fun SwitcherTile(
    title: String,
    selected: Boolean,
    closeDescription: String?,
    onClose: (() -> Unit)?,
    onClick: () -> Unit,
    footer: String?,
    preview: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Surface(
                        modifier = Modifier.size(7.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }
                if (onClose != null && closeDescription != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable(onClick = onClose)
                            .semantics { contentDescription = closeDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        ConnectionsGlyphIcon(
                            glyph = ConnectionsGlyph.CLOSE,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 5.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                content = preview,
            )
            if (footer != null) {
                Text(
                    text = footer,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TerminalThumbnail(lines: List<String>) {
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 9.dp.toPx()
        }
        val lineHeight = paint.fontSpacing.coerceAtLeast(1f)
        val visibleCount = (size.height / lineHeight).toInt().coerceAtLeast(1)
        val preview = lines.takeLast(visibleCount).ifEmpty { listOf("▌") }
        var baseline = -paint.fontMetrics.top
        preview.forEach { line ->
            drawContext.canvas.nativeCanvas.drawText(line, 0f, baseline, paint)
            baseline += lineHeight
        }
    }
}
