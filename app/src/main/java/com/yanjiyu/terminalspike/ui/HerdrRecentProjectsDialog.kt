package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.yanjiyu.terminalspike.ui.theme.AppDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.TerminalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HerdrRecentProjectsDialog(controller: TerminalController) {
    DisposableEffect(controller) { onDispose { controller.dismissHerdrProjectPicker() } }
    val connection by controller.herdrProjectPicker.collectAsStateWithLifecycle()
    val source = connection ?: return
    var projects by remember(source) { mutableStateOf<List<String>?>(null) }
    var loading by remember(source) { mutableStateOf(true) }
    var opening by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    var query by remember(source) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        projects = withContext(Dispatchers.IO) { runCatching { source.recentHerdrProjects() }.getOrNull() }
        loading = false
    }
    val projectPaths = projects.orEmpty()
    AppDialog(
        onDismissRequest = { if (!opening) controller.dismissHerdrProjectPicker() },
        title = { Text(stringResource(R.string.herdr_recent_projects)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.herdr_project_search)) },
                    enabled = !opening, modifier = Modifier.fillMaxWidth(),
                    colors = com.yanjiyu.terminalspike.ui.theme.appSearchFieldColors(),
                )
                when {
                    loading -> Text(stringResource(R.string.herdr_projects_loading))
                    projects == null -> Text(stringResource(R.string.herdr_projects_unavailable))
                    projectPaths.isEmpty() -> Text(stringResource(R.string.herdr_projects_empty))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(projectPaths.filter { it.contains(query, ignoreCase = true) }, key = { it }) { path ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !opening) {
                                opening = true
                                failed = false
                                scope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        // A disconnected or replaced transport must not receive a stale selection.
                                        controller.herdrProjectPicker.value === source &&
                                            runCatching { source.openHerdrProject(path) }.getOrDefault(false)
                                    }
                                    opening = false
                                    if (controller.herdrProjectPicker.value === source) {
                                        if (success) controller.completeHerdrProjectSelection(source) else failed = true
                                    }
                                }
                            }.padding(vertical = 12.dp)) {
                                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                                Text(path, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (opening) Text(stringResource(R.string.herdr_project_opening))
                if (failed) Text(stringResource(R.string.herdr_project_open_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = controller::dismissHerdrProjectPicker, enabled = !opening) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
