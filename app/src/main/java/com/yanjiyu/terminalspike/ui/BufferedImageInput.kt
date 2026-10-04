package com.yanjiyu.terminalspike.ui

import android.os.Bundle
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import com.yanjiyu.terminalspike.terminal.view.TerminalImageContentCallback
import com.yanjiyu.terminalspike.terminal.view.receiveTerminalImageContent

/** Adds IME image paste while leaving Compose in charge of draft editing and composition. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BufferedImageInput(
    callback: TerminalImageContentCallback?,
    content: @Composable () -> Unit,
) {
    val currentCallback by rememberUpdatedState(callback)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod { attributes ->
                val connection = request.createInputConnection(attributes)
                if (currentCallback == null) {
                    connection
                } else {
                    attributes.contentMimeTypes = arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")
                    object : InputConnectionWrapper(connection, false) {
                        override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                            val receiver = currentCallback ?: return false
                            return receiveTerminalImageContent(info, flags, receiver)
                        }
                    }
                }
            }
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
