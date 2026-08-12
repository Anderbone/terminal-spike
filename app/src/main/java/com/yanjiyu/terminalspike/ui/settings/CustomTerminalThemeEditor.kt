package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.terminal.model.TerminalTheme
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes

@Composable
internal fun CustomTerminalThemeEditorDialog(
    initialTheme: CustomTerminalTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTerminalThemeDraft) -> Unit,
    onDelete: (String) -> Unit,
) {
    val current = TerminalThemes.current
    val defaultName = stringResource(R.string.settings_terminal_theme_default_name)
    var name by remember(initialTheme?.id) {
        mutableStateOf(initialTheme?.name ?: defaultName)
    }
    var colours by remember(initialTheme?.id) {
        mutableStateOf(initialTheme?.editorColours() ?: current.editorColours())
    }
    var boldUsesBright by remember(initialTheme?.id) {
        mutableStateOf(initialTheme?.boldUsesBrightColours ?: current.boldUsesBrightColours)
    }
    var deleteRequested by remember(initialTheme?.id) { mutableStateOf(false) }
    val parsed = colours.map(::parseOpaqueArgb)
    val draft = if (isValidThemeName(name) && parsed.all { it != null }) {
        CustomTerminalThemeDraft(
            id = initialTheme?.id,
            name = name,
            foregroundArgb = requireNotNull(parsed[FOREGROUND_INDEX]),
            backgroundArgb = requireNotNull(parsed[BACKGROUND_INDEX]),
            cursorArgb = requireNotNull(parsed[CURSOR_INDEX]),
            selectionArgb = requireNotNull(parsed[SELECTION_INDEX]),
            ansi16Argb = parsed.drop(ANSI_START_INDEX).map { requireNotNull(it) },
            boldUsesBrightColours = boldUsesBright,
        )
    } else {
        null
    }
    val preview = TerminalTheme(
        id = initialTheme?.id ?: "custom_preview",
        displayName = name.takeIf(::isValidThemeName) ?: defaultName,
        foreground = parsed[FOREGROUND_INDEX] ?: current.foreground,
        background = parsed[BACKGROUND_INDEX] ?: current.background,
        cursor = parsed[CURSOR_INDEX] ?: current.cursor,
        selection = parsed[SELECTION_INDEX] ?: current.selection,
        ansi16 = parsed.drop(ANSI_START_INDEX).mapIndexed { index, colour ->
            colour ?: current.ansi16[index]
        },
        boldUsesBrightColours = boldUsesBright,
    )
    val labels = buildList {
        add(stringResource(R.string.settings_terminal_theme_foreground))
        add(stringResource(R.string.settings_terminal_theme_background))
        add(stringResource(R.string.settings_terminal_theme_cursor))
        add(stringResource(R.string.settings_terminal_theme_selection))
        repeat(CustomTerminalTheme.ANSI_COLOUR_COUNT) { index ->
            add(stringResource(R.string.settings_terminal_theme_ansi_colour, index))
        }
    }

    AlertDialog(
        modifier = Modifier.testTag(CustomThemeEditorTestTag),
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initialTheme == null) {
                        R.string.settings_terminal_theme_create_title
                    } else {
                        R.string.settings_terminal_theme_edit_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CustomThemeLivePreview(preview)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(ModelLimits.MAX_DISPLAY_NAME_LENGTH) },
                            modifier = Modifier.fillMaxWidth().testTag(CustomThemeNameTestTag),
                            label = { Text(stringResource(R.string.settings_terminal_theme_name)) },
                            singleLine = true,
                            isError = !isValidThemeName(name),
                        )
                    }
                    itemsIndexed(labels) { index, label ->
                        val parsedColour = parsed[index]
                        OutlinedTextField(
                            value = colours[index],
                            onValueChange = { value ->
                                colours = colours.toMutableList().also {
                                    it[index] = value.take(MAX_HEX_COLOUR_LENGTH)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("$CustomThemeColourTestTagPrefix$index"),
                            label = { Text(label) },
                            supportingText = {
                                Text(stringResource(R.string.settings_terminal_theme_colour_hint))
                            },
                            trailingIcon = {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(
                                            parsedColour?.let { Color(it) }
                                                ?: MaterialTheme.colorScheme.errorContainer,
                                            RoundedCornerShape(4.dp),
                                        ),
                                )
                            },
                            singleLine = true,
                            isError = parsedColour == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_terminal_theme_bold_bright))
                                Text(
                                    stringResource(R.string.settings_terminal_theme_bold_bright_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = boldUsesBright, onCheckedChange = { boldUsesBright = it })
                        }
                    }
                    if (draft == null) {
                        item {
                            Text(
                                stringResource(R.string.settings_terminal_theme_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                colours = current.editorColours()
                                boldUsesBright = current.boldUsesBrightColours
                            },
                            modifier = Modifier.fillMaxWidth().testTag(CustomThemeResetTestTag),
                        ) { Text(stringResource(R.string.settings_terminal_theme_restore_defaults)) }
                    }
                    if (initialTheme != null) {
                        item {
                            OutlinedButton(
                                onClick = { deleteRequested = true },
                                modifier = Modifier.fillMaxWidth().testTag(CustomThemeDeleteTestTag),
                            ) {
                                Text(
                                    stringResource(R.string.settings_terminal_theme_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { draft?.let(onSave) },
                enabled = draft != null,
                modifier = Modifier.testTag(CustomThemeSaveTestTag),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (deleteRequested && initialTheme != null) {
        AlertDialog(
            onDismissRequest = { deleteRequested = false },
            title = { Text(stringResource(R.string.settings_terminal_theme_delete_title)) },
            text = { Text(stringResource(R.string.settings_terminal_theme_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteRequested = false
                        onDelete(initialTheme.id)
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_terminal_theme_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequested = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CustomThemeLivePreview(theme: TerminalTheme) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(CustomThemePreviewTestTag),
        color = Color(theme.background),
        contentColor = Color(theme.foreground),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_terminal_preview_content),
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(18.dp).background(Color(theme.cursor)))
                Box(Modifier.size(width = 56.dp, height = 18.dp).background(Color(theme.selection)))
            }
            theme.ansi16.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { colour ->
                        Box(Modifier.weight(1f).size(height = 8.dp, width = 1.dp).background(Color(colour)))
                    }
                }
            }
        }
    }
}

internal fun parseOpaqueArgb(value: String): Int? {
    val candidate = value.trim()
    if (candidate.length != MAX_HEX_COLOUR_LENGTH || candidate.firstOrNull() != '#') return null
    val rgb = candidate.drop(1).toIntOrNull(16) ?: return null
    return OPAQUE_ALPHA or rgb
}

internal fun formatOpaqueArgb(value: Int): String = "#%06X".format(value and RGB_MASK)

internal fun CustomTerminalThemeDraft.toPreviewTheme(): TerminalTheme = TerminalTheme(
    id = id ?: "custom_preview",
    displayName = name,
    foreground = foregroundArgb,
    background = backgroundArgb,
    cursor = cursorArgb,
    selection = selectionArgb,
    ansi16 = ansi16Argb,
    boldUsesBrightColours = boldUsesBrightColours,
)

private fun isValidThemeName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= ModelLimits.MAX_DISPLAY_NAME_LENGTH &&
        name == name.trim() &&
        name.none(Char::isISOControl)

private fun CustomTerminalTheme.editorColours(): List<String> = listOf(
    foregroundArgb,
    backgroundArgb,
    cursorArgb,
    selectionArgb,
).plus(ansi16Argb).map(::formatOpaqueArgb)

private fun TerminalTheme.editorColours(): List<String> = listOf(
    foreground,
    background,
    cursor,
    selection,
).plus(ansi16).map(::formatOpaqueArgb)

internal const val CustomThemeEditorTestTag = "custom-theme-editor"
internal const val CustomThemeNameTestTag = "custom-theme-name"
internal const val CustomThemePreviewTestTag = "custom-theme-preview"
internal const val CustomThemeSaveTestTag = "custom-theme-save"
internal const val CustomThemeDeleteTestTag = "custom-theme-delete"
internal const val CustomThemeResetTestTag = "custom-theme-reset"
internal const val CustomThemeColourTestTagPrefix = "custom-theme-colour-"

private const val FOREGROUND_INDEX = 0
private const val BACKGROUND_INDEX = 1
private const val CURSOR_INDEX = 2
private const val SELECTION_INDEX = 3
private const val ANSI_START_INDEX = 4
private const val MAX_HEX_COLOUR_LENGTH = 7
private const val OPAQUE_ALPHA = -0x1000000
private const val RGB_MASK = 0x00ffffff
