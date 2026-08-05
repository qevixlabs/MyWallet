package com.mywallet.domain

import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind

/**
 * Where the money is, as the "Add account" form asks it.
 *
 * The user answers one question — where does this sit? — and the answer decides
 * which of two tables the thing lands in. A bank holds several quite different
 * things under one name: an account you spend from, a term loan, an overdraft.
 * Cash, a wallet, and money from a person are each a single choice.
 *
 * Adding a loan used to be a separate screen reached from a separate button,
 * which asked the user to know in advance that a loan is not an account. It is
 * one form now, and this type is the hinge it turns on.
 *
 * [INSURANCE] and [GOAL] are last because they are the least often added: a
 * policy is taken out once and then paid for years, and a goal is set once and
 * then met, where the answers above them are given whenever money moves
 * somewhere new.
 */
enum class HoldingGroup { BANK, WALLET, CASH, PERSON, INSURANCE, GOAL }

/**
 * The things a bank holds for you: somewhere to keep money, and two ways to owe
 * it. One bank usually holds several — a savings account, a term loan and an
 * overdraft at the same bank are three rows sharing one name, which is why the
 * name and this answer are asked together and shown together.
 *
 * [CURRENT] is no longer offered when adding: MyWallet keeps a record of money,
 * not a replica of a bank's product list, and every calculation treated a
 * current account exactly as it treated a savings one. It stays here — and stays
 * selectable on a row that already uses it — because accounts entered before the
 * choice was dropped must not silently change kind.
 */
enum class BankHolding { SAVINGS, CURRENT, FIXED_DEPOSIT, LOAN, OVERDRAFT }

/**
 * The two ways money moves between people.
 *
 * Both are the same arithmetic seen from opposite ends, which is why they share
 * a form — but never a total: one is a debt and the other is an asset.
 */
enum class PersonHolding { BORROWED, LENT }

/**
 * One answer to that question, and what it means for storage.
 *
 * [bank] is kept even while another group is selected, so flipping through the
 * options and coming back to Bank does not silently reset a chosen sub-type.
 */
data class HoldingChoice(
    val group: HoldingGroup = HoldingGroup.BANK,
    val bank: BankHolding = BankHolding.SAVINGS,
    val person: PersonHolding = PersonHolding.BORROWED,
) {
    /** True when this is a debt rather than something to spend from. */
    val isLoan: Boolean
        get() = group == HoldingGroup.PERSON ||
            (group == HoldingGroup.BANK && bank in LOAN_HOLDINGS)

    /** Null when the answer was a loan, which is not an account at all. */
    val accountKind: AccountKind?
        get() = when {
            isLoan -> null
            group == HoldingGroup.WALLET -> AccountKind.WALLET
            group == HoldingGroup.CASH -> AccountKind.CASH
            group == HoldingGroup.INSURANCE -> AccountKind.INSURANCE
            group == HoldingGroup.GOAL -> AccountKind.GOAL
            bank == BankHolding.CURRENT -> AccountKind.CURRENT
            bank == BankHolding.FIXED_DEPOSIT -> AccountKind.FIXED_DEPOSIT
            else -> AccountKind.SAVINGS
        }

    /** True when this is money put away for a fixed term rather than to spend. */
    val isFixedDeposit: Boolean
        get() = group == HoldingGroup.BANK && bank == BankHolding.FIXED_DEPOSIT

    /** True when this is a policy paid for in premiums until it pays out. */
    val isInsurance: Boolean get() = group == HoldingGroup.INSURANCE

    /** True when this is money being put aside towards a figure to reach. */
    val isGoal: Boolean get() = group == HoldingGroup.GOAL

    /** Null when the answer was an account. */
    val loanKind: LoanKind?
        get() = when {
            !isLoan -> null
            group == HoldingGroup.PERSON -> LoanKind.PERSONAL
            bank == BankHolding.OVERDRAFT -> LoanKind.OVERDRAFT
            else -> LoanKind.BANK
        }

    /**
     * Which way the money went. A bank never borrows from the user, so only the
     * person side can be a loan the user gave.
     */
    val loanDirection: LoanDirection
        get() = if (group == HoldingGroup.PERSON && person == PersonHolding.LENT) {
            LoanDirection.LENT
        } else {
            LoanDirection.BORROWED
        }

    /** True when this is money owed *to* the user. */
    val isLent: Boolean get() = loanDirection == LoanDirection.LENT

    companion object {
        /** The bank answers that mean a debt rather than somewhere to spend from. */
        private val LOAN_HOLDINGS = listOf(BankHolding.LOAN, BankHolding.OVERDRAFT)

        fun of(kind: AccountKind): HoldingChoice = when (kind) {
            AccountKind.SAVINGS -> HoldingChoice(HoldingGroup.BANK, BankHolding.SAVINGS)
            AccountKind.CURRENT -> HoldingChoice(HoldingGroup.BANK, BankHolding.CURRENT)
            AccountKind.FIXED_DEPOSIT ->
                HoldingChoice(HoldingGroup.BANK, BankHolding.FIXED_DEPOSIT)
            AccountKind.INSURANCE -> HoldingChoice(HoldingGroup.INSURANCE)
            AccountKind.GOAL -> HoldingChoice(HoldingGroup.GOAL)
            AccountKind.WALLET -> HoldingChoice(HoldingGroup.WALLET)
            AccountKind.CASH -> HoldingChoice(HoldingGroup.CASH)
        }

        fun of(kind: LoanKind, direction: LoanDirection): HoldingChoice {
            val person = if (direction == LoanDirection.LENT) {
                PersonHolding.LENT
            } else {
                PersonHolding.BORROWED
            }
            return when (kind) {
                LoanKind.BANK -> HoldingChoice(HoldingGroup.BANK, BankHolding.LOAN, person)
                LoanKind.OVERDRAFT ->
                    HoldingChoice(HoldingGroup.BANK, BankHolding.OVERDRAFT, person)
                LoanKind.PERSONAL -> HoldingChoice(HoldingGroup.PERSON, BankHolding.LOAN, person)
            }
        }

        /**
         * The options offered when the row already exists.
         *
         * An account cannot become a loan, or a loan an account: they are
         * different tables with different arithmetic, and quietly migrating one
         * to the other would lose either the balance or the schedule. Switching
         * savings to current, or a bank loan to a personal one, is only a
         * relabelling and stays allowed.
         */
        fun groupsFor(existing: HoldingChoice?): List<HoldingGroup> = when {
            existing == null -> HoldingGroup.entries
            existing.isLoan -> listOf(HoldingGroup.BANK, HoldingGroup.PERSON)
            // A deposit is with a bank by definition — there is no such thing as
            // a fixed deposit in a wallet or in a cash tin.
            existing.isFixedDeposit -> listOf(HoldingGroup.BANK)
            // A policy is nothing else either: it has a schedule of premiums
            // behind it and a day it pays out, and calling it a wallet
            // afterwards would leave both pointing at a holding that has
            // neither.
            existing.isInsurance -> listOf(HoldingGroup.INSURANCE)
            // Nor is a goal: it has a plan behind it and a day it is meant to be
            // reached, and calling it a wallet afterwards would leave both
            // pointing at a holding that has neither.
            existing.isGoal -> listOf(HoldingGroup.GOAL)
            else -> listOf(HoldingGroup.BANK, HoldingGroup.WALLET, HoldingGroup.CASH)
        }

        /**
         * A new bank holding is a savings account, a term loan or an overdraft.
         * A current account is only offered to a row that is already one, so
         * editing it does not quietly re-file it as savings.
         *
         * The two debts can be relabelled into each other — that is only a
         * naming correction — but neither can become an account, because a
         * balance and a schedule live in different tables.
         */
        fun bankHoldingsFor(existing: HoldingChoice?): List<BankHolding> = when {
            existing == null -> listOf(
                BankHolding.SAVINGS,
                BankHolding.FIXED_DEPOSIT,
                BankHolding.LOAN,
                BankHolding.OVERDRAFT,
            )
            existing.isLoan -> LOAN_HOLDINGS
            // A deposit is the one account that cannot be relabelled into
            // another. Its balance is not a running total of entries, it cannot
            // be spent from, and it has a day on which the whole of it leaves —
            // so calling it a savings account afterwards would leave a maturity
            // pointing at nothing and a balance nothing would ever move.
            existing.bank == BankHolding.FIXED_DEPOSIT -> listOf(BankHolding.FIXED_DEPOSIT)
            existing.bank == BankHolding.CURRENT ->
                listOf(BankHolding.SAVINGS, BankHolding.CURRENT)
            else -> listOf(BankHolding.SAVINGS)
        }

        /**
         * Borrowing and lending are only offered as a choice while creating.
         *
         * Flipping an existing one would reverse the meaning of every payment
         * already recorded against it — money that left the account would start
         * claiming to have arrived. Getting it wrong means deleting it and
         * entering it the other way round, which is the honest amount of work.
         */
        fun personHoldingsFor(existing: HoldingChoice?): List<PersonHolding> =
            if (existing == null) PersonHolding.entries else listOf(existing.person)
    }
}
