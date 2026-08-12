package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.HostProfileDao
import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileDao
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.db.KnownHostDao
import com.yanjiyu.terminalspike.core.data.db.KnownHostEntity
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoriesTest {
    @Test
    fun hostRepositoryMapsFlowAndCrudWithoutExposingEntities() = runTest {
        val dao = FakeHostProfileDao()
        val repository = HostProfileRepository(dao)
        val original = validHost()

        repository.insert(original)
        assertEquals(listOf(original), repository.observeAll().first())
        assertEquals(original, repository.get(HOST_ID))

        val renamed = original.copy(displayName = "Renamed", updatedAtEpochMillis = 30)
        assertTrue(repository.update(renamed))
        assertEquals("Renamed", repository.get(HOST_ID)?.displayName)
        assertTrue(repository.delete(HOST_ID))
        assertFalse(repository.delete(HOST_ID))
        assertNull(repository.get(HOST_ID))
    }

    @Test
    fun keyboardRepositoryPersistsAndObservesOrderedKeyRows() = runTest {
        val dao = FakeKeyboardProfileDao()
        val repository = KeyboardProfileRepository(dao)
        val original = validKeyboard(
            listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL, KeyboardAction.ARROW_LEFT),
        )

        repository.insert(original)
        assertEquals(listOf(original), repository.observeAll().first())

        val reordered = original.copy(
            orderedActions = listOf(KeyboardAction.ARROW_LEFT, KeyboardAction.ESCAPE),
            updatedAtEpochMillis = 30,
        )
        assertTrue(repository.update(reordered))
        assertEquals(reordered, repository.get(KEYBOARD_ID))
        assertEquals(listOf(0, 1), dao.findKeys(KEYBOARD_ID).map { it.position })
        assertEquals(listOf("arrow_left", "escape"), dao.findKeys(KEYBOARD_ID).map { it.actionCode })
    }

    @Test
    fun keyboardUpdateReturnsFalseWithoutReplacingKeysWhenProfileIsMissing() = runTest {
        val dao = FakeKeyboardProfileDao()
        val repository = KeyboardProfileRepository(dao)

        assertFalse(repository.update(validKeyboard(listOf(KeyboardAction.ESCAPE))))
        assertEquals(0, dao.deleteKeysCallCount)
        assertTrue(dao.findKeys(KEYBOARD_ID).isEmpty())
    }

    @Test
    fun knownHostRepositoryChecksTheWholeEndpointAndRecordsExactMatches() = runTest {
        val dao = FakeKnownHostDao()
        val repository = KnownHostRepository(dao)
        val imported = validKnownHost(
            id = KNOWN_HOST_ID,
            algorithm = "ssh-ed25519",
            key = byteArrayOf(1, 2, 3),
            firstSeen = null,
            lastSeen = null,
        )
        repository.insert(imported)

        val changedAlgorithm = validKnownHost(
            id = SECOND_KNOWN_HOST_ID,
            algorithm = "rsa-sha2-512",
            key = byteArrayOf(4, 5, 6),
            firstSeen = 30,
            lastSeen = 30,
        )
        val mismatch = repository.verifyAndRecordSeen(changedAlgorithm, seenAtEpochMillis = 30)
        assertTrue(mismatch is KnownHostCheck.Mismatch)
        assertEquals(listOf(imported), (mismatch as KnownHostCheck.Mismatch).trustedKeys)

        val exact = repository.verifyAndRecordSeen(imported, seenAtEpochMillis = 40)
        assertTrue(exact is KnownHostCheck.Trusted)
        val observed = (exact as KnownHostCheck.Trusted).knownHost
        assertEquals(40L, observed.firstSeenAtEpochMillis)
        assertEquals(40L, observed.lastSeenAtEpochMillis)

        val conflict = repository.trustIfUntrusted(changedAlgorithm)
        assertTrue(conflict is KnownHostSave.Conflict)
        assertEquals(listOf(observed), (conflict as KnownHostSave.Conflict).trustedKeys)
    }

    private fun validHost() = HostProfile(
        id = HOST_ID,
        displayName = "Production",
        hostname = "shell.example",
        port = 22,
        username = "operator",
        protocol = ConnectionProtocol.SSH,
        credentialId = null,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun validKeyboard(actions: List<KeyboardAction>) = KeyboardProfile(
        id = KEYBOARD_ID,
        name = "General",
        orderedActions = actions,
        layout = KeyboardLayout.ONE_ROW,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
        hapticFeedbackEnabled = true,
        keyRepeatEnabled = true,
        inputMode = TerminalInputMode.RAW,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun validKnownHost(
        id: String,
        algorithm: String,
        key: ByteArray,
        firstSeen: Long?,
        lastSeen: Long?,
    ) = KnownHost(
        id = id,
        host = "shell.example",
        port = 22,
        keyAlgorithm = algorithm,
        fingerprint = key.sha256Fingerprint(),
        publicHostKey = Base64.getEncoder().encodeToString(key),
        firstSeenAtEpochMillis = firstSeen,
        lastSeenAtEpochMillis = lastSeen,
    )

    private fun ByteArray.sha256Fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private class FakeHostProfileDao : HostProfileDao {
        private val rows = MutableStateFlow<List<HostProfileEntity>>(emptyList())

        override fun observeAll(): Flow<List<HostProfileEntity>> = rows

        override suspend fun findById(id: String): HostProfileEntity? = rows.value.find { it.id == id }

        override suspend fun countCredentialReferences(credentialId: String): Int =
            rows.value.count { it.credentialId == credentialId }

        override fun observeFavorites(): Flow<List<HostProfileEntity>> = MutableStateFlow(
            rows.value.filter { it.isFavorite },
        )

        override suspend fun insert(host: HostProfileEntity) {
            check(rows.value.none { it.id == host.id })
            rows.value += host
        }

        override suspend fun update(host: HostProfileEntity): Int {
            val index = rows.value.indexOfFirst { it.id == host.id }
            if (index < 0) return 0
            rows.value = rows.value.toMutableList().also { it[index] = host }
            return 1
        }

        override suspend fun deleteById(id: String): Int {
            val remaining = rows.value.filterNot { it.id == id }
            if (remaining.size == rows.value.size) return 0
            rows.value = remaining
            return 1
        }
    }

    private class FakeKeyboardProfileDao : KeyboardProfileDao() {
        private val profiles = MutableStateFlow<List<KeyboardProfileEntity>>(emptyList())
        private val keys = mutableMapOf<String, List<KeyboardProfileKeyEntity>>()
        var deleteKeysCallCount: Int = 0
            private set

        override fun observeAll(): Flow<List<KeyboardProfileEntity>> = profiles

        override fun observeAllWithKeys(): Flow<List<KeyboardProfileWithKeys>> =
            profiles.map { rows ->
                rows.map { profile -> KeyboardProfileWithKeys(profile, keys[profile.id].orEmpty()) }
            }

        override suspend fun findById(id: String): KeyboardProfileEntity? = profiles.value.find { it.id == id }

        override suspend fun findWithKeys(id: String): KeyboardProfileWithKeys? =
            findById(id)?.let { KeyboardProfileWithKeys(it, findKeys(id)) }

        override suspend fun findKeys(profileId: String): List<KeyboardProfileKeyEntity> =
            keys[profileId].orEmpty().sortedBy { it.position }

        override suspend fun insert(profile: KeyboardProfileEntity) {
            check(profiles.value.none { it.id == profile.id })
            profiles.value += profile
        }

        override suspend fun insertKeys(keys: List<KeyboardProfileKeyEntity>) {
            keys.groupBy { it.profileId }.forEach { (profileId, newKeys) ->
                this.keys[profileId] = this.keys[profileId].orEmpty() + newKeys
            }
        }

        override suspend fun update(profile: KeyboardProfileEntity): Int {
            val index = profiles.value.indexOfFirst { it.id == profile.id }
            if (index < 0) return 0
            profiles.value = profiles.value.toMutableList().also { it[index] = profile }
            return 1
        }

        override suspend fun deleteKeys(profileId: String): Int {
            deleteKeysCallCount += 1
            return keys.remove(profileId)?.size ?: 0
        }

        override suspend fun deleteById(id: String): Int {
            val remaining = profiles.value.filterNot { it.id == id }
            if (remaining.size == profiles.value.size) return 0
            profiles.value = remaining
            keys.remove(id)
            return 1
        }
    }

    private class FakeKnownHostDao : KnownHostDao() {
        private val rows = MutableStateFlow<List<KnownHostEntity>>(emptyList())

        override fun observeAll(): Flow<List<KnownHostEntity>> = rows

        override suspend fun findForEndpoint(host: String, port: Int): List<KnownHostEntity> =
            rows.value.filter { it.host == host && it.port == port }
                .sortedWith(compareBy(KnownHostEntity::algorithmCode, KnownHostEntity::id))

        override suspend fun find(
            host: String,
            port: Int,
            algorithmCode: String,
        ): KnownHostEntity? = rows.value.find {
            it.host == host && it.port == port && it.algorithmCode == algorithmCode
        }

        override suspend fun insert(knownHost: KnownHostEntity) {
            check(rows.value.none { it.id == knownHost.id })
            check(
                rows.value.none {
                    it.host == knownHost.host &&
                        it.port == knownHost.port &&
                        it.algorithmCode == knownHost.algorithmCode
                },
            )
            rows.value += knownHost
        }

        override suspend fun update(knownHost: KnownHostEntity): Int {
            val index = rows.value.indexOfFirst { it.id == knownHost.id }
            if (index < 0) return 0
            rows.value = rows.value.toMutableList().also { it[index] = knownHost }
            return 1
        }

        override suspend fun delete(knownHost: KnownHostEntity): Int {
            val remaining = rows.value.filterNot { it.id == knownHost.id }
            if (remaining.size == rows.value.size) return 0
            rows.value = remaining
            return 1
        }

        override suspend fun deleteEndpointRows(host: String, port: Int): Int {
            val remaining = rows.value.filterNot { it.host == host && it.port == port }
            val removed = rows.value.size - remaining.size
            rows.value = remaining
            return removed
        }
    }

    private companion object {
        const val HOST_ID = "00000000-0000-4000-8000-000000000001"
        const val KEYBOARD_ID = "00000000-0000-4000-8000-000000000002"
        const val KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000003"
        const val SECOND_KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000004"
    }
}
