package com.mywallet.domain

import com.mywallet.core.money.Money
import java.time.LocalDate

/**
 * A policy: premiums going out for an agreed length, one payout at the end.
 *
 * There is no arithmetic here of the kind a deposit or a loan has, and that is
 * deliberate. What a policy pays out is not worked out from what is paid into it
 * — an endowment hands back more than the premiums, a term plan hands back
 * nothing at all, and the split between the two is the insurer's business and
 * not a rate the user could type in. Both figures are printed on the policy
 * document, so both are asked for, and the app's job is to say when each one
 * moves rather than to second-guess either.
 *
 * What it does work out is the schedule those two facts imply: how many premiums
 * there are, when each falls, and what they come to in all. Every one of those
 * is counted rather than assumed, because a policy paid yearly for eighteen
 * months is a real arrangement and dividing months by months would lose the last
 * premium.
 */
object Insurance {

    /**
     * The terms of one policy.
     *
     * [startedOn] is the day it was taken out, which is also the day the first
     * premium falls: an insurer takes the first one to put the policy on risk.
     * [maturesOn] falls out of it, so no second column can disagree about when
     * the money comes back.
     */
    data class Terms(
        val premium: Money,
        val maturityAmount: Money,
        val startedOn: LocalDate,
        val termMonths: Int,
        /** Months between premiums: 1 monthly, 3 quarterly, 12 yearly. */
        val everyMonths: Int,
        /**
         * Whether the schedule counts Nepali months, matching the rule that
         * moves the money. Stored on the holding so every reader of these terms
         * gets the same answer as the rule without having to fetch it.
         */
        val inBikramSambat: Boolean = false,
    ) {
        /** The dates, which a policy shares with every other periodic plan. */
        val plan: PeriodicPlan
            get() = PeriodicPlan(startedOn, termMonths, everyMonths, inBikramSambat)

        /** The day the insurer hands over the maturity amount. */
        val maturesOn: LocalDate get() = plan.endsOn

        /** How many premiums the term holds, counting the one paid on day one. */
        val payments: Int get() = plan.payments

        /** The last day a premium falls, which is where the rule stops. */
        val lastPaymentOn: LocalDate? get() = plan.lastPaymentOn

        /** Every premium added up — what the policy costs over its whole life. */
        val totalPremiums: Money get() = Money(premium.minor * payments)

        /**
         * What the policy hands back over and above what is paid into it —
         * negative where it hands back less, which is what a term plan does.
         *
         * Not a rate and deliberately not presented as one: it is the difference
         * between two figures off the document, and the only reason it is
         * computed here rather than left to the reader is that the two are a
         * hundred and twenty premiums apart. Without it the card gave the
         * premium, the total and the payout and left the one question a policy
         * is bought on — is this worth it — to be done in the user's head.
         */
        val gain: Money get() = maturityAmount - totalPremiums

        /** The day each premium falls, in order, for the table under the card. */
        fun paymentDates(): List<LocalDate> = plan.paymentDates()
    }
}
