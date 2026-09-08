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

/** Reproduces PostgreSQL's remove/reuse sequence through actual guest syscalls. */
class ArchSysVIpcDeviceTest {
    @Test fun removedSharedMemoryKeyCanBeReusedWithoutDeadlock() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localArchSysvIpc") == "true")
        assertEquals("SM-S911B", Build.MODEL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".archverify"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val environment = (context.applicationContext as TerminalSpikeApplication).container.localSessionRepository.environment
            environment.refresh()
            assertTrue(environment.state.value.starterToolsInstalled)
            environment.withShell { lease ->
                val script = instrumentation.context.assets.open("local-arch-sysvipc.sh").bufferedReader().use { it.readText() }
                val output = ArchBootstrap(File(context.applicationInfo.nativeLibraryDir)).runGuest(
                    lease.lease.installation.rootfs, File(context.filesDir, "arch-tmp"),
                    listOf("/bin/bash", "--noprofile", "--norc", "-c", script), 30_000L)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", output) })
                assertTrue(output.contains("ARCH_SYSVIPC_KEY_REUSE_OK"))
            }
        }
    }
}
