package com.yanjiyu.terminalspike.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalResizeRevealTest {
    @Test fun explicitCompleteFrameDoesNotPayTheQuietTimeHeuristic() {
        val reveal = TerminalResizeReveal(1_000L, 7L)
        reveal.onOutputPublished(8L, 1_100L, synchronizedFrame = true)
        assertEquals(0L, reveal.delayMillis(1_100L))
    }
    @Test fun silentConnectionAlwaysRevealsWithinHalfASecond() {
        val reveal = TerminalResizeReveal(1_000L, 7L)
        assertEquals(500L, reveal.delayMillis(1_000L))
        assertEquals(0L, reveal.delayMillis(1_500L))
        assertEquals(0L, reveal.delayMillis(2_000L))
    }

    @Test fun localResizeAndRepeatedNotificationsDoNotCountAsRemoteRepaint() {
        val reveal = TerminalResizeReveal(1_000L, 7L)
        reveal.onOutputPublished(7L, 1_100L)
        assertEquals(400L, reveal.delayMillis(1_100L))
        reveal.onOutputPublished(8L, 1_120L)
        reveal.onOutputPublished(8L, 1_150L)
        assertEquals(18L, reveal.delayMillis(1_150L))
        assertEquals(0L, reveal.delayMillis(1_168L))
    }

    @Test fun splitRemoteRepaintWaitsForTheLastPublishedChunk() {
        val reveal = TerminalResizeReveal(1_000L, 7L)
        reveal.onOutputPublished(8L, 1_100L)
        reveal.onOutputPublished(9L, 1_130L)
        assertEquals(48L, reveal.delayMillis(1_130L))
        assertEquals(1L, reveal.delayMillis(1_177L))
        assertEquals(0L, reveal.delayMillis(1_178L))
    }

    @Test fun continuousRemoteOutputCannotKeepTheOldPictureIndefinitely() {
        val reveal = TerminalResizeReveal(1_000L, 7L)
        for (index in 1L..50L) {
            val now = 1_000L + index * 10L
            reveal.onOutputPublished(7L + index, now)
            assertEquals(minOf(48L, 1_500L - now), reveal.delayMillis(now))
        }
    }
}
