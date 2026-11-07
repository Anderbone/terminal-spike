package com.yanjiyu.terminalspike.ui.releasenotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R

@Composable
internal fun ReleaseNotesDialog(
    notes: List<ReleaseNotes>,
    onDismiss: () -> Unit,
    history: List<ReleaseNotes>? = null,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    onDismissWithoutSaving: () -> Unit = onDismiss,
) {
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    val isHistory = history == null || showingHistory
    val visibleNotes = if (showingHistory) history.orEmpty() else notes
    AlertDialog(
        modifier = Modifier.testTag("release-notes-dialog"),
        onDismissRequest = {
            if (!saving) {
                if (saveFailed) onDismissWithoutSaving() else onDismiss()
            }
        },
        title = {
            Text(stringResource(if (isHistory) R.string.release_notes_history_title else R.string.release_notes_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).weight(1f, fill = false)
                        .testTag("release-notes-list"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (visibleNotes.isEmpty()) item { Text(stringResource(R.string.release_notes_empty)) }
                    visibleNotes.forEachIndexed { index, release ->
                        if (index > 0) item(key = "divider-${release.revision}") { HorizontalDivider() }
                        item(key = release.revision) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    stringResource(R.string.release_notes_version, release.versionName),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() },
                                )
                                release.sections.forEach { section ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            stringResource(section.title),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.semantics { heading() },
                                        )
                                        section.items.forEach { text ->
                                            Row {
                                                Text("\u2022", modifier = Modifier.padding(end = 8.dp))
                                                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (saveFailed) {
                    Text(
                        stringResource(R.string.release_notes_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(if (history == null) R.string.release_notes_close else R.string.release_notes_got_it))
            }
        },
        dismissButton = {
            if (saveFailed) {
                TextButton(onClick = onDismissWithoutSaving) {
                    Text(stringResource(R.string.not_now))
                }
            } else if (history != null && !showingHistory) {
                TextButton(onClick = { showingHistory = true }, enabled = !saving) {
                    Text(stringResource(R.string.release_notes_history))
                }
            }
        },
    )
}
