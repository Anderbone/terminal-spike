package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalRun

internal data class TerminalRunRenderSpan(
    val left: Float,
    val right: Float,
) {
    val width: Float
        get() = right - left
}

/** Resolves retained terminal cells before any font-specific glyph measurement can introduce drift. */
internal object TerminalRunRenderGeometry {
    /**
     * Resolves the actual monospace cell advance. Android applies letter spacing between glyphs,
     * so measuring one glyph alone can under-report every later cell by the configured spacing.
     */
    fun cellAdvance(singleGlyphWidth: Float, repeatedGlyphWidth: Float): Float {
        require(singleGlyphWidth.isFinite() && singleGlyphWidth >= 0f)
        require(repeatedGlyphWidth.isFinite() && repeatedGlyphWidth >= 0f)
        val measuredAdvance = repeatedGlyphWidth - singleGlyphWidth
        return measuredAdvance.takeIf { it.isFinite() && it > 0f }
            ?: singleGlyphWidth.coerceAtLeast(1f)
    }

    fun startX(
        run: TerminalRun,
        terminalOriginX: Float,
        cellWidth: Float,
        measuredFallbackX: Float,
    ): Float = if (run.startColumn >= 0) {
        terminalOriginX + run.startColumn * cellWidth
    } else {
        measuredFallbackX
    }

    fun span(
        run: TerminalRun,
        terminalOriginX: Float,
        cellWidth: Float,
        measuredFallbackX: Float,
        measuredTextWidth: Float,
    ): TerminalRunRenderSpan {
        require(cellWidth.isFinite() && cellWidth > 0f)
        require(measuredTextWidth.isFinite() && measuredTextWidth >= 0f)
        val left = startX(run, terminalOriginX, cellWidth, measuredFallbackX)
        val width = width(run, cellWidth, measuredTextWidth)
        return TerminalRunRenderSpan(left, left + width)
    }

    fun width(run: TerminalRun, cellWidth: Float, measuredTextWidth: Float): Float {
        require(cellWidth.isFinite() && cellWidth > 0f)
        require(measuredTextWidth.isFinite() && measuredTextWidth >= 0f)
        return if (run.columnWidth >= 0) run.columnWidth * cellWidth else measuredTextWidth
    }
}
