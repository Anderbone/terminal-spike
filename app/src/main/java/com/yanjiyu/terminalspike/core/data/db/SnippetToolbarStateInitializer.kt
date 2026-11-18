package com.yanjiyu.terminalspike.core.data.db

import android.content.Context
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Imports the old UI preferences before Room exposes a newly created/migrated database.
 * The singleton's presence, including an empty set, is the durable import-completion marker.
 */
internal class SnippetToolbarStateInitializer(private val readLegacy: () -> Set<Long>) {
    val migration: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `snippet_toolbar_state` (" +
                "`singleton_id` INTEGER NOT NULL, `hidden_default_ids` TEXT NOT NULL, " +
                "PRIMARY KEY(`singleton_id`))")
            seedOnce(db)
        }
    }

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { seedOnce(db) }
    }

    private fun seedOnce(db: SupportSQLiteDatabase) {
        db.query("SELECT singleton_id FROM snippet_toolbar_state WHERE singleton_id = 0").use {
            if (it.moveToFirst()) return
        }
        db.execSQL(
            "INSERT OR IGNORE INTO snippet_toolbar_state (singleton_id, hidden_default_ids) VALUES (0, ?)",
            arrayOf(HiddenDefaultSnippetIds.encode(readLegacy())),
        )
    }

    companion object {
        fun from(context: Context): SnippetToolbarStateInitializer = SnippetToolbarStateInitializer {
            val preferences = context.getSharedPreferences("snippet-toolbar", Context.MODE_PRIVATE)
            buildSet {
                if (preferences.getBoolean("codex-replaced", false)) add(0L)
                preferences.getStringSet("replaced-defaults", emptySet()).orEmpty().forEach { raw ->
                    raw.toLongOrNull()?.takeIf { it <= 0 }?.let(::add)
                }
            }
        }
    }
}
