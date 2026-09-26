package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.PortForwardDirection
import com.yanjiyu.terminalspike.core.model.PortForwardRule
import com.yanjiyu.terminalspike.core.model.PortForwardRules

@Composable
internal fun PortForwardEditor(
    rules: List<PortForwardRule>,
    onChange: (List<PortForwardRule>) -> Unit,
    enabled: Boolean,
) {
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var useWebsiteExample by rememberSaveable { mutableStateOf(false) }
    var conflict by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.port_forward_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.port_forward_summary), style = MaterialTheme.typography.bodySmall)
    rules.forEachIndexed { index, rule ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    enabled = enabled,
                    onCheckedChange = { checked ->
                        val next = rules.toMutableList().apply { set(index, rule.copy(enabled = checked)) }
                        conflict = runCatching { PortForwardRules.validate(next) }.isFailure
                        if (!conflict) onChange(next)
                    },
                )
                Text(
                    stringResource(if (rule.direction == PortForwardDirection.LOCAL)
                        R.string.port_forward_local else R.string.port_forward_remote),
                )
                TextButton(onClick = {
                    useWebsiteExample = false
                    editingIndex = index
                }, enabled = enabled) {
                    Text(stringResource(R.string.port_forward_edit))
                }
                TextButton(onClick = { onChange(rules.filterIndexed { i, _ -> i != index }) }, enabled = enabled) {
                    Text(stringResource(R.string.port_forward_remove))
                }
            }
            Text("${rule.bindAddress}:${rule.listenPort} → ${rule.destinationHost}:${rule.destinationPort}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (conflict) Text(stringResource(R.string.port_forward_invalid), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = {
        useWebsiteExample = false
        editingIndex = rules.size
    }, enabled = enabled && rules.size < PortForwardRules.MAX_RULES) {
        Text(stringResource(R.string.port_forward_add))
    }
    val exampleIndex = rules.indexOf(LocalWebsiteExample)
    TextButton(
        onClick = {
            useWebsiteExample = true
            editingIndex = exampleIndex.takeIf { it >= 0 } ?: rules.size
        },
        enabled = enabled && (exampleIndex >= 0 || rules.size < PortForwardRules.MAX_RULES),
    ) {
        Text(stringResource(R.string.port_forward_website_example))
    }
    Text(stringResource(R.string.port_forward_website_example_help), style = MaterialTheme.typography.bodySmall)
    editingIndex?.let { index ->
        PortForwardRuleDialog(
            initial = rules.getOrNull(index) ?: LocalWebsiteExample.takeIf { useWebsiteExample },
            otherRules = rules.filterIndexed { i, _ -> i != index },
            onDismiss = { editingIndex = null },
            onSave = { rule ->
                onChange(rules.toMutableList().apply { if (index < size) set(index, rule) else add(rule) })
                conflict = false
                editingIndex = null
            },
        )
    }
}

private val LocalWebsiteExample = PortForwardRule(listenPort = 5173, destinationPort = 5173)

@Composable
private fun PortForwardRuleDialog(
    initial: PortForwardRule?,
    otherRules: List<PortForwardRule>,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit,
) {
    var direction by rememberSaveable { mutableStateOf(initial?.direction ?: PortForwardDirection.LOCAL) }
    var bind by rememberSaveable { mutableStateOf(initial?.bindAddress ?: "127.0.0.1") }
    var listen by rememberSaveable { mutableStateOf(initial?.listenPort?.toString().orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial?.destinationHost ?: "127.0.0.1") }
    var destination by rememberSaveable { mutableStateOf(initial?.destinationPort?.toString().orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val rule = runCatching {
        PortForwardRule(direction, bind, listen.toInt(), host.trim(), destination.toInt(), initial?.enabled ?: true)
            .also { PortForwardRules.validate(otherRules + it) }
    }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.port_forward_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortForwardDirection.entries.forEach { option ->
                        FilterChip(selected = direction == option, onClick = { direction = option }, label = {
                            Text(stringResource(if (option == PortForwardDirection.LOCAL)
                                R.string.port_forward_local else R.string.port_forward_remote))
                        })
                    }
                }
                Text(stringResource(if (direction == PortForwardDirection.LOCAL)
                    R.string.port_forward_local_help else R.string.port_forward_remote_help))
                OutlinedTextField(bind, { bind = it.take(15) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.port_forward_bind)) },
                    supportingText = { Text(stringResource(R.string.port_forward_bind_help)) })
                OutlinedTextField(listen, { listen = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.port_forward_listen)) })
                OutlinedTextField(host, { host = it.take(253) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.port_forward_destination_host)) })
                OutlinedTextField(destination, { destination = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.port_forward_destination_port)) })
                if (showError && rule == null) Text(stringResource(R.string.port_forward_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { if (rule != null) onSave(rule) else showError = true }) {
            Text(stringResource(R.string.port_forward_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.port_forward_cancel)) } },
    )
}
