package com.yanjiyu.terminalspike

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.os.SystemClock
import com.yanjiyu.terminalspike.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    @Test
    fun languageSwitchSurvivesRecreationAndRestoresSystemDefault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val original = AppLanguage.selected(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun selectLanguage(tag: String) {
                var previous: MainActivity? = null
                var changed = false
                scenario.onActivity {
                    previous = it
                    changed = AppLanguage.selected(it) != tag
                    AppLanguage.select(it, tag)
                }
                if (!changed) return
                // Both our legacy path and LocaleManager recreate asynchronously.
                // Wait for the replacement Activity before asking Scenario to recreate again.
                val deadline = SystemClock.uptimeMillis() + 5_000
                var replaced = false
                while (!replaced && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        replaced = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>()
                            .any { it !== previous && AppLanguage.selected(it) == tag }
                    }
                    if (!replaced) SystemClock.sleep(20)
                }
                assertTrue("Language change must resume a replacement Activity", replaced)
            }
            try {
                for ((tag, title) in listOf("zh" to "设置", "en" to "Settings")) {
                    selectLanguage(tag)
                    scenario.recreate()
                    scenario.onActivity {
                        assertEquals(tag, AppLanguage.selected(it))
                        assertEquals(title, it.getString(R.string.settings_title))
                    }
                    assertEquals(title, context.getString(R.string.settings_title))
                }
                selectLanguage("")
                scenario.recreate()
                scenario.onActivity { assertEquals("", AppLanguage.selected(it)) }
            } finally {
                selectLanguage(original)
            }
        }
    }
}
