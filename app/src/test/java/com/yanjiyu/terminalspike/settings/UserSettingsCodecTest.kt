package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsCodecTest {
    @Test
    fun roundTripsProfilesSnippetsAndKeyOrder() {
        val settings = UserSettings(
            profiles = listOf(SavedSshProfile(7, "Production", "example.com", 2222, "operator", true)),
            snippets = listOf(CommandSnippet(9, "Deploy status", "cd /srv\ngit status", true)),
            extraKeys = listOf(TerminalExtraKey.CTRL, TerminalExtraKey.ESC, TerminalExtraKey.TAB),
            identities = listOf(
                SavedSshIdentity(11, "Laptop key", "ED25519", "SHA256:test", true),
            ),
        )

        val bytes = ByteArrayOutputStream().also { UserSettingsCodec.write(settings, it) }.toByteArray()

        assertEquals(settings, UserSettingsCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun migratesVersionOneSettingsWithoutIdentities() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x54535031)
                data.writeInt(1)
                data.writeInt(0)
                data.writeInt(0)
                data.writeInt(1)
                data.writeUTF(TerminalExtraKey.ESC.name)
            }
        }.toByteArray()

        val settings = UserSettingsCodec.read(ByteArrayInputStream(bytes))

        assertEquals(listOf(TerminalExtraKey.ESC), settings.extraKeys)
        assertTrue(settings.identities.isEmpty())
    }

    @Test
    fun migratesVersionTwoProfilesWithoutSavedPasswords() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x54535031)
                data.writeInt(2)
                data.writeInt(1)
                data.writeLong(7)
                data.writeUTF("Production")
                data.writeUTF("example.com")
                data.writeInt(22)
                data.writeUTF("operator")
                data.writeInt(0)
                data.writeInt(1)
                data.writeUTF(TerminalExtraKey.ESC.name)
                data.writeInt(0)
            }
        }.toByteArray()

        val settings = UserSettingsCodec.read(ByteArrayInputStream(bytes))

        assertEquals(1, settings.profiles.size)
        assertTrue(!settings.profiles.single().hasSavedPassword)
    }

    @Test
    fun rejectsCorruptOrUnboundedInput() {
        val valid = ByteArrayOutputStream().also { UserSettingsCodec.write(UserSettings(), it) }.toByteArray()
        val corrupt = valid.copyOf().also { it[0] = 0 }

        assertTrue(runCatching { UserSettingsCodec.read(ByteArrayInputStream(corrupt)) }.isFailure)

        val oversized = UserSettings(
            snippets = List(UserSettings.MAX_SNIPPETS + 1) { index ->
                CommandSnippet(index.toLong() + 1, "Snippet $index", "true", true)
            },
        )
        assertTrue(runCatching { UserSettingsCodec.write(oversized, ByteArrayOutputStream()) }.isFailure)
    }

    @Test
    fun defaultLayoutIncludesEverySupportedKeyOnce() {
        assertEquals(TerminalExtraKey.entries, TerminalExtraKey.DEFAULT_ORDER)
        assertEquals(TerminalExtraKey.DEFAULT_ORDER.size, TerminalExtraKey.DEFAULT_ORDER.distinct().size)
    }
}
