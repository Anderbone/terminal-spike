package com.yanjiyu.terminalspike.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TerminalProfileEntity::class,
        CustomTerminalThemeEntity::class,
        KeyboardProfileEntity::class,
        KeyboardProfileKeyEntity::class,
        EncryptedSecretEntity::class,
        SshKeyIdentityEntity::class,
        SshCredentialEntity::class,
        HostProfileEntity::class,
        RecentSessionEntity::class,
        KnownHostEntity::class,
        SnippetEntity::class,
        LegacyMigrationStateEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun terminalProfileDao(): TerminalProfileDao

    abstract fun customTerminalThemeDao(): CustomTerminalThemeDao

    abstract fun keyboardProfileDao(): KeyboardProfileDao

    abstract fun credentialRecordDao(): CredentialRecordDao

    abstract fun credentialBulkClearDao(): CredentialBulkClearDao

    abstract fun sshKeyIdentityDao(): SshKeyIdentityDao

    abstract fun sshCredentialDao(): SshCredentialDao

    abstract fun hostProfileDao(): HostProfileDao

    abstract fun recentSessionDao(): RecentSessionDao

    abstract fun knownHostDao(): KnownHostDao

    abstract fun snippetDao(): SnippetDao

    abstract fun backupSnapshotDao(): BackupSnapshotDao

    abstract fun backupImportDao(): BackupImportDao

    abstract fun legacyMigrationDao(): LegacyMigrationDao

    companion object {
        const val DATABASE_NAME = "terminal-spike.db"
        const val SCHEMA_VERSION = 3
    }
}
