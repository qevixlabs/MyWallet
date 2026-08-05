package com.mywallet

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.BsDate
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.domain.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Recurrence is where a money app quietly goes wrong: a salary that drifts a day
 * each month, or a bill on the 31st that skips February entirely.
 */
class RecurrenceTest {

    private fun monthly(start: LocalDate, from: LocalDate, to: LocalDate) =
        Recurrence.occurrencesBetween(start, RecurrenceInterval.MONTHLY, from, to)

    @Test
    fun `monthly keeps the same day of month`() {
        val dates = monthly(
            LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
        )
        assertEquals(6, dates.size)
        assertTrue("every occurrence should be on the 5th", dates.all { it.dayOfMonth == 5 })
    }

    @Test
    fun `a half-yearly repeat lands twice a year, six months apart`() {
        val dates = Recurrence.occurrencesBetween(
            LocalDate.of(2026, 1, 15), RecurrenceInterval.HALF_YEARLY,
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 7, 15),
            ),
            dates,
        )
    }

    @Test
    fun `a 31st repeat clamps to the last day of shorter months, never skipping one`() {
        val dates = monthly(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
            ),
            dates,
        )
    }

    @Test
    fun `clamping does not become permanent — later months return to the 31st`() {
        // The bug this guards: anchoring off the previous occurrence instead of
        // the series start, so one February drags every later month to the 28th.
        val dates = monthly(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
        )
        assertEquals(listOf(LocalDate.of(2026, 5, 31)), dates)
    }

    @Test
    fun `the day of the month never moves, whatever calendar the user reads`() {
        // The bug this guards is the one a borrower notices first. Monthly used
        // to be added in Bikram Sambat while the user read BS, so an EMI agreed
        // for the 10th landed on the 1st of a Nepali month instead — a different
        // English day almost every time, wandering across the calendar while the
        // bank went on debiting the 10th.
        //
        // Anchored on a Nepali month start, so a BS-stepping implementation
        // would produce varying English days and be caught.
        val start = BikramSambat.toGregorian(BsDate(2083, 4, 1))
        val dates = monthly(start, start, start.plusDays(200))

        assertTrue("expected several occurrences, got ${dates.size}", dates.size >= 6)
        assertTrue(
            "every occurrence should keep the English day ${start.dayOfMonth}, got $dates",
            dates.all { it.dayOfMonth == start.dayOfMonth },
        )
    }

    @Test
    fun `weekly and fortnightly step by exact weeks`() {
        val start = LocalDate.of(2026, 3, 2)
        val weekly = Recurrence.occurrencesBetween(
            start, RecurrenceInterval.WEEKLY, start, start.plusDays(28),
        )
        assertEquals(5, weekly.size)
        assertTrue(weekly.all { it.dayOfWeek == start.dayOfWeek })

        val fortnightly = Recurrence.occurrencesBetween(
            start, RecurrenceInterval.FORTNIGHTLY, start, start.plusDays(28),
        )
        assertEquals(listOf(start, start.plusDays(14), start.plusDays(28)), fortnightly)
    }

    @Test
    fun `an end date stops the series`() {
        val dates = Recurrence.occurrencesBetween(
            start = LocalDate.of(2026, 1, 1),
            interval = RecurrenceInterval.MONTHLY,
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 12, 31),
            endOn = LocalDate.of(2026, 3, 15),
        )
        assertEquals(3, dates.size)
        assertEquals(LocalDate.of(2026, 3, 1), dates.last())
    }

    @Test
    fun `nothing is produced before the series starts`() {
        val dates = monthly(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31),
        )
        assertTrue(dates.isEmpty())
    }

    @Test
    fun `a seven-year loan produces every instalment, past and future, on its own day`() {
        // A loan entered long after it started: the first instalment fell on
        // 10 October 2025 and the app was told about it on 28 July 2026. Every
        // date in between is one the borrower has already paid, and every date
        // after is one they still owe.
        val first = LocalDate.of(2025, 10, 10)
        val last = first.plusMonths(83)
        val all = Recurrence.occurrencesBetween(
            start = first,
            interval = RecurrenceInterval.MONTHLY,
            from = first,
            to = last,
            endOn = last,
        )
        assertEquals(84, all.size)
        assertTrue("every instalment falls on the 10th", all.all { it.dayOfMonth == 10 })
        assertEquals(LocalDate.of(2032, 9, 10), all.last())

        val alreadyDue = all.count { it <= LocalDate.of(2026, 7, 28) }
        assertEquals(10, alreadyDue)
    }

    @Test
    fun `a plain count of months steps by exactly that many`() {
        // The gap a loan was actually agreed at, which the named intervals
        // cannot say. Before the app could carry it, "every two months" fell
        // back to monthly and billed the borrower twice as often as the bank.
        val start = LocalDate.of(2026, 1, 10)
        val everyTwo = Recurrence.occurrencesBetween(
            start = start,
            interval = RecurrenceInterval.MONTHLY,
            from = start,
            to = LocalDate.of(2026, 12, 31),
            everyMonths = 2,
        )
        assertEquals(
            listOf(1, 3, 5, 7, 9, 11).map { LocalDate.of(2026, it, 10) },
            everyTwo,
        )

        val everyFive = Recurrence.occurrencesBetween(
            start = start,
            interval = RecurrenceInterval.MONTHLY,
            from = start,
            to = LocalDate.of(2027, 12, 31),
            everyMonths = 5,
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 6, 10),
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2027, 4, 10),
                LocalDate.of(2027, 9, 10),
            ),
            everyFive,
        )
    }

    @Test
    fun `a gap as long as the loan produces one occurrence and stops`() {
        // How "pay it all at the end" is stored: one period as long as the
        // term, ending the day it starts. A second occurrence here would be the
        // app billing a debt that has already been settled in full.
        val due = LocalDate.of(2027, 1, 10)
        val dates = Recurrence.occurrencesBetween(
            start = due,
            interval = RecurrenceInterval.MONTHLY,
            from = LocalDate.of(2026, 1, 10),
            to = LocalDate.of(2030, 12, 31),
            endOn = due,
            everyMonths = 12,
        )
        assertEquals(listOf(due), dates)
    }

    @Test
    fun `a stepped count wins over the named interval`() {
        // Both are stored, because the column was added to rules that already
        // carried an interval. The count is the one that has to decide.
        val start = LocalDate.of(2026, 1, 10)
        assertEquals(
            LocalDate.of(2026, 3, 10),
            Recurrence.nextAfter(
                start, RecurrenceInterval.YEARLY, after = start, everyMonths = 2,
            ),
        )
    }

    @Test
    fun `nextAfter finds the following occurrence and respects the end date`() {
        val start = LocalDate.of(2026, 1, 10)
        assertEquals(
            LocalDate.of(2026, 2, 10),
            Recurrence.nextAfter(start, RecurrenceInterval.MONTHLY, start),
        )
        assertNull(
            Recurrence.nextAfter(
                start, RecurrenceInterval.MONTHLY,
                after = LocalDate.of(2026, 2, 10), endOn = LocalDate.of(2026, 2, 20),
            ),
        )
    }

    @Test
    fun `nextAfter keeps the instalment day when a lump sum re-bases a loan`() {
        // What the loan editor asks after a prepayment made on the 30th: the
        // next instalment is the schedule's own next date, not a month after
        // the day the money happened to move.
        assertEquals(
            LocalDate.of(2026, 7, 10),
            Recurrence.nextAfter(
                LocalDate.of(2025, 10, 10), RecurrenceInterval.MONTHLY,
                after = LocalDate.of(2026, 6, 30),
            ),
        )
    }

    @Test
    fun `a Nepali monthly rule keeps its Nepali day of the month`() {
        // 14 April 2025 is 1 Baisakh 2082. Counted in Nepali months the rule
        // falls on the 1st of Jestha, Asar, Shrawan — which is the day the user
        // picked and the day they will look for it on.
        val start = LocalDate.of(2025, 4, 14)
        val dates = Recurrence.occurrencesBetween(
            start = start,
            interval = RecurrenceInterval.MONTHLY,
            from = start,
            to = LocalDate.of(2025, 8, 30),
            inBikramSambat = true,
        )
        dates.forEach { day ->
            val bs = BikramSambat.fromGregorian(day)
            assertEquals("$day should be the 1st of a Nepali month", 1, bs.day)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), dates.map { BikramSambat.fromGregorian(it).month })
    }

    @Test
    fun `the same rule counted in English months lands twice in one Nepali month`() {
        // The bug this exists to stop. Baisakh 2082 runs 14 April to 14 May, so
        // stepping in Gregorian months puts the second occurrence back inside the
        // month the first one opened — one bill drawn twice in Baisakh, which is
        // what a user reading Nepali reports as a duplicate.
        val start = LocalDate.of(2025, 4, 14)
        val gregorian = Recurrence.occurrencesBetween(
            start = start,
            interval = RecurrenceInterval.MONTHLY,
            from = start,
            to = LocalDate.of(2025, 5, 14),
        )
        assertEquals(listOf(LocalDate.of(2025, 4, 14), LocalDate.of(2025, 5, 14)), gregorian)
        assertEquals(
            "both fall in Baisakh",
            listOf(1, 1),
            gregorian.map { BikramSambat.fromGregorian(it).month },
        )

        // In Nepali months there is exactly one, which is what was asked for.
        val nepali = Recurrence.occurrencesBetween(
            start = start,
            interval = RecurrenceInterval.MONTHLY,
            from = start,
            to = LocalDate.of(2025, 5, 14),
            inBikramSambat = true,
        )
        assertEquals(listOf(LocalDate.of(2025, 4, 14)), nepali)
    }

    @Test
    fun `a Nepali day past the end of a short month clamps rather than sliding`() {
        // Asar 2082 has 32 days and Shrawan has 31, so a rule anchored on 32
        // Asar lands on 31 Shrawan — its last day — and not on the 1st of
        // Bhadra, which would drag every occurrence after it into the wrong
        // month for good.
        assertEquals(32, BikramSambat.daysInMonth(2082, 3))
        val start = BikramSambat.toGregorian(BsDate(2082, 3, 32))
        val next = Recurrence.nth(
            start, RecurrenceInterval.MONTHLY, index = 1, inBikramSambat = true,
        )
        val bs = BikramSambat.fromGregorian(next)
        assertEquals("the month after Asar", 4, bs.month)
        assertEquals("clamped to its last day", BikramSambat.daysInMonth(2082, 4), bs.day)
    }

    @Test
    fun `a year of Nepali months comes back to the same Nepali day`() {
        val start = LocalDate.of(2025, 4, 14)
        val after = Recurrence.nth(
            start, RecurrenceInterval.MONTHLY, index = 12, inBikramSambat = true,
        )
        val bs = BikramSambat.fromGregorian(after)
        assertEquals(2083, bs.year)
        assertEquals(1, bs.month)
        assertEquals(1, bs.day)
    }

    @Test
    fun `weeks do not care which calendar the rule counts in`() {
        val start = LocalDate.of(2025, 4, 14)
        listOf(RecurrenceInterval.WEEKLY, RecurrenceInterval.FORTNIGHTLY).forEach { every ->
            assertEquals(
                Recurrence.nth(start, every, index = 3),
                Recurrence.nth(start, every, index = 3, inBikramSambat = true),
            )
        }
    }
}
