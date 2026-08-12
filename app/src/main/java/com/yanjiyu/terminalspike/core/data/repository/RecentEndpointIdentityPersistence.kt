package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.RecentEndpointIdentityBackfillUpdate
import com.yanjiyu.terminalspike.core.data.db.RecentEndpointIdentityBackfillRow
import com.yanjiyu.terminalspike.core.data.db.RecentEndpointIdentityResetResult
import com.yanjiyu.terminalspike.core.data.db.RecentSessionDao
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityKeyState
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityProvider
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityToken
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityUnavailableException

/** Non-secret process-owned seed used to reissue active tokens after a Keystore key replacement. */
internal data class ActiveRecentEndpointIdentitySeed(
    val sessionId: String,
    val protocol: ConnectionProtocol,
    val host: String,
    val port: Int,
    val username: String,
)

internal data class RecentEndpointIdentityBackfillResult(
    val candidatesScanned: Int,
    val tokensStored: Int,
    val hasMore: Boolean,
)

/** Signals that persisted tokens belong to an old key and require generation replacement. */
internal class RecentEndpointIdentityGenerationChangedException(
    val keyState: RecentEndpointIdentityKeyState,
) : IllegalStateException("The device-local recent endpoint identity generation changed.")

/**
 * Authority-gated persistence operations for endpoint tokens.
 *
 * This class never retains joined endpoint plaintext. A database containing only null migration
 * rows has no established HMAC generation, so first key creation may safely backfill it. Once any
 * non-null token exists, a key transition refuses to write until the old generation is replaced.
 */
internal class RecentEndpointIdentityPersistence(
    private val dao: RecentSessionDao,
    private val identityProvider: RecentEndpointIdentityProvider,
    private val authority: AuthoritativeDataGate,
) {
    suspend fun backfillSavedHosts(
        limit: Int = DEFAULT_BACKFILL_LIMIT,
    ): RecentEndpointIdentityBackfillResult {
        require(limit in 1..MAX_BACKFILL_LIMIT) {
            "Recent endpoint identity backfill limit is outside the supported range."
        }
        return authority.withMutation {
            val candidates = dao.findEndpointIdentityBackfillRows(limit)
            val hasPersistedGeneration = dao.countEndpointIdentityTokens() > 0
            val tokenized = tokensForStableBackfill(candidates, hasPersistedGeneration)
            val stored = dao.applyEndpointIdentityBackfill(
                tokenized.map { (candidate, token) ->
                    RecentEndpointIdentityBackfillUpdate(candidate.sessionId, token.value)
                },
            )
            if (stored > 0) markCommitted()
            RecentEndpointIdentityBackfillResult(
                candidatesScanned = candidates.size,
                tokensStored = stored,
                hasMore = candidates.size == limit,
            )
        }
    }

    private fun tokensForStableBackfill(
        candidates: List<RecentEndpointIdentityBackfillRow>,
        hasPersistedGeneration: Boolean,
    ): List<Pair<RecentEndpointIdentityBackfillRow, RecentEndpointIdentityToken>> {
        if (candidates.isEmpty()) return emptyList()
        repeat(MAX_KEY_STABILIZATION_ATTEMPTS) {
            val tokenized = candidates.map { candidate ->
                candidate to identityProvider.create(
                    protocol = ConnectionProtocol.fromWireCode(candidate.protocolCode),
                    host = candidate.hostname,
                    port = candidate.port,
                    username = candidate.username,
                )
            }
            val transition = tokenized.firstOrNull { (_, token) ->
                token.keyState != RecentEndpointIdentityKeyState.EXISTING
            }?.second
            if (transition == null) return tokenized
            if (hasPersistedGeneration) {
                throw RecentEndpointIdentityGenerationChangedException(transition.keyState)
            }
            // With no persisted generation, discard this in-memory batch and recompute after the
            // newly created/replaced key has stabilized. Null migration rows remain untouched.
        }
        throw RecentEndpointIdentityUnavailableException()
    }

    /**
     * Deletes ended history, clears every old active token, and reissues caller-proved active rows
     * in one Room transaction. Seeds must come from the live session owner, not persisted endpoint
     * metadata.
     */
    suspend fun replaceKeyGeneration(
        activeSeeds: List<ActiveRecentEndpointIdentitySeed>,
    ): RecentEndpointIdentityResetResult = authority.withMutation {
        require(activeSeeds.map { it.sessionId }.distinct().size == activeSeeds.size) {
            "Active recent endpoint identity session IDs must be unique."
        }
        val updates = tokensForStableCurrentGeneration(activeSeeds)
        val result = dao.replaceEndpointIdentityGeneration(updates)
        if (
            result.endedRowsDeleted > 0 ||
            result.activeTokensCleared > 0 ||
            result.activeTokensReissued > 0
        ) {
            markCommitted()
        }
        result
    }

    private fun tokensForStableCurrentGeneration(
        activeSeeds: List<ActiveRecentEndpointIdentitySeed>,
    ): List<RecentEndpointIdentityBackfillUpdate> {
        repeat(MAX_KEY_STABILIZATION_ATTEMPTS) {
            var generationChanged = false
            val updates = activeSeeds.map { seed ->
                val token = identityProvider.create(
                    protocol = seed.protocol,
                    host = seed.host,
                    port = seed.port,
                    username = seed.username,
                )
                generationChanged = generationChanged ||
                    token.keyState != RecentEndpointIdentityKeyState.EXISTING
                RecentEndpointIdentityBackfillUpdate(seed.sessionId, token.value)
            }
            if (!generationChanged) return updates
        }
        throw RecentEndpointIdentityGenerationChangedException(
            RecentEndpointIdentityKeyState.REPLACED_INVALIDATED,
        )
    }

    private companion object {
        const val DEFAULT_BACKFILL_LIMIT = 256
        const val MAX_BACKFILL_LIMIT = 1_024
        const val MAX_KEY_STABILIZATION_ATTEMPTS = 2
    }
}
