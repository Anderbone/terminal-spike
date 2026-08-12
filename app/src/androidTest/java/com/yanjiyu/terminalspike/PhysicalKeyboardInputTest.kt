package com.yanjiyu.terminalspike

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import com.yanjiyu.terminalspike.terminal.view.FastTerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real View KeyEvent boundary used by attached USB/Bluetooth keyboards. */
@RunWith(AndroidJUnit4::class)
class PhysicalKeyboardInputTest {
    @Test
    fun hardwareKeyDownsEmitExactTerminalBytesAndKeyUpsNeverDuplicateInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val accepted = mutableListOf<ByteArray>()

        instrumentation.runOnMainSync {
            val controller = TerminalController().apply {
                setInputSink(
                    sink = object : TerminalInputSink {
                        override fun send(bytes: ByteArray) {
                            accepted += bytes.copyOf()
                        }

                        override fun trySend(bytes: ByteArray): Boolean {
                            accepted += bytes.copyOf()
                            return true
                        }
                    },
                    onResize = { _, _ -> },
                )
            }
            val view = FastTerminalView(instrumentation.targetContext).apply {
                attachController(controller)
                setDirectInputEnabled(true)
            }

            assertTrue(view.dispatchKeyEvent(keyDown(KeyEvent.KEYCODE_A)))
            view.dispatchKeyEvent(keyUp(KeyEvent.KEYCODE_A))
            assertTrue(
                view.dispatchKeyEvent(
                    keyDown(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
                ),
            )
            view.dispatchKeyEvent(
                keyUp(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            )
            assertTrue(
                view.dispatchKeyEvent(
                    keyDown(KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
                ),
            )
            view.dispatchKeyEvent(
                keyUp(KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            )
            assertTrue(
                view.dispatchKeyEvent(
                    keyDown(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
                ),
            )
            view.dispatchKeyEvent(
                keyUp(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
            )
            assertTrue(
                view.dispatchKeyEvent(
                    keyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
                ),
            )
            view.dispatchKeyEvent(
                keyUp(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            )
            assertTrue(view.dispatchKeyEvent(keyDown(KeyEvent.KEYCODE_F5)))
            view.dispatchKeyEvent(keyUp(KeyEvent.KEYCODE_F5))
        }

        assertArrayEquals(
            byteArrayOf('a'.code.toByte()) +
                byteArrayOf(0x03) +
                byteArrayOf(0x1b, 0x0d) +
                byteArrayOf(0x1b, 0x5b, 0x5a) +
                "\u001B[1;5A".encodeToByteArray() +
                "\u001B[15~".encodeToByteArray(),
            accepted.joined(),
        )
    }

    private fun keyDown(keyCode: Int, metaState: Int = 0): KeyEvent = keyEvent(
        action = KeyEvent.ACTION_DOWN,
        keyCode = keyCode,
        metaState = metaState,
    )

    private fun keyUp(keyCode: Int, metaState: Int = 0): KeyEvent = keyEvent(
        action = KeyEvent.ACTION_UP,
        keyCode = keyCode,
        metaState = metaState,
    )

    private fun keyEvent(action: Int, keyCode: Int, metaState: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD,
        )
    }

    private fun List<ByteArray>.joined(): ByteArray = ByteArrayOutputStream().use { output ->
        forEach { bytes -> output.write(bytes) }
        output.toByteArray()
    }
}
