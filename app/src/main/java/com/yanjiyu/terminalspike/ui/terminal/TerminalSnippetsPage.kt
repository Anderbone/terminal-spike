package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.yanjiyu.terminalspike.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDialog
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDraft

@Composable
internal fun TerminalSnippetsPage(
    snippets: List<CommandSnippet>,
    canSave: Boolean,
    canSend: Boolean,
    onSend: (Long) -> Unit,
    onNewCodex: () -> Unit,
    onSave: suspend (Snippet) -> Result<Unit>,
    onEditorClosed: () -> Unit,
) {
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextButton(enabled = canSave, onClick = { editorVisible = true }) {
            Text(stringResource(R.string.snippet_accessory_add))
        }
        snippets.forEach { snippet ->
            TextButton(enabled = canSend, onClick = { onSend(snippet.id) }) {
                Text(snippet.label)
            }
        }
        TextButton(enabled = canSend, onClick = onNewCodex) {
            Text(stringResource(R.string.snippet_accessory_example))
        }
    }
    if (editorVisible) {
        SnippetEditorDialog(
            initial = SnippetEditorDraft(),
            onDismiss = { editorVisible = false; onEditorClosed() },
            onSave = onSave,
        )
    }
}
