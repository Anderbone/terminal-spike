package com.yanjiyu.terminalspike.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class VoiceInputPhase {
    IDLE,
    LISTENING,
    PROCESSING,
}

internal enum class VoiceInputFailure {
    UNAVAILABLE,
    PERMISSION_DENIED,
    NO_MATCH,
    BUSY,
    OFFLINE_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Thin lifecycle owner around Android's speech service. The app requests offline recognition and
 * never opens its own network connection; the installed Android speech provider owns processing.
 */
internal class AndroidVoiceInputRecognizer(
    context: Context,
    private val onPhase: (VoiceInputPhase) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (VoiceInputFailure) -> Unit,
) : RecognitionListener {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(languageTag: String) {
        if (!available) {
            onFailure(VoiceInputFailure.UNAVAILABLE)
            return
        }
        cancel()
        val speechRecognizer = recognizer ?: createRecognizer().also { recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (languageTag.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        active = true
        onPartial("")
        onPhase(VoiceInputPhase.LISTENING)
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                active = false
                onPhase(VoiceInputPhase.IDLE)
                onFailure(VoiceInputFailure.UNAVAILABLE)
            }
    }

    fun stop() {
        if (!active) return
        onPhase(VoiceInputPhase.PROCESSING)
        runCatching { recognizer?.stopListening() }
            .onFailure {
                active = false
                onPhase(VoiceInputPhase.IDLE)
                onFailure(VoiceInputFailure.UNKNOWN)
            }
    }

    fun cancel() {
        if (active) runCatching { recognizer?.cancel() }
        active = false
        onPartial("")
        onPhase(VoiceInputPhase.IDLE)
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        return if (onDevice) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                .getOrElse { SpeechRecognizer.createSpeechRecognizer(appContext) }
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.also { it.setRecognitionListener(this) }
    }

    override fun onReadyForSpeech(params: Bundle?) = onPhase(VoiceInputPhase.LISTENING)
    override fun onBeginningOfSpeech() = onPhase(VoiceInputPhase.LISTENING)
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = onPhase(VoiceInputPhase.PROCESSING)

    override fun onError(error: Int) {
        active = false
        onPartial("")
        onPhase(VoiceInputPhase.IDLE)
        onFailure(
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputFailure.PERMISSION_DENIED
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> VoiceInputFailure.NO_MATCH
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceInputFailure.BUSY
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                -> VoiceInputFailure.OFFLINE_UNAVAILABLE
                else -> VoiceInputFailure.UNKNOWN
            },
        )
    }

    override fun onResults(results: Bundle?) {
        active = false
        onPartial("")
        onPhase(VoiceInputPhase.IDLE)
        val transcript = results.bestTranscript()
        if (transcript.isNullOrBlank()) onFailure(VoiceInputFailure.NO_MATCH) else onResult(transcript)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        onPartial(partialResults.bestTranscript().orEmpty())
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

private fun Bundle?.bestTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

/** Inserts recognition at the current selection, preserving editable text on both sides. */
internal fun TextFieldValue.withVoiceTranscript(transcript: String): TextFieldValue {
    val normalized = transcript.trim()
    if (normalized.isEmpty()) return this
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val leadingSpace = start > 0 && !text[start - 1].isWhitespace() && !normalized.first().isWhitespace()
    val insertion = (if (leadingSpace) " " else "") + normalized
    val nextText = text.replaceRange(start, end, insertion)
    val cursor = start + insertion.length
    return copy(text = nextText, selection = TextRange(cursor), composition = null)
}
