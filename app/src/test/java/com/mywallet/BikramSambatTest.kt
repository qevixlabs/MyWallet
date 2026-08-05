package com.mywallet

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.BsDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Conversion is delegated to the nepali-date-picker library, so these tests are
 * no longer guarding a local table — they guard the *adapter*: that the year
 * range, month lengths, round-tripping and digit rendering behave the way the
 * rest of the app assumes.
 *
 * The mid-April New Year check stays because it is the cheapest end-to-end
 * sanity check available: if a library upgrade ever shifted the epoch, this
 * fails immediately.
 */
class BikramSambatTest {

    @Test
    fun `every year has a plausible number of days`() {
        for (year in BikramSambat.MIN_YEAR..BikramSambat.MAX_YEAR) {
            val days = BikramSambat.daysInYear(year)
            assertTrue(
                "BS $year has $days days, which is not a real solar year",
                days == 365 || days == 366,
            )
        }
    }

    @Test
    fun `every month length is between 29 and 32 days`() {
        for (year in BikramSambat.MIN_YEAR..BikramSambat.MAX_YEAR) {
            for (month in 1..12) {
                val length = BikramSambat.daysInMonth(year, month)
                assertTrue(
                    "BS $year-$month has $length days",
                    length in 29..32,
                )
            }
        }
    }

    /**
     * The strongest check available without an external source: Nepali New Year
     * (Baisakh 1) always lands in mid-April. A typo anywhere in the table shifts
     * every later year's new year off that window.
     */
    @Test
    fun `new year always falls in mid-April`() {
        for (year in BikramSambat.MIN_YEAR..BikramSambat.MAX_YEAR) {
            val newYear = BikramSambat.toGregorian(BsDate(year, 1, 1))
            assertEquals("BS $year new year is not in April", 4, newYear.monthValue)
            assertTrue(
                "BS $year new year fell on ${newYear.dayOfMonth} April",
                newYear.dayOfMonth in 12..15,
            )
        }
    }

    @Test
    fun `converting to BS and back returns the same day, for every day in range`() {
        var date = BikramSambat.toGregorian(BsDate(BikramSambat.MIN_YEAR, 1, 1))
        val end = BikramSambat.toGregorian(
            BsDate(BikramSambat.MAX_YEAR, 12, BikramSambat.daysInMonth(BikramSambat.MAX_YEAR, 12))
        )
        var checked = 0
        while (!date.isAfter(end)) {
            val bs = BikramSambat.fromGregorian(date)
            assertEquals("round trip failed at $date (BS $bs)", date, BikramSambat.toGregorian(bs))
            date = date.plusDays(1)
            checked++
        }
        assertTrue("expected to check tens of thousands of days, checked $checked", checked > 30_000)
    }

    @Test
    fun `consecutive days advance the BS date by exactly one`() {
        var date = LocalDate.of(2020, 1, 1)
        var previous = BikramSambat.fromGregorian(date)
        repeat(4_000) {
            date = date.plusDays(1)
            val current = BikramSambat.fromGregorian(date)
            val sameMonth = current.year == previous.year && current.month == previous.month
            if (sameMonth) {
                assertEquals("day did not advance by 1 at $date", previous.day + 1, current.day)
            } else {
                assertEquals("month rollover did not start at day 1 at $date", 1, current.day)
                assertEquals(
                    "month rollover skipped the end of the previous month at $date",
                    BikramSambat.daysInMonth(previous.year, previous.month),
                    previous.day,
                )
            }
            previous = current
        }
    }

    @Test
    fun `dates outside the table are reported as unsupported rather than converted`() {
        assertTrue(BikramSambat.supports(LocalDate.of(2026, 7, 26)))
        assertTrue(!BikramSambat.supports(LocalDate.of(1800, 1, 1)))
        assertTrue(!BikramSambat.supports(LocalDate.of(2400, 1, 1)))
    }

    @Test
    fun `nepali digits render Devanagari numerals`() {
        assertEquals("२०८३", BikramSambat.toNepaliDigits(2083))
        assertEquals("१०", BikramSambat.toNepaliDigits(10))
    }
}
