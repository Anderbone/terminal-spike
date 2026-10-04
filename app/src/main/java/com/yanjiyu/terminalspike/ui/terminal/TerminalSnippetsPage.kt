package com.yanjiyu.terminalspike.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDialog
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDraft
import kotlinx.coroutines.delay

// Zero is reserved for the built-in New Codex action; saved snippet IDs are positive.
internal fun snippetToolbarOrder(saved: List<Long>, available: List<Long>): List<Long> =
    saved.filter { it in available }.distinct() + available.filter { it !in saved }

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
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
    }
    var savedOrder by remember {
        mutableStateOf(preferences.getString("order", "").orEmpty().split(',').mapNotNull(String::toLongOrNull))
    }
    val order = snippetToolbarOrder(savedOrder, snippets.map { it.id } + 0L)
    val currentOrder by rememberUpdatedState(order)
    val bounds = remember { mutableMapOf<Long, Rect>() }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var dragScrollStart by remember { mutableStateOf(0) }
    val edge = with(LocalDensity.current) { 20.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dropTarget by remember { mutableStateOf<Long?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    fun move(from: Long, to: Long) {
        val updated = currentOrder.toMutableList()
        val destination = updated.indexOf(to)
        if (destination < 0 || !updated.remove(from)) return
        updated.add(destination, from)
        savedOrder = updated
        preferences.edit().putString("order", updated.joinToString(",")).apply()
    }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            val delta = when {
                dragPosition.y < viewport.top + edge -> -edge / 4
                dragPosition.y > viewport.bottom - edge -> edge / 4
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
            dropTarget = currentOrder.firstOrNull {
                it != dragging && bounds[it]?.contains(dragPosition) == true
            }
            delay(16)
        }
    }
    val moveEarlier = stringResource(R.string.snippet_move_earlier)
    val moveLater = stringResource(R.string.snippet_move_later)
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        FilledTonalButton(
            enabled = canSave,
            onClick = { editorVisible = true },
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) { Text(stringResource(R.string.snippet_accessory_add)) }
        FlowRow(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .onGloballyPositioned { viewport = it.boundsInRoot() }
                .verticalScroll(scroll, enabled = dragging == null)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point ->
                            val absolute = origin + point
                            dragging = currentOrder.firstOrNull { bounds[it]?.contains(absolute) == true }
                            dragScrollStart = scroll.value
                            dragStart = absolute
                            dragPosition = absolute
                        },
                        onDrag = { change, delta ->
                            if (dragging != null) {
                                change.consume()
                                dragPosition += delta
                                dropTarget = currentOrder.firstOrNull {
                                    it != dragging && bounds[it]?.contains(dragPosition) == true
                                }
                            }
                        },
                        onDragEnd = {
                            val from = dragging
                            val to = dropTarget
                            if (from != null && to != null) move(from, to)
                            dragging = null
                            dropTarget = null
                        },
                        onDragCancel = { dragging = null; dropTarget = null },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            order.forEachIndexed { index, id ->
                key(id) {
                    OutlinedButton(
                        // Reordering remains available while terminal input is disconnected.
                        enabled = canSend,
                        onClick = { if (id == 0L) onNewCodex() else onSend(id) },
                        border = BorderStroke(
                            if (dropTarget == id || dragging == id) 2.dp else 1.dp,
                            if (dropTarget == id || dragging == id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.onGloballyPositioned { bounds[id] = it.boundsInRoot() }
                            .zIndex(if (dragging == id) 1f else 0f)
                            .graphicsLayer {
                                if (dragging == id) {
                                    translationX = dragPosition.x - dragStart.x
                                    translationY = dragPosition.y - dragStart.y + scroll.value - dragScrollStart
                                    alpha = 0.8f
                                }
                            }
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveEarlier) {
                                        move(id, order[index - 1]); true
                                    })
                                    if (index < order.lastIndex) add(CustomAccessibilityAction(moveLater) {
                                        move(id, order[index + 1]); true
                                    })
                                }
                            },
                    ) {
                        Text(if (id == 0L) stringResource(R.string.snippet_accessory_example)
                        else snippets.first { it.id == id }.label)
                    }
                }
            }
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
