package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A one-shot modifier belongs to the first key, not an entire IME text batch. */
internal fun applyPendingInputModifiers(
    bytes: ByteArray,
    control: Boolean,
    alt: Boolean,
    shift: Boolean,
): ByteArray {
    if (bytes.isEmpty()) return bytes.copyOf()
    val first = bytes[0].toInt() and 0xff
    // Leave multi-byte characters and already encoded terminal sequences intact.
    if (first !in 0x20..0x7e) return bytes.copyOf()
    val character = first.toChar().let { if (shift) it.uppercaseChar() else it }
    val key = if (control) {
        encodeTerminalChord(if (character == ' ') "C-Space" else "C-$character")
            ?: byteArrayOf(character.code.toByte())
    } else {
        byteArrayOf(character.code.toByte())
    }
    val prefix = if (alt) byteArrayOf(0x1b) else byteArrayOf()
    return prefix + key + bytes.copyOfRange(1, bytes.size)
}

internal data class ModifierActivation(
    val armed: Boolean,
    val locked: Boolean,
    val lastTapNanos: Long,
)

internal fun TerminalSpikeUiState.afterExtraKeyDispatch(accepted: Boolean): TerminalSpikeUiState =
    if (!accepted) {
        this
    } else {
        copy(ctrlArmed = ctrlLocked, altArmed = altLocked)
    }

internal fun TerminalSpikeUiState.afterAccessoryByteDispatch(): TerminalSpikeUiState = copy(
    ctrlArmed = ctrlLocked,
    altArmed = altLocked,
    shiftArmed = shiftLocked,
)

internal fun nextModifierActivation(
    armed: Boolean,
    locked: Boolean,
    behavior: ModifierBehavior,
    lastTapNanos: Long,
    nowNanos: Long,
): ModifierActivation {
    if (behavior == ModifierBehavior.ONE_SHOT) {
        return ModifierActivation(armed = !armed, locked = false, lastTapNanos = 0L)
    }
    if (locked || armed && nowNanos - lastTapNanos !in 1..MODIFIER_DOUBLE_TAP_NANOS) {
        return ModifierActivation(armed = false, locked = false, lastTapNanos = 0L)
    }
    if (armed) {
        return ModifierActivation(armed = true, locked = true, lastTapNanos = 0L)
    }
    return ModifierActivation(armed = true, locked = false, lastTapNanos = nowNanos)
}

private const val MODIFIER_DOUBLE_TAP_NANOS = 350_000_000L

/** The UI caller owns tap timestamps; unrelated background state updates may cause CAS retries. */
internal fun MutableStateFlow<TerminalSpikeUiState>.activateInputModifier(
    modifier: TerminalAccessoryModifier,
    lastTapNanos: Long,
    nowNanos: Long,
): Long {
    var nextTapNanos = lastTapNanos
    update { state ->
        val activation = nextModifierActivation(
            armed = when (modifier) {
                TerminalAccessoryModifier.CONTROL -> state.ctrlArmed
                TerminalAccessoryModifier.ALT -> state.altArmed
                TerminalAccessoryModifier.SHIFT -> state.shiftArmed
            },
            locked = when (modifier) {
                TerminalAccessoryModifier.CONTROL -> state.ctrlLocked
                TerminalAccessoryModifier.ALT -> state.altLocked
                TerminalAccessoryModifier.SHIFT -> state.shiftLocked
            },
            behavior = state.modifierBehavior,
            // Every retry represents the same tap, including its previous timestamp.
            lastTapNanos = lastTapNanos,
            nowNanos = nowNanos,
        )
        nextTapNanos = activation.lastTapNanos
        when (modifier) {
            TerminalAccessoryModifier.CONTROL -> state.copy(ctrlArmed = activation.armed, ctrlLocked = activation.locked)
            TerminalAccessoryModifier.ALT -> state.copy(altArmed = activation.armed, altLocked = activation.locked)
            TerminalAccessoryModifier.SHIFT -> state.copy(shiftArmed = activation.armed, shiftLocked = activation.locked)
        }
    }
    return nextTapNanos
}
