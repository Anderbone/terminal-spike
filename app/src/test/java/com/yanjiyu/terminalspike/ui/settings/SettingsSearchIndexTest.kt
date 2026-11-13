package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    private val herdr = SettingsSearchEntry(
        SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_auto_attach_herdr, "auto attach",
    )
    private val keepalive = SettingsSearchEntry(
        SettingsCategory.SESSIONS_BACKGROUND, R.string.settings_keepalive_interval, "ssh",
    )
    private val labels = mapOf(
        R.string.settings_auto_attach_herdr to listOf("Automatically enter a single herdr session", "自动进入唯一的 herdr 会话"),
        R.string.settings_keepalive_interval to listOf("SSH protocol keepalive", "SSH 协议保活"),
        R.string.settings_category_sessions_background to listOf("Sessions & Background", "会话与后台"),
    )
    private fun search(query: String) = searchSettings(query, listOf(herdr, keepalive)) { labels.getValue(it) }

    @Test
    fun searchesConcreteOptionsAcrossLanguagesAndNormalizesCaseWhitespaceAndWidth() {
        assertEquals(listOf(herdr), search("  ＨＥＲＤＲ  "))
        assertEquals(listOf(herdr), search("自动进入"))
        assertEquals(listOf(keepalive), search("SSH 保活"))
        assertEquals(listOf(keepalive), search("background keepalive"))
    }

    @Test
    fun requiresAllTermsAndDoesNotReturnOptionsForEmptyOrUnknownQueries() {
        assertTrue(search("herdr keepalive").isEmpty())
        assertTrue(search("missing").isEmpty())
        assertTrue(search("  ").isEmpty())
    }

    @Test
    fun exactOptionTitleRanksBeforeCategoryMatch() {
        val category = SettingsSearchEntry(SettingsCategory.SESSIONS_BACKGROUND,
            R.string.settings_category_sessions_background, "herdr", null)
        val result = searchSettings("herdr", listOf(category, herdr)) { labels.getValue(it) }
        assertEquals(listOf(herdr, category), result)
        assertEquals(R.string.settings_auto_attach_herdr, result.first().anchorRes)
    }
}
