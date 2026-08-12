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

    private fun TerminalAccessoryAction.resolvedBytes(
        modifiers: AccessoryModifierSnapshot = AccessoryModifierSnapshot(),
    ): ByteArray = (resolve(modifiers) as TerminalAccessoryDispatch.Bytes).value
}
