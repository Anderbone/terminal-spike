package com.yanjiyu.terminalspike.localarch.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.model.*
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAgentControlInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as TerminalSpikeApplication).container

    @Test fun unixSocketRequiresGrantAndRecoversAfterMalformedFrames() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var control: LocalAgentControl
        control = LocalAgentControl(scope) { AppControlApi(container) { control.enabled.value }.handle(it).toString() }
        try {
            val address = Json.parseToJsonElement(control.connectionInfo()).jsonObject.string("abstractSocket")
            fun call(frame: String): JsonObject = LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(address, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 20_000
                socket.outputStream.write((frame + "\n").toByteArray())
                Json.parseToJsonElement(socket.inputStream.bufferedReader().readLine()).jsonObject
            }
            assertEquals("PERMISSION_REQUIRED", call(request("snippets.list"))["error"]!!.jsonObject.string("code"))
            assertEquals("INVALID_REQUEST", call("not json")["error"]!!.jsonObject.string("code"))
            assertEquals("REQUEST_TOO_LARGE", call("x".repeat(CONTROL_MAX_REQUEST_BYTES + 1))["error"]!!.jsonObject.string("code"))
            control.setEnabled(true)
            assertTrue(call(request("capabilities"))["ok"]!!.jsonPrimitive.boolean)
            assertTrue(call(request("snippets.list"))["ok"]!!.jsonPrimitive.boolean)
            control.setEnabled(false)
            assertFalse(call(request("status"))["result"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("PERMISSION_REQUIRED", call(request("keyboard.list"))["error"]!!.jsonObject.string("code"))
        } finally { control.close(); scope.cancel() }
    }

    @Test fun savedEditsPreserveOtherFieldsAndRejectStaleOrUnsafeMutations() = runBlocking {
        container.resolveStartup().join()
        container.terminalDataRepository.loadCatalog()
        val id = UUID.randomUUID().toString()
        val hostId = UUID.randomUUID().toString()
        var snippetId: String? = null
        var enabled = true
        val api = AppControlApi(container) { enabled }
        suspend fun call(method: String, params: JsonObject = buildJsonObject {}) = api.handle(request(method, params))
        suspend fun result(method: String, params: JsonObject): JsonObject {
            val response = call(method, params)
            assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
            return response["result"]!!.jsonObject
        }
        try {
            container.authoritativeData.withMutation {
                container.keyboardProfiles.insert(KeyboardProfile(
                    id = id, name = "Agent fixture", orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.TAB),
                    layout = KeyboardLayout.TWO_ROWS, modifierBehavior = ModifierBehavior.ONE_SHOT,
                    hapticFeedbackEnabled = true, keyRepeatEnabled = true, createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
                ))
                container.hostProfiles.insert(HostProfile(
                    id = hostId, displayName = "Agent fixture", hostname = "fixture.invalid", port = 22, username = "fixture",
                    protocol = ConnectionProtocol.SSH, credentialId = null, keyboardProfileId = id,
                    startupCommand = "keep-this-field", createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
                ))
                markCommitted()
            }
            val created = result("snippets.add", buildJsonObject { put("name", "Agent fixture 中文"); put("command", "printf 'literal'\nprintf 'second'") })
            snippetId = created.string("id")
            assertEquals("saved_only", created.string("effect"))
            assertFalse(created.boolean("appendEnter"))
            assertEquals("insert", created.string("tapAction"))
            assertEquals("Agent fixture 中文", container.snippets.get(snippetId)!!.name)
            val updated = result("snippets.update", buildJsonObject {
                put("id", snippetId); put("revision", created.long("revision")); put("name", "Updated fixture")
            })
            val stale = call("snippets.delete", buildJsonObject { put("id", snippetId); put("revision", created.long("revision")) })
            assertEquals("CONFLICT", stale["error"]!!.jsonObject.string("code"))
            assertEquals("Updated fixture", container.terminalDataRepository.loadCatalog().catalog!!.snippets.single { it.snippet.id == snippetId }.snippet.name)
            val keyboard = result("keyboard.update", buildJsonObject { put("id", id); put("revision", 20); put("hapticFeedbackEnabled", false) })
            assertFalse(keyboard.boolean("hapticFeedbackEnabled"))
            assertEquals(listOf(KeyboardAction.ESCAPE, KeyboardAction.TAB), container.keyboardProfiles.get(id)!!.orderedActions)
            val duplicateKeys = call("keyboard.update", buildJsonObject {
                put("id", id); put("revision", keyboard.long("revision")); put("actions", JsonArray(listOf(JsonPrimitive("escape"), JsonPrimitive("escape"))))
            })
            assertEquals("INVALID_ARGUMENT", duplicateKeys["error"]!!.jsonObject.string("code"))
            assertEquals(keyboard.long("revision"), container.keyboardProfiles.get(id)!!.updatedAtEpochMillis)
            val forwarded = result("forwards.add", buildJsonObject { put("connection", hostId); put("revision", 20); put("listenPort", 15173); put("destinationPort", 5173) })
            assertEquals("next_connection", forwarded.string("effect"))
            assertEquals("keep-this-field", container.hostProfiles.get(hostId)!!.startupCommand)
            assertEquals(id, container.hostProfiles.get(hostId)!!.keyboardProfileId)
            assertFalse(forwarded.containsKey("credentialId"))
            val unsafe = call("forwards.add", buildJsonObject {
                put("connection", hostId); put("revision", forwarded.long("revision")); put("bindAddress", "0.0.0.0"); put("listenPort", 15174); put("destinationPort", 5174)
            })
            assertEquals("INVALID_ARGUMENT", unsafe["error"]!!.jsonObject.string("code"))
            assertEquals(1, container.hostProfiles.get(hostId)!!.portForwards.size)
            val removed = result("forwards.remove", buildJsonObject { put("connection", hostId); put("revision", forwarded.long("revision")); put("index", 1) })
            assertTrue(removed["portForwards"]!!.jsonArray.isEmpty())
            enabled = false
            val denied = call("snippets.delete", buildJsonObject { put("id", snippetId); put("revision", updated.long("revision")) })
            assertEquals("PERMISSION_REQUIRED", denied["error"]!!.jsonObject.string("code"))
            assertNotNull(container.snippets.get(snippetId))
        } finally {
            container.authoritativeData.withMutation {
                snippetId?.let { container.snippets.delete(it) }
                container.hostProfiles.delete(hostId)
                container.profileDefaults.deleteKeyboardProfile(id)
                markCommitted()
            }
        }
    }

    @Test fun toolInstallationDoesNotFollowGuestDirectorySymlinks() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "agent-tools-test-")
        val rootfs = Files.createDirectory(directory.resolve("rootfs"))
        val outside = Files.createDirectory(directory.resolve("outside"))
        Files.createDirectory(rootfs.resolve("usr"))
        Files.createSymbolicLink(rootfs.resolve("usr/local"), outside)
        try {
            assertThrows(java.io.IOException::class.java) {
                ControlToolsInstaller(context).install(rootfs.toFile(), "{}")
            }
            Files.newDirectoryStream(outside).use { assertFalse(it.iterator().hasNext()) }
        } finally {
            Files.delete(rootfs.resolve("usr/local"))
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun appearanceEditsPersistAndPreserveSecurityProfilesAndBaseColours() = runBlocking {
        container.resolveStartup().join()
        container.terminalDataRepository.loadCatalog()
        val originalSettings = container.settings.settings.first()
        val profileId = UUID.randomUUID().toString()
        val sourceProfile = container.terminalProfiles.observeAll().first().first()
        var themeId: String? = null
        var appearanceWrittenRevision: Long? = null
        val api = AppControlApi(container) { true }
        suspend fun call(method: String, p: JsonObject = buildJsonObject {}) = api.handle(request(method, p))
        suspend fun result(method: String, p: JsonObject = buildJsonObject {}): JsonObject {
            val response = call(method, p)
            assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
            return response["result"]!!.jsonObject
        }
        try {
            container.authoritativeData.withMutation {
                container.terminalProfiles.insert(sourceProfile.copy(
                    id = profileId, name = "Agent appearance fixture", themeId = "nord", fontId = "jetbrains_mono",
                    scrollbackLines = 789, termValue = "keep-this-term", createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
                ))
                markCommitted()
            }
            val initial = result("appearance.get")
            val changed = result("appearance.update", buildJsonObject {
                put("revision", initial.long("revision")); put("themeMode", if (initial.string("themeMode") == "dark") "light" else "dark")
                put("accentPreset", "violet"); put("dynamicColorEnabled", false)
            })
            appearanceWrittenRevision = changed.long("revision")
            assertEquals("violet", container.settings.settings.first().accentPreset)
            assertEquals(originalSettings.appLockMode, container.settings.settings.first().appLockMode)
            assertEquals(originalSettings.keepaliveIntervalSeconds, container.settings.settings.first().keepaliveIntervalSeconds)
            val stale = call("appearance.update", buildJsonObject { put("revision", initial.long("revision")); put("themeMode", "system") })
            assertEquals("CONFLICT", stale["error"]!!.jsonObject.string("code"))
            val securityEdit = call("appearance.update", buildJsonObject { put("revision", changed.long("revision")); put("appLockMode", "off") })
            assertEquals("INVALID_ARGUMENT", securityEdit["error"]!!.jsonObject.string("code"))
            assertEquals(changed.long("revision"), result("appearance.get").long("revision"))

            val created = result("themes.add", buildJsonObject { put("name", "Agent palette fixture"); put("base", "nord"); put("background", "#000000") })
            themeId = created.string("id")
            assertEquals("#000000", created.string("background"))
            assertEquals(TerminalThemes.find("nord").ansi16, container.customTerminalThemes.get(themeId)!!.ansi16Argb)
            val originalTheme = result("themes.get", buildJsonObject { put("id", "nord") })
            assertFalse(originalTheme.boolean("editable"))
            assertEquals("#2E3440", originalTheme.string("background"))
            val builtInEdit = call("themes.update", buildJsonObject { put("id", "nord"); put("revision", 0); put("background", "#FFFFFF") })
            assertEquals("BUILTIN_THEME", builtInEdit["error"]!!.jsonObject.string("code"))

            val edited = result("terminal.update", buildJsonObject {
                put("id", profileId); put("revision", 20); put("themeId", themeId); put("fontId", "source_code_pro")
                put("fontSizeSp", 17.5); put("lineHeightMultiplier", 1.2); put("letterSpacingEm", -0.1)
            })
            val record = container.terminalProfiles.get(profileId)!!
            assertEquals(17.5f, record.fontSizeSp)
            assertEquals(themeId, record.themeId)
            assertEquals("source_code_pro", record.fontId)
            assertEquals(789, record.scrollbackLines)
            assertEquals("keep-this-term", record.termValue)
            assertEquals(sourceProfile.scroll, record.scroll)
            assertEquals(themeId, container.terminalDataRepository.loadCatalog().catalog!!.terminalProfiles.single { it.id == profileId }.themeId)
            val invalidFont = call("terminal.update", buildJsonObject {
                put("id", profileId); put("revision", edited.long("revision")); put("fontId", "/data/private/font.ttf")
            })
            assertEquals("INVALID_ARGUMENT", invalidFont["error"]!!.jsonObject.string("code"))
            assertEquals(edited.long("revision"), container.terminalProfiles.get(profileId)!!.updatedAtEpochMillis)
            val themeUpdated = result("themes.update", buildJsonObject {
                put("id", themeId); put("revision", created.long("revision")); put("cursor", "#12AB34")
            })
            assertEquals("#12AB34", themeUpdated.string("cursor"))
            val transparent = call("themes.update", buildJsonObject {
                put("id", themeId); put("revision", themeUpdated.long("revision")); put("background", "#80000000")
            })
            assertEquals("INVALID_ARGUMENT", transparent["error"]!!.jsonObject.string("code"))
            assertEquals("#000000", result("themes.get", buildJsonObject { put("id", themeId) }).string("background"))
            val fonts = call("fonts.list")
            assertTrue(fonts["result"]!!.jsonArray.any { it.jsonObject.string("id") == "jetbrains_mono" })
            assertTrue(fonts["result"]!!.jsonArray.none { "file" in it.jsonObject || "path" in it.jsonObject })
        } finally {
            container.authoritativeData.withMutation {
                container.profileDefaults.deleteTerminalProfile(profileId)
                themeId?.let { container.customTerminalThemes.deleteAndResetProfiles(it, TerminalThemes.CURRENT_ID, System.currentTimeMillis()) }
                if (appearanceWrittenRevision != null) container.settings.update { builder ->
                    if (appearanceRevision(builder.build()) == appearanceWrittenRevision) {
                        builder.setThemeMode(originalSettings.themeMode).setAccentPreset(originalSettings.accentPreset)
                            .setDynamicColorEnabled(originalSettings.dynamicColorEnabled)
                    }
                }
                markCommitted()
            }
        }
    }

    private fun request(method: String, params: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("api", 1); put("method", method); put("params", params)
    }.toString()
}
