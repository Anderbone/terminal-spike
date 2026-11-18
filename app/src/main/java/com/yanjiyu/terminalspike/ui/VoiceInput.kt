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
    onPhase: (VoiceInputPhase) -> Unit,
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
    onFailure: (VoiceInputFailure) -> Unit,
) {
    private val appContext = context.applicationContext
    private val owner = VoiceInputSessionOwner(
        recognitionAvailable = { SpeechRecognizer.isRecognitionAvailable(appContext) },
        createSession = ::createSession,
        onPhase = onPhase,
        onPartial = onPartial,
        onResult = onResult,
        onFailure = onFailure,
    )

    val available: Boolean get() = owner.available
    fun start(languageTag: String) = owner.start(languageTag)
    fun stop() = owner.stop()
    fun cancel() = owner.cancel()
    fun destroy() = owner.destroy()

    private fun createSession(callbacks: VoiceInputCallbacks): VoiceInputSession {
        val recognizer = createRecognizer()
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = callbacks.phase(VoiceInputPhase.LISTENING)
                override fun onBeginningOfSpeech() = callbacks.phase(VoiceInputPhase.LISTENING)
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = callbacks.phase(VoiceInputPhase.PROCESSING)
                override fun onResults(results: Bundle?) = callbacks.result(results.bestTranscript())
                override fun onPartialResults(partialResults: Bundle?) =
                    callbacks.partial(partialResults.bestTranscript().orEmpty())
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = callbacks.failure(
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
            })
        } catch (error: Exception) {
            try { recognizer.destroy() } catch (_: Exception) { /* Preserve the setup failure. */ }
            throw error
        }
        return object : VoiceInputSession {
            override fun start(languageTag: String) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    if (languageTag.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                }
                recognizer.startListening(intent)
            }
            override fun stop() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        return if (onDevice) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                .getOrElse { SpeechRecognizer.createSpeechRecognizer(appContext) }
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
    }
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
