package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.terminal.TerminalCursor

/** Pure cursor-state and cell-geometry resolution shared by the Canvas hot path and JVM tests. */
internal object TerminalCursorRendering {
    fun resolveStyle(cursor: TerminalCursor, profileStyle: CursorStyle): CursorStyle =
        cursor.styleOverride ?: profileStyle

    fun resolveBlink(cursor: TerminalCursor, profileBlink: Boolean): Boolean =
        cursor.blinkOverride ?: profileBlink

    fun underlineThickness(lineHeight: Float, density: Float): Float =
        maxOf(density.coerceAtLeast(0f) * 2f, lineHeight.coerceAtLeast(0f) * 0.1f)
            .coerceAtMost(lineHeight.coerceAtLeast(0f))

    fun beamWidth(cellWidth: Float, density: Float): Float =
        (density.coerceAtLeast(0f) * 2f).coerceAtMost(cellWidth.coerceAtLeast(0f))
}
