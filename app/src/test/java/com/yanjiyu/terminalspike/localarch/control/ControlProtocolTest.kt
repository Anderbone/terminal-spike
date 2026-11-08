package com.yanjiyu.terminalspike.localarch.control

import com.yanjiyu.terminalspike.core.model.*
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlProtocolTest {
    @Test fun inputPreferencesShareRevisionAndRejectInvalidTypes() {
        val current = com.yanjiyu.terminalspike.core.data.settings.AppSettings.newBuilder()
            .setVoiceInputDisabled(true).build()
        val revision = appearanceRevision(current)
        val patched = patchControlAppAppearance(current, json(
            """{"revision":$revision,"inputButtonsOnRight":true,"voiceInputEnabled":true}""",
        ))
        assertTrue(patched.inputButtonsOnRight)
        assertFalse(patched.voiceInputDisabled)
        assertNotEquals(revision, appearanceRevision(patched))
        assertThrows(ControlFailure::class.java) {
            patchControlAppAppearance(patched, json("""{"revision":$revision,"inputButtonsOnRight":false}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            patchControlAppAppearance(current, json("""{"revision":$revision,"inputButtonsOnRight":"true"}"""))
        }
    }

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

    @Test fun connectionPatchPreservesAuthenticationForwardingAndTransportSettings() {
        val current = HostProfile(
            id = "00000000-0000-0000-0000-000000000001", displayName = "Server",
            hostname = "old.example", port = 22, username = "user", protocol = ConnectionProtocol.MOSH,
            credentialId = "00000000-0000-0000-0000-000000000002", startupCommand = "keep command",
            moshPort = 60001, moshLocale = "C.UTF-8",
            portForwards = listOf(PortForwardRule(listenPort = 8080, destinationPort = 80)),
            createdAtEpochMillis = 10, updatedAtEpochMillis = 20,
        )
        val changed = patchControlConnection(current, json("""{"revision":20,"hostname":"new.example","port":2222,"group":"work"}"""))
        assertEquals(current.copy(hostname = "new.example", port = 2222, group = "work",
            updatedAtEpochMillis = changed.updatedAtEpochMillis), changed)
        val ssh = patchControlConnection(current, json("""{"revision":20,"protocol":"ssh"}"""))
        assertNull(ssh.moshPort)
        assertNull(ssh.moshLocale)
        assertEquals(current.portForwards, ssh.portForwards)
        assertEquals(current.credentialId, ssh.credentialId)
        assertEquals("CONFLICT", assertThrows(ControlFailure::class.java) {
            patchControlConnection(current, json("""{"revision":19,"name":"Stale"}"""))
        }.code)
    }

    @Test fun connectionCreationValidatesRequiredFieldsTypesAndRejectsSecrets() {
        val created = patchControlConnection(null, json("""{"name":"中文 server","hostname":"example.com","username":"me"}"""))
        assertEquals(ConnectionProtocol.SSH, created.protocol)
        assertEquals(22, created.port)
        listOf(
            """{"name":"Server","hostname":"example.com"}""",
            """{"name":"Server","hostname":"example.com","username":"me","port":0}""",
            """{"name":"Server","hostname":"example.com","username":"me","port":"22"}""",
            """{"name":"Server","hostname":"example.com","username":"me","protocol":"telnet"}""",
            """{"name":"Server","hostname":"example.com","username":"me","password":"secret"}""",
            """{"name":"Server","hostname":"example.com","username":"me","startupCommand":"execute"}""",
        ).forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) { patchControlConnection(null, json(fields)) }
        }
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
