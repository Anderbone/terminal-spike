package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.DEFAULT_MAX_QUERY_LENGTH
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFindResult
import com.yanjiyu.terminalspike.terminal.findTerminalText
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.quantityText
import com.yanjiyu.terminalspike.ui.resolve
import com.yanjiyu.terminalspike.ui.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TerminalFindDialogTestTag = "terminal-find-dialog"

/**
 * Local, bounded terminal search. Only the query and stable cell references reach Compose state;
 * the transcript snapshot stays inside the background search operation.
 */
@Composable
internal fun TerminalFindDialog(
    sessionTitle: String,
    controller: TerminalController?,
    onPresentResult: (TerminalFindResult, Int) -> Boolean,
    onClearResult: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(controller) {
        if (controller == null) onDismiss()
    }
    val target = controller ?: return
    val scope = rememberCoroutineScope()
    var query by remember(target) { mutableStateOf("") }
    var result by remember(target) { mutableStateOf<TerminalFindResult?>(null) }
    var activeIndex by remember(target) { mutableIntStateOf(-1) }
    var searching by remember(target) { mutableStateOf(false) }
    var status by remember(target) { mutableStateOf<UiText?>(null) }
    var caseSensitive by remember(target) { mutableStateOf(false) }

    fun present(candidate: TerminalFindResult, index: Int) {
        val shown = onPresentResult(candidate, index)
        if (shown) {
            result = candidate
            activeIndex = index
            status = when {
                candidate.matches.isEmpty() -> uiText(R.string.terminal_find_no_matches)
                candidate.truncated ->
                    quantityText(
                        R.plurals.terminal_find_position_bounded,
                        candidate.matches.size,
                        index + 1,
                        candidate.matches.size,
                    )
                else -> quantityText(
                    R.plurals.terminal_find_position,
                    candidate.matches.size,
                    index + 1,
                    candidate.matches.size,
                )
            }
        } else {
            result = null
            activeIndex = -1
            status = uiText(R.string.terminal_find_changed)
        }
    }

    fun search() {
        val requestedQuery = query
        val requestedCaseSensitive = caseSensitive
        if (requestedQuery.isBlank() || searching) return
        searching = true
        status = null
        scope.launch {
            val found = withContext(Dispatchers.Default) {
                findTerminalText(
                    snapshot = target.transcriptSnapshot(),
                    query = requestedQuery,
                    caseSensitive = requestedCaseSensitive,
                )
            }
            searching = false
            if (query != requestedQuery || caseSensitive != requestedCaseSensitive) return@launch
            if (found.queryTooLong) {
                result = null
                activeIndex = -1
                onClearResult()
                status = uiText(R.string.terminal_find_query_limit, DEFAULT_MAX_QUERY_LENGTH)
            } else {
                present(found, if (found.matches.isEmpty()) -1 else 0)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            onClearResult()
            onDismiss()
        },
        modifier = Modifier.testTag(TerminalFindDialogTestTag),
        title = { Text(stringResource(R.string.terminal_find_title, sessionTitle.take(32))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it.take(DEFAULT_MAX_QUERY_LENGTH + 1)
                        result = null
                        activeIndex = -1
                        status = null
                        onClearResult()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.terminal_find_search_text)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = caseSensitive,
                        onCheckedChange = { enabled ->
                            caseSensitive = enabled
                            result = null
                            activeIndex = -1
                            status = null
                            onClearResult()
                        },
                    )
                    Text(stringResource(R.string.terminal_find_match_case))
                }
                if (searching) {
                    CircularProgressIndicator()
                } else {
                    status?.let { Text(it.resolve()) }
                }
                val current = result
                if (current != null && current.matches.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val next = if (activeIndex <= 0) {
                                    current.matches.lastIndex
                                } else {
                                    activeIndex - 1
                                }
                                present(current, next)
                            },
                        ) { Text(stringResource(R.string.terminal_find_previous)) }
                        TextButton(
                            onClick = {
                                val next = if (activeIndex >= current.matches.lastIndex) {
                                    0
                                } else {
                                    activeIndex + 1
                                }
                                present(current, next)
                            },
                        ) { Text(stringResource(R.string.terminal_find_next)) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { search() },
                enabled = query.isNotBlank() && !searching,
            ) {
                Text(stringResource(R.string.terminal_find_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onClearResult()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.done))
            }
        },
    )
}
