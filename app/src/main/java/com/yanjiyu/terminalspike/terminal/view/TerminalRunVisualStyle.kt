package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalStyle

/** Android-independent decisions that do not allocate in the renderer hot path. */
internal fun TerminalStyle.shouldDrawTerminalText(): Boolean = !conceal

internal fun TerminalStyle.terminalTextAlpha(): Int =
    if (dim) DIM_TEXT_ALPHA else OPAQUE_TEXT_ALPHA

internal const val DIM_TEXT_ALPHA = 128
internal const val OPAQUE_TEXT_ALPHA = 255
