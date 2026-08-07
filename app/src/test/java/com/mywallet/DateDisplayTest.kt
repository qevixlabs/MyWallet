package com.mywallet

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.date.DateDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The date a day's heading is written with, split into the figure in the margin
 * and the words beside it.
 *
 * The timeline draws the day of the month big and the month quietly underneath
 * it, which means one date is now printed by two calls that have to agree with
 * each other and with the joined form every other screen uses. That agreement is
 * what these guard: a day drawn on its own and the same day drawn inside a
 * fuller date cannot come out in two different calendars or two different sets
 * of digits on one page.
 */
class DateDisplayTest {

    private val gregorian = DateDisplay(CalendarSystem.GREGORIAN, Locale.US)
    private val nepali = DateDisplay(CalendarSystem.BIKRAM_SAMBAT, Locale.US)

    @Test
    fun `the day and the month join back into the date they were split from`() {
        var date = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2027, 1, 1)
        while (date < end) {
            for (dates in listOf(gregorian, nepali)) {
                assertEquals(
                    "$date came apart",
                    dates.dayAndMonth(date),
                    "${dates.dayNumber(date)} ${dates.monthName(date)}",
                )
            }
            date = date.plusDays(1)
        }
    }

    @Test
    fun `a Nepali day is written in Nepali digits`() {
        // 1 Shrawan 2083 — a day inside the table, so BS is genuinely in use.
        val date = BikramSambat.toGregorian(com.mywallet.core.date.BsDate(2083, 4, 1))
        assertEquals("१", nepali.dayNumber(date))
        // The same instant, counted the other way: the two calendars disagree
        // about which day of which month this is, which is the whole reason the
        // margin has to be told which one it is printing.
        assertEquals(date.dayOfMonth.toString(), gregorian.dayNumber(date))
    }

    /**
     * The figure counts in the calendar being read, never the other one in the
     * other one's digits.
     *
     * 1 August 2026 falls on 16 Shrawan 2083, so a Nepali page heads that day
     * "१६" and prints "1 Aug" underneath. Read quickly — and it was, when the
     * digits were still tabular and sat a gap apart — "१६" looks like the
     * English day written in Devanagari, which would be the app counting one
     * calendar and labelling it the other.
     */
    @Test
    fun `a Nepali day counts the Nepali month, not the English one`() {
        val date = LocalDate.of(2026, 8, 1)
        assertEquals("१६", nepali.dayNumber(date))
        assertEquals("1 Aug", nepali.secondaryShort(date))
        assertEquals("1", gregorian.dayNumber(date))
    }

    /**
     * A Nepali page is written in Nepali, whatever language the interface is set
     * to — the same rule the dates already followed.
     */
    @Test
    fun `a Nepali page names its weekdays in Nepali`() {
        val saturday = LocalDate.of(2026, 8, 1)
        assertEquals("शनिबार", nepali.weekdayName(saturday))
        assertEquals("Saturday", gregorian.weekdayName(saturday))
    }

    /**
     * Outside the library's table the calendar has already fallen back to
     * Gregorian for that day — the figure has to fall back with it, or the
     * margin says a Gregorian day in Devanagari beside a Gregorian date in Latin.
     */
    @Test
    fun `a day outside the Bikram Sambat table is written the Gregorian way`() {
        val outside = LocalDate.of(1901, 1, 1)
        assertFalse(
            "1901 is inside the table now — pick another date for this test",
            BikramSambat.supports(outside),
        )
        assertEquals("1", nepali.dayNumber(outside))
        assertTrue(
            "the month fell back to a Nepali one",
            nepali.monthName(outside) == gregorian.monthName(outside),
        )
    }
}
