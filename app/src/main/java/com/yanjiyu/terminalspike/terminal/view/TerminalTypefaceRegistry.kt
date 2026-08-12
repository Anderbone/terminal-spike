package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class TerminalTypefaces(
    val normal: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
    /** Used only by the API 26-28 renderer; API 29+ typefaces already contain this fallback. */
    val legacySymbolFallback: Typeface?,
)

/**
 * Process-local bridge between trusted font sources and the renderer.
 *
 * Profile observation runs on Dispatchers.IO and warms this registry before publishing the
 * renderer profile. The View keeps a fail-safe on-demand path for process-restoration races, but
 * never opens an arbitrary path: custom files must already have been registered by the private
 * font store.
 */
internal object TerminalTypefaceRegistry {
    private val registeredFiles = ConcurrentHashMap<String, Typeface>()
    private val resolvedFamilies = ConcurrentHashMap<String, TerminalTypefaces>()

    /** Preserves the existing private-font registration contract. */
    fun register(file: File): Typeface? = registeredFiles[file.absolutePath] ?: runCatching {
        Typeface.Builder(file).build()
    }.getOrNull()?.also { typeface ->
        registeredFiles.putIfAbsent(file.absolutePath, typeface)
    }

    fun registerProfileFont(
        context: Context,
        fontId: String,
        customFontPath: String?,
    ): TerminalTypefaces? = resolve(context, fontId, customFontPath)

    fun resolve(
        context: Context,
        fontId: String,
        customFontPath: String?,
    ): TerminalTypefaces? {
        val cacheKey = customFontPath ?: fontId
        resolvedFamilies[cacheKey]?.let { return it }
        val resolved = when {
            customFontPath != null -> resolveCustom(context, customFontPath)
            TerminalRendererProfile.isBundledFontId(fontId) -> resolveBundled(context, fontId)
            else -> null
        } ?: return null
        return resolvedFamilies.putIfAbsent(cacheKey, resolved) ?: resolved
    }

    /** Compatibility accessor retained for callers that only need the registered custom base. */
    fun resolve(absolutePath: String?): Typeface? = absolutePath?.let(registeredFiles::get)

    private fun resolveCustom(context: Context, absolutePath: String): TerminalTypefaces? {
        val registered = registeredFiles[absolutePath] ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacySet(context, registered)
        }
        return runCatching {
            val primary = FontFamily.Builder(Font.Builder(File(absolutePath)).build()).build()
            api29Set(context, primary)
        }.getOrElse { legacySet(context, registered) }
    }

    private fun resolveBundled(context: Context, fontId: String): TerminalTypefaces? {
        val resources = context.applicationContext.resources
        val fontResources = bundledResources(fontId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                if (fontResources == null) {
                    api29SystemSet(context)
                } else {
                    val primary = FontFamily.Builder(
                        Font.Builder(resources, fontResources.regular).setWeight(NORMAL_WEIGHT).build(),
                    ).addFont(
                        Font.Builder(resources, fontResources.bold).setWeight(BOLD_WEIGHT).build(),
                    ).build()
                    api29Set(context, primary)
                }
            }.getOrNull()
        }
        val base = fontResources?.family?.let { ResourcesCompat.getFont(context, it) }
            ?: Typeface.MONOSPACE
        return legacySet(context, base)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun api29Set(context: Context, primary: FontFamily): TerminalTypefaces {
        val symbols = symbolFamily(context)
        return TerminalTypefaces(
            normal = buildTypeface(primary, symbols, NORMAL_WEIGHT, FontStyle.FONT_SLANT_UPRIGHT),
            bold = buildTypeface(primary, symbols, BOLD_WEIGHT, FontStyle.FONT_SLANT_UPRIGHT),
            italic = buildTypeface(primary, symbols, NORMAL_WEIGHT, FontStyle.FONT_SLANT_ITALIC),
            boldItalic = buildTypeface(primary, symbols, BOLD_WEIGHT, FontStyle.FONT_SLANT_ITALIC),
            legacySymbolFallback = null,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun api29SystemSet(context: Context): TerminalTypefaces {
        // The symbols-only font has no ASCII glyphs, so Android immediately falls through to the
        // named system monospace family for ordinary text while retaining deterministic PUA glyphs.
        val symbols = symbolFamily(context)
        fun style(weight: Int, slant: Int): Typeface = Typeface.CustomFallbackBuilder(symbols)
            .setSystemFallback(SYSTEM_MONOSPACE_FAMILY)
            .setStyle(FontStyle(weight, slant))
            .build()
        return TerminalTypefaces(
            normal = style(NORMAL_WEIGHT, FontStyle.FONT_SLANT_UPRIGHT),
            bold = style(BOLD_WEIGHT, FontStyle.FONT_SLANT_UPRIGHT),
            italic = style(NORMAL_WEIGHT, FontStyle.FONT_SLANT_ITALIC),
            boldItalic = style(BOLD_WEIGHT, FontStyle.FONT_SLANT_ITALIC),
            legacySymbolFallback = null,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildTypeface(
        primary: FontFamily,
        symbols: FontFamily,
        weight: Int,
        slant: Int,
    ): Typeface = Typeface.CustomFallbackBuilder(primary)
        .addCustomFallback(symbols)
        .setSystemFallback(SYSTEM_MONOSPACE_FAMILY)
        .setStyle(FontStyle(weight, slant))
        .build()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun symbolFamily(context: Context): FontFamily = FontFamily.Builder(
        Font.Builder(context.applicationContext.resources, R.font.symbols_nerd_font_mono_regular)
            .setWeight(NORMAL_WEIGHT)
            .build(),
    ).build()

    private fun legacySet(context: Context, base: Typeface): TerminalTypefaces = TerminalTypefaces(
        normal = Typeface.create(base, Typeface.NORMAL),
        bold = Typeface.create(base, Typeface.BOLD),
        italic = Typeface.create(base, Typeface.ITALIC),
        boldItalic = Typeface.create(base, Typeface.BOLD_ITALIC),
        legacySymbolFallback = ResourcesCompat.getFont(
            context,
            R.font.symbols_nerd_font_mono_regular,
        ),
    )

    private fun bundledResources(fontId: String): BundledFontResources? = when (fontId) {
        TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID -> null
        TerminalRendererProfile.SOURCE_CODE_PRO_FONT_ID -> BundledFontResources(
            family = R.font.source_code_pro,
            regular = R.font.source_code_pro_regular,
            bold = R.font.source_code_pro_bold,
        )
        TerminalRendererProfile.JETBRAINS_MONO_FONT_ID -> BundledFontResources(
            family = R.font.jetbrains_mono,
            regular = R.font.jetbrains_mono_regular,
            bold = R.font.jetbrains_mono_bold,
        )
        TerminalRendererProfile.IBM_PLEX_MONO_FONT_ID -> BundledFontResources(
            family = R.font.ibm_plex_mono,
            regular = R.font.ibm_plex_mono_regular,
            bold = R.font.ibm_plex_mono_bold,
        )
        TerminalRendererProfile.CASCADIA_MONO_FONT_ID -> BundledFontResources(
            family = R.font.cascadia_mono,
            regular = R.font.cascadia_mono_regular,
            bold = R.font.cascadia_mono_bold,
        )
        else -> null
    }

    private data class BundledFontResources(
        @FontRes val family: Int,
        @FontRes val regular: Int,
        @FontRes val bold: Int,
    )

    private const val NORMAL_WEIGHT = 400
    private const val BOLD_WEIGHT = 700
    private const val SYSTEM_MONOSPACE_FAMILY = "monospace"
}
