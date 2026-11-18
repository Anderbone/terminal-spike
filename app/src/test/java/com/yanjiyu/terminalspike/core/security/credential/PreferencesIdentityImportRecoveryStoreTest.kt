package com.yanjiyu.terminalspike.core.security.credential

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesIdentityImportRecoveryStoreTest {
    @Test
    fun preferenceAcquisitionIsDeferredUntilTheGuardedReadOrWrite() {
        val failure = IOException("Owned preference acquisition failure")
        var acquisitions = 0
        val store = PreferencesIdentityImportRecoveryStore { acquisitions++; throw failure }

        assertEquals(0, acquisitions)
        assertSame(failure, assertThrows(IOException::class.java) { store.read() })
        assertEquals(1, acquisitions)
        assertSame(failure, assertThrows(IOException::class.java) { store.write(IdentityImportRecoveryState()) })
        assertEquals(2, acquisitions)
    }

    @Test
    fun unavailablePreferencesRequestRecoveryWithoutEscapingStartupOrStartingAnImport() {
        val failure = IOException("Owned preference acquisition failure")
        val issues = mutableListOf<IdentityImportRecoveryIssue>()
        val recovery = IdentityImportRecovery(
            store = PreferencesIdentityImportRecoveryStore { throw failure },
            persistedReadGrants = { emptySet() },
            releaseGrant = { error("No owned grants") },
            onIssue = { issues += it },
        )

        assertTrue(recovery.needsReselection)
        assertEquals(
            listOf(IdentityImportRecoveryIssue.READ_MARKER, IdentityImportRecoveryIssue.WRITE_MARKER),
            issues,
        )
        assertSame(failure, assertThrows(IOException::class.java) { recovery.begin() })
        assertEquals(IdentityImportRecoveryIssue.WRITE_MARKER, issues.last())
    }
}
