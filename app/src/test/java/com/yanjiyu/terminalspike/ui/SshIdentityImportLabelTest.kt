package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SshIdentityImportLabelTest {
    private val fallback = "Imported key"

    @Test
    fun truncationPreservesWholeSupplementaryCharacters() {
        val prefix = "a".repeat(UserSettings.MAX_LABEL_LENGTH - 1)
        assertEquals(prefix, importedSshIdentityLabel(prefix + "🚀", fallback))
        val exact = "a".repeat(UserSettings.MAX_LABEL_LENGTH - 2) + "🚀"
        assertEquals(exact, importedSshIdentityLabel(exact + "tail", fallback))
    }

    @Test
    fun supplementaryAndBmpFormatsAreRemovedWhileVisibleTextSurvives() {
        assertEquals("build🚀密钥", importedSshIdentityLabel(
            "/provider/build\uDB40\uDC01🚀\u202E密钥\uD834\uDD73", fallback,
        ))
    }

    @Test
    fun invisibleOnlyNamesUseTheFallback() {
        assertEquals(fallback, importedSshIdentityLabel("/provider/ \uDB40\uDC01\u202E\uDB40\uDC20 ", fallback))
    }

    @Test
    fun isolatedSurrogateUnitsCannotBecomeIdentityMetadata() {
        assertEquals("validname", importedSshIdentityLabel("valid\uD800name\uDC00", fallback))
    }

    @Test
    fun pathControlRemovalAndInternalWhitespaceKeepTheirExistingContracts() {
        assertEquals("Prodbox  密钥", importedSshIdentityLabel("/provider/  Prod\tbox  密钥 \r\n", fallback))
        assertEquals(fallback, importedSshIdentityLabel("/provider/ \t\n ", fallback))
    }
}
