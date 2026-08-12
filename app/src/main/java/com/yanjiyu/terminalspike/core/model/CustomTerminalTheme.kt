package com.yanjiyu.terminalspike.core.model

/** A user-authored terminal palette. Built-in preset IDs are never persisted as these records. */
data class CustomTerminalTheme(
    val id: String,
    val name: String,
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val cursorArgb: Int,
    val selectionArgb: Int,
    val ansi16Argb: List<Int>,
    val boldUsesBrightColours: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(id, "terminal theme ID")
        requirePlainText(name, "terminal theme name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        require(ansi16Argb.size == ANSI_COLOUR_COUNT) {
            "A terminal theme needs exactly 16 ANSI colours."
        }
        require(
            listOf(foregroundArgb, backgroundArgb, cursorArgb, selectionArgb).all(::isOpaque) &&
                ansi16Argb.all(::isOpaque),
        ) { "Terminal theme colours must be opaque ARGB values." }
        requireEpochMillis(createdAtEpochMillis, "terminal theme creation time")
        requireEpochMillis(updatedAtEpochMillis, "terminal theme update time")
        requireTimestampOrder(
            createdAtEpochMillis,
            updatedAtEpochMillis,
            "terminal theme update time",
        )
    }

    companion object {
        const val ANSI_COLOUR_COUNT = 16

        private fun isOpaque(colour: Int): Boolean = colour ushr 24 == 0xff
    }
}
