package com.mywallet.domain

import com.mywallet.core.money.Money
import java.time.LocalDate

/**
 * Money put aside on purpose: a figure to reach, and a rhythm that reaches it.
 *
 * A goal is the mirror of a policy. There, two figures are facts off a document
 * and the app only counts the dates between them; here the user names one figure
 * — what they want to have — and the app works out what that costs each time.
 * Nothing else about it is invented: no interest, no return, no growth. A goal
 * holds exactly what has been put into it, which is what makes the progress bar
 * a fact rather than a forecast.
 *
 * The contribution is **rounded up**, and that is deliberate. A goal of
 * रू 1,00,000 over twelve months is रू 8,333.33 a month, and twelve of those
 * leave the user four paisa short of the thing they were saving for. Rounding up
 * overshoots by less than one minor unit per payment and reaches the goal, which
 * is the direction that cannot disappoint. What the plan actually comes to is
 * said in full ([total]) rather than hidden, so the few paisa are never a
 * surprise.
 */
object Goal {

    /**
     * The terms of one goal.
     *
     * [target] is what the user wants to have by the end; [startedOn] is the day
     * the first contribution goes in, since a plan that starts next month is a
     * plan that starts next month.
     */
    data class Terms(
        val target: Money,
        val startedOn: LocalDate,
        val termMonths: Int,
        /** Months between contributions: 1 monthly, 3 quarterly, 12 yearly. */
        val everyMonths: Int,
        /**
         * Whether the schedule counts Nepali months, matching the rule that
         * moves the money. Stored on the holding so every reader of these terms
         * gets the same answer as the rule without having to fetch it.
         */
        val inBikramSambat: Boolean = false,
    ) {
        /** The dates, shared with every other periodic plan. */
        val plan: PeriodicPlan
            get() = PeriodicPlan(startedOn, termMonths, everyMonths, inBikramSambat)

        /** The day the goal is meant to be reached. */
        val targetOn: LocalDate get() = plan.endsOn

        /** How many contributions the plan makes. */
        val payments: Int get() = plan.payments

        /** The last day money goes in, which is where the repeating rule stops. */
        val lastPaymentOn: LocalDate? get() = plan.lastPaymentOn

        /**
         * What to put aside each time — the one figure the app works out rather
         * than asks for. Rounded up so the last contribution reaches the goal
         * instead of stopping a few paisa short of it.
         */
        val perPayment: Money
            get() = if (payments <= 0 || !target.isPositive) {
                Money.ZERO
            } else {
                Money((target.minor + payments - 1) / payments)
            }

        /** What the plan comes to in all, which is the goal or a hair over it. */
        val total: Money get() = Money(perPayment.minor * payments)

        /** The day each contribution falls, for the table under the card. */
        fun paymentDates(): List<LocalDate> = plan.paymentDates()
    }

    /**
     * How far along a goal is, as a fraction of the way there.
     *
     * Clamped at both ends: a goal cannot be less than nothing done, and money
     * put aside beyond the target is still the goal reached rather than 130% of
     * a bar. The figures beside the bar say what actually happened; the bar
     * itself only has to be legible.
     */
    fun progress(saved: Money, target: Money): Float {
        if (!target.isPositive) return 0f
        return (saved.minor.toDouble() / target.minor.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** What is still to be put aside, and never a negative amount. */
    fun remaining(saved: Money, target: Money): Money =
        Money((target.minor - saved.minor).coerceAtLeast(0L))

    /**
     * How long the goal still needs, after money has been put in or taken out
     * outside the plan.
     *
     * This is the whole point of depositing early: the saving each time does not
     * change — it is what the user decided they can manage — so what gives is
     * the length. Money in means fewer contributions are left and the goal
     * arrives sooner; money out means more of them and it slips.
     *
     * Counted in payments and converted back, so the term and the schedule
     * cannot disagree: [paymentsDone] have already fallen due whatever happens
     * next, and the rest are however many it takes to close the gap. A deposit
     * big enough to finish the goal leaves none, and the term ends at the last
     * contribution that was actually made.
     */
    fun termAfter(
        saved: Money,
        target: Money,
        perPayment: Money,
        everyMonths: Int,
        paymentsDone: Int,
    ): Int {
        if (everyMonths <= 0) return 0
        val left = remaining(saved, target)
        val stillToPay = if (!perPayment.isPositive) {
            0
        } else {
            ((left.minor + perPayment.minor - 1) / perPayment.minor).toInt()
        }
        // Never less than one payment's worth of term: a goal that has been
        // fully funded on day one still ran for the period it was funded in,
        // and a term of zero months would leave a rule with nowhere to stop.
        return ((paymentsDone + stillToPay).coerceAtLeast(1)) * everyMonths
    }

    /**
     * The gap between payments that a chosen contribution implies.
     *
     * The other half of the pair the form offers: the user may say what they can
     * manage each time instead of how often, and the app answers with the rhythm
     * that reaches the same goal in the same length of time. Rounded to whole
     * months because a schedule cannot fall every 1.2 months — so a typed figure
     * snaps to what its nearest rhythm actually produces, which is the figure
     * the card then quotes.
     */
    fun everyMonthsFor(target: Money, perPayment: Money, termMonths: Int): Int {
        if (termMonths <= 0 || !perPayment.isPositive || !target.isPositive) return 1
        val needed = ((target.minor + perPayment.minor - 1) / perPayment.minor).toInt()
        // Never more payments than there are months to make them in, and never
        // fewer than one.
        val payments = needed.coerceIn(1, termMonths)
        return (termMonths / payments).coerceAtLeast(1)
    }
}
