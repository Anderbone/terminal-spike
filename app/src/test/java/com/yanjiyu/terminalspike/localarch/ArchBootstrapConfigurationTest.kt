package com.yanjiyu.terminalspike.localarch

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArchBootstrapConfigurationTest {
    private val directory = Files.createTempDirectory("arch-config-test").toFile()
    private val root = File(directory, "rootfs").apply { mkdir() }
    private val bootstrap = ArchBootstrap(File("/unused/runtime"))

    init {
        File(root, "etc/pacman.d").mkdirs()
        File(root, "var/lib/pacman/sync").mkdirs()
        File(root, "var/lib/pacman/local").mkdirs()
    }

    @After fun cleanup() = ArchEnvironmentStore.deleteTree(directory.toPath())

    @Test fun firstConfigurationDropsStaleSyncDatabasesAndRetainsInstalledPackages() {
        File(root, "var/lib/pacman/sync/core.db").writeText("old repository metadata")
        val installed = File(root, "var/lib/pacman/local/installed-package").apply { writeText("installed") }
        bootstrap.configure(root, listOf("192.0.2.1"))
        assertTrue(File(root, "var/lib/pacman/sync").listFiles()!!.isEmpty())
        assertEquals("installed", installed.readText())
        assertTrue(File(root, "etc/resolv.conf").readText().contains("nameserver 192.0.2.1"))
    }

    @Test fun networkChangesRefreshGeneratedDnsAndPreserveSubsequentUserEdits() {
        bootstrap.configure(root, listOf("192.0.2.1"))
        bootstrap.refreshDns(root, listOf("192.0.2.2"))
        val resolver = File(root, "etc/resolv.conf")
        assertTrue(resolver.readText().contains("192.0.2.2"))
        resolver.writeText("nameserver 192.0.2.3\n# user managed\n")
        bootstrap.refreshDns(root, listOf("192.0.2.4"))
        assertEquals("nameserver 192.0.2.3\n# user managed\n", resolver.readText())
    }

    @Test fun configurationCannotFollowAGuestSymlinkIntoOtherAndroidFiles() {
        val outside = File(directory, "keep").apply { writeText("other data") }
        Files.createSymbolicLink(File(root, "etc/resolv.conf").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { bootstrap.configure(root, listOf("192.0.2.1")) }
        assertEquals("other data", outside.readText())
    }
}
