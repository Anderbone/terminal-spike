package com.yanjiyu.terminalspike.terminal.view

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@RunWith(AndroidJUnit4::class)
class TerminalClipboardWriterTest {
    @Test
    fun explicitRemoteClipboardPathWritesVisiblePlainText() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            dismissPendingAutofillSavePrompts()
            val token = scenario.writeAndAwaitClipboardChange { writer ->
                writer.writeRemoteClipboard("allowed remote value")
            }

            scenario.withResumedFocusedActivity { activity ->
                val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
                val writer = TerminalClipboardWriter(activity)
                val clip = clipboard.primaryClip

                assertNotNull(token)
                assertEquals(
                    "allowed remote value",
                    clip?.getItemAt(0)?.text?.toString(),
                )
                assertNotNull(clip?.description?.extras)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertFalse(
                        clip
                            ?.description
                            ?.extras
                            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
                    )
                }
                assertTrue(writer.clearIfCurrent(requireNotNull(token)))
            }
        }
    }

    @Test
    fun writesVisiblePlainTextAndOlderTokenCannotClearNewerIdenticalCopy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            dismissPendingAutofillSavePrompts()
            val request = TerminalClipboardRequest(
                label = "Terminal test",
                text = "same terminal value",
                kind = TerminalClipboardContentKind.SELECTION,
            )
            val older = scenario.writeAndAwaitClipboardChange { writer -> writer.write(request) }
            val newer = scenario.writeAndAwaitClipboardChange { writer -> writer.write(request) }

            scenario.withResumedFocusedActivity { activity ->
                val writer = TerminalClipboardWriter(activity)
                assertNotNull(older)
                assertNotNull(newer)
                assertFalse(writer.clearIfCurrent(requireNotNull(older)))
                val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
                val clip = clipboard.primaryClip
                assertEquals("same terminal value", clip?.getItemAt(0)?.text?.toString())
                assertNotNull(clip?.description?.extras)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    assertFalse(
                        clip
                            ?.description
                            ?.extras
                            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
                    )
                }
                assertTrue(writer.clearIfCurrent(requireNotNull(newer)))
            }
        }
    }

    @Test
    fun delayedClearFailsClosedWhileOwningActivityIsNotResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            dismissPendingAutofillSavePrompts()
            val request = TerminalClipboardRequest(
                label = "Terminal lifecycle test",
                text = "retain while backgrounded",
                kind = TerminalClipboardContentKind.SELECTION,
            )
            lateinit var writer: TerminalClipboardWriter
            val token = requireNotNull(
                scenario.writeAndAwaitClipboardChange { candidate ->
                    writer = candidate
                    candidate.write(request)
                },
            )

            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(writer.clearIfCurrent(token))

            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.withResumedFocusedActivity { activity ->
                val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
                assertEquals(
                    request.text,
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
                )
                assertTrue(writer.clearIfCurrent(token))
            }
        }
    }

    @Test
    fun expiredClearRetriesThroughARecreatedResumedActivity() {
        val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val scheduler = TerminalClipboardClearScheduler(schedulerScope)
        scheduler.updateDelaySeconds(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                dismissPendingAutofillSavePrompts()
                val token = scenario.writeAndAwaitClipboardChange(
                    writerFactory = { activity -> TerminalClipboardWriter(activity, scheduler) },
                ) { writer ->
                    writer.write(
                        TerminalClipboardRequest(
                            label = "Terminal recreation test",
                            text = "clear after recreation",
                            kind = TerminalClipboardContentKind.SELECTION,
                        ),
                    )
                }
                assertNotNull(token)

                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(1_250L)
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)

                val cleared = CountDownLatch(1)
                val clipboard = AtomicReference<ClipboardManager?>(null)
                val listener = ClipboardManager.OnPrimaryClipChangedListener { cleared.countDown() }
                scenario.withResumedFocusedActivity { activity ->
                    val manager = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
                    clipboard.set(manager)
                    manager.addPrimaryClipChangedListener(listener)
                    TerminalClipboardWriter(activity, scheduler).retryExpiredClear()
                }
                try {
                    assertTrue(
                        "The expired app-owned clip was not cleared after recreation.",
                        cleared.await(CLIPBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    )
                } finally {
                    scenario.withResumedFocusedActivity {
                        clipboard.get()?.removePrimaryClipChangedListener(listener)
                    }
                }
                scenario.withResumedFocusedActivity { activity ->
                    val manager = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
                    assertTrue(manager.primaryClip == null || manager.primaryClip!!.itemCount == 0)
                }
            }
        } finally {
            scheduler.close()
            schedulerScope.cancel()
        }
    }

    /**
     * Password-entry tests can leave Android's Autofill save UI above the next Activity even after
     * their ActivityScenario has closed. That system-owned, focusable window legitimately prevents
     * clipboard reads. Dismiss only while that exact pending action owns the active window, waiting
     * for each window transition until the Activity beneath it can receive focus.
     */
    private fun dismissPendingAutofillSavePrompts() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(MAX_PENDING_AUTOFILL_PROMPTS) {
            val decline = automation.findAutofillDeclineAction() ?: return
            val blockedWindowId = decline.windowId
            try {
                automation.executeAndWaitForEvent(
                    {
                        check(automation.dismissValidatedAutofillSavePrompt(decline)) {
                            "Android's pending Autofill save prompt could not be dismissed."
                        }
                    },
                    {
                        automation.findAutofillDeclineAction()?.windowId != blockedWindowId
                    },
                    AUTOFILL_WINDOW_TIMEOUT_MILLIS,
                )
            } catch (timeout: TimeoutException) {
                if (automation.findAutofillDeclineAction()?.windowId == blockedWindowId) {
                    throw AssertionError(
                        "Android's pending Autofill save prompt did not close.",
                        timeout,
                    )
                }
            }
        }
        check(automation.findAutofillDeclineAction() == null) {
            "Too many pending Android Autofill save prompts were stacked above MainActivity."
        }
    }

    private fun UiAutomation.findAutofillDeclineAction(): AccessibilityNodeInfo? =
        rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(AUTOFILL_DECLINE_VIEW_ID)
            ?.asSequence()
            ?.firstOrNull { node ->
                node.packageName?.toString() == ANDROID_PACKAGE &&
                    node.viewIdResourceName == AUTOFILL_DECLINE_VIEW_ID &&
                    node.isVisibleToUser &&
                    node.isEnabled &&
                    node.isClickable
            }

    private fun UiAutomation.dismissValidatedAutofillSavePrompt(
        node: AccessibilityNodeInfo,
    ): Boolean {
        check(node.packageName?.toString() == ANDROID_PACKAGE)
        check(node.viewIdResourceName == AUTOFILL_DECLINE_VIEW_ID)
        check(node.isVisibleToUser && node.isEnabled && node.isClickable)
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Android only permits clipboard reads while this app owns the focused window. ActivityScenario
     * reaches RESUMED before the launch transition necessarily grants window focus, so running the
     * assertion directly from its first onActivity callback races that privacy boundary on Android
     * 16. Execute the operation from the focus callback itself and surface failures back to the test
     * thread instead of polling or sleeping.
     */
    private fun <T> ActivityScenario<MainActivity>.withResumedFocusedActivity(
        block: (MainActivity) -> T,
    ): T {
        val completed = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        val outcome = AtomicReference<Result<T>?>(null)
        val observer = AtomicReference<ViewTreeObserver?>(null)
        val listener = AtomicReference<ViewTreeObserver.OnWindowFocusChangeListener?>(null)

        onActivity { activity ->
            fun executeOnce() {
                if (!claimed.compareAndSet(false, true)) return
                listener.get()?.let { focusListener ->
                    observer.get()?.takeIf { it.isAlive }
                        ?.removeOnWindowFocusChangeListener(focusListener)
                }
                outcome.set(
                    runCatching {
                        check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            "Clipboard access requires a resumed Activity."
                        }
                        check(activity.hasWindowFocus()) {
                            "Clipboard access requires the Activity's window to be focused."
                        }
                        block(activity)
                    },
                )
                completed.countDown()
            }

            val focusObserver = activity.window.decorView.viewTreeObserver
            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) executeOnce()
            }
            observer.set(focusObserver)
            listener.set(focusListener)
            focusObserver.addOnWindowFocusChangeListener(focusListener)
            if (activity.hasWindowFocus()) executeOnce()
        }

        if (!completed.await(FOCUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            claimed.set(true)
            onActivity {
                listener.get()?.let { focusListener ->
                    observer.get()?.takeIf { it.isAlive }
                        ?.removeOnWindowFocusChangeListener(focusListener)
                }
            }
            throw AssertionError("MainActivity did not obtain window focus for clipboard access.")
        }
        return requireNotNull(outcome.get()).getOrThrow()
    }

    private fun ActivityScenario<MainActivity>.writeAndAwaitClipboardChange(
        writerFactory: (MainActivity) -> TerminalClipboardWriter = { activity ->
            TerminalClipboardWriter(activity)
        },
        write: (TerminalClipboardWriter) -> TerminalClipboardToken?,
    ): TerminalClipboardToken? {
        val changed = CountDownLatch(1)
        val clipboard = AtomicReference<ClipboardManager?>(null)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { changed.countDown() }
        val token = withResumedFocusedActivity { activity ->
            val manager = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
            clipboard.set(manager)
            manager.addPrimaryClipChangedListener(listener)
            write(writerFactory(activity))
        }
        try {
            assertTrue(
                "The system did not publish the clipboard change.",
                changed.await(CLIPBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        } finally {
            withResumedFocusedActivity {
                clipboard.get()?.removePrimaryClipChangedListener(listener)
            }
        }
        return token
    }

    private companion object {
        const val ANDROID_PACKAGE = "android"
        const val AUTOFILL_DECLINE_VIEW_ID = "android:id/autofill_save_no"
        const val AUTOFILL_WINDOW_TIMEOUT_MILLIS = 5_000L
        const val MAX_PENDING_AUTOFILL_PROMPTS = 16
        const val FOCUS_TIMEOUT_SECONDS = 10L
        const val CLIPBOARD_TIMEOUT_SECONDS = 10L
    }
}
