package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Codex package in a separately prepared guest; no login or model requests. */
@RunWith(AndroidJUnit4::class)
class ArchCodexDaemonDeviceTest {
    @Test fun daemonStartsAndAnotherShellRecognizesItsProcessWithoutNoDaemon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchCodexDaemon") == "true")
        assertEquals("SM-G9708", Build.MODEL)
        val context = instrumentation.targetContext
        val rootfs = File(context.filesDir, "codex-daemon-fixture/archlinux-aarch64")
        assertTrue("Prepare the isolated pinned Arch/Codex fixture first", File(rootfs, "opt/codex/bin/codex").isFile)
        val temporary = File(context.filesDir, "codex-test-tmp").apply { mkdirs() }
        val libraries = File(context.applicationInfo.nativeLibraryDir)
        val guest = listOf("/bin/bash", "--noprofile", "--norc", "-c")

        // Keep the original failure visible on the authorized Android target.
        ProotCommand.archShell(libraries, rootfs, temporary,
            guest + "ps -p \$\$ -o stat= -o lstart=").start(100, 30).use { pty ->
            val red = finish(pty)
            assertTrue(red, red.contains("Unable to get system boot time"))
            assertNotEquals(0, pty.exitCode())
        }

        val compatibility = ArchProcStat.forAndroid(context)
        assertNotNull("Android must expose a stable boot identity", compatibility.prepare(temporary))
        val bootstrap = ArchBootstrap(libraries, compatibility)
        val start = "set -eu; cat /proc/stat; ps -p \$\$ -o stat= -o lstart=; /opt/codex/bin/codex --version; " +
            "/opt/codex/bin/codex -c analytics.enabled=false -c feedback.enabled=false app-server daemon start; " +
            "ls -l /root/.codex/app-server-control /tmp; readlink /root/.codex/app-server-control/app-server-control.sock; " +
            "printf '\\nCODEX_%s\\n' DAEMON_READY; read -r release; " +
            "/opt/codex/bin/codex app-server daemon stop; printf '\\nCODEX_%s\\n' DAEMON_STOPPED"
        bootstrap.command(rootfs, temporary, guest + start).start(100, 30).use { first ->
            val started = readUntil(first, "CODEX_DAEMON_READY")
            assertTrue(started, started.contains("codex-cli 0.166.0"))
            assertTrue(started, started.contains("\"status\":\"started\""))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCODEX_DAEMON_STARTED\n$started") })
            // A new owner must use the same boot epoch, including when its clock sample differs.
            val reopened = ArchBootstrap(libraries, ArchProcStat.forAndroid(context))
            val status = reopened.runGuest(rootfs, temporary,
                guest + ("ls -l /root/.codex/app-server-control /tmp; " +
                    "readlink /root/.codex/app-server-control/app-server-control.sock; " +
                    "/opt/codex/bin/codex app-server daemon version"), 30_000)
            assertTrue(status, status.contains("\"status\":\"running\""))
            first.write("\n".toByteArray())
            val stopped = finish(first)
            assertTrue(stopped, stopped.contains("CODEX_DAEMON_STOPPED"))
            assertEquals(0, first.exitCode())
            // A completed owner shell kills its traced daemon without a clean stop.
            // A fresh shell must find/remove the stale endpoint and start normally.
            reopened.runGuest(rootfs, temporary,
                guest + "/opt/codex/bin/codex -c analytics.enabled=false -c feedback.enabled=false app-server daemon start", 30_000)
            val restarted = reopened.runGuest(rootfs, temporary,
                guest + ("set -e; /opt/codex/bin/codex -c analytics.enabled=false -c feedback.enabled=false app-server daemon start; " +
                    "/opt/codex/bin/codex app-server daemon version; /opt/codex/bin/codex app-server daemon stop"), 30_000)
            assertTrue(restarted, restarted.contains("\"status\":\"started\""))
            assertTrue(restarted, restarted.contains("\"status\":\"running\""))
            assertTrue(restarted, restarted.contains("\"status\":\"stopped\""))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nARCH_CODEX_DAEMON_OK on ${Build.MODEL}\n$started\n$status\n$stopped\n$restarted")
            })
        }
    }

    private fun finish(pty: LocalPty): String = readUntil(pty, null)

    private fun readUntil(pty: LocalPty, marker: String?): String {
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val count = pty.read(buffer)
            if (count > 0) output.append(String(buffer, 0, count, Charsets.UTF_8))
            check(output.length < 128 * 1024) { "Unbounded Codex diagnostic output" }
            if (marker != null && output.contains(marker)) return output.toString()
            if (count < 0) {
                while (pty.exitCode() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
                assertNotNull("Guest did not exit: $output", pty.exitCode())
                return output.toString()
            }
        }
        fail("Timed out waiting for ${marker ?: "guest exit"}: $output")
        return output.toString()
    }
}
