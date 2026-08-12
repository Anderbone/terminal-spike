package com.yanjiyu.terminalspike.core.data.migration

import android.content.Context
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationBatch
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationInsertResult
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationSourceConflictException
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationStateEntity
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialCiphertextStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class LegacyMigrationSourceOutcome {
    NO_SOURCE,
    APPLIED,
    ALREADY_APPLIED,
    BLOCKED,
}

internal data class LegacyMigrationOutcome(
    val sourceCode: String,
    val outcome: LegacyMigrationSourceOutcome,
    val errorCode: String? = null,
)

internal data class LegacyStartupMigrationResult(
    val userSettings: LegacyMigrationOutcome,
    val knownHosts: LegacyMigrationOutcome,
)

internal class LegacyRecoveryDiscardUnavailableException(val sourceCode: String) :
    IllegalStateException("Legacy source '$sourceCode' is not awaiting explicit recovery.")

internal enum class LegacySecretReencryptionStage(val errorCode: String) {
    ENCRYPT("target_encryption_failed"),
    REOPEN("target_reopen_failed"),
}

internal class LegacySecretReencryptionException(
    val stage: LegacySecretReencryptionStage,
    cause: Throwable? = null,
) : Exception("Legacy credential ${stage.errorCode}.", cause)

/** Encrypts one legacy cleartext and proves the new envelope can be reopened before it is returned. */
internal fun interface LegacySecretReencryptor {
    suspend fun reencryptAndVerify(
        reference: CredentialSecretReference,
        cleartext: ByteArray,
    ): EncryptedCredentialRecord
}

internal class AesGcmLegacySecretReencryptor(
    private val keys: CredentialKeyProvider,
) : LegacySecretReencryptor {
    override suspend fun reencryptAndVerify(
        reference: CredentialSecretReference,
        cleartext: ByteArray,
    ): EncryptedCredentialRecord {
        var expectedDigest: ByteArray? = null
        var reopenedDigest: ByteArray? = null
        var encrypted: EncryptedCredentialRecord? = null
        try {
            expectedDigest = MessageDigest.getInstance(DIGEST).digest(cleartext)
            val records = SingleCredentialRecordStore()
            val verifier = AesGcmCredentialStore(records, keys)
            encrypted = try {
                verifier.encryptAndWipe(reference, cleartext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw LegacySecretReencryptionException(
                    LegacySecretReencryptionStage.ENCRYPT,
                    failure,
                )
            }
            records.record = encrypted
            val reopenedMatches = try {
                verifier.withSecret(reference) { reopened ->
                    reopenedDigest = MessageDigest.getInstance(DIGEST).digest(reopened)
                    MessageDigest.isEqual(expectedDigest, reopenedDigest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw LegacySecretReencryptionException(
                    LegacySecretReencryptionStage.REOPEN,
                    failure,
                )
            }
            if (!reopenedMatches) {
                throw LegacySecretReencryptionException(LegacySecretReencryptionStage.REOPEN)
            }
            return encrypted
        } finally {
            cleartext.fill(0)
            expectedDigest?.fill(0)
            reopenedDigest?.fill(0)
        }
    }

    private class SingleCredentialRecordStore : CredentialCiphertextStore {
        var record: EncryptedCredentialRecord? = null

        override suspend fun read(secretId: SecretId): EncryptedCredentialRecord? = record

        override suspend fun write(record: EncryptedCredentialRecord) {
            this.record = record
        }

        override suspend fun delete(secretId: SecretId): Boolean {
            val found = record != null
            record = null
            return found
        }
    }

    private companion object {
        const val DIGEST = "SHA-256"
    }
}

/**
 * One-time, restart-safe import from the retained legacy files into Room.
 *
 * This coordinator has no delete operation for any source. Cleartext sidecars are re-encrypted and
 * reopened in memory, then all ciphertext and metadata rows commit with the completion marker in a
 * single Room transaction. A blocked marker remains retryable; any terminal marker makes Room
 * authoritative before the retained source is opened again.
 */
internal class LegacyStartupMigrationCoordinator(
    private val store: LegacyMigrationStore,
    private val userSettingsSource: LegacyUserSettingsSource,
    private val secretsSource: LegacySecretsSource,
    private val knownHostsSource: LegacyKnownHostsSource,
    private val secretReencryptor: LegacySecretReencryptor,
    private val privateKeyMetadataDeriver: LegacyPrivateKeyMetadataDeriver =
        JschLegacyPrivateKeyMetadataDeriver,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
) {
    private val migrationMutex = Mutex()

    suspend fun migrate(): LegacyStartupMigrationResult = migrationMutex.withLock {
        // A whole-source failure must not prevent the other independent source from progressing.
        val userSettings = migrateIsolated(
            sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
            fallbackErrorCode = ERROR_SETTINGS_MIGRATION,
            migrate = ::migrateUserSettings,
        )
        val knownHosts = migrateIsolated(
            sourceCode = LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE,
            fallbackErrorCode = ERROR_KNOWN_HOSTS_MIGRATION,
            migrate = ::migrateKnownHosts,
        )
        LegacyStartupMigrationResult(userSettings, knownHosts)
    }

    /**
     * Completes an explicitly confirmed settings recovery without reopening or deleting retained
     * legacy input. Default profiles and the tombstone commit in the same Room transaction.
     */
    suspend fun discardUserSettingsAfterRecovery(): LegacyMigrationOutcome =
        migrationMutex.withLock {
            discardAfterRecovery(
                sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
                batch = LegacyMigrationBatchBuilder.buildDefaultUserSettingsTerminal(
                    stateCode = LegacyMigrationBatchBuilder.STATE_DISCARDED_AFTER_RECOVERY,
                    migratedAtEpochMillis = checkedNow(),
                ).requireUserSettingsTargetCompatibility(),
            )
        }

    /**
     * Completes an explicitly confirmed known-host recovery without reopening or deleting the
     * retained known-host file. The empty authoritative tombstone is committed transactionally.
     */
    suspend fun discardKnownHostsAfterRecovery(): LegacyMigrationOutcome =
        migrationMutex.withLock {
            discardAfterRecovery(
                sourceCode = LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE,
                batch = LegacyMigrationBatchBuilder.buildEmptyKnownHostsTerminal(
                    stateCode = LegacyMigrationBatchBuilder.STATE_DISCARDED_AFTER_RECOVERY,
                    migratedAtEpochMillis = checkedNow(),
                ).requireKnownHostsTargetCompatibility(),
            )
        }

    private suspend inline fun migrateIsolated(
        sourceCode: String,
        fallbackErrorCode: String,
        crossinline migrate: suspend () -> LegacyMigrationOutcome,
    ): LegacyMigrationOutcome = try {
        migrate()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        try {
            recordBlocked(
                sourceCode = sourceCode,
                sourceDigestSha256 = null,
                sourceVersion = null,
                errorCode = fallbackErrorCode,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LegacyMigrationOutcome(
                sourceCode = sourceCode,
                outcome = LegacyMigrationSourceOutcome.BLOCKED,
                errorCode = fallbackErrorCode,
            )
        }
    }

    private suspend fun migrateUserSettings(): LegacyMigrationOutcome {
        val existing = store.findState(LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE)
        if (existing?.isSuccessfulCompletion() == true) {
            return alreadyApplied(LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE)
        }
        return when (val source = userSettingsSource.read()) {
            LegacyUserSettingsReadResult.Missing -> if (existing.canWriteAbsentMarker()) {
                insertBatchOrBlock(
                    LegacyMigrationBatchBuilder.buildDefaultUserSettingsTerminal(
                        stateCode = LegacyMigrationBatchBuilder.STATE_ABSENT,
                        migratedAtEpochMillis = checkedNow(),
                    ).requireUserSettingsTargetCompatibility(),
                )
            } else {
                missingOutcome(LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE, existing)
            }
            is LegacyUserSettingsReadResult.Blocked -> {
                recordBlocked(
                    sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
                    sourceDigestSha256 = null,
                    sourceVersion = null,
                    errorCode = source.failure.errorCode(),
                )
            }
            is LegacyUserSettingsReadResult.Loaded -> migrateLoadedUserSettings(source)
        }
    }

    private suspend fun migrateLoadedUserSettings(
        source: LegacyUserSettingsReadResult.Loaded,
    ): LegacyMigrationOutcome {
        val prepared = try {
            prepareSecrets(source.settings.profiles, source.settings.identities)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LegacySecretReencryptionException) {
            return recordBlocked(
                sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
                sourceDigestSha256 = source.sourceDigestSha256,
                sourceVersion = source.sourceVersion,
                errorCode = failure.stage.errorCode,
            )
        } catch (_: Exception) {
            return recordBlocked(
                sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
                sourceDigestSha256 = source.sourceDigestSha256,
                sourceVersion = source.sourceVersion,
                errorCode = ERROR_INVALID_SOURCE,
            )
        }

        val batch = try {
            LegacyMigrationBatchBuilder.buildUserSettings(
                source = source,
                secretInputs = prepared.inputs,
                migratedAtEpochMillis = checkedNow(),
            ).withPrivateKeyMetadata(prepared.privateKeyMetadata)
                .requireUserSettingsTargetCompatibility()
        } catch (_: IllegalArgumentException) {
            return recordBlocked(
                sourceCode = LegacyMigrationBatchBuilder.USER_SETTINGS_SOURCE,
                sourceDigestSha256 = source.sourceDigestSha256,
                sourceVersion = source.sourceVersion,
                errorCode = ERROR_INVALID_SOURCE,
            )
        }
        return insertBatchOrBlock(batch)
    }

    private suspend fun prepareSecrets(
        profiles: List<SavedSshProfile>,
        identities: List<SavedSshIdentity>,
    ): PreparedLegacySecrets {
        val passwords = profiles.filter(SavedSshProfile::hasSavedPassword).map { profile ->
            LegacyPasswordSecretMigration(
                legacyProfileId = profile.id,
                result = migratePassword(profile),
            )
        }
        val privateKeyMetadata = mutableMapOf<String, LegacyPrivateKeyMetadata>()
        val privateKeys = identities.map { identity ->
            LegacyPrivateKeySecretMigration(
                legacyIdentityId = identity.id,
                result = migratePrivateKey(identity, privateKeyMetadata),
            )
        }
        return PreparedLegacySecrets(
            inputs = LegacySecretMigrationInputs(passwords, privateKeys),
            privateKeyMetadata = privateKeyMetadata.toMap(),
        )
    }

    private suspend fun migratePassword(profile: SavedSshProfile): LegacySecretMigrationResult {
        val read = try {
            secretsSource.readPassword(
                SshPasswordScope(profile.id, profile.host, profile.port, profile.username),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(LegacySecretUnavailableReason.IO_UNAVAILABLE)
        }
        return when (read) {
            is LegacySecretReadResult.Unavailable -> read.toMigrationResult()
            is LegacySecretReadResult.Loaded -> withConsumedCleartext(read.cleartext) { cleartext ->
                val reference = CredentialSecretReference(
                    credentialId = CredentialId.parseCanonical(
                        LegacyIds.passwordCredential(profile.id),
                    ),
                    secretId = SecretId.parseCanonical(LegacyIds.passwordSecret(profile.id)),
                    kind = CredentialSecretKind.PASSWORD,
                )
                reencryptOrUnavailable(reference, cleartext)
            }
        }
    }

    private suspend fun migratePrivateKey(
        identity: SavedSshIdentity,
        metadata: MutableMap<String, LegacyPrivateKeyMetadata>,
    ): LegacySecretMigrationResult {
        val read = try {
            secretsSource.readPrivateKey(identity.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(LegacySecretUnavailableReason.IO_UNAVAILABLE)
        }
        return when (read) {
            is LegacySecretReadResult.Unavailable -> read.toMigrationResult()
            is LegacySecretReadResult.Loaded -> withConsumedCleartext(read.cleartext) { cleartext ->
                val derived = try {
                    privateKeyMetadataDeriver.derive(cleartext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                derived?.takeIf { it.isValid() }?.let { valid ->
                    metadata[LegacyIds.keyIdentity(identity.id)] = valid
                }
                val reference = CredentialSecretReference(
                    credentialId = CredentialId.parseCanonical(LegacyIds.keyIdentity(identity.id)),
                    secretId = SecretId.parseCanonical(LegacyIds.privateKeySecret(identity.id)),
                    kind = CredentialSecretKind.PRIVATE_KEY,
                )
                reencryptOrUnavailable(reference, cleartext)
            }
        }
    }

    private suspend fun reencryptOrUnavailable(
        reference: CredentialSecretReference,
        cleartext: ByteArray,
    ): LegacySecretMigrationResult = try {
        LegacySecretMigrationResult.Ready(
            secretReencryptor.reencryptAndVerify(reference, cleartext),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LegacySecretReencryptionException) {
        unavailable(failure.toUnavailableReason())
    } catch (_: Exception) {
        unavailable(LegacySecretUnavailableReason.IO_UNAVAILABLE)
    }

    private suspend fun migrateKnownHosts(): LegacyMigrationOutcome {
        val existing = store.findState(LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE)
        if (existing?.isSuccessfulCompletion() == true) {
            return alreadyApplied(LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE)
        }
        return when (val source = knownHostsSource.read()) {
            LegacyKnownHostsReadResult.Missing -> if (existing.canWriteAbsentMarker()) {
                insertBatchOrBlock(
                    LegacyMigrationBatchBuilder.buildEmptyKnownHostsTerminal(
                        stateCode = LegacyMigrationBatchBuilder.STATE_ABSENT,
                        migratedAtEpochMillis = checkedNow(),
                    ).requireKnownHostsTargetCompatibility(),
                )
            } else {
                missingOutcome(LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE, existing)
            }
            is LegacyKnownHostsReadResult.Blocked -> {
                recordBlocked(
                    sourceCode = LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE,
                    sourceDigestSha256 = null,
                    sourceVersion = null,
                    errorCode = source.failure.errorCode(),
                )
            }
            is LegacyKnownHostsReadResult.Loaded -> {
                val batch = try {
                    LegacyMigrationBatchBuilder.buildKnownHosts(
                        sourceDigestSha256 = source.sourceDigestSha256,
                        parsed = LegacyKnownHostsParser.parse(source.source),
                        migratedAtEpochMillis = checkedNow(),
                    ).requireKnownHostsTargetCompatibility()
                } catch (_: IllegalArgumentException) {
                    return recordBlocked(
                        sourceCode = LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE,
                        sourceDigestSha256 = source.sourceDigestSha256,
                        sourceVersion = null,
                        errorCode = ERROR_INVALID_SOURCE,
                    )
                }
                insertBatchOrBlock(batch)
            }
        }
    }

    private suspend fun discardAfterRecovery(
        sourceCode: String,
        batch: LegacyMigrationBatch,
    ): LegacyMigrationOutcome {
        val existing = store.findState(sourceCode)
        if (existing?.isSuccessfulCompletion() == true) return alreadyApplied(sourceCode)
        if (
            existing == null ||
            existing.stateCode != LegacyMigrationStateEntity.STATE_BLOCKED ||
            !existing.isRetryableBlocked()
        ) {
            throw LegacyRecoveryDiscardUnavailableException(sourceCode)
        }
        check(batch.completion.sourceCode == sourceCode)
        check(
            batch.completion.stateCode ==
                LegacyMigrationBatchBuilder.STATE_DISCARDED_AFTER_RECOVERY,
        )
        return insertBatchOrBlock(batch)
    }

    private suspend fun insertBatchOrBlock(batch: LegacyMigrationBatch): LegacyMigrationOutcome =
        try {
            when (store.insertBatch(batch)) {
                LegacyMigrationInsertResult.INSERTED -> LegacyMigrationOutcome(
                    sourceCode = batch.completion.sourceCode,
                    outcome = LegacyMigrationSourceOutcome.APPLIED,
                )
                LegacyMigrationInsertResult.ALREADY_APPLIED ->
                    alreadyApplied(batch.completion.sourceCode)
            }
        } catch (_: LegacyMigrationSourceConflictException) {
            LegacyMigrationOutcome(
                sourceCode = batch.completion.sourceCode,
                outcome = LegacyMigrationSourceOutcome.BLOCKED,
                errorCode = ERROR_SOURCE_CHANGED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordBlocked(
                sourceCode = batch.completion.sourceCode,
                sourceDigestSha256 = batch.completion.sourceDigestSha256,
                sourceVersion = batch.completion.sourceVersion,
                errorCode = ERROR_TARGET_TRANSACTION,
            )
        }

    private suspend fun recordBlocked(
        sourceCode: String,
        sourceDigestSha256: String?,
        sourceVersion: Int?,
        errorCode: String,
    ): LegacyMigrationOutcome {
        val timestamp = checkedNow()
        val recorded = store.recordBlocked(
            LegacyMigrationStateEntity(
                sourceCode = sourceCode,
                sourceDigestSha256 = sourceDigestSha256,
                sourceVersion = sourceVersion,
                stateCode = STATE_BLOCKED,
                errorCode = errorCode,
                warningCodes = "",
                lastAttemptAtEpochMillis = timestamp,
                completedAtEpochMillis = null,
            ),
        )
        return if (recorded.isSuccessfulCompletion()) {
            alreadyApplied(sourceCode)
        } else {
            LegacyMigrationOutcome(sourceCode, LegacyMigrationSourceOutcome.BLOCKED, errorCode)
        }
    }

    private fun missingOutcome(
        sourceCode: String,
        existing: LegacyMigrationStateEntity?,
    ): LegacyMigrationOutcome = when {
        existing?.isSuccessfulCompletion() == true -> alreadyApplied(sourceCode)
        existing != null -> LegacyMigrationOutcome(
            sourceCode = sourceCode,
            outcome = LegacyMigrationSourceOutcome.BLOCKED,
            errorCode = existing.errorCode ?: ERROR_SOURCE_MISSING_AFTER_ATTEMPT,
        )
        else -> LegacyMigrationOutcome(sourceCode, LegacyMigrationSourceOutcome.NO_SOURCE)
    }

    private fun LegacyMigrationStateEntity?.canWriteAbsentMarker(): Boolean =
        this == null ||
            (
                stateCode == LegacyMigrationStateEntity.STATE_BLOCKED &&
                    errorCode == ERROR_TARGET_TRANSACTION &&
                    sourceDigestSha256 == null &&
                    sourceVersion == null
                )

    private fun alreadyApplied(sourceCode: String) = LegacyMigrationOutcome(
        sourceCode = sourceCode,
        outcome = LegacyMigrationSourceOutcome.ALREADY_APPLIED,
    )

    private fun LegacySecretReadResult.Unavailable.toMigrationResult() =
        LegacySecretMigrationResult.Unavailable(
            when (failure) {
                LegacySecretFailure.MISSING -> LegacySecretUnavailableReason.MISSING
                LegacySecretFailure.KEY_UNAVAILABLE -> LegacySecretUnavailableReason.KEY_UNAVAILABLE
                LegacySecretFailure.CORRUPT -> LegacySecretUnavailableReason.CORRUPT
                LegacySecretFailure.UNSUPPORTED_VERSION ->
                    LegacySecretUnavailableReason.UNSUPPORTED_VERSION
                LegacySecretFailure.IO_UNAVAILABLE -> LegacySecretUnavailableReason.IO_UNAVAILABLE
            },
        )

    private fun LegacySecretReencryptionException.toUnavailableReason():
        LegacySecretUnavailableReason = when (stage) {
        LegacySecretReencryptionStage.REOPEN -> when (cause) {
            is CredentialStoreException.Tampered -> LegacySecretUnavailableReason.TAMPERED
            else -> LegacySecretUnavailableReason.REOPEN_FAILED
        }
        LegacySecretReencryptionStage.ENCRYPT -> when (cause) {
            is CredentialStoreException.KeyUnavailable -> LegacySecretUnavailableReason.KEY_UNAVAILABLE
            is CredentialStoreException.Malformed -> LegacySecretUnavailableReason.CORRUPT
            else -> LegacySecretUnavailableReason.IO_UNAVAILABLE
        }
    }

    private fun unavailable(reason: LegacySecretUnavailableReason) =
        LegacySecretMigrationResult.Unavailable(reason)

    private fun LegacyPrivateKeyMetadata.isValid(): Boolean =
        algorithm.isNotBlank() &&
            algorithm.length <= 32 &&
            algorithm.none(Char::isWhitespace) &&
            fingerprintSha256.startsWith("SHA256:") &&
            fingerprintSha256.length <= 128 &&
            publicKey.isNotEmpty()

    private fun LegacyUserSettingsFailure.errorCode(): String = when (this) {
        LegacyUserSettingsFailure.KEY_UNAVAILABLE -> "settings_key_unavailable"
        LegacyUserSettingsFailure.CORRUPT -> "settings_corrupt"
        LegacyUserSettingsFailure.UNSUPPORTED_VERSION -> "settings_unsupported_version"
        LegacyUserSettingsFailure.IO_UNAVAILABLE -> "settings_io_unavailable"
    }

    private fun LegacyKnownHostsFailure.errorCode(): String = when (this) {
        LegacyKnownHostsFailure.CORRUPT -> "known_hosts_corrupt"
        LegacyKnownHostsFailure.IO_UNAVAILABLE -> "known_hosts_io_unavailable"
    }

    private fun LegacyMigrationBatch.withPrivateKeyMetadata(
        metadata: Map<String, LegacyPrivateKeyMetadata>,
    ): LegacyMigrationBatch = copy(
        keyIdentities = keyIdentities.map { identity ->
            metadata[identity.id]?.let { derived ->
                identity.copy(
                    algorithmCode = derived.algorithm,
                    fingerprint = derived.fingerprintSha256,
                    publicKey = derived.publicKey.copyOf(),
                )
            } ?: identity
        },
    )

    private suspend inline fun <T> withConsumedCleartext(
        cleartext: ByteArray,
        use: suspend (ByteArray) -> T,
    ): T = try {
        use(cleartext)
    } finally {
        cleartext.fill(0)
    }

    private fun checkedNow(): Long = clock.nowEpochMillis().also { timestamp ->
        require(timestamp in 0..MAX_SUPPORTED_EPOCH_MILLIS) {
            "Migration timestamp is outside the supported range."
        }
    }

    private data class PreparedLegacySecrets(
        val inputs: LegacySecretMigrationInputs,
        val privateKeyMetadata: Map<String, LegacyPrivateKeyMetadata>,
    )

    companion object {
        const val STATE_BLOCKED = LegacyMigrationStateEntity.STATE_BLOCKED

        fun create(
            context: Context,
            database: AppDatabase,
            credentialKeys: CredentialKeyProvider,
            clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
        ): LegacyStartupMigrationCoordinator {
            val appContext = context.applicationContext
            return LegacyStartupMigrationCoordinator(
                store = RoomLegacyMigrationStore(database),
                userSettingsSource = LegacyUserSettingsSourceAdapter(
                    LegacyUserSettingsReader(appContext),
                ),
                secretsSource = LegacySecretsSourceAdapter(LegacySecretReaders(appContext)),
                knownHostsSource = FileLegacyKnownHostsSource(
                    appContext.filesDir.resolve(KNOWN_HOSTS_FILE),
                ),
                secretReencryptor = AesGcmLegacySecretReencryptor(credentialKeys),
                clock = clock,
            )
        }

        private const val KNOWN_HOSTS_FILE = "known_hosts"
        private const val ERROR_INVALID_SOURCE = "invalid_source"
        private const val ERROR_SETTINGS_MIGRATION = "settings_migration_failed"
        private const val ERROR_KNOWN_HOSTS_MIGRATION = "known_hosts_migration_failed"
        private const val ERROR_SOURCE_CHANGED = "source_changed_after_completion"
        private const val ERROR_SOURCE_MISSING_AFTER_ATTEMPT = "source_missing_after_attempt"
        private const val ERROR_TARGET_TRANSACTION = "target_transaction_failed"
        private const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
    }
}
