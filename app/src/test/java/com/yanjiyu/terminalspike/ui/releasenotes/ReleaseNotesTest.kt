package com.yanjiyu.terminalspike.ui.releasenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun freshInstallStartsWithCurrentNotesAlreadyRead() {
        val baseline = initialReleaseNotesRevision(100, 100, latestRevision = 3)
        assertEquals(3, baseline)
        assertTrue(unreadReleaseNotes(releases(), baseline.toLong()).isEmpty())
    }

    @Test
    fun installUpgradedBeforeNotesExistedSeesBundledUpdates() {
        val baseline = initialReleaseNotesRevision(100, 200, latestRevision = 3)
        assertEquals(0, baseline)
        assertEquals(listOf(3, 2, 1), unreadReleaseNotes(releases(), baseline.toLong()).map { it.revision })
    }

    @Test
    fun skippedVersionsAreGroupedNewestFirstWithoutRepeatingReadNotes() {
        assertEquals(listOf(3, 2), unreadReleaseNotes(releases(), 1).map { it.revision })
    }

    @Test
    fun reinstallingSameReleaseOrDowngradingDoesNotRepeatNotes() {
        assertTrue(unreadReleaseNotes(releases(), 3).isEmpty())
        assertTrue(unreadReleaseNotes(releases(), 4).isEmpty())
        assertTrue(unreadReleaseNotes(releases(), 4_294_967_295L).isEmpty())
    }

    @Test
    fun bundledNotesHaveStableUniqueRevisionsAndNonEmptyResourceBackedContent() {
        assertEquals(bundledReleaseNotes.size, bundledReleaseNotes.map { it.revision }.toSet().size)
        assertEquals(bundledReleaseNotes.sortedByDescending { it.revision }, bundledReleaseNotes)
        bundledReleaseNotes.forEach { release ->
            assertTrue(release.revision > 0)
            assertTrue(release.versionName.isNotBlank())
            assertTrue(release.sections.isNotEmpty())
            release.sections.forEach { section ->
                assertTrue(section.title != 0)
                assertTrue(section.items.isNotEmpty())
                assertTrue(section.items.all { it != 0 })
            }
        }
    }

    private fun releases(): List<ReleaseNotes> = listOf(1, 3, 2).map { revision ->
        ReleaseNotes(revision, "1.$revision", emptyList())
    }
}
