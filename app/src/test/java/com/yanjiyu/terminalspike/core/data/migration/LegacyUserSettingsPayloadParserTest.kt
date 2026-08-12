package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUserSettingsPayloadParserTest {
    @Test
    fun parsesHandcraftedVersionOneGolden() {
        val bytes = payload(version = 1)

        val parsed = LegacyUserSettingsPayloadParser.parse(bytes)

        assertEquals(1, parsed.version)
        assertEquals(listOf(TerminalExtraKey.ESC), parsed.settings.extraKeys)
        assertTrue(parsed.settings.identities.isEmpty())
    }

    @Test
    fun parsesHandcraftedVersionTwoGolden() {
        val bytes = payload(version = 2)

        val parsed = LegacyUserSettingsPayloadParser.parse(bytes)

        assertEquals(2, parsed.version)
        assertTrue(parsed.settings.identities.isEmpty())
    }

    @Test
    fun parsesHandcraftedVersionThreeGolden() {
        val bytes = payload(version = 3)

        val parsed = LegacyUserSettingsPayloadParser.parse(bytes)

        assertEquals(3, parsed.version)
        assertTrue(parsed.settings.profiles.isEmpty())
    }

    @Test(expected = UnsupportedLegacySettingsVersionException::class)
    fun rejectsNewerPayloadWithoutReplacingIt() {
        LegacyUserSettingsPayloadParser.parse(payload(version = 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingPayloadData() {
        LegacyUserSettingsPayloadParser.parse(payload(version = 3) + byteArrayOf(1))
    }

    private fun payload(version: Int): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(0x54535031)
            data.writeInt(version)
            data.writeInt(0) // profiles
            data.writeInt(0) // snippets
            data.writeInt(1)
            data.writeUTF(TerminalExtraKey.ESC.name)
            if (version >= 2) data.writeInt(0) // identities
        }
    }.toByteArray()
}
