package com.yanjiyu.terminalspike.core.data.credential

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialPlaintextPersistenceTest {
    @Test
    fun uniquePlaintextMarkerNeverReachesDatabaseJournalsProtoOrPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        var database: AppDatabase? = null
        try {
            val openedDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                TEST_DATABASE_NAME,
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            database = openedDatabase
            val crypto = AesGcmCredentialStore(
                records = RoomCredentialCiphertextStore(openedDatabase),
                keys = FixedKeyProvider(),
            )
            val aggregate = RoomCredentialAggregateStore(openedDatabase, crypto)
            val plaintext = UNIQUE_PLAINTEXT_MARKER.toByteArray()
            aggregate.saveCredentialAndSecret(
                reference = CredentialSecretReference(
                    credentialId = CredentialId.parseCanonical(CREDENTIAL_ID),
                    secretId = SecretId.parseCanonical(SECRET_ID),
                    kind = CredentialSecretKind.PASSWORD,
                ),
                secret = plaintext,
                credential = SshCredentialEntity(
                    id = CREDENTIAL_ID,
                    name = "Persistence probe",
                    kindCode = "password",
                    secretId = SECRET_ID,
                    keyIdentityId = null,
                    createdAtEpochMillis = 10,
                    updatedAtEpochMillis = 10,
                ),
            )
            assertTrue(plaintext.all { it == 0.toByte() })

            openedDatabase.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { cursor -> while (cursor.moveToNext()) Unit }
            openedDatabase.close()
            database = null

            val marker = UNIQUE_PLAINTEXT_MARKER.toByteArray()
            persistenceFiles(context).forEach { file ->
                assertFalse(
                    "Plaintext marker was persisted in ${file.name}",
                    file.readBytes().containsSubsequence(marker),
                )
            }
        } finally {
            database?.close()
            // This test owns exactly this database name. Do not clear broader app/test storage.
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    private fun persistenceFiles(context: Context): List<File> {
        val database = context.getDatabasePath(TEST_DATABASE_NAME)
        val databaseFiles = listOf(
            database,
            File(database.path + "-wal"),
            File(database.path + "-shm"),
            File(database.path + "-journal"),
        )
        val protoFiles = context.filesDir.walkTopDown()
            .filter { file -> file.isFile && file.extension in setOf("pb", "proto") }
            .toList()
        val preferenceDirectory = File(context.applicationInfo.dataDir, "shared_prefs")
        val preferenceFiles = preferenceDirectory.listFiles()
            ?.filter(File::isFile)
            .orEmpty()
        return (databaseFiles + protoFiles + preferenceFiles).filter(File::isFile)
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }

    private class FixedKeyProvider : CredentialKeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")

        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey = key

        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey = key
    }

    private companion object {
        const val TEST_DATABASE_NAME = "credential-plaintext-persistence-test.db"
        const val CREDENTIAL_ID = "40000000-0000-4000-8000-000000000001"
        const val SECRET_ID = "40000000-0000-4000-8000-000000000002"
        const val UNIQUE_PLAINTEXT_MARKER =
            "TERMINAL_SPIKE_PLAINTEXT_MUST_NEVER_PERSIST_4f6f5b3e2a1c"
    }
}
