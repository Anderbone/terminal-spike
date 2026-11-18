package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class HerdrProjectTimeTest {
    private val now = 1_794_758_400_000L

    @Test fun missingTimesStayUnknownAndRecentTimesUseCompactLocalizedCopy() {
        assertEquals(uiText(R.string.herdr_project_time_unknown), formatProjectLastUsed(null, now, Locale.ENGLISH))
        assertEquals(uiText(R.string.herdr_project_time_unknown), formatProjectLastUsed(0, now, Locale.ENGLISH))
        assertEquals(uiText(R.string.herdr_project_just_now), formatProjectLastUsed(now - 30_000, now, Locale.ENGLISH))
        assertEquals(quantityText(R.plurals.herdr_project_minutes_ago, 2), formatProjectLastUsed(now - 120_000, now, Locale.ENGLISH))
        assertEquals(quantityText(R.plurals.herdr_project_hours_ago, 2), formatProjectLastUsed(now - 7_200_000, now, Locale.ENGLISH))
        assertEquals(quantityText(R.plurals.herdr_project_days_ago, 3), formatProjectLastUsed(now - 3 * 86_400_000, now, Locale.CHINESE))
    }

    @Test fun olderTimesUseTheSelectedLocaleCalendarDate() {
        val then = now - 10 * 86_400_000L
        for (locale in listOf(Locale.UK, Locale.US, Locale.SIMPLIFIED_CHINESE)) {
            assertEquals(UiText.Dynamic(DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(then))),
                formatProjectLastUsed(then, now, locale))
        }
    }
}
