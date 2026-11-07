package com.yanjiyu.terminalspike.ui.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickStartStateTest {
    @Test
    fun onlyAnUnacknowledgedFreshInstallStartsTheTourAutomatically() {
        assertTrue(shouldIntroduceQuickStart(false, 100L, 100L))
        assertFalse(shouldIntroduceQuickStart(true, 100L, 100L))
        assertFalse(shouldIntroduceQuickStart(false, 100L, 200L))
        assertFalse(shouldIntroduceQuickStart(true, 100L, 200L))
    }
}
