package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityPickerRequestOwnerTest {
    @Test
    fun aNewImportStillHasAnOwnedRequestWhenItsRecoveryTokenIsNull() {
        val owner = IdentityPickerRequestOwner()
        assertTrue(owner.begin(null))
        assertEquals(IdentityPickerRequest(null), owner.take())
        assertNull(owner.take())
    }

    @Test
    fun anotherLaunchCannotReplaceTheFirstRecoveryTarget() {
        val owner = IdentityPickerRequestOwner()
        assertTrue(owner.begin("owned:first"))
        assertFalse(owner.begin("owned:second"))
        assertEquals(IdentityPickerRequest("owned:first"), owner.take())
    }

    @Test
    fun recreatingTheProcessDoesNotOwnAnOldPickerResult() {
        val oldProcess = IdentityPickerRequestOwner()
        assertTrue(oldProcess.begin("owned:old-target"))
        val newProcess = IdentityPickerRequestOwner()
        assertNull(newProcess.take())
        assertTrue(newProcess.begin("owned:new-target"))
        assertEquals(IdentityPickerRequest("owned:new-target"), newProcess.take())
    }

    @Test
    fun aFailedLaunchCanBeCancelledAndRetried() {
        val owner = IdentityPickerRequestOwner()
        assertTrue(owner.begin("owned:first"))
        owner.cancel()
        assertNull(owner.take())
        assertTrue(owner.begin("owned:retry"))
        assertEquals(IdentityPickerRequest("owned:retry"), owner.take())
    }
}
