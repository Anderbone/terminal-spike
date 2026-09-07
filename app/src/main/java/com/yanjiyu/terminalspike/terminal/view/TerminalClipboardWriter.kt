package com.yanjiyu.terminalspike.terminal.view

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.yanjiyu.terminalspike.R
import java.lang.ref.WeakReference
import java.util.UUID

enum class TerminalClipboardContentKind {
    SELECTION,
    LINK,
    REMOTE_SESSION,
}

data class TerminalClipboardRequest(
    val label: String,
    val text: String,
    val kind: TerminalClipboardContentKind,
)

fun interface TerminalClipboardActionCallback {
    /** Returns true only when the copy request was accepted. */
    fun onCopyRequested(request: TerminalClipboardRequest): Boolean
}

/** App-assigned ownership proof used to guard an optional delayed clear. */
class TerminalClipboardToken internal constructor(internal val value: String)

/**
 * The only default Android clipboard write seam for terminal output.
 *
 * Every clip is ordinary visible plain text. An app-private ownership token is deliberately kept
 * separate from Android's sensitive-content flag, so clipboard previews remain visible while
 * [clearIfCurrent] can distinguish repeated identical copies. The write path does not read the
 * clipboard back: newer Android versions may restrict that read while the floating selection
 * toolbar temporarily owns window focus.
 */
internal class TerminalClipboardWriter(
    context: Context,
    private val clearScheduler: TerminalClipboardClearScheduler? = null,
) : TerminalClipboardClearTarget {
    private val activity = WeakReference(context.findActivity())
    private val clipboard = context.applicationContext
        .getSystemService(ClipboardManager::class.java)
    private val remoteClipboardLabel = context.applicationContext
        .getString(R.string.terminal_clipboard_remote_label)

    /** Explicit-Allow OSC 52 path; it shares the same plain-text clipboard path as selections. */
    fun writeRemoteClipboard(text: String): TerminalClipboardToken? = write(
        TerminalClipboardRequest(
            label = remoteClipboardLabel,
            text = text,
            kind = TerminalClipboardContentKind.REMOTE_SESSION,
        ),
    )

    fun write(request: TerminalClipboardRequest): TerminalClipboardToken? {
        if (request.text.isEmpty()) return null
        val manager = clipboard ?: return null
        val token = TerminalClipboardToken(UUID.randomUUID().toString())
        val clip = ClipData.newPlainText(request.label, request.text)
        clip.description.extras = PersistableBundle().apply {
            putString(CLIP_TOKEN_EXTRA, token.value)
        }
        manager.setPrimaryClip(clip)
        clearScheduler?.schedule(this, token)
        return token
    }

    override fun tryClearIfCurrent(token: TerminalClipboardToken): TerminalClipboardClearResult {
        val owner = activity.get() ?: return TerminalClipboardClearResult.TEMPORARILY_UNAVAILABLE
        if (
            (owner as? LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) != true ||
            !owner.hasWindowFocus()
        ) {
            return TerminalClipboardClearResult.TEMPORARILY_UNAVAILABLE
        }
        val manager = clipboard ?: return TerminalClipboardClearResult.TEMPORARILY_UNAVAILABLE
        val currentToken = manager.primaryClip?.description?.extras?.getString(CLIP_TOKEN_EXTRA)
        if (currentToken != token.value) return TerminalClipboardClearResult.NO_LONGER_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        return TerminalClipboardClearResult.CLEARED
    }

    internal fun clearIfCurrent(token: TerminalClipboardToken): Boolean =
        tryClearIfCurrent(token) == TerminalClipboardClearResult.CLEARED

    internal fun retryExpiredClear() = clearScheduler?.retryExpiredClear(this)

    private companion object {
        const val CLIP_TOKEN_EXTRA =
            "com.yanjiyu.terminalspike.extra.TERMINAL_CLIP_TOKEN"
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
}
