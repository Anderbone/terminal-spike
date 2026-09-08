package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit USB probe using a credential-free source archive supplied to the isolated test app. */
class ArchCableFlowDeviceTest {
    @Test fun runsCableFlowAndCodexInTheInstalledGuest() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchCableFlow") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".archverify"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val repository = (context.applicationContext as TerminalSpikeApplication).container.localSessionRepository
            val environment = repository.environment
            environment.refresh()
            assertTrue(environment.state.value.starterToolsInstalled)
            // Simulate a pre-starter installation without replacing its projects or configuration.
            lateinit var project: File
            lateinit var profile: File
            environment.withShell { lease ->
                val rootfs = lease.lease.installation.rootfs
                project = File(rootfs, "root/projects/starter-upgrade-preserved.txt")
                project.parentFile!!.mkdirs()
                project.writeText("preserve-existing-project\n")
                profile = File(rootfs, "etc/profile.d/terminal-spike-tools.sh")
                profile.appendText("\n# USER_PROFILE_KEEP\n")
                assertTrue(File(rootfs.parentFile, "starter-tools.version").delete())
            }
            val profileBefore = profile.readText()
            environment.refresh()
            assertFalse(environment.state.value.starterToolsInstalled)
            instrumentation.runOnMainSync { assertTrue(repository.installOrReset()) }
            val deadline = System.nanoTime() + 5 * 60_000_000_000L
            while (repository.runtime.value.installationActive && System.nanoTime() < deadline) {
                kotlinx.coroutines.delay(50)
            }
            assertFalse(repository.runtime.value.installationActive)
            assertNull(repository.runtime.value.error)
            assertNull(environment.state.value.error)
            assertTrue(environment.state.value.starterToolsInstalled)
            assertEquals("preserve-existing-project\n", project.readText())
            assertEquals(profileBefore, profile.readText())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "STARTER_UPGRADE_PRESERVED_PROJECT_AND_PROFILE\n")
            })
            environment.withShell { lease ->
                val rootfs = lease.lease.installation.rootfs
                val bootstrap = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir))
                val profileOutput = bootstrap.runGuest(rootfs, File(context.filesDir, "arch-tmp"),
                    listOf("/bin/bash", "--login", "-i", "-c", "test \"${'$'}(type -t z)\" = function && printf 'ARCH_ZOXIDE_PROFILE_OK\\n'"),
                    15_000L)
                assertTrue(profileOutput.contains("ARCH_ZOXIDE_PROFILE_OK"))
                val archive = File(context.filesDir, "cable-flow-source.tar")
                assertTrue("Transfer a credential-free committed source archive first", archive.isFile)
                archive.copyTo(File(rootfs, "root/cable-flow-source.tar"), overwrite = true)
                val script = instrumentation.context.assets.open("local-arch-cable-flow.sh").bufferedReader().use { it.readText() }
                val output = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir)).runGuest(rootfs,
                    File(context.filesDir, "arch-tmp"), listOf("/bin/bash", "--noprofile", "--norc", "-c", script),
                    60 * 60_000L)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", output) })
                assertTrue(output.contains("ARCH_CABLE_FLOW_HTTP_OK"))
                assertTrue(output.contains("ARCH_CODEX_CLI_OK"))
            }
        }
    }
}
