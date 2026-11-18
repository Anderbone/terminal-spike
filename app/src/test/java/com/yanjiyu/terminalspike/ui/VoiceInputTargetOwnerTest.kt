package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTargetOwnerTest {
    @Test
    fun permissionKeepsTheOriginalTargetAndLanguageAndIsConsumedOnce() {
        val owner = VoiceInputTargetOwner()
        val request = VoiceInputRequest(10, "zh-CN")
        assertTrue(owner.requestPermission(request))
        assertFalse(owner.requestPermission(VoiceInputRequest(20, "en")))
        assertEquals(request, owner.takePermissionRequest(10, enabled = true))
        assertNull(owner.takePermissionRequest(10, enabled = true))
    }

    @Test
    fun changingTargetsDiscardsThePendingPermissionResult() {
        val owner = VoiceInputTargetOwner()
        owner.requestPermission(VoiceInputRequest(10, "en"))
        assertNull(owner.takePermissionRequest(20, enabled = true))
        assertNull(owner.takePermissionRequest(10, enabled = true))
    }

    @Test
    fun disablingVoiceDiscardsThePendingPermissionResult() {
        val owner = VoiceInputTargetOwner()
        owner.requestPermission(VoiceInputRequest(10, "en"))
        assertNull(owner.takePermissionRequest(10, enabled = false))
        assertNull(owner.takePermissionRequest(10, enabled = true))
    }

    @Test
    fun aRecreatedOwnerCannotAutomaticallyStartFromAnOldPermissionResult() {
        val old = VoiceInputTargetOwner()
        old.requestPermission(VoiceInputRequest(10, "en"))
        assertNull(VoiceInputTargetOwner().takePermissionRequest(10, enabled = true))
    }

    @Test
    fun callbacksAreAcceptedOnlyForTheActiveEnabledTarget() {
        val owner = VoiceInputTargetOwner()
        assertFalse(owner.ownsCallbacks(10, enabled = true))
        owner.activate(VoiceInputRequest(10, "en"))
        assertTrue(owner.ownsCallbacks(10, enabled = true))
        assertFalse(owner.ownsCallbacks(20, enabled = true))
        assertFalse(owner.ownsCallbacks(10, enabled = false))
    }

    @Test
    fun invalidationDropsBothPermissionAndActiveCallbackOwnership() {
        val owner = VoiceInputTargetOwner()
        owner.requestPermission(VoiceInputRequest(10, "en"))
        owner.activate(VoiceInputRequest(10, "en"))
        owner.invalidate()
        assertNull(owner.takePermissionRequest(10, enabled = true))
        assertFalse(owner.ownsCallbacks(10, enabled = true))
        assertTrue(owner.requestPermission(VoiceInputRequest(20, "zh")))
    }
}
