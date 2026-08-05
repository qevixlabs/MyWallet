package com.mywallet.domain

import androidx.compose.ui.graphics.Color
import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import java.time.LocalDate

/** Somewhere money lives, with its own currency. */
data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val currencyCode: String,
    /** Bank this sits under, for grouping. Null for cash and standalone wallets. */
    val institution: String?,
    val openingBalance: Money,
    val color: Color,
    /** True to show this account's money converted into the display currency. */
    val showInDisplayCurrency: Boolean,
    /** What the bank pays on it, when the user has recorded a rate. */
    val annualRate: Double? = null,
    /**
     * How many Nepali months this account's interest period runs for, when the
     * question applies to it and has been answered. Null reads as
     * [SavingsInterest.DEFAULT_EVERY_MONTHS] — see the column.
     */
    val interestPayoutMonths: Int? = null,
    /**
     * Whether this bank cuts those periods into Nepali months — the account's
     * own opt-in, which is only half the answer. See
     * [com.mywallet.core.date.CalendarSystem.forInterest].
     */
    val interestInBs: Boolean = false,
    /**
     * The day this arrangement started: money going into a deposit, or a policy
     * being taken out. Null on every kind with no term.
     */
    val depositStartedOn: LocalDate? = null,
    /** How long it was agreed for, in months — a deposit's term or a policy's. */
    val depositTermMonths: Int? = null,
    /** Where the whole of it lands on the day it matures. */
    val maturesIntoAccountId: String? = null,
    /** What a policy pays out at the end, or what a goal is aimed at. */
    val maturityAmount: Money? = null,
    /** What one premium costs, or what one contribution puts aside. */
    val perPayment: Money? = null,
    /** Months between those payments. */
    val premiumEveryMonths: Int? = null,
    /** The repeating rule that makes them. */
    val premiumSeriesId: String? = null,
    /** Whether this plan's schedule counts Nepali months. See [PeriodicPlan]. */
    val planRecurInBs: Boolean = false,
    val isArchived: Boolean,
) {
    /** True when this is money put away for a term rather than money to spend. */
    val isFixedDeposit: Boolean get() = kind == AccountKind.FIXED_DEPOSIT

    /** True when this is a policy being paid for rather than money to spend. */
    val isInsurance: Boolean get() = kind == AccountKind.INSURANCE

    /** True when this is money being put aside towards a figure to reach. */
    val isGoal: Boolean get() = kind == AccountKind.GOAL

    /**
     * What the user calls *this* holding, as opposed to the bank it is at.
     *
     * Two fixed deposits at one bank are two rows both reading "Fixed deposit",
     * and only their owner knows which is the one for the house. So [name] may
     * carry a name of its own, with [institution] holding the bank — the
     * original meaning of the two columns.
     *
     * Null where they say the same thing, which is every holding created before
     * the field existed and every one where the user left it blank: `name` is
     * then the bank, and the row falls back to saying what kind it is.
     */
    val ownName: String?
        get() = institution?.takeIf { !it.equals(name, ignoreCase = true) }?.let { name }

    /**
     * Everything the maths needs, or null when this deposit is not fully
     * described yet — which the form prevents but a restored backup might not.
     */
    val depositTerms: FixedDeposit.Terms?
        get() {
            if (!isFixedDeposit) return null
            val started = depositStartedOn ?: return null
            val months = depositTermMonths?.takeIf { it > 0 } ?: return null
            return FixedDeposit.Terms(
                principal = openingBalance,
                annualRate = annualRate ?: 0.0,
                startedOn = started,
                termMonths = months,
                inBikramSambat = planRecurInBs,
            )
        }

    /**
     * Everything a policy's schedule needs, or null when it is not one or is
     * only half described — which the form prevents but a restored backup,
     * which carries no premiums, might not.
     */
    val policyTerms: Insurance.Terms?
        get() {
            if (!isInsurance) return null
            val started = depositStartedOn ?: return null
            val months = depositTermMonths?.takeIf { it > 0 } ?: return null
            val every = premiumEveryMonths?.takeIf { it > 0 } ?: return null
            return Insurance.Terms(
                premium = perPayment ?: Money.ZERO,
                maturityAmount = maturityAmount ?: Money.ZERO,
                startedOn = started,
                termMonths = months,
                everyMonths = every,
                inBikramSambat = planRecurInBs,
            )
        }

    /**
     * Everything a goal's plan needs, or null when it is not one or is only
     * half described.
     *
     * The contribution is read from the row rather than divided out again: the
     * rule that moves the money was written from the stored figure, and a second
     * division here could disagree with it after an edit.
     */
    val goalTerms: Goal.Terms?
        get() {
            if (!isGoal) return null
            val started = depositStartedOn ?: return null
            val months = depositTermMonths?.takeIf { it > 0 } ?: return null
            val every = premiumEveryMonths?.takeIf { it > 0 } ?: return null
            return Goal.Terms(
                target = maturityAmount ?: Money.ZERO,
                startedOn = started,
                termMonths = months,
                everyMonths = every,
                inBikramSambat = planRecurInBs,
            )
        }

    /**
     * The day the money comes back: the day it started, plus the agreed length.
     * A deposit coming free, a policy paying out and a goal being reached are
     * the same fact about three arrangements, so one property answers for all.
     */
    val maturesOn: LocalDate?
        get() = depositTerms?.maturesOn ?: policyTerms?.maturesOn ?: goalTerms?.targetOn
}

/**
 * What to call an account where money is being *sent*, rather than described.
 *
 * A bank is one place to a person paying into it: "Nabil Bank", not "Nabil Bank
 * Salary Savings". Which of the bank's accounts the money lands in is the app's
 * business — see [payableHoldings] — and naming it in the chip asked the user a
 * second question they had not thought to ask themselves. Cash and a wallet
 * keep their own names, being the only thing they are.
 */
val Account.payLabel: String
    get() = if (kind == AccountKind.WALLET || kind == AccountKind.CASH) {
        name
    } else {
        institution ?: name
    }

/**
 * The places money can be paid into or out of: one per bank, plus cash and
 * wallets.
 *
 * A bank is offered once and stands for its **savings account** — that is where
 * money paid to a bank goes, and a picker that listed a salary account, a second
 * savings account and a fixed deposit under one name made the user choose
 * between three answers to a question with one. A bank with no account money can
 * sit in is not offered at all: a bank you only have a loan with is not
 * somewhere to be paid.
 *
 * A current account stands in where there is no savings account. It cannot be
 * created any more, but the ones already on file are spendable bank accounts,
 * and dropping them would strand every payment an older install wants to record.
 *
 * @param keep an account that must stay in the list whatever the rule says —
 *   the one already chosen. A form that quietly forgot the account a loan has
 *   been repaid from for two years would be worse than an extra chip.
 */
fun List<Account>.payableHoldings(keep: String? = null): List<Account> {
    // Nothing may be paid into or out of money put away for a term, a policy
    // or a goal: what goes into one is the payment its own schedule makes, and
    // what comes out is the payout on the day it ends. Both are the
    // arrangement's own movements, not somewhere the user sends money.
    val spendable = filterNot { it.isFixedDeposit || it.isInsurance || it.isGoal }
    val (atBanks, ownName) = spendable.partition {
        it.kind == AccountKind.SAVINGS || it.kind == AccountKind.CURRENT
    }
    val banks = atBanks
        .groupBy { (it.institution ?: it.name).trim().lowercase() }
        .values
        .map { rows ->
            rows.firstOrNull { it.id == keep }
                ?: rows.firstOrNull { it.kind == AccountKind.SAVINGS }
                ?: rows.first()
        }
    return banks + ownName
}

/**
 * An account with its current balance.
 *
 * [balance] is in the account's own currency — a Wise account holds dollars.
 * [balanceInBase] is the same figure converted for the totals row, and is null
 * when no rate is available, so the UI can say so instead of showing a wrong
 * number.
 */
data class AccountWithBalance(
    val account: Account,
    val balance: Money,
    val balanceInBase: Money?,
)

/**
 * One line of an account's statement: what happened, and what it left behind.
 *
 * [balanceAfter] is what makes it a statement rather than a list — it is the
 * column the user reads down when a balance is not what they expected, and the
 * one place the app shows its working.
 */
data class AccountMovement(
    /**
     * The movement itself, whole.
     *
     * It used to be flattened here into a title and an amount, and the title
     * led with the *label* it was filed under — so a column that exists to tell one payment from
     * another read "Loan payment · Loan payment · Loan payment" down a bank's
     * statement. The row is named the same way every other list names it now
     * (see `entryTitle`), which needs more of the entry than a string.
     */
    val entry: MoneyEntry,
    val balanceAfter: Money,
    /**
     * True for one instalment of a loan's own repeating rule.
     *
     * The one thing on the statement that cannot be swiped away here. It is one
     * of a run of payments the loan's schedule counts, and what the schedule says
     * is changed by changing the schedule; taking a single instalment out from
     * this list would leave the debt a month ahead of itself with nothing on the
     * screen to say why.
     */
    val fromLoanSchedule: Boolean = false,
) {
    val id: String get() = entry.id
    val on: LocalDate get() = entry.occurredOn
    val amount: Money get() = entry.amount
    val isIn: Boolean get() = entry.direction == Direction.IN
}

data class MoneyEntry(
    val id: String,
    /** EXPECTED rows came from a repeating rule and are still the rule's own. */
    val status: EntryStatus = EntryStatus.CONFIRMED,
    /** The repeating rule this came from, if any. */
    val seriesId: String? = null,
    /** As entered, in [currencyCode]. */
    val amount: Money,
    val currencyCode: String,
    /** Converted to the display currency using the rate locked at save time. */
    val baseAmount: Money,
    val accountId: String?,
    val accountName: String?,
    /** The bank the account sits under, so a row can say "Nabil Bank Savings". */
    val accountInstitution: String? = null,
    /**
     * Which of the bank's products it is, and what it holds — the other two
     * thirds of what a holding is called. See `holdingDisplayName`.
     */
    val accountKind: AccountKind? = null,
    val accountCurrency: String? = null,
    /**
     * How many holdings sit under the same bank name, which decides whether
     * naming the kind distinguishes anything. See `holdingDisplayName`.
     */
    val accountSiblings: Int? = null,
    val isAdjustment: Boolean,
    val direction: Direction,
    val occurredOn: LocalDate,
    val note: String?,
    /** Set when this row is one half of a transfer between two accounts. */
    val transferId: String? = null,
    /**
     * Whether the account this belongs to prefers the display currency. Entries
     * with no account keep the old behaviour of leading with the converted figure.
     */
    val showInDisplayCurrency: Boolean = true,
    /** What the other half of a transfer was worth, in its own currency. */
    val transferPartnerAmount: Money? = null,
    val transferPartnerCurrency: String? = null,
    /**
     * The two ends of a transfer, already resolved to which is which.
     *
     * A transfer's own row knows only its own account; the direction says
     * whether that is the paying or the receiving end. Working it out here means
     * every list can print "Wise → Nabil Bank" without repeating the reasoning.
     */
    val transferFromName: String? = null,
    val transferToName: String? = null,
    /**
     * The debt this row is an instalment of, when its series pays a loan.
     *
     * Carried for the same reason a projection carries it: the day an
     * instalment comes due it stops being a projection and becomes a row, and
     * a row that suddenly read "Nabil Bank · Money out" instead of
     * "EMI · Nabil Bank Loan" looked like a different payment entirely.
     */
    val loanName: String? = null,
    val loanKind: LoanKind? = null,
    /** The loan this entry moved directly — a drawdown, lump sum or interest. */
    val loanId: String? = null,
    /**
     * The debt this row is *about*, whichever way it is attached to one.
     *
     * [loanId] is narrower on purpose: it is what the row itself stores, and
     * [isLoanOpening] reads it. An instalment stores no loan at all — it is
     * found through its series, exactly as [loanName] is — so a tap on one had
     * no debt to open and fell through to the money-out form, which is the one
     * screen that cannot express what the payment did. The projection it grew
     * out of opened the loan; the row it turned into has to open the same
     * thing, or a payment changes what it is by the date arriving.
     */
    val belongsToLoanId: String? = null,
    /**
     * Which half of a debt this went to, when it was deliberately all one or
     * all the other. Null on an ordinary instalment — and on the rows that are
     * not payments at all, which is what [isLoanIncrease] turns on.
     */
    val loanPart: LoanPart? = null,
) {
    /**
     * True when this row belongs to a debt at all — as an instalment of it, a
     * payment against it, or an addition to it.
     *
     * The three are told apart below and drawn differently, but they share one
     * thing: the debt is what the row is *about*, so a note that only repeats
     * its name is saying nothing the row would not say anyway.
     */
    val belongsToLoan: Boolean get() = loanName != null

    /**
     * What the user actually wrote about this row, as opposed to what the app
     * wrote for them.
     *
     * Every movement a debt's own screen records carries the debt's name in its
     * note — that is what the row is about, and it is what the lists fall back
     * to when nothing else names them. A note that merely repeats that name is
     * therefore the app's own writing and says nothing the row would not say
     * anyway; only a note the user typed into the card is one of theirs.
     *
     * One rule in one place, because three lists read it: what a row is called,
     * what its second line says, and the debt's own statement.
     */
    val ownNote: String?
        get() = note
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !belongsToLoan || it != loanName }

    /**
     * Money added to a debt rather than paid off one: drawn from an overdraft,
     * or more borrowed from — or lent to — the same person.
     *
     * Nothing is being repaid, so it must never be drawn as an instalment. The
     * three tests are what separate it: it belongs to a loan, it moves a balance
     * without being income or spending, and it came from neither the loan's own
     * rule nor a payment aimed at one half of it.
     */
    val isLoanIncrease: Boolean
        get() = belongsToLoan && isAdjustment && seriesId == null && loanPart == null

    /**
     * The one increase that is the debt itself arriving: the money handed over on
     * the day it was made.
     *
     * Known from its derived id, never from its shape — it is written exactly as
     * a top-up is, and the timeline has to call it what the loan's own statement
     * calls it. "Borrowed more" on the row that *is* the borrowing reads as a
     * debt that has already grown past what was agreed.
     */
    val isLoanOpening: Boolean
        get() = loanId != null && id == "$loanId$LOAN_DISBURSEMENT_SUFFIX"

    /**
     * A quarter's interest the app credited to this account itself.
     *
     * Told by its derived id — the account and the day — which is the same mark
     * the repository rewrites a period by and the only one a posting carries now
     * that it has no label. It matters to the words on the row: nobody was asked
     * what this was for, so a title falling back to "nothing said" would blame
     * the user for an omission the app does not allow them to fix.
     */
    val isInterestPosting: Boolean
        get() = accountId != null && id.startsWith("$accountId$INTEREST_POSTING_SUFFIX")

    /**
     * A payment deliberately aimed at one half of a debt: a lump sum off the
     * balance, or interest serviced on its own.
     *
     * Not an instalment — no rule produced it and it settles no schedule — so it
     * is drawn as what it is. Left to the generic row it read as a bare "Money
     * out", which is worse than saying nothing on a payment whose whole point is
     * which half of the debt it went to.
     */
    val isLoanSettlement: Boolean get() = belongsToLoan && seriesId == null && loanPart != null

    /**
     * Money drawn out of an overdraft into an account. The one increase drawn as
     * a route rather than named after whoever it is with: a facility on one side,
     * the account the money landed in on the other.
     */
    val isOverdraftDraw: Boolean
        get() = loanKind == LoanKind.OVERDRAFT &&
            direction == Direction.IN && isAdjustment

    /**
     * Money spent straight from a card — see [LoanMovementKind.SPEND].
     *
     * The mirror of the same rule on the entity, which is what the ledger and
     * the balance arithmetic read; this is what the *rows* read, so a purchase
     * is drawn as what it is rather than falling through to "money out of
     * nowhere". Told by its shape: out, against a facility, naming no account
     * and no rule, and not an adjustment.
     */
    val isCardSpend: Boolean
        get() = loanId != null &&
            direction == Direction.OUT &&
            loanPart == null &&
            accountId == null &&
            seriesId == null &&
            !isAdjustment

    /**
     * A "correct this balance" row: an adjustment attached to nothing — no
     * transfer, no loan, no repeating rule.
     *
     * It exists to make an account admit what it really holds, not to record
     * something that happened, so no list draws it. The balances still feel
     * it — that is its entire job — and correcting a wrong correction is done
     * the same way it was made: by correcting the balance again.
     */
    val isBalanceCorrection: Boolean
        get() = isAdjustment && !isTransfer && loanId == null && seriesId == null

    /**
     * True when this row is an instalment of a loan — one occurrence of the rule
     * the loan writes for itself, which is the only thing that may be titled as
     * one.
     */
    val isLoanPayment: Boolean get() = belongsToLoan && seriesId != null

    /**
     * True when this row is the paying half of a transfer that changed currency —
     * the one case where a conversion genuinely happened and both figures are
     * facts rather than one being a valuation of the other.
     */
    val convertedOnTransfer: Boolean
        get() = transferPartnerAmount != null &&
            transferPartnerCurrency != null &&
            direction == Direction.OUT &&
            !transferPartnerCurrency.equals(currencyCode, ignoreCase = true)

    val isTransfer: Boolean get() = transferId != null

    /**
     * The arriving half of a transfer.
     *
     * Both halves are stored — one row cannot move two balances — but a list of
     * what happened should show one movement once. The paying half is the one
     * kept: it names both ends and, across currencies, carries both figures.
     * The arriving half's effect is already visible in the balances.
     */
    val isTransferArrival: Boolean get() = isTransfer && direction == Direction.IN

    /**
     * Whether this row belongs in any total. Adjustments move a balance without
     * being income or spending, which is the only reason a row is ever left out:
     * a payment written by a rule for a day that has arrived is as real as one
     * the user typed, and counts from that day.
     */
    val counts: Boolean get() = !isAdjustment

    /** Positive for money in, negative for money out — for running totals. */
    val signedAmount: Money
        get() = if (direction == Direction.IN) amount else -amount

    /** Same, in the display currency. Use this for anything that sums. */
    val signedBaseAmount: Money
        get() = if (direction == Direction.IN) baseAmount else -baseAmount

    /**
     * True when this entry was made in something other than [displayCurrency].
     * Compared by code, not by value: 100 NPR and 100 USD are different entries
     * that happen to share a number.
     */
    fun isForeign(displayCurrency: String): Boolean =
        !currencyCode.equals(displayCurrency, ignoreCase = true)
}

/**
 * A future occurrence of a repeating series.
 *
 * Never stored: it is derived from the rule every time it is shown, so a rule
 * the user edits corrects every projection immediately.
 */
data class ProjectedEntry(
    val seriesId: String,
    val date: LocalDate,
    val amount: Money,
    val currencyCode: String,
    val baseAmount: Money,
    val direction: Direction,
    /**
     * What to call this row when nothing else on it says. Only the app's own
     * schedule fills it in — a bank's quarter of interest, which is a kind of
     * movement rather than anything the user typed.
     */
    val title: String?,
    val accountId: String?,
    val accountName: String?,
    /** The bank the account sits under, so a row can say "Nabil Bank Savings". */
    val accountInstitution: String? = null,
    /**
     * Which of the bank's products it is, and what it holds — the other two
     * thirds of what a holding is called. See `holdingDisplayName`.
     */
    val accountKind: AccountKind? = null,
    val accountCurrency: String? = null,
    /**
     * How many holdings sit under the same bank name, which decides whether
     * naming the kind distinguishes anything. See `holdingDisplayName`.
     */
    val accountSiblings: Int? = null,
    /**
     * Whether the account this leaves prefers to be read in the display
     * currency. A projection is the same payment as the real row it will become,
     * so it has to answer this question the same way — otherwise a dollar
     * instalment read as dollars in the month it was paid and as rupees in the
     * month it is still due.
     */
    val showInDisplayCurrency: Boolean = true,
    val note: String?,
    /**
     * True when this occurrence moves a balance without being income or
     * spending — a transfer leg, or a repayment of money the user lent out. The
     * month's in and out totals skip these; the balances still feel them.
     */
    val isAdjustment: Boolean = false,
    /** Both ends of a transfer, so a projected one can name them the same way. */
    val transferFromName: String? = null,
    val transferToName: String? = null,
    /**
     * The other half of a transfer: what will land, in the currency it lands in.
     *
     * A real transfer's paying half reads both figures off its partner row and
     * draws them as one movement. A projection has no partner row to read — the
     * occurrence does not exist yet — so the rule's own conversion is carried
     * here instead, and the row says "$ 900 → रू 1,38,587" in the month it is
     * still due exactly as it does in the month it has been paid. Without it a
     * transfer changed shape the moment it stepped past today.
     */
    val transferPartnerAmount: Money? = null,
    val transferPartnerCurrency: String? = null,
    /**
     * The arriving half of a projected transfer. Kept in the list because the
     * balances need it, and hidden from the drawn rows for the same reason a
     * real transfer shows once — see [MoneyEntry.isTransferArrival].
     */
    val isTransferArrival: Boolean = false,
    /**
     * A quarter's savings interest the bank has yet to pay.
     *
     * Scheduled by the bank rather than by a rule the user wrote, so there is
     * nothing behind it to open or stop — which is the whole reason this flag
     * exists. Everything else about it is an ordinary projection: it lands on a
     * day, it moves an account, and the balance below has to feel it.
     */
    val isInterest: Boolean = false,
    /**
     * A fixed deposit coming free, and the money landing where it was told to
     * go. Like a quarter's interest it is the bank's schedule and not a rule the
     * user wrote, so there is nothing behind it to open or stop.
     */
    val isDepositMaturity: Boolean = false,
    /**
     * The debt this instalment pays, when it pays one.
     *
     * Carried rather than a bare "this is an EMI" flag, because the row has to
     * name both ends: which account the money leaves and which of that bank's
     * products it settles. A bank's name on its own distinguishes neither.
     */
    val loanName: String? = null,
    val loanKind: LoanKind? = null,
    /**
     * A premium into a policy or a contribution into a goal.
     *
     * Carried for the same reason [loanName] is: the arrangement counts its own
     * payments — a policy's premium table, a goal's day of arrival — and none of
     * those readers can see a date the timeline dropped.
     */
    val isPlanPayment: Boolean = false,
) {
    /** True when this occurrence is a loan instalment. */
    val isLoanPayment: Boolean get() = loanName != null

    /**
     * Whether there is a rule behind this row for a tap to open.
     *
     * A quarter's interest and a deposit's maturity are both the bank's own
     * schedule — nothing the user wrote, and nothing they can stop — so tapping
     * either used to offer to cancel a repeating rule that has never existed.
     */
    val hasRuleBehindIt: Boolean get() = !isInterest && !isDepositMaturity

    /**
     * Whether this one date can be dropped without the app contradicting itself.
     *
     * A rule the user wrote is theirs to skip a month of. A schedule the app
     * runs *for* them is not: a loan's instalments are what its balance is
     * counted from, and a policy's premiums and a goal's contributions are
     * counted by the arrangement they belong to — each of which would go on
     * showing a payment the timeline had dropped. The bank's own schedule (a
     * quarter's interest, a deposit coming free) has no rule behind it at all.
     *
     * The arriving half of a transfer is excluded because it is not drawn: the
     * paying half is the row the user swipes, and skipping takes both.
     */
    val canSkip: Boolean
        get() = hasRuleBehindIt && !isLoanPayment && !isPlanPayment && !isTransferArrival

    /**
     * The paying half of a projected transfer that crosses currencies — answered
     * exactly as [MoneyEntry.convertedOnTransfer] answers it for a real one, so
     * the two never disagree about how the same movement should be drawn.
     */
    val convertedOnTransfer: Boolean
        get() = transferPartnerAmount != null &&
            transferPartnerCurrency != null &&
            direction == Direction.OUT &&
            !transferPartnerCurrency.equals(currencyCode, ignoreCase = true)

    val signedBaseAmount: Money
        get() = if (direction == Direction.IN) baseAmount else -baseAmount

    /** The same, in [currencyCode]. Only ever added to a balance in that currency. */
    val signedAmount: Money
        get() = if (direction == Direction.IN) amount else -amount
}

/**
 * What an account is expected to hold once the scheduled payments have run.
 *
 * Carried twice over, because "how much is in it" has two honest answers: what
 * the account actually holds, and what that is worth in the currency the user
 * reads totals in. [showInDisplayCurrency] says which of the two this account
 * wants to lead with — a Wise account holding dollars should say dollars, not a
 * rupee valuation of them.
 *
 * [now] and [after] are null when no rate was available to convert with, which
 * is a thing to say rather than a zero to show.
 */
data class AccountProjection(
    val accountId: String,
    val name: String,
    val institution: String?,
    /** Which of the bank's products this is, for the line under the name. */
    val kind: AccountKind,
    val currencyCode: String,
    val showInDisplayCurrency: Boolean,
    /**
     * The holding's own colour, so the timeline's blocks are read the same way
     * the Accounts page is read: a dot the eye already knows this bank by. It is
     * the one thing colour still means in this app — see `HoldingPalette` — and
     * the timeline listed the same holdings with nothing in front of them.
     */
    val color: Color,
    val now: Money?,
    val after: Money?,
    /** The same two figures in the account's own currency. */
    val nowOwn: Money,
    val afterOwn: Money,
) {
    /** What the user called this one, as [Account.ownName] reads it. */
    val ownName: String?
        get() = institution?.takeIf { !it.equals(name, ignoreCase = true) }?.let { name }
}

/**
 * One future day in the timeline: what is due, and what the balance becomes
 * once it has all happened.
 */
data class ProjectedDay(
    val date: LocalDate,
    val entries: List<ProjectedEntry>,
    /** Running balance after this day's items, in the display currency. */
    val balanceAfter: Money,
)

/**
 * Whether a loan's first payment is a broken period, and where the real
 * schedule therefore starts.
 *
 * A bank recovers on a fixed day of the month. Money handed over on the 3rd
 * meets the 20th seventeen days later, and what is taken then is the interest
 * for those days — not an instalment. The eighty-four instalments begin at the
 * *next* recovery date.
 *
 * The test is "less than one whole payment period", which is the only thing that
 * can distinguish the two cases from dates alone. A first payment a full month
 * or more after disbursement is the ordinary arrangement and nothing here
 * applies to it — which is also why a loan with no recorded disbursement date
 * is left alone: without that date there is no gap to measure, and inventing one
 * would invent an interest charge.
 */
object BrokenPeriod {

    fun applies(
        disbursedOn: LocalDate?,
        firstRecoveryOn: LocalDate?,
        monthsPerPayment: Int,
        /**
         * The agreed length, where one is known. A loan whose gap is the whole
         * term is settled in a single payment, and a single payment cannot be
         * early: whatever day it falls on is the day the whole debt comes due,
         * interest and all. Left null by callers with no term to compare against,
         * which is the ordinary case and behaves exactly as it always has.
         */
        termMonths: Int? = null,
        /**
         * Whether a month of this debt's schedule is a Nepali month — the loan's
         * own `recur_in_bs`. "One whole payment period after the money arrived"
         * has to be measured in the months the schedule is actually counted in,
         * or a first payment that is a full period in one calendar reads as a
         * stub in the other and the app invents an interest charge.
         */
        inBikramSambat: Boolean = false,
    ): Boolean {
        if (disbursedOn == null || firstRecoveryOn == null) return false
        if (paysInOnePayment(monthsPerPayment, termMonths)) return false
        if (!disbursedOn.isBefore(firstRecoveryOn)) return false
        return firstRecoveryOn.isBefore(
            Recurrence.addMonths(
                disbursedOn, monthsPerPayment.coerceAtLeast(1).toLong(), inBikramSambat,
            )
        )
    }

    /** The first *full* instalment: one period on when the first payment is a stub. */
    fun firstInstalment(
        disbursedOn: LocalDate?,
        firstRecoveryOn: LocalDate?,
        monthsPerPayment: Int,
        termMonths: Int? = null,
        inBikramSambat: Boolean = false,
    ): LocalDate? = when {
        firstRecoveryOn == null -> null
        applies(disbursedOn, firstRecoveryOn, monthsPerPayment, termMonths, inBikramSambat) ->
            Recurrence.addMonths(
                firstRecoveryOn, monthsPerPayment.coerceAtLeast(1).toLong(), inBikramSambat,
            )
        else -> firstRecoveryOn
    }

    /**
     * Whether the whole debt falls on one date rather than on a schedule.
     *
     * True when a payment period is at least as long as the loan itself, which
     * is how "pay it all at the end" is stored: one period, one payment, the
     * principal and every day of interest together. Pushing that payment a
     * period later because it looked like a stub would move it a whole term out.
     */
    private fun paysInOnePayment(monthsPerPayment: Int, termMonths: Int?): Boolean =
        termMonths != null && termMonths > 0 && monthsPerPayment >= termMonths
}

/**
 * When a loan's first instalment starts charging interest.
 *
 * Every later period runs from one payment to the next, so only the first needs
 * deciding, and it is the day the *current* balance started existing. Three
 * cases, and the rule below is the only one that gets all three right:
 *
 *  - An ordinary loan: the first period is a whole one, ending on the first
 *    instalment.
 *  - A loan whose first payment settled a broken period: the schedule begins at
 *    the *next* recovery date, and its first period is the whole one before it —
 *    the days before that were charged by the stub and must not be charged
 *    twice.
 *  - A loan re-based by a lump sum: `startedOn` is the day the money moved,
 *    which lands partway through a period. The next instalment charges only the
 *    days since, on the reduced balance — which is exactly what the bank does,
 *    and what the old whole-period convention quietly got wrong.
 *
 * So: use `startedOn` when it falls *inside* the first period, and the whole
 * period otherwise. A loan entered long after it began has `startedOn` sitting
 * on its own first instalment, and taking that literally would charge its first
 * payment nothing at all.
 */
fun accrualFor(
    startedOn: LocalDate,
    firstPaymentOn: LocalDate?,
    monthsPerPayment: Int,
    carriedInterest: Money = Money.ZERO,
    rates: RateSchedule? = null,
    /** Whether a month of this schedule is a Nepali one — `loan.recur_in_bs`. */
    inBikramSambat: Boolean = false,
): Accrual? {
    if (firstPaymentOn == null) return null
    // Stepped back in the schedule's own months, so "one whole period before the
    // first payment" is the same span the schedule will go on charging.
    val wholePeriod = Recurrence.addMonths(
        firstPaymentOn, -monthsPerPayment.coerceAtLeast(1).toLong(), inBikramSambat,
    )
    val from = if (startedOn > wholePeriod && startedOn < firstPaymentOn) startedOn else wholePeriod
    return Accrual(
        from = from,
        firstPaymentOn = firstPaymentOn,
        carriedInterest = carriedInterest,
        rates = rates,
        inBikramSambat = inBikramSambat,
    )
}

/** A debt, with what is still owed on it. */
data class Loan(
    val id: String,
    val name: String,
    val kind: LoanKind,
    val direction: LoanDirection,
    /** How each instalment is made up. */
    val style: InstalmentStyle,
    val lender: String?,
    /** Borrowed in full on a term loan; drawn so far on an overdraft. */
    val principal: Money,
    /** The approved ceiling on an overdraft, and null on everything else. */
    val creditLimit: Money? = null,
    /** Still owed, following the amortisation schedule where there is one. */
    val outstanding: Money,
    /**
     * What was borrowed — or lent — in all, which is not [principal] once
     * anything has happened: a lump sum rewrites that figure in place. Null on a
     * loan whose balance follows a schedule, where the walk back through the
     * payments cannot separate principal from the interest inside them.
     */
    val borrowedInAll: Money? = null,
    /**
     * What the debt was **first written down as** — the figure the two of them
     * agreed, before anything was added to it.
     *
     * Deliberately not [borrowedInAll], which is everything ever advanced: lend
     * रू 8,000 and then रू 2,000 more and that reads रू 10,000, which is also
     * what is owed, so the form said the same number twice under two headings
     * and neither of them was the sum anybody remembers agreeing. Null on a debt
     * whose balance follows a schedule, where the walk back through the payments
     * cannot separate principal from the interest inside them.
     */
    val openedAt: Money? = null,
    val currencyCode: String,
    val annualRate: Double?,
    val termMonths: Int?,
    /** Months between instalments: 1 monthly, 3 quarterly, 12 yearly. */
    val paymentEveryMonths: Int,
    val emi: Money?,
    /** The bank's first recovery date, which may be a broken period. */
    val emiStartsOn: LocalDate?,
    /** The day the money arrived, and the day interest starts running. */
    val disbursedOn: LocalDate? = null,
    /**
     * The day the bank approved a card or an overdraft — see the column on
     * `LoanEntity`. With [termMonths] it is the whole of [expiresOn], and it
     * decides nothing else.
     */
    val openedOn: LocalDate? = null,
    /** The day it must be settled by, when there are no instalments. */
    val dueOn: LocalDate?,
    /** The day the current principal figure was set — see [accrualFor]. */
    val startedOn: LocalDate,
    /** Interest a lump sum left for the next instalment to collect. */
    val carriedInterest: Money = Money.ZERO,
    /**
     * Whether a month of this debt's schedule is a Nepali month — the loan's own
     * copy of its rule's answer. See the column on `LoanEntity`.
     */
    val recurInBs: Boolean = false,
    /** What the rate has been, when the bank has moved it. Null when fixed. */
    val rates: RateSchedule? = null,
    val payFromAccountId: String?,
    /**
     * Where the money landed when it was borrowed, or left from when it was
     * lent. Null when the app was never told, which is most loans.
     */
    val disbursedAccountId: String? = null,
    /** The repeating rule paying this loan, so its projections can be found. */
    val seriesId: String? = null,
    /**
     * How many of the schedule's periods have fallen due since the balance was
     * last re-based — which is how many of its rows are behind us.
     *
     * Periods and not payments. They were the same number until an instalment
     * could be swiped away: a period that went unpaid is still a row, and the
     * payment that later collects it is still one row. Counting the payments
     * instead would slide the whole schedule back a month every time one was
     * deleted, so the next instalment would be drawn on a date that had already
     * passed. See [arrears].
     */
    val paymentsMade: Int = 0,
    /**
     * The instalments this schedule was owed and never got — see [Arrears].
     *
     * Empty on every debt nothing has been deleted from, which is nearly all of
     * them, and then every figure below is exactly what it always was.
     */
    val arrears: Arrears = Arrears.NONE,
    /** Repaid so far on a loan with no schedule, in its own currency. */
    val repaid: Money = Money.ZERO,
    val isClosed: Boolean,
    /**
     * The colour a card is drawn in, where its owner chose one.
     *
     * Null on every other debt, which keeps what a debt has always had: the
     * colour of its own figure. A card is the one debt that is *paid with*, so
     * it is found in a list the way an account is — by its colour.
     */
    val color: Color? = null,
    /** True to read this loan in the display currency rather than its own. */
    val showInDisplayCurrency: Boolean = false,
    /**
     * [outstanding] converted into the display currency, or null when no rate
     * was available. Never used for sums — only for showing the loan the way
     * the user asked to read it.
     */
    val outstandingInBase: Money? = null,
    /** [principal] converted the same way, so a row can show both consistently. */
    val principalInBase: Money? = null,
    /** [creditLimit] converted the same way. */
    val creditLimitInBase: Money? = null,
    /** [emi] converted the same way. */
    val emiInBase: Money? = null,
    /** [accruedInterest] converted the same way, so a total can include it. */
    val accruedInterestInBase: Money? = null,
    /**
     * Principal cleared outside the instalments — lump sums and principal-only
     * payments. Already reflected in [outstanding]; kept separately so the user
     * can see what their extra payments have achieved.
     */
    val principalPaidOutright: Money = Money.ZERO,
    /** Interest serviced on its own. A cost, so it never moved [outstanding]. */
    val interestPaidOutright: Money = Money.ZERO,
    /**
     * Interest built up on an overdraft's drawn balance and not yet serviced,
     * metered day by day from the draws and repayments. Null off overdrafts —
     * a scheduled loan's interest lives inside its instalments.
     */
    val accruedInterest: Money? = null,
    /**
     * Lump sums the user has dated forward, with the days they fall on.
     *
     * A payment promised for a day that has not arrived is written down and left
     * alone — there is no balance yet on that day, and reducing the debt now
     * would be wrong on every screen until it came round. So it is *not* in
     * [outstanding], and the only place it belongs is the month it falls in:
     * the timeline projects each holding to the end of the month being looked at,
     * and a debt that ignored a payment the same page has just drawn a row for
     * would sit under it contradicting it. See `LoanRepository.applyDuePayments`
     * for what folds them in when the day comes.
     */
    val pendingPayments: List<BalanceChange> = emptyList(),
) {
    /** What of [pendingPayments] will have been paid by [day]. */
    fun pendingBy(day: LocalDate): Money = Money(
        pendingPayments.filter { it.epochDay <= day.toEpochDay() }.sumOf { it.deltaMinor }
    )

    /**
     * What it would take to be done with this today: the balance, plus the
     * interest that has run on it and not yet been paid.
     *
     * The two are stated separately wherever there is room for two lines,
     * because they are different kinds of fact — the balance is settled and the
     * interest is metered to today and will be a different number tomorrow. But
     * where one figure has to stand for the debt, this is the one the user is
     * actually asking about: what they would hand over to clear it. Equal to
     * [outstanding] on anything with a schedule, where the interest is inside
     * the instalments and metering it again would count it twice.
     */
    val settleToday: Money get() = outstanding + (accruedInterest ?: Money.ZERO)

    /** The same figure in the display currency, for totals. */
    val settleTodayInBase: Money?
        get() = outstandingInBase?.let { it + (accruedInterestInBase ?: Money.ZERO) }

    /** True when the loan has a rate, a term and an instalment to schedule. */
    val hasSchedule: Boolean get() = termMonths != null && emi != null

    /**
     * True when the whole debt falls on one date rather than on a schedule.
     *
     * A payment period at least as long as the loan is one payment: the
     * principal and every day of interest together, on the day the term runs
     * out. Worth knowing outside the editor because a row saying "रू 11,000
     * each time" of a debt paid once is describing a schedule that does not
     * exist.
     */
    val paysAtEnd: Boolean
        get() = termMonths != null && termMonths > 0 && paymentEveryMonths >= termMonths

    /** Money someone owes the user, rather than a debt of theirs. */
    val isLent: Boolean get() = direction == LoanDirection.LENT

    /**
     * What the user calls *this* debt, as opposed to the bank it is with — the
     * mirror of `Account.ownName`, and the same two columns doing the same job:
     * [lender] is the bank, [name] may be the debt's own name.
     *
     * Null on every debt entered before the field existed and on every one left
     * blank, where the two say the same thing and the row says its kind instead.
     */
    val ownName: String?
        get() = when {
            // Money between people is named for the person and nothing else. A
            // few old rows carry a lender as well, from when the form asked for
            // both, and reading that as "the bank" would turn a friend's name
            // into a heading this debt sits under.
            kind == LoanKind.PERSONAL -> null
            else -> lender?.takeIf { !it.equals(name, ignoreCase = true) }?.let { name }
        }

    /**
     * A limit to draw against rather than a sum already taken.
     *
     * Nothing about a schedule applies: there is no instalment, no first payment
     * and no end date, because until money is withdrawn there is no debt at all.
     */
    val isOverdraft: Boolean get() = kind == LoanKind.OVERDRAFT

    /**
     * The day a facility runs out: the day it was approved plus its agreed
     * length.
     *
     * Never stored, because two columns for one arrangement can disagree — and
     * null unless both halves are known, since a card whose approval day the app
     * was never told has no honest expiry. Only a card or an overdraft has one;
     * a term loan's length is its schedule rather than a shelf life.
     */
    val expiresOn: LocalDate?
        get() = openedOn
            ?.takeIf { isOverdraft }
            ?.let { opened -> termMonths?.takeIf { it > 0 }?.let { opened.plusMonths(it.toLong()) } }

    /**
     * True once that day has passed.
     *
     * What it costs is being offered as somewhere money can be spent from: a
     * card the bank has retired cannot be paid with, and a chip for one in the
     * money form is an answer the user's bank would refuse. It changes nothing
     * about what is owed — an expired facility with a balance on it is still a
     * debt, and it is still listed, still repaid and still metering interest.
     */
    fun hasExpired(today: LocalDate): Boolean = expiresOn?.isBefore(today) == true

    /**
     * True when the balance follows an amortisation schedule rather than a
     * running total.
     *
     * The distinction decides how any figure about this loan may be derived. On
     * a schedule, what is owed comes from [LoanMath] and payments cannot simply
     * be subtracted; without one, the balance is a plain running total that
     * repayments and top-ups move by their own amount. Kept in step with
     * `LoanRepository.outstandingOf`, which makes the same distinction for the
     * current figure.
     */
    val amortises: Boolean
        get() = !isClosed && !isOverdraft && seriesId != null &&
            termMonths != null && termMonths > 0

    /**
     * What is still there to draw. Null when this is not an overdraft, or when
     * the limit was never recorded — an unknown headroom is a thing to leave
     * blank rather than guess at.
     */
    val available: Money?
        get() = creditLimit?.takeIf { isOverdraft }
            ?.let { Money((it.minor - outstanding.minor).coerceAtLeast(0L)) }

    /**
     * What will be owed once [extraPayments] more instalments have been made and
     * [extraRepaid] more has gone back.
     *
     * The timeline needs this because a month is shown *after* its scheduled
     * payments have run: the accounts already say where they land, and a debt
     * that ignored the same instalments would sit next to them contradicting
     * them. Derived from the schedule rather than by subtracting the payments,
     * for the reason [LoanMath] exists — most of an early instalment is
     * interest, and naive subtraction clears a loan years too soon.
     */
    fun outstandingAfter(extraPayments: Int, extraRepaid: Money): Money = when {
        isClosed -> Money.ZERO
        // Nothing scheduled to run, so the answer is the balance itself — which
        // on a month that has been and gone is not the same thing as recomputing
        // it from `principal`. That figure describes *today*: a lump sum rewrote
        // it in place, and so did every top-up. `LoanRepository.outstandingOf`
        // has already put those back for the day being asked about, and rebuilding
        // the schedule here would throw the answer away and hand back a June
        // payment in a March the money had not left in.
        //
        // A no-op on this month and every month still to come, where the two
        // computations are the same one; the branches below are what project a
        // schedule forward, and they only ever run with something to project.
        extraPayments == 0 && extraRepaid.isZero -> outstanding
        // An overdraft owes what has been drawn; nothing is scheduled against it.
        isOverdraft -> outstanding
        seriesId == null -> outstanding
        termMonths != null && termMonths > 0 -> LoanMath.outstanding(
            principal = principal,
            annualRatePercent = annualRate ?: 0.0,
            termMonths = termMonths,
            // Periods, not payments: [paymentsMade] already counts the ones that
            // went unpaid, and the projections this adds are the periods still
            // to come. Anything else would index into the schedule at a row that
            // belongs to some other month.
            periodsElapsed = paymentsMade + extraPayments,
            emi = emi,
            style = style,
            monthsPerPayment = paymentEveryMonths,
            accrual = accrual,
            missed = arrears.missed,
        )
        else -> LoanMath.outstandingSimple(principal, repaid + extraRepaid)
    }

    /**
     * The days this loan's instalments charge for. Null on one with no schedule,
     * which has no payment dates to count between.
     */
    val accrual: Accrual?
        get() = accrualFor(
            startedOn,
            BrokenPeriod.firstInstalment(
                disbursedOn, emiStartsOn, paymentEveryMonths,
                inBikramSambat = recurInBs,
            ),
            paymentEveryMonths,
            carriedInterest,
            rates,
            inBikramSambat = recurInBs,
        )

    /**
     * True when this loan's figures should be read in the display currency —
     * only if the user asked *and* there was a rate to do it with. A missing
     * rate falls back to the loan's own currency, which is always a fact.
     */
    val readInBase: Boolean get() = showInDisplayCurrency && outstandingInBase != null
}

/** A period's headline figures. */
data class PeriodSummary(
    val moneyIn: Money = Money.ZERO,
    val moneyOut: Money = Money.ZERO,
) {
    val net: Money get() = moneyIn - moneyOut
    val isEmpty: Boolean get() = moneyIn.isZero && moneyOut.isZero
}

/** One row of the "where it went" list, already ranked and shareable. */
data class HoldingBreakdown(
    val accountId: String?,
    val accountName: String?,
    /**
     * The rest of what names this holding — see `holdingDisplayName`. Null on a
     * slice that is a debt, which is named by the arrangement instead.
     */
    val accountInstitution: String? = null,
    val accountKind: AccountKind? = null,
    val accountCurrency: String? = null,
    /**
     * How many holdings sit under the same bank name, which decides whether
     * naming the kind distinguishes anything. See `holdingDisplayName`.
     */
    val accountSiblings: Int? = null,
    /** Set when the slice is a debt, so the row can say which arrangement. */
    val loanKind: LoanKind? = null,
    val color: Color?,
    /**
     * What the slice comes to in the display currency. Every total in the app is
     * summed from this figure, this one included — it is the only one that can be
     * added to the slice beside it.
     */
    val total: Money,
    /**
     * What the slice comes to in the currency the money was actually in, where
     * that is one currency and not the display one.
     *
     * A dollar account's spending is dollars. Drawing only [total] against it put
     * a rupee figure on a row the user knows in dollars — a valuation stated as
     * though it were the transaction, which is exactly what the two-line rule
     * exists to stop. So this leads and [total] goes underneath, and the bar and
     * the ranking go on being worked out from [total] alone.
     *
     * Null when the slice is already in the display currency, or when more than
     * one currency went into it — there is no honest single figure for that, and
     * inventing one by summing across currencies is always a bug.
     */
    val ownTotal: Money? = null,
    val ownCurrency: String? = null,
    val entryCount: Int,
    /** 0f..1f share of the period's total. Precomputed so the UI does no maths. */
    val share: Float,
)

/** A day's entries with its own totals, which is how the timeline is drawn. */
data class DayGroup(
    val date: LocalDate,
    val entries: List<MoneyEntry>,
    val moneyIn: Money,
    val moneyOut: Money,
)

/**
 * The palette offered when naming a holding.
 *
 * Fifteen, walked once round the wheel with the two neutrals at the end, so that
 * no two are close enough to be mistaken for each other at the size they are
 * actually read — a dot beside a row, a bar in the breakdown. Ten was too few
 * for somebody with a bank account, a wallet, a cash tin, two deposits and three
 * debts, and two of the ten were greens a shade apart: a holding found by its
 * colour on one page and looked for on the next was a coin toss between them.
 *
 * It **starts at the teal**, which is what a holding nobody has coloured gets —
 * see [at]. Leading with the red would hand the app's own "this is money going
 * the wrong way" to every first account somebody opens.
 *
 * **Three of them are reds**, deliberately, and they are three genuinely
 * different reds rather than one repeated: a debt is what people reach for a red
 * for — an EMI going out is the row they want to spot — and one red for a card,
 * a term loan and money owed to a friend is no colour at all. Rose and rust are
 * far enough from the crimson to survive being drawn side by side.
 *
 * Every one is mid-dark and mid-saturated on purpose. These are drawn on cool
 * paper *and* on a near-black page, so anything pale vanishes in the light
 * scheme and anything deep vanishes in the dark one — and none of them may be
 * mistaken for money's own red and green, which mean something else entirely.
 */
object HoldingPalette {
    val colors: List<Color> = listOf(
        Color(0xFF2E7D6F), // teal
        Color(0xFF3F8A45), // green
        Color(0xFF7A8B2B), // olive
        Color(0xFFC79020), // amber
        Color(0xFFD2701F), // orange
        Color(0xFFA64B2A), // rust
        Color(0xFFC0392B), // red
        Color(0xFFC2416F), // rose
        Color(0xFFA8408F), // magenta
        Color(0xFF7A5BC0), // violet
        Color(0xFF4A54B8), // indigo
        Color(0xFF3E7BB6), // blue
        Color(0xFF17879B), // cyan
        Color(0xFF8B5A3C), // brown
        Color(0xFF5E6B7A), // slate
    )

    fun at(index: Int): Color = colors[((index % colors.size) + colors.size) % colors.size]
}
