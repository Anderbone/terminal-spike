package com.yanjiyu.terminalspike.core.model

/** Stable slot IDs must survive replacements/reordering in future shipped defaults. */
object KeyboardDefaultInheritance {
    data class Slot(val id: String, val action: KeyboardAction)
    val current: List<Slot> = listOf(
        Slot("s0", KeyboardAction.ESCAPE),
        Slot("s1", KeyboardAction.SLASH),
        Slot("s2", KeyboardAction.AT_SIGN),
        Slot("s3", KeyboardAction.DOLLAR),
        Slot("s4", KeyboardAction.SELECT_IMAGES),
        Slot("s5", KeyboardAction.HOME),
        Slot("s6", KeyboardAction.ARROW_UP),
        Slot("s7", KeyboardAction.END),
        Slot("s8", KeyboardAction.PAGE_UP),
        Slot("s9", KeyboardAction.BACKSPACE),
        Slot("s10", KeyboardAction.TAB),
        Slot("s11", KeyboardAction.CONTROL),
        Slot("s12", KeyboardAction.AI_AGENTS),
        Slot("s13", KeyboardAction.CTRL_C),
        Slot("s14", KeyboardAction.CTRL_W),
        Slot("s15", KeyboardAction.ARROW_LEFT),
        Slot("s16", KeyboardAction.ARROW_DOWN),
        Slot("s17", KeyboardAction.ARROW_RIGHT),
        Slot("s18", KeyboardAction.ENTER),
        Slot("s19", KeyboardAction.HIDE_KEYBOARD),
    )

    private data class State(val baseline: List<Slot>, val owners: List<String>) {
        fun encode(): String = "1|" + baseline.joinToString(",") { "${it.id}:${it.action.wireCode}" } +
            "|" + owners.joinToString(",")
    }

    private fun parse(value: String, count: Int): State {
        require(value.length <= 16384)
        val parts = value.split('|')
        require(parts.size == 3 && parts[0] == "1")
        val baseline = parts[1].split(',').map {
            val pair = it.split(':')
            require(pair.size == 2 && pair[0].matches(Regex("s[0-9]{1,4}")))
            Slot(pair[0], KeyboardAction.fromWireCode(pair[1]))
        }
        require(baseline.size <= KeyboardAction.entries.size && baseline.map { it.id }.distinct().size == baseline.size)
        val owners = parts[2].split(',')
        require(owners.size == count)
        val ids = baseline.map { it.id }.toSet()
        require(owners.all { it == "x" || it.removeSuffix("!") in ids })
        require(owners.filter { it != "x" }.map { it.removeSuffix("!") }.distinct().size == owners.count { it != "x" })
        return State(baseline, owners)
    }

    fun validate(value: String?, count: Int) { if (value != null) parse(value, count) }

    /** Unknown legacy custom layouts stay custom. Exact current defaults can establish ownership. */
    fun upgrade(profile: KeyboardProfile, target: List<Slot> = current): KeyboardProfile {
        if (profile.defaultLayoutState == null && profile.orderedActions != current.map { it.action }) return profile
        val state = profile.defaultLayoutState?.let { parse(it, profile.orderedActions.size) }
            ?: State(current, if (profile.orderedActions == current.map { it.action }) current.map { it.id }
                else profile.orderedActions.map { "x" })
        if (state.owners == state.baseline.map { it.id } &&
            profile.orderedActions == state.baseline.map { it.action }) {
            return profile.copy(orderedActions = target.map { it.action },
                defaultLayoutState = State(target, target.map { it.id }).encode())
        }
        val old = state.baseline.associateBy { it.id }
        val next = target.associateBy { it.id }
        val custom = profile.orderedActions.filterIndexed { index, action ->
            val owner = state.owners[index]
            owner == "x" || owner.endsWith('!') || old[owner]?.action != action
        }.toSet()
        val actions = mutableListOf<KeyboardAction>()
        val owners = mutableListOf<String>()
        profile.orderedActions.forEachIndexed { index, action ->
            val owner = state.owners[index]
            val id = owner.removeSuffix("!")
            val follows = owner != "x" && !owner.endsWith('!') && old[id]?.action == action
            val replacement = if (follows) next[id]?.action else action
            // Explicit user choices win collisions with a newly inherited button.
            if (replacement != null && (!follows || replacement !in custom) && replacement !in actions) {
                actions += replacement
                owners += if (id in next) owner else "x"
            }
        }
        target.filter { it.id !in old }.forEach {
            if (it.action !in actions) { actions += it.action; owners += it.id }
        }
        // A future default must never erase the last user-accessible button.
        if (actions.isEmpty()) return profile
        return profile.copy(orderedActions = actions, defaultLayoutState = State(target, owners).encode())
    }

    /** Preserve ownership through insert/delete/move. Replacements inherit their original slot. */
    fun edited(previous: KeyboardProfile, edited: KeyboardProfile): KeyboardProfile {
        if (previous.orderedActions == edited.orderedActions) return edited
        val before = upgrade(previous)
        val state = before.defaultLayoutState?.let { parse(it, before.orderedActions.size) }
            ?: return edited
        val remaining = before.orderedActions.toSet() - edited.orderedActions.toSet()
        val commonBefore = before.orderedActions.filter { it in edited.orderedActions }
        val commonAfter = edited.orderedActions.filter { it in before.orderedActions }
        val owners = edited.orderedActions.mapIndexed { index, action ->
            val oldIndex = before.orderedActions.indexOf(action)
            when {
                oldIndex >= 0 -> state.owners[oldIndex].let { owner ->
                    if (commonBefore.indexOf(action) != commonAfter.indexOf(action) && owner != "x") owner.removeSuffix("!") + "!" else owner
                }
                edited.orderedActions.size == before.orderedActions.size &&
                    before.orderedActions[index] in remaining -> state.owners[index]
                else -> "x"
            }
        }
        return edited.copy(defaultLayoutState = State(state.baseline, owners).encode())
    }

    fun reset(profile: KeyboardProfile): KeyboardProfile = profile.copy(
        orderedActions = current.map { it.action },
        defaultLayoutState = State(current, current.map { it.id }).encode(),
    )
}
