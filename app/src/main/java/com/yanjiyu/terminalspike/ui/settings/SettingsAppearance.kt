package com.yanjiyu.terminalspike.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.settings.ImportedTerminalFont
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalTheme
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import com.yanjiyu.terminalspike.terminal.model.toTerminalTheme
import com.yanjiyu.terminalspike.terminal.view.TerminalTypefaceRegistry
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettings(state: SettingsUiState, actions: SettingsActions) {
    val profile = state.terminalProfile
    val selectedCustomTheme = state.customTerminalThemes.firstOrNull { it.id == profile?.themeId }
    val selectedTheme = selectedCustomTheme?.toTerminalTheme()
        ?: TerminalThemes.find(profile?.themeId ?: TerminalThemes.CURRENT_ID)
    val themeChoices = TerminalThemes.presets + state.customTerminalThemes.map { it.toTerminalTheme() }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.onImportFont(uri)
    }
    var resetRequested by remember { mutableStateOf(false) }
    var themeEditorTarget by rememberSaveable { mutableStateOf<String?>(null) }

    SearchableSettingsOptions(
        modifier = Modifier.fillMaxSize().testTag(AppearanceSettingsTestTag),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(R.string.settings_category_appearance, R.string.settings_appearance_intro) }
        item {
            TerminalProfilePreview(
                profile = profile,
                theme = selectedTheme,
                importedFont = state.importedFonts.firstOrNull { it.id == profile?.fontId },
            )
        }
        item(key = R.string.settings_app_appearance_heading) { SectionLabel(R.string.settings_app_appearance_heading) }
        item {
            HorizontalChoices {
                AppearanceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.preferences.appearanceMode == mode,
                        onClick = { actions.onAppearanceMode(mode) },
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
        }
        item { SectionDivider() }
        item(key = R.string.settings_terminal_theme_heading) { SectionLabel(R.string.settings_terminal_theme_heading) }
        item {
            Text(
                text = stringResource(R.string.settings_terminal_theme_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalChoices {
                themeChoices.forEach { theme ->
                    ThemeChoice(theme, selected = theme.id == selectedTheme.id) {
                        actions.onTerminalTheme(theme.id)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { themeEditorTarget = NEW_CUSTOM_THEME_TARGET },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_terminal_theme_add)) }
                if (selectedCustomTheme != null) {
                    OutlinedButton(
                        onClick = { themeEditorTarget = selectedCustomTheme.id },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_terminal_theme_edit)) }
                }
            }
        }
        item(key = R.string.settings_font_heading) { SectionLabel(R.string.settings_font_heading) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoiceRow(
                    title = stringResource(R.string.settings_font_system_monospace),
                    fontId = TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID,
                    importedFont = null,
                    selected = profile?.fontId == SettingsViewModel.SYSTEM_MONOSPACE_FONT_ID,
                    onClick = { actions.onTerminalFont(SettingsViewModel.SYSTEM_MONOSPACE_FONT_ID) },
                )
                bundledFontChoices.forEach { choice ->
                    FontChoiceRow(
                        title = stringResource(choice.titleRes),
                        fontId = choice.id,
                        importedFont = null,
                        selected = profile?.fontId == choice.id,
                        onClick = { actions.onTerminalFont(choice.id) },
                    )
                }
                state.importedFonts.forEach { imported ->
                    FontChoiceRow(
                        title = imported.displayName,
                        fontId = imported.id,
                        importedFont = imported,
                        selected = profile?.fontId == imported.id,
                        onClick = { actions.onTerminalFont(imported.id) },
                    )
                }
                OutlinedButton(
                    onClick = {
                        importFont.launch(
                            arrayOf(
                                "font/ttf",
                                "font/otf",
                                "application/x-font-ttf",
                                "application/x-font-opentype",
                                "application/octet-stream",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_import_font)) }
                Text(
                    text = stringResource(R.string.settings_import_font_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_font_nerd_fallback_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (profile != null) {
            item(key = R.string.settings_font_size) {
                ProfileSlider(
                    title = stringResource(R.string.settings_font_size),
                    value = profile.fontSizeSp,
                    range = 8f..32f,
                    steps = 23,
                    valueLabel = stringResource(R.string.settings_sp_value, profile.fontSizeSp),
                    onCommit = actions.onFontSize,
                )
            }
            item(key = R.string.settings_line_height) {
                ProfileSlider(
                    title = stringResource(R.string.settings_line_height),
                    value = profile.lineHeightMultiplier,
                    range = 0.8f..1.8f,
                    steps = 9,
                    valueLabel = String.format("%.2f×", profile.lineHeightMultiplier),
                    onCommit = actions.onLineHeight,
                )
            }
            item(key = R.string.settings_letter_spacing) {
                ProfileSlider(
                    title = stringResource(R.string.settings_letter_spacing),
                    value = profile.letterSpacingEm,
                    range = -0.1f..0.2f,
                    steps = 29,
                    valueLabel = String.format("%.2f em", profile.letterSpacingEm),
                    onCommit = actions.onLetterSpacing,
                )
            }
            item(key = R.string.settings_font_bold_rendering) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_bold_rendering),
                    summary = stringResource(R.string.settings_font_bold_rendering_summary),
                    checked = profile.boldRenderingEnabled,
                    onCheckedChange = actions.onBoldRendering,
                )
            }
            item(key = R.string.settings_font_ligatures) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_ligatures),
                    summary = stringResource(R.string.settings_font_ligatures_summary),
                    checked = profile.ligaturesEnabled,
                    onCheckedChange = actions.onLigatures,
                )
            }
            item(key = R.string.settings_font_pinch_zoom) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_font_pinch_zoom),
                    summary = stringResource(R.string.settings_font_pinch_zoom_summary),
                    checked = profile.pinchZoomEnabled,
                    onCheckedChange = actions.onPinchZoom,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.settings_preview_coverage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            TextButton(onClick = { resetRequested = true }) {
                Text(stringResource(R.string.settings_reset_appearance))
            }
        }
    }

    if (resetRequested) {
        ResetDialog(
            title = R.string.settings_reset_appearance_title,
            message = R.string.settings_reset_appearance_message,
            onDismiss = { resetRequested = false },
            onConfirm = {
                resetRequested = false
                actions.onResetAppearance()
            },
        )
    }

    themeEditorTarget?.let { target ->
        val initialTheme = if (target == NEW_CUSTOM_THEME_TARGET) {
            null
        } else {
            state.customTerminalThemes.firstOrNull { it.id == target }
        }
        if (target == NEW_CUSTOM_THEME_TARGET || initialTheme != null) {
            CustomTerminalThemeEditorDialog(
                initialTheme = initialTheme,
                onDismiss = { themeEditorTarget = null },
                onSave = { draft ->
                    themeEditorTarget = null
                    actions.onSaveCustomTheme(draft)
                },
                onDelete = { id ->
                    themeEditorTarget = null
                    actions.onDeleteCustomTheme(id)
                },
            )
        } else {
            LaunchedEffect(target) { themeEditorTarget = null }
        }
    }
}

@Composable
private fun ThemeChoice(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Row {
                    theme.ansi16.take(3).forEach { colour ->
                        Box(Modifier.size(8.dp).background(Color(colour)))
                    }
                }
                Text(theme.displayName)
            }
        },
    )
}

@Composable
private fun FontChoiceRow(
    title: String,
    fontId: String,
    importedFont: ImportedTerminalFont?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val typeface = remember(fontId, importedFont?.id) {
        importedFont?.file?.let(TerminalTypefaceRegistry::register)
        TerminalTypefaceRegistry.resolve(
            context = context,
            fontId = fontId,
            customFontPath = importedFont?.file?.absolutePath,
        )?.normal ?: Typeface.MONOSPACE
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title)
                AndroidView(
                    factory = { previewContext ->
                        TextView(previewContext).apply {
                            textSize = 14f
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    },
                    update = { preview ->
                        preview.typeface = typeface
                        preview.text = FONT_CHOICE_PREVIEW
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ProfileSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun TerminalProfilePreview(
    profile: TerminalProfile?,
    theme: TerminalTheme,
    importedFont: ImportedTerminalFont?,
) {
    val context = LocalContext.current
    val typefaces = remember(profile?.fontId, importedFont?.id) {
        importedFont?.file?.let(TerminalTypefaceRegistry::register)
        TerminalTypefaceRegistry.resolve(
            context = context,
            fontId = profile?.fontId ?: TerminalRendererProfile.DEFAULT_FONT_ID,
            customFontPath = importedFont?.file?.absolutePath,
        )
    }
    AndroidView(
        factory = { previewContext ->
            TextView(previewContext).apply {
                setPadding(
                    16.toDpPx(previewContext),
                    14.toDpPx(previewContext),
                    16.toDpPx(previewContext),
                    14.toDpPx(previewContext),
                )
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = previewContext.getString(R.string.settings_terminal_preview_description)
            }
        },
        update = { view ->
            view.typeface = typefaces?.normal ?: Typeface.MONOSPACE
            view.textSize = profile?.fontSizeSp ?: SettingsViewModel.DEFAULT_FONT_SIZE_SP
            view.letterSpacing = profile?.letterSpacingEm ?: 0f
            view.paint.fontFeatureSettings = if (profile?.ligaturesEnabled == true) {
                "'liga' 1, 'calt' 1"
            } else {
                "'liga' 0, 'calt' 0"
            }
            view.setLineSpacing(0f, profile?.lineHeightMultiplier ?: 1f)
            view.setTextColor(theme.foreground)
            view.setBackgroundColor(theme.background)
            view.text = styledPreviewText(
                context = context,
                boldRenderingEnabled = profile?.boldRenderingEnabled != false,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 184.dp)
            .clip(RoundedCornerShape(16.dp))
            .testTag(TerminalPreviewTestTag),
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 8.dp)) {
        theme.ansi16.chunked(8).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { colour -> Box(Modifier.weight(1f).height(8.dp).background(Color(colour))) }
            }
        }
    }
}

private fun styledPreviewText(context: Context, boldRenderingEnabled: Boolean): SpannableString {
    val plain = context.getString(R.string.settings_terminal_preview_content)
    return SpannableString(plain).apply {
        val boldStart = plain.indexOf("Bold")
        if (boldRenderingEnabled && boldStart >= 0) setSpan(
            StyleSpan(Typeface.BOLD),
            boldStart,
            boldStart + "Bold".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val italicStart = plain.indexOf("Italic")
        if (italicStart >= 0) setSpan(
            StyleSpan(Typeface.ITALIC),
            italicStart,
            italicStart + "Italic".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private fun Int.toDpPx(context: Context): Int = (this * context.resources.displayMetrics.density).roundToInt()

private data class BundledFontChoice(@StringRes val titleRes: Int, val id: String)

private val bundledFontChoices = listOf(
    BundledFontChoice(R.string.settings_font_source_code_pro, TerminalRendererProfile.SOURCE_CODE_PRO_FONT_ID),
    BundledFontChoice(R.string.settings_font_jetbrains_mono, TerminalRendererProfile.JETBRAINS_MONO_FONT_ID),
    BundledFontChoice(R.string.settings_font_ibm_plex_mono, TerminalRendererProfile.IBM_PLEX_MONO_FONT_ID),
    BundledFontChoice(R.string.settings_font_cascadia_mono, TerminalRendererProfile.CASCADIA_MONO_FONT_ID),
)

private const val FONT_CHOICE_PREVIEW = "Aa 01 != -> "

internal const val AppearanceSettingsTestTag = "appearance-settings"
internal const val TerminalPreviewTestTag = "terminal-profile-preview"
private const val NEW_CUSTOM_THEME_TARGET = "new_custom_theme"
