package com.yanjiyu.terminalspike.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence models deliberately use stable string wire codes instead of Kotlin enum names.
 * Domain validation owns bounds and kind-dependent combinations before these rows are written.
 */
@Entity(
    tableName = "terminal_profiles",
    indices = [Index(value = ["name"])],
)
data class TerminalProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "theme_id")
    val themeId: String,
    @ColumnInfo(name = "font_id")
    val fontId: String,
    @ColumnInfo(name = "font_size_sp")
    val fontSizeSp: Float,
    @ColumnInfo(name = "line_height_multiplier")
    val lineHeightMultiplier: Float,
    @ColumnInfo(name = "letter_spacing_em")
    val letterSpacingEm: Float,
    @ColumnInfo(name = "cursor_style_code")
    val cursorStyleCode: String,
    @ColumnInfo(name = "cursor_blink")
    val cursorBlink: Boolean,
    @ColumnInfo(name = "scrollback_lines")
    val scrollbackLines: Int,
    @ColumnInfo(name = "visual_bell_enabled")
    val visualBellEnabled: Boolean,
    @ColumnInfo(name = "vibration_bell_enabled")
    val vibrationBellEnabled: Boolean,
    @ColumnInfo(name = "audible_bell_enabled")
    val audibleBellEnabled: Boolean,
    @ColumnInfo(name = "touch_scroll_mode_code")
    val touchScrollModeCode: String,
    @ColumnInfo(name = "two_finger_local_scroll_override")
    val twoFingerLocalScrollOverride: Boolean,
    @ColumnInfo(name = "jump_to_bottom_on_keyboard_input")
    val jumpToBottomOnKeyboardInput: Boolean,
    @ColumnInfo(name = "keep_viewport_position_on_output")
    val keepViewportPositionOnOutput: Boolean,
    @ColumnInfo(name = "detect_plain_text_urls")
    val detectPlainTextUrls: Boolean,
    @ColumnInfo(name = "osc8_hyperlinks_enabled")
    val osc8HyperlinksEnabled: Boolean,
    @ColumnInfo(name = "remote_clipboard_mode_code")
    val remoteClipboardModeCode: String,
    @ColumnInfo(name = "term_type")
    val termType: String,
    @ColumnInfo(name = "retain_alternate_screen_history")
    val retainAlternateScreenHistory: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "bold_rendering_enabled", defaultValue = "1")
    val boldRenderingEnabled: Boolean = true,
    @ColumnInfo(name = "ligatures_enabled", defaultValue = "0")
    val ligaturesEnabled: Boolean = false,
    @ColumnInfo(name = "pinch_zoom_enabled", defaultValue = "1")
    val pinchZoomEnabled: Boolean = true,
    @ColumnInfo(name = "copy_on_selection", defaultValue = "0")
    val copyOnSelection: Boolean = false,
)

@Entity(
    tableName = "custom_terminal_themes",
    indices = [Index(value = ["name"])],
)
data class CustomTerminalThemeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "foreground_argb")
    val foregroundArgb: Int,
    @ColumnInfo(name = "background_argb")
    val backgroundArgb: Int,
    @ColumnInfo(name = "cursor_argb")
    val cursorArgb: Int,
    @ColumnInfo(name = "selection_argb")
    val selectionArgb: Int,
    @ColumnInfo(name = "ansi_0_argb")
    val ansi0Argb: Int,
    @ColumnInfo(name = "ansi_1_argb")
    val ansi1Argb: Int,
    @ColumnInfo(name = "ansi_2_argb")
    val ansi2Argb: Int,
    @ColumnInfo(name = "ansi_3_argb")
    val ansi3Argb: Int,
    @ColumnInfo(name = "ansi_4_argb")
    val ansi4Argb: Int,
    @ColumnInfo(name = "ansi_5_argb")
    val ansi5Argb: Int,
    @ColumnInfo(name = "ansi_6_argb")
    val ansi6Argb: Int,
    @ColumnInfo(name = "ansi_7_argb")
    val ansi7Argb: Int,
    @ColumnInfo(name = "ansi_8_argb")
    val ansi8Argb: Int,
    @ColumnInfo(name = "ansi_9_argb")
    val ansi9Argb: Int,
    @ColumnInfo(name = "ansi_10_argb")
    val ansi10Argb: Int,
    @ColumnInfo(name = "ansi_11_argb")
    val ansi11Argb: Int,
    @ColumnInfo(name = "ansi_12_argb")
    val ansi12Argb: Int,
    @ColumnInfo(name = "ansi_13_argb")
    val ansi13Argb: Int,
    @ColumnInfo(name = "ansi_14_argb")
    val ansi14Argb: Int,
    @ColumnInfo(name = "ansi_15_argb")
    val ansi15Argb: Int,
    @ColumnInfo(name = "bold_uses_bright_colours", defaultValue = "1")
    val boldUsesBrightColours: Boolean = true,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "keyboard_profiles",
    indices = [Index(value = ["name"])],
)
data class KeyboardProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "row_count")
    val rowCount: Int,
    @ColumnInfo(name = "modifier_policy_code")
    val modifierPolicyCode: String,
    @ColumnInfo(name = "haptic_enabled")
    val hapticEnabled: Boolean,
    @ColumnInfo(name = "key_repeat_enabled")
    val keyRepeatEnabled: Boolean,
    @ColumnInfo(name = "input_mode_code")
    val inputModeCode: String,
    @ColumnInfo(name = "tmux_prefix")
    val tmuxPrefix: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "keyboard_profile_keys",
    primaryKeys = ["profile_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = KeyboardProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["profile_id", "action_code"], unique = true),
    ],
)
data class KeyboardProfileKeyEntity(
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "action_code")
    val actionCode: String,
)

@Entity(
    tableName = "encrypted_secrets",
    indices = [
        Index(value = ["state_code"]),
        Index(value = ["legacy_id"]),
    ],
)
data class EncryptedSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "kind_code")
    val kindCode: String,
    @ColumnInfo(name = "envelope_version")
    val envelopeVersion: Int,
    @ColumnInfo(name = "key_version")
    val keyVersion: Int,
    @ColumnInfo(name = "nonce")
    val nonce: ByteArray?,
    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray?,
    @ColumnInfo(name = "state_code")
    val stateCode: String,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "legacy_id")
    val legacyId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "ssh_key_identities",
    foreignKeys = [
        ForeignKey(
            entity = EncryptedSecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["private_secret_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["private_secret_id"], unique = true),
        Index(value = ["fingerprint"]),
    ],
)
data class SshKeyIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "algorithm_code")
    val algorithmCode: String,
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray?,
    @ColumnInfo(name = "provenance_code")
    val provenanceCode: String,
    @ColumnInfo(name = "is_passphrase_protected")
    val isPassphraseProtected: Boolean,
    @ColumnInfo(name = "comment")
    val comment: String?,
    @ColumnInfo(name = "private_secret_id")
    val privateSecretId: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "ssh_credentials",
    foreignKeys = [
        ForeignKey(
            entity = EncryptedSecretEntity::class,
            parentColumns = ["id"],
            childColumns = ["secret_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SshKeyIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["key_identity_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // SQLite permits multiple NULL values in a UNIQUE index, so prompt-only credentials remain
        // valid while one encrypted payload can never be rebound to two credential owners.
        Index(value = ["secret_id"], unique = true),
        Index(value = ["key_identity_id"]),
        Index(value = ["kind_code"]),
    ],
)
data class SshCredentialEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "kind_code")
    val kindCode: String,
    @ColumnInfo(name = "secret_id")
    val secretId: String?,
    @ColumnInfo(name = "key_identity_id")
    val keyIdentityId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "host_profiles",
    foreignKeys = [
        ForeignKey(
            entity = SshCredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credential_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TerminalProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["terminal_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = KeyboardProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyboard_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["credential_id"]),
        Index(value = ["terminal_profile_id"]),
        Index(value = ["keyboard_profile_id"]),
        Index(value = ["is_favorite"]),
        Index(value = ["group_name"]),
    ],
)
data class HostProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "hostname")
    val hostname: String,
    @ColumnInfo(name = "port")
    val port: Int,
    @ColumnInfo(name = "username")
    val username: String,
    @ColumnInfo(name = "protocol_code")
    val protocolCode: String,
    @ColumnInfo(name = "credential_id")
    val credentialId: String?,
    @ColumnInfo(name = "terminal_profile_id")
    val terminalProfileId: String?,
    @ColumnInfo(name = "keyboard_profile_id")
    val keyboardProfileId: String?,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "group_name")
    val groupName: String?,
    @ColumnInfo(name = "tag")
    val tag: String?,
    @ColumnInfo(name = "startup_command")
    val startupCommand: String?,
    @ColumnInfo(name = "keepalive_interval_seconds")
    val keepaliveIntervalSeconds: Int?,
    @ColumnInfo(name = "reconnect_policy_code")
    val reconnectPolicyCode: String?,
    @ColumnInfo(name = "mosh_port_start")
    val moshPortStart: Int?,
    @ColumnInfo(name = "mosh_port_end")
    val moshPortEnd: Int?,
    @ColumnInfo(name = "mosh_server_command")
    val moshServerCommand: String?,
    @ColumnInfo(name = "mosh_locale")
    val moshLocale: String? = null,
    @ColumnInfo(name = "mosh_fallback_policy_code", defaultValue = "'never'")
    val moshFallbackPolicyCode: String = "never",
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "recent_sessions",
    foreignKeys = [
        ForeignKey(
            entity = HostProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["host_profile_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["host_profile_id"]),
        Index(value = ["state_code"]),
        Index(value = ["last_activity_at_epoch_millis"]),
        Index(value = ["endpoint_identity_token"]),
    ],
)
data class RecentSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** Null after its host is removed; the display-safe snapshot remains available. */
    @ColumnInfo(name = "host_profile_id")
    val hostProfileId: String?,
    @ColumnInfo(name = "host_display_name")
    val hostDisplayName: String,
    @ColumnInfo(name = "protocol_code")
    val protocolCode: String,
    @ColumnInfo(name = "state_code")
    val stateCode: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "last_activity_at_epoch_millis")
    val lastActivityAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at_epoch_millis")
    val endedAtEpochMillis: Long?,
    @ColumnInfo(name = "terminal_title")
    val terminalTitle: String?,
    @ColumnInfo(name = "endpoint_identity_token")
    val endpointIdentityToken: String? = null,
)

@Entity(
    tableName = "known_hosts",
    indices = [
        Index(value = ["host", "port", "algorithm_code"], unique = true),
        Index(value = ["last_seen_at_epoch_millis"]),
    ],
)
data class KnownHostEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "host")
    val host: String,
    @ColumnInfo(name = "port")
    val port: Int,
    @ColumnInfo(name = "algorithm_code")
    val algorithmCode: String,
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    @ColumnInfo(name = "public_key")
    val publicKey: ByteArray,
    @ColumnInfo(name = "first_seen_at_epoch_millis")
    val firstSeenAtEpochMillis: Long?,
    @ColumnInfo(name = "last_seen_at_epoch_millis")
    val lastSeenAtEpochMillis: Long?,
)

@Entity(
    tableName = "snippets",
    indices = [
        Index(value = ["group_name"]),
        Index(value = ["is_favorite"]),
        Index(value = ["updated_at_epoch_millis"]),
    ],
)
data class SnippetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "group_name")
    val groupName: String?,
    @ColumnInfo(name = "command")
    val command: String,
    @ColumnInfo(name = "action_code")
    val actionCode: String,
    @ColumnInfo(name = "append_enter")
    val appendEnter: Boolean,
    @ColumnInfo(name = "confirm_multiline")
    val confirmMultiline: Boolean,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "legacy_migration_state",
    indices = [Index(value = ["state_code"])],
)
data class LegacyMigrationStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_code")
    val sourceCode: String,
    @ColumnInfo(name = "source_digest_sha256")
    val sourceDigestSha256: String?,
    @ColumnInfo(name = "source_version")
    val sourceVersion: Int?,
    @ColumnInfo(name = "state_code")
    val stateCode: String,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
    @ColumnInfo(name = "warning_codes")
    val warningCodes: String,
    @ColumnInfo(name = "last_attempt_at_epoch_millis")
    val lastAttemptAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?,
) {
    /**
     * A terminal marker makes Room authoritative for this source. It is deliberately independent
     * of whether a retained legacy file still exists, so a later file cannot resurrect data.
     */
    fun isAuthoritativeTerminal(): Boolean =
        completedAtEpochMillis != null &&
            errorCode == null &&
            when (stateCode) {
                STATE_COMPLETE,
                STATE_COMPLETE_WITH_WARNINGS -> sourceDigestSha256 != null
                STATE_ABSENT,
                STATE_DISCARDED_AFTER_RECOVERY -> sourceDigestSha256 == null &&
                    sourceVersion == null &&
                    warningCodes.isEmpty()
                else -> false
            }

    fun isRetryableAttempt(): Boolean =
        completedAtEpochMillis == null &&
            when (stateCode) {
                STATE_PENDING -> errorCode == null
                STATE_BLOCKED -> errorCode != null
                else -> false
            }

    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_BLOCKED = "blocked"
        const val STATE_COMPLETE = "complete"
        const val STATE_COMPLETE_WITH_WARNINGS = "complete_with_warnings"
        const val STATE_ABSENT = "absent"
        const val STATE_DISCARDED_AFTER_RECOVERY = "discarded_after_recovery"
    }
}
