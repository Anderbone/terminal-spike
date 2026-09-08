package com.yanjiyu.terminalspike.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.localarch.ArchEnvironmentState
import com.yanjiyu.terminalspike.localarch.ArchInstallPhase
import com.yanjiyu.terminalspike.localarch.ArchRootfsManifest
import kotlinx.coroutines.launch

@Composable
internal fun NewSessionDialog(onRemote: (ConnectionProtocol) -> Unit, onLocalArch: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("new-session-picker"),
        title = { Text(stringResource(R.string.local_arch_new_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionProtocol.entries.forEach { protocol ->
                    OutlinedButton(onClick = { onRemote(protocol) }, modifier = Modifier.fillMaxWidth()) {
                        Text(protocol.name)
                    }
                }
                Button(onClick = onLocalArch, modifier = Modifier.fillMaxWidth().testTag("new-local-arch")) {
                    Text(stringResource(R.string.local_arch_title))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun LocalArchInstallDialog(state: ArchEnvironmentState, onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("local-arch-install"),
        title = { Text(stringResource(R.string.local_arch_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalArchStatus(state)
        } },
        confirmButton = {
            if (!state.busy && state.supported && state.checked && (!state.installed || !state.starterToolsInstalled)) Button(onClick = onInstall) {
                Text(stringResource(if (state.error == null) R.string.local_arch_install else R.string.local_arch_retry))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun LocalArchSettings() {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as TerminalSpikeApplication).container.localSessionRepository }
    val state by repository.environment.state.collectAsStateWithLifecycle()
    val runtime by repository.runtime.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var destructive by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(repository) { repository.environment.refresh() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.local_arch_title), style = MaterialTheme.typography.headlineSmall)
        LocalArchStatus(state)
        runtime.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.installed && state.supported) {
            Button(onClick = { repository.installOrReset() }, enabled = state.checked && !state.busy && !runtime.installationActive) {
                Text(stringResource(R.string.local_arch_install))
            }
        }
        if (state.installed && !state.starterToolsInstalled) {
            Button(onClick = { repository.installOrReset() }, enabled = !state.busy && !runtime.installationActive) {
                Text(stringResource(R.string.local_arch_install_tools))
            }
        }
        OutlinedButton(onClick = { scope.launch { repository.environment.refresh() } }, enabled = !state.busy) {
            Text(stringResource(R.string.local_arch_refresh))
        }
        OutlinedButton(onClick = { confirmation = ""; destructive = "reset" }, enabled = state.checked && !state.busy && !runtime.installationActive) {
            Text(stringResource(R.string.local_arch_reset))
        }
        if (state.installed) OutlinedButton(onClick = { confirmation = ""; destructive = "reinstall" },
            enabled = !state.busy && !runtime.installationActive && state.supported) {
            Text(stringResource(R.string.local_arch_reinstall))
        }
    }
    destructive?.let { action ->
        AlertDialog(
            onDismissRequest = { destructive = null },
            title = { Text(stringResource(if (action == "reset") R.string.local_arch_reset else R.string.local_arch_reinstall)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.local_arch_delete_warning))
                OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, singleLine = true,
                    label = { Text(stringResource(R.string.local_arch_confirmation)) })
            } },
            confirmButton = { Button(enabled = confirmation == "DELETE ARCH", onClick = {
                destructive = null
                confirmation = ""
                repository.installOrReset(reinstall = action == "reinstall", reset = action == "reset")
            }) { Text(stringResource(if (action == "reset") R.string.local_arch_reset else R.string.local_arch_reinstall)) } },
            dismissButton = { TextButton(onClick = { destructive = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LocalArchStatus(state: ArchEnvironmentState) {
    val context = LocalContext.current
    Text(stringResource(R.string.local_arch_intro))
    Text(stringResource(R.string.local_arch_tools_detail))
    Text(stringResource(when {
        !state.supported -> R.string.local_arch_unsupported
        !state.checked && !state.busy -> R.string.local_arch_checking
        state.installed -> R.string.local_arch_installed
        else -> R.string.local_arch_not_installed
    }))
    state.version?.let { Text(stringResource(R.string.local_arch_version, it)) }
    state.storageBytes?.let { Text(stringResource(R.string.local_arch_storage, Formatter.formatFileSize(context, it))) }
    if (!state.installed) Text(stringResource(R.string.local_arch_download_detail))
    if (state.busy) {
        Text(stringResource(when (state.phase) {
            ArchInstallPhase.DOWNLOADING -> R.string.local_arch_downloading
            ArchInstallPhase.EXTRACTING -> R.string.local_arch_extracting
            ArchInstallPhase.VALIDATING -> R.string.local_arch_validating
            ArchInstallPhase.INSTALLING_TOOLS -> R.string.local_arch_installing_tools
            ArchInstallPhase.REMOVING -> R.string.local_arch_removing
            else -> R.string.local_arch_checking
        }))
        LocalArchProgressIndicator(state)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LocalArchProgressIndicator(state: ArchEnvironmentState) {
    if (state.phase == ArchInstallPhase.DOWNLOADING) {
        LinearProgressIndicator(
            progress = {
                (state.downloadedBytes.toFloat() / ArchRootfsManifest.DOWNLOAD_BYTES).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Application chrome only; installing never owns a modal window or terminal state. */
@Composable
internal fun LocalArchProgressStrip(state: ArchEnvironmentState, running: Boolean, error: String?, onOpen: () -> Unit,
    onDetails: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("local-arch-progress")) {
        Column(Modifier.statusBarsPadding().displayCutoutPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when {
                    running && state.phase == ArchInstallPhase.INSTALLING_TOOLS -> R.string.local_arch_installing_tools
                    running -> R.string.local_arch_background
                    error != null -> R.string.local_arch_operation_failed
                    state.installed -> R.string.local_arch_ready
                    else -> R.string.local_arch_not_installed
                }), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (!running && error == null && state.installed) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.local_arch_open)) }
                } else {
                    TextButton(onClick = onDetails) { Text(stringResource(R.string.local_arch_details)) }
                }
                if (!running) TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            if (running) {
                LocalArchProgressIndicator(state)
            }
        }
    }
}
