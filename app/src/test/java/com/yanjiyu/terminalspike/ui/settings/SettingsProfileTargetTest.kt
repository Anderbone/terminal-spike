package com.yanjiyu.terminalspike.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsProfileTargetTest {
    @Test
    fun explicitSessionOverrideWinsOverTheAppDefault() {
        val profiles = listOf("default", "session", "other")

        assertEquals(
            "session",
            resolveSettingsProfile(
                profiles = profiles,
                requestedId = "session",
                defaultId = "default",
                idOf = { it },
            ),
        )
    }

    @Test
    fun missingOverrideFallsBackWithoutLosingSettingsAccess() {
        val profiles = listOf("default", "other")

        assertEquals(
            "default",
            resolveSettingsProfile(
                profiles = profiles,
                requestedId = "deleted",
                defaultId = "default",
                idOf = { it },
            ),
        )
    }
}
