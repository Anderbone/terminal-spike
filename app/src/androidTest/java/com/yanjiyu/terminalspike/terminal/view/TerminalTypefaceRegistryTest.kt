package com.yanjiyu.terminalspike.terminal.view

import android.graphics.Paint
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalTypefaceRegistryTest {
    @Test
    fun everyBundledFamilyResolvesAsciiAndNerdSymbolFallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        TerminalRendererProfile.BUNDLED_FONT_IDS.forEach { fontId ->
            val family = TerminalTypefaceRegistry.registerProfileFont(context, fontId, null)
            assertNotNull(fontId, family)
            requireNotNull(family)
            assertTrue(fontId, Paint().apply { typeface = family.normal }.hasGlyph("M"))
            val symbolTypeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                family.normal
            } else {
                requireNotNull(family.legacySymbolFallback)
            }
            assertTrue(fontId, Paint().apply { typeface = symbolTypeface }.hasGlyph("\uE0B0"))
        }
    }
}
