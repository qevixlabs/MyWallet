package com.mywallet.domain

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.BsDate
import com.mywallet.data.db.entity.RecurrenceInterval
import java.time.LocalDate

/**
 * Works out when a repeating entry falls.
 *
 * **A month means a month of the calendar the rule was written in**, and the
 * rule carries which one — `recurring_series.recur_in_bs`. Somebody who set up
 * a subscription on 1 Baisakh means the 1st of Baisakh, Jestha, Asar: that is
 * the day of the month they think in, the day it appears on their statement,
 * and the day they will look for it. Stepping such a rule in Gregorian months
 * puts its second occurrence on 31 Baisakh — the same Nepali month it started
 * in, which is why one bill written on 14 April read as two payments in Baisakh
 * 2082 and looked like a duplicate.
 *
 * **A bank loan's instalment answers the same way, and its schedule answers with
 * it.** The schedule charges interest for the days between payments, so
 * `LoanMath` has to step its own periods in the same months the rule does or the
 * timeline and the loan's own table land on different days — [addMonths] is
 * public for exactly that, and is the one place either of them steps a month.
 * A loan carries its own copy of the answer (`loan.recur_in_bs`) beside the
 * rule's, the way a plan does, because every reader of its dates is a pure
 * function of the loan row. It used to be Gregorian for everybody, on the
 * reasoning that a bank debits on the 20th of the English month — true of an
 * English statement and simply wrong for a borrower whose diary is in Bikram
 * Sambat.
 *
 * **The flag is stored, never read from the display setting.** This used to add
 * months in BS whenever the user happened to be reading BS, so turning the
 * calendar setting on or off silently moved every future payment of every rule.
 * The setting decides what a *new* rule is written in; after that the rule
 * remembers, and drawing it in the other calendar changes nothing about when the
 * money moves.
 *
 * Weeks are weeks in both calendars, so only the month steps differ.
 */
object Recurrence {

    /**
     * Occurrence dates from [from] to [to] inclusive.
     *
     * Steps forward from the series start rather than counting backwards from
     * today, so the day-of-month is always derived from the same anchor and
     * cannot drift.
     */
    fun occurrencesBetween(
        start: LocalDate,
        interval: RecurrenceInterval,
        from: LocalDate,
        to: LocalDate,
        endOn: LocalDate? = null,
        everyMonths: Int? = null,
        inBikramSambat: Boolean = false,
    ): List<LocalDate> {
        if (to < start) return emptyList()
        val limit = listOfNotNull(to, endOn).min()
        if (limit < start) return emptyList()

        val dates = mutableListOf<LocalDate>()
        var index = 0
        while (index <= MAX_OCCURRENCES) {
            val date = nth(start, interval, index, everyMonths, inBikramSambat)
            if (date > limit) break
            if (date >= from) dates += date
            index++
            // The bound is a guard, not a limit anyone should reach: a corrupt
            // interval must not spin forever inside a Flow the UI is waiting on.
        }
        return dates
    }

    /** The next occurrence strictly after [after], or null once the series ends. */
    fun nextAfter(
        start: LocalDate,
        interval: RecurrenceInterval,
        after: LocalDate,
        endOn: LocalDate? = null,
        everyMonths: Int? = null,
        inBikramSambat: Boolean = false,
    ): LocalDate? {
        var index = 0
        while (index <= MAX_OCCURRENCES) {
            val date = nth(start, interval, index, everyMonths, inBikramSambat)
            if (date > after) return if (endOn != null && date > endOn) null else date
            index++
        }
        return null
    }

    /**
     * The [index]-th occurrence, counting the start as index 0.
     *
     * @param everyMonths a plain count of months between occurrences, which wins
     *   over [interval] when it is given. The four named monthly intervals cover
     *   what most rules do, but a loan is repaid on whatever gap the two parties
     *   agreed — every two months, every five, or once at the end of the term —
     *   and a gap the enum cannot say used to fall back to monthly and silently
     *   bill the borrower four times too often.
     */
    fun nth(
        start: LocalDate,
        interval: RecurrenceInterval,
        index: Int,
        everyMonths: Int? = null,
        inBikramSambat: Boolean = false,
    ): LocalDate {
        if (everyMonths != null && everyMonths > 0) {
            return addMonths(start, everyMonths.toLong() * index, inBikramSambat)
        }
        return nthOfInterval(start, interval, index, inBikramSambat)
    }

    private fun nthOfInterval(
        start: LocalDate,
        interval: RecurrenceInterval,
        index: Int,
        inBikramSambat: Boolean,
    ): LocalDate = when (interval) {
        // A week is seven days in either calendar, so these never branch.
        RecurrenceInterval.WEEKLY -> start.plusWeeks(index.toLong())
        RecurrenceInterval.FORTNIGHTLY -> start.plusWeeks(2L * index)
        RecurrenceInterval.MONTHLY -> addMonths(start, index.toLong(), inBikramSambat)
        RecurrenceInterval.QUARTERLY -> addMonths(start, 3L * index, inBikramSambat)
        RecurrenceInterval.HALF_YEARLY -> addMonths(start, 6L * index, inBikramSambat)
        RecurrenceInterval.YEARLY -> addMonths(start, 12L * index, inBikramSambat)
    }

    /**
     * [start] moved on by [months] months of whichever calendar the rule counts
     * in, as a Gregorian date — which is the only thing anything stores.
     *
     * The day of the month is kept and **clamped to the length of the month it
     * lands in**, in both calendars: java.time already does it (31 January plus
     * a month is 28 or 29 February), and Bikram Sambat needs it more, its months
     * running anywhere from 29 to 32 days. Without the clamp a rule anchored on
     * 32 Jestha would slide into the next month and take every occurrence after
     * it along.
     *
     * Falls back to Gregorian rather than throwing when the date or the year it
     * would land in is outside the converter's table. A rule stepping past 2099
     * BS is a projection nobody will live to see; refusing to draw the timeline
     * at all would be the larger bug.
     */
    fun addMonths(start: LocalDate, months: Long, inBikramSambat: Boolean): LocalDate {
        if (!inBikramSambat || months == 0L) return start.plusMonths(months)
        if (!BikramSambat.supports(start)) return start.plusMonths(months)
        val bs = BikramSambat.fromGregorian(start)
        val absolute = bs.year * 12L + (bs.month - 1L) + months
        val year = Math.floorDiv(absolute, 12L).toInt()
        val month = Math.floorMod(absolute, 12L).toInt() + 1
        if (!BikramSambat.supports(year)) return start.plusMonths(months)
        val day = minOf(bs.day, BikramSambat.daysInMonth(year, month))
        return runCatching { BikramSambat.toGregorian(BsDate(year, month, day)) }
            .getOrElse { start.plusMonths(months) }
    }

    private const val MAX_OCCURRENCES = 5_000
}
