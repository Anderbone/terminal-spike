package com.yanjiyu.terminalspike.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureAssetContractTest {
    @Test
    fun benchmarkApkContainsOnlyTheProjectAuthoredScenarioCatalog() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val scenarioFiles = requireNotNull(assets.list(""))
            .filter { name -> name == SCENARIO_ASSET }

        assertEquals(listOf(SCENARIO_ASSET), scenarioFiles)
        val text = assets.open(SCENARIO_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(text.contains("plain-100k\tPLAIN\t100000"))
        assertTrue(text.contains("ansi-heavy\tANSI_HEAVY"))
        assertTrue(text.contains("cursor-redraw\tCURSOR_REDRAW"))
        assertTrue(text.contains("full-screen-redraw\tFULL_SCREEN_REDRAW"))
        assertTrue(text.contains("cjk-wide\tCJK_WIDE"))
        assertTrue(text.contains("tmux-status\tTMUX_STATUS"))
    }

    companion object {
        private const val SCENARIO_ASSET = "scenarios.tsv"
    }
}
