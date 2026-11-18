package com.yanjiyu.terminalspike

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.util.UUID

/** Keep outside the Compose/Activity rule so disposal finishes before private fixture cleanup. */
internal class IsolatedPreferencesRule(private val originalName: String) : ExternalResource() {
    lateinit var preferences: SharedPreferences
        private set
    private lateinit var fixtureName: String

    override fun before() {
        fixtureName = "$originalName-test-${UUID.randomUUID()}"
        preferences = context().getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
        check(preferences.all.isEmpty()) { "Preference fixture must start empty." }
    }

    fun wrap(base: Context): Context = object : ContextWrapper(base) {
        // Components may deliberately retain only applicationContext. Keep its namespace
        // isolated too, with a stable identity for remember(applicationContext) callers.
        private val isolatedApplicationContext: Context by lazy {
            val application = base.applicationContext
            if (application === base) this else wrap(application)
        }

        override fun getApplicationContext(): Context = isolatedApplicationContext

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == originalName) preferences else super.getSharedPreferences(name, mode)
    }

    override fun after() {
        // Drain earlier apply writes before deleting the fixture file and its cached instance.
        check(preferences.edit().clear().commit()) { "Preference fixture could not be flushed." }
        check(context().deleteSharedPreferences(fixtureName)) { "Preference fixture could not be deleted." }
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
