package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BackupCustomFontReconciliationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun inFlightImportKeepsStagedFilesUntilItsReferencesCommit() = runTest {
        val authority = readyAuthority()
        val font = JournalledFont(temporaryFolder.newFolder())
        val prepared = CompletableDeferred<Unit>()
        val finishImport = CompletableDeferred<Unit>()
        var references = emptySet<String>()
        var referenceReads = 0
        val importing = async {
            authority.withMutation {
                val files = font.prepare(emptyList())
                prepared.complete(Unit)
                finishImport.await()
                references = setOf(FONT_ID)
                markCommitted()
                files.commit()
            }
        }
        prepared.await()
        val reconciling = async {
            font.reconcileAfterCutover(authority) {
                referenceReads++
                references
            }
        }

        runCurrent()
        assertFalse(reconciling.isCompleted)
        assertEquals(0, referenceReads)
        assertTrue(font.file.exists())

        finishImport.complete(Unit)
        importing.await()
        reconciling.await()
        assertEquals(1, referenceReads)
        assertTrue(font.file.exists())
    }

    @Test
    fun importCannotPrepareBetweenReferenceReadAndReconciliation() = runTest {
        val authority = readyAuthority()
        val font = JournalledFont(temporaryFolder.newFolder())
        val referencesRead = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        var references = emptySet<String>()
        val reconciling = async {
            font.reconcileAfterCutover(authority) {
                val captured = references
                referencesRead.complete(Unit)
                finishRead.await()
                captured
            }
        }
        referencesRead.await()
        val importing = async {
            authority.withMutation {
                val files = font.prepare(emptyList())
                references = setOf(FONT_ID)
                markCommitted()
                files.commit()
            }
        }

        runCurrent()
        assertFalse(importing.isCompleted)
        assertFalse(font.file.exists())

        finishRead.complete(Unit)
        reconciling.await()
        importing.await()
        assertTrue(font.file.exists())
    }

    @Test
    fun staleJournalRetainsReferencedFileAndRemovesUnreferencedFile() = runTest {
        val authority = readyAuthority()
        val font = JournalledFont(temporaryFolder.newFolder())
        font.prepare(emptyList())
        font.reconcileAfterCutover(authority) { setOf(FONT_ID) }
        assertTrue(font.file.exists())

        font.prepare(emptyList())
        font.reconcileAfterCutover(authority) { emptySet() }
        assertFalse(font.file.exists())
    }

    @Test
    fun failedReferenceReadLeavesJournalUntouchedAndReleasesAuthority() = runTest {
        val authority = readyAuthority()
        val font = JournalledFont(temporaryFolder.newFolder())
        font.prepare(emptyList())
        val expected = IllegalStateException("Reference read failed")

        val failure = runCatching {
            font.reconcileAfterCutover(authority) { throw expected }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(font.file.exists())
        assertTrue(font.journal.exists())
        authority.withMutation { markCommitted() }
        assertEquals(1L, authority.awaitReady())
    }

    private suspend fun readyAuthority() = AuthoritativeDataGate().also {
        it.resolveStartup({ false }, { error("Unexpected recovery") })
    }

    /** The persistence contract writes files before Room commits and journals them until commit. */
    private class JournalledFont(directory: File) : BackupCustomFontPersistence {
        val file = directory.resolve("font")
        val journal = directory.resolve("journal")

        override fun prepare(fonts: List<BackupCustomFont>): PreparedBackupCustomFontImport {
            file.writeText("font data")
            journal.writeText(FONT_ID)
            return object : PreparedBackupCustomFontImport {
                override fun commit() { journal.delete() }
                override fun rollback() {
                    file.delete()
                    journal.delete()
                }
            }
        }

        override fun reconcilePending(referencedFontIds: Set<String>) {
            if (!journal.exists()) return
            if (FONT_ID !in referencedFontIds) file.delete()
            journal.delete()
        }
    }

    private companion object {
        const val FONT_ID = "custom_test"
    }
}
