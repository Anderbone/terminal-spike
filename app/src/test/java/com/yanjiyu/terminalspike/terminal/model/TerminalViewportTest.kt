package com.yanjiyu.terminalspike.terminal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewportTest {
    @Test
    fun visibleRowsIncludeSmallOverscanAndRemainBounded() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(heightPx = 100, newLineHeightPx = 10f)
        viewport.updateContent(newLineCount = 100, newOldestLineId = 0L)
        viewport.scrollTo(500f)

        assertEquals(VisibleRows(48, 63), viewport.visibleRows(2))
    }

    @Test
    fun scrollIsPixelBasedAndClamped() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(95, 10f)
        viewport.updateContent(20, 0L)

        viewport.scrollBy(-10_000f)
        assertEquals(0f, viewport.scrollY)
        viewport.scrollBy(55.5f)
        assertEquals(55.5f, viewport.scrollY)
        viewport.scrollBy(10_000f)
        assertEquals(105f, viewport.scrollY)
    }

    @Test
    fun userScrollDisablesFollowAndExactBottomRestoresIt() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(100, 10f)
        viewport.updateContent(50, 0L)
        assertTrue(viewport.autoFollow)

        viewport.scrollBy(-0.75f)
        assertFalse(viewport.autoFollow)
        viewport.scrollTo(viewport.maximumScrollY)
        assertTrue(viewport.autoFollow)
    }

    @Test
    fun incomingContentDoesNotPullScrolledViewportDown() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(100, 10f)
        viewport.updateContent(50, 0L)
        viewport.scrollTo(200f)

        viewport.updateContent(60, 0L)

        assertEquals(200f, viewport.scrollY)
        assertFalse(viewport.autoFollow)
    }

    @Test
    fun trimPreservesStableTopLineAnchor() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(100, 10f)
        viewport.updateContent(100, 10L)
        viewport.scrollTo(250f)

        viewport.updateContent(100, 20L)

        assertEquals(150f, viewport.scrollY)
        assertFalse(viewport.autoFollow)
    }

    @Test
    fun resizeKeepsBottomWhenFollowing() {
        val viewport = TerminalViewport()
        viewport.updateGeometry(100, 10f)
        viewport.updateContent(100, 0L)

        viewport.updateGeometry(250, 10f)

        assertEquals(750f, viewport.scrollY)
        assertTrue(viewport.autoFollow)
    }

}
