package com.yanjiyu.terminalspike.terminal.view

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.terminal.TerminalBellEvent
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalFindResult
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkAction
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionCallback
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkActionRequest
import com.yanjiyu.terminalspike.terminal.selection.TerminalLinkPolicy

@Stable
class TerminalInputFocusRequester internal constructor() {
    private var terminalView: FastTerminalView? = null
    private var directInputEnabled = true

    internal fun attach(view: FastTerminalView) {
        terminalView = view
        view.setDirectInputEnabled(directInputEnabled)
    }

    internal fun detach(view: FastTerminalView) {
        if (terminalView === view) terminalView = null
    }

    fun requestFocus() {
        setDirectInputEnabled(true)
        terminalView?.requestTerminalInputFocus()
    }

    fun toggleSoftwareKeyboard() {
        terminalView?.toggleSoftwareKeyboard()
    }

    fun setDirectInputEnabled(enabled: Boolean) {
        directInputEnabled = enabled
        terminalView?.setDirectInputEnabled(enabled)
    }

    fun showFindResult(result: TerminalFindResult, activeMatchIndex: Int): Boolean =
        terminalView?.showFindResult(result, activeMatchIndex) ?: false

    fun clearFindResults(): Boolean = terminalView?.clearFindResults() ?: false

    fun resetComposingInput() {
        terminalView?.resetComposingInput()
    }

    /** Event forwarding only: nothing is retained for replay when no native view is attached. */
    fun presentVisualBell(event: TerminalBellEvent): Boolean =
        terminalView?.presentVisualBell(event) ?: false
}

@Composable
fun rememberTerminalInputFocusRequester(): TerminalInputFocusRequester =
    remember { TerminalInputFocusRequester() }

@Composable
fun TerminalViewBridge(
    controller: TerminalController,
    inputFocusRequester: TerminalInputFocusRequester,
    onPreImeBack: () -> Unit,
    modifier: Modifier = Modifier,
    linkActionCallback: TerminalLinkActionCallback? = null,
    clipboardActionCallback: TerminalClipboardActionCallback? = null,
    imageContentCallback: TerminalImageContentCallback? = null,
) {
    val rendererDescription = stringResource(R.string.terminal_renderer_description)
    val context = LocalContext.current
    val currentLinkActionCallback = rememberUpdatedState(linkActionCallback)
    val currentClipboardActionCallback = rememberUpdatedState(clipboardActionCallback)
    val currentImageContentCallback = rememberUpdatedState(imageContentCallback)
    val dispatchLinkAction = remember(context) {
        TerminalLinkActionCallback { request ->
            currentLinkActionCallback.value?.onLinkAction(request)
                ?: performDefaultLinkAction(context, request)
        }
    }
    val defaultClipboardWriter = remember(context) {
        TerminalClipboardWriter(
            context,
            (context.applicationContext as? TerminalSpikeApplication)
                ?.container
                ?.terminalClipboardClearScheduler,
        )
    }
    val dispatchClipboardAction = remember(context) {
        TerminalClipboardActionCallback { request ->
            currentClipboardActionCallback.value?.onCopyRequested(request)
                ?: (defaultClipboardWriter.write(request) != null)
        }
    }
    val dispatchImageContent = remember(context) {
        TerminalImageContentCallback { request ->
            currentImageContentCallback.value?.onImageContent(request) ?: false
        }
    }
    val attachedView = remember { arrayOfNulls<FastTerminalView>(1) }
    DisposableEffect(inputFocusRequester) {
        onDispose {
            attachedView[0]?.let(inputFocusRequester::detach)
            attachedView[0] = null
        }
    }
    AndroidView(
        factory = { context ->
            FastTerminalView(context).apply {
                attachController(controller)
                setLinkActionCallback(dispatchLinkAction)
                setClipboardActionCallback(dispatchClipboardAction)
                setImageContentCallback(dispatchImageContent)
                setPreImeBackCallback(onPreImeBack)
                attachedView[0] = this
                inputFocusRequester.attach(this)
            }
        },
        update = { view ->
            view.attachController(controller)
            view.setLinkActionCallback(dispatchLinkAction)
            view.setClipboardActionCallback(dispatchClipboardAction)
            view.setImageContentCallback(dispatchImageContent)
            view.setPreImeBackCallback(onPreImeBack)
            attachedView[0] = view
            inputFocusRequester.attach(view)
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("terminal_container")
            .semantics { contentDescription = rendererDescription },
    )
}

private fun performDefaultLinkAction(context: Context, request: TerminalLinkActionRequest) {
    when (request.action) {
        TerminalLinkAction.OPEN -> {
            if (!TerminalLinkPolicy.canOpen(request.target.uri)) return
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.target.uri)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            runCatching { context.startActivity(intent) }
        }
        TerminalLinkAction.COPY -> Unit
    }
}
