package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPayloadCodecTest {
    private val codec = BackupPayloadCodec()

    @Test
    fun standardRoundTripIsCanonicalDeterministicAndStreaming() {
        val ordered = standardSnapshot(reverse = false)
        val reversed = standardSnapshot(reverse = true)
        val firstOutput = FragmentingOutputStream(maximumChunk = 7)
        val expectedSize = codec.encodedSize(ordered)

        val firstWritten = codec.writeAndWipeSecrets(ordered, firstOutput)
        val second = encode(reversed)
        val first = firstOutput.toByteArray()

        assertEquals(expectedSize, firstWritten)
        assertEquals(expectedSize, first.size.toLong())
        assertArrayEquals("TSPPAY01".toByteArray(Charsets.US_ASCII), first.copyOfRange(0, 8))
        assertArrayEquals(first, second)

        val result = codec.read(NoAvailableFragmentedInputStream(first, maximumChunk = 3), BackupMode.STANDARD)
        result.snapshot.use { restored ->
            assertSnapshotEquals(standardSnapshot(), restored)
            assertEquals(BackupPayloadCompatibilityReport(), result.compatibility)
            assertTrue(restored.credentials.all { it.portableSecret == null })
            assertTrue(restored.sshKeys.all { it.portablePrivateKey == null })
        }
    }

    @Test
    fun olderThemeAndProfileRecordsUseSafeDefaultsForNewOptionalFields() {
        var payload = encode(standardSnapshot())
        listOf(36, 35, 34, 33).forEach { fieldId ->
            payload = removeField(
                payload,
                BackupPayloadWire.COLLECTION_TERMINAL,
                recordIndex = 0,
                fieldId = fieldId,
            )
        }
        payload = removeField(
            payload,
            BackupPayloadWire.COLLECTION_TERMINAL,
            recordIndex = 1,
            fieldId = 18,
        )

        codec.read(ByteArrayInputStream(payload), BackupMode.STANDARD).snapshot.use { restored ->
            val profile = restored.terminalProfiles.single()
            assertTrue(profile.boldRenderingEnabled)
            assertFalse(profile.ligaturesEnabled)
            assertTrue(profile.pinchZoomEnabled)
            assertFalse(profile.links.copyOnSelection)
            assertTrue(restored.terminalThemes.single().boldUsesBrightColours)
        }
    }

    @Test
    fun explicitlyIncludedCustomFontRoundTripsAsOptionalContentAddressedCollection() {
        val bytes = ByteArray(2_048) { index -> (index * 31).toByte() }
        val fontId = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val snapshot = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            terminalProfiles = listOf(terminalProfile().copy(fontId = fontId)),
            terminalThemes = listOf(terminalTheme()),
            customFonts = listOf(BackupCustomFont.copyOf(fontId, "Portable Mono", bytes)),
        )

        val restored = codec.read(ByteArrayInputStream(encode(snapshot)), BackupMode.STANDARD).snapshot

        assertEquals(fontId, restored.terminalProfiles.single().fontId)
        assertEquals("Portable Mono", restored.customFonts.single().displayName)
        assertArrayEquals(bytes, restored.customFonts.single().copyBytes())
    }

    @Test
    fun fullRoundTripCopiesExplicitSecretsAndWipesEveryOwner() {
        val passwordSource = byteArrayOf(0x70, 0x40, 0x73, 0x73)
        val passphraseSource = byteArrayOf(0x6b, 0x65, 0x79, 0x21)
        val privateKeySource = ByteArray(128) { index -> (index xor 0x5a).toByte() }
        val password = PortableBackupSecret.copyAndWipe(passwordSource)
        val passphrase = PortableBackupSecret.copyAndWipe(passphraseSource)
        val privateKey = PortableBackupSecret.copyAndWipe(privateKeySource)
        val passwordBacking = password.borrowForTest()
        val passphraseBacking = passphrase.borrowForTest()
        val privateKeyBacking = privateKey.borrowForTest()
        val snapshot = fullSnapshot(password, passphrase, privateKey)

        assertAllZero(passwordSource)
        assertAllZero(passphraseSource)
        assertAllZero(privateKeySource)
        assertFalse(password.isWiped)
        val expectedSize = codec.encodedSize(snapshot)

        val output = FragmentingOutputStream(maximumChunk = 11)
        assertEquals(expectedSize, codec.writeAndWipeSecrets(snapshot, output))

        assertTrue(password.isWiped)
        assertTrue(passphrase.isWiped)
        assertTrue(privateKey.isWiped)
        assertAllZero(passwordBacking)
        assertAllZero(passphraseBacking)
        assertAllZero(privateKeyBacking)

        val result = codec.read(NoAvailableFragmentedInputStream(output.toByteArray(), 2), BackupMode.FULL)
        val restored = result.snapshot
        val decodedPassword = restored.credentials.single { it.metadata.id == PASSWORD_CREDENTIAL_ID }
            .portableSecret ?: error("Password secret missing")
        val decodedPassphrase = restored.credentials.single { it.metadata.id == KEY_CREDENTIAL_ID }
            .portableSecret ?: error("Passphrase secret missing")
        val decodedPrivateKey = restored.sshKeys.single().portablePrivateKey ?: error("Private key missing")
        assertArrayEquals(byteArrayOf(0x70, 0x40, 0x73, 0x73), decodedPassword.copyForTest())
        assertArrayEquals(byteArrayOf(0x6b, 0x65, 0x79, 0x21), decodedPassphrase.copyForTest())
        assertArrayEquals(ByteArray(128) { index -> (index xor 0x5a).toByte() }, decodedPrivateKey.copyForTest())
        val decodedBackings = listOf(
            decodedPassword.borrowForTest(),
            decodedPassphrase.borrowForTest(),
            decodedPrivateKey.borrowForTest(),
        )

        restored.close()

        assertTrue(decodedPassword.isWiped)
        assertTrue(decodedPassphrase.isWiped)
        assertTrue(decodedPrivateKey.isWiped)
        decodedBackings.forEach(::assertAllZero)
    }

    @Test
    fun standardModeRejectsPortableContentAndModelExcludesEphemeralCategories() {
        val secret = PortableBackupSecret.copyAndWipe(byteArrayOf(1, 2, 3))
        val record = BackupCredentialRecord(passwordCredential(), secret)

        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadSnapshot(mode = BackupMode.STANDARD, credentials = listOf(record))
        }

        assertTrue(error.message.orEmpty().contains("Standard backups"))
        assertTrue(secret.isWiped)
        val snapshotFieldNames = BackupPayloadSnapshot::class.java.declaredFields
            .joinToString(" ") { it.name.lowercase() }
        listOf("recent", "socket", "transcript", "terminalcontent", "debuglog", "fontpayload", "ephemeral")
            .forEach { excluded -> assertFalse(snapshotFieldNames.contains(excluded)) }
        assertTrue(BackupPayloadSnapshot::class.java.declaredFields.none { it.type == ByteArray::class.java })
        assertTrue(PortableBackupSecret::class.java.declaredFields.none { it.type == String::class.java })

        val fullBytes = encode(
            fullSnapshot(
                PortableBackupSecret.copyAndWipe(byteArrayOf(9)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(8)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(7)),
            ),
        )
        val modeError = assertThrows(BackupPayloadException.Malformed::class.java) {
            codec.read(ByteArrayInputStream(fullBytes), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadMalformedReason.MODE_CONTENT_MISMATCH, modeError.reason)
    }

    @Test
    fun unknownOptionalFieldRecordAndCollectionAreSkippedAndReported() {
        var payload = encode(standardSnapshot())
        payload = appendField(payload, BackupPayloadWire.COLLECTION_HOSTS, 0, 100, byteArrayOf(4, 5, 6))
        payload = appendRecord(
            payload,
            BackupPayloadWire.COLLECTION_HOSTS,
            testRecord(type = 2, id = UNKNOWN_RECORD_ID),
        )
        payload = appendCollection(
            payload,
            10,
            listOf(testRecord(type = 1, id = UNKNOWN_COLLECTION_RECORD_ID)),
        )

        val result = codec.read(NoAvailableFragmentedInputStream(payload, 1), BackupMode.STANDARD)
        result.snapshot.use { restored ->
            assertEquals(2, restored.hostProfiles.size)
            assertEquals(1, result.compatibility.skippedOptionalFields)
            assertEquals(1, result.compatibility.skippedUnknownCollections)
            assertEquals(2, result.compatibility.skippedUnknownRecordTypes)
            assertEquals(1, result.compatibility.skippedUnknownRecords)
            assertTrue(result.compatibility.incompatibleRecords.isEmpty())
        }
    }

    @Test
    fun newerSchemaAndUnknownRequiredMembersAreRecordScoped() {
        var payload = encode(standardSnapshot())
        payload = appendField(
            payload,
            BackupPayloadWire.COLLECTION_HOSTS,
            0,
            BackupPayloadWire.REQUIRED_ID_MASK + 100,
            byteArrayOf(1),
        )
        payload = replaceField(
            payload,
            BackupPayloadWire.COLLECTION_HOSTS,
            1,
            BackupPayloadWire.RECORD_SCHEMA_FIELD,
            testU32(2),
        )
        payload = appendRecord(
            payload,
            BackupPayloadWire.COLLECTION_HOSTS,
            testRecord(type = BackupPayloadWire.REQUIRED_ID_MASK + 1, id = REQUIRED_RECORD_ID),
        )

        val result = codec.read(ByteArrayInputStream(payload), BackupMode.STANDARD)
        result.snapshot.use { restored ->
            assertTrue(restored.hostProfiles.isEmpty())
            assertEquals(
                listOf(
                    BackupIncompatibleReason.UNKNOWN_REQUIRED_FIELD,
                    BackupIncompatibleReason.NEWER_RECORD_SCHEMA,
                    BackupIncompatibleReason.UNKNOWN_REQUIRED_RECORD_TYPE,
                ),
                result.compatibility.incompatibleRecords.map { it.reason },
            )
        }

        val requiredCollection = appendCollection(
            encode(standardSnapshot()),
            BackupPayloadWire.REQUIRED_ID_MASK + 1,
            emptyList(),
        )
        val requiredError = assertThrows(BackupPayloadException.UnsupportedRequiredCollection::class.java) {
            codec.read(ByteArrayInputStream(requiredCollection), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadWire.REQUIRED_ID_MASK + 1, requiredError.collectionId)

        val futureMagic = encode(standardSnapshot()).apply {
            "TSPPAY02".toByteArray(Charsets.US_ASCII).copyInto(this, 0)
        }
        val versionError = assertThrows(BackupPayloadException.UnsupportedVersion::class.java) {
            codec.read(ByteArrayInputStream(futureMagic), BackupMode.STANDARD)
        }
        assertEquals(2L, versionError.version)
    }

    @Test
    fun malformedFieldsFramingUtf8AndValuesHaveTypedFailures() {
        val original = encode(standardSnapshot())
        assertMalformed(
            removeField(original, BackupPayloadWire.COLLECTION_HOSTS, 0, 10),
            BackupPayloadMalformedReason.MISSING_REQUIRED_FIELD,
        )
        assertMalformed(
            replaceField(original, BackupPayloadWire.COLLECTION_HOSTS, 0, 12, ByteArray(5)),
            BackupPayloadMalformedReason.INVALID_LENGTH,
        )
        assertMalformed(
            replaceField(original, BackupPayloadWire.COLLECTION_HOSTS, 0, 10, byteArrayOf(0xc3.toByte())),
            BackupPayloadMalformedReason.INVALID_UTF8,
        )
        assertMalformed(
            appendField(original, BackupPayloadWire.COLLECTION_HOSTS, 0, 29, testU64(2_000)),
            BackupPayloadMalformedReason.NON_CANONICAL_FIELDS,
        )
        assertMalformed(
            replaceField(original, BackupPayloadWire.COLLECTION_TERMINAL, 0, 13, testRawU32(0x7fc0_0000)),
            BackupPayloadMalformedReason.INVALID_VALUE,
        )
        assertMalformed(
            replaceCollectionBody(original, BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS, testU32(0)),
            BackupPayloadMalformedReason.MISSING_REQUIRED_FIELD,
        )

        val trailing = assertThrows(BackupPayloadException.Truncated::class.java) {
            codec.read(ByteArrayInputStream(original + byteArrayOf(1)), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadSection.COLLECTION_HEADER, trailing.section)

        val duplicateCollection = original + collectionBytes(original, BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS)
        assertMalformed(duplicateCollection, BackupPayloadMalformedReason.NON_CANONICAL_COLLECTIONS)
    }

    @Test
    fun totalCollectionRecordFieldStringAndSecretBoundsAreEnforced() {
        val original = encode(standardSnapshot())
        val totalError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            BackupPayloadCodec(original.size.toLong() - 1)
                .read(ByteArrayInputStream(original), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadLimit.TOTAL_BYTES, totalError.limit)

        val collectionError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    setCollectionLength(
                        original,
                        BackupPayloadWire.COLLECTION_HOSTS,
                        BackupPayloadFormat.MAX_PAYLOAD_BYTES + 1,
                    ),
                ),
                BackupMode.STANDARD,
            )
        }
        assertEquals(BackupPayloadLimit.COLLECTION_BYTES, collectionError.limit)

        val countError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    setCollectionCount(
                        original,
                        BackupPayloadWire.COLLECTION_HOSTS,
                        BackupPayloadFormat.MAX_HOST_PROFILES + 1L,
                    ),
                ),
                BackupMode.STANDARD,
            )
        }
        assertEquals(BackupPayloadLimit.RECORD_COUNT, countError.limit)

        val recordError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    setRecordLength(
                        original,
                        BackupPayloadWire.COLLECTION_HOSTS,
                        0,
                        BackupPayloadFormat.MAX_RECORD_BYTES + 1L,
                    ),
                ),
                BackupMode.STANDARD,
            )
        }
        assertEquals(BackupPayloadLimit.RECORD_BYTES, recordError.limit)

        val fieldError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    setFieldLength(
                        original,
                        BackupPayloadWire.COLLECTION_HOSTS,
                        0,
                        10,
                        BackupPayloadFormat.MAX_RECORD_BYTES + 1L,
                    ),
                ),
                BackupMode.STANDARD,
            )
        }
        assertEquals(BackupPayloadLimit.FIELD_BYTES, fieldError.limit)

        val stringError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    replaceField(
                        original,
                        BackupPayloadWire.COLLECTION_HOSTS,
                        0,
                        10,
                        ByteArray(BackupPayloadFormat.MAX_STRING_BYTES + 1) { 'a'.code.toByte() },
                    ),
                ),
                BackupMode.STANDARD,
            )
        }
        assertEquals(BackupPayloadLimit.STRING_BYTES, stringError.limit)

        val full = encode(
            fullSnapshot(
                PortableBackupSecret.copyAndWipe(byteArrayOf(1)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(2)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(3)),
            ),
        )
        val secretError = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            codec.read(
                ByteArrayInputStream(
                    replaceField(
                        full,
                        BackupPayloadWire.COLLECTION_CREDENTIALS,
                        0,
                        14,
                        ByteArray(BackupPayloadFormat.MAX_PASSWORD_OR_PASSPHRASE_BYTES + 1),
                    ),
                ),
                BackupMode.FULL,
            )
        }
        assertEquals(BackupPayloadLimit.SECRET_BYTES, secretError.limit)

        val exportLimit = assertThrows(BackupPayloadException.LimitExceeded::class.java) {
            BackupPayloadCodec(original.size.toLong() - 1).encodedSize(standardSnapshot())
        }
        assertEquals(BackupPayloadLimit.TOTAL_BYTES, exportLimit.limit)
    }

    @Test
    fun duplicateIdsSecretReferencesAndUnresolvedReferencesAreValidated() {
        val original = encode(standardSnapshot())
        val duplicateId = duplicateRecord(original, BackupPayloadWire.COLLECTION_HOSTS, 0)
        val duplicateIdError = assertThrows(BackupPayloadException.Malformed::class.java) {
            codec.read(ByteArrayInputStream(duplicateId), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadMalformedReason.DUPLICATE_RECORD_ID, duplicateIdError.reason)
        assertEquals(HOST_A_ID, duplicateIdError.recordId)

        val full = encode(
            fullSnapshot(
                PortableBackupSecret.copyAndWipe(byteArrayOf(1)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(2)),
                PortableBackupSecret.copyAndWipe(byteArrayOf(3)),
            ),
        )
        val duplicateSecretReference = replaceField(
            full,
            BackupPayloadWire.COLLECTION_CREDENTIALS,
            1,
            12,
            PASSWORD_SECRET_REFERENCE_ID.toByteArray(),
        )
        val duplicateSecretError = assertThrows(BackupPayloadException.Malformed::class.java) {
            codec.read(ByteArrayInputStream(duplicateSecretReference), BackupMode.FULL)
        }
        assertEquals(BackupPayloadMalformedReason.DUPLICATE_SECRET_REFERENCE, duplicateSecretError.reason)

        val unresolvedPayload = replaceField(
            original,
            BackupPayloadWire.COLLECTION_HOSTS,
            0,
            15,
            MISSING_ID.toByteArray(),
        )
        val unresolvedResult = codec.read(ByteArrayInputStream(unresolvedPayload), BackupMode.STANDARD)
        unresolvedResult.snapshot.use {
            assertEquals(
                BackupUnresolvedReference(HOST_A_ID, MISSING_ID, BackupReferenceKind.HOST_CREDENTIAL),
                unresolvedResult.compatibility.unresolvedReferences.single(),
            )
        }

        val unresolvedSnapshot = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            hostProfiles = listOf(hostA().copy(credentialId = MISSING_ID)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            codec.writeAndWipeSecrets(unresolvedSnapshot, ByteArrayOutputStream())
        }
        assertTrue(unresolvedSnapshot.unresolvedReferences.isNotEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                hostProfiles = listOf(hostA().copy(credentialId = null), hostA().copy(credentialId = null)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                credentials = listOf(
                    BackupCredentialRecord(passwordCredential()),
                    BackupCredentialRecord(
                        keyCredential().copy(
                            authentication = SshAuthentication.PrivateKey(KEY_ID, PASSWORD_SECRET_REFERENCE_ID),
                        ),
                    ),
                ),
                sshKeys = listOf(BackupSshKeyRecord(sshKey())),
            )
        }
    }

    @Test
    fun secretsWipeOnExplicitCloseInvalidWrapperAndFailedWrite() {
        val source = byteArrayOf(4, 3, 2, 1)
        val owner = PortableBackupSecret.copyAndWipe(source)
        val backing = owner.borrowForTest()
        assertAllZero(source)

        owner.wipe()

        assertTrue(owner.isWiped)
        assertAllZero(backing)
        assertThrows(IllegalStateException::class.java) { owner.withBytes { it.size } }

        val invalidOwner = PortableBackupSecret.copyAndWipe(byteArrayOf(9))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCredentialRecord(
                passwordCredential().copy(authentication = SshAuthentication.Password()),
                invalidOwner,
            )
        }
        assertTrue(invalidOwner.isWiped)

        val password = PortableBackupSecret.copyAndWipe(byteArrayOf(1))
        val passphrase = PortableBackupSecret.copyAndWipe(byteArrayOf(2))
        val privateKey = PortableBackupSecret.copyAndWipe(byteArrayOf(3))
        val failedSnapshot = fullSnapshot(password, passphrase, privateKey)
        assertThrows(IOException::class.java) {
            codec.writeAndWipeSecrets(failedSnapshot, AlwaysFailingOutputStream())
        }
        assertTrue(password.isWiped)
        assertTrue(passphrase.isWiped)
        assertTrue(privateKey.isWiped)

        val tooLarge = PortableBackupSecret.copyAndWipe(
            ByteArray(BackupPayloadFormat.MAX_PASSWORD_OR_PASSPHRASE_BYTES + 1) { 1 },
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupCredentialRecord(passwordCredential(), tooLarge)
        }
        assertTrue(tooLarge.isWiped)
    }

    @Test
    fun truncationAtPayloadBoundariesIsTyped() {
        val payload = encode(standardSnapshot())
        listOf(0, 1, 7).forEach { length ->
            val error = assertThrows(BackupPayloadException.Truncated::class.java) {
                codec.read(ByteArrayInputStream(payload.copyOf(length)), BackupMode.STANDARD)
            }
            assertEquals(BackupPayloadSection.MAGIC, error.section)
        }

        val host = findCollection(payload, BackupPayloadWire.COLLECTION_HOSTS)
        val record = findRecord(payload, host, 0)
        val bodyError = assertThrows(BackupPayloadException.Truncated::class.java) {
            codec.read(ByteArrayInputStream(payload.copyOf(record.bodyEnd - 1)), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadSection.RECORD_BODY, bodyError.section)

        val last = findCollection(payload, BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS)
        val lengthTooLong = payload.copyOf().apply {
            writeTestU32(last.lengthOffset, last.bodyLength + 1L)
        }
        val collectionError = assertThrows(BackupPayloadException.Truncated::class.java) {
            codec.read(ByteArrayInputStream(lengthTooLong), BackupMode.STANDARD)
        }
        assertEquals(BackupPayloadSection.COLLECTION_BODY, collectionError.section)
    }

    private fun assertMalformed(payload: ByteArray, reason: BackupPayloadMalformedReason) {
        val error = assertThrows(BackupPayloadException.Malformed::class.java) {
            codec.read(ByteArrayInputStream(payload), BackupMode.STANDARD)
        }
        assertEquals(reason, error.reason)
    }

    private fun encode(snapshot: BackupPayloadSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        codec.writeAndWipeSecrets(snapshot, output)
        return output.toByteArray()
    }
}

private fun standardSnapshot(reverse: Boolean = false): BackupPayloadSnapshot {
    fun <T> ordered(values: List<T>): List<T> = if (reverse) values.reversed() else values
    return BackupPayloadSnapshot(
        mode = BackupMode.STANDARD,
        hostProfiles = ordered(listOf(hostA(), hostB())),
        credentials = ordered(
            listOf(
                BackupCredentialRecord(passwordCredential()),
                BackupCredentialRecord(keyCredential()),
            ),
        ),
        sshKeys = listOf(BackupSshKeyRecord(sshKey())),
        knownHosts = listOf(knownHost()),
        snippets = listOf(snippet()),
        terminalProfiles = listOf(terminalProfile()),
        terminalThemes = listOf(terminalTheme()),
        keyboardProfiles = listOf(keyboardProfile()),
        globalSettings = globalSettings(),
    )
}

private fun fullSnapshot(
    password: PortableBackupSecret,
    passphrase: PortableBackupSecret,
    privateKey: PortableBackupSecret,
): BackupPayloadSnapshot {
    val standard = standardSnapshot()
    return BackupPayloadSnapshot(
        mode = BackupMode.FULL,
        hostProfiles = standard.hostProfiles,
        credentials = listOf(
            BackupCredentialRecord(passwordCredential(), password),
            BackupCredentialRecord(keyCredential(), passphrase),
        ),
        sshKeys = listOf(BackupSshKeyRecord(sshKey(), privateKey)),
        knownHosts = standard.knownHosts,
        snippets = standard.snippets,
        terminalProfiles = standard.terminalProfiles,
        terminalThemes = standard.terminalThemes,
        keyboardProfiles = standard.keyboardProfiles,
        globalSettings = standard.globalSettings,
    )
}

private fun assertSnapshotEquals(expected: BackupPayloadSnapshot, actual: BackupPayloadSnapshot) {
    assertEquals(expected.mode, actual.mode)
    assertEquals(expected.hostProfiles, actual.hostProfiles)
    assertEquals(expected.credentials.map { it.metadata }, actual.credentials.map { it.metadata })
    assertEquals(expected.sshKeys.map { it.metadata }, actual.sshKeys.map { it.metadata })
    assertEquals(expected.knownHosts, actual.knownHosts)
    assertEquals(expected.snippets, actual.snippets)
    assertEquals(expected.terminalProfiles, actual.terminalProfiles)
    assertEquals(expected.terminalThemes, actual.terminalThemes)
    assertEquals(expected.customFonts, actual.customFonts)
    assertEquals(expected.keyboardProfiles, actual.keyboardProfiles)
    assertEquals(expected.globalSettings, actual.globalSettings)
    assertEquals(expected.unresolvedReferences, actual.unresolvedReferences)
}

private fun hostA(): HostProfile = HostProfile(
    id = HOST_A_ID,
    displayName = "Production Mosh",
    hostname = "shell.example.com",
    port = 22,
    username = "operator",
    protocol = ConnectionProtocol.MOSH,
    credentialId = PASSWORD_CREDENTIAL_ID,
    terminalProfileId = TERMINAL_PROFILE_ID,
    keyboardProfileId = KEYBOARD_PROFILE_ID,
    isFavorite = true,
    group = "Production",
    tag = "primary",
    startupCommand = "exec tmux new-session -A -s main",
    keepaliveIntervalSeconds = 30,
    reconnectPolicy = ReconnectPolicy.AUTOMATIC,
    moshPortRange = MoshPortRange(60_000, 60_010),
    moshServerCommand = "mosh-server new",
    moshLocale = "en_GB.UTF-8",
    moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
    createdAtEpochMillis = 1_000,
    updatedAtEpochMillis = 2_000,
)

private fun hostB(): HostProfile = HostProfile(
    id = HOST_B_ID,
    displayName = "Backup SSH",
    hostname = "192.0.2.10",
    port = 2222,
    username = "backup",
    protocol = ConnectionProtocol.SSH,
    credentialId = KEY_CREDENTIAL_ID,
    createdAtEpochMillis = 1_100,
    updatedAtEpochMillis = 2_100,
)

private fun passwordCredential(): SshCredential = SshCredential(
    PASSWORD_CREDENTIAL_ID,
    "Saved password",
    SshAuthentication.Password(PASSWORD_SECRET_REFERENCE_ID),
    1_200,
    2_200,
)

private fun keyCredential(): SshCredential = SshCredential(
    KEY_CREDENTIAL_ID,
    "Imported key",
    SshAuthentication.PrivateKey(KEY_ID, PASSPHRASE_SECRET_REFERENCE_ID),
    1_300,
    2_300,
)

private fun sshKey(): SshKeyIdentity = SshKeyIdentity(
    id = KEY_ID,
    name = "Deploy key",
    algorithm = "ssh-ed25519",
    publicKeyFingerprint = "SHA256:abcdefghijklmnop",
    publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest",
    privateKeySecretReferenceId = PRIVATE_KEY_SECRET_REFERENCE_ID,
    origin = SshKeyOrigin.IMPORTED,
    isPassphraseProtected = true,
    createdAtEpochMillis = 1_400,
    updatedAtEpochMillis = 2_400,
    comment = "deployment",
)

private fun knownHost(): KnownHost = KnownHost(
    KNOWN_HOST_ID,
    "shell.example.com",
    22,
    "ssh-ed25519",
    "SHA256:hostfingerprint",
    "ssh-ed25519 AAAAC3NzaHostKey",
    1_500,
    2_500,
)

private fun snippet(): Snippet = Snippet(
    id = SNIPPET_ID,
    name = "List details",
    group = "Shell",
    command = "ls -la",
    tapAction = SnippetTapAction.SEND_IMMEDIATELY,
    appendEnter = true,
    confirmMultilineExecution = true,
    isFavorite = true,
    createdAtEpochMillis = 1_600,
    updatedAtEpochMillis = 2_600,
)

private fun terminalProfile(): TerminalProfile = TerminalProfile(
    id = TERMINAL_PROFILE_ID,
    name = "Ops terminal",
    themeId = TERMINAL_THEME_ID,
    fontId = "builtin.monospace",
    fontSizeSp = 15.5f,
    lineHeightMultiplier = 1.2f,
    letterSpacingEm = 0.05f,
    cursorStyle = CursorStyle.BEAM,
    cursorBlinkEnabled = false,
    scrollbackLines = 80_000,
    bell = BellSettings(true, false, true),
    scroll = ScrollBehavior(TouchScrollMode.LOCAL_SCROLLBACK, true, false, true),
    links = LinkBehavior(true, false, RemoteClipboardMode.DISABLED, copyOnSelection = true),
    termValue = "xterm-256color",
    preserveAlternateScreenHistory = false,
    boldRenderingEnabled = false,
    ligaturesEnabled = true,
    pinchZoomEnabled = false,
    createdAtEpochMillis = 1_700,
    updatedAtEpochMillis = 2_700,
)

private fun terminalTheme(): BackupTerminalTheme = BackupTerminalTheme(
    id = TERMINAL_THEME_ID,
    name = "Midnight",
    foregroundArgb = 0xffeeeeee.toInt(),
    backgroundArgb = 0xff101010.toInt(),
    cursorArgb = 0xffffcc00.toInt(),
    selectionArgb = 0xff334455.toInt(),
    ansi16Argb = List(16) { index -> 0xff000000.toInt() or (index * 0x010101) },
    createdAtEpochMillis = 1_800,
    updatedAtEpochMillis = 2_800,
    boldUsesBrightColours = false,
)

private fun keyboardProfile(): KeyboardProfile = KeyboardProfile(
    id = KEYBOARD_PROFILE_ID,
    name = "Ops keys",
    orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL, KeyboardAction.TMUX_PREFIX),
    layout = KeyboardLayout.TWO_ROWS,
    modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
    hapticFeedbackEnabled = true,
    keyRepeatEnabled = false,
    inputMode = TerminalInputMode.TEXT,
    tmuxPrefix = "C-a",
    createdAtEpochMillis = 1_900,
    updatedAtEpochMillis = 2_900,
)

private fun globalSettings(): BackupGlobalSettings = BackupGlobalSettings(
    themeMode = BackupThemeMode.DARK,
    dynamicColorEnabled = false,
    accentPreset = "violet",
    defaultTerminalProfileId = TERMINAL_PROFILE_ID,
    defaultKeyboardProfileId = KEYBOARD_PROFILE_ID,
    keepaliveIntervalSeconds = 30,
    reconnectEnabled = true,
    reconnectMaxAttempts = 7,
    backgroundSessionsEnabled = true,
    notificationPrivacyEnabled = false,
    disconnectNotificationsEnabled = true,
    reconnectNotificationsEnabled = true,
    keepCpuAwake = true,
    keepScreenOnWhileTerminalVisible = true,
    appLockMode = BackupAppLockMode.DELAYED,
    appLockDelaySeconds = 120,
    screenshotBlockingEnabled = true,
    sensitiveClipboardClearSeconds = 60,
    osc52Policy = RemoteClipboardMode.DISABLED,
    multilinePasteConfirmationEnabled = false,
    tmuxSessionSelectorDisabled = true,
    lastBackupMode = BackupMode.FULL,
)

private fun PortableBackupSecret.copyForTest(): ByteArray = withBytes(ByteArray::copyOf)

private fun PortableBackupSecret.borrowForTest(): ByteArray {
    var borrowed: ByteArray? = null
    withBytes { borrowed = it }
    return requireNotNull(borrowed)
}

private fun assertAllZero(bytes: ByteArray) = assertTrue(bytes.all { it == 0.toByte() })

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private class NoAvailableFragmentedInputStream(
    bytes: ByteArray,
    private val maximumChunk: Int,
) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)

    override fun read(): Int = delegate.read()

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        delegate.read(destination, offset, minOf(length, maximumChunk))

    override fun available(): Int = error("Payload parser must not call available().")
}

private class FragmentingOutputStream(private val maximumChunk: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream()

    override fun write(value: Int) = delegate.write(value)

    override fun write(source: ByteArray, offset: Int, length: Int) {
        var position = offset
        while (position < offset + length) {
            val count = minOf(maximumChunk, offset + length - position)
            delegate.write(source, position, count)
            position += count
        }
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()
}

private class AlwaysFailingOutputStream : OutputStream() {
    override fun write(value: Int): Unit = throw IOException("injected write failure")
    override fun write(source: ByteArray, offset: Int, length: Int): Unit =
        throw IOException("injected write failure")
}

private data class TestCollectionFrame(
    val headerStart: Int,
    val lengthOffset: Int,
    val bodyStart: Int,
    val bodyLength: Int,
) {
    val bodyEnd: Int get() = bodyStart + bodyLength
}

private data class TestRecordFrame(
    val headerStart: Int,
    val lengthOffset: Int,
    val bodyStart: Int,
    val bodyLength: Int,
) {
    val bodyEnd: Int get() = bodyStart + bodyLength
}

private data class TestFieldFrame(
    val headerStart: Int,
    val lengthOffset: Int,
    val valueStart: Int,
    val valueLength: Int,
) {
    val end: Int get() = valueStart + valueLength
}

private fun findCollection(payload: ByteArray, wantedId: Int): TestCollectionFrame {
    var position = BackupPayloadWire.MAGIC_V1.size
    while (position < payload.size) {
        val id = payload.readTestU16(position)
        val bodyLength = payload.readTestU32(position + 2).toInt()
        val frame = TestCollectionFrame(position, position + 2, position + 6, bodyLength)
        if (id == wantedId) return frame
        position = frame.bodyEnd
    }
    error("Collection $wantedId not found")
}

private fun findRecord(payload: ByteArray, collection: TestCollectionFrame, wantedIndex: Int): TestRecordFrame {
    val count = payload.readTestU32(collection.bodyStart).toInt()
    require(wantedIndex in 0 until count)
    var position = collection.bodyStart + 4
    repeat(count) { index ->
        val bodyLength = payload.readTestU32(position + 2).toInt()
        val frame = TestRecordFrame(position, position + 2, position + 6, bodyLength)
        if (index == wantedIndex) return frame
        position = frame.bodyEnd
    }
    error("Record $wantedIndex not found")
}

private fun findField(payload: ByteArray, record: TestRecordFrame, wantedId: Int): TestFieldFrame {
    var position = record.bodyStart
    while (position < record.bodyEnd) {
        val id = payload.readTestU16(position)
        val valueLength = payload.readTestU32(position + 2).toInt()
        val frame = TestFieldFrame(position, position + 2, position + 6, valueLength)
        if (id == wantedId) return frame
        position = frame.end
    }
    error("Field $wantedId not found")
}

private fun appendField(
    payload: ByteArray,
    collectionId: Int,
    recordIndex: Int,
    fieldId: Int,
    value: ByteArray,
): ByteArray {
    val collection = findCollection(payload, collectionId)
    val record = findRecord(payload, collection, recordIndex)
    val encoded = testTlv(fieldId, value)
    return insert(payload, record.bodyEnd, encoded).apply {
        writeTestU32(record.lengthOffset, record.bodyLength + encoded.size.toLong())
        writeTestU32(collection.lengthOffset, collection.bodyLength + encoded.size.toLong())
    }
}

private fun replaceField(
    payload: ByteArray,
    collectionId: Int,
    recordIndex: Int,
    fieldId: Int,
    value: ByteArray,
): ByteArray {
    val collection = findCollection(payload, collectionId)
    val record = findRecord(payload, collection, recordIndex)
    val field = findField(payload, record, fieldId)
    val replacement = testTlv(fieldId, value)
    val result = replaceRange(payload, field.headerStart, field.end, replacement)
    val delta = replacement.size - (field.end - field.headerStart)
    result.writeTestU32(record.lengthOffset, record.bodyLength + delta.toLong())
    result.writeTestU32(collection.lengthOffset, collection.bodyLength + delta.toLong())
    return result
}

private fun removeField(
    payload: ByteArray,
    collectionId: Int,
    recordIndex: Int,
    fieldId: Int,
): ByteArray {
    val collection = findCollection(payload, collectionId)
    val record = findRecord(payload, collection, recordIndex)
    val field = findField(payload, record, fieldId)
    val removed = field.end - field.headerStart
    return replaceRange(payload, field.headerStart, field.end, ByteArray(0)).apply {
        writeTestU32(record.lengthOffset, record.bodyLength - removed.toLong())
        writeTestU32(collection.lengthOffset, collection.bodyLength - removed.toLong())
    }
}

private fun appendRecord(payload: ByteArray, collectionId: Int, encodedRecord: ByteArray): ByteArray {
    val collection = findCollection(payload, collectionId)
    val count = payload.readTestU32(collection.bodyStart)
    return insert(payload, collection.bodyEnd, encodedRecord).apply {
        writeTestU32(collection.bodyStart, count + 1)
        writeTestU32(collection.lengthOffset, collection.bodyLength + encodedRecord.size.toLong())
    }
}

private fun duplicateRecord(payload: ByteArray, collectionId: Int, recordIndex: Int): ByteArray {
    val collection = findCollection(payload, collectionId)
    val record = findRecord(payload, collection, recordIndex)
    return appendRecord(payload, collectionId, payload.copyOfRange(record.headerStart, record.bodyEnd))
}

private fun appendCollection(payload: ByteArray, id: Int, records: List<ByteArray>): ByteArray {
    val body = join(testU32(records.size.toLong()), *records.toTypedArray())
    return join(payload, testU16(id), testU32(body.size.toLong()), body)
}

private fun replaceCollectionBody(payload: ByteArray, collectionId: Int, body: ByteArray): ByteArray {
    val collection = findCollection(payload, collectionId)
    return replaceRange(payload, collection.bodyStart, collection.bodyEnd, body).apply {
        writeTestU32(collection.lengthOffset, body.size.toLong())
    }
}

private fun setCollectionLength(payload: ByteArray, collectionId: Int, length: Long): ByteArray =
    payload.copyOf().apply { writeTestU32(findCollection(this, collectionId).lengthOffset, length) }

private fun setCollectionCount(payload: ByteArray, collectionId: Int, count: Long): ByteArray =
    payload.copyOf().apply { writeTestU32(findCollection(this, collectionId).bodyStart, count) }

private fun setRecordLength(
    payload: ByteArray,
    collectionId: Int,
    recordIndex: Int,
    length: Long,
): ByteArray = payload.copyOf().apply {
    val collection = findCollection(this, collectionId)
    writeTestU32(findRecord(this, collection, recordIndex).lengthOffset, length)
}

private fun setFieldLength(
    payload: ByteArray,
    collectionId: Int,
    recordIndex: Int,
    fieldId: Int,
    length: Long,
): ByteArray = payload.copyOf().apply {
    val collection = findCollection(this, collectionId)
    val record = findRecord(this, collection, recordIndex)
    writeTestU32(findField(this, record, fieldId).lengthOffset, length)
}

private fun collectionBytes(payload: ByteArray, collectionId: Int): ByteArray {
    val collection = findCollection(payload, collectionId)
    return payload.copyOfRange(collection.headerStart, collection.bodyEnd)
}

private fun testRecord(type: Int, id: String): ByteArray {
    val body = join(
        testTlv(BackupPayloadWire.RECORD_SCHEMA_FIELD, testU32(1)),
        testTlv(BackupPayloadWire.RECORD_ID_FIELD, id.toByteArray()),
    )
    return join(testU16(type), testU32(body.size.toLong()), body)
}

private fun testTlv(id: Int, value: ByteArray): ByteArray =
    join(testU16(id), testU32(value.size.toLong()), value)

private fun insert(source: ByteArray, offset: Int, addition: ByteArray): ByteArray =
    replaceRange(source, offset, offset, addition)

private fun replaceRange(source: ByteArray, start: Int, end: Int, replacement: ByteArray): ByteArray {
    require(start in 0..end && end <= source.size)
    return ByteArray(source.size - (end - start) + replacement.size).also { result ->
        source.copyInto(result, 0, 0, start)
        replacement.copyInto(result, start)
        source.copyInto(result, start + replacement.size, end, source.size)
    }
}

private fun join(vararg arrays: ByteArray): ByteArray {
    val total = arrays.sumOf(ByteArray::size)
    return ByteArray(total).also { destination ->
        var offset = 0
        arrays.forEach { bytes ->
            bytes.copyInto(destination, offset)
            offset += bytes.size
        }
    }
}

private fun testU16(value: Int): ByteArray = ByteArray(2).apply {
    this[0] = (value ushr 8).toByte()
    this[1] = value.toByte()
}

private fun testU32(value: Long): ByteArray = ByteArray(4).apply { writeTestU32(0, value) }

private fun testU64(value: Long): ByteArray = ByteArray(8).apply {
    repeat(8) { index -> this[index] = (value ushr (56 - index * 8)).toByte() }
}

private fun testRawU32(value: Int): ByteArray = ByteArray(4).apply {
    repeat(4) { index -> this[index] = (value ushr (24 - index * 8)).toByte() }
}

private fun ByteArray.writeTestU32(offset: Int, value: Long) {
    require(value in 0..0xffff_ffffL)
    repeat(4) { index -> this[offset + index] = (value ushr (24 - index * 8)).toByte() }
}

private fun ByteArray.readTestU16(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

private fun ByteArray.readTestU32(offset: Int): Long {
    var result = 0L
    repeat(4) { index -> result = (result shl 8) or (this[offset + index].toInt() and 0xff).toLong() }
    return result
}

private const val HOST_A_ID = "00000000-0000-0000-0000-000000000101"
private const val HOST_B_ID = "00000000-0000-0000-0000-000000000102"
private const val PASSWORD_CREDENTIAL_ID = "00000000-0000-0000-0000-000000000201"
private const val KEY_CREDENTIAL_ID = "00000000-0000-0000-0000-000000000202"
private const val PASSWORD_SECRET_REFERENCE_ID = "00000000-0000-0000-0000-000000000211"
private const val PASSPHRASE_SECRET_REFERENCE_ID = "00000000-0000-0000-0000-000000000212"
private const val KEY_ID = "00000000-0000-0000-0000-000000000301"
private const val PRIVATE_KEY_SECRET_REFERENCE_ID = "00000000-0000-0000-0000-000000000311"
private const val KNOWN_HOST_ID = "00000000-0000-0000-0000-000000000401"
private const val SNIPPET_ID = "00000000-0000-0000-0000-000000000501"
private const val TERMINAL_PROFILE_ID = "00000000-0000-0000-0000-000000000601"
private const val TERMINAL_THEME_ID = "00000000-0000-0000-0000-000000000602"
private const val KEYBOARD_PROFILE_ID = "00000000-0000-0000-0000-000000000701"
private const val UNKNOWN_RECORD_ID = "00000000-0000-0000-0000-000000000901"
private const val UNKNOWN_COLLECTION_RECORD_ID = "00000000-0000-0000-0000-000000000902"
private const val REQUIRED_RECORD_ID = "00000000-0000-0000-0000-000000000903"
private const val MISSING_ID = "00000000-0000-0000-0000-000000000999"
