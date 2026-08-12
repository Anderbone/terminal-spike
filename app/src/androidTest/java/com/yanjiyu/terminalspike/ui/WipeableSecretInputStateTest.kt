package com.yanjiyu.terminalspike.ui

import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WipeableSecretInputStateTest {
    @Test
    fun countEqualityTransferAndDisposeNeverRequireAnImmutableSecret() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val firstEditor = EditText(context)
            val secondEditor = EditText(context)
            val first = WipeableSecretInputState()
            val second = WipeableSecretInputState()
            first.bind(firstEditor) {}
            second.bind(secondEditor) {}

            firstEditor.setText("password")
            secondEditor.setText("password")
            first.onTextChanged(firstEditor.text)
            second.onTextChanged(secondEditor.text)

            assertEquals(8, first.characterCount)
            assertTrue(first.hasValue)
            assertTrue(first.contentEquals(second))

            secondEditor.setText("passw0rd")
            second.onTextChanged(secondEditor.text)
            assertEquals(8, second.characterCount)
            assertFalse(first.contentEquals(second))

            val transferred = first.takeChars()
            assertArrayEquals("password".toCharArray(), transferred)
            assertEquals(0, firstEditor.text.length)
            assertEquals(0, first.characterCount)
            assertFalse(first.hasValue)
            transferred.fill('\u0000')

            second.dispose()
            assertEquals(0, secondEditor.text.length)
            assertEquals(0, second.characterCount)
            assertFalse(second.hasValue)
        }
    }
}
