package com.yanjiyu.terminalspike.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val SHOW_CONNECTION = "show_connection"

internal fun setLocalArchConnectionVisible(context: Context, visible: Boolean) {
    context.getSharedPreferences("local_arch_ui", Context.MODE_PRIVATE)
        .edit().putBoolean(SHOW_CONNECTION, visible).apply()
}

@Composable
internal fun rememberLocalArchConnectionVisible(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("local_arch_ui", Context.MODE_PRIVATE) }
    val visible = remember(preferences) { mutableStateOf(preferences.getBoolean(SHOW_CONNECTION, true)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SHOW_CONNECTION) visible.value = preferences.getBoolean(SHOW_CONNECTION, true)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        visible.value = preferences.getBoolean(SHOW_CONNECTION, true)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}
