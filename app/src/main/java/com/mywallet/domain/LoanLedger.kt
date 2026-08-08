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
     * What was owed the moment before this happened, or null when the app cannot
     * say.
     *
     * The figure a payment is *worked out from*, and the only one that makes the
     * other three add up: interest is charged on this, whatever the payment has
     * left over comes off it, and [balanceAfter] is what that leaves. Without it
     * a reader has the answer and neither of the numbers it came from.
     *
     * Deliberately null on the debt arriving. The schedule's running figure
     * before that row is the loan's own principal, which is what the borrowing
     * *made* rather than what was owed before it — stating it would say the debt
     * existed the day before it did.
     */
    val balanceBefore: Money? = null,
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
        /**
         * The table the instalments before [countingFrom] were paid against,
         * where a lump sum re-based the loan and that table could be rebuilt —
         * see `LoanRepository.scheduleBeforeRebasing`.
         *
         * Empty is the honest answer rather than a missing feature: a debt
         * re-based twice has a middle basis nobody kept, and there is nothing to
         * read those rows against. They are then listed with their amounts and
         * no working, exactly as they were before this existed.
         */
        before: List<Instalment> = emptyList(),
    ): List<LoanMovement> {
        val ordered = facts.sortedWith(compareBy({ it.date }, { it.createdAt }))
        val kinds = ordered.map { it.kind() }.toMutableList()
        // **And where no row said it was the opening, a row that could only be
        // the opening is promoted to it.** [LoanEntryFact.isOpening] is read off
        // an id derived from the loan's, which is right and is the only thing
        // that can tell the borrowing from a top-up made the same day — but only
        // a row this app wrote carries that id. A debt restored from a file
        // another tool made, or imported, or written before the app derived the
        // id at all, arrives with a perfectly ordinary adjustment where its
        // disbursement should be. Left as an increase it read "Borrowed more" on
        // the row that *is* the borrowing, it counted the whole principal into
        // "so much more borrowed since it started", and it offered to delete the
        // one row the debt cannot lose.
        //
        // Two things have to hold, and together they leave no room for a guess.
        // It must be the **earliest** movement on the debt: nothing can be paid
        // off, serviced or added to a debt that does not exist yet, so anything
        // with a movement in front of it is not the debt arriving. And it must
        // fall **on or before the day the loan's own figures count from**, which
        // a genuine disbursement always does — a lump sum moves that day forward
        // and leaves the disbursement behind it — while money borrowed later
        // never can. A top-up on a debt whose disbursement was never recorded is
        // the one row this cannot reach, and it is right not to: with no earlier
        // movement and no later start day there is nothing left to tell them
        // apart, and the app would be inventing the difference.
        //
        // A facility is deliberately left out of it. An overdraft is never
        // disbursed; its first drawdown is a drawdown like every one after it.
        if (!loan.isOverdraft &&
            kinds.none { it == LoanMovementKind.OPENING } &&
            kinds.firstOrNull() == LoanMovementKind.INCREASE &&
            ordered.first().date <= countingFrom
        ) {
            kinds[0] = LoanMovementKind.OPENING
        }
        val balanceAfter = arrayOfNulls<Money>(ordered.size)
        val balanceBefore = arrayOfNulls<Money>(ordered.size)
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
            // **The rows a lump sum left behind, read against the schedule they
            // were actually paid against.** [before] is that schedule rebuilt —
            // the app keeps enough to put it back, and a payment the reader can
            // see on the page deserves the same answer as any other. The two
            // walks are the same arithmetic on two tables: this one runs out at
            // the lump sum, where the loan was written down again and the walk
            // below picks it up from the current basis.
            //
            // Nothing is filled where [before] is empty. A debt re-based twice
            // has a middle basis nobody kept, and those rows keep the amounts
            // they always had and no working — which the statement says out loud
            // rather than leaving the tap to do nothing.
            if (before.isNotEmpty()) {
                var earlier = 0
                var was = before.firstOrNull()?.let { first ->
                    Money(first.balance.minor + first.principal.minor)
                } ?: loan.principal
                ordered.forEachIndexed { index, fact ->
                    if (fact.date >= countingFrom) return@forEachIndexed
                    if (kinds[index] == LoanMovementKind.OPENING) {
                        balanceAfter[index] = was
                        return@forEachIndexed
                    }
                    if (kinds[index] == LoanMovementKind.INTEREST) {
                        // Servicing interest leaves the balance where it found
                        // it, on the old basis exactly as on the new one.
                        balanceBefore[index] = was
                        balanceAfter[index] = was
                        return@forEachIndexed
                    }
                    if (kinds[index] != LoanMovementKind.INSTALMENT) return@forEachIndexed
                    val row = before.getOrNull(earlier) ?: return@forEachIndexed
                    earlier++
                    balanceBefore[index] = was
                    was = row.balance
                    balanceAfter[index] = row.balance
                    principal[index] = row.principal
                    interest[index] = row.interest
                }
            }
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
                        // And what it was worked out from, which for interest
                        // serviced on its own is the same figure said twice —
                        // that being the fact the row exists to state. Not on
                        // the debt arriving: the schedule's figure before that
                        // row is what the borrowing created, not what was owed
                        // the day before it.
                        if (kinds[index] == LoanMovementKind.INTEREST) {
                            balanceBefore[index] = running
                        }
                    }
                    return@forEachIndexed
                }
                // Past any period that went unpaid: it has no row on this
                // statement — that is what deleting the instalment did — and the
                // payment in hand belongs to the next one that does.
                while (paid + 1 in loan.arrears.missed) paid++
                val row = rows.getOrNull(paid) ?: return@forEachIndexed
                paid++
                // Where the schedule stood when this payment arrived, taken
                // before it moves: the interest below was charged on this
                // figure, and the principal below came off it.
                balanceBefore[index] = running
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
                // Walking backwards hands the figure the row started from for
                // nothing: it is the balance one step further back, which is
                // exactly where the subtraction has just left the total.
                if (kinds[index] != LoanMovementKind.OPENING) balanceBefore[index] = running
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
                balanceBefore = balanceBefore[index],
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
     * Whether a row reopened in the entry form is money **taken from** a debt —
     * a drawdown on a facility, or a person's loan handed over — rather than
     * money handed back on one.
     *
     * The form asks because the two want opposite screens: a drawdown is named
     * after the arrangement it came from and has nothing to label, so it shows
     * "Taken from Dad" where the note box would be.
     *
     * **The `loan_part` is what tells them apart, and leaving it out inverted
     * the sentence.** Money in, against a debt, written as an adjustment
     * describes both acts, so a repayment on money the user had *lent* reopened
     * claiming it was borrowing: "Taken from abc. Borrowed money is not income"
     * over a रू 500 payment that had come back the other way. A repayment
     * carries `PRINCIPAL` — or `INTEREST` — and a drawdown carries neither,
     * which is the same order [kindOf] has always tested them in.
     */
    fun isDrawdown(
        loanId: String?,
        part: LoanPart?,
        isAdjustment: Boolean,
        isMoneyIn: Boolean,
    ): Boolean = loanId != null && part == null && isAdjustment && isMoneyIn

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
