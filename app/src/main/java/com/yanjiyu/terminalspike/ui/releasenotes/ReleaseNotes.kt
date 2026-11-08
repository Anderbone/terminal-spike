package com.yanjiyu.terminalspike.ui.releasenotes

import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R

internal data class ReleaseNoteSection(
    @param:StringRes val title: Int,
    val items: List<Int>,
)

internal data class ReleaseNotes(
    val revision: Int,
    val versionName: String,
    val sections: List<ReleaseNoteSection>,
)

/** Newest first. Revisions identify authored release notes, independently of APK build codes. */
internal val bundledReleaseNotes = listOf(
    ReleaseNotes(
        revision = 3,
        versionName = "0.0.14",
        sections = listOf(
            ReleaseNoteSection(
                R.string.release_notes_improved,
                listOf(R.string.release_notes_3_typography, R.string.release_notes_3_keyboard, R.string.release_notes_3_screens),
            ),
            ReleaseNoteSection(
                R.string.release_notes_fixed,
                listOf(R.string.release_notes_3_herdr),
            ),
        ),
    ),
    ReleaseNotes(
        revision = 2,
        versionName = "0.0.13",
        sections = listOf(
            ReleaseNoteSection(
                R.string.release_notes_added,
                listOf(R.string.release_notes_2_browser, R.string.release_notes_2_guide, R.string.release_notes_2_local),
            ),
            ReleaseNoteSection(
                R.string.release_notes_improved,
                listOf(R.string.release_notes_2_projects, R.string.release_notes_2_attachments, R.string.release_notes_2_snippets),
            ),
            ReleaseNoteSection(
                R.string.release_notes_fixed,
                listOf(R.string.release_notes_2_history, R.string.release_notes_2_input),
            ),
        ),
    ),
    ReleaseNotes(
        revision = 1,
        versionName = "0.0.12",
        sections = listOf(
            ReleaseNoteSection(
                R.string.release_notes_added,
                listOf(R.string.release_notes_1_updates, R.string.release_notes_1_snippets),
            ),
            ReleaseNoteSection(
                R.string.release_notes_improved,
                listOf(R.string.release_notes_1_tabs),
            ),
            ReleaseNoteSection(
                R.string.release_notes_fixed,
                listOf(R.string.release_notes_1_navigation),
            ),
        ),
    ),
)

internal fun unreadReleaseNotes(notes: List<ReleaseNotes>, seenRevision: Long): List<ReleaseNotes> =
    notes.filter { it.revision.toLong() > seenRevision }.sortedByDescending { it.revision }

/** Existing installs see the first bundled notes; fresh installs start with nothing unread. */
internal fun initialReleaseNotesRevision(
    firstInstallTime: Long,
    lastUpdateTime: Long,
    latestRevision: Int,
): Int = if (lastUpdateTime > firstInstallTime) 0 else latestRevision
