package com.yanjiyu.terminalspike

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import com.yanjiyu.terminalspike.ui.ExtraKeysBar
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.terminal.TerminalCursor
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.TerminalAccessoryBar
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import org.junit.Test

class BufferedInputPagerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun typingModeImagePastePreservesDraftAndUsesLatestReceiver() {
        val draft = BufferedInputDraftState()
        draft.update(androidx.compose.ui.text.input.TextFieldValue("keep this draft"), 1L)
        val received = mutableListOf<Long>()
        val target = androidx.compose.runtime.mutableStateOf(1L)
        var connection: android.view.inputmethod.InputConnection? = null
        val attributes = EditorInfo()
        composeRule.setContent {
            val sessionId = target.value
            androidx.compose.ui.platform.InterceptPlatformTextInput(
                interceptor = { request, _ ->
                    connection = request.createInputConnection(attributes)
                    kotlinx.coroutines.awaitCancellation()
                },
            ) {
                com.yanjiyu.terminalspike.ui.BufferedImageInput(
                    com.yanjiyu.terminalspike.terminal.view.TerminalImageContentCallback {
                        received += sessionId
                        it.releasePermission()
                        true
                    },
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft.value,
                        onValueChange = { draft.update(it, sessionId) },
                        modifier = Modifier.testTag("image_paste_draft"),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("image_paste_draft").performClick()
        composeRule.waitUntil { connection != null }
        composeRule.runOnIdle { target.value = 2L }
        composeRule.runOnIdle {
            assertTrue(attributes.contentMimeTypes?.contains("image/png") == true)
            assertTrue(connection!!.commitContent(
                android.view.inputmethod.InputContentInfo(
                    android.net.Uri.parse("content://terminal-spike-test/image.png"),
                    android.content.ClipDescription("image", arrayOf("image/png")),
                    null,
                ), 0, null,
            ))
            assertEquals(listOf(2L), received)
            assertEquals("keep this draft", draft.value.text)
            org.junit.Assert.assertFalse(connection!!.commitContent(
                android.view.inputmethod.InputContentInfo(
                    android.net.Uri.parse("content://terminal-spike-test/file.pdf"),
                    android.content.ClipDescription("document", arrayOf("application/pdf")),
                    null,
                ), 0, null,
            ))
            assertEquals(listOf(2L), received)
        }
    }

    private fun withSoftwareKeyboard(block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val setting = "show_ime_with_hard_keyboard"
        val previous = shell("settings get secure $setting")
        check(previous in listOf("null", "0", "1"))
        try {
            // The emulator runner disables the IME for ordinary semantics-only tests.
            // These two regressions specifically exercise visible-keyboard ownership.
            shell("settings put secure $setting 1")
            composeRule.runOnUiThread {
                composeRule.activity.enableEdgeToEdge()
                composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            block()
        } finally {
            shell(if (previous == "null") "settings delete secure $setting"
                else "settings put secure $setting $previous")
        }
    }

    @Test
    fun bufferedDraftKeepsFocusWhileHerdrTabsAndSpacesReceiveFirstTap() = withSoftwareKeyboard {
        val sent = mutableListOf<String>()
        val draft = BufferedInputDraftState()
        val controller = TerminalController()
        lateinit var terminal: FastTerminalView
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().imePadding()) {
                    AndroidView(
                        factory = { context ->
                            FastTerminalView(context).also { view ->
                                terminal = view
                                controller.setInputSink(
                                    sink = object : TerminalInputSink {
                                        override fun send(bytes: ByteArray) { sent += bytes.decodeToString() }
                                    },
                                    onResize = { _, _ -> },
                                )
                                view.attachController(controller)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("mouse_terminal"),
                    )
                    ExtraKeysBar(
                        keys = TerminalExtraKey.DEFAULT_ORDER,
                        ctrlArmed = false,
                        altArmed = false,
                        customizationEnabled = true,
                        inputTargetId = 11L,
                        bufferedInputSendEnabled = true,
                        bufferedInputDraftState = draft,
                        inputContext = TerminalInputContext("herdr/agent", agent = true),
                        onKey = {},
                        onCustomize = {},
                        onSendBufferedInput = { _, text -> sent += text; true },
                        onBufferedInputModeChanged = { terminal.setDirectInputEnabled(!it) },
                        onDirectInputMode = { terminal.requestTerminalInputFocus(showKeyboard = false) },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        composeRule.onNodeWithTag("buffered_terminal_input").performClick().performTextInput("keep my draft")
        composeRule.waitUntil(5_000) { !terminal.onCheckIsTextEditor() }
        composeRule.runOnIdle {
            controller.updateTerminalFrame(
                TerminalFrameUpdate(
                    completedScrollback = emptyList(),
                    screen = emptyList(),
                    cursor = TerminalCursor(),
                    alternateScreen = false,
                    modes = TerminalModes(
                        mouseTracking = true, sgrMouseEncoding = true,
                    ),
                ),
            )
        }
        composeRule.waitUntil(5_000) { controller.isMouseTrackingEnabled() }
        for ((column, row) in listOf(6 to 0, 1 to 3)) {
            composeRule.onNodeWithTag("mouse_terminal").performTouchInput {
                val density = terminal.resources.displayMetrics.density
                click(
                    Offset(
                        8f * density + (column + 0.5f) * terminal.terminalCellWidthPx,
                        5f * density + (row + 0.5f) * controller.viewport.lineHeightPx,
                    ),
                )
            }
            composeRule.onNodeWithTag("buffered_terminal_input").assertIsFocused()
            composeRule.runOnIdle {
                assertEquals("keep my draft", draft.value.text)
                assertEquals(false, terminal.onCheckIsTextEditor())
                assertEquals(null, terminal.onCreateInputConnection(EditorInfo()))
                assertEquals(
                    "\u001B[<0;${column + 1};${row + 1}M\u001B[<0;${column + 1};${row + 1}m",
                    sent.lastOrNull(),
                )
            }
        }
        composeRule.runOnIdle { assertEquals(2, sent.size) }
    }

    @Test
    fun snippetsPageReturnsImeEnterToTerminalAndPreservesBufferedDraft() = withSoftwareKeyboard {
        val sent = mutableListOf<String>()
        val draft = BufferedInputDraftState()
        lateinit var terminal: FastTerminalView
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().imePadding()) {
                    AndroidView(
                        factory = { context ->
                            FastTerminalView(context).also { view ->
                                terminal = view
                                view.attachController(TerminalController().apply {
                                    setInputSink(
                                        sink = object : TerminalInputSink {
                                            override fun send(bytes: ByteArray) {
                                                sent += bytes.decodeToString()
                                            }
                                        },
                                        onResize = { _, _ -> },
                                    )
                                })
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TerminalAccessoryBar(
                        actions = TerminalExtraKey.DEFAULT_ORDER.map { it.toAccessoryAction() },
                        modifiers = AccessoryModifierSnapshot(),
                        customizationEnabled = true,
                        inputTargetId = 11L,
                        bufferedInputSendEnabled = true,
                        bufferedInputDraftState = draft,
                        inputContext = TerminalInputContext("test-agent", agent = true),
                        onAction = {},
                        onCustomize = {},
                        onSendBufferedInput = { _, text -> sent += text; true },
                        onBufferedInputModeChanged = { terminal.setDirectInputEnabled(!it) },
                        onDirectInputMode = { terminal.requestTerminalInputFocus(showKeyboard = false) },
                        snippetsContent = {
                            TerminalSnippetsPage(
                                snippets = listOf(CommandSnippet(1L, "Insert prompt", "hello", false)),
                                canSave = true,
                                canSend = true,
                                onSend = { sent += "hello" },
                                onNewCodex = {},
                                onSave = { Result.success(Unit) },
                                onEditorClosed = {},
                            )
                        },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        composeRule.onNodeWithTag("buffered_terminal_input").performClick().performTextInput("keep my draft")
        composeRule.waitUntil(5_000) { !terminal.onCheckIsTextEditor() }
        composeRule.onNodeWithTag("terminal_input_pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Insert prompt").performClick()
        composeRule.runOnIdle {
            assertTrue("Snippets must return keyboard focus to the terminal", terminal.hasFocus())
            val connection = checkNotNull(terminal.onCreateInputConnection(EditorInfo()))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals(listOf("hello", "\r"), sent)
            assertEquals("keep my draft", draft.value.text)
        }
        composeRule.onNodeWithTag("terminal_input_pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("buffered_terminal_input").assertIsFocused()
        composeRule.onNodeWithText("keep my draft").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(false, terminal.onCheckIsTextEditor())
            assertEquals(listOf("hello", "\r"), sent)
        }
    }

    @Test
    fun foregroundContextSelectsInputWithoutLosingDraftOrManualChoice() {
        val context = mutableStateOf(TerminalInputContext())
        val draft = BufferedInputDraftState()
        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = 11L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draft,
                    inputContext = context.value,
                    onKey = {},
                    onCustomize = {},
                    onSendBufferedInput = { _, _ -> true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
        composeRule.onNodeWithTag("buffered_terminal_input").assertDoesNotExist()
        composeRule.runOnIdle { context.value = TerminalInputContext("tmux/1", true) }
        composeRule.onNodeWithTag("buffered_terminal_input").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.runOnIdle { context.value = TerminalInputContext("tmux/1", true) }
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
        composeRule.runOnIdle { context.value = TerminalInputContext("herdr/2", true) }
        composeRule.onNodeWithTag("buffered_terminal_input").performTextInput("keep this draft")
        composeRule.runOnIdle { context.value = TerminalInputContext("herdr/2", false) }
        composeRule.onNodeWithTag("buffered_terminal_input").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("keep this draft", draft.value.text) }
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("keep this draft").assertIsDisplayed()
        composeRule.runOnIdle {
            draft.update(TextFieldValue(), 11L)
            context.value = TerminalInputContext("herdr/3", true)
        }
        composeRule.onNodeWithTag("buffered_terminal_input").assertIsDisplayed()
        composeRule.runOnIdle { context.value = TerminalInputContext("herdr/3", false) }
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
    }

    @Test
    fun keyboardSendStagesTextAndExplicitEnterSubmitsEvenWithAnEmptyDraft() {
        val staged = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        var enterCount = 0
        val draftState = BufferedInputDraftState()
        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = 11L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    inputContext = TerminalInputContext("test-agent", agent = true),
                    onKey = { if (it == TerminalExtraKey.ENTER) enterCount++ },
                    onCustomize = {},
                    onSendBufferedInput = { _, text -> staged.add(text); true },
                    onSubmitBufferedInput = { _, text -> submitted.add(text); true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }
        val input = composeRule.onNodeWithTag("buffered_terminal_input")
        input.performTextInput("first part")
        input.performImeAction()
        input.performTextInput("second part")
        input.performImeAction()
        composeRule.runOnIdle {
            assertEquals(listOf("first part", "second part"), staged)
            assertEquals(emptyList<String>(), submitted)
            assertEquals(0, enterCount)
            assertEquals("", draftState.value.text)
        }
        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.runOnIdle { assertEquals(1, enterCount) }
        input.performTextInput("submit this draft")
        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("submit this draft"), submitted)
            assertEquals(1, enterCount)
            assertEquals("", draftState.value.text)
        }
    }

    @Test
    fun stagedTextFollowsTheActiveSessionWhenSent() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val inputTargetId = mutableLongStateOf(11L)
        var sendCount = 0
        var sentTargetId: Long? = null
        var sentText: String? = null
        val draftState = BufferedInputDraftState()

        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = inputTargetId.longValue,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    inputContext = TerminalInputContext("test-agent", agent = true),
                    onKey = {},
                    onCustomize = {},
                    onSendBufferedInput = { targetId, text ->
                        sendCount += 1
                        sentTargetId = targetId
                        sentText = text
                        targetId == inputTargetId.longValue
                    },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(resources.getString(R.string.terminal_buffered_input_description))
            .performTextInput("printf 'exact ✓'")
        val typingSize = composeRule.onNodeWithTag("terminal_input_pager").fetchSemanticsNode().boundsInRoot.size

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("ESC").assertIsDisplayed()
        composeRule.onNodeWithTag("buffered_terminal_input").assertDoesNotExist()
        assertEquals(typingSize, composeRule.onNodeWithTag("terminal_input_pager").fetchSemanticsNode().boundsInRoot.size)
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        assertEquals(typingSize, composeRule.onNodeWithTag("terminal_input_pager").fetchSemanticsNode().boundsInRoot.size)
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.runOnIdle { inputTargetId.longValue = 22L }
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()

        composeRule.onNodeWithTag("buffered_input_enter").performClick()

        composeRule.runOnIdle {
            assertEquals(1, sendCount)
            assertEquals(22L, sentTargetId)
            assertEquals("printf 'exact ✓'", sentText)
        }
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()
        val voiceButton = composeRule.onNodeWithContentDescription(
            resources.getString(R.string.terminal_voice_input),
        )
        val restoreButton = composeRule.onNodeWithContentDescription(
            resources.getString(R.string.terminal_restore_last_sent_input),
        )
        voiceButton.assertIsDisplayed()
        restoreButton.assertIsDisplayed()
        val inputBounds = composeRule.onNodeWithTag("buffered_terminal_input").fetchSemanticsNode().boundsInRoot
        val voiceBounds = voiceButton.fetchSemanticsNode().boundsInRoot
        val restoreBounds = restoreButton.fetchSemanticsNode().boundsInRoot
        assertTrue("input=$inputBounds voice=$voiceBounds restore=$restoreBounds", voiceBounds.top >= inputBounds.center.y)
        assertTrue(voiceBounds.bottom <= inputBounds.bottom)
        assertTrue(voiceBounds.left >= inputBounds.left)
        assertTrue(restoreBounds.top >= inputBounds.center.y)
        assertTrue(restoreBounds.bottom <= inputBounds.bottom)
        assertTrue(restoreBounds.right <= inputBounds.right)
        restoreButton.performClick()
        voiceButton.assertIsDisplayed()
        assertEquals(voiceBounds, voiceButton.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()
    }

    @Test
    fun multilineSendRequiresConfirmationAndCancelKeepsTheDraft() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var sendCount = 0
        var sentText: String? = null
        val draftState = BufferedInputDraftState()

        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = 11L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    inputContext = TerminalInputContext("test-agent", agent = true),
                    onKey = {},
                    onCustomize = {},
                    onSendBufferedInput = { _, text ->
                        sendCount += 1
                        sentText = text
                        true
                    },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        val exactDraft = "printf one\nprintf two"
        composeRule.runOnIdle { draftState.update(TextFieldValue(exactDraft), 11L) }
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()
        composeRule.onNodeWithTag("buffered_input_enter").performClick()

        composeRule.onNodeWithText(resources.getString(R.string.terminal_multiline_paste_title)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.onNodeWithText(resources.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()

        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.onNodeWithText(resources.getString(R.string.terminal_paste)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, sendCount)
            assertEquals(exactDraft, sentText)
        }
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            resources.getString(R.string.terminal_restore_last_sent_input),
        ).performClick()
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()
    }
}
