package com.yanjiyu.terminalspike.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTerminalThemeRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CustomTerminalThemeRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repository = CustomTerminalThemeRepository(database.customTerminalThemeDao())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun palettePersistsAndDeleteAtomicallyResetsProfileReferences() = runBlocking {
        repository.insert(theme())
        database.terminalProfileDao().insert(profile().toEntity())

        val stored = repository.observeAll().first().single()
        assertEquals(theme(), stored)
        assertFalse(stored.boldUsesBrightColours)

        assertEquals(
            true,
            repository.deleteAndResetProfiles(THEME_ID, "current", updatedAtEpochMillis = 3),
        )

        assertNull(repository.get(THEME_ID))
        val profile = requireNotNull(database.terminalProfileDao().findById(PROFILE_ID))
        assertEquals("current", profile.themeId)
        assertEquals(3L, profile.updatedAtEpochMillis)
    }

    private fun theme() = CustomTerminalTheme(
        id = THEME_ID,
        name = "Ocean",
        foregroundArgb = 0xffeeeeee.toInt(),
        backgroundArgb = 0xff101820.toInt(),
        cursorArgb = 0xffffffff.toInt(),
        selectionArgb = 0xff304050.toInt(),
        ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
        boldUsesBrightColours = false,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun profile() = TerminalProfile(
        id = PROFILE_ID,
        name = "Default",
        themeId = THEME_ID,
        fontId = "system_monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 20_000,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private companion object {
        const val THEME_ID = "10000000-0000-4000-8000-000000000001"
        const val PROFILE_ID = "20000000-0000-4000-8000-000000000002"
    }
}
