package com.yanjiyu.terminalspike.ui.sftp

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpPreviewCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameNameFromDifferentHostsHasIndependentContent() {
        val cache = SftpPreviewCache(temporary.root)
        val first = cache.download("photo.jpg") { it.write(byteArrayOf(1)) }
        val second = cache.download("photo.jpg") { it.write(byteArrayOf(2)) }
        assertNotEquals(first, second)
        assertEquals(1, first.readBytes().single().toInt())
        assertEquals(2, second.readBytes().single().toInt())
    }

    @Test fun failedDownloadRemovesPartialFile() {
        assertThrows(IOException::class.java) {
            SftpPreviewCache(temporary.root).download("clip.mp4") {
                it.write(byteArrayOf(1))
                throw IOException("Connection lost")
            }
        }
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsNamesThatEscapeTheCache() {
        listOf("../photo.jpg", "/photo.jpg", "..", "a\\b.jpg").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                SftpPreviewCache(temporary.root).download(name) { error("Must not write") }
            }
        }
    }

    @Test fun expiresOldPreviewsButRetainsRecentFiles() {
        val old = File(temporary.root, "old").apply { mkdir() }
        File(old, "clip.mp4").writeText("old")
        old.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        val recent = File(temporary.root, "recent").apply { mkdir() }
        SftpPreviewCache(temporary.root).download("new.jpg") { it.write(1) }
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
