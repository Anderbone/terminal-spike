package com.yanjiyu.terminalspike.localarch.control

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.AppContainer
import com.yanjiyu.terminalspike.isCutoverReady
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.*
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.ui.settings.AccentPreset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A deliberately narrow settings API. It cannot read credentials or send terminal input. */
internal class AppControlApi(
    private val container: AppContainer,
    private val granted: () -> Boolean,
) {
    private val appearance = AppearanceControl(container)
    suspend fun handle(frame: String): JsonObject = try {
        val (method, params) = parseControlRequest(frame)
        val result = when (method) {
            "status" -> {
                params.requireFields()
                buildJsonObject { put("enabled", granted()); put("authorization", "until_app_process_exits") }
            }
            "capabilities" -> {
                params.requireFields()
                buildJsonObject {
                    put("methods", JsonArray(METHODS.map(::JsonPrimitive)))
                    put("keyboardActions", JsonArray(KeyboardAction.entries.map { JsonPrimitive(it.wireCode) }))
                    put("keyboardLayouts", JsonArray(KeyboardLayout.entries.map { JsonPrimitive(it.wireCode) }))
                    put("modifierBehaviors", JsonArray(ModifierBehavior.entries.map { JsonPrimitive(it.wireCode) }))
                    put("inputModes", JsonArray(TerminalInputMode.entries.map { JsonPrimitive(it.wireCode) }))
                    put("appThemeModes", JsonArray(listOf("system", "light", "dark").map(::JsonPrimitive)))
                    put("accentPresets", JsonArray(AccentPreset.entries.map { JsonPrimitive(it.wireCode) }))
                    put("cursorStyles", JsonArray(CursorStyle.entries.map { JsonPrimitive(it.wireCode) }))
                    put("fontSizeSpRange", JsonArray(listOf(ModelLimits.MIN_FONT_SIZE_SP, ModelLimits.MAX_FONT_SIZE_SP).map(::JsonPrimitive)))
                    put("lineHeightMultiplierRange", JsonArray(listOf(ModelLimits.MIN_LINE_HEIGHT_MULTIPLIER, ModelLimits.MAX_LINE_HEIGHT_MULTIPLIER).map(::JsonPrimitive)))
                    put("letterSpacingEmRange", JsonArray(listOf(ModelLimits.MIN_LETTER_SPACING_EM, ModelLimits.MAX_LETTER_SPACING_EM).map(::JsonPrimitive)))
                    put("colourFormat", "#RRGGBB")
                    put("portForwardEffect", "next_connection")
                }
            }
            else -> {
                if (method !in METHODS) throw ControlFailure("UNKNOWN_METHOD", "Unsupported method.")
                requireGrant()
                container.authoritativeData.awaitReady()
                check(container.startLegacyMigrationForCutover().await().isCutoverReady())
                if (method in READ_METHODS) {
                    container.authoritativeData.withRead { requireGrant(); requireUnlockedSettings(); read(method, params) }
                } else {
                    // A cancellation cannot split the durable write and generation notification.
                    container.authoritativeData.withMutation {
                        withContext(NonCancellable) {
                            requireGrant()
                            requireUnlockedSettings()
                            // Settings screens also write through Room. Hold its writer transaction
                            // across revision checking and the edit, not just the authority gate.
                            val edited = if (method == "appearance.update") appearance.updateApp(params, ::requireGrant)
                            else container.database.withTransaction {
                                requireGrant()
                                write(method, params)
                            }
                            markCommitted()
                            edited
                        }
                    }
                }
            }
        }
        buildJsonObject { put("api", 1); put("ok", true); put("result", result) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ControlFailure) {
        controlError(failure.code, requireNotNull(failure.message))
    } catch (_: IllegalArgumentException) {
        // Do not echo caller payloads, snippets, database errors or endpoint details.
        controlError("INVALID_ARGUMENT", "Invalid or unknown fields. Check spikectl --help and capabilities.")
    } catch (_: Exception) {
        controlError("DATA_UNAVAILABLE", "App data could not be accessed. Check the app recovery screen.")
    }

    private fun requireGrant() {
        if (!granted()) throw ControlFailure("PERMISSION_REQUIRED", "Enable agent access in Settings > Local Arch Linux.")
    }

    private suspend fun requireUnlockedSettings() {
        if (container.settings.settings.first().appLockMode !in setOf(
                AppSettings.AppLockMode.APP_LOCK_MODE_OFF, AppSettings.AppLockMode.APP_LOCK_MODE_UNSPECIFIED,
            )) throw ControlFailure("APP_LOCK_ENABLED", "Agent settings access is unavailable while App lock is enabled.")
    }

    private suspend fun read(method: String, p: JsonObject): JsonElement = when (method) {
        "snippets.list" -> {
            p.requireFields()
            JsonArray(container.snippets.observeAll().first().map { it.json() })
        }
        "snippets.get" -> {
            p.requireFields("id")
            snippet(p.string("id")).json()
        }
        "keyboard.list" -> {
            p.requireFields()
            val defaultId = container.settings.settings.first().defaultKeyboardProfileId
            JsonArray(container.keyboardProfiles.observeAll().first().map { it.json(defaultId) })
        }
        "keyboard.get" -> {
            p.requireFields("id")
            keyboard(p.string("id")).json(container.settings.settings.first().defaultKeyboardProfileId)
        }
        "connections.list" -> {
            p.requireFields()
            JsonArray(container.hostProfiles.observeAll().first().map { it.json() })
        }
        "forwards.list" -> {
            p.requireFields("connection")
            host(p.string("connection")).json()
        }
        else -> appearance.read(method, p)
    }

    private suspend fun write(method: String, p: JsonObject): JsonElement = when (method) {
        "snippets.add" -> {
            p.requireFields("name", "command", "group", "appendEnter", "tapAction", "isFavorite")
            require(container.snippets.observeAll().first().size < UserSettings.MAX_SNIPPETS) { "Too many snippets." }
            val now = System.currentTimeMillis()
            val record = Snippet(
                id = UUID.randomUUID().toString(), name = p.string("name"), command = p.string("command"),
                group = p["group"]?.let { p.string("group").ifEmpty { null } },
                appendEnter = p["appendEnter"]?.let { p.boolean("appendEnter") } ?: false,
                tapAction = p["tapAction"]?.let { SnippetTapAction.fromWireCode(p.string("tapAction")) } ?: SnippetTapAction.INSERT,
                isFavorite = p["isFavorite"]?.let { p.boolean("isFavorite") } ?: false,
                createdAtEpochMillis = now, updatedAtEpochMillis = now,
            )
            container.snippets.insert(record)
            record.json()
        }
        "snippets.update" -> {
            p.requireFields("id", "revision", "name", "command", "group", "appendEnter", "tapAction", "isFavorite")
            val current = snippet(p.string("id"))
            p.requireRevision(current.updatedAtEpochMillis)
            require(p.keys.any { it !in setOf("id", "revision") })
            val edited = current.copy(
                name = p["name"]?.let { p.string("name") } ?: current.name,
                command = p["command"]?.let { p.string("command") } ?: current.command,
                group = if ("group" in p) p.string("group").ifEmpty { null } else current.group,
                appendEnter = p["appendEnter"]?.let { p.boolean("appendEnter") } ?: current.appendEnter,
                tapAction = p["tapAction"]?.let { SnippetTapAction.fromWireCode(p.string("tapAction")) } ?: current.tapAction,
                isFavorite = p["isFavorite"]?.let { p.boolean("isFavorite") } ?: current.isFavorite,
                updatedAtEpochMillis = nextControlRevision(current.updatedAtEpochMillis),
            )
            check(container.snippets.update(edited))
            edited.json()
        }
        "snippets.delete" -> {
            p.requireFields("id", "revision")
            val current = snippet(p.string("id"))
            p.requireRevision(current.updatedAtEpochMillis)
            check(container.snippets.delete(current.id))
            buildJsonObject { put("deleted", current.id) }
        }
        "keyboard.update" -> {
            p.requireFields("id", "revision", "actions", "layout", "modifierBehavior", "hapticFeedbackEnabled", "keyRepeatEnabled", "inputMode", "tmuxPrefix")
            val current = keyboard(p.string("id"))
            val edited = patchControlKeyboard(current, p)
            check(container.keyboardProfiles.update(edited))
            edited.json(container.settings.settings.first().defaultKeyboardProfileId)
        }
        "forwards.add", "forwards.remove" -> {
            val fields = arrayOf("connection", "revision")
            if (method == "forwards.add") p.requireFields(*fields, "direction", "bindAddress", "listenPort", "destinationHost", "destinationPort", "enabled")
            else p.requireFields(*fields, "index")
            val current = host(p.string("connection"))
            p.requireRevision(current.updatedAtEpochMillis)
            val rules = current.portForwards.toMutableList()
            if (method == "forwards.add") {
                val direction = p["direction"]?.let { p.string("direction") } ?: "local"
                require(direction in setOf("local", "remote"))
                val rule = PortForwardRule(
                    direction = if (direction == "local") PortForwardDirection.LOCAL else PortForwardDirection.REMOTE,
                    bindAddress = p["bindAddress"]?.let { p.string("bindAddress") } ?: "127.0.0.1",
                    listenPort = p.int("listenPort"), destinationPort = p.int("destinationPort"),
                    destinationHost = p["destinationHost"]?.let { p.string("destinationHost") } ?: "127.0.0.1",
                    enabled = p["enabled"]?.let { p.boolean("enabled") } ?: true,
                )
                // Local-only scope: broader exposure remains an explicit action in the app editor.
                require(rule.bindAddress in setOf("127.0.0.1", "::1"))
                if (rule !in rules) rules.add(rule)
            } else {
                val index = p.int("index")
                require(index in 1..rules.size)
                rules.removeAt(index - 1)
            }
            val edited = current.copy(portForwards = rules, updatedAtEpochMillis = nextControlRevision(current.updatedAtEpochMillis))
            check(container.hostProfiles.update(edited))
            edited.json()
        }
        else -> appearance.write(method, p)
    }

    private suspend fun snippet(id: String): Snippet = container.snippets.get(id) ?: missing()
    private suspend fun keyboard(id: String): KeyboardProfile = container.keyboardProfiles.get(id) ?: missing()
    private suspend fun host(id: String): HostProfile = container.hostProfiles.get(id) ?: missing()
    private fun missing(): Nothing = throw ControlFailure("NOT_FOUND", "The requested record no longer exists.")

    private companion object {
        val READ_METHODS = setOf("snippets.list", "snippets.get", "keyboard.list", "keyboard.get", "connections.list", "forwards.list") + AppearanceControl.READ_METHODS
        val METHODS = READ_METHODS + setOf("status", "capabilities", "snippets.add", "snippets.update", "snippets.delete", "keyboard.update", "forwards.add", "forwards.remove") + AppearanceControl.WRITE_METHODS
    }
}

internal fun patchControlKeyboard(current: KeyboardProfile, p: JsonObject): KeyboardProfile {
    p.requireRevision(current.updatedAtEpochMillis)
    require(p.keys.any { it !in setOf("id", "revision") })
    val actions = p["actions"]?.let { value ->
        require(value is JsonArray)
        value.map { item ->
            require(item is JsonPrimitive && item.isString)
            KeyboardAction.fromWireCode(item.content)
        }
    } ?: current.orderedActions
    return current.copy(
        orderedActions = actions,
        layout = p["layout"]?.let { KeyboardLayout.fromWireCode(p.string("layout")) } ?: current.layout,
        modifierBehavior = p["modifierBehavior"]?.let { ModifierBehavior.fromWireCode(p.string("modifierBehavior")) } ?: current.modifierBehavior,
        hapticFeedbackEnabled = p["hapticFeedbackEnabled"]?.let { p.boolean("hapticFeedbackEnabled") } ?: current.hapticFeedbackEnabled,
        keyRepeatEnabled = p["keyRepeatEnabled"]?.let { p.boolean("keyRepeatEnabled") } ?: current.keyRepeatEnabled,
        inputMode = p["inputMode"]?.let { TerminalInputMode.fromWireCode(p.string("inputMode")) } ?: current.inputMode,
        tmuxPrefix = p["tmuxPrefix"]?.let { p.string("tmuxPrefix") } ?: current.tmuxPrefix,
        updatedAtEpochMillis = nextControlRevision(current.updatedAtEpochMillis),
    )
}

private fun Snippet.json() = buildJsonObject {
    put("id", id); put("revision", updatedAtEpochMillis); put("name", name); put("command", command)
    put("group", group?.let(::JsonPrimitive) ?: JsonNull); put("appendEnter", appendEnter)
    put("tapAction", tapAction.wireCode); put("isFavorite", isFavorite); put("confirmMultilineExecution", confirmMultilineExecution)
    put("effect", "saved_only")
}

private fun KeyboardProfile.json(defaultId: String) = buildJsonObject {
    put("id", id); put("revision", updatedAtEpochMillis); put("name", name); put("isDefault", id == defaultId)
    put("actions", JsonArray(orderedActions.map { JsonPrimitive(it.wireCode) }))
    put("layout", layout.wireCode); put("modifierBehavior", modifierBehavior.wireCode)
    put("hapticFeedbackEnabled", hapticFeedbackEnabled); put("keyRepeatEnabled", keyRepeatEnabled)
    put("inputMode", inputMode.wireCode); put("tmuxPrefix", tmuxPrefix); put("effect", "profile_updated")
}

private fun HostProfile.json() = buildJsonObject {
    put("id", id); put("revision", updatedAtEpochMillis); put("name", displayName)
    put("hostname", hostname); put("port", port); put("username", username); put("protocol", protocol.wireCode)
    put("keyboardProfileId", keyboardProfileId?.let(::JsonPrimitive) ?: JsonNull)
    put("terminalProfileId", terminalProfileId?.let(::JsonPrimitive) ?: JsonNull)
    put("portForwards", JsonArray(portForwards.mapIndexed { index, rule -> buildJsonObject {
        put("index", index + 1); put("direction", rule.direction.name.lowercase()); put("bindAddress", rule.bindAddress)
        put("listenPort", rule.listenPort); put("destinationHost", rule.destinationHost)
        put("destinationPort", rule.destinationPort); put("enabled", rule.enabled)
    } }))
    put("effect", "next_connection")
}
