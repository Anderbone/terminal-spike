package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArchEnvironmentStoreTest {
    private val directory = Files.createTempDirectory("arch-store-test").toFile()
    private val store = ArchEnvironmentStore(File(directory, "environment"))

    @After fun cleanup() = ArchEnvironmentStore.deleteTree(directory.toPath())

    @Test fun interruptedInstallNeverBecomesInstalledAndIsCleanedOnRetry() {
        val abandoned = store.beginOperation().use { operation ->
            operation.prepare()
            operation.rootfs.mkdir()
            File(operation.rootfs, "incomplete").writeText("partial")
            operation.generation
        }
        val reopened = ArchEnvironmentStore(File(directory, "environment"))
        assertNull(reopened.installed())
        reopened.beginOperation().use { operation ->
            operation.prepare()
            assertFalse(abandoned.exists())
            assertNull(reopened.installed())
        }
    }

    @Test fun failedReplacementPreservesExistingProjectsAndInstallation() {
        val installed = install()
        val project = File(installed.rootfs, "project.txt").apply { writeText("user project") }
        store.beginOperation().use { operation ->
            operation.prepare()
            operation.rootfs.mkdir()
            assertThrows(IOException::class.java) {
                operation.activate { throw IOException("guest validation failed") }
            }
        }
        assertEquals(installed, store.installed())
        assertEquals("user project", project.readText())
        assertEquals(installed, ArchEnvironmentStore(File(directory, "environment")).installed())
    }

    @Test fun multipleLeasesPreventDestructiveOperationsUntilAllProcessesClose() {
        val installed = install()
        val first = store.acquire()
        val second = store.acquire()
        assertEquals(installed.rootfs, first.installation.rootfs)
        assertEquals(first.installation, second.installation)
        assertThrows(IllegalStateException::class.java) { store.beginOperation() }
        first.close()
        first.close()
        assertThrows(IllegalStateException::class.java) { store.beginOperation() }
        second.close()
        store.beginOperation().use { operation ->
            assertThrows(IllegalStateException::class.java) { store.acquire() }
            operation.reset()
        }
        assertNull(store.installed())
    }

    @Test fun resetDoesNotFollowGuestLinksIntoOtherData() {
        val installed = install()
        val outside = File(directory, "other-data").apply { mkdir() }
        val secret = File(outside, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(installed.rootfs, "outside").toPath(), outside.toPath())
        store.beginOperation().use { it.reset() }
        assertNull(store.installed())
        assertEquals("keep", secret.readText())
        assertFalse(installed.rootfs.exists())
    }

    @Test fun resetRemovesPackageCreatedDirectoriesWithNoAccessPermissions() {
        val installed = install()
        val restricted = File(installed.rootfs, "run/systemd/dissect-root").apply { mkdirs() }
        File(restricted, "private-data").writeText("package-created data")
        Files.setPosixFilePermissions(restricted.toPath(), emptySet())
        try {
            store.beginOperation().use { it.reset() }
            assertNull(store.installed())
            assertFalse(installed.rootfs.exists())
        } finally {
            // Restore access for test cleanup on the red implementation.
            if (Files.exists(restricted.toPath())) {
                Files.setPosixFilePermissions(restricted.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    @Test fun completedReplacementBecomesTheOnlyActiveGeneration() {
        val first = install()
        val second = install()
        assertNotEquals(first.id, second.id)
        assertEquals(second, store.installed())
        store.beginOperation().use { it.cleanupInactive() }
        assertFalse(first.rootfs.exists())
        assertTrue(second.rootfs.isDirectory)
    }

    @Test fun corruptOrTraversingMetadataDoesNotActivateAnotherDirectory() {
        val installed = install()
        val project = File(installed.rootfs, "keep.txt").apply { writeText("project") }
        File(directory, "environment/active.properties").writeText(
            "format=1\ngeneration=../../outside\nversion=test\nsha256=" + ArchRootfsManifest.SHA256,
        )
        assertThrows(IOException::class.java) { store.installed() }
        store.beginOperation().use { operation ->
            assertThrows(IOException::class.java) { operation.prepare() }
        }
        assertEquals("project", project.readText())
    }

    private fun install(): ArchEnvironmentStore.Installation = store.beginOperation().use { operation ->
        operation.prepare()
        operation.rootfs.mkdir()
        operation.activate { assertTrue(it.isDirectory) }
    }
}
