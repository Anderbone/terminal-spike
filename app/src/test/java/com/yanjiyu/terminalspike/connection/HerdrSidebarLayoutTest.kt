package com.yanjiyu.terminalspike.connection

import org.junit.Assert.*
import org.junit.Test

class HerdrSidebarLayoutTest {
    @Test fun mobileHistoryRequiresTheLiveSwitchButtonInsteadOfTheCoveredPaneGeometry() {
        val mobile = HerdrSidebarLayout(0, 60, 2, 28)
        assertTrue(mobile.allowsNativeHistory(60, "项目 🤖 tab 1                       │ switch  "))
        assertTrue(mobile.allowsNativeHistory(60, "tab 1                            │ switch"))
        for (header in listOf(null, "", "tab switch", "│    ×    ", "│  close  ")) {
            assertFalse(mobile.allowsNativeHistory(60, header))
        }
        assertFalse(mobile.allowsNativeHistory(80, "│ switch  "))
        assertTrue(HerdrSidebarLayout(26, 120, 1, 29).allowsNativeHistory(120, null))
        assertTrue(HerdrSidebarLayout(0, 120, 0, 30).allowsNativeHistory(120, null))
    }

    @Test fun readsOnlyTheSelectedSessionAndUsesOuterAreaInsteadOfTheFocusedSplit() {
        val commands = mutableListOf<String>()
        val layout = captureHerdrSidebarLayout({ command ->
            commands += command
            response(26, 94)
        }, HerdrStartupChoice("/usr/bin/herdr", "work ' quoted"))
        assertEquals(HerdrSidebarLayout(26, 120, 1, 29), layout)
        assertEquals(listOf("'/usr/bin/herdr' '--session' 'work '\\'' quoted' 'pane' 'layout'"), commands)
    }

    @Test fun mobileOrRemotelyHiddenSidebarNeedsNoLocalCropping() {
        assertEquals(HerdrSidebarLayout(0, 60, 1, 29), captureHerdrSidebarLayout(
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

    @Test fun contextMenusExcludePaneOutputAndRejectResizedOrMobileGeometry() {
        val topTabs = HerdrSidebarLayout(26, 120, 1, 29)
        assertTrue(topTabs.isContextMenuCell(4, 8, 120, 30))
        assertTrue(topTabs.isContextMenuCell(40, 0, 120, 30))
        assertFalse(topTabs.isContextMenuCell(26, 1, 120, 30))
        assertFalse(topTabs.isContextMenuCell(119, 29, 120, 30))
        assertFalse(topTabs.isContextMenuCell(4, 8, 100, 30))
        assertFalse(topTabs.isContextMenuCell(4, 8, 120, 40))
        assertFalse(topTabs.isContextMenuCell(-1, 0, 120, 30))
        assertFalse(topTabs.isContextMenuCell(120, 0, 120, 30))
        val bottomTabs = HerdrSidebarLayout(26, 120, 0, 29)
        assertTrue(bottomTabs.isContextMenuCell(40, 29, 120, 30))
        assertFalse(bottomTabs.isContextMenuCell(40, 0, 120, 30))
        val hiddenTabs = HerdrSidebarLayout(0, 120, 0, 30)
        assertFalse(hiddenTabs.isContextMenuCell(40, 0, 120, 30))
        val mobile = HerdrSidebarLayout(0, 60, 2, 28)
        assertFalse(mobile.isContextMenuCell(4, 0, 60, 30))
        assertFalse(mobile.isContextMenuCell(4, 8, 60, 30))
        assertFalse(HerdrSidebarLayout(26, 120).isContextMenuCell(4, 8, 120, 30))
    }

    private fun response(x: Int, width: Int) = TmuxExecOutput(
        """{"result":{"layout":{"area":{"x":$x,"y":1,"width":$width,"height":29},"focused_pane_id":"w1:p2","panes":[{"pane_id":"w1:p2","rect":{"x":80,"y":1,"width":40,"height":29}}]}}}""".toByteArray(), 0,
    )
}
