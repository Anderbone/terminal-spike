package com.yanjiyu.terminalspike.localarch

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Separate compatibility probe: failure never changes the basic Arch bootstrap contract. */
class ArchPrismaDeviceTest {
    @Test fun stablePrismaSqliteCompatibility() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchPrisma") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val temporary = File(context.filesDir, "arch-test-tmp")
        val environment = ArchEnvironment(context, File(context.filesDir, "local-arch-device-bootstrap"), temporary)
        environment.refresh()
        assertTrue("Run the real bootstrap and package gates first", environment.state.value.installed)
        environment.withShell { lease ->
            val script = instrumentation.context.assets.open("local-arch-prisma.sh").bufferedReader().use { it.readText() }
            val output = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir)).runGuest(
                lease.lease.installation.rootfs, temporary,
                listOf("/bin/bash", "--noprofile", "--norc", "-c", script), 20 * 60_000L,
            )
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", output) })
            assertTrue(output.contains("ARCH_PRISMA_SQLITE_OK"))
        }
    }
}
