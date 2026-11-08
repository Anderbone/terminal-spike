package com.yanjiyu.terminalspike.localarch

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ArchInstallDiagnosticsTest {
    @Test fun starterFailureShowsTheRealErrorAndExitStatus() {
        val error = archInstallationError(ArchInstallPhase.INSTALLING_TOOLS,
            IOException("Arch command exited with status 1:\nError: claude native binary not installed."))
        assertTrue(error.contains("installing starter tools and system updates"))
        assertTrue(error.contains("status 1"))
        assertTrue(error.contains("claude native binary not installed"))
        assertFalse(error.contains("Check the network"))
    }

    @Test fun terminalControlsAreRemovedAndTheFailureTailIsBounded() {
        val error = archDiagnosticOutput("x".repeat(30_000) +
            "\u001B]11;?\u001B\\\u001B[31m\r\nError: claude native binary not installed.\u001B[0m\n")
        assertEquals(12 * 1024, error.length)
        assertTrue(error.endsWith("\nError: claude native binary not installed."))
        assertFalse(error.contains('\u001B'))
    }
}
