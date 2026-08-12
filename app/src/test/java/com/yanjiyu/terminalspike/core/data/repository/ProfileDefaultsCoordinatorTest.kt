package com.yanjiyu.terminalspike.core.data.repository

import androidx.datastore.core.DataStoreFactory
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileDao
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileDao
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileEntity
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileDefaultsCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun selectingDefaultRequiresAnExistingValidProfile() = runBlocking {
        val owner = dataStoreOwner("selection.pb")
        try {
            val terminals = FakeTerminalProfileDao(
                terminalProfile(DEFAULT_TERMINAL_ID),
                terminalProfile(TERMINAL_REPLACEMENT_ID),
                terminalProfile(CORRUPT_TERMINAL_ID).copy(name = ""),
            )
            val coordinator = ProfileDefaultsCoordinator(
                owner.repository,
                terminals,
                FakeKeyboardProfileDao(keyboardProfile(DEFAULT_KEYBOARD_ID)),
            )

            assertEquals(
                SelectDefaultProfileResult.ProfileNotFound(MISSING_ID),
                coordinator.selectTerminalDefault(MISSING_ID),
            )
            assertEquals(
                DEFAULT_TERMINAL_ID,
                owner.repository.settings.first().defaultTerminalProfileId,
            )
            expectFailure(CorruptStoredDataException::class.java) {
                coordinator.selectTerminalDefault(CORRUPT_TERMINAL_ID)
            }
            assertEquals(
                DEFAULT_TERMINAL_ID,
                owner.repository.settings.first().defaultTerminalProfileId,
            )
            assertEquals(
                SelectDefaultProfileResult.Selected(TERMINAL_REPLACEMENT_ID),
                coordinator.selectTerminalDefault(TERMINAL_REPLACEMENT_ID),
            )
            assertEquals(
                TERMINAL_REPLACEMENT_ID,
                owner.repository.settings.first().defaultTerminalProfileId,
            )
            assertEquals(
                SelectDefaultProfileResult.Selected(DEFAULT_KEYBOARD_ID),
                coordinator.selectKeyboardDefault(DEFAULT_KEYBOARD_ID),
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun deletingActiveDefaultRequiresExistingDifferentReplacementAndCommitsItFirst() = runBlocking {
        val owner = dataStoreOwner("successful-delete.pb")
        try {
            val terminals = FakeTerminalProfileDao(
                terminalProfile(DEFAULT_TERMINAL_ID),
                terminalProfile(TERMINAL_REPLACEMENT_ID),
            )
            val coordinator = ProfileDefaultsCoordinator(
                owner.repository,
                terminals,
                FakeKeyboardProfileDao(keyboardProfile(DEFAULT_KEYBOARD_ID)),
            )

            assertEquals(
                DeleteProfileResult.ReplacementRequired(DEFAULT_TERMINAL_ID),
                coordinator.deleteTerminalProfile(DEFAULT_TERMINAL_ID),
            )
            assertEquals(
                DeleteProfileResult.InvalidReplacement(DEFAULT_TERMINAL_ID),
                coordinator.deleteTerminalProfile(DEFAULT_TERMINAL_ID, DEFAULT_TERMINAL_ID),
            )
            assertEquals(
                DeleteProfileResult.ReplacementNotFound(MISSING_ID),
                coordinator.deleteTerminalProfile(DEFAULT_TERMINAL_ID, MISSING_ID),
            )

            assertEquals(
                DeleteProfileResult.Deleted(DEFAULT_TERMINAL_ID, TERMINAL_REPLACEMENT_ID),
                coordinator.deleteTerminalProfile(DEFAULT_TERMINAL_ID, TERMINAL_REPLACEMENT_ID),
            )
            assertNull(terminals.findById(DEFAULT_TERMINAL_ID))
            assertNotNull(terminals.findById(TERMINAL_REPLACEMENT_ID))
            assertEquals(
                TERMINAL_REPLACEMENT_ID,
                owner.repository.settings.first().defaultTerminalProfileId,
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun keyboardDefaultDeletionUsesTheSameReplacementProtocol() = runBlocking {
        val owner = dataStoreOwner("keyboard-delete.pb")
        try {
            val keyboards = FakeKeyboardProfileDao(
                keyboardProfile(DEFAULT_KEYBOARD_ID),
                keyboardProfile(KEYBOARD_REPLACEMENT_ID),
            )
            val coordinator = ProfileDefaultsCoordinator(
                owner.repository,
                FakeTerminalProfileDao(terminalProfile(DEFAULT_TERMINAL_ID)),
                keyboards,
            )

            assertEquals(
                DeleteProfileResult.Deleted(DEFAULT_KEYBOARD_ID, KEYBOARD_REPLACEMENT_ID),
                coordinator.deleteKeyboardProfile(DEFAULT_KEYBOARD_ID, KEYBOARD_REPLACEMENT_ID),
            )
            assertNull(keyboards.findWithKeys(DEFAULT_KEYBOARD_ID))
            assertNotNull(keyboards.findWithKeys(KEYBOARD_REPLACEMENT_ID))
            assertEquals(
                KEYBOARD_REPLACEMENT_ID,
                owner.repository.settings.first().defaultKeyboardProfileId,
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun failedRoomDeletionLeavesPersistedReplacementResolvableAfterDataStoreRecreate() = runBlocking {
        val file = settingsFile("failed-delete.pb")
        val terminals = FakeTerminalProfileDao(
            terminalProfile(DEFAULT_TERMINAL_ID),
            terminalProfile(TERMINAL_REPLACEMENT_ID),
        ).also { it.deleteFailure = IOException("simulated Room write failure") }
        val firstOwner = dataStoreOwner(file)
        val firstCoordinator = ProfileDefaultsCoordinator(
            firstOwner.repository,
            terminals,
            FakeKeyboardProfileDao(keyboardProfile(DEFAULT_KEYBOARD_ID)),
        )

        try {
            expectFailure(IOException::class.java) {
                firstCoordinator.deleteTerminalProfile(
                    DEFAULT_TERMINAL_ID,
                    TERMINAL_REPLACEMENT_ID,
                )
            }
        } finally {
            firstOwner.close()
        }

        val recreatedOwner = dataStoreOwner(file)
        try {
            val recreatedSettings = recreatedOwner.repository.settings.first()
            assertEquals(TERMINAL_REPLACEMENT_ID, recreatedSettings.defaultTerminalProfileId)
            assertNotNull(terminals.findById(recreatedSettings.defaultTerminalProfileId)?.toDomainModel())
            val recreatedCoordinator = ProfileDefaultsCoordinator(
                recreatedOwner.repository,
                terminals,
                FakeKeyboardProfileDao(keyboardProfile(DEFAULT_KEYBOARD_ID)),
            )
            assertEquals(
                SelectDefaultProfileResult.Selected(TERMINAL_REPLACEMENT_ID),
                recreatedCoordinator.selectTerminalDefault(recreatedSettings.defaultTerminalProfileId),
            )
        } finally {
            recreatedOwner.close()
        }
    }

    private fun dataStoreOwner(name: String): DataStoreOwner = dataStoreOwner(settingsFile(name))

    private suspend fun <T : Throwable> expectFailure(
        type: Class<T>,
        block: suspend () -> Unit,
    ): T = try {
        block()
        fail("Expected ${type.simpleName}.")
        throw AssertionError("unreachable")
    } catch (failure: Throwable) {
        if (!type.isInstance(failure)) throw failure
        type.cast(failure)
    }

    private fun dataStoreOwner(file: File): DataStoreOwner {
        val job = SupervisorJob()
        return DataStoreOwner(
            repository = AppSettingsRepository(
                DataStoreFactory.create(
                    serializer = AppSettingsSerializer,
                    scope = CoroutineScope(job + Dispatchers.IO),
                    produceFile = { file },
                ),
            ),
            job = job,
        )
    }

    private fun settingsFile(name: String): File = temporaryFolder.newFile(name).also { file ->
        file.writeBytes(AppSettingsSerializer.defaultValue.toByteArray())
    }

    private data class DataStoreOwner(
        val repository: AppSettingsRepository,
        val job: CompletableJob,
    ) {
        suspend fun close() = job.cancelAndJoin()
    }

    private class FakeTerminalProfileDao(vararg initial: TerminalProfileEntity) : TerminalProfileDao {
        private val rows = MutableStateFlow(initial.toList())
        var deleteFailure: IOException? = null

        override fun observeAll(): Flow<List<TerminalProfileEntity>> = rows

        override suspend fun findById(id: String): TerminalProfileEntity? = rows.value.find { it.id == id }

        override suspend fun insert(profile: TerminalProfileEntity) {
            check(rows.value.none { it.id == profile.id })
            rows.value += profile
        }

        override suspend fun update(profile: TerminalProfileEntity): Int {
            val index = rows.value.indexOfFirst { it.id == profile.id }
            if (index < 0) return 0
            rows.value = rows.value.toMutableList().also { it[index] = profile }
            return 1
        }

        override suspend fun deleteById(id: String): Int {
            deleteFailure?.let { throw it }
            val remaining = rows.value.filterNot { it.id == id }
            if (remaining.size == rows.value.size) return 0
            rows.value = remaining
            return 1
        }
    }

    private class FakeKeyboardProfileDao(vararg initial: KeyboardProfileRows) : KeyboardProfileDao() {
        private val profiles = MutableStateFlow(initial.map(KeyboardProfileRows::profile))
        private val keys = initial.associate { it.profile.id to it.keys }.toMutableMap()

        override fun observeAll(): Flow<List<KeyboardProfileEntity>> = profiles

        override fun observeAllWithKeys(): Flow<List<KeyboardProfileWithKeys>> = profiles.map { rows ->
            rows.map { KeyboardProfileWithKeys(it, keys[it.id].orEmpty()) }
        }

        override suspend fun findById(id: String): KeyboardProfileEntity? = profiles.value.find { it.id == id }

        override suspend fun findWithKeys(id: String): KeyboardProfileWithKeys? =
            findById(id)?.let { KeyboardProfileWithKeys(it, findKeys(id)) }

        override suspend fun findKeys(profileId: String): List<KeyboardProfileKeyEntity> =
            keys[profileId].orEmpty()

        override suspend fun insert(profile: KeyboardProfileEntity) {
            profiles.value += profile
        }

        override suspend fun insertKeys(keys: List<KeyboardProfileKeyEntity>) {
            keys.groupBy(KeyboardProfileKeyEntity::profileId).forEach { (profileId, rows) ->
                this.keys[profileId] = this.keys[profileId].orEmpty() + rows
            }
        }

        override suspend fun update(profile: KeyboardProfileEntity): Int {
            val index = profiles.value.indexOfFirst { it.id == profile.id }
            if (index < 0) return 0
            profiles.value = profiles.value.toMutableList().also { it[index] = profile }
            return 1
        }

        override suspend fun deleteKeys(profileId: String): Int = keys.remove(profileId)?.size ?: 0

        override suspend fun deleteById(id: String): Int {
            val remaining = profiles.value.filterNot { it.id == id }
            if (remaining.size == profiles.value.size) return 0
            profiles.value = remaining
            keys.remove(id)
            return 1
        }
    }

    private fun terminalProfile(id: String) = TerminalProfileEntity(
        id = id,
        name = "Terminal $id",
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
        termType = "xterm-256color",
        retainAlternateScreenHistory = true,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun keyboardProfile(id: String): KeyboardProfileRows = KeyboardProfileRows(
        profile = KeyboardProfileEntity(
            id = id,
            name = "Keyboard $id",
            rowCount = 1,
            modifierPolicyCode = "one_shot",
            hapticEnabled = true,
            keyRepeatEnabled = true,
            inputModeCode = "raw",
            tmuxPrefix = "C-b",
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        ),
        keys = listOf(KeyboardProfileKeyEntity(id, position = 0, actionCode = "escape")),
    )

    private companion object {
        const val DEFAULT_TERMINAL_ID = "df558cdb-05fb-50f3-baf9-e7dd6e911ce5"
        const val DEFAULT_KEYBOARD_ID = "f23f85fd-3122-5f8b-b28a-d0320f402866"
        const val TERMINAL_REPLACEMENT_ID = "00000000-0000-4000-8000-000000000001"
        const val CORRUPT_TERMINAL_ID = "00000000-0000-4000-8000-000000000002"
        const val MISSING_ID = "00000000-0000-4000-8000-000000000003"
        const val KEYBOARD_REPLACEMENT_ID = "00000000-0000-4000-8000-000000000004"
    }
}
