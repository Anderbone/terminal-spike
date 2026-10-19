package com.yanjiyu.terminalspike.ui

import android.content.Context

/** Private, device-local draft; deliberately excluded from the app's exports and backup. */
internal class BufferedInputDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "buffered_input_draft",
        Context.MODE_PRIVATE,
    )

    fun load(): String = preferences.getString("text", "").orEmpty()
        .take(MAX_BUFFERED_INPUT_CHARACTERS)

    fun save(text: String) {
        // Queue each edit immediately without blocking keyboard input on disk I/O.
        preferences.edit().apply {
            if (text.isEmpty()) remove("text") else putString("text", text)
        }.apply()
    }
}
