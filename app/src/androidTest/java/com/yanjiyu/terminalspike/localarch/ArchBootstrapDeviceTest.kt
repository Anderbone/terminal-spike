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
        assertTrue("Use an authorized old-phone target", Build.MODEL in setOf("SM-S911B", "SM-G9708"))
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "local-arch-device-bootstrap")
        val temporary = File(context.filesDir, "arch-test-tmp")
        val environment = ArchEnvironment(context, directory, temporary)
        val installation = environment.install(replaceExisting =
            InstrumentationRegistry.getArguments().getString("localArchReinstall") == "true")
        assertTrue(environment.state.value.installed)
        assertEquals(ArchRootfsManifest.VERSION, environment.state.value.version)
        environment.withShell(listOf("/bin/bash", "--login")) { firstLease ->
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
                environment.withShell(listOf("/bin/bash", "--login")) { secondLease ->
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
        val bootstrap = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir))
        val aurProbe = bootstrap.runGuest(installation.rootfs, temporary,
            listOf("/bin/bash", "-c", AUR_PROBE), timeoutMillis = 120_000L)
        assertTrue(aurProbe, aurProbe.contains("ARCH_AUR_BUILD_AND_INSTALL_OK"))
        val codexStartup = bootstrap.runGuest(installation.rootfs, temporary,
            listOf("/bin/bash", "-c", CODEX_STARTUP_PROBE), timeoutMillis = 45_000L)
        assertTrue(codexStartup, codexStartup.contains("CODEX_LOCAL_STARTUP_OK"))
        assertFalse(codexStartup, codexStartup.contains("could not find bubblewrap", ignoreCase = true))
        assertTrue(environment.state.value.toolsVersions.orEmpty().contains("fish, version"))
        assertTrue(environment.state.value.toolsVersions.orEmpty().contains("herdr"))
        // A fresh owner reads the persisted completion marker and launches another real guest.
        val reopened = ArchEnvironment(context, directory, temporary)
        reopened.refresh()
        assertTrue(reopened.state.value.installed)
        reopened.withShell { lease ->
            assertEquals(listOf("/usr/bin/fish", "--login"), lease.command.arguments.takeLast(2))
            lease.command.start(80, 24).use { pty ->
                pty.write("fish --version; cat /root/projects/device-gate.txt\n".toByteArray())
                awaitOutput(pty, "persistent-arch-project")
            }
        }
    }

    // Initialize the real local server and create a thread, without sending a
    // turn or model request. Capture startup warnings instead of hiding them.
    private val CODEX_STARTUP_PROBE = """
        set -eu
        command -v bwrap
        bwrap --version
        node <<'NODE'
        const {spawn} = require('node:child_process');
        const {createInterface} = require('node:readline');
        const child = spawn('codex', ['app-server'], {stdio: ['pipe', 'pipe', 'pipe']});
        let ready = false;
        let badWarning = false;
        const deadline = setTimeout(() => { process.exitCode = 1; child.kill(); }, 30000);
        const send = value => child.stdin.write(JSON.stringify(value) + '\n');
        child.stderr.on('data', data => {
            const text = data.toString();
            if (/could not find.*(?:bubblewrap|bwrap)/i.test(text)) badWarning = true;
            process.stderr.write(text);
        });
        createInterface({input: child.stdout}).on('line', line => {
            let message;
            try { message = JSON.parse(line); } catch { return; }
            if (message.method === 'warning' || message.method === 'configWarning') {
                const warning = JSON.stringify(message.params);
                console.log(warning);
                if (/bubblewrap|bwrap/i.test(warning)) badWarning = true;
            }
            if (message.error) { console.error(JSON.stringify(message.error)); process.exitCode = 1; child.stdin.end(); return; }
            if (message.id === 1 && message.result) {
                send({method: 'initialized', params: {}});
                send({id: 2, method: 'thread/start', params: {ephemeral: true}});
            }
            if (message.id === 2 && message.result) {
                ready = true;
                setTimeout(() => child.stdin.end(), 1000);
            }
        });
        child.on('error', error => { console.error(error.message); process.exitCode = 1; });
        child.on('exit', code => {
            clearTimeout(deadline);
            if (!ready || badWarning || code !== 0) process.exitCode = 1;
            else console.log('CODEX_LOCAL_STARTUP_OK');
        });
        send({id: 1, method: 'initialize', params: {
            clientInfo: {name: 'terminal_spike_fixture', version: '1.0.0'}
        }});
        NODE
    """.trimIndent()

    private val AUR_PROBE = """
        set -eu
        test "${'$'}(runuser -u aur -- id -u)" != 0
        test "${'$'}(runuser -u aur -- sudo -n id -u)" = 0
        bwrap --version
        test -f /usr/local/bin/claude && test ! -L /usr/local/bin/claude
        test -f /usr/local/bin/opencode && test ! -L /usr/local/bin/opencode
        claude --version
        opencode --version
        yay --version
        yay --aur -Si yay-bin
        herdr --version
        runuser -u aur -- bash -c '
            set -eu
            mkdir -p "${'$'}HOME/terminal-spike-aur-smoke"
            cd "${'$'}HOME/terminal-spike-aur-smoke"
            cat > PKGBUILD <<"PKG"
        pkgname=terminal-spike-aur-smoke
        pkgver=1
        pkgrel=1
        arch=(any)
        package() {
            install -d "${'$'}pkgdir/usr/share/terminal-spike"
            printf aur-smoke > "${'$'}pkgdir/usr/share/terminal-spike/aur-smoke"
        }
        PKG
            makepkg --force --noconfirm --install
        '
        test "${'$'}(cat /usr/share/terminal-spike/aur-smoke)" = aur-smoke
        printf '\nARCH_AUR_BUILD_AND_INSTALL_OK\n'
    """.trimIndent()

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
