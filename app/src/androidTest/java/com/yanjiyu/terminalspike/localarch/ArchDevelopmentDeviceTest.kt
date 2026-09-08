package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real package/network workload in the bootstrap gate's separate persistent test environment. */
@RunWith(AndroidJUnit4::class)
class ArchDevelopmentDeviceTest {
    @Test
    fun runsSignedArchUpgradeAndNativeNodeDevelopmentWorkloads() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchDevelopment") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val temporary = File(context.filesDir, "arch-test-tmp")
        val environment = ArchEnvironment(context, File(context.filesDir, "local-arch-device-bootstrap"), temporary)
        environment.refresh()
        assertTrue("Run the real bootstrap gate first", environment.state.value.installed)
        val bootstrap = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir))
        environment.withShell { lease ->
            val rootfs = lease.lease.installation.rootfs
            fun runStage(name: String, asset: String, marker: String, timeoutMillis: Long) {
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nArch development stage: $name\n") })
                val script = instrumentation.context.assets.open(asset).bufferedReader().use { it.readText() }
                val output = bootstrap.runGuest(rootfs, temporary,
                    listOf("/bin/bash", "--noprofile", "--norc", "-c", script), timeoutMillis)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", output) })
                assertTrue("Missing $marker in real guest output", output.contains(marker))
            }
            runStage("signed pacman upgrade", "local-arch-packages.sh", "ARCH_PACKAGES_OK", 20 * 60_000L)
            runStage("TypeScript and native npm modules", "local-arch-node.sh", "ARCH_NODE_NATIVE_OK", 20 * 60_000L)
        }
    }
}
