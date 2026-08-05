package com.mywallet.domain

import com.mywallet.core.money.Money
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * What a fixed deposit will be worth when it comes free.
 *
 * **Simple interest, on the whole term**: `principal × rate ÷ 100 × years`,
 * where the years are the agreed length. Nothing compounds, so the interest
 * never earns interest of its own and the figure at the end is the deposit plus
 * one multiplication.
 *
 * That is deliberately the bank's own arithmetic rather than a better one. It is
 * what Everest Bank's fixed-deposit calculator computes — its script offers a
 * compounding branch and then calls itself with compounding switched off — and a
 * deposit is the one holding where the user has a printed figure from the bank
 * to check the app against. An app that quietly returned more than the
 * certificate says would be telling them their bank had short-changed them.
 *
 * This is why interest here is **not** [SavingsInterest]'s quarter of the annual
 * rate, and not the loan side's `rate ÷ 365` a day. Three products, three
 * conventions, each the one its own bank actually uses.
 *
 * There is deliberately no "what has it accrued so far". A deposit holds what
 * was put into it until the day it comes free, and the interest arrives in one
 * piece on that day — that is what makes it a fixed deposit rather than a
 * savings account. A figure creeping up day by day looked precise and was
 * simply a different arrangement.
 */
object FixedDeposit {

    private const val MONTHS_IN_YEAR = 12.0

    /**
     * The terms of one deposit.
     *
     * [startedOn] is the day the money went in — the fact the user has — and
     * [maturesOn] falls out of it. Storing the day it comes free as well would
     * give two columns the chance to disagree about one arrangement.
     */
    data class Terms(
        val principal: Money,
        val annualRate: Double,
        val startedOn: LocalDate,
        val termMonths: Int,
        /**
         * Whether the agreed length is counted in Nepali months.
         *
         * A bank writing a receipt for "six months from 1 Shrawan" means
         * 1 Magh, not the day six English months lands on — the two are days
         * apart, and the day it comes free is the one figure the certificate
         * states outright. Off on every deposit taken before the app asked, so
         * none of their maturity dates move.
         */
        val inBikramSambat: Boolean = false,
    ) {
        /** The day it comes free: the day it went in, plus the agreed length. */
        val maturesOn: LocalDate
            get() = Recurrence.addMonths(startedOn, termMonths.toLong(), inBikramSambat)
    }

    /** Everything it will have earned by the day it comes free. */
    fun totalInterest(terms: Terms): Money {
        if (terms.principal.minor <= 0L || terms.termMonths <= 0) return Money.ZERO
        val years = terms.termMonths / MONTHS_IN_YEAR
        return Money(
            (terms.principal.minor * terms.annualRate / 100.0 * years).roundToLong()
        )
    }

    /** The whole of it on the day it comes free — deposit and interest together. */
    fun maturityValue(terms: Terms): Money = terms.principal + totalInterest(terms)
}
