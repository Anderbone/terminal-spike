package com.yanjiyu.terminalspike.ui.sftp

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.SftpFile
import com.yanjiyu.terminalspike.ui.WipeableSecretInput
import com.yanjiyu.terminalspike.ui.WipeableSecretInputState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SftpScreen(
    controller: SftpSessionController,
    scope: CoroutineScope,
    onSubmitAuthentication: (String, CharArray) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uploadFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { readUploadFiles(context.contentResolver, uris) }
            controller.uploadFiles(files)
        }
    }
    val uploadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val source = try {
                withContext(Dispatchers.IO) { readUploadFolder(context.contentResolver, uri) }
            } catch (error: Exception) {
                controller.reportFailure(error)
                return@launch
            }
            controller.uploadFolder(source)
        }
    }
    val selectedItem = (state as? SftpUiState.Browsing)?.let { browsing ->
        browsing.files.firstOrNull { it.path == browsing.selectedPath }
    }
    val saveToPhoneFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            controller.downloadSelected(
                DocumentTreeDownloadDestination(context.contentResolver, uri),
                "selected phone folder",
            )
        }
    }
    BackHandler {
        val browsing = state as? SftpUiState.Browsing
        when {
            browsing?.selectedPath != null -> controller.select(null)
            browsing != null && browsing.path != "/" -> controller.goUp()
            else -> onClose()
        }
    }
    Surface(modifier = modifier.fillMaxSize().testTag(SftpScreenTestTag)) {
        when (val current = state) {
            SftpUiState.Closed -> Unit
            is SftpUiState.AuthenticationRequired -> SftpAuthenticationDialog(
                state = current,
                onSubmit = onSubmitAuthentication,
                onClose = onClose,
            )
            is SftpUiState.Connecting -> {
                CenterMessage("Connecting to ${current.hostName}…", progress = true)
                current.hostIdentityPrompt?.let { prompt ->
                    HostIdentityDialog(prompt, controller::answerHostIdentity)
                }
                current.keyboardInteractiveChallenge?.let { challenge ->
                    KeyboardInteractiveDialog(
                        challenge = challenge,
                        onSubmit = { controller.answerKeyboardInteractive(challenge.challengeToken, it) },
                        onCancel = { controller.cancelKeyboardInteractive(challenge.challengeToken) },
                    )
                }
            }
            is SftpUiState.Failed -> CenterMessage(
                message = current.message,
                action = "Close" to onClose,
            )
            is SftpUiState.Browsing -> SftpBrowser(
                state = current,
                selectedItem = selectedItem,
                onClose = onClose,
                onUp = controller::goUp,
                onOpen = controller::openDirectory,
                onOpenFile = { file ->
                    controller.select(file.path)
                    saveToPhoneFolder.launch(null)
                },
                onSelect = controller::select,
                onCopy = { controller.copySelection(false) },
                onCut = { controller.copySelection(true) },
                onPaste = controller::paste,
                onMkdir = controller::createDirectory,
                onRename = controller::renameSelection,
                onDelete = controller::deleteSelection,
                onUploadFiles = { uploadFiles.launch(arrayOf("*/*")) },
                onUploadFolder = { uploadFolder.launch(null) },
                onDownload = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        scope.launch {
                            controller.downloadSelected(
                                PhoneDownloadsDestination(context.contentResolver),
                                "Downloads",
                            )
                        }
                    } else {
                        saveToPhoneFolder.launch(null)
                    }
                },
                onSaveTo = { saveToPhoneFolder.launch(null) },
                onDismissMessage = controller::dismissMessage,
            )
        }
    }
}

@Composable
internal fun SftpBrowser(
    state: SftpUiState.Browsing,
    selectedItem: SftpFile?,
    onClose: () -> Unit,
    onUp: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenFile: (SftpFile) -> Unit,
    onSelect: (String?) -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onMkdir: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onDownload: () -> Unit,
    onSaveTo: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var dialog by remember { mutableStateOf<SftpEditDialog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseUpload by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Close") }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    state.hostName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.path != "/") TextButton(onClick = onUp) { Text("Up") }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(
                onClick = { dialog = SftpEditDialog.NewFolder },
                enabled = !state.busy,
                contentPadding = SftpActionPadding,
            ) { Text("New folder") }
            TextButton(onClick = { chooseUpload = true }, enabled = !state.busy, contentPadding = SftpActionPadding) {
                Text("From phone")
            }
            if (state.clipboard != null) {
                TextButton(onClick = onPaste, enabled = !state.busy, contentPadding = SftpActionPadding) {
                    Text("Paste")
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(SftpBusyTestTag))
        if (state.files.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CenterMessage("This folder is empty")
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.files, key = SftpFile::path) { file ->
                    SftpFileRow(
                        file = file,
                        selected = state.selectedPath == file.path,
                        enabled = !state.busy,
                        onOpen = { if (file.isDirectory) onOpen(file.path) else onOpenFile(file) },
                        onSelect = { onSelect(file.path) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
        if (state.selectedPath != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sftp_one_selected),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(onClick = { onSelect(null) }, contentPadding = SftpActionPadding) {
                        Text(stringResource(R.string.sftp_selection_done))
                    }
                    TextButton(onClick = onCopy, enabled = !state.busy, contentPadding = SftpActionPadding) { Text("Copy") }
                    TextButton(onClick = onCut, enabled = !state.busy, contentPadding = SftpActionPadding) { Text("Cut") }
                    TextButton(
                        onClick = { dialog = SftpEditDialog.Rename },
                        enabled = !state.busy,
                        contentPadding = SftpActionPadding,
                    ) { Text("Rename") }
                    if (selectedItem != null) {
                        TextButton(onClick = onDownload, enabled = !state.busy, contentPadding = SftpActionPadding) {
                            Text("Download")
                        }
                        TextButton(onClick = onSaveTo, enabled = !state.busy, contentPadding = SftpActionPadding) {
                            Text("Save to…")
                        }
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !state.busy,
                        contentPadding = SftpActionPadding,
                    ) { Text("Delete") }
                }
            }
        }
        state.message?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }
        }
    }
    dialog?.let { mode ->
        NameDialog(
            title = if (mode == SftpEditDialog.NewFolder) "New folder" else "Rename",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                if (mode == SftpEditDialog.NewFolder) onMkdir(name) else onRename(name)
            },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete selected item?") },
        text = { Text("Folders and all of their contents will be deleted permanently.") },
        confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (chooseUpload) AlertDialog(
        onDismissRequest = { chooseUpload = false },
        title = { Text("Copy from phone") },
        text = { Text("Choose files or a whole folder to upload into ${state.path}.") },
        confirmButton = {
            TextButton(onClick = { chooseUpload = false; onUploadFiles() }) { Text("Choose files") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { chooseUpload = false }) { Text("Cancel") }
                TextButton(onClick = { chooseUpload = false; onUploadFolder() }) { Text("Choose folder") }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SftpFileRow(
    file: SftpFile,
    selected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "SFTP item selection",
    )
    Surface(color = background) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(SftpItemTestTagPrefix + file.path)
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = stringResource(
                        if (file.isDirectory) R.string.sftp_open_folder else R.string.sftp_download_file,
                    ),
                    onLongClickLabel = stringResource(R.string.sftp_select_item),
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) { this.selected = selected },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (file.isDirectory) R.drawable.ic_sftp_folder else R.drawable.ic_sftp_file,
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .testTag(if (file.isDirectory) SftpFolderIconTestTag else SftpFileIconTestTag),
                tint = if (file.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
                if (!file.isDirectory) {
                    Text(
                        formatFileSize(file.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private enum class SftpEditDialog { NewFolder, Rename }

@Composable
private fun NameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SftpAuthenticationDialog(
    state: SftpUiState.AuthenticationRequired,
    onSubmit: (String, CharArray) -> Unit,
    onClose: () -> Unit,
) {
    val secret = remember(state.hostId) { WipeableSecretInputState() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Open files on ${state.hostName}") },
        text = {
            WipeableSecretInput(
                state = secret,
                label = if (state.kind == SftpSecretKind.PASSWORD) "Password" else "Key passphrase",
                testTag = "sftp-auth-secret",
            )
        },
        confirmButton = { Button(onClick = { onSubmit(state.hostId, secret.takeChars()) }, enabled = secret.hasValue) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun HostIdentityDialog(prompt: HostIdentityPrompt, onDecision: (Long, HostIdentityDecision) -> Unit) {
    val changed = prompt is HostIdentityPrompt.Changed
    AlertDialog(
        onDismissRequest = { onDecision(prompt.promptToken, HostIdentityDecision.Reject) },
        title = { Text(if (changed) "Host key changed" else "Verify host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) Text("The saved host key no longer matches. Verify it before replacing the saved key.")
                Text(prompt.endpoint)
                Text(prompt.algorithm, fontFamily = FontFamily.Monospace)
                (prompt as? HostIdentityPrompt.Changed)?.let {
                    Text("Previous: ${it.previousFingerprint}", fontFamily = FontFamily.Monospace)
                }
                Text(prompt.newFingerprint, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            Button(onClick = {
                onDecision(prompt.promptToken, if (changed) HostIdentityDecision.ReplaceSavedKey else HostIdentityDecision.TrustAndSave)
            }) { Text(if (changed) "Replace saved key" else "Trust and save") }
        },
        dismissButton = { TextButton(onClick = { onDecision(prompt.promptToken, HostIdentityDecision.Reject) }) { Text("Cancel") } },
    )
}

@Composable
private fun KeyboardInteractiveDialog(
    challenge: com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge,
    onSubmit: (List<CharArray>) -> Unit,
    onCancel: () -> Unit,
) {
    val states = remember(challenge.challengeToken) { challenge.questions.map { WipeableSecretInputState() } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(challenge.name.ifBlank { "Authentication" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (challenge.instruction.isNotBlank()) Text(challenge.instruction)
                challenge.questions.forEachIndexed { index, question ->
                    WipeableSecretInput(states[index], question.prompt, "sftp-challenge-$index", masked = !question.echo)
                }
            }
        },
        confirmButton = { Button(onClick = { onSubmit(states.map { it.takeChars() }) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun CenterMessage(message: String, progress: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress) CircularProgressIndicator()
            Text(message)
            action?.let { OutlinedButton(onClick = it.second) { Text(it.first) } }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}

internal const val SftpScreenTestTag = "sftp-screen"
internal const val SftpItemTestTagPrefix = "sftp-item:"
internal const val SftpFolderIconTestTag = "sftp-folder-icon"
internal const val SftpFileIconTestTag = "sftp-file-icon"
internal const val SftpBusyTestTag = "sftp-busy"

private val SftpActionPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
