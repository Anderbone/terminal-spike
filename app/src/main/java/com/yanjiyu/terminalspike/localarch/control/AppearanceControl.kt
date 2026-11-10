package com.yanjiyu.terminalspike.localarch.control

import com.yanjiyu.terminalspike.AppContainer
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalTheme
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import com.yanjiyu.terminalspike.terminal.model.toTerminalTheme
import com.yanjiyu.terminalspike.ui.settings.AccentPreset
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Shares the existing stores, profile models and rendering observers with the Settings screen. */
internal class AppearanceControl(private val container: AppContainer) {
    suspend fun read(method: String, p: JsonObject): JsonElement = when (method) {
        "appearance.get" -> { p.requireFields(); container.settings.settings.first().appearanceJson() }
        "terminal.list" -> {
            p.requireFields()
            val defaultId = container.settings.settings.first().defaultTerminalProfileId
            JsonArray(container.terminalProfiles.observeAll().first().map { it.controlJson(defaultId) })
        }
        "terminal.get" -> {
            p.requireFields("id")
            terminal(p.string("id")).controlJson(container.settings.settings.first().defaultTerminalProfileId)
        }
        "themes.list" -> {
            p.requireFields()
            JsonArray(TerminalThemes.presets.map { it.controlJson(null) } +
                container.customTerminalThemes.observeAll().first().map { it.toTerminalTheme().controlJson(it.updatedAtEpochMillis) })
        }
        "themes.get" -> {
            p.requireFields("id")
            val id = p.string("id")
            val custom = container.customTerminalThemes.get(id)
            (custom?.toTerminalTheme() ?: theme(id)).controlJson(custom?.updatedAtEpochMillis)
        }
        "fonts.list" -> {
            p.requireFields()
            JsonArray(TerminalRendererProfile.BUNDLED_FONT_IDS.map { id -> buildJsonObject {
                put("id", id); put("name", id.replace('_', ' ')); put("source", if (id == "system_monospace") "system" else "bundled")
            } } + container.customTerminalFonts.list().map { font -> buildJsonObject {
                put("id", font.id); put("name", font.displayName); put("source", "imported")
            } })
        }
        else -> error("Unsupported appearance read")
    }

    /** DataStore is updated in its own atomic transform, outside a Room transaction. */
    suspend fun updateApp(p: JsonObject, requireAccess: () -> Unit): JsonObject {
        val saved = container.settings.update { builder ->
            requireAccess()
            val current = builder.build()
            if (current.appLockMode != AppSettings.AppLockMode.APP_LOCK_MODE_OFF) {
                throw ControlFailure("APP_LOCK_ENABLED", "Agent settings access is unavailable while App lock is enabled.")
            }
            val patched = patchControlAppAppearance(current, p)
            builder.setThemeMode(patched.themeMode)
                .setDynamicColorEnabled(patched.dynamicColorEnabled)
                .setAccentPreset(patched.accentPreset)
                .setInputButtonsOnRight(patched.inputButtonsOnRight)
                .setVoiceInputDisabled(patched.voiceInputDisabled)
                .clearButtonColors().putAllButtonColors(patched.buttonColorsMap)
        }
        return saved.appearanceJson()
    }

    /** Caller owns the authority gate and one Room writer transaction. */
    suspend fun write(method: String, p: JsonObject): JsonElement = when (method) {
        "terminal.update" -> {
            val current = terminal(p.string("id"))
            val themeIds = TerminalThemes.presets.map { it.id }.toSet() +
                container.customTerminalThemes.observeAll().first().map { it.id }
            val fontIds = TerminalRendererProfile.BUNDLED_FONT_IDS + container.customTerminalFonts.list().map { it.id }
            val edited = patchControlTerminal(current, p, themeIds, fontIds)
            check(container.terminalProfiles.update(edited))
            edited.controlJson(container.settings.settings.first().defaultTerminalProfileId)
        }
        "themes.add" -> {
            p.requireFields("name", "base", *COLOUR_FIELDS)
            val base = theme(p["base"]?.let { p.string("base") } ?: TerminalThemes.CURRENT_ID)
            val now = System.currentTimeMillis()
            val added = controlThemeRecord(UUID.randomUUID().toString(), p.string("name"), base, p, now, now)
            container.customTerminalThemes.insert(added)
            added.toTerminalTheme().controlJson(added.updatedAtEpochMillis)
        }
        "themes.update" -> {
            p.requireFields("id", "revision", "name", *COLOUR_FIELDS)
            val id = p.string("id")
            if (TerminalThemes.presets.any { it.id == id }) {
                throw ControlFailure("BUILTIN_THEME", "Built-in themes are read-only. Add a custom theme using base instead.")
            }
            val current = container.customTerminalThemes.get(id) ?: missing()
            p.requireRevision(current.updatedAtEpochMillis)
            require(p.keys.any { it !in setOf("id", "revision") })
            val edited = controlThemeRecord(id, p["name"]?.let { p.string("name") } ?: current.name,
                current.toTerminalTheme(), p, current.createdAtEpochMillis, nextControlRevision(current.updatedAtEpochMillis))
            check(container.customTerminalThemes.update(edited))
            edited.toTerminalTheme().controlJson(edited.updatedAtEpochMillis)
        }
        else -> error("Unsupported appearance mutation")
    }

    private suspend fun terminal(id: String): TerminalProfile = container.terminalProfiles.get(id) ?: missing()
    private suspend fun theme(id: String): TerminalTheme = TerminalThemes.presets.firstOrNull { it.id == id }
        ?: container.customTerminalThemes.get(id)?.toTerminalTheme() ?: missing()
    private fun missing(): Nothing = throw ControlFailure("NOT_FOUND", "The requested profile or theme no longer exists.")

    companion object {
        val READ_METHODS = setOf("appearance.get", "terminal.list", "terminal.get", "themes.list", "themes.get", "fonts.list")
        val WRITE_METHODS = setOf("appearance.update", "terminal.update", "themes.add", "themes.update")
        private val COLOUR_FIELDS = arrayOf("foreground", "background", "cursor", "selection", "ansi16", "boldUsesBrightColours")
    }
}

/** A safe-integer token of just the editable appearance fields; unrelated settings do not conflict. */
internal fun appearanceRevision(settings: AppSettings): Long {
    val bytes = "${settings.buttonColorsMap.toSortedMap()}\u0000${settings.themeModeValue}\u0000${settings.dynamicColorEnabled}\u0000${settings.accentPreset}\u0000${settings.inputButtonsOnRight}\u0000${settings.hasVoiceInputDisabled() && !settings.voiceInputDisabled}".toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).take(6).fold(0L) { value, byte ->
        (value shl 8) or (byte.toLong() and 0xff)
    }
}

internal fun patchControlAppAppearance(current: AppSettings, p: JsonObject): AppSettings {
    p.requireFields("revision", "themeMode", "dynamicColorEnabled", "accentPreset", "inputButtonsOnRight", "voiceInputEnabled", "buttonColors")
    p.requireRevision(appearanceRevision(current))
    require(p.keys.any { it != "revision" })
    return current.toBuilder().also { builder ->
        p["buttonColors"]?.let { value ->
            require(value is JsonObject && value.size <= 256)
            val colors = value.mapValues { (id, color) ->
                require(id.length in 1..100 && id.matches(Regex("[a-zA-Z0-9_:.-]+")))
                require(color is JsonPrimitive && color.isString)
                controlColour(color.content)
                color.content.uppercase()
            }
            builder.clearButtonColors().putAllButtonColors(colors)
        }
        p["themeMode"]?.let { builder.setThemeMode(when (p.string("themeMode")) {
            "system" -> AppSettings.ThemeMode.THEME_MODE_SYSTEM
            "light" -> AppSettings.ThemeMode.THEME_MODE_LIGHT
            "dark" -> AppSettings.ThemeMode.THEME_MODE_DARK
            else -> throw IllegalArgumentException("Unsupported app theme mode.")
        }) }
        p["inputButtonsOnRight"]?.let { builder.setInputButtonsOnRight(p.boolean("inputButtonsOnRight")) }
        p["voiceInputEnabled"]?.let { builder.setVoiceInputDisabled(!p.boolean("voiceInputEnabled")) }
        p["dynamicColorEnabled"]?.let { builder.setDynamicColorEnabled(p.boolean("dynamicColorEnabled")) }
        p["accentPreset"]?.let {
            val accent = p.string("accentPreset")
            require(AccentPreset.entries.any { it.wireCode == accent })
            builder.setAccentPreset(accent)
        }
    }.build()
}

internal fun patchControlTerminal(current: TerminalProfile, p: JsonObject, themeIds: Set<String>, fontIds: Set<String>): TerminalProfile {
    p.requireFields("id", "revision", "themeId", "fontId", "fontSizeSp", "lineHeightMultiplier", "letterSpacingEm",
        "boldRenderingEnabled", "ligaturesEnabled", "pinchZoomEnabled", "cursorStyle", "cursorBlinkEnabled")
    p.requireRevision(current.updatedAtEpochMillis)
    require(p.keys.any { it !in setOf("id", "revision") })
    val themeId = p["themeId"]?.let { p.string("themeId").also { require(it in themeIds) } } ?: current.themeId
    val fontId = p["fontId"]?.let { p.string("fontId").also { require(it in fontIds) } } ?: current.fontId
    return current.copy(
        themeId = themeId, fontId = fontId,
        fontSizeSp = p["fontSizeSp"]?.let { p.float("fontSizeSp") } ?: current.fontSizeSp,
        lineHeightMultiplier = p["lineHeightMultiplier"]?.let { p.float("lineHeightMultiplier") } ?: current.lineHeightMultiplier,
        letterSpacingEm = p["letterSpacingEm"]?.let { p.float("letterSpacingEm") } ?: current.letterSpacingEm,
        boldRenderingEnabled = p["boldRenderingEnabled"]?.let { p.boolean("boldRenderingEnabled") } ?: current.boldRenderingEnabled,
        ligaturesEnabled = p["ligaturesEnabled"]?.let { p.boolean("ligaturesEnabled") } ?: current.ligaturesEnabled,
        pinchZoomEnabled = p["pinchZoomEnabled"]?.let { p.boolean("pinchZoomEnabled") } ?: current.pinchZoomEnabled,
        cursorStyle = p["cursorStyle"]?.let { CursorStyle.fromWireCode(p.string("cursorStyle")) } ?: current.cursorStyle,
        cursorBlinkEnabled = p["cursorBlinkEnabled"]?.let { p.boolean("cursorBlinkEnabled") } ?: current.cursorBlinkEnabled,
        updatedAtEpochMillis = nextControlRevision(current.updatedAtEpochMillis),
    )
}

internal fun controlThemeRecord(id: String, name: String, base: TerminalTheme, p: JsonObject, created: Long, updated: Long): CustomTerminalTheme =
    CustomTerminalTheme(
        id = id, name = name,
        foregroundArgb = p["foreground"]?.let { controlColour(p.string("foreground")) } ?: base.foreground,
        backgroundArgb = p["background"]?.let { controlColour(p.string("background")) } ?: base.background,
        cursorArgb = p["cursor"]?.let { controlColour(p.string("cursor")) } ?: base.cursor,
        selectionArgb = p["selection"]?.let { controlColour(p.string("selection")) } ?: base.selection,
        ansi16Argb = p["ansi16"]?.let { value ->
            require(value is JsonArray)
            value.map { colour ->
                require(colour is JsonPrimitive && colour.isString)
                controlColour(colour.content)
            }
        } ?: base.ansi16.toList(),
        boldUsesBrightColours = p["boldUsesBrightColours"]?.let { p.boolean("boldUsesBrightColours") } ?: base.boldUsesBrightColours,
        createdAtEpochMillis = created, updatedAtEpochMillis = updated,
    )

internal fun controlColour(value: String): Int {
    require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "Use opaque #RRGGBB colours." }
    return value.substring(1).toInt(16) or 0xff000000.toInt()
}

private fun colourHex(value: Int): String = "#" + (value and 0xffffff).toString(16).padStart(6, '0').uppercase()

private fun TerminalTheme.controlJson(revision: Long?): JsonObject = buildJsonObject {
    put("id", id); put("name", displayName); put("editable", revision != null)
    put("revision", revision?.let(::JsonPrimitive) ?: JsonNull)
    put("foreground", colourHex(foreground)); put("background", colourHex(background))
    put("cursor", colourHex(cursor)); put("selection", colourHex(selection))
    put("ansi16", JsonArray(ansi16.map { JsonPrimitive(colourHex(it)) }))
    put("boldUsesBrightColours", boldUsesBrightColours)
}

private fun TerminalProfile.controlJson(defaultId: String): JsonObject = buildJsonObject {
    put("id", id); put("revision", updatedAtEpochMillis); put("name", name); put("isDefault", id == defaultId)
    put("themeId", themeId); put("fontId", fontId); put("fontSizeSp", fontSizeSp)
    put("lineHeightMultiplier", lineHeightMultiplier); put("letterSpacingEm", letterSpacingEm)
    put("boldRenderingEnabled", boldRenderingEnabled); put("ligaturesEnabled", ligaturesEnabled)
    put("pinchZoomEnabled", pinchZoomEnabled); put("cursorStyle", cursorStyle.wireCode); put("cursorBlinkEnabled", cursorBlinkEnabled)
    put("effect", "profile_updated")
}

private fun AppSettings.appearanceJson(): JsonObject = buildJsonObject {
    put("revision", appearanceRevision(this@appearanceJson))
    put("themeMode", when (themeMode) {
        AppSettings.ThemeMode.THEME_MODE_LIGHT -> "light"
        AppSettings.ThemeMode.THEME_MODE_DARK -> "dark"
        else -> "system"
    })
    put("buttonColors", buildJsonObject { buttonColorsMap.forEach { (id, color) -> put(id, color) } })
    put("inputButtonsOnRight", inputButtonsOnRight)
    put("voiceInputEnabled", hasVoiceInputDisabled() && !voiceInputDisabled)
    put("dynamicColorEnabled", dynamicColorEnabled); put("accentPreset", accentPreset.ifEmpty { "mint" })
    put("effect", "app_theme_updated")
}
