package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsValidator
import com.yanjiyu.terminalspike.core.data.settings.InvalidAppSettingsException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiAgentsTest {
    @Test
    fun untouchedInstallHasFourAgentsAndAlwaysShowsPicker() {
        val agents = AiAgents.from(AppSettingsSerializer.defaultValue)
        assertEquals(listOf("Codex", "Claude", "pi", "OpenCode"), agents.entries.map { it.name })
        assertEquals("codex --yolo", agents.entries.first().command)
        assertNull(agents.defaultAgent)
        assertTrue(agents.isValid())
    }

    @Test
    fun editedCommandsDefaultAndIntentionallyEmptyListSurviveSerialization() = runTest {
        val custom = AiAgent("custom", "My agent", "my-agent --resume 'my project'")
        for (agents in listOf(
            AiAgents(listOf(custom), custom.id), AiAgents(listOf(custom)), AiAgents(emptyList()),
        )) {
            val settings = AppSettingsSerializer.defaultValue.toBuilder().setAiAgents(agents.toProto()).build()
            val bytes = ByteArrayOutputStream().also { AppSettingsSerializer.writeTo(settings, it) }.toByteArray()
            assertEquals(agents, AiAgents.from(AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes))))
        }
    }

    @Test
    fun deletingDefaultClearsItAndDeletingOtherAgentsPreservesIt() {
        val agents = AiAgents(defaultId = "codex")
        assertEquals("codex", agents.without("pi").defaultAgent?.id)
        assertNull(agents.without("codex").defaultAgent)
        assertTrue(agents.without("codex").isValid())
    }

    @Test
    fun invalidCommandsDuplicatesAndDanglingDefaultsAreRejected() {
        val entry = AiAgent("custom", "Agent", "agent")
        for (invalid in listOf(
            AiAgents(listOf(entry.copy(command = "agent\nexit"))),
            AiAgents(listOf(entry.copy(command = "agent\r"))),
            AiAgents(listOf(entry.copy(command = "agent\u0000"))),
            AiAgents(listOf(entry.copy(command = " "))),
            AiAgents(listOf(entry.copy(name = ""))),
            AiAgents(listOf(entry, entry)),
            AiAgents(listOf(entry), "missing"),
        )) assertFalse(invalid.isValid())
        val invalidProto = AiAgents().toProto().toBuilder().setDefaultId("missing").build()
        assertThrows(InvalidAppSettingsException::class.java) {
            AppSettingsValidator.requireValidCurrent(
                AppSettingsSerializer.defaultValue.toBuilder().setAiAgents(invalidProto).build(),
            )
        }
    }
}
