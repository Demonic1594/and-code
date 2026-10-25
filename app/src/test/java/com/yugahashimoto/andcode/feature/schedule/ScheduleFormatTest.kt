package com.yugahashimoto.andcode.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ScheduleFormatTest {
    @Test
    fun `a single day is its short name`() {
        assertEquals("Fri", weekdaysLabel(listOf(5), Locale.ENGLISH))
        assertEquals("Sun", weekdaysLabel(listOf(0), Locale.ENGLISH))
    }

    @Test
    fun `three or more consecutive days read as a range`() {
        assertEquals("Mon–Fri", weekdaysLabel(listOf(1, 2, 3, 4, 5), Locale.ENGLISH))
        assertEquals("Mon–Fri", weekdaysLabel(listOf(5, 4, 3, 2, 1), Locale.ENGLISH))
    }

    @Test
    fun `other sets are listed`() {
        assertEquals("Mon, Wed, Fri", weekdaysLabel(listOf(1, 3, 5), Locale.ENGLISH))
        assertEquals("Sat, Sun", weekdaysLabel(listOf(6, 0), Locale.ENGLISH))
        assertEquals("Mon、Wed", weekdaysLabel(listOf(3, 1), Locale.ENGLISH, separator = "、"))
    }

    @Test
    fun `the week reads Monday first so a run through Sunday is still a range`() {
        assertEquals("Fri–Sun", weekdaysLabel(listOf(0, 5, 6), Locale.ENGLISH))
        // Cron also accepts 7 for Sunday; the parser normalises it, but a duplicate stays one day.
        assertEquals("Sun", weekdaysLabel(listOf(0, 7), Locale.ENGLISH))
    }
}
