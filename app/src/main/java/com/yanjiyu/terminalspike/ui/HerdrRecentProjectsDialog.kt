package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.RecentHerdrProject
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HerdrRecentProjectsDialog(controller: TerminalController) {
    DisposableEffect(controller) { onDispose { controller.dismissHerdrProjectPicker() } }
    val connection by controller.herdrProjectPicker.collectAsStateWithLifecycle()
    val source = connection ?: return
    val cache = controller.herdrProjectsFor(source) ?: return
    val cached by cache.state.collectAsStateWithLifecycle()
    var openingPath by remember(source) { mutableStateOf<String?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var query by remember(source) { mutableStateOf("") }
    var now by remember(source) { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    HerdrRecentProjectsPicker(
        projects = cached.projects, loading = cached.refreshing, openingPath = openingPath, failed = failed,
        query = query, nowEpochMillis = now, onQueryChange = { query = it },
        onDismiss = controller::dismissHerdrProjectPicker,
        onOpen = { project ->
            openingPath = project.path
            failed = false
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    // A disconnected or replaced transport must not receive a stale selection.
                    controller.herdrProjectPicker.value === source &&
                        runCatching { source.openHerdrProject(project.path) }.getOrDefault(false)
                }
                openingPath = null
                if (controller.herdrProjectPicker.value === source) {
                    if (success) {
                        cache.projectOpened(project.path)
                        controller.completeHerdrProjectSelection(source)
                    } else failed = true
                }
            }
        },
    )
}

@Composable
internal fun HerdrRecentProjectsPicker(
    projects: List<RecentHerdrProject>?,
    loading: Boolean,
    openingPath: String?,
    failed: Boolean,
    query: String,
    nowEpochMillis: Long,
    onQueryChange: (String) -> Unit,
    onOpen: (RecentHerdrProject) -> Unit,
    onDismiss: () -> Unit,
) {
    val opening = openingPath != null
    val filtered = projects.orEmpty().filter { it.path.contains(query, ignoreCase = true) }
    // A filtered result set starts at its newest row; do not retain a different query's anchor.
    val listState = remember(query) { LazyListState() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    val searchDescription = stringResource(R.string.herdr_project_search)
    val clearDescription = stringResource(R.string.herdr_project_clear_search)
    AppDialog(
        onDismissRequest = { if (!opening) onDismiss() },
        title = { Text(stringResource(R.string.herdr_recent_projects)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    placeholder = { Text(stringResource(R.string.herdr_project_search)) },
                    leadingIcon = { ConnectionsGlyphIcon(ConnectionsGlyph.SEARCH, Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, enabled = !opening,
                            modifier = Modifier.semantics { contentDescription = clearDescription }) {
                            ConnectionsGlyphIcon(ConnectionsGlyph.CLOSE, Modifier.size(20.dp))
                        }
                    },
                    enabled = !opening, modifier = Modifier.fillMaxWidth().testTag("recent-project-search")
                        .semantics { contentDescription = searchDescription },
                    shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyMedium,
                    colors = com.yanjiyu.terminalspike.ui.theme.appSearchFieldColors(),
                )
                when {
                    loading && projects == null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.herdr_projects_loading), color = muted)
                    }
                    projects == null -> Text(stringResource(R.string.herdr_projects_unavailable), color = muted)
                    projects.isEmpty() -> Text(stringResource(R.string.herdr_projects_empty), color = muted)
                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.herdr_project_order_recent), style = MaterialTheme.typography.labelSmall, color = muted)
                            Text(stringResource(R.string.herdr_project_last_used), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                        if (filtered.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.herdr_projects_no_matches), color = muted)
                            }
                        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("recent-project-list"), state = listState) {
                            itemsIndexed(filtered, key = { _, project -> project.path }) { index, project ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                Row(
                                    Modifier.fillMaxWidth().testTag("recent-project:${project.path}").clickable(enabled = !opening) { onOpen(project) }
                                        .heightIn(min = 64.dp).padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(project.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(project.path.substringBeforeLast('/').ifEmpty { "/" },
                                            modifier = Modifier.clearAndSetSemantics { contentDescription = project.path },
                                            style = MaterialTheme.typography.bodySmall, color = muted,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Box(Modifier.width(92.dp).padding(top = 2.dp), contentAlignment = Alignment.TopEnd) {
                                        if (openingPath == project.path) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            val date = project.lastUsedAtEpochMillis?.let {
                                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it))
                                            }
                                            val description = if (date != null) stringResource(R.string.herdr_project_last_used_description, date)
                                                else stringResource(R.string.herdr_project_time_unknown)
                                            Text(formatProjectLastUsed(project.lastUsedAtEpochMillis, nowEpochMillis, locale).resolve(LocalResources.current),
                                                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                                                style = MaterialTheme.typography.labelSmall, color = muted,
                                                textAlign = TextAlign.End, maxLines = 2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (opening) Text(stringResource(R.string.herdr_project_opening), color = muted)
                if (failed) Text(stringResource(R.string.herdr_project_open_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !opening) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

internal fun formatProjectLastUsed(lastUsedAt: Long?, now: Long, locale: Locale): UiText {
    if (lastUsedAt == null || lastUsedAt <= 0) return uiText(R.string.herdr_project_time_unknown)
    val elapsed = (now - lastUsedAt).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> uiText(R.string.herdr_project_just_now)
        elapsed < 3_600_000L -> quantityText(R.plurals.herdr_project_minutes_ago, (elapsed / 60_000L).toInt())
        elapsed < 86_400_000L -> quantityText(R.plurals.herdr_project_hours_ago, (elapsed / 3_600_000L).toInt())
        elapsed < 7 * 86_400_000L -> quantityText(R.plurals.herdr_project_days_ago, (elapsed / 86_400_000L).toInt())
        else -> UiText.Dynamic(DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(lastUsedAt)))
    }
}
