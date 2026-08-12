/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the source/build plumbing that keeps upstream Mosh prediction in the Android frontend. */
class MoshNativePredictionIntegrationTest {
    @Test
    fun nativeBuildIncludesUpstreamPredictionEngine() {
        val cmake = sourceFile("src/main/cpp/CMakeLists.txt").readText()
        val lines = cmake.lineSequence().map(String::trim).toSet()

        assertTrue("\${MOSH_SOURCE_DIR}/src/frontend/terminaloverlay.cc" in lines)
        assertTrue("\${MOSH_SOURCE_DIR}/src/frontend" in lines)
    }

    @Test
    fun userBytesUpdatePredictionBeforeEnteringTheTransport() {
        val source = sourceFile("src/main/cpp/mosh_native.cpp").readText()
        val predictInput = source.indexOf("predictions.new_user_byte(")
        val transportInput = source.indexOf(
            "transport->get_current_state().push_back( Parser::UserByte(",
            startIndex = predictInput,
        )

        assertTrue(source.contains("#include \"terminaloverlay.h\""))
        assertTrue(source.contains("Overlay::PredictionEngine predictions;"))
        assertTrue(
            source.contains(
                "predictions.set_display_preference( Overlay::PredictionEngine::Always );",
            ),
        )
        assertTrue(predictInput >= 0)
        assertTrue(transportInput > predictInput)
        assertTrue(source.contains("predictions.set_local_frame_sent("))
        assertTrue(source.contains("if ( paste ) predictions.reset();"))
    }

    @Test
    fun predictedFramebufferIsRenderedAndReconciledWithServerAcks() {
        val source = sourceFile("src/main/cpp/mosh_native.cpp").readText()
        val applyPrediction = source.indexOf("predictions.apply( frame );")
        val renderFrame = source.indexOf(
            "display.new_frame( display_initialized, local_framebuffer, frame )",
            startIndex = applyPrediction,
        )

        assertTrue(applyPrediction >= 0)
        assertTrue(renderFrame > applyPrediction)
        assertTrue(source.contains("predictions.set_local_frame_acked("))
        assertTrue(source.contains("predictions.set_local_frame_late_acked("))
        assertTrue(source.contains("transport->wait_time(), predictions.wait_time()"))
    }

    private fun sourceFile(relativePath: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    File(directory, "mosh-extension/$relativePath"),
                    File(directory, relativePath),
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Could not find Mosh extension source file: $relativePath")
    }
}
