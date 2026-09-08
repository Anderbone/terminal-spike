package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.TerminalTaskStatus

/** Used only by the surrounding tab/picker UI, never terminal cell rendering. */
@Composable
internal fun TerminalTaskIndicator(status: TerminalTaskStatus) {
    val running = stringResource(R.string.terminal_task_running)
    val finished = stringResource(R.string.terminal_task_finished)
    val attention = stringResource(R.string.terminal_task_attention)
    val description = buildList {
        if (status.running) add(running)
        if (status.finished) add(finished)
        if (status.needsAttention) add(attention)
    }.joinToString(", ")
    if (description.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        if (status.running) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        if (status.finished) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        if (status.needsAttention) Text("🔔", style = MaterialTheme.typography.labelSmall)
    }
}
