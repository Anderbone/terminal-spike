package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRunRenderGeometryTest {
    @Test
    fun retainedCellGeometryWinsOverBoldOrFallbackGlyphMeasurement() {
        val run = TerminalRun(
            text = "界🚀",
            startColumn = 9,
            columnWidth = 4,
        )

        val span = TerminalRunRenderGeometry.span(
            run = run,
            terminalOriginX = 4f,
            cellWidth = 8f,
            measuredFallbackX = 999f,
            measuredTextWidth = 123f,
        )

        assertEquals(76f, span.left)
        assertEquals(108f, span.right)
        assertEquals(32f, span.width)
    }

    @Test
    fun syntheticRunRetainsMeasuredFallbackBehavior() {
        val span = TerminalRunRenderGeometry.span(
            run = TerminalRun("synthetic"),
            terminalOriginX = 4f,
            cellWidth = 8f,
            measuredFallbackX = 12f,
            measuredTextWidth = 31f,
        )

        assertEquals(12f, span.left)
        assertEquals(43f, span.right)
    }

    @Test
    fun sgrVisualAttributesDoNotChangeRetainedCellGeometry() {
        val styledRun = TerminalRun(
            text = "hidden",
            style = TerminalStyle(dim = true, conceal = true, strikethrough = true),
            startColumn = 3,
            columnWidth = 6,
        )

        val span = TerminalRunRenderGeometry.span(
            run = styledRun,
            terminalOriginX = 4f,
            cellWidth = 8f,
            measuredFallbackX = 999f,
            measuredTextWidth = 1f,
        )

        assertEquals(28f, span.left)
        assertEquals(76f, span.right)
        assertEquals(DIM_TEXT_ALPHA, styledRun.style.terminalTextAlpha())
        assertFalse(styledRun.style.shouldDrawTerminalText())
        assertTrue(styledRun.style.strikethrough)
    }

    @Test
    fun cellAdvanceIncludesTheLetterSpacingThatOnlyAppearsBetweenGlyphs() {
        assertEquals(
            12f,
            TerminalRunRenderGeometry.cellAdvance(
                singleGlyphWidth = 10f,
                repeatedGlyphWidth = 22f,
            ),
        )
    }

    @Test
    fun invalidRepeatedGlyphMeasurementFallsBackToTheSingleGlyphWidth() {
        assertEquals(
            10f,
            TerminalRunRenderGeometry.cellAdvance(
                singleGlyphWidth = 10f,
                repeatedGlyphWidth = 9f,
            ),
        )
    }
}
