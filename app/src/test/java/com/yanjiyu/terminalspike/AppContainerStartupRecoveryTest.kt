package com.yanjiyu.terminalspike

import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerStartupRecoveryTest {
    @Test
    fun startupRecoveryRequiresAResultRemovedMarkerAndNoPendingMarker() {
        assertFalse(null.completedStartupRecovery(markerStillPending = false))
        assertFalse(result(markerRemoved = false).completedStartupRecovery(markerStillPending = false))
        assertFalse(result(markerRemoved = true).completedStartupRecovery(markerStillPending = true))
        assertTrue(result(markerRemoved = true).completedStartupRecovery(markerStillPending = false))
    }

    private fun result(markerRemoved: Boolean) = BackupImportResult(
        strategy = BackupImportStrategy.REPLACE_CORRESPONDING,
        hostsApplied = 0,
        credentialsApplied = 0,
        sshKeysApplied = 0,
        knownHostsApplied = 0,
        snippetsApplied = 0,
        terminalProfilesApplied = 0,
        terminalThemesApplied = 0,
        keyboardProfilesApplied = 0,
        unavailableSecretPlaceholders = 0,
        skippedRecords = 0,
        incompatibleRecords = 0,
        recoveryMarkerRemoved = markerRemoved,
    )
}
