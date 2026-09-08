package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in PTY primitive gate, deliberately separate from real Arch acceptance. */
@RunWith(AndroidJUnit4::class)
class NativePtyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun authorizedOldPhoneOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchPty") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        assertTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
    }

    @Test
    fun realPtyPropagatesResizeAndControlCharacters() {
        shell().use { pty ->
            pty.write("test -t 0 && test -t 1 && printf '\nPTY_%s\n' READY\n".toByteArray())
            awaitOutput(pty, "PTY_READY")
            pty.resize(87, 23)
            pty.write("stty size\n".toByteArray())
            awaitOutput(pty, "23 87")
            pty.write("sleep 30\n".toByteArray())
            // Wait until the input has actually reached the interactive shell.
            awaitOutput(pty, "sleep 30")
            SystemClock.sleep(300)
            pty.write(byteArrayOf(3))
            awaitOutput(pty, "^C")
            pty.write("printf '\\nPTY_%s_%s\\n' INTERRUPTED \"\$?\"\n".toByteArray())
            awaitOutput(pty, "PTY_INTERRUPTED_130")
            pty.write(byteArrayOf(4))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline && pty.exitCode() == null) {
                SystemClock.sleep(20)
            }
            assertEquals(0, pty.exitCode())
        }
    }

    @Test
    fun packagedProotExecutesWithItsPrivateLibraryDependencies() {
        val library = context.applicationInfo.nativeLibraryDir
        val tmp = File(context.cacheDir, "proot-runtime-probe").apply { mkdirs() }
        NativePty.start(
            "$library/libproot.so", listOf("--version"),
            mapOf("LD_LIBRARY_PATH" to library, "PROOT_TMP_DIR" to tmp.path,
                "PROOT_LOADER" to "$library/libproot_loader.so"),
            context.filesDir.path, 80, 24,
        ).use { pty -> awaitOutput(pty, "5.1.107.81") }
        tmp.deleteRecursively()
    }

    @Test
    fun closingOnePtyDoesNotCloseOrResizeAnother() {
        val first = shell()
        shell().use { second ->
            try {
                first.resize(64, 17)
                second.resize(93, 29)
                first.close()
                first.close()
                second.write("stty size; printf '\nPTY_%s\n' SECOND\n".toByteArray())
                val output = awaitOutput(second, "PTY_SECOND")
                assertTrue(output, output.contains("29 93"))
            } finally {
                first.close()
            }
        }
    }

    @Test
    fun missingExecutableFailsAtLaunchInsteadOfPretendingToConnect() {
        try {
            NativePty.start("/missing/local-arch-executable", emptyList(), emptyMap(), context.filesDir.path, 80, 24).close()
            fail("Missing executable was accepted")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message.orEmpty().contains("Execute local terminal"))
        }
    }

    private fun shell(): NativePty = NativePty.start(
        "/system/bin/sh", listOf("-i"),
        mapOf("PATH" to "/system/bin", "TERM" to "xterm-256color", "HOME" to context.filesDir.path),
        context.filesDir.path, 80, 24,
    )

    private fun awaitOutput(pty: NativePty, marker: String): String {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        while (SystemClock.elapsedRealtime() < deadline) {
            val count = pty.read(buffer)
            if (count < 0) break
            if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
            if (output.contains(marker)) return output.toString()
            if (output.length > 64 * 1024) fail("PTY produced unbounded probe output")
        }
        fail("Missing $marker in PTY output: $output")
        return output.toString()
    }
}
