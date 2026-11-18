package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputSessionOwnerTest {
    @Test
    fun cancellationRejectsEveryLateCallback() {
        val f = Fixture()
        f.owner.start("en")
        val old = f.sessions.single()
        f.owner.cancel()
        val events = f.events.toList()
        old.emitLateCallbacks()
        assertEquals(events, f.events)
        assertEquals(1, old.cancels)
        assertEquals(1, old.destroys)
    }

    @Test
    fun restartingUsesANewBackendAndRejectsTheOldResults() {
        val f = Fixture()
        f.owner.start("en")
        val old = f.sessions.single()
        f.owner.start("zh")
        val current = f.sessions.last()
        assertEquals(2, f.sessions.size)
        val events = f.events.toList()
        old.emitLateCallbacks()
        assertEquals(events, f.events)
        current.callbacks.result(" current ")
        assertEquals(listOf("current"), f.results)
        assertEquals("zh", current.language)
        assertEquals(1, old.cancels)
        assertEquals(1, old.destroys)
    }

    @Test
    fun stoppingAcceptsTheFinalResultAndReleasesTheBackend() {
        val f = Fixture()
        f.owner.start("en")
        val session = f.sessions.single()
        session.callbacks.partial("draft")
        f.owner.stop()
        assertEquals(1, session.stops)
        assertEquals("phase:PROCESSING", f.events.last())
        session.callbacks.result(" final ")
        assertEquals(listOf("final"), f.results)
        assertEquals("phase:IDLE", f.events.last())
        assertEquals(1, session.destroys)
    }

    @Test
    fun aTerminalResultRejectsDuplicatesAndLateErrors() {
        val f = Fixture()
        f.owner.start("")
        val session = f.sessions.single()
        session.callbacks.result("done")
        val events = f.events.toList()
        session.emitLateCallbacks()
        assertEquals(events, f.events)
        assertEquals(listOf("done"), f.results)
        assertTrue(f.failures.isEmpty())
        assertEquals(1, session.destroys)
    }

    @Test
    fun aBlankFinalResultReportsNoMatch() {
        val f = Fixture()
        f.owner.start("en")
        f.sessions.single().callbacks.result("  ")
        assertTrue(f.results.isEmpty())
        assertEquals(listOf(VoiceInputFailure.NO_MATCH), f.failures)
    }

    @Test
    fun aFactoryFailureIsReportedAndDoesNotPreventRetry() {
        val f = Fixture()
        f.factoryFailure = true
        f.owner.start("en")
        assertEquals(listOf(VoiceInputFailure.UNAVAILABLE), f.failures)
        assertTrue(f.sessions.isEmpty())
        assertEquals("phase:IDLE", f.events.last())
        f.factoryFailure = false
        f.owner.start("zh")
        f.sessions.single().callbacks.result("retry")
        assertEquals(listOf("retry"), f.results)
    }

    @Test
    fun anAvailabilityFailureDoesNotEscapeOrCreateABackend() {
        val f = Fixture()
        f.availabilityFailure = true
        assertFalse(f.owner.available)
        f.owner.start("en")
        assertEquals(listOf(VoiceInputFailure.UNAVAILABLE), f.failures)
        assertTrue(f.sessions.isEmpty())
    }

    @Test
    fun unavailableRecognitionDoesNotCreateABackend() {
        val f = Fixture()
        f.available = false
        f.owner.start("en")
        assertEquals(listOf(VoiceInputFailure.UNAVAILABLE), f.failures)
        assertTrue(f.sessions.isEmpty())
    }

    @Test
    fun aStartFailureRetiresTheBackendBeforeReportingUnavailable() {
        val f = Fixture()
        f.configureSession = { it.startFailure = true }
        f.owner.start("en")
        val failed = f.sessions.single()
        assertEquals(listOf(VoiceInputFailure.UNAVAILABLE), f.failures)
        assertEquals(1, failed.cancels)
        assertEquals(1, failed.destroys)
        val events = f.events.toList()
        failed.emitLateCallbacks()
        assertEquals(events, f.events)
    }

    @Test
    fun aStopFailureRetiresTheBackendAndReportsUnknown() {
        val f = Fixture()
        f.owner.start("en")
        val session = f.sessions.single()
        session.stopFailure = true
        f.owner.stop()
        assertEquals(listOf(VoiceInputFailure.UNKNOWN), f.failures)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
    }

    @Test
    fun destroyAttemptsIndependentCleanupAndCannotStartAgain() {
        val f = Fixture()
        f.owner.start("en")
        val session = f.sessions.single()
        session.cancelFailure = true
        session.destroyFailure = true
        f.owner.destroy()
        val events = f.events.toList()
        session.emitLateCallbacks()
        f.owner.destroy()
        f.owner.start("zh")
        assertEquals(events, f.events)
        assertFalse(f.owner.available)
        assertEquals(1, f.sessions.size)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
    }

    @Test
    fun synchronousCallbacksDuringCancelAndDestroyCannotOwnTheDraft() {
        val f = Fixture()
        f.owner.start("en")
        val session = f.sessions.single()
        session.onCancel = { session.emitLateCallbacks() }
        session.onDestroy = { session.emitLateCallbacks() }
        f.owner.cancel()
        assertTrue(f.results.isEmpty())
        assertTrue(f.failures.isEmpty())
        assertEquals("phase:IDLE", f.events.last())
    }

    @Test
    fun anEarlyTerminalCallbackStillReleasesTheCreatedBackendWithoutStartingIt() {
        val f = Fixture()
        f.configureSession = { it.callbacks.result("early") }
        f.owner.start("en")
        val session = f.sessions.single()
        assertEquals(listOf("early"), f.results)
        assertEquals(0, session.starts)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
    }

    @Test
    fun anExceptionAfterASynchronousFinalResultCannotReplaceThatResult() {
        val f = Fixture()
        f.configureSession = { session ->
            session.onStart = { session.callbacks.result("finished") }
            session.startFailure = true
        }
        f.owner.start("en")
        assertEquals(listOf("finished"), f.results)
        assertTrue(f.failures.isEmpty())
        assertEquals(1, f.sessions.single().destroys)
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val results = mutableListOf<String>()
        val failures = mutableListOf<VoiceInputFailure>()
        val sessions = mutableListOf<Session>()
        var available = true
        var availabilityFailure = false
        var factoryFailure = false
        var configureSession: (Session) -> Unit = {}
        val owner = VoiceInputSessionOwner(
            recognitionAvailable = {
                if (availabilityFailure) error("Owned availability failure")
                available
            },
            createSession = { callbacks ->
                if (factoryFailure) error("Owned factory failure")
                Session(callbacks).also { sessions += it; configureSession(it) }
            },
            onPhase = { events += "phase:$it" },
            onPartial = { events += "partial:$it" },
            onResult = { results += it },
            onFailure = { failures += it },
        )
    }

    private class Session(val callbacks: VoiceInputCallbacks) : VoiceInputSession {
        var starts = 0
        var stops = 0
        var cancels = 0
        var destroys = 0
        var language: String? = null
        var startFailure = false
        var stopFailure = false
        var cancelFailure = false
        var destroyFailure = false
        var onStart: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var onDestroy: () -> Unit = {}
        override fun start(languageTag: String) {
            starts++
            language = languageTag
            onStart()
            if (startFailure) error("Owned start failure")
        }
        override fun stop() { stops++; if (stopFailure) error("Owned stop failure") }
        override fun cancel() { cancels++; onCancel(); if (cancelFailure) error("Owned cancel failure") }
        override fun destroy() { destroys++; onDestroy(); if (destroyFailure) error("Owned destroy failure") }
        fun emitLateCallbacks() {
            callbacks.phase(VoiceInputPhase.LISTENING)
            callbacks.partial("stale")
            callbacks.result("stale")
            callbacks.failure(VoiceInputFailure.UNKNOWN)
        }
    }
}
