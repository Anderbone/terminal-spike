package com.yanjiyu.terminalspike.core.data.migration

import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SecureUserSettingsStore
import com.yanjiyu.terminalspike.settings.UserSettings
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUserSettingsReaderTest {
    @Test
    fun readsExistingV3WithoutChangingSourceOrKey() {
        val fixture = fixture()
        try {
            val settings = UserSettings(snippets = listOf(CommandSnippet(9, "Status", "git status", false)))
            SecureUserSettingsStore(fixture.context, fixture.fileName, fixture.keyAlias).save(settings)
            val before = fixture.file.readBytes()

            val result = LegacyUserSettingsReader(
                fixture.context,
                fixture.fileName,
                fixture.keyAlias,
            ).read() as LegacyUserSettingsReadResult.Loaded

            assertEquals(3, result.sourceVersion)
            assertEquals(settings, result.settings)
            assertEquals(64, result.sourceDigestSha256.length)
            assertArrayEquals(before, fixture.file.readBytes())
            assertTrue(fixture.keyStore().containsAlias(fixture.keyAlias))
        } finally {
            fixture.clear()
        }
    }

    @Test
    fun corruptCiphertextIsBlockedAndPreserved() {
        val fixture = fixture()
        try {
            SecureUserSettingsStore(fixture.context, fixture.fileName, fixture.keyAlias).save(UserSettings())
            val corrupt = fixture.file.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
            fixture.file.writeBytes(corrupt)

            val result = LegacyUserSettingsReader(
                fixture.context,
                fixture.fileName,
                fixture.keyAlias,
            ).read()

            assertEquals(
                LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT),
                result,
            )
            assertArrayEquals(corrupt, fixture.file.readBytes())
            assertTrue(fixture.keyStore().containsAlias(fixture.keyAlias))
        } finally {
            fixture.clear()
        }
    }

    @Test
    fun missingKeyIsBlockedWithoutCreatingAReplacement() {
        val fixture = fixture()
        try {
            SecureUserSettingsStore(fixture.context, fixture.fileName, fixture.keyAlias).save(UserSettings())
            val before = fixture.file.readBytes()
            fixture.keyStore().deleteEntry(fixture.keyAlias)

            val result = LegacyUserSettingsReader(
                fixture.context,
                fixture.fileName,
                fixture.keyAlias,
            ).read()

            assertEquals(
                LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.KEY_UNAVAILABLE),
                result,
            )
            assertArrayEquals(before, fixture.file.readBytes())
            assertFalse(fixture.keyStore().containsAlias(fixture.keyAlias))
        } finally {
            fixture.clear()
        }
    }

    private fun fixture(): ReaderFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        return ReaderFixture(
            context = context,
            fileName = "legacy-settings-reader-$suffix.bin",
            keyAlias = "terminal-spike-legacy-reader-$suffix",
        )
    }

    private data class ReaderFixture(
        val context: android.content.Context,
        val fileName: String,
        val keyAlias: String,
    ) {
        val file get() = context.filesDir.resolve(fileName)

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun clear() {
            file.delete()
            file.resolveSibling("$fileName.bak").delete()
            keyStore().deleteEntry(keyAlias)
        }
    }
}
