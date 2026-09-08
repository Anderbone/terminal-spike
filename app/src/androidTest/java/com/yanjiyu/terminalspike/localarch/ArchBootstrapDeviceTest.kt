package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real pinned-image gate. Its persistent test rootfs is separate from the user's Arch environment. */
@RunWith(AndroidJUnit4::class)
class ArchBootstrapDeviceTest {
    @Test
    fun pinnedArchInstallsValidatesAndSharesPersistentFilesBetweenRealShells() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchBootstrap") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "local-arch-device-bootstrap")
        val temporary = File(context.filesDir, "arch-test-tmp")
        val environment = ArchEnvironment(context, directory, temporary)
        val installation = environment.install(replaceExisting =
            InstrumentationRegistry.getArguments().getString("localArchReinstall") == "true")
        assertTrue(environment.state.value.installed)
        assertEquals(ArchRootfsManifest.VERSION, environment.state.value.version)
        environment.withShell { firstLease ->
            firstLease.command.start(80, 24).use { first ->
                first.write(("test -t 0 && test -t 1 && test \"\$(id -u)\" = 0 && " +
                    "test \"\$(uname -m)\" = aarch64 && pacman --version && " +
                    "mkdir -p /root/projects && printf '%s\\n' 'persistent-arch-project' > /root/projects/device-gate.txt && " +
                    "printf '\\nARCH_%s\\n' FIRST_READY\n").toByteArray())
                val firstOutput = awaitOutput(first, "ARCH_FIRST_READY")
                assertTrue(firstOutput, firstOutput.contains("Pacman v"))
                first.resize(91, 27)
                first.write("stty size\n".toByteArray())
                awaitOutput(first, "27 91")
                first.write("printf '\nARCH_%s\n' INTERRUPT_READY; sleep 60\n".toByteArray())
                awaitOutput(first, "ARCH_INTERRUPT_READY")
                first.write(byteArrayOf(3))
                first.write("printf '\nARCH_INTERRUPT_STATUS_%s\n' \$?\n".toByteArray())
                awaitOutput(first, "ARCH_INTERRUPT_STATUS_130")
                environment.withShell { secondLease ->
                    assertEquals(installation.rootfs, secondLease.lease.installation.rootfs)
                    secondLease.command.start(80, 24).use { second ->
                        second.write(("PS1='ARCH_''PROMPT> '; test \"\$(cat /root/projects/device-gate.txt)\" = persistent-arch-project && " +
                            "printf '\\nARCH_%s\\n' SHARED_ROOTFS\n").toByteArray())
                        val sharedOutput = awaitOutput(second, "ARCH_PROMPT> ")
                        assertTrue(sharedOutput, sharedOutput.contains("ARCH_SHARED_ROOTFS"))
                        second.write(byteArrayOf(4))
                        val deadline = SystemClock.elapsedRealtime() + 10_000
                        val exiting = StringBuilder()
                        val buffer = ByteArray(8192)
                        while (second.exitCode() == null && SystemClock.elapsedRealtime() < deadline) {
                            val count = second.read(buffer)
                            if (count > 0) exiting.append(String(buffer, 0, count, Charsets.UTF_8))
                        }
                        assertEquals("Ctrl+D exits the real Arch shell: $exiting", 0, second.exitCode())
                    }
                }
            }
        }
        // A fresh owner reads the persisted completion marker and launches another real guest.
        val reopened = ArchEnvironment(context, directory, temporary)
        reopened.refresh()
        assertTrue(reopened.state.value.installed)
        reopened.withShell { lease ->
            lease.command.start(80, 24).use { pty ->
                pty.write("cat /root/projects/device-gate.txt\n".toByteArray())
                awaitOutput(pty, "persistent-arch-project")
            }
        }
    }

    private fun awaitOutput(pty: NativePty, marker: String): String {
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val count = pty.read(buffer)
            if (count < 0) break
            if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
            if (output.contains(marker)) return output.toString()
            if (output.length > 128 * 1024) fail("Unexpectedly large Arch probe output")
        }
        fail("Missing $marker in real Arch output: $output")
        return output.toString()
    }
}
