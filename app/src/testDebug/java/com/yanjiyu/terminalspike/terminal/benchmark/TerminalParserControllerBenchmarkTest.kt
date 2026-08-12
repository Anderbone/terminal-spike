package com.yanjiyu.terminalspike.terminal.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalParserControllerBenchmarkTest {
    private val scenarios = TerminalBenchmarkFixtureCatalog.load()

    @Test
    fun catalogCoversEveryRequiredLargeWorkloadWithoutProductionAssets() {
        assertEquals(TerminalFixtureKind.entries.toSet(), scenarios.map { it.kind }.toSet())
        assertTrue(scenarios.single { it.kind == TerminalFixtureKind.PLAIN }.records >= 100_000)

        scenarios.forEach { spec ->
            assertTrue("${spec.id} must declare at least two MiB", spec.minimumTotalBytes >= TWO_MIB)
            assertTrue(
                "${spec.id} generated fewer bytes than declared",
                TerminalBenchmarkFixtureGenerator.byteCount(spec) >= spec.minimumTotalBytes,
            )
        }
    }

    @Test
    fun fixtureBytesAreIndependentOfTransportChunkBoundaries() {
        scenarios.forEach { spec ->
            assertEquals(
                "Raw fixture changed when ${spec.id} was split at odd boundaries",
                TerminalBenchmarkFixtureGenerator.digest(spec),
                TerminalBenchmarkFixtureGenerator.digest(spec, chunkBytes = PRIME_CHUNK_BYTES),
            )
        }
    }

    @Test
    fun parserAndControllerReplayEveryLargeScenarioThroughBoundedFrames() {
        scenarios.forEach { spec ->
            val result = TerminalParserControllerBenchmarkHarness.replay(spec)

            assertTrue(result.inputBytes >= spec.minimumTotalBytes)
            assertEquals(TerminalBenchmarkFixtureGenerator.digest(spec), result.inputSha256)
            assertTrue(result.inputChunks > 1)
            assertTrue(result.parserDirtyRows > 0)
            assertTrue(result.controllerFrames > 0)
            assertTrue(result.controllerLineCount in 1..100_040)
            assertEquals(64, result.transcriptSha256.length)
            assertFalse(result.alternateScreen)
        }
    }

    @Test
    fun parserAndControllerEndStateIsStableAcrossUtf8AndEscapeSplits() {
        val spec = scenarios.single { it.kind == TerminalFixtureKind.CJK_WIDE }
        val aligned = TerminalParserControllerBenchmarkHarness.replay(spec)
        val split = TerminalParserControllerBenchmarkHarness.replay(
            spec = spec,
            chunkBytes = PRIME_CHUNK_BYTES,
        )

        assertEquals(aligned.inputBytes, split.inputBytes)
        assertEquals(aligned.inputSha256, split.inputSha256)
        assertEquals(aligned.completedScrollbackLines, split.completedScrollbackLines)
        assertEquals(aligned.controllerLineCount, split.controllerLineCount)
        assertEquals(aligned.transcriptSha256, split.transcriptSha256)
        assertEquals(aligned.cursorRow, split.cursorRow)
        assertEquals(aligned.cursorColumn, split.cursorColumn)
    }

    companion object {
        private const val TWO_MIB = 2L * 1024L * 1024L
        private const val PRIME_CHUNK_BYTES = 4_093
    }
}
