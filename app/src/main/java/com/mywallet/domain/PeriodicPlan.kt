package com.mywallet.domain

import com.mywallet.data.db.entity.RecurrenceInterval
import java.time.LocalDate

/**
 * Money moving on a fixed rhythm for an agreed length: a policy's premiums, a
 * goal's contributions.
 *
 * Both arrangements ask the same three questions — when did it start, how long
 * does it run, how often does money move — and every date either of them needs
 * falls out of the answers. Written once here rather than twice, because the two
 * counting rules below are each easy to get wrong by one and a second copy is a
 * second chance to.
 */
data class PeriodicPlan(
    val startedOn: LocalDate,
    val termMonths: Int,
    /** Months between payments: 1 monthly, 3 quarterly, 12 yearly. */
    val everyMonths: Int,
    /**
     * Whether a "month" here is a Nepali one, matching the rule that moves the
     * money — see [Recurrence].
     *
     * It has to be the same answer as the rule's, and for a sharper reason than
     * tidiness: the rule *stops* at [lastPaymentOn], computed here. Counted in
     * the other calendar that day lands a fortnight from where the rule's own
     * last occurrence falls, and the final premium is either dropped or paid
     * into a policy that has already matured.
     */
    val inBikramSambat: Boolean = false,
) {

    /** The day the term runs out — the day it started plus its agreed length. */
    val endsOn: LocalDate get() = on(index = 1, gap = termMonths)

    /**
     * How many payments the term holds, counting the one on day one.
     *
     * Counted from the dates rather than by dividing the two lengths. A
     * three-year arrangement paid yearly has three payments and not two, and one
     * agreed for eighteen months has a second at twelve — the division drops it,
     * and the money silently stops moving while the arrangement is still
     * running.
     */
    val payments: Int
        get() = if (termMonths <= 0 || everyMonths <= 0) 0 else (termMonths - 1) / everyMonths + 1

    /** The last day money moves, which is where the repeating rule stops. */
    val lastPaymentOn: LocalDate?
        get() = payments.takeIf { it > 0 }?.let { on(index = it - 1, gap = everyMonths) }

    /** Every one of those days, in order, for the table the card opens. */
    fun paymentDates(): List<LocalDate> = (0 until payments).map { on(it, everyMonths) }

    /**
     * [startedOn] moved on by [index] gaps, in whichever calendar this plan
     * counts. Through [Recurrence] rather than a second copy of the arithmetic,
     * because the rule that actually moves the money steps with exactly that.
     */
    private fun on(index: Int, gap: Int): LocalDate = Recurrence.nth(
        start = startedOn,
        interval = RecurrenceInterval.MONTHLY,
        index = index,
        everyMonths = gap,
        inBikramSambat = inBikramSambat,
    )

    /** How many of them are still to come after [on]. */
    fun paymentsAfter(on: LocalDate): Int = paymentDates().count { it.isAfter(on) }
}
