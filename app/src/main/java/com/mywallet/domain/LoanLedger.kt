package com.mywallet.domain

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.LoanPart
import java.time.LocalDate

/**
 * What one dated movement against a loan actually did to it.
 *
 * The five are not shades of one thing. Two of them bring the balance down, one
 * deliberately leaves it exactly where it was, and two put it up — and a list
 * that drew them all as "a payment" would let a user read money going out as
 * progress when half of it bought nothing but time.
 */
enum class LoanMovementKind {
    /** One instalment of the loan's own repeating rule. */
    INSTALMENT,

    /** A lump sum, deliberately all principal. */
    PRINCIPAL,

    /**
     * Interest serviced on its own. The money left and the balance did not move,
     * which is the whole point of servicing interest rather than paying it off.
     */
    INTEREST,

    /** Money added to what is owed: drawn on an overdraft, or more lent or borrowed. */
    INCREASE,

    /**
     * Money spent straight from a card, which is both at once: it is spending,
     * and it is the debt growing by the same figure.
     *
     * Deliberately not an [INCREASE]. A drawdown is money *arriving* somewhere —
     * you take रू 5,000 out of the facility and it lands in your bank — so it is
     * an adjustment, and a month lived on the overdraft must not read as a good
     * one. Buying groceries on the card is the opposite: nothing arrives, the
     * money is gone, and the month really was that expensive. It is the only
     * movement in the app that counts as spending *and* puts a balance up.
     *
     * Its price is that repaying the card must then count as nothing — see
     * `LoanRepository.repayOverdraft`. Counting both would say a रू 2,000 bag of
     * groceries cost रू 4,000.
     */
    SPEND,

    /**
     * The money that made the debt: what was handed over on the day it began.
     *
     * It moves the balance exactly as an increase does, and is deliberately not
     * one. "Borrowed more" on the row that *is* the borrowing reads as a debt
     * that has already grown past what was agreed, and it would be counted into
     * "so much more borrowed since it started" — which would then be the whole
     * of the loan.
     */
    OPENING;

    /**
     * Whether this was the user handing money over.
     *
     * The three that were: an instalment, a lump sum off the balance, and
     * interest serviced on its own — which includes the charge a bank takes for
     * the days between the money arriving and the first instalment. The two that
     * were not are the debt arriving and more borrowed on it, which move the
     * balance the other way.
     */
    val isPayment: Boolean
        get() = this == INSTALMENT || this == PRINCIPAL || this == INTEREST
}

/**
 * What removing one movement has to do to the loan's own stored figure.
 *
 * Only some of a debt's history is stored. What is owed on a schedule is worked
 * out from how many payments are on file, so removing an instalment corrects the
 * balance by itself; but a lump sum, a drawdown and money lent on an existing
 * arrangement each rewrote `principal_minor` in place, and removing one of those
 * rows without putting the figure back leaves the debt exactly as the payment
 * made it — the money back in the account and still off the balance.
 */
enum class MovementReversal {
    /** Nothing to do: the balance follows the rows that remain. */
    NONE,

    /** The money goes back on: this row had taken it off the debt. */
    ADD_BACK,

    /** It comes off: this row had added to what is owed. */
    TAKE_OFF,

    /**
     * The balance is untouched and only the account is forgotten.
     *
     * The debt arriving is money landing in an account, not a payment against
     * anything — the debt itself is unaffected by the record of where the money
     * went, which is why the field is optional in the first place.
     */
    FORGET_ACCOUNT,
}

/** What removing a movement of this kind means for the balance. */
fun LoanMovementKind.reversal(): MovementReversal = when (this) {
    LoanMovementKind.PRINCIPAL -> MovementReversal.ADD_BACK
    // Both put the debt up, so both come off it again. A purchase taken back
    // gives the card's headroom back with it, which is the figure the user is
    // actually watching.
    LoanMovementKind.INCREASE, LoanMovementKind.SPEND -> MovementReversal.TAKE_OFF
    LoanMovementKind.OPENING -> MovementReversal.FORGET_ACCOUNT
    // Interest serviced left the balance where it was, deliberately; an
    // instalment is counted rather than stored.
    LoanMovementKind.INTEREST, LoanMovementKind.INSTALMENT -> MovementReversal.NONE
}

/**
 * What makes the opening movement's id out of the loan's own.
 *
 * The row is told from a genuine top-up by this and nothing else — the two are
 * identical in shape, both being an adjustment carrying `loan_id` and no
 * `loan_part` — so everything that has to tell them apart derives the id the
 * same way. It also means the row can only ever be written once.
 */
const val LOAN_DISBURSEMENT_SUFFIX = "-disbursed"

/**
 * What makes an interest posting's id out of the account's own.
 *
 * Shared for the same reason the one above is: a screen has to be able to tell
 * the app's own working from anything the user wrote, and the id is the only
 * thing that says so. A statement row for interest credited to the account being
 * read would open the page it was tapped on.
 */
const val INTEREST_POSTING_SUFFIX = "-interest-"

/**
 * A confirmed entry against a loan, before the ledger works out what it did.
 *
 * Deliberately flat and free of Room: what a payment meant is arithmetic, and
 * arithmetic in this codebase lives where a unit test can reach it.
 */
data class LoanEntryFact(
    val entryId: String,
    val date: LocalDate,
    /** Ties the order of two payments recorded for the same day. */
    val createdAt: Long,
    /** As entered — always positive; the kind carries the sign. */
    val amount: Money,
    val currencyCode: String,
    /** The same amount in the display currency, at the rate locked that day. */
    val baseAmount: Money,
    val isAdjustment: Boolean,
    /** Set only when the payment was deliberately all principal or all interest. */
    val part: LoanPart?,
    /**
     * True for the one row that is the debt itself arriving — the money handed
     * over on the day it was borrowed or lent. Known from its id, which is
     * derived from the loan's, rather than guessed from its shape: it is
     * otherwise indistinguishable from more borrowed on the same arrangement.
     */
    val isOpening: Boolean = false,
    /** True for money spent straight from a card — see [LoanMovementKind.SPEND]. */
    val isSpend: Boolean = false,
    /** True when this row came from the loan's own repeating rule. */
    val fromSeries: Boolean,
    val accountId: String?,
    val accountName: String?,
    val note: String?,
)

/** One row of the ledger: what happened, and what it left owing. */
data class LoanMovement(
    val entryId: String,
    val date: LocalDate,
    val kind: LoanMovementKind,
    val amount: Money,
    val currencyCode: String,
    val baseAmount: Money,
    /**
     * The holding this movement passed through, so the row can open it.
     *
     * Null on a payment that names none — one the app generated for a day before
     * it was told about the debt, or a lump sum handed over in cash the user
     * never set an account up for. Those rows lead nowhere, which is the honest
     * answer: there is no page for money that left nothing.
     */
    val accountId: String?,
    val accountName: String?,
    val note: String?,
    /**
     * What an instalment was made of. Only ever filled from the amortisation
     * schedule, because that is the only thing that knows: the payment figure
     * alone cannot say how much of it the lender kept.
     */
    val principalPart: Money? = null,
    val interestPart: Money? = null,
    /**
     * What was owed once this had happened, or null when the app cannot say.
     *
     * Null is a real answer here rather than a gap to fill in. A loan re-based
     * by a lump sum has no record of the balance it used to carry, and a payment
     * made in some other currency would need that day's rate to be applied to a
     * debt in this one — inventing either would be a confident wrong number in
     * the one column a user checks against their lender's own statement.
     */
    val balanceAfter: Money? = null,
) {
    /** True when this put the debt up rather than down. */
    val increases: Boolean
        get() = kind == LoanMovementKind.INCREASE ||
            kind == LoanMovementKind.OPENING ||
            kind == LoanMovementKind.SPEND
}

/**
 * The statement a loan can produce for itself: every payment, receipt and
 * increase, in order, each with what it left owing.
 *
 * The balance column is worked out two entirely different ways, because a loan
 * keeps its balance two entirely different ways:
 *
 *  - A loan on an **amortisation schedule** is walked forwards through
 *    [LoanMath]. Most of an early instalment is interest, so subtracting the
 *    payments would clear the debt years early — the same reason
 *    `LoanRepository.outstandingOf` derives the current figure from the schedule
 *    rather than from the money that has gone out.
 *  - Everything else — a bare IOU, a loan due in one go, an overdraft — keeps a
 *    running total, so the ledger is walked **backwards** from what is owed
 *    today. It has to be backwards: a lump sum rewrites the loan's principal
 *    figure in place, so the amount it started at is no longer anywhere on file
 *    and only the present balance is a fact.
 */
object LoanLedger {

    /**
     * @param countingFrom the day the loan's current principal figure was set —
     *   `loan.started_on`. Instalments before it were paid against a balance
     *   that no longer exists, so the schedule cannot number them and they are
     *   listed without one.
     */
    fun of(
        loan: Loan,
        countingFrom: LocalDate,
        facts: List<LoanEntryFact>,
    ): List<LoanMovement> {
        val ordered = facts.sortedWith(compareBy({ it.date }, { it.createdAt }))
        val kinds = ordered.map { it.kind() }
        val balanceAfter = arrayOfNulls<Money>(ordered.size)
        val principal = arrayOfNulls<Money>(ordered.size)
        val interest = arrayOfNulls<Money>(ordered.size)

        if (loan.amortises) {
            val rows = LoanMath.schedule(
                principal = loan.principal,
                annualRatePercent = loan.annualRate ?: 0.0,
                termMonths = loan.termMonths ?: 0,
                emi = loan.emi,
                style = loan.style,
                monthsPerPayment = loan.paymentEveryMonths,
                // The same days the balance itself was worked out from, or the
                // column would disagree with the figure at the top of the screen.
                accrual = loan.accrual,
                // And the same periods it skipped. A period nobody paid is a row
                // of this schedule with nothing in it, so the payments that came
                // after it sit one row further down than they would otherwise —
                // and read against the wrong one, every balance below a deleted
                // instalment would be a month out.
                missed = loan.arrears.missed,
            )
            var paid = 0
            // Where the schedule stands as the walk goes down the list. Only an
            // instalment moves it; everything else is read against it.
            var running = loan.principal
            ordered.forEachIndexed { index, fact ->
                if (fact.date < countingFrom) return@forEachIndexed
                if (kinds[index] != LoanMovementKind.INSTALMENT) {
                    // The debt arriving, and interest serviced on its own, both
                    // leave the schedule exactly where they found it — and both
                    // have to *say* so. Blank, they read as rows the app could
                    // not account for, and the broken-period charge is the one
                    // row on the page a reader is most likely to mistake for a
                    // payment off the balance. A lump sum is deliberately not
                    // here: it re-based the loan, so the schedule above it
                    // belongs to a debt that no longer exists.
                    if (kinds[index] == LoanMovementKind.OPENING ||
                        kinds[index] == LoanMovementKind.INTEREST
                    ) {
                        balanceAfter[index] = running
                    }
                    return@forEachIndexed
                }
                // Past any period that went unpaid: it has no row on this
                // statement — that is what deleting the instalment did — and the
                // payment in hand belongs to the next one that does.
                while (paid + 1 in loan.arrears.missed) paid++
                val row = rows.getOrNull(paid) ?: return@forEachIndexed
                paid++
                running = row.balance
                balanceAfter[index] = row.balance
                principal[index] = row.principal
                interest[index] = row.interest
            }
        } else {
            var running = loan.outstanding
            for (index in ordered.indices.reversed()) {
                balanceAfter[index] = running
                // The balance *before* an unreadable row is unknowable, and so is
                // every balance before that one. The walk stops rather than
                // carrying an error backwards through the rest of the history.
                val delta = ordered[index].delta(kinds[index], loan.currencyCode) ?: break
                running -= delta
            }
        }

        return ordered.mapIndexed { index, fact ->
            LoanMovement(
                entryId = fact.entryId,
                date = fact.date,
                kind = kinds[index],
                amount = fact.amount,
                currencyCode = fact.currencyCode,
                baseAmount = fact.baseAmount,
                accountId = fact.accountId,
                accountName = fact.accountName,
                note = fact.note,
                principalPart = principal[index],
                interestPart = interest[index],
                balanceAfter = balanceAfter[index],
            )
            // Newest first, like every other list of what happened in the app.
        }.reversed()
    }

    /**
     * What a row was.
     *
     * The order of the tests is the point. A repayment on money the user lent
     * out is an adjustment *and* comes from the loan's rule — it is an
     * instalment, and asking about the adjustment flag first would file every
     * one of them as the debt growing.
     *
     * Taken apart from [LoanEntryFact] because two entirely different callers
     * have to reach the same verdict about one row: this list, and the reversal
     * that runs when the row is deleted. A statement calling something a lump sum
     * while the delete treated it as the debt arriving would put the money back
     * on a debt that never lost it.
     */
    fun kindOf(
        isOpening: Boolean,
        part: LoanPart?,
        fromSeries: Boolean,
        isAdjustment: Boolean,
        /**
         * Money spent straight from a card — see [LoanMovementKind.SPEND]. It is
         * told by its shape and nothing else: money out, against a facility,
         * naming no account and no rule, and not an adjustment. Nothing else in
         * the app writes that, and the alternative was a new `loan_part` value,
         * which an older build restoring the backup could not parse.
         */
        isSpend: Boolean = false,
    ): LoanMovementKind = when {
        // First, because it is known from the row's own id rather than inferred:
        // the debt arriving looks exactly like more of it being borrowed.
        isOpening -> LoanMovementKind.OPENING
        isSpend -> LoanMovementKind.SPEND
        part == LoanPart.PRINCIPAL -> LoanMovementKind.PRINCIPAL
        part == LoanPart.INTEREST -> LoanMovementKind.INTEREST
        fromSeries -> LoanMovementKind.INSTALMENT
        isAdjustment -> LoanMovementKind.INCREASE
        else -> LoanMovementKind.INSTALMENT
    }

    private fun LoanEntryFact.kind(): LoanMovementKind =
        kindOf(isOpening, part, fromSeries, isAdjustment, isSpend)

    /**
     * How much this moved the balance, signed. Null when it cannot be said.
     *
     * A payment in another currency is exactly that case: converting it would
     * need the rate on the day it happened applied to a debt held in a different
     * one, and a balance column built on a guessed rate is worse than one that
     * stops.
     */
    private fun LoanEntryFact.delta(kind: LoanMovementKind, loanCurrency: String): Money? {
        if (!currencyCode.equals(loanCurrency, ignoreCase = true)) return null
        return when (kind) {
            LoanMovementKind.INTEREST -> Money.ZERO
            LoanMovementKind.INCREASE, LoanMovementKind.OPENING, LoanMovementKind.SPEND -> amount
            LoanMovementKind.PRINCIPAL, LoanMovementKind.INSTALMENT -> -amount
        }
    }
}
