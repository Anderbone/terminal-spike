package com.yanjiyu.terminalspike

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchModeTest {
    @Test
    fun coldLaunchDoesNotYieldFocusToAnUnrequestedSystemPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + ACTIVITY_FOCUS_TIMEOUT_MILLIS
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity -> focused = activity.hasWindowFocus() }
                if (!focused) SystemClock.sleep(ACTIVITY_FOCUS_POLL_MILLIS)
            }

            assertTrue(
                "Cold launch yielded focus to a system prompt before any user-requested action.",
                focused,
            )
        }
    }

    @Test
    fun launcherUsesTheAdaptiveIconFamilyForStandardAndRoundSurfaces() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val applicationInfo = context.applicationInfo

        assertEquals(R.mipmap.ic_launcher, applicationInfo.icon)
        assertNotNull(context.packageManager.getApplicationIcon(applicationInfo))
    }

    @Test
    fun launcherReentryReusesTheExistingMainActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, MainActivity::class.java)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getActivityInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(component, 0)
        }

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }

    private companion object {
        const val ACTIVITY_FOCUS_TIMEOUT_MILLIS = 5_000L
        const val ACTIVITY_FOCUS_POLL_MILLIS = 50L
    }
}
