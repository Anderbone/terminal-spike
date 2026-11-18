package com.yanjiyu.terminalspike.core.security.credential

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityImportRecoveryTest {
    @Test
    fun aCleanStartDoesNotWriteOrRequestReselection() {
        val fixture = Fixture()
        assertFalse(fixture.reconstruct().needsReselection)
        assertEquals(0, fixture.store.writes)
        assertTrue(fixture.released.isEmpty())
    }

    @Test
    fun processDeathReleasesAllOldGrantsAndPersistsReselection() {
        val fixture = Fixture()
        fixture.reconstruct().begin() // The old process dies without finishing its lease.
        fixture.grants += listOf("owned:first", "owned:second")

        val recovered = fixture.reconstruct()

        assertTrue(recovered.needsReselection)
        assertEquals(setOf("owned:first", "owned:second"), fixture.released.toSet())
        assertEquals(IdentityImportRecoveryState(needsReselection = true), fixture.store.value)
        assertTrue(fixture.reconstruct().needsReselection)
    }

    @Test
    fun aLegacyGrantWithoutAMarkerStillRequiresReselection() {
        val fixture = Fixture()
        fixture.grants += "owned:legacy"
        assertTrue(fixture.reconstruct().needsReselection)
        assertTrue(fixture.grants.isEmpty())
    }

    @Test
    fun interruptedImportsWithoutPersistableGrantsStillRequireReselection() {
        val fixture = Fixture()
        fixture.reconstruct().begin()
        assertTrue(fixture.reconstruct().needsReselection)
        assertTrue(fixture.released.isEmpty())
    }

    @Test
    fun onlyTheLastLiveImportReleasesTheSharedGrants() {
        val fixture = Fixture()
        val recovery = fixture.reconstruct()
        val first = recovery.begin()
        val second = recovery.begin()
        fixture.grants += listOf("owned:first", "owned:second")

        first.close()
        first.close()
        assertTrue(fixture.released.isEmpty())
        assertTrue(fixture.store.value.incomplete)
        second.close()

        assertEquals(setOf("owned:first", "owned:second"), fixture.released.toSet())
        assertEquals(IdentityImportRecoveryState(), fixture.store.value)
        assertFalse(fixture.reconstruct().needsReselection)
    }

    @Test
    fun aFailedBeginCannotStartAnImportWithoutADurableMarker() {
        val fixture = Fixture()
        val recovery = fixture.reconstruct()
        val failure = IOException("Owned marker write failure")
        fixture.store.writeFailure = failure

        assertSame(failure, assertThrows(IOException::class.java) { recovery.begin() })
        assertEquals(IdentityImportRecoveryState(), fixture.store.value)
        assertEquals(listOf(IdentityImportRecoveryIssue.WRITE_MARKER), fixture.issues)
    }

    @Test
    fun oneReleaseFailureDoesNotSkipTheOtherGrantsAndCanBeRetriedNextProcess() {
        val fixture = Fixture()
        fixture.grants += listOf("owned:first", "owned:second")
        fixture.failingGrant = "owned:first"

        assertTrue(fixture.reconstruct().needsReselection)
        assertEquals(setOf("owned:first", "owned:second"), fixture.released.toSet())
        assertEquals(setOf("owned:first"), fixture.grants)
        assertTrue(fixture.store.value.incomplete)

        fixture.failingGrant = null
        fixture.reconstruct()
        assertTrue(fixture.grants.isEmpty())
        assertFalse(fixture.store.value.incomplete)
    }

    @Test
    fun acknowledgementDoesNotEraseAnActiveImportMarker() {
        val fixture = Fixture()
        fixture.store.value = IdentityImportRecoveryState(needsReselection = true)
        val recovery = fixture.reconstruct()
        val lease = recovery.begin()

        recovery.acknowledgeReselection()

        assertEquals(IdentityImportRecoveryState(incomplete = true), fixture.store.value)
        lease.close()
        assertFalse(fixture.reconstruct().needsReselection)
    }

    @Test
    fun anUndispatchedRestoredPickerRequestNeedsExplicitReselection() {
        val fixture = Fixture()
        val recovery = fixture.reconstruct()
        recovery.requireReselection()
        assertTrue(fixture.reconstruct().needsReselection)
        recovery.acknowledgeReselection()
        assertFalse(fixture.reconstruct().needsReselection)
    }

    @Test
    fun grantEnumerationFailureRetainsARetryMarkerWithoutCrashingStartup() {
        val fixture = Fixture()
        fixture.failEnumeration = true
        assertTrue(fixture.reconstruct().needsReselection)
        assertTrue(fixture.store.value.incomplete)
        fixture.failEnumeration = false
        fixture.reconstruct()
        assertFalse(fixture.store.value.incomplete)
    }

    @Test
    fun cleanupWriteFailureDoesNotReplaceTheImportOutcome() {
        val fixture = Fixture()
        val recovery = fixture.reconstruct()
        val lease = recovery.begin()
        fixture.store.writeFailure = IOException("Owned cleanup write failure")

        lease.close()

        assertTrue(fixture.store.value.incomplete)
        assertEquals(listOf(IdentityImportRecoveryIssue.WRITE_MARKER), fixture.issues)
    }

    @Test
    fun markerReadFailureRequestsReselectionAndAttemptsGrantCleanup() {
        val fixture = Fixture()
        fixture.store.readFailure = IOException("Owned marker read failure")
        fixture.grants += "owned:first"

        assertTrue(fixture.reconstruct().needsReselection)
        assertTrue(fixture.grants.isEmpty())
        assertEquals(IdentityImportRecoveryState(needsReselection = true), fixture.store.value)
        assertEquals(listOf(IdentityImportRecoveryIssue.READ_MARKER), fixture.issues)
    }

    @Test
    fun failedAcknowledgementWriteRepeatsThePromptAfterRestart() {
        val fixture = Fixture()
        fixture.store.value = IdentityImportRecoveryState(needsReselection = true)
        val recovery = fixture.reconstruct()
        fixture.store.writeFailure = IOException("Owned acknowledgement write failure")

        recovery.acknowledgeReselection()

        assertFalse(recovery.needsReselection)
        fixture.store.writeFailure = null
        assertTrue(fixture.reconstruct().needsReselection)
    }

    @Test
    fun theGrantSnapshotSurvivesProviderCollectionMutationDuringRelease() {
        val grants = linkedSetOf("owned:first", "owned:second")
        val store = Store()
        val recovery = IdentityImportRecovery(store, { grants }, { grants.remove(it) })
        assertTrue(grants.isEmpty())
        assertTrue(recovery.needsReselection)
        assertFalse(store.value.incomplete)
    }

    private class Fixture {
        val store = Store()
        val grants = linkedSetOf<String>()
        val released = mutableListOf<String>()
        val issues = mutableListOf<IdentityImportRecoveryIssue>()
        var failingGrant: String? = null
        var failEnumeration = false

        fun reconstruct(): IdentityImportRecovery = IdentityImportRecovery(
            store,
            persistedReadGrants = {
                if (failEnumeration) throw IOException("Owned enumeration failure")
                grants.toSet()
            },
            releaseGrant = { uri ->
                released += uri
                if (uri == failingGrant) throw IOException("Owned release failure")
                grants -= uri
            },
            onIssue = { issues += it },
        )
    }

    private class Store : IdentityImportRecoveryStore {
        var value = IdentityImportRecoveryState()
        var writes = 0
        var writeFailure: Exception? = null
        var readFailure: Exception? = null

        override fun read(): IdentityImportRecoveryState {
            readFailure?.let { throw it }
            return value
        }

        override fun write(state: IdentityImportRecoveryState) {
            writeFailure?.let { throw it }
            value = state
            writes += 1
        }
    }
}
