package com.yanjiyu.terminalspike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAppDataResetterTest {
    @Test
    fun acceptedPlatformResetIsReported() {
        var requests = 0
        val resetter = AndroidLocalAppDataResetter.forTest {
            requests += 1
            true
        }

        assertTrue(resetter.requestReset())
        assertTrue(requests == 1)
    }

    @Test
    fun rejectedPlatformResetIsReportedWithoutRetrying() {
        var requests = 0
        val resetter = AndroidLocalAppDataResetter.forTest {
            requests += 1
            false
        }

        assertFalse(resetter.requestReset())
        assertTrue(requests == 1)
    }
}
