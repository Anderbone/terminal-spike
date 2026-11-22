package com.yanjiyu.terminalspike

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits until the node matched by [matcher] keeps the same window bounds for [quietMillis].
 *
 * Instrumentation starts outside touch mode, so API 26–27 give a dialog's first text field focus
 * when its window gains focus and open the IME. The resulting insets move the dialog after
 * Compose reports idle; geometry read or clicks resolved before that move can land outside the
 * panel (dismissing it) or compare positions from two different layouts.
 */
internal fun ComposeTestRule.awaitBoundsSettled(
    matcher: SemanticsMatcher,
    quietMillis: Long = 1_000,
    timeoutMillis: Long = 10_000,
) {
    var previous: Rect? = null
    var unchangedSince = 0L
    waitUntil(timeoutMillis) {
        val bounds = onNode(matcher).fetchSemanticsNode().boundsInWindow
        val now = SystemClock.uptimeMillis()
        if (bounds != previous) {
            previous = bounds
            unchangedSince = now
        }
        now - unchangedSince >= quietMillis
    }
}
