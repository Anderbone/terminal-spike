package com.yanjiyu.terminalspike.terminal.view

import org.junit.Assert.*
import org.junit.Test
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine

class TerminalResizeMotionTest {
    @Test fun primaryInlineUiFooterIsRecognizedWithoutMouseOrAlternateMode() {
        val engine = VtTerminalEngine(40, 6)
        assertTrue(engine.accept("\u001b[1;4r".toByteArray()).topAnchoredScrollRegionHasFooter)
        assertFalse(engine.accept("\u001b[2;4r".toByteArray()).topAnchoredScrollRegionHasFooter)
        assertFalse(engine.accept("\u001b[r".toByteArray()).topAnchoredScrollRegionHasFooter)
    }
    @Test fun everyWholeRowEndpointMatchesTheLiveViewportWithoutAResidualJump() {
        for (line in 12..64) for (target in 80..700 step 7) {
            val source = 401
            val sourceGrid = source / line * line
            val targetGrid = target / line * line
            val motion = TerminalResizeMotion(source, sourceGrid, TerminalResizeMotion.Anchor.CONTENT,
                20_000f, 20_000f - sourceGrid)
            motion.retarget(source, target, targetGrid, 0)
            assertEquals((targetGrid - sourceGrid).toFloat(), motion.offset(target, 200), 0.002f)
        }
    }

    @Test fun reversalAndCancellationPreserveTheCurrentPixelAndSettleWithinOneRow() {
        val motion = TerminalResizeMotion(600, 589, TerminalResizeMotion.Anchor.BOTTOM, 0f, 0f)
        motion.retarget(600, 300, 285, 0)
        motion.offset(450, 50)
        motion.retarget(450, 600, 589, 50)
        val before = motion.offset(500, 70)
        motion.retarget(500, 500, 494, 70)
        assertEquals(before, motion.offset(500, 70), 0.001f)
        assertTrue(kotlin.math.abs(before - (494 - 589)) < 19f)
        motion.retarget(500, 500, 494, 100) // repeated layout must not extend settling
        assertEquals(66L, motion.remainingMillis(100))
        assertEquals((494 - 589).toFloat(), motion.offset(500, 166), 0.001f)
        assertEquals(0L, motion.remainingMillis(166))
    }

    @Test fun aFittingCursorDoesNotMoveButAnOccludedCursorMovesOnlyAsFarAsNeeded() {
        val motion = TerminalResizeMotion(600, 589, TerminalResizeMotion.Anchor.CURSOR, 76f, 0f)
        motion.retarget(600, 400, 399, 0)
        assertEquals(0f, motion.offset(400, 200), 0f)
        motion.retarget(400, 1_000, 988, 200)
        assertEquals(0f, motion.offset(1_000, 400), 0f)
        motion.retarget(1_000, 60, 57, 400)
        assertEquals(-19f, motion.offset(60, 600), 0f)
    }

    @Test fun shortTranscriptsStayAtTheTopAndGrowingTranscriptsKeepTheirLiveBottom() {
        val short = TerminalResizeMotion(600, 589, TerminalResizeMotion.Anchor.CONTENT, 76f, 0f)
        short.retarget(600, 400, 399, 0)
        assertEquals(0f, short.offset(400, 100), 0f)
        val full = TerminalResizeMotion(600, 589, TerminalResizeMotion.Anchor.CONTENT, 1_900f, 1_311f)
        full.retarget(600, 400, 399, 0)
        assertEquals(-190f, full.offset(400, 100), 0f)
    }

    @Test fun unavailableImeTargetsKeepPixelMotionAndOnlySettleTheFractionalTail() {
        val motion = TerminalResizeMotion(600, 589, TerminalResizeMotion.Anchor.BOTTOM, 0f, 0f)
        assertEquals(-100f, motion.offset(500, 0), 0f)
        motion.retarget(500, 500, 494, 0)
        assertEquals(-100f, motion.offset(500, 0), 0f)
        assertEquals(-95f, motion.offset(500, 96), 0f)
    }
}
