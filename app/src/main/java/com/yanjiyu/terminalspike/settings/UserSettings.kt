package com.yanjiyu.terminalspike.settings

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class SavedHostConnectionCompatibility {
    SSH_PASSWORD,
    /** Password bootstrap is supported when the separately verified extension is available. */
    MOSH_PASSWORD,
    MOSH_UNAVAILABLE,
    PRIVATE_KEY_REQUIRES_FULL_UI,
    KEYBOARD_INTERACTIVE_UNAVAILABLE,
    PROFILE_OPTIONS_REQUIRE_FULL_UI,
}

data class SavedSshProfile(
    val id: Long,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val hasSavedPassword: Boolean = false,
    /** Prevents the compatibility UI from silently changing protocol or authentication type. */
    val connectionCompatibility: SavedHostConnectionCompatibility =
        SavedHostConnectionCompatibility.SSH_PASSWORD,
    /** Stable Room identity used by session/recent presentation; never serialized by the legacy codec. */
    val persistentId: String? = null,
    /** Room-backed favourite state presented as a pinned Workspace host. */
    val isFavorite: Boolean = false,
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val moshPort: Int? = null,
    val moshPortRange: MoshPortRange? = null,
    val moshServerCommand: String? = null,
) {
    val canConnectFromCompatibilityUi: Boolean
        get() = connectionCompatibility == SavedHostConnectionCompatibility.SSH_PASSWORD ||
            connectionCompatibility == SavedHostConnectionCompatibility.MOSH_PASSWORD
}

data class CommandSnippet(
    val id: Long,
    val label: String,
    val command: String,
    val appendEnter: Boolean,
    /** Authoritative Room policy; legacy codec rows default to safe multiline confirmation. */
    val confirmMultilineExecution: Boolean = true,
    /** False for Room INSERT snippets until the compatibility terminal gains an insert surface. */
    val sendsImmediately: Boolean = true,
)

data class SavedSshIdentity(
    val id: Long,
    val label: String,
    val keyType: String,
    val fingerprint: String,
    val passphraseRequired: Boolean,
    /** False when legacy metadata was preserved but its encrypted private payload could not migrate. */
    val isAvailable: Boolean = true,
    /** Stable, non-secret Room UUID used only to target recovery across Activity/process recreation. */
    val recoveryToken: String? = null,
)

data class UserSettings(
    val profiles: List<SavedSshProfile> = emptyList(),
    val snippets: List<CommandSnippet> = emptyList(),
    val extraKeys: List<TerminalExtraKey> = TerminalExtraKey.DEFAULT_ORDER,
    val identities: List<SavedSshIdentity> = emptyList(),
    /** False when the authoritative keyboard profile needs runtime semantics not yet carried here. */
    val keyboardRuntimeCompatible: Boolean = true,
) {
    companion object {
        const val MAX_PROFILES = 20
        const val MAX_SNIPPETS = 40
        const val MAX_IDENTITIES = 10
        const val MAX_LABEL_LENGTH = 48
        const val MAX_HOST_LENGTH = 253
        const val MAX_USERNAME_LENGTH = 64
        const val MAX_SNIPPET_LENGTH = 4_096
        const val MAX_KEY_TYPE_LENGTH = 32
        const val MAX_FINGERPRINT_LENGTH = 128
    }
}

internal fun UserSettings.nextAvailableSettingsId(): Long {
    val maximumId = sequenceOf(
        profiles.asSequence().map(SavedSshProfile::id),
        snippets.asSequence().map(CommandSnippet::id),
        identities.asSequence().map(SavedSshIdentity::id),
    ).flatten().maxOrNull() ?: 0L
    require(maximumId < Long.MAX_VALUE) { "Settings IDs are exhausted." }
    return maximumId + 1L
}

internal fun SavedSshProfile.hostPreservingLegacyPasswordScope(
    editedHost: String,
    editedPort: Int,
    editedUsername: String,
): String = if (
    hasSavedPassword &&
    host.equals(editedHost, ignoreCase = true) &&
    port == editedPort &&
    username == editedUsername
) {
    host
} else {
    editedHost
}

internal object UserSettingsCodec {
    private const val MAGIC = 0x54535031
    private const val VERSION = 3

    fun write(settings: UserSettings, output: OutputStream) {
        validate(settings)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(settings.profiles.size)
            settings.profiles.forEach { profile ->
                data.writeLong(profile.id)
                data.writeUTF(profile.label)
                data.writeUTF(profile.host)
                data.writeInt(profile.port)
                data.writeUTF(profile.username)
                data.writeBoolean(profile.hasSavedPassword)
            }
            data.writeInt(settings.snippets.size)
            settings.snippets.forEach { snippet ->
                data.writeLong(snippet.id)
                data.writeUTF(snippet.label)
                data.writeUTF(snippet.command)
                data.writeBoolean(snippet.appendEnter)
            }
            data.writeInt(settings.extraKeys.size)
            settings.extraKeys.forEach { data.writeUTF(it.name) }
            data.writeInt(settings.identities.size)
            settings.identities.forEach { identity ->
                data.writeLong(identity.id)
                data.writeUTF(identity.label)
                data.writeUTF(identity.keyType)
                data.writeUTF(identity.fingerprint)
                data.writeBoolean(identity.passphraseRequired)
            }
        }
    }

    fun read(input: InputStream): UserSettings = DataInputStream(input).use { data ->
        require(data.readInt() == MAGIC) { "Invalid settings header." }
        val version = data.readInt()
        require(version in 1..VERSION) { "Unsupported settings version." }
        val profileCount = data.readBoundedCount(UserSettings.MAX_PROFILES)
        val profiles = List(profileCount) {
            SavedSshProfile(
                id = data.readLong(),
                label = data.readUTF().requireLength(1, UserSettings.MAX_LABEL_LENGTH),
                host = data.readUTF().requireHost(),
                port = data.readInt().also { require(it in 1..65_535) },
                username = data.readUTF().requireUsername(),
                hasSavedPassword = version >= 3 && data.readBoolean(),
            )
        }
        val snippetCount = data.readBoundedCount(UserSettings.MAX_SNIPPETS)
        val snippets = List(snippetCount) {
            CommandSnippet(
                id = data.readLong(),
                label = data.readUTF().requireLength(1, UserSettings.MAX_LABEL_LENGTH),
                command = data.readUTF().requireSnippet(),
                appendEnter = data.readBoolean(),
            )
        }
        val keyCount = data.readBoundedCount(TerminalExtraKey.entries.size)
        val keys = List(keyCount) { TerminalExtraKey.valueOf(data.readUTF()) }
        val identities = if (version >= 2) {
            val identityCount = data.readBoundedCount(UserSettings.MAX_IDENTITIES)
            List(identityCount) {
                SavedSshIdentity(
                    id = data.readLong(),
                    label = data.readUTF().requireLength(1, UserSettings.MAX_LABEL_LENGTH),
                    keyType = data.readUTF().requireLength(1, UserSettings.MAX_KEY_TYPE_LENGTH),
                    fingerprint = data.readUTF().requireLength(1, UserSettings.MAX_FINGERPRINT_LENGTH),
                    passphraseRequired = data.readBoolean(),
                )
            }
        } else {
            emptyList()
        }
        require(keys.isNotEmpty() && keys.distinct().size == keys.size) { "Invalid extra-key layout." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile ID." }
        require(snippets.map { it.id }.distinct().size == snippets.size) { "Duplicate snippet ID." }
        require(identities.map { it.id }.distinct().size == identities.size) { "Duplicate identity ID." }
        UserSettings(
            profiles = profiles,
            snippets = snippets,
            extraKeys = keys,
            identities = identities,
        ).also(::validate)
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int =
        readInt().also { require(it in 0..maximum) { "Invalid settings item count." } }

    private fun String.requireLength(minimum: Int, maximum: Int): String =
        also { require(length in minimum..maximum && none(Char::isISOControl)) { "Invalid settings text." } }

    private fun String.requireHost(): String =
        requireLength(1, UserSettings.MAX_HOST_LENGTH).also {
            require(it.none(Char::isWhitespace)) { "Invalid saved host." }
        }

    private fun String.requireUsername(): String =
        requireLength(1, UserSettings.MAX_USERNAME_LENGTH).also {
            require(it.none(Char::isWhitespace)) { "Invalid saved username." }
        }

    private fun String.requireSnippet(): String = also {
        require(length in 1..UserSettings.MAX_SNIPPET_LENGTH) { "Invalid snippet length." }
        require(none { character -> character.isISOControl() && character !in "\r\n\t" }) {
            "Invalid snippet text."
        }
    }

    private fun validate(settings: UserSettings) {
        require(settings.profiles.size <= UserSettings.MAX_PROFILES) { "Too many profiles." }
        require(settings.snippets.size <= UserSettings.MAX_SNIPPETS) { "Too many snippets." }
        require(settings.identities.size <= UserSettings.MAX_IDENTITIES) { "Too many SSH identities." }
        require(settings.profiles.map { it.id }.distinct().size == settings.profiles.size) {
            "Duplicate profile ID."
        }
        require(settings.snippets.map { it.id }.distinct().size == settings.snippets.size) {
            "Duplicate snippet ID."
        }
        require(settings.identities.map { it.id }.distinct().size == settings.identities.size) {
            "Duplicate identity ID."
        }
        settings.profiles.forEach { profile ->
            require(profile.id > 0) { "Invalid profile ID." }
            profile.label.requireLength(1, UserSettings.MAX_LABEL_LENGTH)
            profile.host.requireHost()
            require(profile.port in 1..65_535) { "Invalid profile port." }
            profile.username.requireUsername()
        }
        settings.snippets.forEach { snippet ->
            require(snippet.id > 0) { "Invalid snippet ID." }
            snippet.label.requireLength(1, UserSettings.MAX_LABEL_LENGTH)
            snippet.command.requireSnippet()
        }
        settings.identities.forEach { identity ->
            require(identity.id > 0) { "Invalid identity ID." }
            identity.label.requireLength(1, UserSettings.MAX_LABEL_LENGTH)
            identity.keyType.requireLength(1, UserSettings.MAX_KEY_TYPE_LENGTH)
            identity.fingerprint.requireLength(1, UserSettings.MAX_FINGERPRINT_LENGTH)
        }
        require(
            settings.extraKeys.isNotEmpty() &&
                settings.extraKeys.size <= TerminalExtraKey.entries.size &&
                settings.extraKeys.distinct().size == settings.extraKeys.size,
        ) { "Invalid extra-key layout." }
    }
}
