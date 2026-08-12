package com.yanjiyu.terminalspike.core.backup

/**
 * Filesystem side of a portable font restore.
 *
 * [prepare] must validate and durably journal every newly created content-addressed file before it
 * becomes visible. A database failure calls [PreparedBackupCustomFontImport.rollback]. A process
 * death is resolved later by [reconcilePending] against authoritative Room profile references.
 */
interface BackupCustomFontPersistence {
    fun prepare(fonts: List<BackupCustomFont>): PreparedBackupCustomFontImport

    fun reconcilePending(referencedFontIds: Set<String>)

    companion object {
        val NONE: BackupCustomFontPersistence = object : BackupCustomFontPersistence {
            override fun prepare(fonts: List<BackupCustomFont>): PreparedBackupCustomFontImport =
                PreparedBackupCustomFontImport.NONE

            override fun reconcilePending(referencedFontIds: Set<String>) = Unit
        }
    }
}

interface PreparedBackupCustomFontImport {
    /** Database state now references the prepared files; remove the durable rollback journal. */
    fun commit()

    /** Database state did not commit; remove only files created by this preparation. */
    fun rollback()

    companion object {
        val NONE: PreparedBackupCustomFontImport = object : PreparedBackupCustomFontImport {
            override fun commit() = Unit
            override fun rollback() = Unit
        }
    }
}
