package com.yanjiyu.terminalspike.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.yanjiyu.terminalspike"

internal fun UiDevice.openSettings() {
    clickRequiredDescription("Open settings")
    waitRequiredText("Settings")
}

internal fun UiDevice.openWorkspace() {
    clickRequiredDescription("Open local workspace")
    waitRequiredDescription("Open settings")
}

private fun UiDevice.clickRequiredDescription(description: String) {
    val target = wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MILLIS)
    checkNotNull(target) { "Could not find '$description' in $TARGET_PACKAGE" }
    target.click()
}

private fun UiDevice.waitRequiredDescription(description: String) {
    check(wait(Until.hasObject(By.desc(description)), UI_TIMEOUT_MILLIS)) {
        "Timed out waiting for '$description' in $TARGET_PACKAGE"
    }
}

private fun UiDevice.waitRequiredText(text: String) {
    check(wait(Until.hasObject(By.text(text)), UI_TIMEOUT_MILLIS)) {
        "Timed out waiting for '$text' in $TARGET_PACKAGE"
    }
}

private const val UI_TIMEOUT_MILLIS = 10_000L
