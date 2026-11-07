package com.yanjiyu.terminalspike.localarch.control

import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.*
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppearanceControlTest {
    @Test fun fontAndThemeEditsPreserveTerminalBehaviorAndRequireAvailableReferences() {
        val current = profile()
        val edited = patchControlTerminal(current, json("""{"revision":20,"themeId":"nord","fontId":"source_code_pro","fontSizeSp":17.5,"lineHeightMultiplier":1.2,"letterSpacingEm":-0.1}"""), themes, fonts)
        assertEquals(17.5f, edited.fontSizeSp)
        assertEquals(-0.1f, edited.letterSpacingEm)
        assertEquals("nord", edited.themeId)
        assertEquals("source_code_pro", edited.fontId)
        assertEquals(current.scroll, edited.scroll)
        assertEquals(current.links, edited.links)
        assertEquals(current.bell, edited.bell)
        assertEquals(current.scrollbackLines, edited.scrollbackLines)
        assertEquals(current.termValue, edited.termValue)
        assertEquals(current.createdAtEpochMillis, edited.createdAtEpochMillis)
        assertTrue(edited.updatedAtEpochMillis > current.updatedAtEpochMillis)
    }

    @Test fun invalidTypographyAndUnknownOrUnsafeFieldsFailBeforeSaving() {
        listOf(
            """{"revision":20,"fontId":"/data/private/font.ttf"}""",
            """{"revision":20,"themeId":"missing"}""", """{"revision":20,"fontSizeSp":7}""",
            """{"revision":20,"fontSizeSp":73}""", """{"revision":20,"fontSizeSp":"17"}""",
            """{"revision":20,"lineHeightMultiplier":0.7}""", """{"revision":20,"letterSpacingEm":3}""",
            """{"revision":20,"cursorStyle":"invalid"}""", """{"revision":20,"cursorBlinkEnabled":"false"}""",
            """{"revision":20,"scrollbackLines":0}""", """{"revision":20}""",
        ).forEach { p -> assertThrows(IllegalArgumentException::class.java) { patchControlTerminal(profile(), json(p), themes, fonts) } }
        assertEquals("CONFLICT", assertThrows(ControlFailure::class.java) {
            patchControlTerminal(profile(), json("""{"revision":19,"fontSizeSp":18}"""), themes, fonts)
        }.code)
    }

    @Test fun customColoursCloneTheBaseWithoutChangingItsAnsiPalette() {
        val base = TerminalThemes.find("nord")
        val theme = controlThemeRecord(ID, "My Nord", base, json("""{"background":"#000000","cursor":"#aabbcc"}"""), 10, 20)
        assertEquals(0xff000000.toInt(), theme.backgroundArgb)
        assertEquals(0xffaabbcc.toInt(), theme.cursorArgb)
        assertEquals(base.foreground, theme.foregroundArgb)
        assertEquals(base.selection, theme.selectionArgb)
        assertEquals(base.ansi16, theme.ansi16Argb)
        assertEquals("#2E3440".substring(1).toInt(16) or 0xff000000.toInt(), base.background)
        listOf("#80ffffff", "#fff", "transparent", "#zzzzzz").forEach {
            assertThrows(IllegalArgumentException::class.java) { controlColour(it) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            controlThemeRecord(ID, "Bad", base, json("""{"ansi16":["#000000"]}"""), 10, 20)
        }
    }

    @Test fun appAppearanceUsesOnlyItsOwnRevisionAndPreservesSecurityAndOtherPreferences() {
        val current = AppSettings.newBuilder().setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
            .setDynamicColorEnabled(true).setAccentPreset("mint")
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE).setScreenshotBlockingEnabled(true)
            .setKeepaliveIntervalSeconds(60).setSensitiveClipboardClearSeconds(30).build()
        val p = buildJsonObject { put("revision", appearanceRevision(current)); put("themeMode", "dark"); put("accentPreset", "blue"); put("dynamicColorEnabled", false) }
        val edited = patchControlAppAppearance(current, p)
        assertEquals(AppSettings.ThemeMode.THEME_MODE_DARK, edited.themeMode)
        assertEquals("blue", edited.accentPreset)
        assertFalse(edited.dynamicColorEnabled)
        assertEquals(current.appLockMode, edited.appLockMode)
        assertEquals(current.screenshotBlockingEnabled, edited.screenshotBlockingEnabled)
        assertEquals(current.keepaliveIntervalSeconds, edited.keepaliveIntervalSeconds)
        assertEquals(current.sensitiveClipboardClearSeconds, edited.sensitiveClipboardClearSeconds)
        assertTrue(appearanceRevision(current) <= 9_007_199_254_740_991L)
        assertNotEquals(appearanceRevision(current), appearanceRevision(edited))
        assertEquals(appearanceRevision(current), appearanceRevision(current.toBuilder().setKeepaliveIntervalSeconds(90).build()))
        assertEquals("CONFLICT", assertThrows(ControlFailure::class.java) { patchControlAppAppearance(edited, p) }.code)
        listOf("""{"themeMode":"invalid"}""", """{"accentPreset":"missing"}""", """{"appLockMode":"off"}""", """{"dynamicColorEnabled":"true"}""").forEach { fields ->
            val invalid = buildJsonObject { put("revision", appearanceRevision(current)); Json.parseToJsonElement(fields).jsonObject.forEach { (k, v) -> put(k, v) } }
            assertThrows(IllegalArgumentException::class.java) { patchControlAppAppearance(current, invalid) }
        }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun profile() = TerminalProfile(
        id = ID, name = "Fixture", themeId = "current", fontId = "jetbrains_mono", fontSizeSp = 14f,
        lineHeightMultiplier = 1f, letterSpacingEm = 0f, cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 789, termValue = "screen-256color", scroll = ScrollBehavior(jumpToBottomOnKeyboardInput = false),
        createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
    )

    private val themes = setOf("current", "nord")
    private val fonts = setOf("jetbrains_mono", "source_code_pro")
    private companion object { const val ID = "00000000-0000-0000-0000-000000000001" }
}
