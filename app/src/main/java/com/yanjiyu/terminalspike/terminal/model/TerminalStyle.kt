package com.yanjiyu.terminalspike.terminal.model

/**
 * A terminal colour together with the VT source that produced it.
 *
 * Keeping the source in the cell model is important: an indexed colour is resolved through the
 * active terminal theme, while an RGB colour is always literal even when its value happens to be
 * identical to one of that theme's ANSI entries.
 */
sealed interface TerminalColour {
    data object Default : TerminalColour

    data class Indexed(val index: Int) : TerminalColour {
        init {
            require(index in 0..255) { "Indexed terminal colours must be in the xterm 0–255 range." }
        }
    }

    data class Rgb(val argb: Int) : TerminalColour {
        init {
            require(argb ushr 24 == 0xFF) { "True-colour terminal values must be opaque ARGB." }
        }
    }
}

data class TerminalStyle(
    val foreground: TerminalColour = TerminalColour.Default,
    val background: TerminalColour = TerminalColour.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val conceal: Boolean = false,
    val strikethrough: Boolean = false,
)

object TerminalPalette {
    const val BACKGROUND: Int = -0xf7f3f0
    const val FOREGROUND: Int = -0x18202a
    const val BLACK: Int = -0xd5d1ce
    const val RED: Int = -0x32b8d
    const val GREEN: Int = -0x7f55a8
    const val YELLOW: Int = -0x4480
    const val BLUE: Int = -0xa94b01
    const val MAGENTA: Int = -0x5b2bb7
    const val CYAN: Int = -0xb75742
    const val WHITE: Int = -0x1
    const val CURSOR: Int = -0x102824
    const val SELECTION_MUTED: Int = -0xd5c2a2

    private val ANSI_16 = intArrayOf(
        0xFF1D1F21.toInt(),
        0xFFE06C75.toInt(),
        0xFF98C379.toInt(),
        0xFFE5C07B.toInt(),
        0xFF61AFEF.toInt(),
        0xFFC678DD.toInt(),
        0xFF56B6C2.toInt(),
        0xFFD7DAE0.toInt(),
        0xFF5C6370.toInt(),
        0xFFFF7A85.toInt(),
        0xFFB4E88B.toInt(),
        0xFFFFD68A.toInt(),
        0xFF75BEFF.toInt(),
        0xFFD58AF0.toInt(),
        0xFF6DDBE5.toInt(),
        0xFFFFFFFF.toInt(),
    )

    fun xtermColour(index: Int): Int = when (val bounded = index.coerceIn(0, 255)) {
        in 0..15 -> ANSI_16[bounded]
        in 16..231 -> {
            val offset = bounded - 16
            val red = colourCube(offset / 36)
            val green = colourCube(offset / 6 % 6)
            val blue = colourCube(offset % 6)
            rgb(red, green, blue)
        }
        else -> {
            val level = 8 + (bounded - 232) * 10
            rgb(level, level, level)
        }
    }

    fun rgb(red: Int, green: Int, blue: Int): Int =
        0xFF000000.toInt() or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)

    private fun colourCube(value: Int): Int = if (value == 0) 0 else 55 + value * 40
}
