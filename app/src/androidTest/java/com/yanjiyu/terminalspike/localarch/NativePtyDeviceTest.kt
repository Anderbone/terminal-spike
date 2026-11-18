package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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
        assertTrue("Authorized S23 or S10e required", Build.MODEL in setOf("SM-S911B", "SM-G9708"))
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
            val interrupted = awaitOutput(pty, "PTY_INTERRUPTED_130")
            // The line editor can flush input while restoring its prompt after a command.
            // Wait for the next input boundary before sending the single Ctrl+D.
            if (!interrupted.substringAfter("PTY_INTERRUPTED_130").contains(SHELL_PROMPT)) {
                awaitOutput(pty, SHELL_PROMPT)
            }
            pty.write(byteArrayOf(4))
            val deadline = SystemClock.elapsedRealtime() + 5_000
            val closingOutput = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < deadline && pty.exitCode() == null) {
                val count = pty.read(buffer)
                if (count > 0) closingOutput.write(buffer, 0, count)
                check(closingOutput.size() <= 64 * 1024)
                SystemClock.sleep(20)
            }
            assertEquals("Ctrl+D output: ${closingOutput.toString("UTF-8")}", 0, pty.exitCode())
        }
    }

    @Test
    fun packagedProotExecutesWithItsPrivateLibraryDependencies() {
        val library = context.applicationInfo.nativeLibraryDir
        val tmp = Files.createTempDirectory(context.cacheDir.toPath(), "proot-runtime-probe-").toFile()
        try {
            NativePty.start(
                "$library/libproot.so", listOf("--version"),
                mapOf("LD_LIBRARY_PATH" to library, "PROOT_TMP_DIR" to tmp.path,
                    "PROOT_LOADER" to "$library/libproot_loader.so"),
                context.filesDir.path, 80, 24,
            ).use { pty -> awaitOutput(pty, "5.1.107.81") }
        } finally {
            assertTrue("Private PRoot fixture must be removed", tmp.deleteRecursively())
        }
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

    @Test
    fun argumentsPreserveUtf8EmptyValuesAndWhitespace() {
        val value = "argument 中文 👩🏽‍💻 e\u0301"
        NativePty.start(
            "/system/bin/sh", listOf("-c", "printf '<%s><%s><%s>' \"\$1\" \"\$2\" \"\$3\"", "probe", value, "", " space "),
            emptyMap(), context.filesDir.path, 80, 24,
        ).use { pty ->
            assertArrayEquals("<$value><>< space >".toByteArray(Charsets.UTF_8), outputUntilExit(pty))
        }
    }

    @Test
    fun environmentValuesReachTheChildAsStandardUtf8() {
        val value = "environment 中文 🚀 𐐷 e\u0301"
        NativePty.start(
            "/system/bin/sh", listOf("-c", "printf '%s' \"\$SPIKE_UNICODE\""),
            mapOf("SPIKE_UNICODE" to value), context.filesDir.path, 80, 24,
        ).use { pty -> assertArrayEquals(value.toByteArray(Charsets.UTF_8), outputUntilExit(pty)) }
    }

    @Test
    fun workingDirectoryPreservesSupplementaryUnicode() = withPrivateDirectory { root ->
        val directory = File(root, "目录-🚀-𐐷").apply { check(mkdir()) }
        NativePty.start(
            "/system/bin/sh", listOf("-c", "pwd -P"), emptyMap(), directory.path, 80, 24,
        ).use { pty ->
            assertArrayEquals((directory.canonicalPath + "\r\n").toByteArray(Charsets.UTF_8), outputUntilExit(pty))
        }
    }

    @Test
    fun executablePathPreservesSupplementaryUnicode() = withPrivateDirectory { root ->
        val executable = File(root, "shell-中文-🚀")
        Files.createSymbolicLink(executable.toPath(), File("/system/bin/sh").toPath())
        NativePty.start(
            executable.path, listOf("-c", "printf 'PTY_UNICODE_EXECUTABLE'"),
            emptyMap(), context.filesDir.path, 80, 24,
        ).use { pty ->
            assertArrayEquals("PTY_UNICODE_EXECUTABLE".toByteArray(Charsets.UTF_8), outputUntilExit(pty))
        }
    }

    private fun withPrivateDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "pty-unicode-").toFile()
        try {
            test(directory)
        } finally {
            assertTrue("Private Unicode fixture must be removed", directory.deleteRecursively())
        }
    }

    private fun outputUntilExit(pty: NativePty): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var eof = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val count = pty.read(buffer)
            if (count < 0) { eof = true; break }
            if (count > 0) output.write(buffer, 0, count)
            check(output.size() <= 64 * 1024) { "Unbounded PTY probe output" }
        }
        assertTrue("Child must close its PTY", eof)
        while (SystemClock.elapsedRealtime() < deadline && pty.exitCode() == null) SystemClock.sleep(20)
        assertEquals("Child must exit successfully", 0, pty.exitCode())
        return output.toByteArray()
    }

    private fun shell(): NativePty = NativePty.start(
        "/system/bin/sh", listOf("-i"),
        mapOf("PATH" to "/system/bin", "TERM" to "xterm-256color", "HOME" to context.filesDir.path,
            "ENV" to "/dev/null", "PS1" to SHELL_PROMPT),
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

    private companion object {
        const val SHELL_PROMPT = "PTY_PROMPT_READY> "
    }
}
