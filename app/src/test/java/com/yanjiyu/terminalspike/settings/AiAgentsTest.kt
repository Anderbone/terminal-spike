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
    @Test
    fun launchOverridesAreQuotedAndDefaultsLeaveTheOriginalCommandUntouched() {
        val codex = AiAgents.defaultEntries.first()
        assertEquals("codex --yolo", codex.launchCommand)
        assertEquals(
            "codex --yolo --model 'gpt-6.1-sol' -c 'model_reasoning_effort=\"high\"'",
            codex.copy(model = "gpt-6.1-sol", effort = "high").launchCommand,
        )
        assertEquals("claude --model 'opus' --effort 'low'",
            AiAgent("claude", "Claude", "claude", "opus", "low").launchCommand)
        assertEquals("codex --yolo --model 'model'\"'\"'; touch /tmp/nope'",
            codex.copy(model = "model'; touch /tmp/nope").launchCommand)
        assertEquals("codex --yolo -c 'model_reasoning_effort=\"low\"'",
            codex.copy(effort = "low").launchCommand)
    }

    @Test
    fun wrappersKeepTheirCommandsAndLegacyHaikuDoesNotInheritUnsupportedEffort() {
        for (command in listOf("pi", "opencode", "codex && echo done", "ssh host codex", "echo codex", "codex --model fixed", "claude --effort high", "codex # comment", "codex 'prompt'")) {
            val agent = AiAgent("custom", "Custom", command, model = "example")
            assertNull(agent.cli)
            assertEquals(command, agent.launchCommand)
        }
        assertEquals("codex", AiAgent("custom", "Custom", "/usr/local/bin/codex --yolo").cli)
        assertTrue(AiAgent("claude", "Claude", "claude", "claude-haiku-4-5").effortPresets.isEmpty())
        for (agent in listOf(
            AiAgent("codex", "Codex", "codex", effort = "high; exit"),
            AiAgent("codex", "Codex", "codex", model = "bad\nmodel"),
            AiAgent("claude", "Claude", "claude", model = "claude-haiku-4-5", effort = "high"),
        )) assertFalse(AiAgents(listOf(agent)).isValid())
    }

    @Test
    fun selectedModelsAndEffortsSurviveSettingsAndLegacyEntriesInheritCliDefaults() = runTest {
        val agents = AiAgents(listOf(
            AiAgent("codex", "Codex", "codex --yolo", "gpt-6.1-terra", "high"),
            AiAgent("claude", "Claude", "claude", "opus", "low"),
        ), defaultId = "codex")
        val settings = AppSettingsSerializer.defaultValue.toBuilder().setAiAgents(agents.toProto()).build()
        val bytes = ByteArrayOutputStream().also { AppSettingsSerializer.writeTo(settings, it) }.toByteArray()
        assertEquals(agents, AiAgents.from(AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes))))
        val legacy = agents.toProto().toBuilder().clearEntries().addEntries(
            com.yanjiyu.terminalspike.core.data.settings.AiAgentCommand.newBuilder()
                .setId("codex").setName("Codex").setCommand("codex --yolo"),
        ).build()
        val restored = AiAgents.from(settings.toBuilder().setAiAgents(legacy).build()).defaultAgent!!
        assertEquals("", restored.model)
        assertEquals("", restored.effort)
        assertEquals("codex --yolo", restored.launchCommand)
    }

}
