package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PendingInputModifierActivationTest(private val modifier: TerminalAccessoryModifier) {
    @Test
    fun doubleTapStillLocksAfterAnUnrelatedStateUpdateWinsTheFirstCas() {
        exercise(
            armed = true, locked = false,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTap = 1_000_000_000L, now = 1_100_000_000L,
            expectedArmed = true, expectedLocked = true, expectedTimestamp = 0L,
        )
    }

    @Test
    fun firstTapArmsOnceAndRetainsItsTimestampAcrossTheRetry() {
        exercise(
            armed = false, locked = false,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTap = 0L, now = 1_000_000_000L,
            expectedArmed = true, expectedLocked = false, expectedTimestamp = 1_000_000_000L,
        )
    }

    @Test
    fun tappingALockedModifierClearsItAcrossTheRetry() {
        exercise(
            armed = true, locked = true,
            behavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            lastTap = 0L, now = 1_100_000_000L,
            expectedArmed = false, expectedLocked = false, expectedTimestamp = 0L,
        )
    }

    @Test
    fun oneShotTogglesOnlyOnceAcrossTheRetry() {
        exercise(
            armed = false, locked = false, behavior = ModifierBehavior.ONE_SHOT,
            lastTap = 1_000_000_000L, now = 1_100_000_000L,
            expectedArmed = true, expectedLocked = false, expectedTimestamp = 0L,
        )
    }

    private fun exercise(
        armed: Boolean,
        locked: Boolean,
        behavior: ModifierBehavior,
        lastTap: Long,
        now: Long,
        expectedArmed: Boolean,
        expectedLocked: Boolean,
        expectedTimestamp: Long,
    ) {
        val initial = TerminalSpikeUiState(
            ctrlArmed = true, ctrlLocked = true,
            altArmed = true, altLocked = true,
            shiftArmed = true, shiftLocked = true,
            modifierBehavior = behavior,
        ).withModifier(armed, locked)
        val flow = RetryingFlow(MutableStateFlow(initial))

        val committedTimestamp = flow.activateInputModifier(modifier, lastTap, now)

        assertEquals("The unrelated write must force exactly one retry", 2, flow.attempts)
        assertEquals(expectedTimestamp, committedTimestamp)
        assertEquals(
            initial.withModifier(expectedArmed, expectedLocked).copy(notice = concurrentNotice),
            flow.value,
        )
    }

    private fun TerminalSpikeUiState.withModifier(armed: Boolean, locked: Boolean): TerminalSpikeUiState =
        when (modifier) {
            TerminalAccessoryModifier.CONTROL -> copy(ctrlArmed = armed, ctrlLocked = locked)
            TerminalAccessoryModifier.ALT -> copy(altArmed = armed, altLocked = locked)
            TerminalAccessoryModifier.SHIFT -> copy(shiftArmed = armed, shiftLocked = locked)
        }

    // This deterministic interleaving represents a background notice arriving between
    // the update transform and its CAS. It uses the real StateFlow for storage/equality.
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class RetryingFlow(
        private val delegate: MutableStateFlow<TerminalSpikeUiState>,
    ) : MutableStateFlow<TerminalSpikeUiState> by delegate {
        var attempts = 0
            private set

        override fun compareAndSet(expect: TerminalSpikeUiState, update: TerminalSpikeUiState): Boolean {
            attempts += 1
            if (attempts == 1) delegate.value = delegate.value.copy(notice = concurrentNotice)
            return delegate.compareAndSet(expect, update)
        }
    }

    companion object {
        private val concurrentNotice = UiText.Dynamic("Concurrent notice")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modifiers(): List<Array<TerminalAccessoryModifier>> =
            TerminalAccessoryModifier.entries.map { arrayOf(it) }
    }
}
