package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierActivationTest {
    @Test
    fun acceptedExtraKeyConsumesOnlyOneShotModifiers() {
        val result = TerminalSpikeUiState(
            ctrlArmed = true,
            altArmed = true,
            ctrlLocked = false,
            altLocked = true,
        ).afterExtraKeyDispatch(accepted = true)

        assertFalse(result.ctrlArmed)
        assertTrue(result.altArmed)
        assertFalse(result.ctrlLocked)
        assertTrue(result.altLocked)
    }

    @Test
    fun rejectedExtraKeyPreservesOneShotModifiersForRetry() {
        val state = TerminalSpikeUiState(ctrlArmed = true, altArmed = true)

        assertEquals(state, state.afterExtraKeyDispatch(accepted = false))
    }

    @Test
    fun oneShotTogglesWithoutLocking() {
        assertEquals(
            ModifierActivation(armed = true, locked = false, lastTapNanos = 0L),
            nextModifierActivation(
                armed = false,
                locked = false,
                behavior = ModifierBehavior.ONE_SHOT,
                lastTapNanos = 0L,
                nowNanos = 10L,
            ),
        )
    }

    @Test
    fun secondQuickTapLocksAndNextTapUnlocks() {
        val first = nextModifierActivation(
            armed = false,
            locked = false,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTapNanos = 0L,
            nowNanos = 1_000_000_000L,
        )
        val second = nextModifierActivation(
            armed = first.armed,
            locked = first.locked,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTapNanos = first.lastTapNanos,
            nowNanos = 1_200_000_000L,
        )
        val third = nextModifierActivation(
            armed = second.armed,
            locked = second.locked,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTapNanos = second.lastTapNanos,
            nowNanos = 2_000_000_000L,
        )

        assertEquals(ModifierActivation(true, false, 1_000_000_000L), first)
        assertEquals(ModifierActivation(true, true, 0L), second)
        assertEquals(ModifierActivation(false, false, 0L), third)
    }

    @Test
    fun slowSecondTapDisarmsInsteadOfLocking() {
        assertEquals(
            ModifierActivation(armed = false, locked = false, lastTapNanos = 0L),
            nextModifierActivation(
                armed = true,
                locked = false,
                behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
                lastTapNanos = 1_000_000_000L,
                nowNanos = 1_500_000_000L,
            ),
        )
    }
}
