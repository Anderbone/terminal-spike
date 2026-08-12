package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme

internal fun CustomTerminalTheme.toBackupTerminalTheme(): BackupTerminalTheme = BackupTerminalTheme(
    id = id,
    name = name,
    foregroundArgb = foregroundArgb,
    backgroundArgb = backgroundArgb,
    cursorArgb = cursorArgb,
    selectionArgb = selectionArgb,
    ansi16Argb = ansi16Argb,
    boldUsesBrightColours = boldUsesBrightColours,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun BackupTerminalTheme.toCustomTerminalTheme(): CustomTerminalTheme = CustomTerminalTheme(
    id = id,
    name = name,
    foregroundArgb = foregroundArgb,
    backgroundArgb = backgroundArgb,
    cursorArgb = cursorArgb,
    selectionArgb = selectionArgb,
    ansi16Argb = ansi16Argb,
    boldUsesBrightColours = boldUsesBrightColours,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
