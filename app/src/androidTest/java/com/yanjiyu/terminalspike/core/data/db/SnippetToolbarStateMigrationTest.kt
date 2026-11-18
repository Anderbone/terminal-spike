package com.yanjiyu.terminalspike.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.IsolatedPreferencesRule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Real SQLite/Room opening transactions with an isolated legacy preference file. */
class SnippetToolbarStateMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences = IsolatedPreferencesRule("snippet-toolbar")
    private val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preferences).around(helper)
    private val context get() = preferences.wrap(instrumentation.targetContext)
    private val name = "snippet-toolbar-migration-${UUID.randomUUID()}.db"

    @After fun cleanup() { instrumentation.targetContext.deleteDatabase(name) }

    @Test fun migrationImportsLegacyVisibilityOnceAndPreservesSnippetsOrderAndExpansion() = runBlocking {
        preferences.preferences.edit()
            .putBoolean("codex-replaced", true)
            .putStringSet("replaced-defaults", setOf("-2"))
            .putString("order", "12,-2,0")
            .putBoolean("expanded", false).commit()
        helper.createDatabase(name, 5).use {
            it.execSQL("INSERT INTO snippets (id,name,group_name,command,action_code,append_enter," +
                "confirm_multiline,is_favorite,created_at_epoch_millis,updated_at_epoch_millis) " +
                "VALUES ('80000000-0000-4000-8000-000000000001','Preserved',NULL,'pwd'," +
                "'send_immediately',0,1,0,100,100)")
        }
        val initializer = SnippetToolbarStateInitializer.from(context)
        helper.runMigrationsAndValidate(name, 6, true, initializer.migration).use { migrated ->
            migrated.query("SELECT name FROM snippets").use {
                assertTrue(it.moveToFirst()); assertEquals("Preserved", it.getString(0))
            }
            migrated.query("SELECT hidden_default_ids FROM snippet_toolbar_state WHERE singleton_id=0").use {
                assertTrue(it.moveToFirst()); assertEquals(setOf(-2L, 0L), HiddenDefaultSnippetIds.decode(it.getString(0)))
            }
        }
        // An existing row is authoritative even if the retained legacy source later changes.
        preferences.preferences.edit().putStringSet("replaced-defaults", setOf("-1")).commit()
        withDatabase(initializer) { reopened ->
            assertEquals(setOf(-2L, 0L), HiddenDefaultSnippetIds.decode(
                requireNotNull(reopened.snippetToolbarStateDao().findState()).hiddenDefaultIds,
            ))
        }
        assertEquals("12,-2,0", preferences.preferences.getString("order", null))
        assertEquals(false, preferences.preferences.getBoolean("expanded", true))
        assertEquals(true, preferences.preferences.getBoolean("codex-replaced", false))
        assertEquals(setOf("-1"), preferences.preferences.getStringSet("replaced-defaults", emptySet()))
    }

    @Test fun freshEmptyImportIsDurableAndNeverReplaysLaterLegacyFlags() = runBlocking {
        val initializer = SnippetToolbarStateInitializer.from(context)
        withDatabase(initializer) {
            assertEquals("", requireNotNull(it.snippetToolbarStateDao().findState()).hiddenDefaultIds)
        }
        preferences.preferences.edit().putBoolean("codex-replaced", true).commit()
        withDatabase(initializer) {
            assertEquals("", requireNotNull(it.snippetToolbarStateDao().findState()).hiddenDefaultIds)
        }
    }

    @Test fun failedLegacyReadDoesNotCommitTheMigrationOrAnEmptyImportMarker() {
        helper.createDatabase(name, 5).close()
        val failure = runCatching {
            helper.runMigrationsAndValidate(name, 6, true,
                SnippetToolbarStateInitializer { throw IllegalStateException("owned legacy read failure") }.migration,
            ).close()
        }.exceptionOrNull()
        assertTrue(failure != null)
        val initializer = SnippetToolbarStateInitializer { setOf(-1L) }
        helper.runMigrationsAndValidate(name, 6, true, initializer.migration).use {
            it.query("SELECT hidden_default_ids FROM snippet_toolbar_state WHERE singleton_id=0").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("-1", row.getString(0))
            }
        }
    }

    private inline fun <T> withDatabase(
        initializer: SnippetToolbarStateInitializer,
        block: (AppDatabase) -> T,
    ): T {
        val database = open(initializer)
        return try { block(database) } finally { database.close() }
    }

    private fun open(initializer: SnippetToolbarStateInitializer): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(initializer.migration).addCallback(initializer.callback).build()
}
