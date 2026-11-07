package com.yanjiyu.terminalspike.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.zIndex
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDialog
import com.yanjiyu.terminalspike.ui.connections.SnippetEditorDraft
import com.yanjiyu.terminalspike.ui.connections.toEditorDraft
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
    snippetForEditing: (Long) -> Snippet? = { null },
    onDelete: ((String) -> Unit)? = null,
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
    var editingSnippetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var managementRequested by rememberSaveable { mutableStateOf(false) }
    val managing = managementRequested && canSave
    BackHandler(enabled = managing && !editorVisible) { managementRequested = false }
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
    Column {
        if (managing) {
            Text(
                text = stringResource(R.string.snippet_management_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            CompactSnippetLayout(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    .onGloballyPositioned { viewport = it.boundsInRoot() }
                    .verticalScroll(scroll, enabled = dragging == null)
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(managing) {
                        if (!managing) return@pointerInput
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
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val manageDescription = stringResource(
                        if (managing) R.string.snippet_management_done else R.string.snippet_management_start,
                    )
                    FilledTonalButton(
                        enabled = canSave,
                        onClick = { managementRequested = !managing },
                        modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                            .semantics { contentDescription = manageDescription },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (managing) stringResource(R.string.done) else "✎",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    val addDescription = stringResource(R.string.snippet_accessory_add)
                    FilledTonalButton(
                        enabled = canSave,
                        onClick = { editingSnippetId = null; editorVisible = true },
                        modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                            .semantics { contentDescription = addDescription },
                        contentPadding = PaddingValues(4.dp),
                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                }
                order.forEachIndexed { index, id ->
                    key(id) {
                        Surface(
                            shape = ButtonDefaults.shape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (managing && id == 0L) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (managing || canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            border = BorderStroke(
                                if (dropTarget == id || dragging == id) 2.dp else 1.dp,
                                if (dropTarget == id || dragging == id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            ),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp)
                                .clickable(
                                    role = Role.Button,
                                    enabled = if (managing) id != 0L else canSend,
                                    onClick = {
                                        if (managing) {
                                            if (snippetForEditing(id) != null) {
                                                editingSnippetId = id
                                                editorVisible = true
                                            }
                                        } else if (canSend) {
                                            if (id == 0L) onNewCodex() else onSend(id)
                                        }
                                    },
                                )
                                .onGloballyPositioned { bounds[id] = it.boundsInRoot() }
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
                                        if (managing && index > 0) add(CustomAccessibilityAction(moveEarlier) {
                                            move(id, order[index - 1]); true
                                        })
                                        if (managing && index < order.lastIndex) add(CustomAccessibilityAction(moveLater) {
                                            move(id, order[index + 1]); true
                                        })
                                    }
                                },
                        ) {
                            Text(
                                text = if (id == 0L) stringResource(
                                    if (managing) R.string.snippet_accessory_example_builtin
                                    else R.string.snippet_accessory_example,
                                )
                                else snippets.first { it.id == id }.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    .wrapContentSize(Alignment.Center),
                            )
                        }
                    }
                }
            }
        }
    }
    if (editorVisible) {
        SnippetEditorDialog(
            initial = editingSnippetId?.let(snippetForEditing)?.toEditorDraft() ?: SnippetEditorDraft(),
            onDismiss = { editorVisible = false; onEditorClosed() },
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

// Reserve space for the management/add controls in the first row only.
@Composable
private fun CompactSnippetLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 4.dp.roundToPx()
        val rowGap = 2.dp.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val controls = measurables.first().measure(childConstraints)
        val width = constraints.maxWidth
        val placements = mutableListOf<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>()
        var x = 0
        var y = 0
        var rowHeight = controls.height
        var rowWidth = (width - controls.width - gap).coerceAtLeast(0)
        measurables.drop(1).forEach { measurable ->
            val child = measurable.measure(childConstraints)
            if (x + child.width > rowWidth && (x > 0 || y == 0)) {
                y += rowHeight + rowGap
                x = 0
                rowHeight = 0
                rowWidth = width
            }
            placements.add(Triple(child, x, y))
            x += child.width + gap
            rowHeight = maxOf(rowHeight, child.height)
        }
        layout(width, constraints.constrainHeight(y + rowHeight)) {
            controls.placeRelative(width - controls.width, 0)
            placements.forEach { (child, left, top) -> child.placeRelative(left, top) }
        }
    }
}
