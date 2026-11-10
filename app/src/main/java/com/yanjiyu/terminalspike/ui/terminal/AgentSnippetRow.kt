package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.settings.AgentTerminalSnippets

@Composable
internal fun CodexEffortMenu(
    contextKey: String,
    agentId: String,
    effort: String?,
    canSend: Boolean,
    onSend: (Long) -> Unit,
) {
    if (agentId != "codex") return
    var open by remember(contextKey) { mutableStateOf(false) }
    val value = effort?.let { if (it == "xhigh") "Extra High" else it.replaceFirstChar(Char::uppercase) } ?: stringResource(R.string.ai_agents_effort)
    Box {
        FilledTonalButton(
            enabled = canSend, onClick = { open = !open },
            modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).width(96.dp).testTag("codex_effort_menu"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) { Text("$value ▾", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded = open && canSend, onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false)) {
            AgentTerminalSnippets.codex.forEach { snippet ->
                val selected = snippet.command.substringAfter("--reasoning=") == effort
                DropdownMenuItem(
                    text = { Text(if (selected) "✓ ${snippet.label}" else snippet.label) },
                    modifier = Modifier.testTag("agent_snippet_${snippet.id}"),
                    onClick = { open = false; onSend(snippet.id) },
                )
            }
        }
    }
}
