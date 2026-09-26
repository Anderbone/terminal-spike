package com.yanjiyu.terminalspike.terminal.view

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalImagePasteTest {
    @Test
    fun selectedMediaSupportsVideosWithoutBroadeningClipboardImages() {
        assertEquals("mp4", selectedMediaExtension("VIDEO/MP4; charset=binary"))
        assertEquals("webm", selectedMediaExtension("video/webm"))
        assertEquals("mov", selectedMediaExtension("video/quicktime"))
        assertEquals("mkv", selectedMediaExtension("video/x-matroska"))
        assertEquals("3gp", selectedMediaExtension("video/3gpp"))
        assertEquals("3g2", selectedMediaExtension("video/3gpp2"))
        assertEquals("mpeg", selectedMediaExtension("video/mpeg"))
        assertEquals("avi", selectedMediaExtension("video/x-msvideo"))
        assertEquals("png", selectedMediaExtension("image/png"))
        assertNull(selectedMediaExtension("text/plain"))
        assertNull(selectedMediaExtension("application/octet-stream"))
        assertNull(pastedImageExtension("video/mp4"))
    }

    @Test
    fun supportedClipboardMimeTypesMapToStableExtensions() {
        assertEquals("png", pastedImageExtension("image/png"))
        assertEquals("jpg", pastedImageExtension("IMAGE/JPEG; charset=binary"))
        assertEquals("webp", pastedImageExtension("image/webp"))
        assertEquals("gif", pastedImageExtension("image/gif"))
        assertNull(pastedImageExtension("image/svg+xml"))
        assertNull(pastedImageExtension("text/plain"))
    }

    @Test
    fun boundedStreamAllowsAnImageAtTheExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val actual = PastedImageSizeLimitInputStream(
            ByteArrayInputStream(bytes),
            maximumBytes = bytes.size.toLong(),
        ).readBytes()

        assertArrayEquals(bytes, actual)
    }

    @Test
    fun boundedStreamRejectsTheFirstByteBeyondTheLimit() {
        val stream = PastedImageSizeLimitInputStream(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
            maximumBytes = 4,
        )
        val buffer = ByteArray(8)

        assertEquals(4, stream.read(buffer))
        assertThrows(PastedImageTooLargeException::class.java) { stream.read(buffer) }
    }
}
