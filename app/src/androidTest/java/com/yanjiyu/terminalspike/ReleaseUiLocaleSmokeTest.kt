package com.yanjiyu.terminalspike

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseUiLocaleSmokeTest {
    @Test
    fun nonDefaultLocaleResolvesReleaseNavigationAndFormattedSummary() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }
        val localized = base.createConfigurationContext(configuration).resources

        assertEquals("Workspace", localized.getString(R.string.navigation_workspace))
        assertEquals("Connections", localized.getString(R.string.connections_title))
        assertEquals(
            "Keepalive 30s · reconnect On · CPU awake Off",
            localized.getString(
                R.string.settings_category_sessions_background_live_summary,
                30,
                localized.getString(R.string.settings_summary_enabled),
                localized.getString(R.string.settings_summary_disabled),
            ),
        )
        assertEquals(
            "Settings categories",
            localized.getString(R.string.settings_category_list_description),
        )
    }
}
