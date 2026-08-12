package com.yanjiyu.terminalspike.connection

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInteractiveBridgeTest {
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun reusableResponseIsUsedOnlyForSupportedSingleHiddenPromptAndOwnedCopyIsWiped() {
        val challenges = mutableListOf<KeyboardInteractiveChallenge>()
        val reusable = "saved response".encodeToByteArray()
        val bridge = KeyboardInteractiveBridge(challenges::add, timeoutMillis = 1_000)
        bridge.replaceReusableResponse(reusable)
        val ownedReusable = bridge.retainedReusableResponseForTest()
        assertFalse(ownedReusable === reusable)
        assertArrayEquals(reusable, ownedReusable)
        reusable.fill(0)

        val result = bridge.promptKeyboardInteractive(
            "ignored-user@example.test",
            "authentication",
            "",
            arrayOf("Password:"),
            booleanArrayOf(false),
        )

        assertEquals(listOf("saved response"), result?.toList())
        assertTrue(challenges.isEmpty())
        assertTrue(reusable.isZeroed())
        assertTrue(ownedReusable.isZeroed())
        bridge.close()
    }

    @Test
    fun oversizedMultibyteReusableResponseIsWipedAndFallsBackToChallenge() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val reusable = "界".repeat(1_366).encodeToByteArray()
        assertTrue(reusable.size > KeyboardInteractiveLimits.MAX_RESPONSE_UTF8_BYTES)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        bridge.replaceReusableResponse(reusable)
        reusable.fill(0)

        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val current = "bounded".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(current)))
        assertEquals("bounded", future.get(1, TimeUnit.SECONDS)?.single())
        assertTrue(current.isZeroed())
        bridge.close()
    }

    @Test
    fun malformedUtf8ReusableResponseIsRejectedAndFallsBackToChallenge() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)

        bridge.replaceReusableResponse(malformed)

        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), malformed)
        assertNull(bridge.retainedReusableResponseOrNullForTest())
        assertEquals(0, bridge.challengeCountForTest())
        malformed.fill(0)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val challenge = requireNotNull(challengeQueue.poll(1, TimeUnit.SECONDS))
        val response = "valid response".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(response)))
        assertEquals("valid response", future.get(1, TimeUnit.SECONDS)?.single())
        assertTrue(response.isZeroed())
        assertEquals(1, bridge.challengeCountForTest())
        bridge.close()
    }

    @Test
    fun unsupportedReusableShapeFallsBackToMixedEchoChallenge() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val reusable = "must-not-be-reused".encodeToByteArray()
        bridge.replaceReusableResponse(reusable)
        reusable.fill(0)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Two factor",
                "Enter both values",
                arrayOf("Account:", "One-time code:"),
                booleanArrayOf(true, false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val first = "alice".toCharArray()
        val second = "123456".toCharArray()

        assertEquals(listOf(true, false), challenge.questions.map { it.echo })
        assertTrue(bridge.answer(challenge.challengeToken, listOf(first, second)))
        assertEquals(listOf("alice", "123456"), future.get(1, TimeUnit.SECONDS)?.toList())
        assertTrue(first.isZeroed())
        assertTrue(second.isZeroed())
        bridge.close()
    }

    @Test
    fun consecutiveOtpThenPasswordChallengesHaveDifferentTokensAndWipeBothResponses() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(2)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)

        val otpFuture = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "OTP",
                "",
                arrayOf("Code:"),
                booleanArrayOf(false),
            )
        }
        val otpChallenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val otp = "654321".toCharArray()
        assertTrue(bridge.answer(otpChallenge.challengeToken, listOf(otp)))
        assertEquals("654321", otpFuture.get(1, TimeUnit.SECONDS)?.single())
        assertTrue(otp.isZeroed())

        val passwordFuture = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val passwordChallenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val password = "fresh secret".toCharArray()
        assertNotEquals(otpChallenge.challengeToken, passwordChallenge.challengeToken)
        assertTrue(bridge.answer(passwordChallenge.challengeToken, listOf(password)))
        assertEquals("fresh secret", passwordFuture.get(1, TimeUnit.SECONDS)?.single())
        assertTrue(password.isZeroed())
        bridge.close()
    }

    @Test
    fun timeoutReturnsNullAndRejectsThenWipesLateResponse() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 10)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "OTP",
                "",
                arrayOf("Code:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)

        assertNull(future.get(1, TimeUnit.SECONDS))
        val late = "too late".toCharArray()
        assertFalse(bridge.answer(challenge.challengeToken, listOf(late)))
        assertTrue(late.isZeroed())
        bridge.close()
    }

    @Test
    fun timeoutWinsWhenAnswerArrivesBeforeTimedOutResolutionIsConsumed() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val consumeEntered = CountDownLatch(1)
        val releaseConsume = CountDownLatch(1)
        val bridge = KeyboardInteractiveBridge(
            onChallenge = challengeQueue::put,
            timeoutMillis = 10,
            beforeResolutionConsumeForTest = {
                consumeEntered.countDown()
                releaseConsume.await()
            },
        )
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "OTP",
                "",
                arrayOf("Code:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        assertTrue(consumeEntered.await(1, TimeUnit.SECONDS))
        val raced = "arrived-after-timeout".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(raced)))
        releaseConsume.countDown()

        assertNull(future.get(1, TimeUnit.SECONDS))
        assertTrue(raced.isZeroed())
        bridge.close()
    }

    @Test
    fun interruptionWinsAfterAnswerIsAcceptedButBeforeItIsConsumed() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val consumeEntered = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val bridge = KeyboardInteractiveBridge(
            onChallenge = challengeQueue::put,
            timeoutMillis = 1_000,
            beforeResolutionConsumeForTest = {
                consumeEntered.countDown()
                neverReleased.await()
            },
        )
        val future = executor.submit<Array<String>?> {
            callbackThread.set(Thread.currentThread())
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val raced = "accepted-before-interrupt".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(raced)))
        assertTrue(consumeEntered.await(1, TimeUnit.SECONDS))
        callbackThread.get().interrupt()

        assertNull(future.get(1, TimeUnit.SECONDS))
        assertTrue(raced.isZeroed())
        bridge.close()
    }

    @Test
    fun closeWinsAfterAnswerIsAcceptedButBeforeItIsConsumed() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val consumeEntered = CountDownLatch(1)
        val releaseConsume = CountDownLatch(1)
        val bridge = KeyboardInteractiveBridge(
            onChallenge = challengeQueue::put,
            timeoutMillis = 1_000,
            beforeResolutionConsumeForTest = {
                consumeEntered.countDown()
                releaseConsume.await()
            },
        )
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val raced = "accepted-before-close".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(raced)))
        assertTrue(consumeEntered.await(1, TimeUnit.SECONDS))
        bridge.close()
        releaseConsume.countDown()

        assertNull(future.get(1, TimeUnit.SECONDS))
        assertTrue(raced.isZeroed())
    }

    @Test
    fun closeWinsAfterAcceptedResponsesBecomeInFlightButBeforeStringsAreConstructed() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val responsesTaken = CountDownLatch(1)
        val releaseResponses = CountDownLatch(1)
        val bridge = KeyboardInteractiveBridge(
            onChallenge = challengeQueue::put,
            timeoutMillis = 1_000,
            afterInteractiveResponsesTakeForTest = {
                responsesTaken.countDown()
                releaseResponses.await()
            },
        )
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Two factors",
                "",
                arrayOf("Password:", "Code:"),
                booleanArrayOf(false, false),
            )
        }
        val challenge = requireNotNull(challengeQueue.poll(1, TimeUnit.SECONDS))
        val password = "accepted-password".toCharArray()
        val code = "123456".toCharArray()

        assertTrue(bridge.answer(challenge.challengeToken, listOf(password, code)))
        assertTrue(responsesTaken.await(1, TimeUnit.SECONDS))
        bridge.close()
        releaseResponses.countDown()

        assertNull(future.get(1, TimeUnit.SECONDS))
        assertTrue(password.isZeroed())
        assertTrue(code.isZeroed())
    }

    @Test
    fun closeWinsAfterReusableResponseOwnershipIsTakenButBeforeItIsConsumed() {
        val challenges = mutableListOf<KeyboardInteractiveChallenge>()
        val reusableTaken = CountDownLatch(1)
        val releaseReusable = CountDownLatch(1)
        val bridge = KeyboardInteractiveBridge(
            onChallenge = challenges::add,
            timeoutMillis = 1_000,
            afterReusableResponseTakeForTest = {
                reusableTaken.countDown()
                releaseReusable.await()
            },
        )
        val reusable = "must-not-escape-close".encodeToByteArray()
        bridge.replaceReusableResponse(reusable)
        val ownedReusable = bridge.retainedReusableResponseForTest()
        reusable.fill(0)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }

        assertTrue(reusableTaken.await(1, TimeUnit.SECONDS))
        bridge.close()
        releaseReusable.countDown()

        assertNull(future.get(1, TimeUnit.SECONDS))
        assertTrue(ownedReusable.isZeroed())
        assertTrue(challenges.isEmpty())
    }

    @Test
    fun tokenScopedCancellationReturnsNullWithoutResolvingLaterChallenge() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(2)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val firstFuture = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "First",
                "",
                arrayOf("First:"),
                booleanArrayOf(false),
            )
        }
        val first = challengeQueue.poll(1, TimeUnit.SECONDS)
        assertTrue(bridge.cancel(first.challengeToken))
        assertNull(firstFuture.get(1, TimeUnit.SECONDS))

        val secondFuture = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Second",
                "",
                arrayOf("Second:"),
                booleanArrayOf(false),
            )
        }
        val second = challengeQueue.poll(1, TimeUnit.SECONDS)
        val stale = "stale".toCharArray()
        assertFalse(bridge.answer(first.challengeToken, listOf(stale)))
        assertTrue(stale.isZeroed())
        val current = "current".toCharArray()
        assertTrue(bridge.answer(second.challengeToken, listOf(current)))
        assertEquals("current", secondFuture.get(1, TimeUnit.SECONDS)?.single())
        assertTrue(current.isZeroed())
        bridge.close()
    }

    @Test
    fun closeCancelsPendingChallengeAndWipesStaleResponseSubmittedAfterClose() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        bridge.close()

        assertNull(future.get(1, TimeUnit.SECONDS))
        val response = "submitted-after-close".toCharArray()
        assertFalse(bridge.answer(challenge.challengeToken, listOf(response)))
        assertTrue(response.isZeroed())
    }

    @Test
    fun oversizedChallengeIsRejectedWithoutPublishing() {
        val challenges = mutableListOf<KeyboardInteractiveChallenge>()
        val bridge = KeyboardInteractiveBridge(challenges::add, timeoutMillis = 1_000)

        val result = bridge.promptKeyboardInteractive(
            "ignored",
            "name",
            "instruction",
            Array(KeyboardInteractiveLimits.MAX_PROMPTS_PER_CHALLENGE + 1) { "Prompt" },
            BooleanArray(KeyboardInteractiveLimits.MAX_PROMPTS_PER_CHALLENGE + 1),
        )

        assertNull(result)
        assertTrue(challenges.isEmpty())
        bridge.close()
    }

    @Test
    fun totalChallengeLimitRejectsTheNextExchangeWithoutPublishingIt() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)

        repeat(KeyboardInteractiveLimits.MAX_TOTAL_CHALLENGES) { index ->
            val future = executor.submit<Array<String>?> {
                bridge.promptKeyboardInteractive(
                    "ignored",
                    "Challenge ${index + 1}",
                    "",
                    arrayOf("Code:"),
                    booleanArrayOf(false),
                )
            }
            val challenge = requireNotNull(challengeQueue.poll(1, TimeUnit.SECONDS))
            val response = "response-${index + 1}".toCharArray()

            assertTrue(bridge.answer(challenge.challengeToken, listOf(response)))
            assertEquals("response-${index + 1}", future.get(1, TimeUnit.SECONDS)?.single())
            assertTrue(response.isZeroed())
        }

        assertNull(
            bridge.promptKeyboardInteractive(
                "ignored",
                "One challenge too many",
                "",
                arrayOf("Code:"),
                booleanArrayOf(false),
            ),
        )
        assertTrue(challengeQueue.isEmpty())
        bridge.close()
    }

    @Test
    fun reusableAndInteractiveCallbacksShareTheTotalChallengeLimit() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val reusable = "first response".encodeToByteArray()
        bridge.replaceReusableResponse(reusable)
        val firstOwnedReusable = bridge.retainedReusableResponseForTest()
        reusable.fill(0)

        assertEquals(
            "first response",
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            )?.single(),
        )
        assertTrue(firstOwnedReusable.isZeroed())

        repeat(KeyboardInteractiveLimits.MAX_TOTAL_CHALLENGES - 1) { index ->
            assertChallengePublishedThenCancel(
                bridge,
                challengeQueue,
                name = "Interactive challenge ${index + 1}",
                instruction = "",
                prompt = "Code:",
            )
        }

        val rejectedReusable = "must-not-bypass-cap".encodeToByteArray()
        bridge.replaceReusableResponse(rejectedReusable)
        val rejectedOwnedReusable = bridge.retainedReusableResponseForTest()
        rejectedReusable.fill(0)
        assertNull(
            bridge.promptKeyboardInteractive(
                "ignored",
                "Password",
                "",
                arrayOf("Password:"),
                booleanArrayOf(false),
            ),
        )
        assertTrue(rejectedOwnedReusable.isZeroed())
        assertTrue(challengeQueue.isEmpty())
        bridge.close()
    }

    @Test
    fun nameInstructionAndPromptBoundsAreMeasuredInUtf8Bytes() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val boundedName = utf8TextOfSize(KeyboardInteractiveLimits.MAX_NAME_UTF8_BYTES)
        val oversizedName = utf8TextOfSize(KeyboardInteractiveLimits.MAX_NAME_UTF8_BYTES + 1)
        val boundedInstruction =
            utf8TextOfSize(KeyboardInteractiveLimits.MAX_INSTRUCTION_UTF8_BYTES)
        val oversizedInstruction =
            utf8TextOfSize(KeyboardInteractiveLimits.MAX_INSTRUCTION_UTF8_BYTES + 1)
        val boundedPrompt = utf8TextOfSize(KeyboardInteractiveLimits.MAX_PROMPT_UTF8_BYTES)
        val oversizedPrompt = utf8TextOfSize(KeyboardInteractiveLimits.MAX_PROMPT_UTF8_BYTES + 1)

        assertChallengePublishedThenCancel(
            bridge,
            challengeQueue,
            name = boundedName,
            instruction = "",
            prompt = "Code:",
        )
        assertChallengeRejected(
            bridge,
            challengeQueue,
            name = oversizedName,
            instruction = "",
            prompt = "Code:",
        )
        assertChallengePublishedThenCancel(
            bridge,
            challengeQueue,
            name = "Authentication",
            instruction = boundedInstruction,
            prompt = "Code:",
        )
        assertChallengeRejected(
            bridge,
            challengeQueue,
            name = "Authentication",
            instruction = oversizedInstruction,
            prompt = "Code:",
        )
        assertChallengePublishedThenCancel(
            bridge,
            challengeQueue,
            name = "Authentication",
            instruction = "",
            prompt = boundedPrompt,
        )
        assertChallengeRejected(
            bridge,
            challengeQueue,
            name = "Authentication",
            instruction = "",
            prompt = oversizedPrompt,
        )

        bridge.close()
    }

    @Test
    fun promptEchoCountMismatchIsRejectedAndDiscardsReusableOwnedCopy() {
        val challenges = mutableListOf<KeyboardInteractiveChallenge>()
        val bridge = KeyboardInteractiveBridge(challenges::add, timeoutMillis = 1_000)
        val reusable = "must-be-discarded".encodeToByteArray()
        bridge.replaceReusableResponse(reusable)
        val ownedReusable = bridge.retainedReusableResponseForTest()
        reusable.fill(0)

        assertNull(
            bridge.promptKeyboardInteractive(
                "ignored",
                "Two prompts",
                "",
                arrayOf("First:", "Second:"),
                booleanArrayOf(false),
            ),
        )
        assertNull(
            bridge.promptKeyboardInteractive(
                "ignored",
                "One prompt",
                "",
                arrayOf("Only:"),
                booleanArrayOf(false, true),
            ),
        )

        assertTrue(challenges.isEmpty())
        assertTrue(ownedReusable.isZeroed())
        bridge.close()
    }

    @Test
    fun responseCountMismatchIsRejectedAndWipedWhileChallengeRemainsAnswerable() {
        val challengeQueue = ArrayBlockingQueue<KeyboardInteractiveChallenge>(1)
        val bridge = KeyboardInteractiveBridge(challengeQueue::put, timeoutMillis = 1_000)
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                "Two prompts",
                "",
                arrayOf("One:", "Two:"),
                booleanArrayOf(false, false),
            )
        }
        val challenge = challengeQueue.poll(1, TimeUnit.SECONDS)
        val incomplete = "only one".toCharArray()
        assertFalse(bridge.answer(challenge.challengeToken, listOf(incomplete)))
        assertTrue(incomplete.isZeroed())

        val first = "one".toCharArray()
        val second = "two".toCharArray()
        assertTrue(bridge.answer(challenge.challengeToken, listOf(first, second)))
        assertArrayEquals(arrayOf("one", "two"), future.get(1, TimeUnit.SECONDS))
        assertTrue(first.isZeroed())
        assertTrue(second.isZeroed())
        bridge.close()
    }

    private fun assertChallengePublishedThenCancel(
        bridge: KeyboardInteractiveBridge,
        challengeQueue: ArrayBlockingQueue<KeyboardInteractiveChallenge>,
        name: String,
        instruction: String,
        prompt: String,
    ) {
        val future = executor.submit<Array<String>?> {
            bridge.promptKeyboardInteractive(
                "ignored",
                name,
                instruction,
                arrayOf(prompt),
                booleanArrayOf(false),
            )
        }
        val challenge = requireNotNull(challengeQueue.poll(1, TimeUnit.SECONDS))

        assertEquals(name, challenge.name)
        assertEquals(instruction, challenge.instruction)
        assertEquals(prompt, challenge.questions.single().prompt)
        assertTrue(bridge.cancel(challenge.challengeToken))
        assertNull(future.get(1, TimeUnit.SECONDS))
    }

    private fun assertChallengeRejected(
        bridge: KeyboardInteractiveBridge,
        challengeQueue: ArrayBlockingQueue<KeyboardInteractiveChallenge>,
        name: String,
        instruction: String,
        prompt: String,
    ) {
        assertNull(
            bridge.promptKeyboardInteractive(
                "ignored",
                name,
                instruction,
                arrayOf(prompt),
                booleanArrayOf(false),
            ),
        )
        assertTrue(challengeQueue.isEmpty())
    }
}

private fun CharArray.isZeroed(): Boolean = all { it == '\u0000' }

private fun ByteArray.isZeroed(): Boolean = all { it == 0.toByte() }

private fun KeyboardInteractiveBridge.retainedReusableResponseForTest(): ByteArray {
    return requireNotNull(retainedReusableResponseOrNullForTest())
}

private fun KeyboardInteractiveBridge.retainedReusableResponseOrNullForTest(): ByteArray? {
    val field = javaClass.getDeclaredField("reusableResponse")
    field.isAccessible = true
    return field.get(this) as? ByteArray
}

private fun KeyboardInteractiveBridge.challengeCountForTest(): Int {
    val field = javaClass.getDeclaredField("challengeCount")
    field.isAccessible = true
    return field.getInt(this)
}

private fun utf8TextOfSize(byteCount: Int): String = buildString {
    repeat(byteCount / THREE_BYTE_UTF8_CHARACTER_SIZE) { append('\u754c') }
    repeat(byteCount % THREE_BYTE_UTF8_CHARACTER_SIZE) { append('a') }
}.also { value ->
    check(value.encodeToByteArray().size == byteCount)
}

private const val THREE_BYTE_UTF8_CHARACTER_SIZE = 3
