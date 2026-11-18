package com.yanjiyu.terminalspike.terminal.view

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAccessoryActionTest {
    @Test
    fun modifierPresentationHasTruthfulOffArmedAndLockedStates() {
        assertFalse(AccessoryModifierState.OFF.isActive)
        assertTrue(AccessoryModifierState.ARMED.isActive)
        assertTrue(AccessoryModifierState.LOCKED.isActive)
        assertEquals(
            AccessoryModifierState.LOCKED,
            AccessoryModifierSnapshot(shift = AccessoryModifierState.LOCKED)
                .stateOf(TerminalAccessoryModifier.SHIFT),
        )
    }

    @Test
    fun oneShotClearsOnlyAfterAcceptedByteDispatch() {
        val state = AccessoryModifierSnapshot(
            control = AccessoryModifierState.ARMED,
            alt = AccessoryModifierState.LOCKED,
            shift = AccessoryModifierState.ARMED,
        )
        val bytes = TerminalAccessoryDispatch.Bytes(byteArrayOf('x'.code.toByte()))

        assertEquals(state, state.afterDispatch(bytes, accepted = false))
        assertEquals(
            AccessoryModifierSnapshot(
                control = AccessoryModifierState.OFF,
                alt = AccessoryModifierState.LOCKED,
                shift = AccessoryModifierState.OFF,
            ),
            state.afterDispatch(bytes, accepted = true),
        )
        assertEquals(
            state,
            state.afterDispatch(
                TerminalAccessoryDispatch.Local(TerminalLocalAccessoryAction.PASTE),
                accepted = true,
            ),
        )
    }

    @Test
    fun shiftLeftShortcutSendsTheQueuedQuestionsChordOnEveryPress() {
        val action = TerminalExtraKey.SHIFT_LEFT.toAccessoryAction()
        val expected = "\u001b[1;2D".toByteArray(Charsets.US_ASCII)
        val first = action.resolvedBytes(AccessoryModifierSnapshot())
        assertArrayEquals(expected, first)
        first.fill(0)
        assertArrayEquals(expected, action.resolvedBytes(AccessoryModifierSnapshot()))
    }

    @Test
    fun shiftNavigationUsesExactXtermSequences() {
        val shift = AccessoryModifierSnapshot(shift = AccessoryModifierState.ARMED)
        val expected = mapOf(
            TerminalExtraKey.TAB to "\u001B[Z",
            TerminalExtraKey.UP to "\u001B[1;2A",
            TerminalExtraKey.DOWN to "\u001B[1;2B",
            TerminalExtraKey.RIGHT to "\u001B[1;2C",
            TerminalExtraKey.LEFT to "\u001B[1;2D",
            TerminalExtraKey.HOME to "\u001B[1;2H",
            TerminalExtraKey.END to "\u001B[1;2F",
            TerminalExtraKey.INSERT to "\u001B[2;2~",
            TerminalExtraKey.DELETE to "\u001B[3;2~",
            TerminalExtraKey.PAGE_UP to "\u001B[5;2~",
            TerminalExtraKey.PAGE_DOWN to "\u001B[6;2~",
        )

        expected.forEach { (key, sequence) ->
            assertArrayEquals(
                sequence.toByteArray(Charsets.US_ASCII),
                key.toAccessoryAction().resolvedBytes(shift),
            )
        }
    }

    @Test
    fun usAsciiShiftPairsCoverLettersDigitsAndPunctuation() {
        assertEquals('A', shiftUsAscii('a'))
        assertEquals('!', shiftUsAscii('1'))
        assertEquals('?', shiftUsAscii('/'))
        assertEquals('|', shiftUsAscii('\\'))
        assertEquals('"', shiftUsAscii('\''))

        val shift = AccessoryModifierSnapshot(shift = AccessoryModifierState.ARMED)
        assertArrayEquals(byteArrayOf('?'.code.toByte()), TerminalExtraKey.SLASH.toAccessoryAction().resolvedBytes(shift))
        assertArrayEquals(byteArrayOf('_'.code.toByte()), TerminalExtraKey.DASH.toAccessoryAction().resolvedBytes(shift))
    }

    @Test
    fun directPrecomposedControlChordIgnoresShift() {
        val shift = AccessoryModifierSnapshot(shift = AccessoryModifierState.LOCKED)

        assertArrayEquals(byteArrayOf(0x03), TerminalExtraKey.CTRL_C.toAccessoryAction().resolvedBytes(shift))
    }

    @Test
    fun repeatedControlChordsResolveToFreshBytesAfterDispatchWipesTheFirstValue() {
        val expected = mapOf(
            TerminalExtraKey.CTRL_C to byteArrayOf(0x03),
            TerminalExtraKey.CTRL_W to byteArrayOf(0x17),
        )

        expected.forEach { (key, bytes) ->
            key.toAccessoryAction().resolvedBytes().fill(0)

            assertArrayEquals(bytes, key.toAccessoryAction().resolvedBytes())
        }
    }

    @Test
    fun shiftedTabDispatchOwnsBytesAfterEveryPress() {
        for (state in listOf(AccessoryModifierState.ARMED, AccessoryModifierState.LOCKED)) {
            val shift = AccessoryModifierSnapshot(shift = state)
            assertFreshAfterWipe("\u001b[Z".encodeToByteArray()) {
                TerminalExtraKey.TAB.toAccessoryAction().resolvedBytes(shift)
            }
        }
    }

    @Test
    fun configuredControlTabPrefixOwnsBytesAfterEveryPress() {
        assertFreshAfterWipe(byteArrayOf(0x09)) {
            TerminalAccessoryAction.TmuxPrefix("C-Tab").resolvedBytes()
        }
    }

    @Test
    fun configuredControlEnterPrefixOwnsBytesAfterEveryPress() {
        assertFreshAfterWipe(byteArrayOf(0x0d)) {
            TerminalAccessoryAction.TmuxPrefix("C-Enter").resolvedBytes()
        }
    }

    @Test
    fun configuredControlEscapePrefixOwnsBytesAfterEveryPress() {
        assertFreshAfterWipe(byteArrayOf(0x1b)) {
            TerminalAccessoryAction.TmuxPrefix("C-Escape").resolvedBytes()
        }
    }

    @Test
    fun configuredTextControlAndMetaPrefixesKeepIndependentExactBytes() {
        val chords = mapOf(
            "x" to byteArrayOf(0x78),
            "C-b" to byteArrayOf(0x02),
            "C-Space" to byteArrayOf(0x00),
            "M-Tab" to byteArrayOf(0x1b, 0x09),
            "M-Enter" to byteArrayOf(0x1b, 0x0d),
            "M-Escape" to byteArrayOf(0x1b, 0x1b),
        )
        for ((chord, expected) in chords) {
            assertFreshAfterWipe(expected) {
                TerminalAccessoryAction.TmuxPrefix(chord).resolvedBytes()
            }
        }
    }

    @Test
    fun everySupportedDeckModifierCombinationOwnsItsOutgoingBytes() {
        for (key in TerminalExtraKey.entries.filter { it.bytes != null && !it.isModifier && !it.isLocalAction }) {
            for (mask in 0..7) {
                fun state(bit: Int) = if (mask and bit != 0) {
                    AccessoryModifierState.ARMED
                } else {
                    AccessoryModifierState.OFF
                }
                val modifiers = AccessoryModifierSnapshot(state(1), state(2), state(4))
                val action = key.toAccessoryAction()
                val dispatch = action.resolve(modifiers) as? TerminalAccessoryDispatch.Bytes ?: continue
                assertFreshAfterWipe(dispatch.value.copyOf()) { action.resolvedBytes(modifiers) }
            }
        }
    }

    @Test
    fun configuredTmuxChordEncodesWithoutBorrowingLiveModifiers() {
        assertArrayEquals(
            byteArrayOf(0x02),
            TerminalAccessoryAction.TmuxPrefix("C-b").resolvedBytes(
                AccessoryModifierSnapshot(shift = AccessoryModifierState.LOCKED),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x1b, 0x20),
            TerminalAccessoryAction.TmuxPrefix("M-Space").resolvedBytes(),
        )
    }

    @Test
    fun localActionsAndModifiersCannotResolveAsNetworkBytes() {
        val local = TerminalAccessoryAction.Local(TerminalLocalAccessoryAction.PASTE).resolve()
        val modifier = TerminalAccessoryAction.Modifier(TerminalAccessoryModifier.SHIFT).resolve()

        assertTrue(local is TerminalAccessoryDispatch.Local)
        assertTrue(modifier is TerminalAccessoryDispatch.ToggleModifier)
    }

    @Test
    fun unsupportedShiftCombinationIsExplicit() {
        val dispatch = TerminalExtraKey.ESC.toAccessoryAction().resolve(
            AccessoryModifierSnapshot(shift = AccessoryModifierState.ARMED),
        )

        assertTrue(dispatch is TerminalAccessoryDispatch.Unsupported)
    }

    private fun assertFreshAfterWipe(expected: ByteArray, resolve: () -> ByteArray) {
        val first = resolve()
        assertArrayEquals(expected, first)
        val original = first.copyOf()
        try {
            first.fill(0)
            assertArrayEquals(expected, resolve())
        } finally {
            // Restore a borrowed baseline array even when the old implementation fails this probe.
            original.copyInto(first)
        }
    }

    private fun TerminalAccessoryAction.resolvedBytes(
        modifiers: AccessoryModifierSnapshot = AccessoryModifierSnapshot(),
    ): ByteArray = (resolve(modifiers) as TerminalAccessoryDispatch.Bytes).value
}
