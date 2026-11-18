package com.yanjiyu.terminalspike

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Fault probes use only unique, test-owned preference names, including the original sentinel. */
@RunWith(AndroidJUnit4::class)
class IsolatedPreferencesRuleTest {
    @Test
    fun wrappedWritesKeepTheOriginalAndRemoveTheFixtureAfterSuccess() {
        exercise(failBody = false)
    }

    @Test
    fun innerDisposalPrecedesFixtureDeletionAndPreservesTheBodyFailure() {
        exercise(failBody = true)
    }

    @Test
    fun applicationContextKeepsTheSamePreferenceIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalName = "owned-pref-isolation-${UUID.randomUUID()}"
        val original = context.getSharedPreferences(originalName, Context.MODE_PRIVATE)
        try {
            assertTrue(original.edit().putString("sentinel", "keep").commit())
            val rule = IsolatedPreferencesRule(originalName)
            rule.apply(object : Statement() {
                override fun evaluate() {
                    val wrapped = rule.wrap(context)
                    val application = wrapped.applicationContext
                    assertSame(application, wrapped.applicationContext)
                    assertSame(application, application.applicationContext)
                    val preferences = application.getSharedPreferences(originalName, Context.MODE_PRIVATE)
                    assertSame(rule.preferences, preferences)
                    assertFalse(preferences.contains("sentinel"))
                    assertTrue(preferences.edit().putString("fixture", "owned").commit())
                    assertEquals(mapOf("sentinel" to "keep"), original.all)
                }
            }, description()).evaluate()
            assertEquals(mapOf("sentinel" to "keep"), original.all)
            assertNoFixtureFile(context, originalName)
        } finally {
            assertTrue(context.deleteSharedPreferences(originalName))
        }
    }

    @Test
    fun unrelatedPreferenceNamesStillDelegateToTheBaseContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalName = "owned-pref-isolation-${UUID.randomUUID()}"
        val otherName = "$originalName-other"
        val other = context.getSharedPreferences(otherName, Context.MODE_PRIVATE)
        try {
            assertTrue(other.edit().putString("sentinel", "keep").commit())
            val rule = IsolatedPreferencesRule(originalName)
            rule.apply(object : Statement() {
                override fun evaluate() {
                    assertSame(other, rule.wrap(context).getSharedPreferences(otherName, Context.MODE_PRIVATE))
                    assertSame(other, rule.wrap(context).applicationContext.getSharedPreferences(otherName, Context.MODE_PRIVATE))
                    assertEquals("keep", other.getString("sentinel", null))
                    assertTrue(rule.preferences.all.isEmpty())
                }
            }, description()).evaluate()
            assertEquals("keep", other.getString("sentinel", null))
            assertNoFixtureFile(context, originalName)
        } finally {
            assertTrue(context.deleteSharedPreferences(otherName))
        }
    }

    private fun exercise(failBody: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalName = "owned-pref-isolation-${UUID.randomUUID()}"
        val original = context.getSharedPreferences(originalName, Context.MODE_PRIVATE)
        var fixture: SharedPreferences? = null
        val events = mutableListOf<String>()
        val failure = AssertionError("owned fixture failure")
        try {
            assertTrue(original.edit().putString("sentinel", "keep").commit())
            val rule = IsolatedPreferencesRule(originalName)
            val disposable = TestRule { statement, _ ->
                object : Statement() {
                    override fun evaluate() {
                        try {
                            statement.evaluate()
                        } finally {
                            // This stands for the inner Compose/Activity rule's disposal phase.
                            assertTrue(requireNotNull(fixture).getBoolean("expanded", false))
                            events += "disposed"
                        }
                    }
                }
            }
            val body = object : Statement() {
                override fun evaluate() {
                    val isolated = rule.wrap(context).getSharedPreferences(originalName, Context.MODE_PRIVATE)
                    fixture = isolated
                    assertFalse(isolated.contains("sentinel"))
                    assertTrue(isolated.edit().putBoolean("expanded", true).commit())
                    assertEquals(mapOf("sentinel" to "keep"), original.all)
                    events += "body"
                    if (failBody) throw failure
                }
            }
            val evaluation = RuleChain.outerRule(rule).around(disposable).apply(body, description())
            if (failBody) {
                assertSame(failure, assertThrows(AssertionError::class.java) { evaluation.evaluate() })
            } else {
                evaluation.evaluate()
            }
            assertEquals(listOf("body", "disposed"), events)
            assertEquals(mapOf("sentinel" to "keep"), original.all)
            assertTrue(requireNotNull(fixture).all.isEmpty())
            assertNoFixtureFile(context, originalName)
        } finally {
            assertTrue(context.deleteSharedPreferences(originalName))
        }
    }

    private fun assertNoFixtureFile(context: Context, originalName: String) {
        val remaining = context.filesDir.parentFile?.resolve("shared_prefs")?.listFiles()
            .orEmpty().filter { it.name.startsWith("$originalName-test-") }
        assertTrue("Only the owned fixture's XML/cache files must be removed", remaining.isEmpty())
    }

    private fun description(): Description = Description.createTestDescription(javaClass, "ownedPreferences")
}
