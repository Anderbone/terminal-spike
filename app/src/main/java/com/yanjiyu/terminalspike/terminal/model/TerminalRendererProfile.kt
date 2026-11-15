package com.yanjiyu.terminalspike.terminal.model

import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.TouchScrollMode

/** Immutable settings snapshot consumed by the native renderer without Compose cell state. */
data class TerminalRendererProfile(
    val theme: TerminalTheme = TerminalThemes.current,
    val fontId: String = DEFAULT_FONT_ID,
    /** Absolute path is accepted only after the private custom-font store resolves the stable ID. */
    val customFontPath: String? = null,
    val fontSizeSp: Float = 14f,
    val lineHeightMultiplier: Float = 1f,
    val letterSpacingEm: Float = 0f,
    val boldRenderingEnabled: Boolean = true,
    val ligaturesEnabled: Boolean = false,
    val pinchZoomEnabled: Boolean = true,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlinkEnabled: Boolean = true,
    val touchScrollMode: TouchScrollMode = TouchScrollMode.AUTO,
    val twoFingerLocalScrollOverride: Boolean = true,
    val jumpToBottomOnKeyboardInput: Boolean = true,
    val keepViewportPositionOnOutput: Boolean = true,
    val preserveAlternateScreenHistory: Boolean = true,
    val detectPlainTextUrls: Boolean = true,
    val osc8HyperlinksEnabled: Boolean = true,
    val copyOnSelection: Boolean = false,
) {
    init {
        require(fontId.isNotBlank())
        require(fontSizeSp.isFinite() && fontSizeSp in 8f..72f)
        require(lineHeightMultiplier.isFinite() && lineHeightMultiplier in 0.8f..3f)
        require(letterSpacingEm.isFinite() && letterSpacingEm in -0.5f..2f)
        require(customFontPath == null || fontId != SYSTEM_MONOSPACE_FONT_ID)
    }

    companion object {
        const val SYSTEM_MONOSPACE_FONT_ID = "system_monospace"
        const val SOURCE_CODE_PRO_FONT_ID = "source_code_pro"
        const val JETBRAINS_MONO_FONT_ID = "jetbrains_mono"
        const val IBM_PLEX_MONO_FONT_ID = "ibm_plex_mono"
        const val CASCADIA_MONO_FONT_ID = "cascadia_mono"
        const val DEFAULT_FONT_ID = JETBRAINS_MONO_FONT_ID

        val BUNDLED_FONT_IDS: Set<String> = setOf(
            SYSTEM_MONOSPACE_FONT_ID,
            SOURCE_CODE_PRO_FONT_ID,
            JETBRAINS_MONO_FONT_ID,
            IBM_PLEX_MONO_FONT_ID,
            CASCADIA_MONO_FONT_ID,
        )

        fun isBundledFontId(fontId: String): Boolean = fontId in BUNDLED_FONT_IDS
    }
}
