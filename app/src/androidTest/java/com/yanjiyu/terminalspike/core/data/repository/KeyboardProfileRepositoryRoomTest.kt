package com.yanjiyu.terminalspike.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardProfileRepositoryRoomTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun rejectedKeyReplacementRollsBackProfileMetadataAndOriginalKeys() = runBlocking {
        val dao = database.keyboardProfileDao()
        val originalProfile = keyboardProfile()
        val originalKeys = listOf(
            KeyboardProfileKeyEntity(PROFILE_ID, position = 0, actionCode = "escape"),
            KeyboardProfileKeyEntity(PROFILE_ID, position = 1, actionCode = "control"),
        )
        dao.insertWithKeys(originalProfile, originalKeys)

        expectConstraint {
            dao.updateWithKeys(
                originalProfile.copy(name = "Must roll back", updatedAtEpochMillis = 30),
                listOf(
                    KeyboardProfileKeyEntity(PROFILE_ID, position = 0, actionCode = "tab"),
                    KeyboardProfileKeyEntity(PROFILE_ID, position = 1, actionCode = "tab"),
                ),
            )
        }

        assertEquals(originalProfile, dao.findById(PROFILE_ID))
        assertEquals(originalKeys, dao.findKeys(PROFILE_ID))
    }

    @Test
    fun relationFlowEmitsForKeysOnlyChangesAndMapsRowsByPosition() = runBlocking {
        val dao = database.keyboardProfileDao()
        dao.insertWithKeys(
            keyboardProfile(),
            listOf(
                KeyboardProfileKeyEntity(PROFILE_ID, position = 1, actionCode = "control"),
                KeyboardProfileKeyEntity(PROFILE_ID, position = 0, actionCode = "escape"),
            ),
        )
        val repository = KeyboardProfileRepository(dao)
        assertEquals(
            listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL),
            repository.observeAll().first().single().orderedActions,
        )

        val initialObserved = CompletableDeferred<Unit>()
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) {
                repository.observeAll()
                    .onEach { initialObserved.complete(Unit) }
                    .drop(1)
                    .first()
                    .single()
            }
        }
        initialObserved.await()
        dao.replaceKeys(
            PROFILE_ID,
            listOf(
                KeyboardProfileKeyEntity(PROFILE_ID, position = 1, actionCode = "arrow_right"),
                KeyboardProfileKeyEntity(PROFILE_ID, position = 0, actionCode = "arrow_left"),
            ),
        )

        assertEquals(
            listOf(KeyboardAction.ARROW_LEFT, KeyboardAction.ARROW_RIGHT),
            changed.await().orderedActions,
        )
    }

    private suspend fun expectConstraint(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected the replacement key rows to violate a database constraint.")
        } catch (_: SQLiteConstraintException) {
            // Expected: @Transaction must restore both the profile row and its original keys.
        }
    }

    private fun keyboardProfile() = KeyboardProfileEntity(
        id = PROFILE_ID,
        name = "Original",
        rowCount = 1,
        modifierPolicyCode = "one_shot",
        hapticEnabled = true,
        keyRepeatEnabled = true,
        inputModeCode = "raw",
        tmuxPrefix = "C-b",
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private companion object {
        const val PROFILE_ID = "00000000-0000-4000-8000-000000000001"
    }
}
