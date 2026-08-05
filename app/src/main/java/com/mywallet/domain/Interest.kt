package com.mywallet.domain

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** A rate the bank moved to, and the day it started applying. */
data class RateChange(val effectiveFrom: LocalDate, val annualRate: Double)

/**
 * What a holding's rate has been, day by day.
 *
 * A bank rate is not a property of an account, it is a property of a *period*.
 * Nepali banks review savings and floating-rate loans quarterly, so over the
 * life of a seven-year loan the rate on file is the current one and every
 * figure ever computed from it — the balance, the interest already charged, the
 * term — was computed at some other rate. Storing only the latest silently
 * rewrites history every time the bank moves.
 *
 * [base] is the rate the holding opened at; each change overrides it from its
 * own day onwards. Changes dated before the base are still honoured, because
 * the day a user records them is not the day they took effect.
 */
class RateSchedule(
    private val base: Double,
    changes: List<RateChange> = emptyList(),
) {
    private val ordered = changes.sortedBy { it.effectiveFrom }

    /** The rate in force on [date]. */
    fun on(date: LocalDate): Double =
        ordered.lastOrNull { !it.effectiveFrom.isAfter(date) }?.annualRate ?: base

    /** True when the rate never moves, which is the ordinary case. */
    val isFixed: Boolean get() = ordered.isEmpty()

    /**
     * Whether anything is ever charged on this at all.
     *
     * Not the same question as "what is the rate now". A debt written down with
     * no rate and given one later has nothing on the holding itself — the rate
     * it *opened* at is still none — and everything it charges lives in the
     * dated changes. Asking only the base figure said such a debt was
     * interest-free for as long as it existed.
     */
    val everCharged: Boolean get() = base > 0.0 || ordered.any { it.annualRate > 0.0 }

    /**
     * Interest on [balance] from [from] (excluded) through [to] (included),
     * charged at whatever rate was in force on each of those days.
     *
     * Split at the changes rather than averaged: a rate that rises on the 15th
     * costs the old rate for a fortnight and the new one for a fortnight, and a
     * mean of the two is only right when the change lands exactly halfway.
     */
    fun interest(balance: Money, from: LocalDate, to: LocalDate): Money {
        if (balance.minor <= 0L || !from.isBefore(to)) return Money.ZERO
        var total = 0.0
        // [from] is excluded and [to] included, so the first day charged is the
        // one after [from] and that is the day whose rate opens the run.
        var start = from
        while (start.isBefore(to)) {
            val firstDay = start.plusDays(1)
            val rate = on(firstDay)
            // This run ends the day before the next change takes effect, so the
            // day a new rate starts is charged at the new rate and not the old.
            val next = ordered.firstOrNull { it.effectiveFrom.isAfter(firstDay) }
                ?.effectiveFrom?.minusDays(1)
            val end = if (next != null && next.isBefore(to)) next else to
            total += balance.minor * rate / 100.0 * ChronoUnit.DAYS.between(start, end) / 365.0
            start = end
        }
        return Money(total.roundToLong())
    }

    /** The rate to quote when only one number will fit. */
    fun latest(): Double = ordered.lastOrNull()?.annualRate ?: base

    /** Days strictly inside `(from, to)` where the rate changes, for splitting a span. */
    fun boundaries(from: LocalDate, to: LocalDate): List<LocalDate> =
        ordered.map { it.effectiveFrom }.filter { it.isAfter(from) && it.isBefore(to) }
}

/**
 * A day a balance changed, and what it changed by. Signed: positive in.
 *
 * Deliberately not [BalanceChange], which describes a *debt* moving. The sign
 * convention is the opposite one and mixing them up would pay interest on money
 * that had already been spent.
 */
data class BalanceDay(val date: LocalDate, val deltaMinor: Long)

/**
 * Interest a savings account earns, and the days the bank pays it out.
 *
 * The year is cut into fixed periods of whole months, and each pays **its own
 * share of the annual rate** — at the usual three months, 9% a year is 2.25% a
 * quarter, not 9% × 92÷365. A period runs from the 1st of a month to the day
 * before the next period opens, and the interest lands on that next 1st.
 *
 * **Whose months, is the calendar the user reads.** This is the one place in the
 * app where the calendar decides when money *moves* rather than only how a day
 * is drawn, so it is the one place that has to be told which one — every
 * function that counts a year or opens a month takes a [CalendarSystem] and
 * none of them may assume. Somebody reading Bikram Sambat has periods opening on
 * 1 Baisakh, 1 Shrawan, 1 Kartik, 1 Magh; somebody reading Gregorian has them
 * opening on 1 January, 1 April, 1 July, 1 October. Both are the same rule said
 * in the reader's own months, and a passbook is checked against whichever of the
 * two its owner keeps their diary in.
 *
 * Switching the setting therefore moves the payout days, exactly as changing the
 * interval does — and the answer is the same one: [postDueInterest] works the
 * whole history out again in the calendar now in force and sweeps the postings
 * left on days that are no longer payout days. Keeping the old credits beside
 * the new ones would pay the same days twice, because a Gregorian quarter and a
 * Nepali one overlap rather than abut.
 *
 * **How long a period is, is the user's answer and not this file's.** Banks do
 * not agree: quarterly is the common Nepali arrangement and the default here, but
 * monthly, half-yearly and yearly all exist, and an app that hardcoded one told
 * three users in four a figure their passbook disagreed with. [everyMonths] comes
 * from Settings and is threaded through every function that has an opinion about
 * it — the payout days, the day a period opens, and the slice of the year it
 * pays. Nothing here may assume three.
 *
 * **The year always restarts at its own first month** — 1 Baisakh in Bikram
 * Sambat, 1 January in Gregorian. Periods are counted from there, so a gap that
 * does not divide twelve — every five months, say — leaves a short period at the
 * end of the year rather than drifting across the calendar for ever. That short
 * one pays what it is worth: its own months over twelve. It is the same rule the
 * whole-year case follows, said once.
 *
 * Within a period, money earns for the share of it that it was actually there.
 * रू 1,00,000 deposited halfway through a 92-day quarter earns 2.25% of half of
 * it — which is why every movement's date matters and why the app keeps them
 * whether or not it ever shows them. The period's own length in days is the
 * denominator, so a 90-day quarter and a 92-day one both pay exactly a quarter of
 * the year's rate on money held throughout.
 *
 * Deliberately **not** the day-count convention loans use (`rate ÷ 365 × days`).
 * A loan accrues; a savings period is a fixed slice of the year, and the two
 * disagree by about a quarter of a percent of the interest — small, and wrong.
 */
object SavingsInterest {

    /**
     * Quarterly, which is what Nepali banks do unless they say otherwise — and
     * what this app did for everyone before the interval could be asked for.
     */
    const val DEFAULT_EVERY_MONTHS = 3

    /**
     * A year, and no longer. The period's rate is a slice of the *annual* one,
     * so a gap longer than a year has no slice to be: twelve months is the whole
     * of it, paid once. Below it, one month is the shortest thing a calendar
     * built out of months can say.
     */
    const val MAX_EVERY_MONTHS = 12
    const val MIN_EVERY_MONTHS = 1

    /** [months] as a gap this can actually work in. */
    fun gapOf(months: Int): Int = months.coerceIn(MIN_EVERY_MONTHS, MAX_EVERY_MONTHS)

    /**
     * The months a period's interest is paid at the start of, numbered within
     * whichever calendar is in force.
     *
     * Counted from the first month of the year so the year opens a period rather
     * than landing in the middle of one. At the default three that is the
     * familiar 1, 4, 7, 10 — Baisakh, Shrawan, Kartik, Magh in Bikram Sambat;
     * January, April, July, October in Gregorian.
     */
    fun payoutMonths(everyMonths: Int): List<Int> {
        val gap = gapOf(everyMonths)
        return generateSequence(1) { it + gap }.takeWhile { it <= 12 }.toList()
    }

    /**
     * Every payout day after [after] and on or before [through].
     *
     * Half-open at the bottom so a day already paid is not paid twice, which is
     * what makes the caller's watermark safe to advance to the last one.
     */
    fun payoutsBetween(
        after: LocalDate,
        through: LocalDate,
        everyMonths: Int = DEFAULT_EVERY_MONTHS,
        calendar: CalendarSystem = CalendarSystem.BIKRAM_SAMBAT,
    ): List<LocalDate> {
        if (!after.isBefore(through)) return emptyList()
        val start = yearOf(after, calendar) ?: return emptyList()
        val end = yearOf(through, calendar) ?: return emptyList()
        val months = payoutMonths(everyMonths)
        val days = mutableListOf<LocalDate>()
        for (year in start..end) {
            for (month in months) {
                val day = startOfMonth(year, month, calendar) ?: continue
                if (day.isAfter(after) && !day.isAfter(through)) days += day
            }
        }
        return days.sorted()
    }

    /**
     * The day the period paid on [payout] opened: the payout day before it.
     *
     * Not simply "[everyMonths] months earlier". Periods are counted from the
     * first month and start again each year, so where the gap does not divide
     * twelve the first period of a year is preceded by the short one that closed
     * the last — stepping back by the gap would land in the middle of it and
     * charge days twice.
     */
    fun periodStart(
        payout: LocalDate,
        everyMonths: Int = DEFAULT_EVERY_MONTHS,
        calendar: CalendarSystem = CalendarSystem.BIKRAM_SAMBAT,
    ): LocalDate? {
        val (year, month) = previousPayout(payout, everyMonths, calendar) ?: return null
        return startOfMonth(year, month, calendar)
    }

    /**
     * How many months the period paid on [payout] covers — which is the share of
     * the annual rate it pays, over twelve.
     *
     * Usually [everyMonths]; less where the year ran out first. See [periodStart].
     */
    fun periodMonths(
        payout: LocalDate,
        everyMonths: Int = DEFAULT_EVERY_MONTHS,
        calendar: CalendarSystem = CalendarSystem.BIKRAM_SAMBAT,
    ): Int? {
        val (_, month) = previousPayout(payout, everyMonths, calendar) ?: return null
        val closing = monthOf(payout, calendar) ?: return null
        return if (month < closing) closing - month else 12 - month + closing
    }

    /** The year and month of the payout before [payout], in [calendar]. */
    private fun previousPayout(
        payout: LocalDate,
        everyMonths: Int,
        calendar: CalendarSystem,
    ): Pair<Int, Int>? {
        val year = yearOf(payout, calendar) ?: return null
        val thisMonth = monthOf(payout, calendar) ?: return null
        val months = payoutMonths(everyMonths)
        val index = months.indexOf(thisMonth)
        if (index < 0) return null
        // The first period of a year is preceded by the last of the one before,
        // whatever length that happened to be.
        val openedIn = if (index == 0) year - 1 else year
        val month = if (index == 0) months.last() else months[index - 1]
        if (!supportsYear(openedIn, calendar)) return null
        return openedIn to month
    }

    /**
     * The three questions this file asks of a calendar, and the whole of what
     * changes between the two.
     *
     * Bikram Sambat is bounded by the table the picker ships with, so it answers
     * null outside it and every caller already treats that as "no period here" —
     * the same answer they got before any of this took a calendar. Gregorian has
     * no such edge.
     */
    private fun yearOf(date: LocalDate, calendar: CalendarSystem): Int? = when (calendar) {
        CalendarSystem.BIKRAM_SAMBAT ->
            if (BikramSambat.supports(date)) BikramSambat.fromGregorian(date).year else null
        CalendarSystem.GREGORIAN -> date.year
    }

    private fun monthOf(date: LocalDate, calendar: CalendarSystem): Int? = when (calendar) {
        CalendarSystem.BIKRAM_SAMBAT ->
            if (BikramSambat.supports(date)) BikramSambat.fromGregorian(date).month else null
        CalendarSystem.GREGORIAN -> date.monthValue
    }

    private fun startOfMonth(year: Int, month: Int, calendar: CalendarSystem): LocalDate? =
        when (calendar) {
            CalendarSystem.BIKRAM_SAMBAT ->
                if (BikramSambat.supports(year)) {
                    runCatching { BikramSambat.startOfMonth(year, month) }.getOrNull()
                } else {
                    null
                }
            CalendarSystem.GREGORIAN -> runCatching { LocalDate.of(year, month, 1) }.getOrNull()
        }

    private fun supportsYear(year: Int, calendar: CalendarSystem): Boolean = when (calendar) {
        CalendarSystem.BIKRAM_SAMBAT -> BikramSambat.supports(year)
        CalendarSystem.GREGORIAN -> true
    }

    /**
     * What a period pays: its share of the annual rate, on the balance held,
     * weighted by how much of the period it was held for.
     *
     * The span is half-open — [periodStart] earns, [payout] does not, because
     * that day opens the next period. [opening] is the balance on the first day;
     * [changes] are every movement the account has ever seen, and only the ones
     * inside the period are used.
     *
     * [monthsInPeriod] over twelve is the slice of the year being paid, and it is
     * asked for rather than derived from the two dates: the dates are stored
     * Gregorian whatever calendar the period was cut in, and a period is a whole
     * number of *that calendar's* months however many days that turns out to be.
     * See [periodMonths].
     *
     * Days the account is overdrawn earn nothing rather than costing something:
     * an overdrawn savings account is the bank's business, not a second rate the
     * app can invent. Days before the account had a rate on file earn nothing
     * either, which falls out of the rate being zero on them.
     */
    fun earned(
        opening: Money,
        changes: List<BalanceDay>,
        rates: RateSchedule,
        periodStart: LocalDate,
        payout: LocalDate,
        monthsInPeriod: Int = DEFAULT_EVERY_MONTHS,
    ): Money {
        val periodDays = ChronoUnit.DAYS.between(periodStart, payout)
        if (periodDays <= 0L) return Money.ZERO
        val yearShare = gapOf(monthsInPeriod) / 12.0

        val moves = changes
            .filter { it.date.isAfter(periodStart) && it.date.isBefore(payout) }
            .groupBy { it.date }
        // Every day the sum being paid on changes: money moving, or the rate
        // moving. Between two of them nothing varies, so one multiplication does.
        val breaks = (moves.keys + rates.boundaries(periodStart, payout)).distinct().sorted()

        var balance = opening
        var day = periodStart
        var total = 0.0
        fun run(to: LocalDate) {
            if (balance.minor <= 0L) return
            val share = ChronoUnit.DAYS.between(day, to).toDouble() / periodDays
            total += balance.minor * rates.on(day) / 100.0 * yearShare * share
        }
        for (point in breaks) {
            run(point)
            moves[point]?.let { rows -> balance = Money(balance.minor + rows.sumOf { it.deltaMinor }) }
            day = point
        }
        run(payout)
        return Money(total.roundToLong())
    }
}
