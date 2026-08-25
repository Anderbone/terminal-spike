package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.CommandSnippet

internal const val TerminalSnippetPickerTestTag = "terminal-snippet-picker"
internal const val TerminalSnippetPickerItemTestTagPrefix = "terminal-snippet-picker-item-"

@Composable
internal fun TerminalSnippetPickerDialog(
    sessionTitle: String,
    snippets: List<CommandSnippet>,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TerminalSnippetPickerTestTag),
        title = { Text(stringResource(R.string.connections_tab_snippets)) },
        text = {
            if (snippets.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.connections_no_snippets))
                    Text(
                        text = stringResource(R.string.connections_no_snippets_detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.terminal_snippet_picker_detail, sessionTitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(snippets, key = CommandSnippet::id) { snippet ->
                            SnippetPickerRow(snippet = snippet, onSelect = onSelect)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SnippetPickerRow(
    snippet: CommandSnippet,
    onSelect: (Long) -> Unit,
) {
    val action = if (snippet.sendsImmediately) {
        stringResource(R.string.connections_action_run_now)
    } else {
        stringResource(R.string.connections_action_insert_terminal)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(snippet.id) }
            .semantics { role = Role.Button }
            .testTag(TerminalSnippetPickerItemTestTagPrefix + snippet.id)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = snippet.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snippet.command.toSnippetPreview(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = action,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun String.toSnippetPreview(): String =
    replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().take(120)
