package com.yanjiyu.terminalspike.ui.guide

import android.content.Context
import androidx.core.content.edit

/** Device-local discovery state, deliberately excluded from portable settings backups. */
internal class QuickStartState(context: Context) {
    private val preferences = context.getSharedPreferences("quick-start", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    val shouldIntroduce: Boolean = shouldIntroduceQuickStart(
        acknowledged = preferences.getBoolean("acknowledged", false),
        firstInstallTime = packageInfo.firstInstallTime,
        lastUpdateTime = packageInfo.lastUpdateTime,
    )

    fun acknowledge() {
        preferences.edit { putBoolean("acknowledged", true) }
    }
}

internal fun shouldIntroduceQuickStart(
    acknowledged: Boolean,
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = !acknowledged && firstInstallTime == lastUpdateTime
