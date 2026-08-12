package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.db.KnownHostEntity
import com.yanjiyu.terminalspike.core.data.db.RecentSessionEntity
import com.yanjiyu.terminalspike.core.data.db.SnippetEntity
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EntityMappersTest {
    @Test
    fun moshSinglePortAndOneElementRangeUseDistinctLosslessRepresentations() {
        val single = validHost(moshPort = 60_001)
        val range = validHost(moshPortRange = MoshPortRange(60_001, 60_001))

        val singleRow = single.toEntity()
        val rangeRow = range.toEntity()

        assertEquals(60_001, singleRow.moshPortStart)
        assertNull(singleRow.moshPortEnd)
        assertEquals(60_001, rangeRow.moshPortStart)
        assertEquals(60_001, rangeRow.moshPortEnd)
        assertEquals(single, singleRow.toDomainModel())
        assertEquals(range, rangeRow.toDomainModel())
    }

    @Test
    fun moshRangeEndWithoutStartIsReportedAsCorruptStoredData() {
        val corrupt = validHost().toEntity().copy(
            moshPortStart = null,
            moshPortEnd = 60_010,
        )

        val failure = assertThrows(CorruptStoredDataException::class.java) {
            corrupt.toDomainModel()
        }

        assertEquals(RepositoryRecordType.HOST_PROFILE, failure.recordType)
        assertEquals(HOST_ID, failure.recordKey)
    }

    @Test
    fun stableWireCodeDecoderRejectsUnknownPersistedCodeWithoutDefaulting() {
        val corrupt = validHost().toEntity().copy(protocolCode = "future_protocol")

        val failure = assertThrows(CorruptStoredDataException::class.java) {
            corrupt.toDomainModel()
        }

        assertEquals(RepositoryRecordType.HOST_PROFILE, failure.recordType)
    }

    @Test
    fun knownHostUnknownLegacyTimestampsAndBinaryKeyRoundTripExactly() {
        val key = byteArrayOf(0, 1, 2, 3, 0x7f, -1)
        val row = KnownHostEntity(
            id = KNOWN_HOST_ID,
            host = "shell.example",
            port = 22,
            algorithmCode = "ssh-ed25519",
            fingerprint = key.sha256Fingerprint(),
            publicKey = key,
            firstSeenAtEpochMillis = null,
            lastSeenAtEpochMillis = null,
        )

        val domain = row.toDomainModel()
        val restored = domain.toEntity()

        assertEquals(Base64.getEncoder().encodeToString(key), domain.publicHostKey)
        assertNull(domain.firstSeenAtEpochMillis)
        assertNull(domain.lastSeenAtEpochMillis)
        assertArrayEquals(key, restored.publicKey)
        assertNull(restored.firstSeenAtEpochMillis)
        assertNull(restored.lastSeenAtEpochMillis)
    }

    @Test
    fun halfKnownLegacyTimestampsAreReportedInsteadOfInventingHistory() {
        val corrupt = validKnownHostRow().copy(lastSeenAtEpochMillis = null)

        val failure = assertThrows(CorruptStoredDataException::class.java) {
            corrupt.toDomainModel()
        }

        assertEquals(RepositoryRecordType.KNOWN_HOST, failure.recordType)
    }

    @Test
    fun invalidDomainEncodingIsRejectedBeforeWrite() {
        val invalidBase64 = validKnownHostRow().toDomainModel().copy(publicHostKey = "not base64!")

        val failure = assertThrows(InvalidRepositoryInputException::class.java) {
            invalidBase64.toEntity()
        }

        assertEquals(RepositoryRecordType.KNOWN_HOST, failure.recordType)
    }

    @Test
    fun knownHostFingerprintMustBeCanonicalAndMatchTheExactPublicKeyBytes() {
        val validRow = validKnownHostRow()
        val storedMismatch = validRow.copy(
            fingerprint = byteArrayOf(9, 9, 9).sha256Fingerprint(),
        )

        val storedFailure = assertThrows(CorruptStoredDataException::class.java) {
            storedMismatch.toDomainModel()
        }
        assertEquals(RepositoryRecordType.KNOWN_HOST, storedFailure.recordType)

        val paddedFingerprint = validRow.toDomainModel().copy(
            fingerprint = validRow.fingerprint + "=",
        )
        val domainFailure = assertThrows(InvalidRepositoryInputException::class.java) {
            paddedFingerprint.toEntity()
        }
        assertEquals(RepositoryRecordType.KNOWN_HOST, domainFailure.recordType)
    }

    @Test
    fun keyboardRowsPreserveActionOrderAndRejectPositionGaps() {
        val profile = KeyboardProfileEntity(
            id = KEYBOARD_ID,
            name = "tmux",
            rowCount = 2,
            modifierPolicyCode = "one_shot_with_double_tap_lock",
            hapticEnabled = true,
            keyRepeatEnabled = true,
            inputModeCode = "raw",
            tmuxPrefix = "C-a",
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val rows = KeyboardProfileWithKeys(
            profile,
            listOf(
                KeyboardProfileKeyEntity(KEYBOARD_ID, 2, "arrow_left"),
                KeyboardProfileKeyEntity(KEYBOARD_ID, 0, "escape"),
                KeyboardProfileKeyEntity(KEYBOARD_ID, 1, "tmux_prefix"),
            ),
        )

        val domain = rows.toDomainModel()
        val restored = domain.toRows()

        assertEquals(listOf("escape", "tmux_prefix", "arrow_left"), restored.keys.map { it.actionCode })
        assertEquals(listOf(0, 1, 2), restored.keys.map { it.position })

        val corrupt = rows.copy(keys = rows.keys.mapIndexed { index, key -> key.copy(position = index + 1) })
        assertThrows(CorruptStoredDataException::class.java) { corrupt.toDomainModel() }
    }

    @Test
    fun terminalSnippetAndRecentSessionRowsRoundTripThroughValidatedModels() {
        val terminalRow = TestRows.terminalProfile()
        val snippetRow = SnippetEntity(
            id = SNIPPET_ID,
            name = "Status",
            groupName = "Operations",
            command = "systemctl status app",
            actionCode = SnippetTapAction.SEND_IMMEDIATELY.wireCode,
            appendEnter = true,
            confirmMultiline = true,
            isFavorite = true,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val sessionRow = RecentSessionEntity(
            id = SESSION_ID,
            hostProfileId = HOST_ID,
            hostDisplayName = "Production",
            protocolCode = ConnectionProtocol.SSH.wireCode,
            stateCode = SessionState.DISCONNECTED.wireCode,
            startedAtEpochMillis = 10,
            lastActivityAtEpochMillis = 20,
            endedAtEpochMillis = 30,
            terminalTitle = "server shell",
            endpointIdentityToken = "ab".repeat(32),
        )

        assertEquals(terminalRow, terminalRow.toDomainModel().toEntity())
        assertEquals(snippetRow, snippetRow.toDomainModel().toEntity())
        assertEquals(sessionRow, sessionRow.toDomainModel().toEntity())
    }

    @Test
    fun invalidRecentSessionStateCombinationIsAStoredDataError() {
        val activeButEnded = RecentSessionEntity(
            id = SESSION_ID,
            hostProfileId = HOST_ID,
            hostDisplayName = "Production",
            protocolCode = "ssh",
            stateCode = "connected",
            startedAtEpochMillis = 10,
            lastActivityAtEpochMillis = 20,
            endedAtEpochMillis = 30,
            terminalTitle = null,
        )

        assertThrows(CorruptStoredDataException::class.java) {
            activeButEnded.toDomainModel()
        }
    }

    private fun validHost(
        moshPort: Int? = null,
        moshPortRange: MoshPortRange? = null,
    ) = HostProfile(
        id = HOST_ID,
        displayName = "Production",
        hostname = "shell.example",
        port = 22,
        username = "operator",
        protocol = ConnectionProtocol.MOSH,
        credentialId = CREDENTIAL_ID,
        terminalProfileId = TERMINAL_ID,
        keyboardProfileId = KEYBOARD_ID,
        isFavorite = true,
        group = "Work",
        tag = "prod",
        startupCommand = "tmux attach",
        keepaliveIntervalSeconds = 30,
        reconnectPolicy = com.yanjiyu.terminalspike.core.model.ReconnectPolicy.AUTOMATIC,
        moshPort = moshPort,
        moshPortRange = moshPortRange,
        moshServerCommand = "mosh-server new -s",
        moshLocale = "en_GB.UTF-8",
        moshFallbackPolicy = MoshFallbackPolicy.ASK,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun validKnownHostRow(): KnownHostEntity {
        val publicKey = byteArrayOf(1, 2, 3)
        return KnownHostEntity(
            id = KNOWN_HOST_ID,
            host = "shell.example",
            port = 22,
            algorithmCode = "ssh-ed25519",
            fingerprint = publicKey.sha256Fingerprint(),
            publicKey = publicKey,
            firstSeenAtEpochMillis = 10,
            lastSeenAtEpochMillis = 20,
        )
    }

    private fun ByteArray.sha256Fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private object TestRows {
        fun terminalProfile() = com.yanjiyu.terminalspike.core.data.db.TerminalProfileEntity(
            id = TERMINAL_ID,
            name = "Nord 14",
            themeId = "nord",
            fontId = "system_monospace",
            fontSizeSp = 14f,
            lineHeightMultiplier = 1.1f,
            letterSpacingEm = 0f,
            cursorStyleCode = "beam",
            cursorBlink = true,
            scrollbackLines = 20_000,
            visualBellEnabled = true,
            vibrationBellEnabled = false,
            audibleBellEnabled = false,
            touchScrollModeCode = "auto",
            twoFingerLocalScrollOverride = true,
            jumpToBottomOnKeyboardInput = true,
            keepViewportPositionOnOutput = true,
            detectPlainTextUrls = true,
            osc8HyperlinksEnabled = true,
            remoteClipboardModeCode = "ask",
            copyOnSelection = true,
            termType = "xterm-256color",
            retainAlternateScreenHistory = true,
            boldRenderingEnabled = false,
            ligaturesEnabled = true,
            pinchZoomEnabled = false,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
    }

    private companion object {
        const val HOST_ID = "00000000-0000-4000-8000-000000000001"
        const val CREDENTIAL_ID = "00000000-0000-4000-8000-000000000002"
        const val TERMINAL_ID = "00000000-0000-4000-8000-000000000003"
        const val KEYBOARD_ID = "00000000-0000-4000-8000-000000000004"
        const val KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000005"
        const val SNIPPET_ID = "00000000-0000-4000-8000-000000000006"
        const val SESSION_ID = "00000000-0000-4000-8000-000000000007"
    }
}
