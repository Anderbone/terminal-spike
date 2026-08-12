package com.yanjiyu.terminalspike.connection

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.migration.FileLegacyKnownHostsSource
import com.yanjiyu.terminalspike.core.data.migration.LegacyMigrationSourceOutcome
import com.yanjiyu.terminalspike.core.data.migration.LegacySecretReadResult
import com.yanjiyu.terminalspike.core.data.migration.LegacySecretReencryptor
import com.yanjiyu.terminalspike.core.data.migration.LegacySecretsSource
import com.yanjiyu.terminalspike.core.data.migration.LegacyStartupMigrationCoordinator
import com.yanjiyu.terminalspike.core.data.migration.LegacyStartupMigrationResult
import com.yanjiyu.terminalspike.core.data.migration.LegacyUserSettingsReadResult
import com.yanjiyu.terminalspike.core.data.migration.LegacyUserSettingsSource
import com.yanjiyu.terminalspike.core.data.migration.RoomLegacyMigrationStore
import com.yanjiyu.terminalspike.core.data.repository.KnownHostRepository
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomKnownHostTrustStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "room-known-host-trust-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized && database.isOpen) database.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun differentAlgorithmAtKnownEndpointIsChangedNotFirstContact() {
        val store = roomStore()
        val endpoint = KnownHostEndpoint.create("EXAMPLE.test", 22)

        assertEquals(
            FirstHostKeyTrustResult.STORED,
            store.trustFirst(endpoint, "ssh-ed25519", byteArrayOf(1, 2, 3)),
        )

        assertTrue(
            store.check(endpoint, "ssh-rsa", byteArrayOf(4, 5, 6)) is KnownHostTrustCheck.Changed,
        )
        assertEquals(1, store.list(endpoint).size)
    }

    @Test
    fun promptedReplacementAtomicallyRejectsAStaleEndpointSnapshot() {
        val store = roomStore()
        val endpoint = KnownHostEndpoint.create("replace-race.example", 22)
        val original = byteArrayOf(1, 2, 3)
        val offered = byteArrayOf(4, 5, 6)
        val intervening = byteArrayOf(7, 8, 9)
        store.trustFirst(endpoint, "ssh-ed25519", original)

        val prompted = store.check(endpoint, "ssh-ed25519", offered) as
            KnownHostTrustCheck.Changed
        store.replaceEndpoint(endpoint, "ssh-rsa", intervening)

        assertEquals(
            ConditionalHostKeyReplacementResult.STALE,
            store.replaceEndpointIfUnchanged(
                endpoint = endpoint,
                expectedTrustedKeys = prompted.trustedKeys,
                algorithm = "ssh-ed25519",
                key = offered,
            ),
        )
        assertArrayEquals(intervening, store.list(endpoint).single().copyKey())

        val refreshed = store.check(endpoint, "ssh-ed25519", offered) as
            KnownHostTrustCheck.Changed
        assertEquals(
            ConditionalHostKeyReplacementResult.REPLACED,
            store.replaceEndpointIfUnchanged(
                endpoint = endpoint,
                expectedTrustedKeys = refreshed.trustedKeys,
                algorithm = "ssh-ed25519",
                key = offered,
            ),
        )
        assertArrayEquals(offered, store.list(endpoint).single().copyKey())
    }

    @Test
    fun twoConcurrentApprovedFirstKeysCommitExactlyOneEndpointKey() {
        val store = roomStore()
        val endpoint = KnownHostEndpoint.create("race.example", 2222)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<FirstHostKeyTrustResult>())
        val workers = listOf(byteArrayOf(1), byteArrayOf(2)).map { key ->
            thread {
                ready.countDown()
                assertTrue(start.await(2, TimeUnit.SECONDS))
                results += store.trustFirst(endpoint, "ssh-ed25519", key)
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { worker -> worker.join(5_000) }

        assertEquals(1, results.count { it == FirstHostKeyTrustResult.STORED })
        assertEquals(1, results.count { it == FirstHostKeyTrustResult.CHANGED })
        assertEquals(1, store.list(endpoint).size)
    }

    @Test
    fun trustPersistsAcrossReopenAndManagerReplaceListForgetAreEndpointWide() {
        val endpoint = KnownHostEndpoint.create("persist.example", 2200)
        val originalKey = byteArrayOf(7, 8, 9)
        roomStore().trustFirst(endpoint, "ssh-ed25519", originalKey)

        database.close()
        database = openDatabase()
        val manager = KnownHostManager(roomStore())

        val reopened = manager.list().single()
        assertEquals("[persist.example]:2200", reopened.host)
        assertEquals(2200, reopened.port)
        assertEquals("ssh-ed25519", reopened.algorithm)

        val replacementKey = byteArrayOf(10, 11, 12)
        val replacement = manager.replace(
            host = "persist.example",
            port = 2200,
            algorithm = "ssh-rsa",
            key = replacementKey,
        )

        assertEquals("ssh-rsa", replacement.algorithm)
        assertEquals(listOf("ssh-rsa"), manager.list().map { it.algorithm })
        assertEquals(1, manager.forget("persist.example", 2200))
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun migrationGateRunsBeforeFirstRoomReadAndRetainedFileIsNeverFallbackAuthority() {
        val legacyFile = context.cacheDir.resolve("known-hosts-${UUID.randomUUID()}")
        val originalSource = "legacy.example ssh-ed25519 AQIDBA==\n"
        legacyFile.writeText(originalSource)
        val coordinator = migrationCoordinator(legacyFile)
        val gateLock = Mutex()
        var cutover: LegacyStartupMigrationResult? = null
        var gateCalls = 0
        val store = roomStore(
            KnownHostAuthorityGate {
                gateLock.withLock {
                    if (cutover == null) {
                        gateCalls += 1
                        cutover = coordinator.migrate()
                    }
                }
                check(cutover?.knownHosts?.outcome != LegacyMigrationSourceOutcome.BLOCKED)
            },
        )

        try {
            val imported = store.list()

            assertEquals(1, gateCalls)
            assertEquals("legacy.example", imported.single().endpoint.host)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), imported.single().copyKey())
            assertTrue(legacyFile.isFile)
            assertEquals(originalSource, legacyFile.readText())

            legacyFile.writeText(
                originalSource + "file-only.example ssh-ed25519 BQYHCA==\n",
            )

            val authoritativeRoomRows = store.list()
            assertEquals(1, authoritativeRoomRows.size)
            assertEquals("legacy.example", authoritativeRoomRows.single().endpoint.host)
            assertTrue(legacyFile.readText().contains("file-only.example"))
        } finally {
            legacyFile.delete()
        }
    }

    private fun roomStore(
        gate: KnownHostAuthorityGate = KnownHostAuthorityGate { },
    ) = RoomKnownHostTrustStore(
        repository = KnownHostRepository(database.knownHostDao()),
        authorityGate = gate,
        clock = CredentialEpochClock { TIMESTAMP },
        isMainThread = { false },
    )

    private fun migrationCoordinator(legacyFile: java.io.File) =
        LegacyStartupMigrationCoordinator(
            store = RoomLegacyMigrationStore(database),
            userSettingsSource = LegacyUserSettingsSource {
                LegacyUserSettingsReadResult.Missing
            },
            secretsSource = object : LegacySecretsSource {
                override fun readPassword(
                    scope: com.yanjiyu.terminalspike.settings.SshPasswordScope,
                ): LegacySecretReadResult = error("No settings secrets are expected")

                override fun readPrivateKey(identityId: Long): LegacySecretReadResult =
                    error("No settings secrets are expected")
            },
            knownHostsSource = FileLegacyKnownHostsSource(legacyFile),
            secretReencryptor = LegacySecretReencryptor { _, _ ->
                error("No settings secrets are expected")
            },
            clock = CredentialEpochClock { TIMESTAMP },
        )

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        databaseName,
    ).build()

    private companion object {
        const val TIMESTAMP = 1_700_000_000_000L
    }
}
