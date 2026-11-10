package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.*
import org.junit.Test

class KeyboardDefaultInheritanceTest {
    private fun profile() = KeyboardDefaultInheritance.reset(KeyboardProfile(
        id = "00000000-0000-4000-8000-000000000001", name = "Default",
        orderedActions = KeyboardAction.DEFAULT_ORDER, layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT, hapticFeedbackEnabled = false,
        keyRepeatEnabled = true, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    ))
    private fun target() = KeyboardDefaultInheritance.current.map {
        if (it.id == "s12") it.copy(action = KeyboardAction.TMUX_SESSIONS) else it
    }

    @Test fun stableSlotActionsMatchTheShippedDefault() {
        assertEquals(KeyboardAction.DEFAULT_ORDER, KeyboardDefaultInheritance.current.map { it.action })
    }

    @Test fun entirelyUntouchedDefaultsFollowTheNewOrderAsWellAsReplacements() {
        val next = target().reversed()
        val updated = KeyboardDefaultInheritance.upgrade(profile(), next)
        assertEquals(next.map { it.action }, updated.orderedActions)
        assertEquals(updated, KeyboardDefaultInheritance.upgrade(updated, next))
    }

    @Test fun partialCustomizationStillInheritsUntouchedSlotAndRoundTripsProvenance() {
        val original = profile()
        val custom = KeyboardDefaultInheritance.edited(original, original.copy(
            orderedActions = original.orderedActions.map { if (it == KeyboardAction.SLASH) KeyboardAction.F1 else it },
        ))
        val upgraded = KeyboardDefaultInheritance.upgrade(custom, target())
        assertEquals(KeyboardAction.F1, upgraded.orderedActions[1])
        assertEquals(KeyboardAction.TMUX_SESSIONS, upgraded.orderedActions[12])
        assertEquals(upgraded, KeyboardDefaultInheritance.upgrade(upgraded, target()))
        KeyboardDefaultInheritance.validate(upgraded.defaultLayoutState, upgraded.orderedActions.size)
    }

    @Test fun editedReplacementWinsAndMovedActionNeverBecomesAnInheritedReplacement() {
        val original = profile()
        val replacement = KeyboardDefaultInheritance.edited(original, original.copy(
            orderedActions = original.orderedActions.map { if (it == KeyboardAction.AI_AGENTS) KeyboardAction.F1 else it },
        ))
        assertEquals(KeyboardAction.F1, KeyboardDefaultInheritance.upgrade(replacement, target()).orderedActions[12])
        val moved = original.orderedActions.toMutableList().apply { add(0, removeAt(12)) }
        val upgraded = KeyboardDefaultInheritance.upgrade(KeyboardDefaultInheritance.edited(original,
            original.copy(orderedActions = moved)), target())
        assertEquals(KeyboardAction.AI_AGENTS, upgraded.orderedActions.first())
        assertFalse(KeyboardAction.TMUX_SESSIONS in upgraded.orderedActions)
    }

    @Test fun insertionDeletionAndNewSlotsPreserveUserOrderWithoutRevivingDeletedSlots() {
        val original = profile()
        val actions = original.orderedActions.toMutableList().apply { removeAt(1); add(0, KeyboardAction.F1) }
        val custom = KeyboardDefaultInheritance.edited(original, original.copy(orderedActions = actions))
        val next = target() + KeyboardDefaultInheritance.Slot("s20", KeyboardAction.F2)
        val upgraded = KeyboardDefaultInheritance.upgrade(custom, next)
        assertEquals(KeyboardAction.F1, upgraded.orderedActions.first())
        assertFalse(KeyboardAction.SLASH in upgraded.orderedActions)
        assertTrue(KeyboardAction.TMUX_SESSIONS in upgraded.orderedActions)
        assertEquals(KeyboardAction.F2, upgraded.orderedActions.last())
    }

    @Test fun customDuplicateWinsAndRemovedCustomSlotSurvives() {
        val original = profile()
        val actions = original.orderedActions.map { if (it == KeyboardAction.SLASH) KeyboardAction.TMUX_SESSIONS else it }
        val custom = KeyboardDefaultInheritance.edited(original, original.copy(orderedActions = actions))
        val upgraded = KeyboardDefaultInheritance.upgrade(custom, target().filterNot { it.id == "s1" })
        assertEquals(KeyboardAction.TMUX_SESSIONS, upgraded.orderedActions[1])
        assertEquals(1, upgraded.orderedActions.count { it == KeyboardAction.TMUX_SESSIONS })
    }

    @Test fun unknownLegacyCustomLayoutsArePreservedAndMalformedOwnershipIsRejected() {
        val custom = profile().copy(defaultLayoutState = null, orderedActions = listOf(KeyboardAction.F1))
        assertEquals(custom.orderedActions, KeyboardDefaultInheritance.upgrade(custom).orderedActions)
        assertThrows(IllegalArgumentException::class.java) { KeyboardDefaultInheritance.validate("1|s1:escape|s1,s1", 2) }
    }
}
