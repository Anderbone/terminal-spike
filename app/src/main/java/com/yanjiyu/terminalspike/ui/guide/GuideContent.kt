package com.yanjiyu.terminalspike.ui.guide

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R

/** Each visual teaches one action, using pixels from the running app. */
internal data class GuideVisual(
    @DrawableRes val poster: Int,
    val aspectRatio: Float,
    @RawRes val video: Int? = null,
)

internal enum class GuideLesson(
    @StringRes val title: Int,
    @StringRes val caption: Int,
    val visual: GuideVisual,
    val showOnFirstRun: Boolean = false,
) {
    TYPING(R.string.guide_input_title, R.string.guide_input_caption,
        GuideVisual(R.drawable.guide_start_typing, 720f / 900, R.raw.guide_start_typing), showOnFirstRun = true),
    SNIPPETS(R.string.guide_snippets_title, R.string.guide_snippets_caption,
        GuideVisual(R.drawable.guide_start_snippets, 720f / 900, R.raw.guide_start_snippets), showOnFirstRun = true),
    PROJECTS(R.string.guide_projects_title, R.string.guide_projects_caption,
        GuideVisual(R.drawable.guide_start_projects, 720f / 650, R.raw.guide_start_projects), showOnFirstRun = true),
    HERDR_LAYOUT(R.string.guide_herdr_layout_title, R.string.guide_herdr_layout_caption,
        GuideVisual(R.drawable.guide_start_projects, 720f / 650), showOnFirstRun = true),
    MOVE_SNIPPET(R.string.guide_tip_move_title, R.string.guide_tip_move_caption,
        GuideVisual(R.drawable.guide_tip_move, 720f / 410, R.raw.guide_tip_move)),
    EDIT_SNIPPET(R.string.guide_tip_edit_title, R.string.guide_tip_edit_caption,
        GuideVisual(R.drawable.guide_tip_edit, 720f / 1150)),
    RECENT_PROJECTS(R.string.guide_tip_recent_title, R.string.guide_tip_recent_caption,
        GuideVisual(R.drawable.guide_tip_recent, 720f / 1100, R.raw.guide_tip_recent)),
    CLOSE_TAB(R.string.guide_tip_close_tab_title, R.string.guide_tip_close_tab_caption,
        GuideVisual(R.drawable.guide_tip_close_tab, 720f / 450, R.raw.guide_tip_close_tab)),
    CLOSE_SPACE(R.string.guide_tip_close_space_title, R.string.guide_tip_close_space_caption,
        GuideVisual(R.drawable.guide_tip_close_space, 720f / 740)),
    TAB_ACTIONS(R.string.guide_tip_actions_title, R.string.guide_tip_actions_caption,
        GuideVisual(R.drawable.guide_tip_actions, 720f / 450, R.raw.guide_tip_actions)),
    ATTACHMENTS(R.string.guide_tip_attach_title, R.string.guide_tip_attach_caption,
        GuideVisual(R.drawable.guide_tip_attach, 720f / 410)),
    RESTORE_DRAFT(R.string.guide_tip_undo_title, R.string.guide_tip_undo_caption,
        GuideVisual(R.drawable.guide_tip_undo, 720f / 520)),
}

internal val onboardingGuideLessons = GuideLesson.entries.filter(GuideLesson::showOnFirstRun)
