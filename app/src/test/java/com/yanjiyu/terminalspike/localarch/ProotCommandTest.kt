package com.yanjiyu.terminalspike.localarch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotCommandTest {
    @Test
    fun guestReceivesCleanEnvironmentAndPersistentRootRatherThanHostShellInterpolation() {
        val root = File("/private/arch with spaces/rootfs")
        val command = ProotCommand.archShell(File("/apk/lib/arm64"), root, File("/private/tmp"))
        assertEquals("/apk/lib/arm64/libproot.so", command.executable)
        assertEquals(root.path, command.arguments[command.arguments.indexOf("-r") + 1])
        assertEquals(listOf("/bin/bash", "--login"), command.arguments.takeLast(2))
        assertEquals("-i", command.arguments[command.arguments.indexOf("/usr/bin/env") + 1])
        assertEquals("/apk/lib/arm64/libproot_loader.so", command.environment["PROOT_LOADER"])
        assertEquals("1", command.environment["PROOT_SHARED_UNIX_SOCKETS"])
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_SHARED_UNIX_SOCKETS", "LD_LIBRARY_PATH"), command.environment.keys)
        assertTrue(command.arguments.contains("--kill-on-exit"))
        assertTrue(command.arguments.contains("--link2symlink"))
        assertTrue(command.arguments.contains("--sysvipc"))
        assertFalse(command.arguments.any { it.contains("/storage/") || it.contains("/sdcard") })
    }

    @Test
    fun interactiveShellMigratesToFishAfterInstallationAndKeepsBashForOlderGuests() {
        val root = java.nio.file.Files.createTempDirectory("arch-shell").toFile()
        try {
            assertEquals(listOf("/bin/bash", "--login"), ProotCommand.interactiveShell(root))
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/fish").writeText("fish binary fixture")
            val selected = ProotCommand.interactiveShell(root)
            assertEquals(listOf("/usr/bin/fish", "--login"), selected)
            val command = ProotCommand.archShell(File("/apk/lib"), root, File("/private/tmp"), selected)
            assertTrue(command.arguments.contains("SHELL=/usr/bin/fish"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousBindPathIsRejectedBeforeLaunching() {
        ProotCommand.archShell(File("/apk/lib"), File("/private/root:other"), File("/private/tmp"))
    }

    @Test
    fun bootTimeOverrideFollowsTheRealProcBindAndLeavesProcessEntriesUnchanged() {
        val command = ProotCommand.archShell(File("/apk/lib"), File("/private/root"), File("/private/tmp"),
            procStat = File("/private/tmp/proc-stat-boot"))
        assertTrue(command.arguments.indexOf("/proc") < command.arguments.indexOf("/private/tmp/proc-stat-boot:/proc/stat"))
        assertFalse(command.arguments.any { it.endsWith(":/proc/uptime") || it.endsWith(":/proc/self/stat") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun temporarySocketFallbackMustFitTheNativeUnixSocketLimit() {
        ProotCommand.archShell(File("/apk/lib"), File("/private/root"), File("/private/" + "long-generation-path".repeat(6)))
    }
}
