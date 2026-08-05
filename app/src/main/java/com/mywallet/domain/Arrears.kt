package com.mywallet.domain

import java.time.LocalDate

/**
 * The instalments a schedule was owed and never got.
 *
 * An instalment can be swiped away — from the timeline, from the account it left,
 * from the debt's own statement — and what that says is that the payment never
 * happened. The principal it would have cleared is still owed, so the balance
 * holds where it was and interest goes on running on it day by day; and the money
 * itself does not evaporate, it is simply late. So the **next** scheduled payment
 * collects it: an instalment of रू 10,000 dropped from July leaves August asking
 * for रू 20,000, and dropping August in turn leaves September asking for रू 30,000.
 *
 * That is one rule with three places to be obeyed, and they have to agree to the
 * paisa or the app contradicts itself on one screen: the remaining-payments table
 * ([LoanMath.schedule]), the row the timeline draws for a date still to come, and
 * the row `materialiseDue` writes when that date arrives.
 *
 * ## Which period was missed, rather than how many
 *
 * The set matters and not just the count, because a missed period is a
 * *permanent* fact about the schedule: its own interest was never collected, and
 * the payment that eventually catches up collects two periods' interest and
 * clears correspondingly less principal. Once August has paid its रू 20,000 the
 * arrears are settled, but July is still a period that charged interest and paid
 * nothing — and a balance recomputed as though it had never happened would be a
 * few rupees light for the rest of the loan.
 *
 * It is read from the rows rather than remembered anywhere: a date the user threw
 * away leaves a tombstone behind, and a scheduled date with no *surviving* row on
 * it is exactly a period that went unpaid. Nothing has to be written down for
 * this to stay true across a restore or a reinstall.
 */
data class Arrears(
    /**
     * Which periods went unpaid, numbered from 1 at the first instalment the
     * current balance is counted from — the same numbering
     * [LoanMath.schedule]'s rows carry, so a set member indexes a row.
     */
    val missed: Set<Int> = emptySet(),
    /**
     * How many instalments the **next** payment has to collect on top of its own.
     *
     * Only the unbroken run of missed periods at the end counts. Anything before
     * a period that was actually paid has already been caught up: that payment
     * carried it, which is the whole point of rolling it forward.
     *
     * And **less whatever is already dated forward**. Somebody who says "I will
     * pay July's on the 20th" has answered the question this figure exists to
     * ask, so piling it onto the next scheduled payment as well would demand the
     * money twice. It is deliberately the only thing a forward-dated payment
     * moves: [missed] is the schedule as it stands *today*, and a payment that
     * has not happened has cleared no principal — the same rule that keeps a
     * salary banked for the 3rd out of today's balance.
     */
    val carriedForward: Int = 0,
    /**
     * How many of the schedule's periods have fallen due in all — the missed
     * ones included, since a period that went unpaid still ran.
     *
     * It comes back with the arrears because it is the same walk over the same
     * dates, and because the two are only ever right together: this is how many
     * rows of the schedule are behind us, and [missed] says which of them
     * charged interest and collected nothing.
     */
    val periodsDue: Int = 0,
) {
    val isEmpty: Boolean get() = missed.isEmpty()

    companion object {
        val NONE = Arrears()

        /**
         * What a schedule is owed, from the dates it was due on and the days it
         * was actually paid on.
         *
         * @param dueDates every scheduled date that has fallen due, in order,
         *   counted from the day the current balance started running.
         * @param paidDays the days a surviving payment of this rule sits on.
         * @param paidCount how many surviving payments there are in all.
         * @param paidLater payments already dated **after** the day being asked
         *   about — money the user has said is going out but which has not gone
         *   yet. They settle no period today and so change nothing about
         *   [missed]; they come off [carriedForward], because the next scheduled
         *   payment must not ask again for what is already promised.
         *
         * The count is asked for separately, and it is what decides *how many*
         * periods are missing; [paidDays] only decides **which**. They can
         * disagree — an instalment whose date the user corrected by a day or two
         * sits on no scheduled date at all — and in that case the money is
         * plainly there and nothing is owed. Trusting the days alone would tell
         * a borrower who paid two days late that they owed double next month.
         */
        fun of(
            dueDates: List<LocalDate>,
            paidDays: Set<Long>,
            paidCount: Int,
            paidLater: Int = 0,
        ): Arrears {
            val shortfall = dueDates.size - paidCount
            if (shortfall <= 0) return Arrears(periodsDue = dueDates.size)
            val unpaid = dueDates.mapIndexedNotNull { index, date ->
                (index + 1).takeIf { date.toEpochDay() !in paidDays }
            }
            // The latest of them, where the two answers disagree: a payment that
            // landed off its own date belongs to the run that has been settled,
            // not to the one still outstanding.
            val missed = unpaid.takeLast(shortfall).toSet()
            // The tail: how far back from the last period due the misses run
            // without a paid one interrupting them.
            var carried = 0
            for (period in dueDates.size downTo 1) {
                if (period !in missed) break
                carried++
            }
            return Arrears(
                missed = missed,
                carriedForward = (carried - paidLater).coerceAtLeast(0),
                periodsDue = dueDates.size,
            )
        }
    }
}
