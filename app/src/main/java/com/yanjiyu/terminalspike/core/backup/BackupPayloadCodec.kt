package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import java.io.InputStream
import java.io.OutputStream

/** Bounded, deterministic, streaming codec for the decrypted `TSPPAY01` repository snapshot. */
class BackupPayloadCodec(
    private val maximumPayloadBytes: Long = BackupPayloadFormat.MAX_PAYLOAD_BYTES,
) {
    init {
        require(maximumPayloadBytes in 1..BackupPayloadFormat.MAX_PAYLOAD_BYTES) {
            "Configured payload limit is outside the reviewed range."
        }
    }

    /** Calculates the exact length required by the envelope without consuming portable secrets. */
    fun encodedSize(snapshot: BackupPayloadSnapshot): Long {
        snapshot.requireExportable()
        return BackupPayloadEncoder.measure(snapshot, maximumPayloadBytes)
    }

    /** Writes one canonical payload and wipes every secret owned by [snapshot] on every exit. */
    fun writeAndWipeSecrets(snapshot: BackupPayloadSnapshot, output: OutputStream): Long = try {
        snapshot.requireExportable()
        BackupPayloadEncoder.write(snapshot, output, maximumPayloadBytes)
    } finally {
        snapshot.wipeSecrets()
    }

    /** Reads through EOF without using `available()` or requiring a seekable/file-backed stream. */
    fun read(input: InputStream, expectedMode: BackupMode): BackupPayloadReadResult =
        BackupPayloadDecoder.read(input, expectedMode, maximumPayloadBytes)
}

internal object BackupPayloadWire {
    val MAGIC_V1: ByteArray = "TSPPAY01".toByteArray(Charsets.US_ASCII)
    const val REQUIRED_ID_MASK = 0x8000

    const val COLLECTION_HOSTS = 1
    const val COLLECTION_CREDENTIALS = 2
    const val COLLECTION_SSH_KEYS = 3
    const val COLLECTION_KNOWN_HOSTS = 4
    const val COLLECTION_SNIPPETS = 5
    const val COLLECTION_TERMINAL = 6
    const val COLLECTION_KEYBOARD = 7
    const val COLLECTION_GLOBAL_SETTINGS = 8
    const val COLLECTION_CUSTOM_FONTS = 9

    const val RECORD_PRIMARY = 1
    const val RECORD_TERMINAL_THEME = 2
    const val RECORD_SCHEMA_FIELD = 1
    const val RECORD_ID_FIELD = 2
    const val RECORD_SCHEMA_VERSION = 1
    const val TLV_PREFIX_BYTES = 6
    const val COLLECTION_COUNT_BYTES = 4
    const val RECORD_FRAME_PREFIX_BYTES = 6

    val REQUIRED_COLLECTIONS = (COLLECTION_HOSTS..COLLECTION_GLOBAL_SETTINGS).toSet()
    val KNOWN_COLLECTIONS = REQUIRED_COLLECTIONS + COLLECTION_CUSTOM_FONTS
}

private object BackupPayloadEncoder {
    fun measure(snapshot: BackupPayloadSnapshot, maximumBytes: Long): Long {
        var total = BackupPayloadWire.MAGIC_V1.size.toLong()
        sources(snapshot).forEach { collection ->
            val bodySize = collectionBodySize(collection)
            total = checkedAdd(total, BackupPayloadWire.TLV_PREFIX_BYTES + bodySize, maximumBytes)
        }
        return total
    }

    fun write(snapshot: BackupPayloadSnapshot, output: OutputStream, maximumBytes: Long): Long {
        val expectedSize = measure(snapshot, maximumBytes)
        val writer = PayloadOutput(output, maximumBytes)
        writer.write(BackupPayloadWire.MAGIC_V1)
        sources(snapshot).forEach { collection ->
            val bodySize = collectionBodySize(collection)
            writer.writeU16(collection.id)
            writer.writeU32(bodySize)
            writer.writeU32(collection.records.size.toLong())
            collection.records.forEach { source ->
                source.newPlan().use { plan ->
                    writer.writeU16(source.type)
                    writer.writeU32(plan.bodySize.toLong())
                    plan.writeTo(writer)
                }
            }
        }
        check(writer.bytesWritten == expectedSize) { "Payload size calculation diverged from encoding." }
        return writer.bytesWritten
    }

    private fun collectionBodySize(collection: PayloadCollectionSource): Long {
        var size = BackupPayloadWire.COLLECTION_COUNT_BYTES.toLong()
        collection.records.forEach { source ->
            source.newPlan().use { plan ->
                size = checkedAdd(
                    size,
                    BackupPayloadWire.RECORD_FRAME_PREFIX_BYTES + plan.bodySize.toLong(),
                    BackupPayloadFormat.MAX_PAYLOAD_BYTES,
                )
            }
        }
        return size
    }

    private fun sources(snapshot: BackupPayloadSnapshot): List<PayloadCollectionSource> = buildList {
        addAll(
            listOf(
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_HOSTS,
                    snapshot.hostProfiles.recordSources(::hostPlan),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_CREDENTIALS,
                    snapshot.credentials.recordSources(
                        id = { it.metadata.id },
                        plan = ::credentialPlan,
                    ),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_SSH_KEYS,
                    snapshot.sshKeys.recordSources(
                        id = { it.metadata.id },
                        plan = ::sshKeyPlan,
                    ),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_KNOWN_HOSTS,
                    snapshot.knownHosts.recordSources(::knownHostPlan),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_SNIPPETS,
                    snapshot.snippets.recordSources(::snippetPlan),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_TERMINAL,
                    buildList {
                        snapshot.terminalProfiles.forEach { profile ->
                            add(
                                PayloadRecordSource(
                                    BackupPayloadWire.RECORD_PRIMARY,
                                    profile.id,
                                ) { terminalPlan(profile) },
                            )
                        }
                        snapshot.terminalThemes.forEach { theme ->
                            add(
                                PayloadRecordSource(
                                    BackupPayloadWire.RECORD_TERMINAL_THEME,
                                    theme.id,
                                ) { themePlan(theme) },
                            )
                        }
                    }.sortedWith(compareBy(PayloadRecordSource::type, PayloadRecordSource::id)),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_KEYBOARD,
                    snapshot.keyboardProfiles.recordSources(::keyboardPlan),
                ),
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS,
                    listOf(
                        PayloadRecordSource(
                            BackupPayloadWire.RECORD_PRIMARY,
                            BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID,
                        ) { globalSettingsPlan(snapshot.globalSettings) },
                    ),
                ),
            ),
        )
        if (snapshot.customFonts.isNotEmpty()) {
            add(
                PayloadCollectionSource(
                    BackupPayloadWire.COLLECTION_CUSTOM_FONTS,
                    snapshot.customFonts.recordSources(
                        id = BackupCustomFont::recordId,
                        plan = ::customFontPlan,
                    ),
                ),
            )
        }
    }

    private fun <T> List<T>.recordSources(
        plan: (T) -> PayloadRecordPlan,
    ): List<PayloadRecordSource> where T : Any = recordSources(
        id = { record ->
            when (record) {
                is HostProfile -> record.id
                is KnownHost -> record.id
                is Snippet -> record.id
                is TerminalProfile -> record.id
                is KeyboardProfile -> record.id
                else -> error("Record ID mapping is missing.")
            }
        },
        plan = plan,
    )

    private fun <T> List<T>.recordSources(
        id: (T) -> String,
        plan: (T) -> PayloadRecordPlan,
    ): List<PayloadRecordSource> = map { record ->
        PayloadRecordSource(BackupPayloadWire.RECORD_PRIMARY, id(record)) { plan(record) }
    }.sortedBy(PayloadRecordSource::id)

    private fun hostPlan(host: HostProfile): PayloadRecordPlan = recordPlan(host.id) {
        string(10, host.displayName)
        string(11, host.hostname)
        u32(12, host.port)
        string(13, host.username)
        string(14, host.protocol.wireCode)
        optionalString(15, host.credentialId)
        optionalString(16, host.terminalProfileId)
        optionalString(17, host.keyboardProfileId)
        bool(18, host.isFavorite)
        optionalString(19, host.group)
        optionalString(20, host.tag)
        optionalString(21, host.startupCommand)
        host.keepaliveIntervalSeconds?.let { u32(22, it) }
        optionalString(23, host.reconnectPolicy?.wireCode)
        host.moshPort?.let { u32(24, it) }
        host.moshPortRange?.let { range ->
            u32(25, range.first)
            u32(26, range.last)
        }
        optionalString(27, host.moshServerCommand)
        u64(28, host.createdAtEpochMillis)
        u64(29, host.updatedAtEpochMillis)
        optionalString(30, host.moshLocale)
        if (host.moshFallbackPolicy != MoshFallbackPolicy.NEVER) {
            string(31, host.moshFallbackPolicy.wireCode)
        }
    }

    private fun credentialPlan(record: BackupCredentialRecord): PayloadRecordPlan =
        recordPlan(record.metadata.id) {
            val authentication = record.metadata.authentication
            string(10, record.metadata.displayName)
            string(11, record.metadata.kind.wireCode)
            optionalString(12, record.secretReferenceId)
            if (authentication is SshAuthentication.PrivateKey) {
                string(13, authentication.keyIdentityId)
            }
            record.portableSecret?.let { secret(14, it) }
            u64(15, record.metadata.createdAtEpochMillis)
            u64(16, record.metadata.updatedAtEpochMillis)
        }

    private fun sshKeyPlan(record: BackupSshKeyRecord): PayloadRecordPlan = recordPlan(record.metadata.id) {
        string(10, record.metadata.name)
        string(11, record.metadata.algorithm)
        string(12, record.metadata.publicKeyFingerprint)
        optionalString(13, record.metadata.publicKey)
        string(14, record.metadata.privateKeySecretReferenceId)
        string(15, record.metadata.origin.wireCode)
        bool(16, record.metadata.isPassphraseProtected)
        optionalString(17, record.metadata.comment)
        record.portablePrivateKey?.let { secret(18, it) }
        u64(19, record.metadata.createdAtEpochMillis)
        u64(20, record.metadata.updatedAtEpochMillis)
    }

    private fun knownHostPlan(host: KnownHost): PayloadRecordPlan = recordPlan(host.id) {
        string(10, host.host)
        u32(11, host.port)
        string(12, host.keyAlgorithm)
        string(13, host.fingerprint)
        string(14, host.publicHostKey)
        host.firstSeenAtEpochMillis?.let { u64(15, it) }
        host.lastSeenAtEpochMillis?.let { u64(16, it) }
    }

    private fun snippetPlan(snippet: Snippet): PayloadRecordPlan = recordPlan(snippet.id) {
        string(10, snippet.name)
        optionalString(11, snippet.group)
        string(12, snippet.command)
        string(13, snippet.tapAction.wireCode)
        bool(14, snippet.appendEnter)
        bool(15, snippet.confirmMultilineExecution)
        bool(16, snippet.isFavorite)
        u64(17, snippet.createdAtEpochMillis)
        u64(18, snippet.updatedAtEpochMillis)
    }

    private fun terminalPlan(profile: TerminalProfile): PayloadRecordPlan = recordPlan(profile.id) {
        string(10, profile.name)
        string(11, profile.themeId)
        string(12, profile.fontId)
        float(13, profile.fontSizeSp)
        float(14, profile.lineHeightMultiplier)
        float(15, profile.letterSpacingEm)
        string(16, profile.cursorStyle.wireCode)
        bool(17, profile.cursorBlinkEnabled)
        u32(18, profile.scrollbackLines)
        bool(19, profile.bell.visualBellEnabled)
        bool(20, profile.bell.vibrationBellEnabled)
        bool(21, profile.bell.audibleBellEnabled)
        string(22, profile.scroll.touchMode.wireCode)
        bool(23, profile.scroll.twoFingerLocalScrollOverride)
        bool(24, profile.scroll.jumpToBottomOnKeyboardInput)
        bool(25, profile.scroll.keepViewportPositionOnOutput)
        bool(26, profile.links.detectPlainTextUrls)
        bool(27, profile.links.osc8HyperlinksEnabled)
        string(28, profile.links.remoteClipboardMode.wireCode)
        string(29, profile.termValue)
        bool(30, profile.preserveAlternateScreenHistory)
        u64(31, profile.createdAtEpochMillis)
        u64(32, profile.updatedAtEpochMillis)
        bool(33, profile.boldRenderingEnabled)
        bool(34, profile.ligaturesEnabled)
        bool(35, profile.pinchZoomEnabled)
        bool(36, profile.links.copyOnSelection)
    }

    private fun themePlan(theme: BackupTerminalTheme): PayloadRecordPlan = recordPlan(theme.id) {
        string(10, theme.name)
        rawU32(11, theme.foregroundArgb)
        rawU32(12, theme.backgroundArgb)
        rawU32(13, theme.cursorArgb)
        rawU32(14, theme.selectionArgb)
        bytes(15, encodeColours(theme.ansi16Argb))
        u64(16, theme.createdAtEpochMillis)
        u64(17, theme.updatedAtEpochMillis)
        bool(18, theme.boldUsesBrightColours)
    }

    private fun customFontPlan(font: BackupCustomFont): PayloadRecordPlan = recordPlan(font.recordId) {
        string(10, font.fontId)
        string(11, font.displayName)
        bytes(12, font.copyBytes())
    }

    private fun keyboardPlan(profile: KeyboardProfile): PayloadRecordPlan = recordPlan(profile.id) {
        string(10, profile.name)
        bytes(11, encodeStrings(profile.orderedActions.map { it.wireCode }))
        string(12, profile.layout.wireCode)
        string(13, profile.modifierBehavior.wireCode)
        bool(14, profile.hapticFeedbackEnabled)
        bool(15, profile.keyRepeatEnabled)
        string(16, profile.inputMode.wireCode)
        string(17, profile.tmuxPrefix)
        u64(18, profile.createdAtEpochMillis)
        u64(19, profile.updatedAtEpochMillis)
    }

    private fun globalSettingsPlan(settings: BackupGlobalSettings): PayloadRecordPlan =
        recordPlan(BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID) {
            u8(10, settings.themeMode.wireValue)
            bool(11, settings.dynamicColorEnabled)
            optionalString(12, settings.accentPreset)
            optionalString(13, settings.defaultTerminalProfileId)
            optionalString(14, settings.defaultKeyboardProfileId)
            u32(15, settings.keepaliveIntervalSeconds)
            bool(16, settings.reconnectEnabled)
            u32(17, settings.reconnectMaxAttempts)
            bool(18, settings.backgroundSessionsEnabled)
            bool(19, settings.notificationPrivacyEnabled)
            bool(20, settings.disconnectNotificationsEnabled)
            bool(21, settings.reconnectNotificationsEnabled)
            bool(22, settings.keepCpuAwake)
            bool(23, settings.keepScreenOnWhileTerminalVisible)
            u8(24, settings.appLockMode.wireValue)
            u32(25, settings.appLockDelaySeconds)
            bool(26, settings.screenshotBlockingEnabled)
            u32(27, settings.sensitiveClipboardClearSeconds)
            string(28, settings.osc52Policy.wireCode)
            bool(29, settings.multilinePasteConfirmationEnabled)
            settings.lastBackupMode?.let { u8(30, it.wireValue) }
        }

    private fun recordPlan(id: String, fields: PayloadRecordPlanBuilder.() -> Unit): PayloadRecordPlan =
        PayloadRecordPlanBuilder().apply {
            u32(BackupPayloadWire.RECORD_SCHEMA_FIELD, BackupPayloadWire.RECORD_SCHEMA_VERSION)
            string(BackupPayloadWire.RECORD_ID_FIELD, id)
            fields()
        }.build()

    private fun encodeColours(colours: List<Int>): ByteArray = ByteArray(colours.size * 4).also { bytes ->
        colours.forEachIndexed { index, colour -> bytes.writeRawU32(index * 4, colour) }
    }

    private fun encodeStrings(values: List<String>): ByteArray {
        val encoded = values.map { it.utf8BytesOrThrow("ordered value") }
        try {
            val size = 4 + encoded.sumOf { 2 + it.size }
            return ByteArray(size).also { destination ->
                destination.writeU32(0, values.size.toLong())
                var offset = 4
                encoded.forEach { value ->
                    require(value.size <= 0xffff) { "Ordered value is too large." }
                    destination.writeU16(offset, value.size)
                    offset += 2
                    value.copyInto(destination, offset)
                    offset += value.size
                }
            }
        } finally {
            encoded.forEach { it.fill(0) }
        }
    }

    private fun checkedAdd(current: Long, additional: Long, maximum: Long): Long {
        val result = current + additional
        if (additional < 0 || result < current || result > maximum) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.TOTAL_BYTES)
        }
        return result
    }
}

private data class PayloadCollectionSource(
    val id: Int,
    val records: List<PayloadRecordSource>,
)

private data class PayloadRecordSource(
    val type: Int,
    val id: String,
    val newPlan: () -> PayloadRecordPlan,
)

internal class PayloadRecordPlanBuilder {
    private val fields = mutableListOf<PlannedPayloadField>()

    fun u8(id: Int, value: Int) = bytes(id, byteArrayOf(value.toByte()))

    fun bool(id: Int, value: Boolean) = u8(id, if (value) 1 else 0)

    fun u32(id: Int, value: Int) = bytes(id, ByteArray(4).also { it.writeU32(0, value.toLong()) })

    fun rawU32(id: Int, value: Int) = bytes(id, ByteArray(4).also { it.writeRawU32(0, value) })

    fun float(id: Int, value: Float) = rawU32(id, value.toRawBits())

    fun u64(id: Int, value: Long) = bytes(id, ByteArray(8).also { it.writeU64(0, value) })

    fun string(id: Int, value: String) = bytes(id, value.utf8BytesOrThrow("payload string"))

    fun optionalString(id: Int, value: String?) {
        value?.let { string(id, it) }
    }

    fun bytes(id: Int, value: ByteArray) {
        fields += PlannedPayloadField(id, OwnedPayloadValue(value))
    }

    fun secret(id: Int, value: PortableBackupSecret) {
        fields += PlannedPayloadField(id, SecretPayloadValue(value))
    }

    fun build(): PayloadRecordPlan {
        val sorted = fields.sortedBy(PlannedPayloadField::id)
        require(sorted.map { it.id }.distinct().size == sorted.size) { "Record fields must be unique." }
        return PayloadRecordPlan(sorted)
    }
}

internal class PayloadRecordPlan(
    private val fields: List<PlannedPayloadField>,
) : AutoCloseable {
    val bodySize: Int = fields.fold(0L) { size, field ->
        size + BackupPayloadWire.TLV_PREFIX_BYTES + field.value.size
    }.also { size ->
        if (size > BackupPayloadFormat.MAX_RECORD_BYTES) {
            close()
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_BYTES)
        }
    }.toInt()

    fun writeTo(output: PayloadOutput) {
        val body = ByteArray(bodySize)
        try {
            var offset = 0
            fields.forEach { field ->
                body.writeU16(offset, field.id)
                body.writeU32(offset + 2, field.value.size.toLong())
                offset += BackupPayloadWire.TLV_PREFIX_BYTES
                field.value.copyInto(body, offset)
                offset += field.value.size
            }
            output.write(body)
        } finally {
            body.fill(0)
        }
    }

    override fun close() = fields.forEach { it.value.close() }
}

internal data class PlannedPayloadField(val id: Int, val value: PlannedPayloadValue)

internal sealed interface PlannedPayloadValue : AutoCloseable {
    val size: Int
    fun copyInto(destination: ByteArray, offset: Int)
}

private class OwnedPayloadValue(private val bytes: ByteArray) : PlannedPayloadValue {
    override val size: Int
        get() = bytes.size

    override fun copyInto(destination: ByteArray, offset: Int) {
        bytes.copyInto(destination, offset)
    }

    override fun close() = bytes.fill(0)
}

private class SecretPayloadValue(private val secret: PortableBackupSecret) : PlannedPayloadValue {
    override val size: Int
        get() = secret.size

    override fun copyInto(destination: ByteArray, offset: Int) {
        secret.withBytes { bytes -> bytes.copyInto(destination, offset) }
    }

    override fun close() = Unit
}

internal class PayloadOutput(
    private val output: OutputStream,
    private val maximumBytes: Long,
) {
    var bytesWritten: Long = 0
        private set

    fun write(bytes: ByteArray) {
        ensureCapacity(bytes.size.toLong())
        output.write(bytes)
        bytesWritten += bytes.size
    }

    fun writeU16(value: Int) = write(ByteArray(2).also { it.writeU16(0, value) })

    fun writeU32(value: Long) = write(ByteArray(4).also { it.writeU32(0, value) })

    private fun ensureCapacity(additional: Long) {
        if (additional < 0 || bytesWritten + additional < bytesWritten || bytesWritten + additional > maximumBytes) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.TOTAL_BYTES)
        }
    }
}

internal fun ByteArray.writeU16(offset: Int, value: Int) {
    require(value in 0..0xffff) { "Value does not fit u16." }
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.writeU32(offset: Int, value: Long) {
    require(value in 0..0xffff_ffffL) { "Value does not fit u32." }
    repeat(4) { index -> this[offset + index] = (value ushr (24 - index * 8)).toByte() }
}

internal fun ByteArray.writeRawU32(offset: Int, value: Int) {
    repeat(4) { index -> this[offset + index] = (value ushr (24 - index * 8)).toByte() }
}

internal fun ByteArray.writeU64(offset: Int, value: Long) {
    require(value >= 0) { "Value does not fit the supported u64 range." }
    repeat(8) { index -> this[offset + index] = (value ushr (56 - index * 8)).toByte() }
}
