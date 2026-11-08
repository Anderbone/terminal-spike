package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared compact dialog shell; long forms scroll while title and actions stay visible. */
@Composable
internal fun AppEditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    dense: Boolean = false,
) {
    AppDialogFrame(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        maxWidth = 560.dp,
        scrollState = scrollState,
        dense = dense,
    )
}

@Composable
internal fun AppButtonLabel(text: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        }
        Text(text)
    }
}
