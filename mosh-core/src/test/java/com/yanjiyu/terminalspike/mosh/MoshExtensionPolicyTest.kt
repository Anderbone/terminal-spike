/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import com.yanjiyu.terminalspike.mosh.api.MoshDisconnectReason
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshSessionState
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MoshExtensionPolicyTest {
    @Test
    fun callerMustShareTheApplicationUid() {
        assertTrue(
            isTrustedMainCaller(
                callingUid = 10_001,
                extensionUid = 10_001,
                callerPackages = setOf("com.yanjiyu.terminalspike"),
                signaturesMatch = true,
            ),
        )
        assertFalse(isTrustedMainCaller(10_001, 10_002, setOf("look.alike"), true))
        assertFalse(isTrustedMainCaller(10_001, 10_002, setOf("com.yanjiyu.terminalspike"), false))
        assertFalse(isTrustedMainCaller(10_001, 10_002, setOf("com.yanjiyu.terminalspike"), true))
    }

    @Test
    fun allTenWorkerSlotsAreBoundedAndReusable() {
        val allocator = WorkerSlotAllocator(MOSH_WORKER_COUNT)
        assertEquals((0 until MOSH_WORKER_COUNT).toList(), List(MOSH_WORKER_COUNT) { allocator.acquire() })
        assertNull(allocator.acquire())
        allocator.release(1)
        assertEquals(1, allocator.acquire())
        assertThrows(IllegalStateException::class.java) { allocator.release(0).also { allocator.release(0) } }
    }

    @Test
    fun completedOrReplacedSessionDoesNotRetainLateWorkerBinding() {
        assertTrue(shouldRetainWorkerBinding(isCurrentSession = true, isFinished = false))
        assertFalse(shouldRetainWorkerBinding(isCurrentSession = true, isFinished = true))
        assertFalse(shouldRetainWorkerBinding(isCurrentSession = false, isFinished = false))
        assertFalse(shouldRetainWorkerBinding(isCurrentSession = false, isFinished = true))
    }

    @Test
    fun callbackBroadcastsCannotOverlapAcrossBinderThreads() {
        val gate = SerializedCallbackGate()
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        try {
            val first = executor.submit {
                gate.run {
                    firstEntered.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

            val second = executor.submit {
                secondAttempting.countDown()
                gate.run { secondEntered.countDown() }
            }
            assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals(0L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun nativeResultsMapOnlyToBoundedRedactedApiEvents() {
        val timeout = nativeResultEvent(MoshNativeResult.UDP_TIMEOUT, 0)
        assertEquals(MoshSessionState.ERROR, timeout.state)
        assertEquals(MoshErrorCode.UDP_TIMEOUT_OR_FIREWALL, timeout.errorCode)
        assertFalse(timeout.detail.contains(':'))

        val replaced = nativeResultEvent(
            MoshNativeResult.CANCELLED,
            MoshStopReason.SESSION_REPLACED,
        )
        assertEquals(MoshSessionState.DISCONNECTED, replaced.state)
        assertEquals(MoshDisconnectReason.SESSION_REPLACED, replaced.disconnectReason)
    }
}
