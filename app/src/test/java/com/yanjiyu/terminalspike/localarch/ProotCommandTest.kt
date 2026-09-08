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
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "LD_LIBRARY_PATH"), command.environment.keys)
        assertTrue(command.arguments.contains("--kill-on-exit"))
        assertTrue(command.arguments.contains("--link2symlink"))
        assertTrue(command.arguments.contains("--sysvipc"))
        assertFalse(command.arguments.any { it.contains("/storage/") || it.contains("/sdcard") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousBindPathIsRejectedBeforeLaunching() {
        ProotCommand.archShell(File("/apk/lib"), File("/private/root:other"), File("/private/tmp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun temporarySocketFallbackMustFitTheNativeUnixSocketLimit() {
        ProotCommand.archShell(File("/apk/lib"), File("/private/root"), File("/private/" + "long-generation-path".repeat(6)))
    }
}
