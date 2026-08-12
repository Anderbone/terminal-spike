package com.yanjiyu.terminalspike.core.data.db

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migration = MigrationTestHelper(instrumentation, AppDatabase::class.java)

    @After
    fun deleteDatabase() {
        instrumentation.targetContext.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun versionOneToLatestPreservesPreReleaseNoOpRevisionRowsAndAppliesVersionThree() = runBlocking {
        migration.createDatabase(DATABASE_NAME, 1).use { versionOne ->
            assertEquals(
                setOf("mosh_locale", "mosh_fallback_policy_code"),
                versionOne.columnNames("host_profiles")
                    .intersect(setOf("mosh_locale", "mosh_fallback_policy_code")),
            )
            assertTrue(
                versionOne.columnNames("recent_sessions").contains("endpoint_identity_token"),
            )
            assertEquals(
                1,
                versionOne.query(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'index' AND name = ?",
                    arrayOf("index_recent_sessions_endpoint_identity_token"),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                },
            )
            versionOne.insert("host_profiles", 0, versionOneHost())
            versionOne.insert("recent_sessions", 0, versionOneRecent())
        }

        val migrated = Room.databaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).build()
        try {
            assertEquals(3, migrated.openHelper.writableDatabase.version)
            val host = migrated.hostProfileDao().findById(HOST_ID)
            assertNotNull(host)
            assertEquals("Mosh", host?.displayName)
            assertNull(host?.moshLocale)
            assertEquals("never", host?.moshFallbackPolicyCode)
            val recent = migrated.recentSessionDao().findById(SESSION_ID)
            assertNotNull(recent)
            assertEquals("Mosh", recent?.hostDisplayName)
            assertNull(recent?.endpointIdentityToken)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun versionTwoToThreePreservesProfilesAddsDefaultsAndCustomThemes() = runBlocking {
        migration.createDatabase(DATABASE_NAME, 2).use { versionTwo ->
            versionTwo.insert("terminal_profiles", 0, versionTwoTerminalProfile())
        }

        val migrated = Room.databaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).build()
        try {
            val profile = requireNotNull(migrated.terminalProfileDao().findById(PROFILE_ID))
            assertEquals(true, profile.boldRenderingEnabled)
            assertEquals(false, profile.ligaturesEnabled)
            assertEquals(true, profile.pinchZoomEnabled)
            assertEquals(false, profile.copyOnSelection)

            migrated.customTerminalThemeDao().insert(customTheme())
            assertEquals(
                "Ocean",
                migrated.customTerminalThemeDao().findById(THEME_ID)?.name,
            )
        } finally {
            migrated.close()
        }
    }

    private fun versionOneHost() = ContentValues().apply {
        put("id", HOST_ID)
        put("display_name", "Mosh")
        put("hostname", "example.test")
        put("port", 22)
        put("username", "tester")
        put("protocol_code", "mosh")
        putNull("credential_id")
        putNull("terminal_profile_id")
        putNull("keyboard_profile_id")
        put("is_favorite", false)
        putNull("group_name")
        putNull("tag")
        putNull("startup_command")
        putNull("keepalive_interval_seconds")
        putNull("reconnect_policy_code")
        putNull("mosh_port_start")
        putNull("mosh_port_end")
        putNull("mosh_server_command")
        put("created_at_epoch_millis", 1_000L)
        put("updated_at_epoch_millis", 2_000L)
    }

    private fun versionOneRecent() = ContentValues().apply {
        put("id", SESSION_ID)
        put("host_profile_id", HOST_ID)
        put("host_display_name", "Mosh")
        put("protocol_code", "mosh")
        put("state_code", "disconnected")
        put("started_at_epoch_millis", 1_000L)
        put("last_activity_at_epoch_millis", 2_000L)
        put("ended_at_epoch_millis", 3_000L)
        putNull("terminal_title")
    }

    private fun versionTwoTerminalProfile() = ContentValues().apply {
        put("id", PROFILE_ID)
        put("name", "Default")
        put("theme_id", "current")
        put("font_id", "system_monospace")
        put("font_size_sp", 14f)
        put("line_height_multiplier", 1f)
        put("letter_spacing_em", 0f)
        put("cursor_style_code", "block")
        put("cursor_blink", true)
        put("scrollback_lines", 20_000)
        put("visual_bell_enabled", false)
        put("vibration_bell_enabled", false)
        put("audible_bell_enabled", false)
        put("touch_scroll_mode_code", "auto")
        put("two_finger_local_scroll_override", true)
        put("jump_to_bottom_on_keyboard_input", true)
        put("keep_viewport_position_on_output", true)
        put("detect_plain_text_urls", true)
        put("osc8_hyperlinks_enabled", true)
        put("remote_clipboard_mode_code", "ask")
        put("term_type", "xterm-256color")
        put("retain_alternate_screen_history", true)
        put("created_at_epoch_millis", 1_000L)
        put("updated_at_epoch_millis", 2_000L)
    }

    private fun customTheme() = CustomTerminalThemeEntity(
        id = THEME_ID,
        name = "Ocean",
        foregroundArgb = 0xffeeeeee.toInt(),
        backgroundArgb = 0xff101820.toInt(),
        cursorArgb = 0xffffffff.toInt(),
        selectionArgb = 0xff304050.toInt(),
        ansi0Argb = 0xff000000.toInt(),
        ansi1Argb = 0xff000001.toInt(),
        ansi2Argb = 0xff000002.toInt(),
        ansi3Argb = 0xff000003.toInt(),
        ansi4Argb = 0xff000004.toInt(),
        ansi5Argb = 0xff000005.toInt(),
        ansi6Argb = 0xff000006.toInt(),
        ansi7Argb = 0xff000007.toInt(),
        ansi8Argb = 0xff000008.toInt(),
        ansi9Argb = 0xff000009.toInt(),
        ansi10Argb = 0xff00000a.toInt(),
        ansi11Argb = 0xff00000b.toInt(),
        ansi12Argb = 0xff00000c.toInt(),
        ansi13Argb = 0xff00000d.toInt(),
        ansi14Argb = 0xff00000e.toInt(),
        ansi15Argb = 0xff00000f.toInt(),
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
    )

    private fun androidx.sqlite.db.SupportSQLiteDatabase.columnNames(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    private companion object {
        const val DATABASE_NAME = "plan-004-migration-test.db"
        const val HOST_ID = "00000000-0000-4000-8000-000000000001"
        const val SESSION_ID = "00000000-0000-4000-8000-000000000002"
        const val PROFILE_ID = "00000000-0000-4000-8000-000000000003"
        const val THEME_ID = "00000000-0000-4000-8000-000000000004"
    }
}
