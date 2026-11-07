package com.yanjiyu.terminalspike.terminal.view

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.ui.TerminalSpikeViewModel
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.HostConnectRequest
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.connection.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual Codex in a runner-owned Herdr session; never selects an existing user pane. */
class HerdrInlineRealEndToEndTest {
    @Test fun actualInlineCodexHasOrderedHistoryAndNativeMotion(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val session = args.getString("herdrE2eSession").orEmpty()
        assumeTrue("Requires an isolated Herdr fixture", session.startsWith("terminal-spike-inline-"))
        requireAuthorizedScrollDevice()
        val key = android.util.Base64.decode(requireNotNull(args.getString("sshE2ePrivateKeyBase64")), 0)
        val command = String(android.util.Base64.decode(requireNotNull(args.getString("sshE2eCodexCommandBase64")), 0))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val knownHosts = File.createTempFile("herdr-e2e-", ".hosts", context.cacheDir)
        val ssh = JschSshConnection({ knownHosts }, SshConnectionConfig(
            host = requireNotNull(args.getString("sshE2eHost")),
            port = args.getString("sshE2ePort")?.toInt() ?: 22,
            username = requireNotNull(args.getString("sshE2eUsername")),
            authentication = SshAuthentication.PrivateKey("herdr-inline-fixture", { key.copyOf() }, null),
            tmuxSessionSelectorEnabled = true,
        ))
        val wheels = AtomicInteger()
        val counted = object : Connection by ssh {
            override fun trySend(bytes: ByteArray): Boolean {
                val sent = ssh.trySend(bytes)
                if (sent && Regex("\\u001B\\[<(64|65);").containsMatchIn(bytes.toString(Charsets.US_ASCII))) wheels.incrementAndGet()
                return sent
            }
            override fun send(bytes: ByteArray) { trySend(bytes) }
        }
        val terminal = DefaultSshSessionTerminalFactory.create()
        val controller = requireNotNull(terminal.controller)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var view: FastTerminalView
        val failure = AtomicReference<String?>()
        scenario.onActivity { activity ->
            terminal.attach(counted)
            view = FastTerminalView(activity)
            activity.setContentView(view)
            view.attachController(controller)
        }
        val job = launch(Dispatchers.IO) {
            ssh.connect(controller.terminalColumns, controller.terminalRows, { terminal.accept(it, {}) }, { state ->
                when (state) {
                    is ConnectionState.AwaitingApproval -> when (val prompt = state.prompt) {
                        is HostIdentityPrompt -> ssh.answerHostIdentityPrompt(prompt.promptToken, HostIdentityDecision.TrustOnce)
                        is TmuxSessionPrompt -> {
                            check(prompt.herdrSessions.any { it.name == session }) { "Owned Herdr session absent" }
                            ssh.answerTmuxSessionPrompt(prompt.promptToken, "herdr:$session")
                        }
                        else -> Unit
                    }
                    is ConnectionState.Failed -> failure.set(state.message)
                    else -> Unit
                }
            })
        }
        suspend fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
            withTimeout(timeout) {
                while (!condition()) {
                    check(failure.get() == null) { "Connection failed: ${failure.get()}" }
                    delay(25)
                }
            }
            android.util.Log.i("HerdrInlineE2e", label)
        }
        try {
            await("connected") { ssh.isHerdrSession && controller.lineCount() > 0 }
            // The raw transport fixture must deliver the final laid-out dimensions after connect.
            scenario.onActivity { ssh.resize(controller.terminalColumns, controller.terminalRows) }
            exerciseCodex(scenario, controller, view, command, wheels)
        } finally {
            scenario.onActivity { terminal.stopAndClear() }
            ssh.close()
            job.cancelAndJoin()
            scenario.close()
            key.fill(0)
            knownHosts.delete()
        }
    }

    @Test fun savedHostCodexHasOrderedHistoryAndNativeMotion(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val hostName = args.getString("herdrE2eSavedHost").orEmpty()
        val session = args.getString("herdrE2eSession").orEmpty()
        assumeTrue("Requires an explicitly selected saved host and owned Herdr fixture",
            hostName.isNotBlank() && session.startsWith("terminal-spike-inline-"))
        requireAuthorizedScrollDevice()
        val command = String(android.util.Base64.decode(requireNotNull(args.getString("sshE2eCodexCommandBase64")), 0))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TerminalSpikeApplication
        val repository = app.container.sshSessionRepository
        val beforeIds = repository.sessions.value.map { it.id }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedId: Long? = null
        try {
            lateinit var model: TerminalSpikeViewModel
            scenario.onActivity { model = ViewModelProvider(it)[TerminalSpikeViewModel::class.java] }
            await("saved host catalog ready") {
                model.uiState.value.settingsReady && model.connectionsUiState.value.loadState is ConnectionsLoadState.Ready
            }
            val hosts = (model.connectionsUiState.value.loadState as ConnectionsLoadState.Ready).hosts
            val host = hosts.single { it.displayName == hostName }
            android.util.Log.i("HerdrInlineE2e", "saved transport=${host.protocol}")
            if (host.protocol == com.yanjiyu.terminalspike.core.model.ConnectionProtocol.MOSH) {
                scenario.onActivity { model.refreshMoshExtension() }
                await("Mosh runtime ready") { model.uiState.value.moshExtension.kind == com.yanjiyu.terminalspike.ui.MoshExtensionUiKind.AVAILABLE }
            }
            scenario.onActivity { assertTrue("Saved-host connect rejected", model.connectConnectionsHost(HostConnectRequest(host.id))) }
            await("saved host session started") { repository.sessions.value.any { it.id !in beforeIds } }
            val id = repository.sessions.value.single { it.id !in beforeIds }.id
            startedId = id
            var selectedOwnedSession = false
            await("saved host connected", 60_000) {
                when (val state = repository.sessions.value.single { it.id == id }.connectionState) {
                    is ConnectionState.AwaitingApproval -> {
                        when (val prompt = state.prompt) {
                            is HostIdentityPrompt -> repository.answerHostIdentityPrompt(id, prompt.promptToken, HostIdentityDecision.TrustOnce)
                            is TmuxSessionPrompt -> {
                                check(prompt.herdrSessions.any { it.name == session }) { "Owned session missing on saved host" }
                                repository.answerTmuxSessionPrompt(id, prompt.promptToken, "herdr:$session")
                                selectedOwnedSession = true
                            }
                            else -> error("Saved host requires an unsupported prompt")
                        }
                        false
                    }
                    ConnectionState.Connected -> true
                    is ConnectionState.Failed -> error("Saved host connection failed: ${state.message}")
                    else -> false
                }
            }
            check(selectedOwnedSession) { "Refusing to type into a session not selected by this fixture" }
            val controller = requireNotNull(repository.controllerFor(id))
            scenario.onActivity { it.openTerminalSession(id) }
            var view: FastTerminalView? = null
            fun find(v: android.view.View): FastTerminalView? {
                if (v is FastTerminalView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            await("saved host native view attached") {
                scenario.onActivity { view = find(it.window.decorView) }
                view != null
            }
            val wheels = AtomicInteger()
            val sinkField = TerminalController::class.java.getDeclaredField("inputSink").apply { isAccessible = true }
            val original = sinkField.get(controller) as TerminalInputSink
            val counter = object : TerminalInputSink {
                private fun count(bytes: ByteArray, accepted: Boolean): Boolean {
                    if (accepted && Regex("\\u001B\\[<(64|65);").containsMatchIn(bytes.toString(Charsets.US_ASCII))) wheels.incrementAndGet()
                    return accepted
                }
                override fun send(bytes: ByteArray) { count(bytes, original.sendWithAcceptance(bytes)) }
                override fun trySend(bytes: ByteArray) = count(bytes, original.trySend(bytes))
                override fun sendWithAcceptance(bytes: ByteArray) = count(bytes, original.sendWithAcceptance(bytes))
            }
            scenario.onActivity { sinkField.set(controller, counter) }
            try { exerciseCodex(scenario, controller, requireNotNull(view), command, wheels) }
            finally { scenario.onActivity { sinkField.set(controller, original) } }
        } finally {
            startedId?.let(repository::close)
            scenario.close()
        }
    }

    @Test fun actualMoshCodexHasOrderedHistoryAndNativeMotion(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val session = args.getString("herdrE2eSession").orEmpty()
        assumeTrue("Requires an isolated real Mosh/Herdr fixture",
            args.getString("herdrE2eMosh") == "true" && session.startsWith("terminal-spike-inline-"))
        requireAuthorizedScrollDevice()
        val key = android.util.Base64.decode(requireNotNull(args.getString("sshE2ePrivateKeyBase64")), 0)
        val command = String(android.util.Base64.decode(requireNotNull(args.getString("sshE2eCodexCommandBase64")), 0))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TerminalSpikeApplication
        val repository = app.container.sshSessionRepository
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedId: Long? = null
        try {
            if (args.getString("herdrE2eLandscape") == "true") {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("landscape activity ready") {
                    var ready = false
                    scenario.onActivity { ready = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    ready
                }
            }
            val result = repository.startUserInitiatedSession(RemoteSessionStartRequest(
                title = "Owned Mosh scroll fixture",
                workspaceName = "Owned Mosh scroll fixture",
                connection = RemoteSessionConnectionRequest.Mosh(MoshBootstrapRequest(
                    ssh = SshConnectionConfig(
                        host = requireNotNull(args.getString("sshE2eHost")),
                        port = requireNotNull(args.getString("sshE2ePort")).toInt(),
                        username = requireNotNull(args.getString("sshE2eUsername")),
                        authentication = SshAuthentication.PrivateKey("herdr-mosh-fixture", { key.copyOf() }, null),
                        tmuxSessionSelectorEnabled = true,
                    ),
                    udpPort = requireNotNull(args.getString("herdrE2eMoshUdpPort")).toInt(),
                )),
            ))
            val id = (result as? StartSshSessionResult.Started)?.sessionId
                ?: error("Production Mosh session did not start: $result")
            startedId = id
            var selectedOwnedSession = false
            await("real Mosh connected", 60_000) {
                when (val state = repository.sessions.value.single { it.id == id }.connectionState) {
                    is ConnectionState.AwaitingApproval -> {
                        when (val prompt = state.prompt) {
                            is HostIdentityPrompt -> repository.answerHostIdentityPrompt(id, prompt.promptToken, HostIdentityDecision.TrustOnce)
                            is TmuxSessionPrompt -> {
                                check(prompt.herdrSessions.any { it.name == session }) { "Owned Mosh Herdr session missing" }
                                repository.answerTmuxSessionPrompt(id, prompt.promptToken, "herdr:$session")
                                selectedOwnedSession = true
                            }
                            else -> error("Mosh fixture requires an unsupported prompt")
                        }
                        false
                    }
                    ConnectionState.Connected -> true
                    is ConnectionState.Failed -> error("Real Mosh connection failed: ${state.message}")
                    else -> false
                }
            }
            check(selectedOwnedSession) { "Refusing to type into an unowned Mosh session" }
            val controller = requireNotNull(repository.controllerFor(id))
            scenario.onActivity { it.openTerminalSession(id) }
            var view: FastTerminalView? = null
            fun find(v: android.view.View): FastTerminalView? {
                if (v is FastTerminalView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            await("real Mosh native view attached") {
                scenario.onActivity { view = find(it.window.decorView) }
                view != null
            }
            val wheels = AtomicInteger()
            val sinkField = TerminalController::class.java.getDeclaredField("inputSink").apply { isAccessible = true }
            val original = sinkField.get(controller) as TerminalInputSink
            val counter = object : TerminalInputSink {
                private fun count(bytes: ByteArray, accepted: Boolean): Boolean {
                    if (accepted && Regex("\\u001B\\[<(64|65);").containsMatchIn(bytes.toString(Charsets.US_ASCII))) wheels.incrementAndGet()
                    return accepted
                }
                override fun send(bytes: ByteArray) { count(bytes, original.sendWithAcceptance(bytes)) }
                override fun trySend(bytes: ByteArray) = count(bytes, original.trySend(bytes))
                override fun sendWithAcceptance(bytes: ByteArray) = count(bytes, original.sendWithAcceptance(bytes))
            }
            scenario.onActivity { sinkField.set(controller, counter) }
            try {
                android.util.Log.i("HerdrInlineE2e", "transport=MOSH production repository; no SSH fallback")
                exerciseCodex(scenario, controller, requireNotNull(view), command, wheels)
            } finally { scenario.onActivity { sinkField.set(controller, original) } }
        } finally {
            startedId?.let(repository::close)
            if (args.getString("herdrE2eLandscape") == "true") {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
            scenario.close()
            key.fill(0)
        }
    }

    private fun requireAuthorizedScrollDevice() {
        val model = android.os.Build.MODEL.replace('_', '-')
        check(model == "SM-S911B" || model == "SM-G9708") {
            "Real scroll tests require the authorized S23 or S10e; the host runner must verify its serial"
        }
    }

    private suspend fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(25) }
        android.util.Log.i("HerdrInlineE2e", label)
    }

    private suspend fun exerciseCodex(
        scenario: ActivityScenario<MainActivity>, controller: TerminalController,
        view: FastTerminalView, command: String, wheels: AtomicInteger,
    ) {
        fun liveContains(marker: String) = (0 until controller.lineCount()).any {
            controller.lineAt(it)?.text?.contains(marker) == true
        }
        fun visibleContains(marker: String): Boolean {
            val rows = controller.viewport.visibleRows(0)
            return (rows.first until rows.lastExclusive).any { controller.lineAt(it)?.text?.contains(marker) == true }
        }
            delay(2_000)
            assertTrue(controller.sendPaste(command, appendEnter = true))
            await("actual Codex input ready", 60_000) { liveContains("Ask Codex") || liveContains("100% context left") }
            delay(1_000)
            assertTrue(controller.sendPaste("Do not use tools or inspect files. Print exactly 200 separate plain text lines. Each line must consist only of CODEX_SCROLL_ followed by its three-digit zero-padded row number, counting from 001 through 200. No heading, explanation, Markdown list or code fences."))
            delay(1_000)
            assertTrue(controller.sendWithAcceptance(byteArrayOf(13)))
            await("actual Codex row 200 visible", 120_000) { liveContains("CODEX_SCROLL_200") }
            // Wait for a settled visible response, not a history-cache readiness flag.
            var lastVisible = ""
            var stableSince = SystemClock.uptimeMillis()
            await("visible response settled", 15_000) {
                val visible = (0 until controller.lineCount()).joinToString("\n") { controller.lineAt(it)?.text.orEmpty() }
                if (visible != lastVisible) {
                    lastVisible = visible
                    stableSince = SystemClock.uptimeMillis()
                }
                SystemClock.uptimeMillis() - stableSince >= 1_000
            }
            assertTrue("Bottom must show row 200 before touch", visibleContains("CODEX_SCROLL_200"))
            assertFalse("Row 001 must not already be visible before touch", visibleContains("CODEX_SCROLL_001"))
            // Only output readiness gates the first gesture, never native-history readiness.
            val native = FastTerminalView::class.java.getDeclaredField("herdrScroll").apply { isAccessible = true }.get(view) as HerdrNativeScroll
            var downTime = SystemClock.uptimeMillis()
            fun event(action: Int, y: Float) {
                scenario.onActivity {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, view.width * .75f, y, 0)
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            scenario.onActivity {
                val history = controller.herdrHistory
                if (InstrumentationRegistry.getArguments().getString("herdrE2eTailTrace") == "true" &&
                    history?.identity?.startsWith("terminal-spike-inline-") == true) {
                    val projection = controller.herdrHistoryForDisplay()
                    val projected = projection ?: history
                    val field = TerminalController::class.java.getDeclaredField("terminalScreen").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val screen = field.get(controller) as List<com.yanjiyu.terminalspike.terminal.model.TerminalLine>
                    val data = org.json.JSONObject().put("x", projected.x).put("y", projected.y)
                        .put("columns", projected.columns).put("rows", projected.rows)
                        .put("sourceCount", history.lines.size).put("surface", history.surface.toString())
                        .put("projectionAccepted", projection != null)
                        .put("navigation", org.json.JSONArray((screen.take(3) + screen.takeLast(3)).map { it.text }))
                        .put("sourceTail", org.json.JSONArray(history.lines.takeLast(projected.rows * 2).map { it.text }))
                        .put("screen", org.json.JSONArray(screen.subList(projected.y, projected.y + projected.rows).map { it.text }))
                        .put("cropped", org.json.JSONArray(screen.subList(projected.y, projected.y + projected.rows).map {
                            com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry.sliceColumns(it, projected.x, projected.x + projected.columns)?.text
                        }))
                    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "herdr-owned-tail.json").writeText(data.toString())
                }
                android.util.Log.i("HerdrInlineE2e", "pre-touch visible=${controller.isHerdrNativeHistoryVisible()} follow=${controller.viewport.autoFollow} outer=${controller.terminalColumns}x${controller.terminalRows} pane=${history?.x},${history?.y},${history?.columns},${history?.rows} view=${view.width}x${view.height}")
            }
            val startY = view.height * .35f
            event(MotionEvent.ACTION_DOWN, startY)
            delay(25)
            event(MotionEvent.ACTION_MOVE, startY + 70f)
            var before = 0f
            scenario.onActivity {
                android.util.Log.i("HerdrInlineE2e", "first reader=${controller.herdrHistoryReading} cached=${controller.herdrHistory?.lines?.size ?: 0} wheels=${wheels.get()}")
                assertTrue("First gesture must own native history; wheels=${wheels.get()}", controller.herdrHistoryReading)
                before = native.reader.viewport.scrollY
            }
            delay(25)
            event(MotionEvent.ACTION_MOVE, startY + 73.25f)
            scenario.onActivity {
                assertEquals(before - 3.25f, native.reader.viewport.scrollY, .05f)
                val snapshot = requireNotNull(native.reader.snapshot)
                android.util.Log.i("HerdrInlineE2e", "route=native-herdr-reader rows=${snapshot.lines.size} viewport=${snapshot.rows} offset=${snapshot.offsetFromBottom}")
                val markers = snapshot.lines.flatMap { Regex("CODEX_SCROLL_\\d{3}").findAll(it.text).map { m -> m.value }.toList() }
                assertEquals((1..200).map { "CODEX_SCROLL_%03d".format(it) }, markers)
                val bottom = snapshot.lines.takeLast(snapshot.rows).joinToString("\n") { it.text }
                assertTrue("Bottom excludes row 200: outer=${controller.terminalColumns}x${controller.terminalRows} capture=${snapshot.columns}x${snapshot.rows} rows=${snapshot.lines.size} lastMarker=${snapshot.lines.indexOfLast { it.text.contains("CODEX_SCROLL_200") }}", bottom.contains("CODEX_SCROLL_200"))
                assertFalse(bottom.contains("CODEX_SCROLL_001"))
            }
            // Keep the swipe tail continuous even if the ordered-history assertion
            // above was slow. Long gaps make older VelocityTracker versions correctly
            // classify a stopped finger, which would not test a release fling.
            scenario.onActivity {
                for (offset in listOf(120f, 170f, 220f, 270f)) {
                    val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE, view.width * .75f, startY + offset, 0)
                    try { view.onTouchEvent(motion) } finally { motion.recycle() }
                    SystemClock.sleep(12)
                }
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    view.width * .75f, startY + 290f, 0)
                try { view.onTouchEvent(up) } finally { up.recycle() }
            }
            var released = 0f
            scenario.onActivity {
                released = native.reader.viewport.scrollY
                val physics = HerdrNativeScroll::class.java.getDeclaredField("scroller").apply { isAccessible = true }.get(native) as android.widget.OverScroller
                android.util.Log.i("HerdrInlineE2e", "release y=$released shown=${view.isShown} focus=${view.hasWindowFocus()} window=${view.windowVisibility} finished=${physics.isFinished} velocity=${physics.currVelocity} final=${physics.finalY}")
            }
            delay(100)
            scenario.onActivity {
                android.util.Log.i("HerdrInlineE2e", "after release y=${native.reader.viewport.scrollY} shown=${view.isShown} focus=${view.hasWindowFocus()} reader=${controller.herdrHistoryReading}")
                assertTrue("Fling must move after release", native.reader.viewport.scrollY < released)
            }
            downTime = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, startY)
            var caught = 0f
            scenario.onActivity { caught = native.reader.viewport.scrollY }
            delay(100)
            scenario.onActivity { assertEquals(caught, native.reader.viewport.scrollY, .01f) }
            event(MotionEvent.ACTION_MOVE, startY + 70f)
            event(MotionEvent.ACTION_UP, startY + 70f)
            fun oldestTrace(attempt: Int): String {
                val visibleMarkers = view.herdrVisibleRowsForTesting().flatMap {
                    Regex("CODEX_SCROLL_\\d{3}").findAll(it).map { match -> match.value }.toList()
                }
                return "oldest attempt=$attempt y=${native.reader.viewport.scrollY} max=${native.reader.viewport.maximumScrollY}" +
                    " reader=${controller.herdrHistoryReading} pinned=${controller.herdrHistoryPinned}" +
                    " source=${controller.isHerdrNativeHistoryVisible()} snapshot=${native.reader.snapshot?.lines?.size}" +
                    " visible=${visibleMarkers.firstOrNull()}..${visibleMarkers.lastOrNull()} wheels=${wheels.get()}"
            }
            var traversalAttempts = 0
            for (attempt in 0 until 100) {
                traversalAttempts = attempt + 1
                // Catch the preceding drag's release motion before checking the
                // target. Otherwise a fling can pass row 001 while the test keeps
                // polling it, then stop at earlier prompt/header rows instead.
                downTime = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, startY)
                var oldest = false
                scenario.onActivity {
                    oldest = view.herdrVisibleRowsForTesting().any { it.contains("CODEX_SCROLL_001") }
                    if (attempt % 10 == 0 || oldest) android.util.Log.i("HerdrInlineE2e", oldestTrace(attempt))
                }
                if (oldest) {
                    event(MotionEvent.ACTION_UP, startY)
                    break
                }
                delay(20)
                event(MotionEvent.ACTION_MOVE, startY + view.height * .4f)
                delay(100)
                event(MotionEvent.ACTION_UP, startY + view.height * .4f)
            }
            delay(100)
            scenario.onActivity {
                val trace = oldestTrace(traversalAttempts)
                android.util.Log.i("HerdrInlineE2e", trace)
                assertTrue(trace, view.herdrVisibleRowsForTesting().any { it.contains("CODEX_SCROLL_001") })
            }
            assertEquals("Native history must emit zero remote wheels", 0, wheels.get())
            android.util.Log.i("HerdrInlineE2e", "PASS markers=200 fractional=3.25 fling=true catch=true oldest=true wheels=0")
    }
}
