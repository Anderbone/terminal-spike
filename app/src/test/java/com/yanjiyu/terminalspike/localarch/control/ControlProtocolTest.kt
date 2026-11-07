package com.yanjiyu.terminalspike.localarch.control

import com.yanjiyu.terminalspike.core.model.*
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlProtocolTest {
    @Test fun framingRejectsIncompleteAndOversizedRequestsButAcceptsTheExactLimit() {
        assertEquals("é", readControlFrame(ByteArrayInputStream("é\nignored".toByteArray())))
        assertEquals(CONTROL_MAX_REQUEST_BYTES, readControlFrame(ByteArrayInputStream(("x".repeat(CONTROL_MAX_REQUEST_BYTES) + "\n").toByteArray())).length)
        assertThrows(ControlFailure::class.java) { readControlFrame(ByteArrayInputStream("no newline".toByteArray())) }
        val oversized = assertThrows(ControlFailure::class.java) {
            readControlFrame(ByteArrayInputStream(("x".repeat(CONTROL_MAX_REQUEST_BYTES + 1) + "\n").toByteArray()))
        }
        assertEquals("REQUEST_TOO_LARGE", oversized.code)
    }

    @Test fun requestSchemaRejectsExtraFieldsWrongTypesAndUnsupportedVersions() {
        assertEquals("status", parseControlRequest("""{"api":1,"method":"status"}""").first)
        listOf(
            "[]", "null", "not json", """{"api":"1","method":"status"}""",
            """{"api":2,"method":"status"}""", """{"api":1,"method":"status","token":"secret"}""",
            """{"api":1,"method":"status","params":[]}""",
        ).forEach { frame -> assertThrows(IllegalArgumentException::class.java) { parseControlRequest(frame) } }
    }

    @Test fun keyboardPatchPreservesUntouchedFieldsAndRequiresFreshRevision() {
        val current = keyboard()
        val patched = patchControlKeyboard(current, json("""{"id":"${current.id}","revision":20,"hapticFeedbackEnabled":false}"""))
        assertFalse(patched.hapticFeedbackEnabled)
        assertEquals(current.orderedActions, patched.orderedActions)
        assertEquals(current.layout, patched.layout)
        assertEquals(current.inputMode, patched.inputMode)
        assertEquals(current.createdAtEpochMillis, patched.createdAtEpochMillis)
        assertTrue(patched.updatedAtEpochMillis > current.updatedAtEpochMillis)
        val conflict = assertThrows(ControlFailure::class.java) {
            patchControlKeyboard(current, json("""{"revision":19,"actions":["escape"]}"""))
        }
        assertEquals("CONFLICT", conflict.code)
    }

    @Test fun keyboardPatchRejectsDuplicateUnknownEmptyKeysAndCoercedBooleans() {
        listOf(
            """{"revision":20,"actions":["escape","escape"]}""",
            """{"revision":20,"actions":["unknown"]}""", """{"revision":20,"actions":[]}""",
            """{"revision":20,"actions":[2]}""", """{"revision":20,"actions":"escape"}""",
            """{"revision":20,"hapticFeedbackEnabled":"false"}""", """{"revision":20,"layout":"unknown"}""",
            """{"revision":20,"tmuxPrefix":"invalid-prefix"}""", """{"revision":20}""",
        ).forEach { fields -> assertThrows(IllegalArgumentException::class.java) { patchControlKeyboard(keyboard(), json(fields)) } }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun keyboard() = KeyboardProfile(
        id = "00000000-0000-0000-0000-000000000001", name = "Fixture",
        orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.TAB, KeyboardAction.CONTROL),
        layout = KeyboardLayout.TWO_ROWS, modifierBehavior = ModifierBehavior.ONE_SHOT,
        hapticFeedbackEnabled = true, keyRepeatEnabled = true,
        createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
    )
}
