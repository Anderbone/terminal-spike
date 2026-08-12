package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.quantityText
import com.yanjiyu.terminalspike.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsUiFormattingTest {
    @Test
    fun snippetPreviewNormalizesLinesAndMakesControlAndFormatCharactersVisible() {
        val preview = safeSnippetPreview("echo one\r\nprintf '\u001B[31m'\u202E", maxCodePoints = 80, maxLines = 4)

        assertEquals("echo one\nprintf '�[31m'�", preview)
        assertFalse(preview.contains('\r'))
        assertFalse(preview.contains('\u001B'))
        assertFalse(preview.contains('\u202E'))
    }

    @Test
    fun snippetPreviewTruncatesByCodePointWithoutSplittingEmoji() {
        val preview = safeSnippetPreview("A😀BC", maxCodePoints = 2, maxLines = 1)

        assertEquals("A😀…", preview)
        assertTrue(preview.last() == '…')
    }

    @Test
    fun snippetPreviewBoundsMultilineOutput() {
        assertEquals("one\ntwo…", safeSnippetPreview("one\ntwo\nthree", maxCodePoints = 80, maxLines = 2))
    }

    @Test
    fun hostActivityFormattingUsesStableBoundaries() {
        val now = 10L * 86_400_000L

        assertEquals(uiText(R.string.connections_last_used_now), formatHostLastUsed(now - 59_999L, now))
        assertEquals(
            quantityText(R.plurals.connections_last_used_minutes, 1),
            formatHostLastUsed(now - 60_000L, now),
        )
        assertEquals(
            quantityText(R.plurals.connections_last_used_hours, 1),
            formatHostLastUsed(now - 3_600_000L, now),
        )
        assertEquals(
            quantityText(R.plurals.connections_last_used_days, 1),
            formatHostLastUsed(now - 86_400_000L, now),
        )
        assertEquals(uiText(R.string.connections_last_used_unavailable), formatHostLastUsed(0L, now))
    }
}
