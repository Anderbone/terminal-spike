package com.yanjiyu.terminalspike.terminal.view

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalImagePasteTest {
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
