package com.yanjiyu.terminalspike.terminal.view

import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TerminalImeCompositionInstrumentedTest {
    @Test
    fun controlChordDiscardsPrivateCompositionAndSuppressesItsLateCommit() = onMain { connection, sent ->
        connection.setComposingText("日本", 1)
        assertTrue(sent.size() == 0)

        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
        connection.commitText("日本", 1)
        connection.finishComposingText()
        assertArrayEquals(byteArrayOf(3), sent.toByteArray())

        connection.setComposingText("新", 1)
        connection.commitText("新", 1)
        assertArrayEquals(byteArrayOf(3) + "新".encodeToByteArray(), sent.toByteArray())
    }

    @Test
    fun modifiedNavigationDoesNotReplayTheCancelledComposition() = onMain { connection, sent ->
        connection.setComposingText("日本", 1)
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON))
        connection.commitText("日本", 1)
        connection.finishComposingText()

        assertArrayEquals("\u001B[1;3D".encodeToByteArray(), sent.toByteArray())
    }

    @Test
    fun modifierKeyUpLeavesPrivateCompositionForNormalFinalization() = onMain { connection, sent ->
        connection.setComposingText("日本", 1)
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
        assertTrue(sent.size() == 0)

        connection.finishComposingText()

        assertArrayEquals("日本".encodeToByteArray(), sent.toByteArray())
    }

    private fun key(action: Int, code: Int, modifiers: Int) = KeyEvent(0, 0, action, code, 0, modifiers)

    private fun onMain(block: (TerminalInputConnection, ByteArrayOutputStream) -> Unit) {
        var outcome: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            outcome = runCatching {
                val sent = ByteArrayOutputStream()
                val connection = TerminalInputConnection(
                    View(ApplicationProvider.getApplicationContext()),
                    object : TerminalInputSink {
                        override fun send(bytes: ByteArray) = sent.write(bytes)
                    },
                )
                block(connection, sent)
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
}
