package com.yanjiyu.terminalspike.ui

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Resource-backed copy that may safely cross a ViewModel/UI boundary.
 *
 * [Dynamic] is reserved for bounded user data or sanitized runtime/provider detail. [Token] is for
 * reviewed protocol identifiers and keycap glyphs. Product-authored copy belongs in [Resource] or
 * [Quantity] so locale changes are applied when the UI renders it.
 */
sealed interface UiText {
    data class Resource(
        @StringRes val id: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : UiText

    data class Quantity(
        @PluralsRes val id: Int,
        val quantity: Int,
        val formatArgs: List<Any> = listOf(quantity),
    ) : UiText

    data class Dynamic(val value: String) : UiText

    data class Token(val value: String) : UiText

    /** Locale-aware list composition for resource-backed labels with optional bounded values. */
    data class Joined(val items: List<UiText>) : UiText
}

internal fun uiText(@StringRes id: Int, vararg formatArgs: Any): UiText =
    UiText.Resource(id, formatArgs.toList())

internal fun quantityText(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): UiText = UiText.Quantity(
    id = id,
    quantity = quantity,
    formatArgs = formatArgs.toList().ifEmpty { listOf(quantity) },
)

internal fun uiToken(value: String): UiText = UiText.Token(value)

@Composable
internal fun UiText.resolve(): String {
    // Reading the configuration makes locale changes observable even when the Context instance is
    // retained across a configuration update.
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}

internal fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Resource -> resources.getString(id, *formatArgs.resolveUiTextArgs(resources))
    is UiText.Quantity -> resources.getQuantityString(
        id,
        quantity,
        *formatArgs.resolveUiTextArgs(resources),
    )
    is UiText.Dynamic -> value
    is UiText.Token -> value
    is UiText.Joined -> ListFormatter.getInstance(resources.configuration.locales[0]).format(
        items.map { it.resolve(resources) },
    )
}

private fun List<Any>.resolveUiTextArgs(resources: Resources): Array<out Any> =
    map { value -> if (value is UiText) value.resolve(resources) else value }.toTypedArray()
