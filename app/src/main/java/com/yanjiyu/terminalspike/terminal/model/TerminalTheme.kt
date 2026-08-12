package com.yanjiyu.terminalspike.terminal.model

import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme

/** Immutable terminal palette used by Settings previews and renderer profile selection. */
data class TerminalTheme(
    val id: String,
    val displayName: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val ansi16: List<Int>,
    val boldUsesBrightColours: Boolean = true,
) {
    init {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it in "._-" }) {
            "Terminal theme ID must be a stable identifier."
        }
        require(displayName.isNotBlank()) { "Terminal theme name must not be blank." }
        require(ansi16.size == ANSI_COLOUR_COUNT) { "Terminal themes require exactly 16 ANSI colours." }
        require(
            listOf(background, foreground, cursor, selection).all(::isOpaque) && ansi16.all(::isOpaque),
        ) { "Terminal theme colours must be opaque ARGB values." }
    }

    /** Resolves the source foreground before inverse-video is applied. */
    fun resolveForeground(style: TerminalStyle): Int = resolve(
        colour = style.foreground,
        defaultColour = foreground,
        brightenForBold = style.bold,
    )

    /** Resolves the source background before inverse-video is applied. */
    fun resolveBackground(style: TerminalStyle): Int = resolve(
        colour = style.background,
        defaultColour = background,
        brightenForBold = false,
    )

    /** Foreground ARGB consumed by the Canvas renderer after inverse-video is applied. */
    fun resolveDrawForeground(style: TerminalStyle): Int =
        if (style.inverse) resolveBackground(style) else resolveForeground(style)

    /** Background ARGB consumed by the Canvas renderer after inverse-video is applied. */
    fun resolveDrawBackground(style: TerminalStyle): Int =
        if (style.inverse) resolveForeground(style) else resolveBackground(style)

    private fun resolve(
        colour: TerminalColour,
        defaultColour: Int,
        brightenForBold: Boolean,
    ): Int = when (colour) {
        TerminalColour.Default -> defaultColour
        is TerminalColour.Rgb -> colour.argb
        is TerminalColour.Indexed -> {
            val index = if (
                brightenForBold && boldUsesBrightColours && colour.index in STANDARD_ANSI_RANGE
            ) {
                colour.index + BRIGHT_ANSI_OFFSET
            } else {
                colour.index
            }
            ansi16.getOrNull(index) ?: TerminalPalette.xtermColour(index)
        }
    }

    companion object {
        const val ANSI_COLOUR_COUNT = 16
        private val STANDARD_ANSI_RANGE = 0..7
        private const val BRIGHT_ANSI_OFFSET = 8

        private fun isOpaque(colour: Int): Boolean = colour ushr 24 == 0xFF
    }
}

/**
 * Project-authored mappings from the pinned palettes recorded in docs/DEPENDENCIES.md and
 * docs/asset-licensing.md. Palette names identify the upstream colour specifications; no upstream
 * UI assets are copied. A new named preset also requires a notice and reviewed-inventory update.
 */
object TerminalThemes {
    const val CURRENT_ID = "current"
    const val HIGH_CONTRAST_ID = "high_contrast"

    private val currentAnsi16 = listOf(
        0xFF1D1F21, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
        0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD7DAE0,
        0xFF5C6370, 0xFFFF7A85, 0xFFB4E88B, 0xFFFFD68A,
        0xFF75BEFF, 0xFFD58AF0, 0xFF6DDBE5, 0xFFFFFFFF,
    ).map(Long::toInt)

    private val solarizedAnsi = listOf(
        0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
        0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
        0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
        0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
    )

    val current = TerminalTheme(
        id = CURRENT_ID,
        displayName = "Current",
        background = TerminalPalette.BACKGROUND,
        foreground = TerminalPalette.FOREGROUND,
        cursor = TerminalPalette.CURSOR,
        selection = TerminalPalette.SELECTION_MUTED,
        ansi16 = currentAnsi16,
    )

    val presets: List<TerminalTheme> = listOf(
        current,
        theme(
            id = "ayu_dark",
            name = "Ayu Dark",
            background = 0xFF0A0E14,
            foreground = 0xFFB3B1AD,
            cursor = 0xFFE6B450,
            selection = 0xFF273747,
            ansi = listOf(
                0xFF01060E, 0xFFEA6C73, 0xFF91B362, 0xFFF9AF4F,
                0xFF53BDFA, 0xFFFAE994, 0xFF90E1C6, 0xFFC7C7C7,
                0xFF686868, 0xFFF07178, 0xFFC2D94C, 0xFFFFB454,
                0xFF59C2FF, 0xFFFFEE99, 0xFF95E6CB, 0xFFFFFFFF,
            ),
        ),
        theme(
            id = "one_dark",
            name = "One Dark",
            background = 0xFF1E2127,
            foreground = 0xFFABB2BF,
            cursor = 0xFF528BFF,
            selection = 0xFF3E4451,
            ansi = listOf(
                0xFF1D1F21, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
                0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD7DAE0,
                0xFF5C6370, 0xFFFF7A85, 0xFFB4E88B, 0xFFFFD68A,
                0xFF75BEFF, 0xFFD58AF0, 0xFF6DDBE5, 0xFFFFFFFF,
            ),
        ),
        theme(
            id = "dracula",
            name = "Dracula",
            background = 0xFF282A36,
            foreground = 0xFFF8F8F2,
            cursor = 0xFFF8F8F0,
            selection = 0xFF44475A,
            ansi = listOf(
                0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
                0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
                0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
            ),
        ),
        theme(
            id = "nord",
            name = "Nord",
            background = 0xFF2E3440,
            foreground = 0xFFD8DEE9,
            cursor = 0xFFD8DEE9,
            selection = 0xFF434C5E,
            ansi = listOf(
                0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
                0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
            ),
        ),
        theme(
            id = "solarized_dark",
            name = "Solarized Dark",
            background = 0xFF002B36,
            foreground = 0xFF839496,
            cursor = 0xFF93A1A1,
            selection = 0xFF073642,
            ansi = solarizedAnsi,
        ),
        theme(
            id = "solarized_light",
            name = "Solarized Light",
            background = 0xFFFDF6E3,
            foreground = 0xFF657B83,
            cursor = 0xFF586E75,
            selection = 0xFFEEE8D5,
            ansi = solarizedAnsi,
        ),
        theme(
            id = "gruvbox_dark",
            name = "Gruvbox Dark",
            background = 0xFF282828,
            foreground = 0xFFEBDBB2,
            cursor = 0xFFEBDBB2,
            selection = 0xFF504945,
            ansi = listOf(
                0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
                0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
                0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
                0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
            ),
        ),
        theme(
            id = "tokyo_night",
            name = "Tokyo Night",
            background = 0xFF1A1B26,
            foreground = 0xFFC0CAF5,
            cursor = 0xFFC0CAF5,
            selection = 0xFF33467C,
            ansi = listOf(
                0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
                0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
            ),
        ),
        theme(
            id = "catppuccin_mocha",
            name = "Catppuccin Mocha",
            background = 0xFF1E1E2E,
            foreground = 0xFFCDD6F4,
            cursor = 0xFFF5E0DC,
            selection = 0xFF45475A,
            ansi = listOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
                0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
            ),
        ),
        theme(
            id = HIGH_CONTRAST_ID,
            name = "High Contrast",
            background = 0xFF000000,
            foreground = 0xFFFFFFFF,
            cursor = 0xFFFFFF00,
            selection = 0xFF005FCC,
            ansi = listOf(
                0xFF000000, 0xFFFF5F56, 0xFF5AF78E, 0xFFF3F99D,
                0xFF57C7FF, 0xFFFF6AC1, 0xFF9AEDFE, 0xFFFFFFFF,
                0xFF686868, 0xFFFF6E67, 0xFF5AF78E, 0xFFF4F99D,
                0xFF57C7FF, 0xFFFF6AC1, 0xFF9AEDFE, 0xFFFFFFFF,
            ),
        ),
    )

    fun find(id: String): TerminalTheme = presets.firstOrNull { it.id == id } ?: current

    fun find(id: String, customThemes: Iterable<CustomTerminalTheme>): TerminalTheme =
        customThemes.firstOrNull { it.id == id }?.toTerminalTheme() ?: find(id)

    private fun theme(
        id: String,
        name: String,
        background: Long,
        foreground: Long,
        cursor: Long,
        selection: Long,
        ansi: List<Long>,
    ) = TerminalTheme(
        id = id,
        displayName = name,
        background = background.toInt(),
        foreground = foreground.toInt(),
        cursor = cursor.toInt(),
        selection = selection.toInt(),
        ansi16 = ansi.map(Long::toInt),
    )

}

fun CustomTerminalTheme.toTerminalTheme(): TerminalTheme = TerminalTheme(
    id = id,
    displayName = name,
    background = backgroundArgb,
    foreground = foregroundArgb,
    cursor = cursorArgb,
    selection = selectionArgb,
    ansi16 = ansi16Argb.toList(),
    boldUsesBrightColours = boldUsesBrightColours,
)
