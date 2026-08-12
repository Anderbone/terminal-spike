package com.yanjiyu.terminalspike.core.data.migration

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationBatch
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationInsertResult
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationSourceConflictException
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationStateEntity

internal interface LegacyMigrationStore {
    suspend fun findState(sourceCode: String): LegacyMigrationStateEntity?

    suspend fun recordBlocked(state: LegacyMigrationStateEntity): LegacyMigrationStateEntity

    suspend fun insertBatch(batch: LegacyMigrationBatch): LegacyMigrationInsertResult
}

/** Room boundary for migration state and the DAO's completion-last INSERT(ABORT) transaction. */
internal class RoomLegacyMigrationStore(
    private val database: AppDatabase,
) : LegacyMigrationStore {
    private val dao
        get() = database.legacyMigrationDao()

    override suspend fun findState(sourceCode: String): LegacyMigrationStateEntity? =
        dao.findState(sourceCode)

    override suspend fun recordBlocked(
        state: LegacyMigrationStateEntity,
    ): LegacyMigrationStateEntity = database.withTransaction {
        require(state.stateCode == LegacyMigrationStateEntity.STATE_BLOCKED)
        require(state.completedAtEpochMillis == null && state.errorCode != null)
        val existing = dao.findState(state.sourceCode)
        if (existing?.isSuccessfulCompletion() == true) return@withTransaction existing
        if (existing == null) {
            dao.insertState(state)
        } else {
            if (!existing.isRetryableBlocked()) {
                throw LegacyMigrationSourceConflictException(state.sourceCode)
            }
            check(dao.updateState(state) == 1) { "Migration state disappeared during blocked update." }
        }
        state
    }

    override suspend fun insertBatch(
        batch: LegacyMigrationBatch,
    ): LegacyMigrationInsertResult = dao.insertBatch(batch)
}

internal fun LegacyMigrationStateEntity.isSuccessfulCompletion(): Boolean =
    isAuthoritativeTerminal()

internal fun LegacyMigrationStateEntity.isRetryableBlocked(): Boolean =
    isRetryableAttempt()
