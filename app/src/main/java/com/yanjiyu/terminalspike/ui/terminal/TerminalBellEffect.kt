package com.yanjiyu.terminalspike.ui.terminal

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.terminal.TerminalBellEvent
import com.yanjiyu.terminalspike.terminal.TerminalBellListener
import com.yanjiyu.terminalspike.terminal.TerminalBellRateDecision
import com.yanjiyu.terminalspike.terminal.TerminalBellRateLimiter
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.resolveTerminalBellEffects
import com.yanjiyu.terminalspike.terminal.view.TerminalInputFocusRequester
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns BEL side effects only while this Activity visibly owns the selected terminal session. */
@Composable
internal fun TerminalBellEffect(
    sessionId: Long,
    controller: TerminalController,
    settings: BellSettings,
    foregroundUiOwnsSession: Boolean,
    terminalView: TerminalInputFocusRequester,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestSettings = rememberUpdatedState(settings)
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val limiter = remember(sessionId, controller) { TerminalBellRateLimiter() }
    val toneGenerator = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BELL_VOLUME_PERCENT) }
            .getOrNull()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(toneGenerator) {
        onDispose { toneGenerator?.release() }
    }

    val enabled = foregroundUiOwnsSession && resumed
    DisposableEffect(sessionId, controller, enabled, terminalView, toneGenerator) {
        if (!enabled) {
            limiter.suppressThrough(controller.latestBellSequence())
            return@DisposableEffect onDispose { }
        }

        limiter.suppressThrough(controller.latestBellSequence())
        var deferredPresentation: Job? = null

        fun present(event: TerminalBellEvent) {
            if (!foregroundUiOwnsSession ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                return
            }
            val effects = resolveTerminalBellEffects(
                settings = latestSettings.value,
                foregroundUiOwnsSession = true,
            )
            if (effects.visual) terminalView.presentVisualBell(event)
            if (effects.haptic) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (effects.audible) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BELL_TONE_DURATION_MS)
            }
        }

        fun consume(decision: TerminalBellRateDecision) {
            when (decision) {
                is TerminalBellRateDecision.Present -> present(decision.event)
                is TerminalBellRateDecision.Deferred -> {
                    deferredPresentation?.cancel()
                    deferredPresentation = scope.launch {
                        delay(
                            (decision.nextEligibleAtMillis - SystemClock.elapsedRealtime())
                                .coerceAtLeast(1L),
                        )
                        consume(limiter.poll(SystemClock.elapsedRealtime()))
                    }
                }
                TerminalBellRateDecision.None -> Unit
            }
        }

        val listener = TerminalBellListener { event ->
            // Controller frame publication is on the main thread. Keeping this synchronous avoids
            // an untracked launch outliving ON_PAUSE and presenting a background bell.
            consume(limiter.offer(event, SystemClock.elapsedRealtime()))
        }
        controller.addBellListener(listener)
        onDispose {
            controller.removeBellListener(listener)
            deferredPresentation?.cancel()
            limiter.suppressThrough(controller.latestBellSequence())
        }
    }
}

private const val BELL_VOLUME_PERCENT = 55
private const val BELL_TONE_DURATION_MS = 80
