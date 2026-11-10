package com.yanjiyu.terminalspike.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/** Device-local UI preference; terminal input and remote process locales are independent. */
internal object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val KEY = "language_tag"
    val supportedTags = listOf("", "zh", "en")

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            .substringBefore(',').let { tag -> supportedTags.firstOrNull { it.isNotEmpty() && tag.startsWith(it) } ?: "" }
    } else {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "")
            ?.takeIf { it in supportedTags } ?: ""
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
    }

    fun select(activity: Activity, tag: String) {
        require(tag in supportedTags)
        if (selected(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }
}
