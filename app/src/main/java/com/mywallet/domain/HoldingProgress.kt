package com.mywallet.domain

import com.mywallet.core.money.Money
import java.time.LocalDate

/**
 * How far along an arrangement is, as a bar can draw it.
 *
 * A goal has always had one, and the argument for it was never about goals: two
 * figures and a date are a sentence to be read, and a bar is the one thing on the
 * Accounts page that can be understood without reading. That is just as true of
 * everything else on the page with an end to reach — a debt being paid off, a
 * policy being paid up, a deposit running down its term, a card filling up
 * towards its ceiling — and every one of those was a row of digits the reader had
 * to do arithmetic on.
 *
 * **What "along" means is the arrangement's own question, and the answers are not
 * all the same shape.** Three of them fill as the user gets what they want — the
 * debt cleared, the policy paid up, the day the deposit comes free — and the card
 * is deliberately the odd one, filling as the headroom is spent, because a
 * borrower's question about a card is *how much of it have I used*. That is what
 * the row's own figures say beside each bar, and the bar is the same sentence
 * drawn.
 *
 * **Null is a real answer** and means "there is no honest measure", not zero: a
 * card whose limit the app was never told, a debt from before the column that
 * records what was advanced, a half-described policy. An empty bar there would
 * state a fact — nothing paid, nothing used — that nobody knows.
 */
object HoldingProgress {

    /**
     * How far along a holding is, or null where it is not that kind of thing.
     *
     * [balance] is passed rather than read, because what an account holds is a
     * sum over its movements and lives beside the row rather than on it.
     */
    fun of(account: Account, balance: Money, today: LocalDate): Float? = when {
        // Money towards a figure to reach — the original, and the reason the
        // other three are drawn the way they are.
        account.isGoal -> account.goalTerms?.let { Goal.progress(balance, it.target) }

        // A policy is paid up rather than saved up: what is in it is every
        // premium handed over so far, and what it is on its way to is all of
        // them. Deliberately not the maturity amount — the payout is the
        // insurer's money and arrives in one piece on one day, so a bar creeping
        // towards it would draw the premiums as though they were growing into it.
        account.isInsurance -> account.policyTerms?.let {
            fraction(balance.minor, it.totalPremiums.minor)
        }

        // A deposit is the one holding where nothing about the *money* moves:
        // it holds what was put into it until the day it comes free, and the
        // interest arrives whole on that day. So the only thing that is
        // travelling is the term, and the bar draws that — which is also the one
        // question a depositor has about it.
        account.isFixedDeposit -> account.depositTerms?.let { terms ->
            elapsed(terms.startedOn, terms.maturesOn, today)
        }

        else -> null
    }

    /**
     * How far along a debt is.
     *
     * **A card and an overdraft measure what is used, and everything else
     * measures what is cleared.** Those run opposite ways on purpose: what a
     * borrower wants of a facility is how much of the ceiling is gone, and what
     * they want of a loan is how much of it is behind them.
     *
     * What is *cleared* is measured against everything ever advanced rather than
     * against the balance — `principal` stops meaning "the sum borrowed" the
     * moment a lump sum re-bases the debt in place, and a bar measured against it
     * would jump back to empty the day somebody paid a large chunk off.
     * [Loan.borrowedInAll] is the figure that survives that, and its being null
     * on a debt from before the column is why this can answer null.
     *
     * The interest metered on top is left out: it is not principal, it grows
     * every day a debt sits, and a bar that ran backwards on a debt nobody had
     * touched would say the borrower was losing ground by doing nothing.
     */
    fun of(loan: Loan): Float? = when {
        loan.isClosed -> 1f
        loan.isOverdraft -> loan.creditLimit?.let { fraction(loan.outstanding.minor, it.minor) }
        else -> loan.borrowedInAll?.let { advanced ->
            fraction(advanced.minor - loan.outstanding.minor, advanced.minor)
        }
    }

    /**
     * Clamped at both ends, exactly as a goal's is: a debt cannot be less than
     * nothing paid, and a card drawn past its ceiling is still a full bar. The
     * figures beside it say what actually happened; the bar only has to be
     * legible.
     */
    private fun fraction(part: Long, whole: Long): Float? {
        if (whole <= 0L) return null
        return (part.toDouble() / whole.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The same, over days rather than money.
     *
     * In epoch days, which is how every date in this app is counted: a term is
     * agreed in months, but the months it lands on are 28 to 32 days long and a
     * bar drawn in months would tick a twelfth of a year at a time.
     */
    private fun elapsed(from: LocalDate, to: LocalDate, today: LocalDate): Float? =
        fraction(
            today.toEpochDay() - from.toEpochDay(),
            to.toEpochDay() - from.toEpochDay(),
        )
}
