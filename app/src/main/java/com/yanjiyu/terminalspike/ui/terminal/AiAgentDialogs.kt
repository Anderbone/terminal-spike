package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
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
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyph
import com.yanjiyu.terminalspike.ui.connections.ConnectionsGlyphIcon
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.AiAgent
import com.yanjiyu.terminalspike.settings.AiAgents
import com.yanjiyu.terminalspike.ui.theme.AppDialog
import com.yanjiyu.terminalspike.ui.theme.AppEditorDialog
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
internal fun AiAgentPickerDialog(
    agents: AiAgents,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_agents_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (agents.entries.isEmpty()) Text(
                    stringResource(R.string.ai_agents_empty), Modifier.padding(vertical = 16.dp),
                )
                agents.entries.forEach { agent ->
                    Row(
                        Modifier.fillMaxWidth().testTag("ai_agent_run_${agent.id}")
                            .clickable { onSelect(agent.id) }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(agent.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(agent.command, Modifier.weight(1.4f), fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        },
        dismissButton = { TextButton(onClick = onEdit) { Text(stringResource(R.string.ai_agents_edit)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_agents_close)) } },
    )
}

private val agentsSaver = listSaver<AiAgents, String>(
    save = { listOf(it.defaultId) + it.entries.flatMap { entry -> listOf(entry.id, entry.name, entry.command) } },
    restore = { values ->
        AiAgents(values.drop(1).chunked(3).map { AiAgent(it[0], it[1], it[2]) }, values[0])
    },
)

@Composable
internal fun AiAgentEditorDialog(
    agents: AiAgents,
    onSave: suspend (AiAgents) -> Boolean,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = agentsSaver) { mutableStateOf(agents) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!saving) onDismiss() }
    AppEditorDialog(
        modifier = Modifier.testTag("ai_agent_editor"),
        dense = true,
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.ai_agents_title)) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !saving) { draft = draft.copy(defaultId = "") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = draft.defaultId.isEmpty(), enabled = !saving,
                        onClick = { draft = draft.copy(defaultId = "") },
                        modifier = Modifier.testTag("ai_agent_no_default"))
                    Text(stringResource(R.string.ai_agents_no_default), style = MaterialTheme.typography.bodyMedium)
                }
                draft.entries.forEach { agent ->
                    val editing = editingId == agent.id
                    Column(Modifier.testTag("ai_agent_edit_${agent.id}")) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val defaultDescription = "${stringResource(R.string.ai_agents_default)} ${agent.name}"
                            RadioButton(
                                selected = draft.defaultId == agent.id, enabled = !saving,
                                onClick = { draft = draft.copy(defaultId = if (draft.defaultId == agent.id) "" else agent.id) },
                                modifier = Modifier.testTag("ai_agent_default_${agent.id}")
                                    .semantics { contentDescription = defaultDescription },
                            )
                            Row(
                                Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("ai_agent_open_${agent.id}")
                                    .clickable(enabled = !saving) { editingId = if (editing) null else agent.id },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    agent.name.ifBlank { stringResource(R.string.ai_agents_name) },
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    agent.command, Modifier.widthIn(max = 140.dp),
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val deleteDescription = stringResource(R.string.ai_agents_delete, agent.name)
                            IconButton(enabled = !saving, onClick = {
                                draft = draft.without(agent.id)
                                if (editing) editingId = null
                            }, modifier = Modifier.semantics { contentDescription = deleteDescription }) {
                                ConnectionsGlyphIcon(ConnectionsGlyph.DELETE, Modifier.size(18.dp), MaterialTheme.colorScheme.error)
                            }
                        }
                        AnimatedVisibility(
                            visible = editing,
                            enter = expandVertically(tween(160)) + fadeIn(tween(160)),
                            exit = shrinkVertically(tween(120)) + fadeOut(tween(120)),
                        ) {
                            Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = agent.name,
                                    onValueChange = { value ->
                                        draft = draft.copy(entries = draft.entries.map {
                                            if (it.id == agent.id) it.copy(name = value) else it
                                        })
                                    },
                                    label = { Text(stringResource(R.string.ai_agents_name)) },
                                    singleLine = true, enabled = !saving,
                                    modifier = Modifier.fillMaxWidth().testTag("ai_agent_name_${agent.id}"),
                                )
                                OutlinedTextField(
                                    value = agent.command,
                                    onValueChange = { value ->
                                        draft = draft.copy(entries = draft.entries.map {
                                            if (it.id == agent.id) it.copy(command = value) else it
                                        })
                                    },
                                    label = { Text(stringResource(R.string.ai_agents_command)) },
                                    singleLine = true, enabled = !saving,
                                    modifier = Modifier.fillMaxWidth().testTag("ai_agent_command_${agent.id}"),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
                val addDescription = stringResource(R.string.ai_agents_add)
                IconButton(
                    enabled = !saving && draft.entries.size < AiAgents.MAX_ENTRIES,
                    onClick = {
                        val agent = AiAgent(UUID.randomUUID().toString(), "", "")
                        draft = draft.copy(entries = draft.entries + agent)
                        editingId = agent.id
                    },
                    modifier = Modifier.align(Alignment.End).testTag("ai_agent_add")
                        .semantics { contentDescription = addDescription },
                ) {
                    ConnectionsGlyphIcon(ConnectionsGlyph.ADD, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                }
                if (!draft.isValid()) Text(stringResource(R.string.ai_agents_invalid),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (saveFailed) Text(stringResource(R.string.ai_agents_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(R.string.ai_agents_cancel)) }
        },
        confirmButton = {
            TextButton(enabled = !saving && draft.isValid(), onClick = {
                saving = true
                saveFailed = false
                scope.launch {
                    val saved = onSave(draft)
                    saving = false
                    if (saved) onDismiss() else saveFailed = true
                }
            }) { Text(stringResource(R.string.ai_agents_save)) }
        },
    )
}
