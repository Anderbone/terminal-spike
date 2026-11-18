package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import com.yanjiyu.terminalspike.ui.settings.SettingsCategory
import com.yanjiyu.terminalspike.ui.settings.SettingsDestination
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class ToolSection {
    PROFILES,
    IDENTITIES,
    SNIPPETS,
    TERMINAL,
    KEYS,
    SECURITY,
    MOSH,
    ABOUT,
    DEVELOPER,
}

@Composable
internal fun SettingsToolsScreen(
    knownHosts: List<KnownHostSummary>,
    snippets: List<CommandSnippet>,
    moshExtension: MoshExtensionUiState,
    onNavigateBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
    onForgetKnownHost: (host: String, algorithm: String) -> Unit,
    onSaveSnippet: suspend (label: String, command: String, appendEnter: Boolean, existingId: Long?) -> Result<Unit>,
    onDeleteSnippet: (Long) -> Unit,
    onRefreshMoshExtension: () -> Unit,
    onOpenRendererLab: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    keysContent: (@Composable (String) -> Unit)? = null,
    onOpenSnippets: () -> Unit = {},
    onOpenPortForwards: () -> Unit = {},
    initialSection: ToolSection = ToolSection.PROFILES,
    terminalProfileId: String? = null,
    keyboardProfileId: String? = null,
    hiddenDefaultSnippetIds: Set<Long> = emptySet(),
    onHideDefaultSnippet: (Long) -> Unit = {},
) {
    var editingSettingsSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    var settingsSnippetEditorVisible by remember { mutableStateOf(false) }
    var deletingSettingsSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
    val allSnippets = snippets + com.yanjiyu.terminalspike.settings.DefaultTerminalSnippets.all.filter {
        it.id !in hiddenDefaultSnippetIds
    }

    SettingsDestination(
        initialCategory = initialSection.toSettingsCategory(),
        knownHosts = knownHosts,
        moshExtension = moshExtension,
        onForgetKnownHost = onForgetKnownHost,
        onRefreshMoshExtension = onRefreshMoshExtension,
        onOpenRendererLab = onOpenRendererLab,
        onNavigateBack = onNavigateBack,
        onOpenWorkspace = onOpenWorkspace,
        onOpenTerminal = onOpenTerminal,
        onOpenSettings = onOpenSettings,
        onOpenSshKeys = onOpenSshKeys,
        keysContent = keysContent,
        onOpenSnippets = onOpenSnippets,
        snippetsContent = {
            SnippetsSection(
                snippets = allSnippets,
                onAdd = { editingSettingsSnippet = null; settingsSnippetEditorVisible = true },
                onEdit = { editingSettingsSnippet = it; settingsSnippetEditorVisible = true },
                onDelete = { id -> deletingSettingsSnippet = allSnippets.firstOrNull { it.id == id } },
            )
        },
        onOpenPortForwards = onOpenPortForwards,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = keyboardProfileId,
    )
    if (settingsSnippetEditorVisible) {
        SnippetEditorDialog(
            snippet = editingSettingsSnippet,
            onDismiss = { settingsSnippetEditorVisible = false },
            onSave = { label, command, appendEnter ->
                onSaveSnippet(label, command, appendEnter, editingSettingsSnippet?.id)
            },
        )
    }
    deletingSettingsSnippet?.let { snippet ->
        SnippetDeletionConfirmationDialog(
            snippet = snippet,
            onDismiss = { deletingSettingsSnippet = null },
            onConfirm = {
                if (snippet.id <= 0) onHideDefaultSnippet(snippet.id) else onDeleteSnippet(snippet.id)
                deletingSettingsSnippet = null
            },
        )
    }
}

private fun ToolSection.toSettingsCategory(): SettingsCategory? = when (this) {
    ToolSection.TERMINAL -> SettingsCategory.TERMINAL
    ToolSection.KEYS -> SettingsCategory.KEYBOARD
    ToolSection.IDENTITIES -> SettingsCategory.SSH_KEYS
    ToolSection.SNIPPETS -> SettingsCategory.SNIPPETS
    ToolSection.SECURITY -> SettingsCategory.SECURITY
    ToolSection.MOSH -> SettingsCategory.MOSH
    ToolSection.ABOUT -> SettingsCategory.ABOUT
    ToolSection.DEVELOPER -> SettingsCategory.DEVELOPER
    ToolSection.PROFILES -> null
}

@Composable
private fun SnippetDeletionConfirmationDialog(
    snippet: CommandSnippet,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_delete_title, snippet.label)) },
        text = { Text(stringResource(R.string.connections_delete_snippet_detail)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.connections_delete_snippet)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SnippetsSection(
    snippets: List<CommandSnippet>,
    onAdd: () -> Unit,
    onEdit: (CommandSnippet) -> Unit,
    onDelete: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            SectionAction(title = stringResource(R.string.ui_command_snippets), action = stringResource(R.string.connections_add_snippet), onAction = onAdd)
        }
        if (snippets.isEmpty()) {
            item { EmptyLine(stringResource(R.string.ui_no_snippets_yet)) }
        }
        items(snippets, key = { it.id }) { snippet ->
            val deleteDescription = stringResource(R.string.ui_delete_snippet_description, snippet.label)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onEdit(snippet) }
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(snippet.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        snippet.command.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onDelete(snippet.id) },
                    modifier = Modifier.semantics {
                        contentDescription = deleteDescription
                    },
                ) {
                    ConnectionsGlyphIcon(ConnectionsGlyph.CLOSE, Modifier.size(18.dp))
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SectionAction(title: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
    )
}

internal const val SettingsSnippetEditorTestTag = "settings-snippet-editor"
internal const val SettingsSnippetSaveErrorTestTag = "settings-snippet-save-error"

@Composable
internal fun SnippetEditorDialog(
    snippet: CommandSnippet?,
    onDismiss: () -> Unit,
    onSave: suspend (label: String, command: String, appendEnter: Boolean) -> Result<Unit>,
) {
    // A completion for an editor that left composition must not dismiss a later editor.
    key(snippet?.id) {
        var label by rememberSaveable { mutableStateOf(snippet?.label.orEmpty()) }
        var command by rememberSaveable { mutableStateOf(snippet?.command.orEmpty()) }
        var appendEnter by rememberSaveable { mutableStateOf(snippet?.appendEnter ?: true) }
        var saving by remember { mutableStateOf(false) }
        var saveFailed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val sequenceValid = runCatching { com.yanjiyu.terminalspike.settings.SnippetSequence.parse(command) }.isSuccess
        val valid = sequenceValid && label.isNotBlank() && label.none(Char::isISOControl) && command.isNotBlank() &&
            command.none { it.isISOControl() && it !in "\r\n\t" }
        fun requestDismiss() {
            if (!saving) onDismiss()
        }
        AppDialog(
            onDismissRequest = ::requestDismiss,
            title = { Text(if (snippet == null) stringResource(R.string.ui_new_command_snippet) else stringResource(R.string.ui_edit_command_snippet)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag(SettingsSnippetEditorTestTag),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(UserSettings.MAX_LABEL_LENGTH) },
                        label = { Text(stringResource(R.string.session_detail_name)) },
                        singleLine = true,
                        enabled = !saving,
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it.take(UserSettings.MAX_SNIPPET_LENGTH) },
                        label = { Text(stringResource(R.string.connections_field_command)) },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !saving,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) { appendEnter = !appendEnter },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = appendEnter, onCheckedChange = { appendEnter = it }, enabled = !saving)
                        Text(stringResource(R.string.ui_press_enter_after_sending))
                    }
                    Text(stringResource(R.string.snippet_sequence_help), style = MaterialTheme.typography.bodySmall)
                    if (!sequenceValid) Text(stringResource(R.string.snippet_sequence_invalid))
                    if (saveFailed) {
                        Text(
                            stringResource(R.string.snippet_editor_save_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(SettingsSnippetSaveErrorTestTag),
                        )
                    }
                    Text(stringResource(R.string.ui_snippet_storage_summary), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    enabled = valid && !saving,
                    onClick = {
                        if (!saving) {
                            saving = true
                            saveFailed = false
                            val submittedLabel = label
                            val submittedCommand = command
                            val submittedAppendEnter = appendEnter
                            scope.launch {
                                try {
                                    val result = try {
                                        onSave(submittedLabel, submittedCommand, submittedAppendEnter)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Result.failure(error)
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (result.isSuccess) onDismiss() else saveFailed = true
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (saving) R.string.host_editor_saving else R.string.host_editor_save))
                }
            },
            dismissButton = {
                TextButton(onClick = ::requestDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
