package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One bounded, non-secret question supplied by the SSH server. */
data class KeyboardInteractiveQuestion(
    val prompt: String,
    val echo: Boolean,
)

/**
 * Public prompt metadata for one exact keyboard-interactive exchange.
 *
 * Responses deliberately never enter this object, a Flow, SavedState, Room, logs, or
 * notifications. The process-wide token prevents a response retained by an old Activity from
 * resolving a later challenge after a reconnect or session replacement.
 */
data class KeyboardInteractiveChallenge(
    val challengeToken: Long,
    val name: String,
    val instruction: String,
    val questions: List<KeyboardInteractiveQuestion>,
) : ConnectionPrompt {
    init {
        require(challengeToken > 0L) { "Keyboard-interactive challenge token must be positive." }
        require(questions.size <= KeyboardInteractiveLimits.MAX_PROMPTS_PER_CHALLENGE) {
            "Keyboard-interactive challenge has too many prompts."
        }
    }
}

/**
 * Session-owned implementation of JSch's synchronous keyboard-interactive callback.
 *
 * The bridge retains only mutable response arrays. JSch 2.28.3 requires a [String] array at the
 * callback return boundary, so Strings are constructed only in [promptKeyboardInteractive], are
 * returned once, and are never retained by application state. JVM Strings cannot be wiped; every
 * mutable source array is wiped immediately in `finally`.
 */
internal class KeyboardInteractiveBridge(
    private val onChallenge: (KeyboardInteractiveChallenge) -> Unit,
    private val timeoutMillis: Long = KeyboardInteractiveLimits.DEFAULT_TIMEOUT_MILLIS,
    /** Deterministic race seam; production callers always use the no-op default. */
    private val beforeResolutionConsumeForTest: () -> Unit = {},
    /** Deterministic reusable-response ownership seam; production callers use the default. */
    private val afterReusableResponseTakeForTest: () -> Unit = {},
    /** Deterministic interactive-response ownership seam; production callers use the default. */
    private val afterInteractiveResponsesTakeForTest: () -> Unit = {},
) : UserInfo, UIKeyboardInteractive, AutoCloseable {
    private val lock = Any()
    private var closed = false
    /** Counts every admitted callback, including callbacks resolved by a reusable response. */
    private var challengeCount = 0
    private var active: PendingChallenge? = null
    private var reusableResponse: ByteArray? = null
    /** Keeps close and final immutable-String construction on one linearized ownership path. */
    private var inFlightReusableResponse: ByteArray? = null
    /** Keeps accepted response arrays bridge-owned until their one-shot JSch conversion completes. */
    private var inFlightInteractiveResponses: List<CharArray>? = null

    init {
        require(timeoutMillis > 0L) { "Keyboard-interactive timeout must be positive." }
    }

    /** Copies the source so its caller can wipe its own credential boundary immediately. */
    fun replaceReusableResponse(source: ByteArray) {
        val owned = source.copyOf()
        val valid = owned.size <= KeyboardInteractiveLimits.MAX_RESPONSE_UTF8_BYTES &&
            isValidUtf8(owned)
        val accepted = synchronized(lock) {
            reusableResponse?.fill(0)
            reusableResponse = null
            if (closed || !valid) {
                false
            } else {
                reusableResponse = owned
                true
            }
        }
        if (!accepted) owned.fill(0)
    }

    /** Takes ownership of every response array, including on a stale or invalid answer. */
    fun answer(challengeToken: Long, responses: List<CharArray>): Boolean {
        val owned = responses.toList()
        val pending = synchronized(lock) {
            active?.takeIf { current ->
                !closed && current.challenge.challengeToken == challengeToken &&
                    current.resolution == null &&
                    owned.size == current.challenge.questions.size &&
                    owned.all(::isValidResponse)
            }?.also { current ->
                current.resolution = ChallengeResolution.Answered(owned)
            }
        }
        if (pending == null) {
            owned.wipe()
            return false
        }
        pending.completed.countDown()
        return true
    }

    fun cancel(challengeToken: Long): Boolean {
        val pending = synchronized(lock) {
            active?.takeIf { current ->
                current.challenge.challengeToken == challengeToken && current.resolution == null
            }?.also { current -> current.resolution = ChallengeResolution.Cancelled }
        } ?: return false
        pending.completed.countDown()
        return true
    }

    fun cancelActive(): Boolean {
        val pending = synchronized(lock) {
            active?.takeIf { current -> current.resolution == null }
                ?.also { current -> current.resolution = ChallengeResolution.Cancelled }
        } ?: return false
        pending.completed.countDown()
        return true
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            reusableResponse?.fill(0)
            reusableResponse = null
            inFlightReusableResponse?.fill(0)
            inFlightReusableResponse = null
            inFlightInteractiveResponses?.wipe()
            inFlightInteractiveResponses = null
            active?.also { current ->
                (current.resolution as? ChallengeResolution.Answered)?.responses?.wipe()
                current.resolution = ChallengeResolution.Cancelled
            }
        }
        pending?.completed?.countDown()
    }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        // `destination` contains `username@host`; it is intentionally neither retained nor
        // published. The session already owns the canonical, privacy-reviewed endpoint metadata.
        val safeName = name.orEmpty()
        val safeInstruction = instruction.orEmpty()
        val prompts = prompt ?: emptyArray()
        val echoFlags = echo ?: BooleanArray(0)
        if (!isValidChallenge(safeName, safeInstruction, prompts, echoFlags)) {
            discardReusableResponse()
            return null
        }

        val challenge = when (
            val admission = admitChallenge(safeName, safeInstruction, prompts, echoFlags)
        ) {
            ChallengeAdmission.Rejected -> return null
            is ChallengeAdmission.Reusable -> return consumeReusableResponse(admission.response)
            is ChallengeAdmission.Interactive -> admission.pending
        }

        try {
            onChallenge(challenge.challenge)
        } catch (_: Exception) {
            cancel(challenge.challenge.challengeToken)
        }

        val completed = try {
            challenge.completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        var interruptedBeforeConsume = false
        try {
            beforeResolutionConsumeForTest()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            interruptedBeforeConsume = true
        }
        val mustAbort = !completed || interruptedBeforeConsume || Thread.currentThread().isInterrupted
        val responses = synchronized(lock) {
            if (mustAbort) {
                (challenge.resolution as? ChallengeResolution.Answered)?.responses?.wipe()
                challenge.resolution = ChallengeResolution.TimedOut
            }
            if (active === challenge) active = null
            val answered = challenge.resolution as? ChallengeResolution.Answered
            challenge.resolution = ChallengeResolution.Consumed
            answered?.responses?.also { accepted ->
                inFlightInteractiveResponses = accepted
            }
        } ?: return null

        return consumeInteractiveResponses(responses)
    }

    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) = Unit

    private fun admitChallenge(
        name: String,
        instruction: String,
        prompts: Array<out String>,
        echoFlags: BooleanArray,
    ): ChallengeAdmission = synchronized(lock) {
        if (
            closed || active != null || inFlightReusableResponse != null ||
            inFlightInteractiveResponses != null ||
            challengeCount >= KeyboardInteractiveLimits.MAX_TOTAL_CHALLENGES
        ) {
            reusableResponse?.fill(0)
            reusableResponse = null
            return@synchronized ChallengeAdmission.Rejected
        }

        challengeCount += 1
        val reusable = reusableResponse
        if (
            reusable != null && prompts.size == 1 && !echoFlags[0] &&
            reusable.size <= KeyboardInteractiveLimits.MAX_RESPONSE_UTF8_BYTES &&
            isValidUtf8(reusable)
        ) {
            reusableResponse = null
            inFlightReusableResponse = reusable
            ChallengeAdmission.Reusable(reusable)
        } else {
            reusable?.fill(0)
            reusableResponse = null
            val challenge = KeyboardInteractiveChallenge(
                challengeToken = nextKeyboardInteractiveToken(),
                name = name,
                instruction = instruction,
                questions = prompts.indices.map { index ->
                    KeyboardInteractiveQuestion(prompts[index], echoFlags[index])
                },
            )
            val pending = PendingChallenge(challenge)
            active = pending
            ChallengeAdmission.Interactive(pending)
        }
    }

    private fun consumeReusableResponse(response: ByteArray): Array<String>? {
        val canConsume = try {
            afterReusableResponseTakeForTest()
            !Thread.currentThread().isInterrupted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        if (!canConsume) {
            discardInFlightReusableResponse(response)
            return null
        }

        // Holding the ownership lock through String construction is the linearization point:
        // close either wipes first and this returns null, or waits for this one-shot conversion.
        return synchronized(lock) {
            if (closed || inFlightReusableResponse !== response) {
                if (inFlightReusableResponse === response) inFlightReusableResponse = null
                response.fill(0)
                return@synchronized null
            }
            try {
                // Unavoidable one-shot third-party API boundary. Never retain this String.
                arrayOf(String(response, StandardCharsets.UTF_8))
            } finally {
                inFlightReusableResponse = null
                response.fill(0)
            }
        }
    }

    private fun discardInFlightReusableResponse(response: ByteArray) {
        synchronized(lock) {
            if (inFlightReusableResponse === response) inFlightReusableResponse = null
            response.fill(0)
        }
    }

    private fun consumeInteractiveResponses(responses: List<CharArray>): Array<String>? {
        val canConsume = try {
            afterInteractiveResponsesTakeForTest()
            !Thread.currentThread().isInterrupted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        if (!canConsume) {
            discardInFlightInteractiveResponses(responses)
            return null
        }

        // This mirrors reusable-response ownership: close either wipes first and this returns
        // null, or waits behind the one-shot immutable String construction and source wipe.
        return synchronized(lock) {
            if (closed || inFlightInteractiveResponses !== responses) {
                if (inFlightInteractiveResponses === responses) {
                    inFlightInteractiveResponses = null
                }
                responses.wipe()
                return@synchronized null
            }
            try {
                // Unavoidable one-shot third-party API boundary. Never retain these Strings.
                Array(responses.size) { index -> responses[index].concatToString() }
            } finally {
                inFlightInteractiveResponses = null
                responses.wipe()
            }
        }
    }

    private fun discardInFlightInteractiveResponses(responses: List<CharArray>) {
        synchronized(lock) {
            if (inFlightInteractiveResponses === responses) {
                inFlightInteractiveResponses = null
            }
            responses.wipe()
        }
    }

    private fun discardReusableResponse() {
        synchronized(lock) {
            reusableResponse?.fill(0)
            reusableResponse = null
        }
    }
}

/** Mutable-response control surface retained only by the live transport owner. */
internal interface KeyboardInteractivePromptController {
    fun answerKeyboardInteractiveChallenge(
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean

    fun cancelKeyboardInteractiveChallenge(challengeToken: Long): Boolean

    fun cancelPendingKeyboardInteractiveChallenge(): Boolean
}

internal object KeyboardInteractiveLimits {
    const val MAX_PROMPTS_PER_CHALLENGE = 8
    const val MAX_TOTAL_CHALLENGES = 8
    const val MAX_NAME_UTF8_BYTES = 1_024
    const val MAX_INSTRUCTION_UTF8_BYTES = 4_096
    const val MAX_PROMPT_UTF8_BYTES = 1_024
    const val MAX_RESPONSE_UTF8_BYTES = 4_096
    const val DEFAULT_TIMEOUT_MILLIS = 120_000L
}

private class PendingChallenge(
    val challenge: KeyboardInteractiveChallenge,
) {
    val completed = CountDownLatch(1)
    var resolution: ChallengeResolution? = null
}

private sealed interface ChallengeResolution {
    class Answered(val responses: List<CharArray>) : ChallengeResolution
    data object Cancelled : ChallengeResolution
    data object TimedOut : ChallengeResolution
    data object Consumed : ChallengeResolution
}

private sealed interface ChallengeAdmission {
    class Reusable(val response: ByteArray) : ChallengeAdmission
    class Interactive(val pending: PendingChallenge) : ChallengeAdmission
    data object Rejected : ChallengeAdmission
}

private fun isValidChallenge(
    name: String,
    instruction: String,
    prompts: Array<out String>,
    echo: BooleanArray,
): Boolean = prompts.size <= KeyboardInteractiveLimits.MAX_PROMPTS_PER_CHALLENGE &&
    echo.size == prompts.size &&
    name.hasBoundedUtf8Length(KeyboardInteractiveLimits.MAX_NAME_UTF8_BYTES) &&
    instruction.hasBoundedUtf8Length(KeyboardInteractiveLimits.MAX_INSTRUCTION_UTF8_BYTES) &&
    prompts.all { it.hasBoundedUtf8Length(KeyboardInteractiveLimits.MAX_PROMPT_UTF8_BYTES) }

private fun isValidResponse(response: CharArray): Boolean =
    response.hasBoundedUtf8Length(KeyboardInteractiveLimits.MAX_RESPONSE_UTF8_BYTES)

private fun String.hasBoundedUtf8Length(maxBytes: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.code <= 0x7f -> bytes += 1
            current.code <= 0x7ff -> bytes += 2
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                bytes += 4
                index += 1
            }
            current.isLowSurrogate() -> return false
            else -> bytes += 3
        }
        if (bytes > maxBytes) return false
        index += 1
    }
    return true
}

private fun CharArray.hasBoundedUtf8Length(maxBytes: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < size) {
        val current = this[index]
        when {
            current.code <= 0x7f -> bytes += 1
            current.code <= 0x7ff -> bytes += 2
            current.isHighSurrogate() -> {
                if (index + 1 >= size || !this[index + 1].isLowSurrogate()) return false
                bytes += 4
                index += 1
            }
            current.isLowSurrogate() -> return false
            else -> bytes += 3
        }
        if (bytes > maxBytes) return false
        index += 1
    }
    return true
}

private fun isValidUtf8(bytes: ByteArray): Boolean {
    if (bytes.size > KeyboardInteractiveLimits.MAX_RESPONSE_UTF8_BYTES) return false
    val decodedScratch = CharArray(bytes.size)
    return try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = ByteBuffer.wrap(bytes)
        val decoded = CharBuffer.wrap(decodedScratch)
        val result = decoder.decode(encoded, decoded, true)
        result.isUnderflow && !encoded.hasRemaining() && decoder.flush(decoded).isUnderflow
    } finally {
        decodedScratch.fill('\u0000')
    }
}

private fun List<CharArray>.wipe() {
    forEach { it.fill('\u0000') }
}

private fun nextKeyboardInteractiveToken(): Long {
    while (true) {
        val current = KeyboardInteractiveTokenSource.get()
        val next = if (current == Long.MAX_VALUE || current <= 0L) 1L else current + 1L
        if (KeyboardInteractiveTokenSource.compareAndSet(current, next)) return next
    }
}

private val KeyboardInteractiveTokenSource = AtomicLong(0L)
