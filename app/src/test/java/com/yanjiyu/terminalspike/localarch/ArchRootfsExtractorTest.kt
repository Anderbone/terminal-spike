package com.yanjiyu.terminalspike.localarch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Date
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ArchRootfsExtractorTest {
    private lateinit var directory: Path
    private val root get() = directory.resolve("rootfs")

    @Before fun createDirectory() { directory = Files.createTempDirectory("arch-extraction-test") }
    @After fun removeDirectoryWithoutFollowingLinks() {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun preservesExecutablePermissionsAndGuestAbsoluteLinks() {
        val archive = tar(
            Entry("usr/bin/bash", "binary", mode = 0x1ed),
            Entry("bin", link = "/usr/bin"),
        )
        extract(archive)
        assertEquals("binary", Files.readString(root.resolve("usr/bin/bash")))
        assertTrue(Files.getPosixFilePermissions(root.resolve("usr/bin/bash")).contains(PosixFilePermission.OWNER_EXECUTE))
        assertEquals("/usr/bin", Files.readSymbolicLink(root.resolve("bin")).toString())
    }

    @Test
    fun preservesArchiveTimestampInsteadOfMakingStalePackageDatabasesLookCurrent() {
        extract(tar(Entry("var/lib/pacman/sync/core.db", "old database")))
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(root.resolve("var/lib/pacman/sync/core.db")).toMillis())
    }

    @Test
    fun rejectsTraversalBeforeWritingOutsideStaging() {
        expectIo { extract(tar(Entry("../escaped", "untrusted"))) }
        assertFalse(Files.exists(directory.resolve("escaped")))
    }

    @Test
    fun symlinkCannotRedirectLaterFileCreationOutsideStaging() {
        val outside = directory.resolve("outside").also(Files::createDirectory)
        expectIo {
            extract(tar(Entry("redirect", link = outside.toString()), Entry("redirect/escaped", "untrusted")))
        }
        assertFalse(Files.exists(outside.resolve("escaped")))
        assertTrue(Files.isDirectory(root.resolve("redirect"), NOFOLLOW_LINKS))
    }

    @Test
    fun refusesToOverwriteAnExistingEnvironment() {
        Files.createDirectory(root)
        Files.writeString(root.resolve("project.ts"), "user data")
        expectIo { extract(tar(Entry("project.ts", "replacement"))) }
        assertEquals("user data", Files.readString(root.resolve("project.ts")))
    }

    @Test
    fun extractionBudgetIsCheckedBeforeOpeningAnOversizedFile() {
        expectIo { extract(tar(Entry("large", "12345")), ArchRootfsExtractor(maximumBytes = 4)) }
        assertFalse(Files.exists(root.resolve("large")))
    }

    @Test
    fun duplicateFileDoesNotReplaceEarlierContents() {
        expectIo { extract(tar(Entry("same", "first"), Entry("same", "second"))) }
        assertEquals("first", Files.readString(root.resolve("same")))
    }

    @Test
    fun truncatedTarCannotReportSuccessfulExtraction() {
        val archive = tar(Entry("partial", "payload"))
        expectIo { extract(archive.copyOf(514)) }
    }

    @Test
    fun cancellationStopsExtractionWithoutTouchingAnExistingEnvironment() {
        val existing = directory.resolve("active").also(Files::createDirectory)
        Files.writeString(existing.resolve("keep"), "persistent")
        try {
            ArchRootfsExtractor().extractTar(ByteArrayInputStream(tar(Entry("first", "data"))), root) {
                throw java.util.concurrent.CancellationException()
            }
            fail("Cancellation was ignored")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals("persistent", Files.readString(existing.resolve("keep")))
        }
    }

    private fun extract(bytes: ByteArray, extractor: ArchRootfsExtractor = ArchRootfsExtractor()) =
        extractor.extractTar(ByteArrayInputStream(bytes), root)

    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Unsafe/incomplete extraction was accepted") } catch (_: IOException) { }
    }

    private data class Entry(val path: String, val text: String = "", val mode: Int = 0x1a4, val link: String? = null)

    private fun tar(vararg entries: Entry): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            entries.forEach { item ->
                val entry = TarArchiveEntry(
                    ArchRootfsManifest.ARCHIVE_PREFIX + "/" + item.path,
                    if (item.link == null) TarConstants.LF_NORMAL else TarConstants.LF_SYMLINK,
                )
                entry.mode = item.mode
                entry.modTime = Date(1_700_000_000_000L)
                if (item.link != null) entry.linkName = item.link else entry.size = item.text.toByteArray().size.toLong()
                tar.putArchiveEntry(entry)
                if (item.link == null) tar.write(item.text.toByteArray())
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
