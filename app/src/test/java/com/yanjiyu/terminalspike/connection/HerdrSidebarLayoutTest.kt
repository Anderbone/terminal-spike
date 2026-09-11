package com.yanjiyu.terminalspike.connection

import org.junit.Assert.*
import org.junit.Test

class HerdrSidebarLayoutTest {
    @Test fun readsOnlyTheSelectedSessionAndUsesOuterAreaInsteadOfTheFocusedSplit() {
        val commands = mutableListOf<String>()
        val layout = captureHerdrSidebarLayout({ command ->
            commands += command
            response(26, 94)
        }, HerdrStartupChoice("/usr/bin/herdr", "work ' quoted"))
        assertEquals(HerdrSidebarLayout(26, 120), layout)
        assertEquals(listOf("'/usr/bin/herdr' '--session' 'work '\\'' quoted' 'pane' 'layout'"), commands)
    }

    @Test fun mobileOrRemotelyHiddenSidebarNeedsNoLocalCropping() {
        assertEquals(HerdrSidebarLayout(0, 60), captureHerdrSidebarLayout(
            { response(0, 60) }, HerdrStartupChoice("/usr/bin/herdr", "default"),
        ))
    }

    @Test fun invalidUnavailableAndOversizedResponsesFallBackToTheFullTerminal() {
        val choice = HerdrStartupChoice("/usr/bin/herdr", "default")
        for (output in listOf(response(-1, 100), response(26, 0), response(201, 30),
            response(26, 500), TmuxExecOutput("{}".toByteArray(), 0),
            TmuxExecOutput(ByteArray(65 * 1024), 0), response(26, 94).copy(exitStatus = 1))) {
            assertNull(captureHerdrSidebarLayout({ output }, choice))
        }
        assertNull(captureHerdrSidebarLayout({ error("Disconnected") }, choice))
    }

    private fun response(x: Int, width: Int) = TmuxExecOutput(
        """{"result":{"layout":{"area":{"x":$x,"y":1,"width":$width,"height":29},"focused_pane_id":"w1:p2","panes":[{"pane_id":"w1:p2","rect":{"x":80,"y":1,"width":40,"height":29}}]}}}""".toByteArray(), 0,
    )
}
