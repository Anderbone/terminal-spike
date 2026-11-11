package com.yanjiyu.terminalspike.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.theme.AppDialog

@Composable
internal fun SshIdentityImportDialog(
    onDismiss: () -> Unit,
    onFile: () -> Unit,
    onText: (label: String, privateKey: String) -> Unit,
) {
    // Deliberately not saveable: key material must never enter a saved-state Bundle.
    var paste by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var tooLarge by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun close() { text = ""; onDismiss() }
    AppDialog(
        onDismissRequest = ::close,
        securePolicy = SecureFlagPolicy.SecureOn,
        title = { Text(stringResource(if (paste) R.string.key_import_paste else R.string.connections_import_key)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_import_help))
                if (!paste) {
                    TextButton(onClick = onFile) { Text(stringResource(R.string.key_import_file)) }
                    TextButton(onClick = { paste = true }) { Text(stringResource(R.string.key_import_paste)) }
                } else {
                    OutlinedTextField(value = label, onValueChange = { label = it.take(UserSettings.MAX_LABEL_LENGTH) },
                        label = { Text(stringResource(R.string.key_import_name)) }, singleLine = true)
                    OutlinedTextField(value = text, onValueChange = {
                        tooLarge = it.length > MAX_SSH_PRIVATE_KEY_BYTES
                        text = if (tooLarge) "" else it
                    }, label = { Text(stringResource(R.string.key_import_text)) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        visualTransformation = PasswordVisualTransformation(), minLines = 3, maxLines = 6)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val value = runCatching { clipboard.primaryClip?.getItemAt(0)?.text }.getOrNull()
                        tooLarge = value != null && value.length > MAX_SSH_PRIVATE_KEY_BYTES
                        text = if (tooLarge) "" else value?.toString().orEmpty()
                    }) { Text(stringResource(R.string.key_import_clipboard)) }
                    if (tooLarge) Text(stringResource(R.string.notice_key_import_too_large))
                }
            }
        },
        confirmButton = {
            if (paste) TextButton(enabled = text.isNotBlank() && !tooLarge, onClick = {
                val key = text
                text = ""
                onText(label, key)
            }) { Text(stringResource(R.string.connections_import_key)) }
        },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(android.R.string.cancel)) } },
    )
}
