package com.mywallet.ui.screens

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.R
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.PlanRepository
import com.mywallet.data.repo.InterestRepository
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.SaveResult
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Account
import com.mywallet.domain.AccountMovement
import com.mywallet.domain.BankHolding
import com.mywallet.domain.BrokenPeriod
import com.mywallet.domain.FixedDeposit
import com.mywallet.domain.HoldingChoice
import com.mywallet.domain.HoldingGroup
import com.mywallet.domain.INTEREST_POSTING_SUFFIX
import com.mywallet.domain.Goal
import com.mywallet.domain.Insurance
import com.mywallet.domain.PersonHolding
import com.mywallet.domain.HoldingPalette
import com.mywallet.domain.LoanMath
import com.mywallet.domain.MoneyEntry
import com.mywallet.domain.PeriodicPlan
import com.mywallet.domain.RateChange
import com.mywallet.domain.RateSchedule
import com.mywallet.domain.Recurrence
import com.mywallet.domain.SavingsInterest
import com.mywallet.domain.accrualFor
import com.mywallet.domain.payableHoldings
import com.mywallet.ui.labelRes
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * One form for everywhere money sits, including the places it is owed from.
 *
 * The [choice] decides which half of this state is in play: an account has a
 * balance and a colour, a loan has a rate and a schedule. Both halves are kept
 * rather than cleared when the choice changes, so exploring the options and
 * coming back does not lose what was typed.
 */
/**
 * One instalment of the repayment schedule, ready to draw as a row of the table.
 *
 * Formatted here rather than in the Composable, like every other figure in this
 * form: the screen prints strings and never does arithmetic on money.
 *
 * The figures carry no currency symbol, because the table states its currency
 * once above itself — see [MoneyFormatter.formatBare]. Everything outside the
 * table still says it on every amount.
 */
data class ScheduleRow(
    val number: Int,
    /** When it falls, when the loan has a start date to count from. */
    val date: LocalDate?,
    val payment: String,
    val principal: String,
    val interest: String,
    /** What is still owed once it has been paid. */
    val balance: String,
)

/**
 * One line of "what this rate has been": the figure, and the day it started.
 *
 * Carried unformatted because the date has to be drawn in whichever calendar the
 * user reads, and that is the screen's job — the same way the timeline draws a
 * day. The arithmetic is done by then; this is only a label.
 */
data class RateHistoryRow(val annualRate: Double, val from: LocalDate)

/** One quarter the bank actually paid: the day, and how much. */
data class InterestPaidRow(val on: LocalDate, val amount: String)


/**
 * What the figure beside "Pay every" is counted in.
 *
 * The first two are the same pair the length above them is given in, and for
 * the same reason: a gap is a number and a unit, and the four named
 * frequencies could not say "every two months" however common that is.
 *
 * [AT_END] is not a unit at all but the answer to the same question — the debt
 * is settled in one payment when the term runs out, principal and every day of
 * interest together. It is stored as a period as long as the loan itself, which
 * is exactly what it is: one period, one payment. Nothing special computes it.
 */
enum class PayEvery { MONTHS, YEARS, AT_END }

/**
 * One of a bank's holdings, as a way into it from any of the others.
 *
 * Exactly one of [accountId] and [loanId] is set — the two are stored in
 * different tables and opened by different routes, and the tab has to say which
 * without the screen guessing from the label.
 */
data class HoldingTab(
    val accountId: String? = null,
    val loanId: String? = null,
    /**
     * What the user called this one, where they called it anything. Two deposits
     * at one bank are otherwise two tabs reading "Fixed deposit".
     */
    val ownName: String? = null,
    /** What the bank calls it: "Savings", "Loan", "Overdraft". */
    val labelRes: Int,
    /**
     * The currency to print after the kind, or null to print the kind alone.
     *
     * The last thing tried when two of a bank's holdings would otherwise wear
     * the same word. Two savings accounts at one bank, one in rupees and one in
     * dollars, gave a row of tabs reading "Savings" twice — two chips that
     * looked like the same holding drawn by mistake, and no way to tell which
     * one a tap would open.
     *
     * Set only where it actually settles the question: it is null on a holding
     * the user named (they have already said what to call it), null where the
     * kind is unique at that bank, and null where the currencies inside a
     * clashing group repeat — two unnamed rupee deposits are still two chips
     * reading the same thing, and "Fixed deposit (NPR)" twice would say so at
     * greater length.
     */
    val currencyCode: String? = null,
    /**
     * The whole of what this tab says, where the kind would say nothing.
     *
     * One provider's wallets are all "Wallet", so the word is dropped and each
     * tab is named by what it actually holds — see `walletTabs`. Null everywhere
     * else, and the kind and its currency are assembled the usual way.
     */
    val literal: String? = null,
    /** True for the holding whose form is currently open. */
    val isCurrent: Boolean = false,
)

/**
 * Takes the currency back off every tab that reads plainly without it.
 *
 * The tabs are built carrying their currency and this decides which ones keep
 * it, rather than each tab working out on its own whether it clashes — the
 * question is about the row as a whole and cannot be answered one chip at a
 * time.
 *
 * A currency is kept only where it settles the question it was added for. Where
 * the clashing holdings do not all have distinct currencies it is dropped from
 * the whole group: two unnamed rupee deposits qualified with "(NPR)" are still
 * two chips saying the same thing, and saying it twice as long is worse than
 * leaving them alone.
 */
private fun List<HoldingTab>.disambiguated(): List<HoldingTab> {
    // Two tabs clash when they would draw the same words — the name the user
    // gave this one, or failing that the kind. That is exactly what
    // `holdingLabel` reads, so the two cannot disagree about what a clash is.
    fun clashKey(tab: HoldingTab): String =
        tab.ownName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "kind:${tab.labelRes}"

    val groups = groupBy(::clashKey)
    return map { tab ->
        val group = groups.getValue(clashKey(tab))
        val codes = group.mapNotNull { it.currencyCode }
        val settles = group.size > 1 &&
            // A holding the user named has already been told apart by its name.
            tab.ownName.isNullOrBlank() &&
            codes.size == group.size &&
            codes.distinct().size == codes.size
        if (settles) tab else tab.copy(currencyCode = null)
    }
}

/**
 * Where a statement row goes when it is tapped.
 *
 * Two destinations and a third answer, which is nowhere. A payment towards a
 * debt opens the debt — that is the rule everywhere in the app, because the
 * money form has no field for what such a payment did and re-saving it there
 * would quietly unpick it. Everything else the user wrote opens on the money
 * form. And interest the app credited to *this* account opens nothing: it would
 * land on the page it was tapped on, and it is the app's own working rather than
 * anything anyone typed.
 */
sealed interface StatementTarget {
    data class Entry(val entryId: String) : StatementTarget
    data class Loan(val loanId: String) : StatementTarget
}

/** One line of the account's statement, formatted and ready to draw. */
data class StatementRow(
    /**
     * The movement itself, so the row can be named the way the timeline names
     * it — see `entryTitle`. It used to arrive here as a finished title built
     * from the *label*, which is the one thing on a movement that groups rather
     * than identifies: a bank's statement read "Loan payment" down every line of
     * the column that exists to tell its payments apart.
     */
    val entry: MoneyEntry,
    val amount: String,
    val balanceAfter: String,
    /** Everything but a loan's own instalment — see [AccountMovement.fromLoanSchedule]. */
    val canDelete: Boolean = true,
    /** Where a tap leads, or null for a row that would open its own page. */
    val opens: StatementTarget? = null,
) {
    val id: String get() = entry.id
    val on: LocalDate get() = entry.occurredOn
    val isIn: Boolean get() = entry.direction == Direction.IN
}

data class HoldingEditorState(
    val isEditing: Boolean = false,
    /**
     * True while a holding is on its way out of the database.
     *
     * An editor opened on an existing holding starts as the *create* form —
     * empty boxes, "What is it?" chips — and becomes itself a frame or two
     * later. Nobody notices that arriving from a list, where the screen slides
     * in over another; between one bank's tabs, where nothing slides, it was a
     * blink of the wrong form. So the form waits until it knows what it is.
     */
    val isLoading: Boolean = false,
    val choice: HoldingChoice = HoldingChoice(),
    /**
     * Which answers may be picked. Narrower when editing — see [HoldingChoice].
     *
     * Taken from the same functions the editor uses on load, rather than from
     * `entries`: a current account is a stored kind that is no longer offered,
     * and hard-coding the full list here would put it back on the Add form.
     */
    val groups: List<HoldingGroup> = HoldingChoice.groupsFor(null),
    val bankHoldings: List<BankHolding> = HoldingChoice.bankHoldingsFor(null),
    val personHoldings: List<PersonHolding> = HoldingChoice.personHoldingsFor(null),

    val name: String = "",
    /**
     * What the user calls this one holding, as opposed to the bank it is at.
     *
     * Optional and often blank. Two fixed deposits at one bank are two rows both
     * reading "Fixed deposit", and only their owner knows which is the one for
     * the house — so "Emergency FD" is theirs to write. Blank means the row goes
     * on saying what kind it is, which is the right answer for the one savings
     * account at a bank that has only one.
     */
    val holdingName: String = "",
    /**
     * Banks already on file, offered as chips so the second and third holding at
     * the same bank cost a tap rather than the name typed out again — and,
     * because they are typed once, spelled the same way in every list.
     */
    val knownBanks: List<String> = emptyList(),
    /** The wallet providers already on file — see [knownBanks]. */
    val knownWallets: List<String> = emptyList(),
    /**
     * Everything this bank holds, the one being edited included.
     *
     * A savings account, a fixed deposit, a term loan and an overdraft at one
     * bank are one relationship the user thinks of by the bank's name, and they
     * used to be four screens reachable only by backing out to the list. Empty
     * unless a bank's holding is open — money with a person or in a wallet has
     * no siblings to move between.
     */
    val bankTabs: List<HoldingTab> = emptyList(),
    val currencyCode: String = "NPR",
    /**
     * The few currencies offered before the rest are asked for, most likely
     * first — the display currency, then whatever the user's other holdings are
     * in, then the app's guesses. See [CurrencyOption.shortlist].
     *
     * Held in state rather than worked out where it is drawn, because it must
     * not move: it is settled when the form loads and the chips stay where the
     * eye left them however many times the answer changes.
     */
    val currencyChoices: List<CurrencyOption> = CurrencyOption.SUGGESTED,
    /** The currency totals are read in, for offering to convert into it. */
    val baseCurrencyCode: String = "NPR",
    /**
     * How often this bank credits the interest, as it is being typed — the digits
     * alone, in whatever unit [payoutInYears] says.
     *
     * A fact about the account and not a preference, which is why it is asked
     * here beside the rate rather than once in Settings: the two of them are one
     * question — what does this account pay, and how often — and somebody with
     * savings at two banks has two answers to it.
     */
    val payoutText: String = SavingsInterest.DEFAULT_EVERY_MONTHS.toString(),
    val payoutInYears: Boolean = false,
    /**
     * Whether the interval has stopped being a question.
     *
     * True the moment a period has actually been credited. Every figure on file
     * was worked out from it, and changing it then does not describe a new
     * arrangement — it moves the payout days, sweeps the postings that no longer
     * land on one, and stops the passbook the user reconciled against from
     * matching. Before that first credit there is nothing to rewrite, so it stays
     * open: an account entered with the wrong rhythm can be corrected as long as
     * the correction costs nothing.
     */
    val payoutSettled: Boolean = false,
    /**
     * Which calendar a plan's premiums or contributions count in.
     *
     * The stored answer on a plan already on file, and the calendar being read
     * on one being set up — the same rule a repeating entry follows. The card's
     * table of dates is drawn from this, so without it the preview would name
     * days the rule will not produce.
     */
    val planRecurInBs: Boolean = false,
    /**
     * Whether this bank counts the holding's months in Nepali ones — the
     * checkbox, and the opt-in stored on the holding itself.
     *
     * Not the effective answer. Reading dates in Nepali is a display choice; a
     * bank closing its quarters on 1 Baisakh or debiting on 1 Shrawan is a fact
     * about the arrangement, and most do neither. Off unless the user says so.
     */
    val interestInBs: Boolean? = null,
    /** Whether the calendar being read is Bikram Sambat. */
    val calendarIsNepali: Boolean = false,
    /**
     * Whether the app is being read in Nepali, which is the other half of
     * whether the calendar question is worth asking — see
     * [offersInterestCalendar] and `AppSettings.readsNepali`.
     */
    val languageIsNepali: Boolean = false,

    // ---- an account -------------------------------------------------------
    /**
     * The bank this account was filed under, back when the form asked for it
     * separately from the name. No longer editable — the one name field is the
     * bank — but carried through a save so accounts created before the merge
     * keep the grouping they were given.
     */
    val institution: String = "",
    val openingText: String = "",
    /**
     * The same figure formatted, for reading beside the name once it is settled.
     * Not [openingText], which is the raw digits of the box it was typed into —
     * grouping belongs on a figure being read and gets in the way of one being
     * typed.
     */
    val openingDisplay: String? = null,
    val color: Color = HoldingPalette.colors.first(),
    /** Show this account's money converted into [baseCurrencyCode]. */
    val showInDisplayCurrency: Boolean = false,

    // ---- money put away for a term ----------------------------------------
    /**
     * The day the money went in — the fact the user has. The day it comes free
     * falls out of this plus the agreed length, so it is worked out rather than
     * asked for a second time.
     */
    val depositStartedOn: LocalDate? = null,
    /** Where the whole of it lands when it comes free. */
    val depositIntoAccountId: String? = null,
    /** What it will be worth, the interest that gets it there, and when. */
    val depositMaturityValue: String? = null,
    val depositTotalInterest: String? = null,
    val depositMaturesOn: LocalDate? = null,
    /** Which required answer is missing, as the message to show for it. */
    val depositError: Int? = null,

    // ---- a policy ---------------------------------------------------------
    /**
     * What the insurer hands over at the end, as typed and as read.
     *
     * Asked rather than worked out. An endowment policy pays back more than the
     * premiums and a term plan pays back nothing, and the difference is the
     * insurer's business — dividing this by the number of payments would quote a
     * premium that disagrees with the policy document. Both figures are printed
     * on it, so both are asked for.
     */
    val maturityText: String = "",
    val maturityDisplay: String? = null,
    /** What one premium costs — the figure the card leads with. */
    val premiumText: String = "",
    val premiumDisplay: String? = null,
    /** How many premiums the term holds, and what they come to in all. */
    val premiumCount: Int = 0,
    val premiumTotal: String? = null,
    /** The day each one falls, for the table under the card. */
    val premiumDates: List<LocalDate> = emptyList(),
    /**
     * What a policy hands back beyond the premiums, already made absolute, with
     * [policyShortfall] saying which way round it is.
     *
     * A policy only. A goal hands back exactly what was put into it — that is
     * what makes its bar a fact rather than a forecast — so a line about the
     * difference there would be a line saying zero.
     */
    val policyGain: String? = null,
    val policyShortfall: Boolean = false,
    /**
     * The day the policy pays out, or the day a goal is meant to be reached.
     *
     * Derived — the day it started plus the agreed length — and on a policy
     * editable from either end: someone who knows their policy matures in 2091
     * should not have to count the months, so picking a date there sets the
     * length instead. The length stays the stored fact, so the date snaps to a
     * whole month.
     *
     * A goal only ever shows it. There the length is the answer being given —
     * "in two years" is how a person sets one — and the date is what falls out.
     */
    val policyMaturesOn: LocalDate? = null,
    /**
     * True when one premium is not less than the whole payout.
     *
     * A policy that hands back less than every premium put into it is a real
     * arrangement — that is a term plan, and [policyShortfall] is what says so.
     * One whose *single* premium is larger than the whole payout is not:
     * nobody pays रू 5,000 a month towards रू 500. It is a figure typed into
     * the wrong box, so the card stops there rather than quoting a schedule
     * for an arrangement nobody has.
     */
    val policyPremiumTooBig: Boolean = false,

    // ---- a goal -----------------------------------------------------------
    /**
     * How far along a goal is: what has been put aside, against what it is for.
     *
     * Both read off the holding rather than the plan, so the bar says what has
     * actually happened. A goal that has been paid into twice is two payments
     * along whatever the schedule intended.
     */
    val goalSaved: String? = null,
    val goalSavedFraction: Float = 0f,
    val goalLeft: String? = null,
    /** True once nothing is left to put aside. */
    val goalReached: Boolean = false,
    /**
     * Money going into or out of a goal outside its plan.
     *
     * Two cards, one shape: an amount, the day it moved, and the account it
     * moved through. What either does is change how long the goal still needs —
     * the saving each time is what the user said they can manage, so that is the
     * thing that must not move.
     */
    val goalDepositText: String = "",
    val goalWithdrawText: String = "",
    val goalMoveDate: LocalDate = LocalDate.now(),
    val goalDepositAccountId: String? = null,
    val goalWithdrawAccountId: String? = null,
    /** What the goal would hold afterwards, and the day it would then be due. */
    val goalDepositAfter: String? = null,
    val goalDepositReadyOn: LocalDate? = null,
    val goalWithdrawAfter: String? = null,
    val goalWithdrawReadyOn: LocalDate? = null,
    /** Set when more is being taken out than the goal holds. */
    val goalWithdrawTooMuch: Boolean = false,

    // ---- a loan -----------------------------------------------------------
    val principalText: String = "",
    /** The same figure formatted, for showing beside the name once it is settled. */
    val principalDisplay: String? = null,
    val rateText: String = "",
    val termText: String = "",
    val termInYears: Boolean = false,
    /**
     * How often an instalment falls due, as the figure the user typed.
     *
     * Changes the arithmetic, not just the dates: interest accrues over the gap,
     * so the same loan paid every two months genuinely costs more than one paid
     * monthly.
     */
    val payEveryText: String = "1",
    val payEveryUnit: PayEvery = PayEvery.MONTHS,
    /**
     * The bank's own instalment, where one is on file.
     *
     * No longer asked for: a figure typed into a box beside the computed one was
     * a second answer to a question already answered, and the app's own
     * arithmetic is what every other figure on the form is built from. Loans
     * saved with a lender's rounding keep it — it is read here and written back
     * untouched, so re-saving one never quietly restates its instalment.
     */
    val emiText: String = "",
    /** How each instalment is made up: level, principal plus interest, or interest. */
    val style: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
    /** Accounts the instalment could be taken from. */
    val accounts: List<Account> = emptyList(),
    val payFromAccountId: String? = null,
    /**
     * The account the money itself moved through when the debt was made: where a
     * borrowing landed, or the one a lending left from.
     *
     * Null is a real answer and the default — plenty of money between people is
     * written down long after it moved, against a balance the user has already
     * corrected by hand, and crediting it again would double it.
     */
    val disbursedAccountId: String? = null,
    /**
     * The bank's first recovery date. The user's to choose — it used to be
     * silently a month after the loan was entered, which is only true of a
     * bank's default.
     */
    val emiStartsOn: LocalDate? = null,
    /**
     * The day the money arrived. Interest runs from here, and the gap between
     * this and the first recovery date is what makes a broken period.
     *
     * The same fact on money between people, where it is the whole of what the
     * form asks about *when*: the day it was borrowed or lent is the day the
     * debt began, and interest is counted from it to today. It used to ask for a
     * day to clear it by instead, which nobody filled in and nothing could be
     * measured from.
     */
    val disbursedOn: LocalDate? = null,
    /**
     * The day a card or an overdraft was approved.
     *
     * Deliberately not [disbursedOn]: a facility is approved long before
     * anything is drawn on it, and interest is metered from the day money
     * actually moved. This decides one thing only — with the length beside it,
     * the day the card runs out — so it stays editable for good, because a bank
     * renewing a card rewrites nothing the app has already worked out.
     */
    val openedOn: LocalDate? = null,
    /**
     * Today, from the injected clock — set in `init`, never read from the
     * platform here, so a test that freezes time gets the day it asked for.
     *
     * Only the facility's expiry reads it. `LocalDate.MIN` is the "not yet
     * known" default, which for that one comparison means "nothing has expired",
     * and the state is stamped before anything can be drawn.
     */
    val today: LocalDate = LocalDate.MIN,
    /**
     * The earliest day anything about this holding can honestly have happened —
     * the day the money changed hands, or failing that the day the arrangement
     * itself began.
     *
     * Every other date on the form is measured from it, so the calendar is
     * floored at it: a first instalment before the money arrived, a repayment of
     * a debt that did not exist yet, or a rate agreed a year before the loan was
     * taken are each a date the app would then have to make sense of. The
     * calendar saying no is the cheapest place to answer that, and it says it
     * where the user is already looking.
     *
     * Null on a debt entered before the app asked for a disbursement date, where
     * there is no such day on file and inventing one would refuse dates that are
     * perfectly true.
     */
    val movedOn: LocalDate? = null,

    // ---- a rate that moves ------------------------------------------------
    /**
     * The day a newly typed rate started applying.
     *
     * Only asked once the user actually changes the rate on something that
     * already exists, because that is the only time the answer matters: a bank
     * moves its rate on a date, and everything before that date was earned or
     * charged at the old one.
     */
    val rateChangedOn: LocalDate? = null,
    /** What this holding's rate has been, newest first, once it has moved. */
    val rateHistory: List<RateHistoryRow> = emptyList(),
    /** Every quarter's interest the bank has paid this account, newest first. */
    val interestPaid: List<InterestPaidRow> = emptyList(),
    /** All of it added up — the figure the expander leads with. */
    val interestTotal: String? = null,
    /**
     * Which account this form is open on, or null while creating one — what the
     * statement's own page is read by.
     */
    val accountId: String? = null,
    /**
     * How many movements it has, and nothing more about them. The rows live on
     * [AccountStatementScreen] now; all this card needs to know is whether there
     * is a page worth offering.
     */
    val movementCount: Int = 0,
    /**
     * What just happened, for the snackbar.
     *
     * Every card on this form acts **in place** — a payment, more borrowed,
     * money into a goal — and then reads the holding back rather than closing,
     * because what the user wants next is the figure their action produced. The
     * cost of that is a page which rearranges itself under the thumb: the
     * preview and the two option buttons vanish, the card shrinks, and the only
     * evidence anything happened is a number further up that the eye was not on.
     * It read as the screen glitching. This says what was done and what it left,
     * and goes away on its own.
     */
    val message: HoldingMessage? = null,
    /** What it holds now — the figure the statement works down from. */
    val balanceNow: String? = null,
    /** The day it must be settled by, for a loan with no instalments. */
    val dueOn: LocalDate? = null,
    /** The first payment worked out from principal, rate, term and style. */
    val quotedEmi: String? = null,
    /**
     * The whole sum to hand back on a debt settled in one go: what was borrowed
     * plus simple interest over the agreed length. The same arithmetic a fixed
     * deposit uses, because it is the same arrangement from the other side.
     */
    val totalToRepay: String? = null,
    /** The interest inside [totalToRepay]. */
    val interestToRepay: String? = null,
    /**
     * The day it is owed back: the day it changed hands plus the agreed length.
     *
     * Worked out rather than asked. The form used to ask for a day to clear it
     * by as well, which is a third fact that can only agree or disagree with the
     * two the user has already given.
     */
    val oneGoDueOn: LocalDate? = null,
    /** The last payment, shown only when it differs from the first. */
    val finalPayment: String? = null,
    /** True when the schedule eases off rather than ending in a balloon. */
    val finalPaymentIsLower: Boolean = false,
    val totalInterest: String? = null,
    /** Months the loan runs for, when the monthly principal decides it. */
    val derivedTermMonths: Int? = null,
    /**
     * How the next instalment divides.
     *
     * The instalment figure on its own says what leaves the account but not what
     * it achieves: on an early payment most of it is interest, and a user who
     * expects the debt to fall by the whole amount will think the app has lost
     * their money. Said in prose, so both figures carry their currency.
     */
    val nextSplitPrincipal: String? = null,
    val nextSplitInterest: String? = null,
    /**
     * Every instalment still to come, for the table under the card.
     *
     * Starts at the next payment due, not at the first ever made, so reopening a
     * loan halfway through shows the half that is left rather than the history.
     */
    val scheduleFromHere: List<ScheduleRow> = emptyList(),
    /**
     * Periods of the schedule that have fallen due against the *current*
     * balance, which is how many rows [scheduleFromHere] drops. Internal
     * bookkeeping: a lump sum re-bases the loan and resets this to nothing.
     */
    val paymentsMade: Int = 0,
    /**
     * Which of those periods went unpaid — see [com.mywallet.domain.Arrears].
     *
     * Carried so the preview charges the same days the saved loan does: a period
     * nobody paid clears no principal and hands its interest to the payment that
     * collects it, so a schedule built without them quotes a split this debt is
     * not on.
     */
    val missedPeriods: Set<Int> = emptySet(),
    /**
     * Payments actually handed over since the debt began — every instalment,
     * every lump sum, and the interest charged for the broken first period.
     *
     * What the table says above itself, because it is the question the reader is
     * asking. [paymentsMade] answers a different one and answered it out loud:
     * it read "1 already paid before this" the moment a lump sum landed on a
     * loan with a year of instalments behind it.
     */
    val paymentsSoFar: Int = 0,
    /** What is still owed, shown when editing an existing loan. */
    val outstanding: String? = null,
    /** Principal cleared so far — the loan amount less what is still owed. */
    val principalCleared: String? = null,
    /** Headroom left on an overdraft: the limit less what has been drawn. */
    val available: String? = null,
    /**
     * True when money has actually been taken from this overdraft.
     *
     * An overdraft with nothing drawn owes nothing, so there is nothing to pay
     * back and nothing for a rate change to apply to. Both offers are withheld
     * until there is a balance for them to act on.
     */
    val hasDrawnBalance: Boolean = false,
    /** Interest built up on the balance and not yet serviced. */
    val accruedInterest: String? = null,
    /**
     * The balance and the interest on it together — what it would take to be
     * done with the loan today.
     *
     * The two are kept apart above and added up here on purpose. What is owed is
     * a settled figure; the interest is metered to today and will be a different
     * number tomorrow, so folding it into the balance would make a moving figure
     * look like a fixed one. The sum is still the question anyone actually asks.
     */
    val settleToday: String? = null,
    val principalPaidOutright: String? = null,
    val interestPaidOutright: String? = null,

    // ---- a lump sum -------------------------------------------------------
    val prepayText: String = "",
    /**
     * The day the money actually moved.
     *
     * Not always today: a debt between people is usually written down after the
     * fact, and a list of payments is only worth reviewing if each one carries
     * the date it really happened on.
     */
    val prepayDate: LocalDate = LocalDate.now(),
    /**
     * The account this particular payment moves through.
     *
     * Asked inside the card rather than once at the top of the form, because it
     * is a fact about *this* payment: a lump sum handed over in cash and an
     * instalment the bank debits are the same debt and different accounts. It
     * starts at whichever account the loan already uses and changes nothing that
     * has already happened — only the row about to be written.
     */
    val prepayAccountId: String? = null,
    /**
     * What this payment was about, in the user's own words. Optional.
     *
     * Written onto the entry in place of the debt's name, which is what these
     * rows carry when nothing is said. It never becomes the row's title — the
     * debt does, on every one of them — but it is what tells three payments to
     * the same person apart wherever they are listed. See `loanMovementLabel`.
     */
    val prepayNote: String = "",
    val prepayShorterMonths: Int? = null,
    val prepayLowerEmi: String? = null,
    val prepaySavedByShortening: String? = null,
    val prepaySavedByLowering: String? = null,
    val prepayNewBalance: String? = null,

    // ---- more of the same -------------------------------------------------
    /** How much more has been lent or borrowed on the same arrangement. */
    val moreText: String = "",
    val moreDate: LocalDate = LocalDate.now(),
    /**
     * Where this addition lands or leaves from — the same per-movement question
     * the lump sum asks, defaulting to the account the arrangement was made
     * through rather than the one it is repaid through.
     */
    val moreAccountId: String? = null,
    /** What this addition was for — the same optional note a payment takes. */
    val moreNote: String = "",
    /** What would be owed afterwards, shown before anything is committed. */
    val moreNewBalance: String? = null,

    /**
     * True once the user has asked to add interest to a holding that had none —
     * a loan's terms, or an account's rate.
     *
     * One flag for both, because it is one act: the offer reads the same on
     * either, and a holding is only ever one of the two.
     */
    val termsRevealed: Boolean = false,
    /**
     * Whether this holding ever had a rate at all.
     *
     * It decides what the date beside a newly typed rate is asking. On one that
     * had a rate, the answer is when it *moved*; on one that never did — money
     * between people, written down as a bare amount and given a rate
     * afterwards — nothing moved and the question is when the new one starts
     * applying from.
     */
    val hadRate: Boolean = false,
    /**
     * Whether the *holding itself* carries a rate, as opposed to one given to it
     * afterwards in a dated change. Only the second kind can be undone whole.
     */
    val hadOpeningRate: Boolean = false,

    /**
     * Whether anything has happened to this loan yet.
     *
     * The way into the statement is withheld until there is one. On money
     * between people an empty ledger is the normal state for a while — nothing
     * is scheduled, so nothing exists until a payment is recorded — and a button
     * that opens a blank page is worse than no button.
     */
    val hasMovements: Boolean = false,

    val nameError: Int? = null,
    /**
     * Said under the currency chips, because that is where the answer is.
     *
     * One provider's wallets are told apart by what each holds — see the tabs on
     * an existing one — so a second Wise in dollars beside the first is two rows
     * with the same name and the same subtext, indistinguishable in every list
     * that draws them and pointing at two tabs reading "USD". What the user
     * meant is nearly always the wallet they already have.
     */
    val currencyError: Int? = null,
    val amountError: Boolean = false,
    val isSaved: Boolean = false,
) {
    val isLoan: Boolean get() = choice.isLoan

    /** True when the loan is repaid in equal slices of principal. */
    val isPrincipalOnly: Boolean get() = style == InstalmentStyle.PRINCIPAL_ONLY

    /**
     * Whether the amount borrowed is a record rather than a field.
     *
     * On a running loan it is: what is owed falls by paying, and retyping the
     * figure would move the balance with no payment behind it. An overdraft is
     * the exception — its box is the approved ceiling, and editing the ceiling
     * never touches what has been drawn.
     */
    val principalSettled: Boolean get() = isEditing && isLoan && !isOverdraft

    /**
     * The approved ceiling on a running overdraft, which is a fact and not a
     * field.
     *
     * It used to stay editable on the reasoning that changing the ceiling never
     * touches what has been drawn — true, but the bank sets it, not the
     * borrower, and a box invited typing into the one figure on the screen the
     * user has no say over. It sits beside the name now, where it reads as part
     * of what the facility *is*.
     */
    val limitSettled: Boolean get() = isEditing && isOverdraft

    /** Either figure that pairs with the name once the debt exists. */
    val amountPairsWithName: Boolean get() = principalSettled || limitSettled

    /**
     * What the figure a debt is entered with is called.
     *
     * One word — "Amount" — used to serve all of them, on the reasoning that the
     * chips or the tabs above have already said what kind of debt this is. That
     * holds while the answer is still being given and stops holding once it is:
     * on a debt already on file the chips are gone, the figure is settled text
     * beside a name, and "Amount" is then the only thing on the line that does
     * not say what it is.
     *
     * So each kind says its own, and the two that a reader could otherwise
     * misread say it on the way in as well. A card's is the ceiling the *bank*
     * approved rather than anything drawn against it; a bank loan's is the sum
     * agreed. Money between people keeps the bare word while it is being
     * written down — the heading above it already reads "I borrowed" or "I
     * lent" — and becomes the *starting* amount afterwards, because by then what
     * is owed has moved and the card below says so.
     */
    val amountLabelRes: Int
        get() = when {
            isOverdraft -> R.string.accounts_amount_limit
            choice.group == HoldingGroup.PERSON ->
                if (isEditing) R.string.accounts_amount_starting else R.string.accounts_amount
            isLoan -> R.string.accounts_amount_loan
            else -> R.string.accounts_amount
        }


    /**
     * Whether to offer adding interest to a holding recorded without any.
     *
     * Only where there is genuinely nothing on file. On a debt that means no
     * rate, no length and no instalment: it was removed once for offering
     * paperwork to the one kind of debt that exists to avoid it, and is back
     * because the other half of that is true too — a loan between people that
     * *does* start charging is agreed in a sentence, months after the money
     * moved, and re-entering the debt to record it lost every payment made
     * against it.
     *
     * **And on an account, no rate.** Plenty of savings accounts are opened in
     * this app as somewhere for money to sit, and reopening one then showed an
     * empty "Interest rate" beside an "Interest paid every" reading 3 months and
     * two lines explaining an arithmetic the account does none of — four things
     * to read past, all of them about a bank arrangement the user never said
     * they had. Cash, a wallet and a debt already withhold the whole block; this
     * is the same rule for the one holding that *might* have a rate and does
     * not. A deposit is excluded: what it earns is the whole of what it is, so a
     * rate it has no answer for is a fact missing rather than one not applicable.
     */
    val offersInterest: Boolean
        get() = isEditing && when {
            isLoan -> !showsTerms
            earnsInterest && !isFixedDeposit -> !showsRate
            else -> false
        }

    /**
     * Whether to draw an account's rate at all.
     *
     * Always while creating — that is when the arrangement is being described.
     * On one already on file only if there is a rate, or if the user asked for
     * the boxes: an account opened as somewhere for money to sit stays that way,
     * and one whose bank starts paying can still be told so.
     */
    val showsRate: Boolean get() = !isEditing || rateText.isNotBlank() || termsRevealed

    /**
     * Whether the figure being saved stops the interest rather than moving it.
     *
     * Zero and an emptied box are the same answer — there is no rate any more —
     * and the date beside it is then saying when the charging stops, not when a
     * new figure starts. Said in those words, because "rate changed from" over a
     * blank box asks the user to work out what a blank rate means.
     */
    val cancelsInterest: Boolean
        get() = asksWhenRateChanged && (rateText.toDoubleOrNull() ?: 0.0) <= 0.0

    /**
     * Whether that leaves no interest ever charged, rather than stopping it here.
     *
     * True only when the one rate on file is being zeroed on its own start date:
     * the day it starts is the day it stops, so no day was ever charged. A
     * holding that opened at a rate is never this — the days before its first
     * change were charged at the opening figure — and neither is one with a
     * later change still on file, which resumes charging on its own date.
     */
    val cancelsAllInterest: Boolean
        get() = cancelsInterest && !hadOpeningRate && rateHistory.isNotEmpty() &&
            rateHistory.all { it.from == rateChangedOn }

    /**
     * Whether to ask when a rate change took effect.
     *
     * Only on an existing holding whose rate the user has just edited. While
     * creating, the rate is simply what the holding opened at and there is no
     * "before" for it to be different from.
     */
    val asksWhenRateChanged: Boolean get() = isEditing && rateChangedOn != null

    /**
     * Whether the opening balance belongs beside the name rather than below it.
     *
     * Once the account exists, both are settled text: two short facts being
     * read, not two boxes being filled. Side by side they take one line and read
     * as the pair they are — "Nabil, रू 5,00,000" — where stacked they were two
     * captions with a gap between them and pushed everything that actually
     * describes the holding further down the page.
     *
     * While creating they stay stacked, because a field being typed into wants
     * the room.
     *
     * Never on a policy or a goal: nothing was ever "put into" either, and the
     * zero it opened at is bookkeeping rather than a fact about the
     * arrangement. What pairs with the name is the figure it is aimed at, which
     * is still a live field.
     */
    val pairsOpeningWithName: Boolean get() = isEditing && !isLoan && !hasPlan

    /**
     * A wallet and a cash tin, which are read where they sit.
     *
     * Kept apart from [pairsOpeningWithName], which it used to share: pairing
     * the name with the balance is a layout question about every account, and
     * this is a question about which two holdings have no total to be compared
     * against. Tying them together meant widening the first silently took the
     * display-currency choice away from every foreign bank account.
     */
    val isReadWhereItSits: Boolean
        get() = choice.group == HoldingGroup.WALLET || choice.group == HoldingGroup.CASH

    /**
     * Whether to offer reading this holding in the display currency.
     *
     * Never on a wallet or cash: the question is about a holding whose figures
     * the user compares against a total, and these two are read where they sit.
     * It also arrived without context now that the currency chips are gone from
     * an existing holding — a checkbox naming a currency the screen no longer
     * shows.
     */
    val offersDisplayCurrency: Boolean
        get() = canConvertForDisplay && !isReadWhereItSits

    /** Whether the bank pays anything on this — savings, current and deposits do. */
    val earnsInterest: Boolean
        get() = !isLoan && choice.group == HoldingGroup.BANK && (
            choice.bank == BankHolding.SAVINGS ||
                choice.bank == BankHolding.CURRENT ||
                choice.bank == BankHolding.FIXED_DEPOSIT
            )

    /**
     * Whether to ask how often the interest is credited.
     *
     * Only where the bank pays period by period, which is a savings or current
     * account. A deposit has a rate and no periods at all — simple interest over
     * its whole term, arriving in one piece — so an interval on one would be a
     * question about a schedule it does not have.
     */
    val offersPayoutInterval: Boolean get() = earnsInterest && !isFixedDeposit

    /**
     * The interval in months, whichever unit it was typed in. Null while the box
     * is empty or half-typed, which is not an answer.
     */
    val payoutMonths: Int?
        get() = payoutText.toIntOrNull()
            ?.let { if (payoutInYears) it * 12 else it }
            ?.takeIf { it > 0 }
            ?.let { SavingsInterest.gapOf(it) }

    /** True when this is money put away for a term rather than money to spend. */
    val isFixedDeposit: Boolean get() = choice.isFixedDeposit

    /**
     * Whether the top of the form has anything to say about *what* this is.
     *
     * On an existing holding it states the kind; on a new one it offers the
     * sub-choice, which only a bank and a person have. A new wallet, cash tin,
     * policy or goal is one thing and nothing else — the kind was chosen before
     * the screen opened, and a heading over no chips is a question with no
     * answers under it.
     */
    val showsKindQuestion: Boolean
        get() = choice.group == HoldingGroup.BANK ||
            choice.group == HoldingGroup.PERSON

    /** True when this is a policy paid for in premiums until it pays out. */
    val isInsurance: Boolean get() = choice.isInsurance

    /** True when this is money being put aside towards a figure to reach. */
    val isGoal: Boolean get() = choice.isGoal

    /** Whether this holding is fed by a rule rather than by the user's hand. */
    val hasPlan: Boolean get() = isInsurance || isGoal

    /**
     * Whether the name is a fact being read rather than a box to fill in.
     *
     * A policy's stays a box, unlike every other holding's. It is a label the
     * user chose — "House policy", "Sita's education" — and not a fact about the
     * money, so changing it rewrites nothing; the same reason a bank holding's
     * own name is editable for good. A bank's own name is settled because it
     * identifies the relationship every other holding there is grouped under,
     * and a policy is grouped under nothing.
     */
    val nameSettled: Boolean get() = isEditing && !hasPlan

    /**
     * Whether the name is drawn at all, as its own box or as a settled pair.
     *
     * A policy and a goal say theirs inside the "What is it?" box — "Goal ·
     * Bike", "Insurance · Nepal Life" — so a field underneath is the same words
     * a second time. Neither can be renamed from here any more, which is the
     * cost: the arrangement's name is what every projection, every premium and
     * every row already calls it.
     */
    val hidesNameField: Boolean get() = isEditing && hasPlan

    /** Everything a goal's plan needs, once it has all been answered. */
    val goalTerms: Goal.Terms?
        get() {
            if (!isGoal) return null
            val started = depositStartedOn ?: return null
            val months = termInMonths ?: return null
            return Goal.Terms(
                target = Money.ZERO,
                startedOn = started,
                termMonths = months,
                everyMonths = monthsPerPayment,
                inBikramSambat = planRecurInBs,
            )
        }

    /** Everything a policy's schedule needs, once it has all been answered. */
    val policyTerms: Insurance.Terms?
        get() {
            if (!isInsurance) return null
            val started = depositStartedOn ?: return null
            val months = termInMonths ?: return null
            return Insurance.Terms(
                premium = Money.ZERO,
                maturityAmount = Money.ZERO,
                startedOn = started,
                termMonths = months,
                everyMonths = monthsPerPayment,
                inBikramSambat = planRecurInBs,
            )
        }

    /**
     * The earliest day a policy could honestly mature: one whole payment
     * period after the day it starts.
     *
     * Two jobs, and they are one fact. It is the floor under the maturity
     * date's picker, because a policy paying out before its first period is
     * over is a length no premium could be counted over. And it is what that
     * box *shows* while "How long?" is still blank: the length, the rhythm and
     * the maturity date are one arrangement said three ways, and a blank date
     * beside a rhythm reading "1 months" leaves the pair saying nothing. It
     * moves with the rhythm for exactly that reason — answer "every 3 months"
     * and the earliest a policy could mature is three months out.
     *
     * Counted through [PeriodicPlan] rather than by adding months here, so the
     * suggestion falls on the day the rule that pays the premiums would.
     */
    val policyEarliestMaturity: LocalDate?
        get() = depositStartedOn?.let {
            PeriodicPlan(
                startedOn = it,
                termMonths = monthsPerPayment,
                everyMonths = monthsPerPayment,
                inBikramSambat = planRecurInBs,
            ).endsOn
        }

    /** The agreed length in months, whichever unit the user typed it in. */
    val termInMonths: Int?
        get() = termText.trim().toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { if (termInYears) it * 12 else it }

    /**
     * The day a card or an overdraft runs out: approved plus its agreed length.
     *
     * Worked out from the two boxes rather than stored, so the form cannot state
     * an expiry the save would not produce — the same reason a deposit's
     * maturity is never a column of its own. Null until both halves have been
     * answered, which is every facility entered before the day was asked for.
     */
    val facilityExpiresOn: LocalDate?
        get() = openedOn
            ?.takeIf { isOverdraft }
            ?.let { opened -> termInMonths?.let { opened.plusMonths(it.toLong()) } }

    /**
     * True once that day is behind us — said on the form because the card then
     * quietly stops being offered on the money form, and a chip that vanished
     * with nothing said about it reads as the app having lost the card.
     */
    val facilityHasExpired: Boolean
        get() = facilityExpiresOn?.isBefore(today) == true

    /** Everything the deposit's arithmetic needs, once it has been answered. */
    val depositTerms: FixedDeposit.Terms?
        get() {
            val started = depositStartedOn ?: return null
            val months = termInMonths ?: return null
            return FixedDeposit.Terms(
                principal = Money.ZERO,
                annualRate = 0.0,
                startedOn = started,
                termMonths = months,
            )
        }

    /**
     * The repayment shapes on offer.
     *
     * Equal-principal is not one of them any more: paying a fixed slice off the
     * balance is what "pay off a lump sum" already does, on any loan, whenever
     * the user actually has the money — a whole schedule shape for it was a
     * third answer to a question with two. It stays selectable on a loan already
     * running that way, so an existing schedule is never rewritten behind the
     * user's back.
     */
    val styles: List<InstalmentStyle>
        get() = if (isPrincipalOnly) {
            InstalmentStyle.entries
        } else {
            listOf(InstalmentStyle.LEVEL_EMI, InstalmentStyle.INTEREST_ONLY)
        }

    /**
     * Months between instalments, as the arithmetic wants it.
     *
     * A debt settled at the end is one period as long as the loan: the schedule
     * then holds a single payment carrying the principal and all its interest,
     * which is what "pay it all at the end" means. Nothing else in the app has
     * to know about it — one payment is a schedule of one.
     */
    val monthsPerPayment: Int
        get() = when (payEveryUnit) {
            PayEvery.AT_END -> termInMonths ?: 1
            PayEvery.YEARS -> payEveryFigure * 12
            PayEvery.MONTHS -> payEveryFigure
        }

    /** The figure beside the unit, never below one payment. */
    private val payEveryFigure: Int
        get() = payEveryText.trim().toIntOrNull()?.takeIf { it > 0 } ?: 1

    /** True when the whole debt falls on one date rather than on a schedule. */
    val paysAtEnd: Boolean get() = payEveryUnit == PayEvery.AT_END

    /**
     * Whether this debt's schedule steps in Nepali months.
     *
     * The opt-in and the calendar being read, joined the one way that is
     * honest: a bank only counts in Nepali months if the borrower said so *and*
     * that is the calendar in front of them. See [CalendarSystem.forInterest],
     * which the repository asks the same question of.
     */
    val loanStepsInBs: Boolean get() = usesSelectedCalendar && calendarIsNepali

    /**
     * Whether the interest-calendar question is worth asking of this holding.
     *
     * Three things narrow it, and the last is the important one:
     *
     * - **Only where the app counts periods or instalments.** A savings or
     *   current account, whose quarters close on the 1st of a month, and a debt
     *   with a schedule. A deposit's interest is simple across its whole term,
     *   and a policy's premiums and a goal's contributions are the user's own
     *   rhythm — those simply follow the calendar being read, with nothing to
     *   ask and nothing to get wrong.
     * - **Only where somebody might think in Nepali months.** The calendar being
     *   read is the strong signal; the language is the other, since a reader in
     *   Nepali on Gregorian dates is still somebody whose bank may well close its
     *   quarters at the end of Ashoj. To an English reader on English dates there
     *   is nothing to opt into — both answers count the same months — so the
     *   question is not put and the app counts in Gregorian.
     * - **Only while the holding is being created.** Answering it later moves
     *   payout days and due dates that have already been counted from — every
     *   closed period restated, every schedule re-cut — for a question the user
     *   answered once. So it is asked on the way in and settled afterwards; the
     *   line below the fields still says which calendar is in force, and a
     *   holding set up wrong is deleted and entered again, which is one
     *   arrangement rewritten rather than every figure derived from it.
     */
    val offersInterestCalendar: Boolean
        get() = !isEditing && (calendarIsNepali || languageIsNepali) && (
            offersPayoutInterval ||
                isFixedDeposit ||
                (isLoan && !isOverdraft && !paysInOneGo)
            )

    /**
     * What this holding's months are actually counted in, as the word to print.
     *
     * The opt-in and the setting joined — the switch says which way it is set,
     * and this says what that comes to, which is the half a reader can act on.
     */
    /**
     * Whether this holding follows whichever calendar is set — the answer as it
     * stands, with the default filled in.
     *
     * Null in [interestInBs] means the user has not touched the switch, and what
     * it then means depends on what is being set up.
     *
     * A **debt** is the one that says no. A bank debits an instalment on the day
     * the loan agreement names, which is an English date whatever patro the
     * borrower reads, and a schedule that moved with the display setting would
     * be the app guessing at somebody's standing order.
     *
     * Everything else says yes. A deposit, a policy and a goal are the *user's*
     * own arrangements, counted in the months they think in; a savings or
     * current account is a Nepali bank's quarter, and those close on the Nepali
     * quarter ends — a passbook credited at the end of Ashoj, Poush, Chaitra and
     * Ashad is what the reader is checking the app against. Both are still only
     * a default: the switch is there, and a bank that really does close its
     * quarters in English months is one tap away.
     *
     * Said as the holdings that are actually asked rather than as "not a loan",
     * so a cash tin or a wallet — which is offered no switch at all — goes on
     * storing the answer it always stored. The bank account's yes is conditional
     * on [offersInterestCalendar] putting the switch on screen, and reads the
     * same test rather than a copy of half of it: opting one in behind a reader's
     * back would leave a quarter that moves the day they first switch their
     * display, which is the one thing this whole opt-in exists to prevent. Shown
     * and defaulted-on is a different thing from silently on — it is one tap from
     * the other answer, and the line underneath says what it comes to today.
     */
    val usesSelectedCalendar: Boolean
        get() = interestInBs ?: (
            isFixedDeposit || isInsurance || isGoal ||
                (offersPayoutInterval && (calendarIsNepali || languageIsNepali))
            )

    val effectiveCalendarNameRes: Int
        get() = if (usesSelectedCalendar && calendarIsNepali) {
            R.string.calendar_name_nepali
        } else {
            R.string.calendar_name_english
        }

    /** The first *full* instalment, which a broken period pushes one period out. */
    val firstInstalmentOn: LocalDate?
        get() = BrokenPeriod.firstInstalment(
            disbursedOn, emiStartsOn, monthsPerPayment, termInMonths, loanStepsInBs,
        )

    /**
     * Whether this loan has any terms at all — a rate, a length, an instalment
     * or a date to clear it by.
     *
     * Most money between people has none of them. It is a note of who owes what,
     * and reopening it should not present four empty boxes implying the user
     * forgot to fill them in.
     */
    val hasTerms: Boolean
        get() = rateText.isNotBlank() || termText.isNotBlank() ||
            emiText.isNotBlank() || dueOn != null

    /**
     * Whether to draw the terms at all.
     *
     * Always while creating — that is when the user is deciding. On an existing
     * loan only if there are some, or if they asked for them: a bare IOU stays
     * bare, and one that was never meant to have terms can still be given them.
     */
    val showsTerms: Boolean get() = !isEditing || hasTerms || termsRevealed

    /** True once there is a term, which is what makes instalments possible. */
    val hasSchedule: Boolean
        get() = hasInstalments && (derivedTermMonths != null || termText.isNotBlank())

    /** True when this is money the user lent out rather than borrowed. */
    val isLent: Boolean get() = isLoan && choice.isLent

    /**
     * A limit to draw against, not a sum already borrowed.
     *
     * Everything about instalments is hidden for one: an overdraft has no
     * schedule, no repayment style and nothing due on any date until money is
     * actually taken from it. What it has is a ceiling, a rate, and a box to pay
     * back what has been drawn.
     */
    val isOverdraft: Boolean get() = isLoan && choice.bank == BankHolding.OVERDRAFT &&
        choice.group == HoldingGroup.BANK

    /**
     * Money between people, which is handed back in one go rather than in
     * instalments.
     *
     * Nobody sets up an EMI with their sister. The arrangement is "here is
     * 8,000, give it back by Dashain, with something on top if we said so" — one
     * amount, one date — so the whole apparatus of a schedule is absent: no
     * frequency, no instalment figure, no first-payment date, no table of
     * payments to come. The interest is worked out exactly as a fixed deposit's
     * is, because it is the same arrangement seen from the other side: a rate
     * agreed for a length, settled at the end.
     *
     * What stays is everything about what is owed — the balance, every payment
     * recorded against it, a lump sum off it, and more lent or borrowed on the
     * same arrangement. Those are how money between people actually moves.
     */
    val paysInOneGo: Boolean get() = isLoan && choice.group == HoldingGroup.PERSON

    /** Whether this debt is repaid on a schedule at all. */
    val hasInstalments: Boolean get() = isLoan && !isOverdraft && !paysInOneGo

    /**
     * Whether to ask which account the money itself moved through.
     *
     * Asked wherever there is a day for it to have moved on, which is money
     * between people and any loan with a schedule — the two shapes that record
     * the day the money changed hands. It is never a claim that a loan was paid
     * into an account: the answer defaults to "not recorded" and is left there
     * for the plenty of loans disbursed straight to a seller, or entered years
     * late against a balance the user has already corrected.
     *
     * Not on an overdraft, which has no disbursement at all — money arrives
     * from one by being drawn, and that is offered on the money-in form.
     *
     * Only while creating, because that movement has already happened — the debt
     * it made is on file and the row it wrote is in the ledger, and re-pointing a
     * field would move neither. From then on the question is asked where it is
     * live: inside each card that is about to move money.
     */
    val showsDisbursedAccount: Boolean
        get() = !isEditing && (paysInOneGo || hasSchedule)

    /**
     * Whether the account repayments run through is asked as a section of its
     * own.
     *
     * Only while creating. On a debt already on file every movement the form can
     * still make carries its own account — a payment has a "from", more borrowed
     * an "into" — and both are questions about that movement rather than about
     * the debt. A settled line repeating the answer above them was one more
     * thing to read and nothing to do.
     */
    val showsPayFromSection: Boolean get() = !isEditing

    /**
     * Whether that account may be left unanswered.
     *
     * Between people it may: plenty of it is cash, and naming an account the
     * money never touches is worse than naming none. A bank debits an account on
     * a date, so its schedule needs one.
     */
    val payFromOptional: Boolean get() = paysInOneGo

    /**
     * Whether more can be added to what is owed.
     *
     * Money between people grows as often as it shrinks — another 2,000 lent to
     * someone who already owes 8,000 — and recording it as a second loan under
     * the same name leaves the user adding two rows up themselves.
     *
     * Never on anything with a schedule. Raising the principal of a term loan
     * while leaving its instalment and length alone would describe a schedule
     * that no longer clears the debt; a bank top-up re-bases the whole loan,
     * which is a different operation. An overdraft grows by being drawn on, and
     * that is offered on the money-in form.
     */
    val canAddMore: Boolean
        get() = isEditing && isLoan && !isOverdraft && !hasSchedule

    /** Whether to offer the bank shortcut chips: only above a bank's own name. */
    val showsBankShortcuts: Boolean
        get() = !isEditing && nameSuggestions.isNotEmpty()

    /**
     * The names this form offers under the box, or empty for the kinds that
     * have none.
     *
     * A bank's holdings and a provider's wallets are the two things a user has
     * several of under one name, and both want the name typed once so the app
     * can see they belong together. Everything else — a cash tin, a person, a
     * policy, a goal — is one of itself and named something nothing else can
     * guess.
     */
    val nameSuggestions: List<String>
        get() = when (choice.group) {
            HoldingGroup.BANK -> knownBanks
            HoldingGroup.WALLET -> knownWallets
            else -> emptyList()
        }

    /**
     * Whether this holding may carry a name of its own.
     *
     * Only a bank's. Cash, a wallet and money with a person are already named
     * for the only thing that identifies them — a person's debt is "Sita" — and
     * a second name box there would ask the same question twice. At a bank the
     * name field is the *bank*, and everything under it is one of several.
     */
    val offersHoldingName: Boolean get() = choice.group == HoldingGroup.BANK

    /**
     * The name typed for this holding, or null where it says nothing the bank's
     * name does not. Trimmed once, here, so the save and the tabs agree.
     */
    val holdingOwnName: String?
        get() = holdingName.trim()
            .takeIf { offersHoldingName && it.isNotEmpty() && !it.equals(name.trim(), true) }

    /**
     * Whether this form is one of a bank's holdings, drawn as a row of them.
     *
     * It replaces two things at once when it is: the settled "Bank · Savings",
     * which said what this is without saying it was one of four, and the name
     * field, which repeated the heading the tabs now sit under. What is left to
     * state beside the figure is the length, which is why they pair.
     *
     * **More than one, or none of it.** A bank with a single holding drew a row
     * of one tab, already selected, directly under its own name — and a lone
     * chip reading "Savings" at the top of a form is not navigation, it is the
     * shape the *create* form uses to ask what a holding is. So it read as a
     * question, on the one screen where that question is settled for good, and
     * tapping it did nothing. Withheld, the settled "Bank · Savings" comes back
     * and says the same thing without offering to change it. The same rule
     * `bankTabs` already applied to cash, a wallet and money with a person.
     */
    val showsBankTabs: Boolean get() = isEditing && bankTabs.size > 1

    /**
     * Whether to offer the conversion choice at all. An account already held in
     * the display currency has nothing to convert.
     */
    val canConvertForDisplay: Boolean
        get() = !currencyCode.equals(baseCurrencyCode, ignoreCase = true)
}

/**
 * A sentence for the snackbar, as a resource and its arguments.
 *
 * Not a finished string: the view model has no Context, and it should not — the
 * words belong to whichever language the screen is being read in at the moment
 * it is drawn.
 */
data class HoldingMessage(@StringRes val text: Int, val args: List<String> = emptyList())

@HiltViewModel
class HoldingEditorViewModel @Inject constructor(
    private val loans: LoanRepository,
    private val wallet: WalletRepository,
    private val interest: InterestRepository,
    private val plans: PlanRepository,
    private val recurrence: RecurrenceRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val loanId: String? =
        savedStateHandle.get<String>(Routes.ARG_LOAN_ID)?.takeIf { it.isNotBlank() }
    private val accountId: String? =
        savedStateHandle.get<String>(Routes.ARG_ACCOUNT_ID)?.takeIf { it.isNotBlank() }

    /**
     * What is being added, answered before this screen opened.
     *
     * Null only on a holding that already exists, which knows its own kind. The
     * form never asks again: the six kinds open six different forms, and the
     * question belongs in front of one rather than as its first row.
     */
    private val newGroup: HoldingGroup? = savedStateHandle
        .get<String>(Routes.ARG_GROUP)
        ?.takeIf { it.isNotBlank() }
        ?.let { name -> HoldingGroup.entries.firstOrNull { it.name == name } }

    private val _state = MutableStateFlow(HoldingEditorState())
    val state: StateFlow<HoldingEditorState> = _state.asStateFlow()

    private var formatter = MoneyFormatter(CurrencyOption.NPR, grouping = settings.grouping)

    /** The preview in flight, so a slower earlier one cannot land on top of it. */
    private var previewJob: Job? = null

    /** True once the user has picked a colour, so the palette stops guessing. */
    private var colorPicked = false

    /**
     * What is owed, as a figure rather than the formatted string the form shows.
     * Kept so "you would owe this afterwards" can be worked out without asking
     * the repository again on every keystroke.
     */
    private var owed: Money? = null

    /**
     * The most a payment can usefully be: what it would take to clear the debt
     * today, interest and all.
     *
     * A payment larger than the debt is not a payment, it is a typo — and a
     * silent one, because `applyPrepayment` clamps what it writes while the box
     * goes on showing the figure that was typed. So the box is clamped instead,
     * as the digits are entered: somebody who means to settle a card taps a long
     * run of nines and gets exactly what settling it costs.
     */
    private var maxPayable: Money? = null

    /**
     * True once the first instalment's date is the user's own answer rather than
     * the app's default, so changing how often the loan is paid stops moving it.
     */
    private var emiStartPicked = false

    /**
     * The rate as it stood when the form opened — the *current* one, which on a
     * holding the bank has repriced is the newest change and not the figure in
     * its own column. Typing this back is a correction, not a second change.
     */
    private var openingRateText: String = ""

    /**
     * The rate the holding opened at, exactly as stored.
     *
     * Passed back through every save untouched. Nothing but creation may write
     * it: it is the rate every quarter and every instalment before the first
     * change was settled at, and overwriting it would recompute all of them at
     * whatever the bank charges today.
     */
    private var storedBaseRate: Double? = null

    /** Every rate move already on file, so the preview can honour them. */
    private var storedChanges: List<RateChange> = emptyList()

    /**
     * The payout interval as stored, so an emptied box cannot take it away.
     *
     * A blank field is somebody half way through retyping a number, not a
     * request to be paid interest on no rhythm at all — and null in the column
     * would silently move the account onto the default.
     */
    private var storedPayoutMonths: Int? = null

    /**
     * The interest-calendar opt-in this account arrived with.
     *
     * Held so saving can tell whether the answer actually moved. Ticking or
     * unticking it re-cuts every period the account has ever been paid on, which
     * is a walk over its whole history — worth doing exactly once, and only when
     * it is owed.
     */
    private var storedInterestInBs: Boolean = false

    /**
     * The day this balance started running, and the days of interest it is
     * carrying — the two facts that decide what the *next* instalment charges.
     *
     * Both are the loan's own, and the preview has to use them or it quotes a
     * different schedule from the one the app itself is running. It used to
     * measure from the disbursement and carry nothing, which after a lump sum
     * charged the next instalment a whole fresh period on the reduced balance
     * and forgave the days already run: the table said रू 19,94,974.50 where the
     * timeline said रू 19,96,043.61 for the very same payment.
     *
     * Null and zero on a loan being created, where there is nothing yet to
     * preserve and the disbursement is the only day there is.
     */
    private var storedStartedOn: LocalDate? = null
    private var storedCarriedInterest: Money = Money.ZERO

    /**
     * What a goal actually holds, as a figure rather than the formatted string.
     *
     * Read from the balance when the holding loads, so the progress bar answers
     * "how much has been put aside" from the rows that put it there rather than
     * from the plan that intended to.
     */
    private var goalSaved: Money = Money.ZERO

    init {
        // Today, from the injected clock rather than the data class's own
        // default, so a test that freezes time gets the day it asked for.
        _state.value = _state.value.copy(
            // Set before anything is read, so the create form is never drawn
            // for the frame or two it takes an existing holding to load.
            isLoading = accountId != null || loanId != null,
            // Known from the route alone, and said straight away: the title bar
            // is drawn while the body waits, and "Add account" over a holding
            // the user has just opened is worse than a blank form. Which *kind*
            // of loan is still unknown here — only that it is one, which is all
            // the two titles need.
            isEditing = accountId != null || loanId != null,
            choice = when {
                loanId != null -> HoldingChoice(group = HoldingGroup.BANK, bank = BankHolding.LOAN)
                newGroup != null -> _state.value.choice.copy(group = newGroup)
                else -> _state.value.choice
            },
            prepayDate = clock.today(),
            moreDate = clock.today(),
            // The day money between people changed hands is answered with today
            // rather than left blank — the same default picking the group used
            // to apply, now that the group is picked before this screen opens.
            disbursedOn = if (newGroup == HoldingGroup.PERSON) clock.today() else null,
            // A deposit is nearly always entered on the day it is made, and an
            // empty box that refuses to save is a worse answer than the obvious
            // one — the same reason the day a loan's money arrived starts at
            // today. Overwritten by the stored date when an existing one loads.
            depositStartedOn = clock.today(),
            // A bank's arrangements are agreed in years and nobody says "sixty
            // months" out loud: a deposit runs for a year or five, and a loan
            // for two or seven. Months was the default because it is the unit
            // the app *stores*, which is a fact about the database rather than
            // about the person filling the box in — and it cost a tap on nearly
            // every one of them. Overwritten the moment a stored holding loads,
            // where the length on file decides which unit reads it back.
            // A policy is agreed in years too — nobody buys "two hundred and
            // forty months" of life cover — so it opens on the same unit a
            // bank's arrangements do.
            termInYears = newGroup == HoldingGroup.BANK ||
                newGroup == HoldingGroup.INSURANCE,
            today = clock.today(),
        )
        // One coroutine, in order: the display currency is only a starting
        // point, and a loaded row must be able to override it. Two racing
        // launches would sometimes stamp the default over the real value.
        viewModelScope.launch {
            val stored = settings.settings.first()
            val base = stored.currencyCode
            _state.value = _state.value.copy(
                baseCurrencyCode = base,
                planRecurInBs = stored.calendarSystem == CalendarSystem.BIKRAM_SAMBAT,
                calendarIsNepali = stored.calendarSystem == CalendarSystem.BIKRAM_SAMBAT,
                languageIsNepali = stored.readsNepali,
            )
            setCurrency(base)
            // After the display currency is chosen and before anything can be
            // tapped: the shortlist has to contain whatever is selected, and
            // this is the moment that is known.
            //
            // Worked out into a local *before* the state is touched, because
            // `_state.value` is read the moment the copy is written and the
            // query in the middle of one takes long enough for the accounts
            // flow below to land in the gap: the copy then put its own stale
            // snapshot back, and the banks, the tabs and the paying account
            // were emptied for the life of the form — the flow does not emit
            // again until something in the database moves. Anything that
            // suspends inside a state copy is this bug.
            val choices = CurrencyOption.shortlist(
                used = wallet.currenciesInUse(),
                selected = base,
            )
            _state.value = _state.value.copy(currencyChoices = choices)
            loanId?.let { loadLoan(it) }
            accountId?.let { loadAccount(it) }
            // Whatever happened — loaded, or the row has since been deleted —
            // the form is now as complete as it is going to get.
            _state.value = _state.value.copy(isLoading = false)
        }
        viewModelScope.launch {
            // Names come from both tables: a bank the user only has a loan with
            // is still a bank they should not have to type twice.
            combine(wallet.observeAccounts(), loans.observeLoans()) { accounts, loanRows ->
                accounts to loanRows
            }.collect { (accounts, loanRows) ->
                val current = _state.value
                // The *bank*, never the holding's own name: since a deposit may
                // be called "Emergency FD", taking both fields would offer that
                // as somewhere to open a second account.
                val banks = (
                    accounts
                        .filter {
                            it.kind != AccountKind.WALLET && it.kind != AccountKind.CASH &&
                                it.kind != AccountKind.INSURANCE && it.kind != AccountKind.GOAL
                        }
                        .map { it.institution ?: it.name } +
                        loanRows.filter { it.kind == LoanKind.BANK || it.kind == LoanKind.OVERDRAFT }
                            .map { it.lender ?: it.name }
                    )
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                // The wallets already open, for the same reason the banks are
                // offered: one provider holds a wallet per currency — a Wise
                // account in dollars and another in euros — and the two are the
                // same wallet to their owner. Typed twice they are two spellings
                // and the app can no longer see they belong together.
                //
                // Kept apart from the banks rather than merged into one list:
                // they are different relationships, and offering "Nabil Bank" as
                // a wallet name would invite a wallet that reads as one of that
                // bank's holdings and groups with none of them.
                val wallets = accounts
                    .filter { it.kind == AccountKind.WALLET }
                    .map { (it.institution ?: it.name).trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
                _state.value = current.copy(
                    accounts = accounts,
                    knownBanks = banks,
                    knownWallets = wallets,
                    bankTabs = bankTabs(accounts, loanRows),
                    // Preselected for a debt that has to name one, and left
                    // exactly as the user left it on the debts that do not —
                    // otherwise clearing it would be undone by the next time
                    // this flow emitted.
                    // The first place money can actually move through — one
                    // per bank, cash and wallets — rather than the first row in
                    // the table, which may be a fixed deposit or a second
                    // savings account nothing is ever paid from.
                    payFromAccountId = current.payFromAccountId
                        ?: accounts.payableHoldings().firstOrNull()?.id
                            .takeIf { !current.payFromOptional },
                    // Each goal movement starts at the account the plan itself
                    // runs through, and neither is written back to the goal: a
                    // top-up handed over in cash must not restate where the
                    // contributions come from.
                    goalDepositAccountId = current.goalDepositAccountId
                        ?: current.payFromAccountId
                        ?: accounts.payableHoldings().firstOrNull()?.id,
                    goalWithdrawAccountId = current.goalWithdrawAccountId
                        ?: current.payFromAccountId
                        ?: accounts.payableHoldings().firstOrNull()?.id,
                    // A new account gets the next colour along, so two created
                    // in a row do not come out identical.
                    color = if (colorPicked || current.isEditing) {
                        current.color
                    } else {
                        HoldingPalette.at(accounts.size)
                    },
                )
            }
        }
    }

    /**
     * The other holdings at this holding's bank, this one included.
     *
     * Read from the stored rows rather than from the form, deliberately: the
     * form's name is editable on some holdings and empty for a moment on
     * others, and a row of tabs that came and went as the user typed would be
     * unusable. Which bank this *is* was decided when it was saved.
     *
     * Empty for everything that is not a bank's: cash, a wallet and money with
     * a person have no siblings, and a lone tab beside a heading that already
     * names the holding is a chip with nowhere to go.
     */
    /**
     * One provider's wallets, told apart by what each holds.
     *
     * The kind is the same word on every one of them — "Wallet" three times
     * distinguishes nothing — so the currency is what the tab says. That is the
     * same rule the bank tabs already reach for when two of a bank's holdings
     * would wear one word: it is simply the *first* thing to reach for here
     * rather than the last, because there is no second word to try.
     *
     * A wallet the user named keeps its name, for the reason a named deposit
     * does: they have already said what to call it.
     */
    private fun walletTabs(accounts: List<Account>): List<HoldingTab> {
        fun key(raw: String?) = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wallets = accounts.filter { it.kind == AccountKind.WALLET }
        val provider = accountId?.let { id ->
            wallets.firstOrNull { it.id == id }?.let { key(it.institution ?: it.name) }
        } ?: return emptyList()
        return wallets
            .filter { key(it.institution ?: it.name) == provider }
            .sortedBy { it.currencyCode }
            .map {
                HoldingTab(
                    accountId = it.id,
                    labelRes = it.kind.labelRes(),
                    literal = it.ownName ?: it.currencyCode,
                    isCurrent = it.id == accountId,
                )
            }
    }

    /**
     * Whether this provider already holds a wallet in this currency.
     *
     * Asked of the accounts the form has already loaded rather than of the
     * database, for the reason every other check on this screen is: the list is
     * a live flow the form is already collecting, and a second read could only
     * disagree with what the user is looking at. The holding being edited is
     * excluded, or saving one without touching it would refuse itself.
     */
    private fun HoldingEditorState.walletClashes(): Boolean {
        val provider = name.trim().lowercase().takeIf { it.isNotEmpty() } ?: return false
        return accounts.any {
            it.kind == AccountKind.WALLET &&
                it.id != accountId &&
                (it.institution ?: it.name).trim().lowercase() == provider &&
                it.currencyCode.equals(currencyCode, ignoreCase = true)
        }
    }

    private fun bankTabs(
        accounts: List<Account>,
        loanRows: List<LoanEntity>,
    ): List<HoldingTab> {
        fun key(raw: String?) = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        // A policy and a goal are at no bank, whatever their names happen to
        // be: tabs are the holdings of one relationship, and a goal called
        // "Nabil" is not one of Nabil Bank's.
        // A provider's wallets are their own family and never mix with a bank's
        // holdings. One provider holds a wallet per currency — Wise in dollars
        // and Wise in euros — which is the same shape as a bank holding a
        // savings account and a loan, so it takes the same row of tabs; but a
        // wallet named after a bank is not one of that bank's, exactly as a goal
        // called "Nabil" is not.
        val editingWallet = accountId?.let { id ->
            accounts.firstOrNull { it.id == id }?.kind == AccountKind.WALLET
        } ?: false
        if (editingWallet) return walletTabs(accounts)

        val bankAccounts = accounts.filter {
            it.kind != AccountKind.WALLET && it.kind != AccountKind.CASH &&
                it.kind != AccountKind.INSURANCE && it.kind != AccountKind.GOAL
        }
        val bankLoans = loanRows.filter { it.kind != LoanKind.PERSONAL && !it.isClosed }
        // The same rule the accounts list groups by: the bank a holding was
        // filed under, or its own name where that *is* the bank.
        val bank = accountId?.let { id ->
            bankAccounts.firstOrNull { it.id == id }?.let { key(it.institution ?: it.name) }
        } ?: loanId?.let { id ->
            bankLoans.firstOrNull { it.id == id }?.let { key(it.lender ?: it.name) }
        } ?: return emptyList()

        val tabs = bankAccounts
            .filter { key(it.institution ?: it.name) == bank }
            .sortedBy { it.kind.ordinal }
            .map {
                HoldingTab(
                    accountId = it.id,
                    ownName = it.ownName,
                    labelRes = it.kind.labelRes(),
                    currencyCode = it.currencyCode,
                    isCurrent = it.id == accountId,
                )
            } +
            bankLoans
                .filter { key(it.lender ?: it.name) == bank }
                .sortedBy { it.kind.ordinal }
                .map {
                    HoldingTab(
                        loanId = it.id,
                        // The entity's own two columns, read the same way the
                        // domain reads them: a name that is not the bank's is
                        // this debt's own.
                        ownName = it.name.takeIf { own ->
                            it.lender?.equals(own, ignoreCase = true) == false
                        },
                        labelRes = it.kind.labelRes(),
                        currencyCode = it.currencyCode,
                        isCurrent = it.id == loanId,
                    )
                }
        return tabs.disambiguated()
    }

    private suspend fun loadLoan(id: String) {
        val loan = loans.findLoan(id) ?: return
        val money = MoneyFormatter(CurrencyOption.byCode(loan.currencyCode), grouping = settings.grouping)
        val months = loan.termMonths
        val wholeYears = months != null && months % 12 == 0 && months >= 12
        val gap = loan.paymentEveryMonths.coerceAtLeast(1)
        // A gap that covers the whole loan is one payment, which is the same
        // arrangement "at the end" writes — so it reads back as that rather than
        // as "every twelve months", which describes a schedule of one.
        val gapAtEnd = months != null && months > 0 && gap >= months
        val gapInYears = !gapAtEnd && gap % 12 == 0 && gap >= 12
        val choice = HoldingChoice.of(loan.kind, loan.direction)
        formatter = money
        owed = loan.outstanding
        // A saved loan's dates are settled facts, not defaults to be re-derived.
        emiStartPicked = true
        storedBaseRate = loan.annualRate
        storedChanges = interest.changesFor(loanId = id)
        storedStartedOn = loan.startedOn
        storedCarriedInterest = loan.carriedInterest
        // The rate the bank charges *now*, which after a repricing is the newest
        // change rather than the figure the loan was taken at.
        openingRateText = (storedChanges.maxByOrNull { it.effectiveFrom }?.annualRate
            ?: loan.annualRate)?.toString().orEmpty()
        _state.value = _state.value.copy(
            isEditing = true,
            choice = choice,
            groups = HoldingChoice.groupsFor(choice),
            bankHoldings = HoldingChoice.bankHoldingsFor(choice),
            personHoldings = HoldingChoice.personHoldingsFor(choice),
            // The bank, which on a debt the user has named is the *lender*
            // column; the name field on this form is always whoever the money
            // is with, and what they called this particular debt sits beside it.
            // Money between people is named for the person, whatever an old row
            // happens to carry as its lender.
            name = if (loan.kind == LoanKind.PERSONAL) loan.name else loan.lender ?: loan.name,
            holdingName = loan.ownName.orEmpty(),
            // The one amount box means the ceiling on an overdraft and the sum
            // borrowed on everything else. What has been drawn is shown below
            // as a balance, not offered back as an input.
            principalText = money.toPlainInput(loan.creditLimit ?: loan.principal),
            // What was agreed, which the box below deliberately does not carry:
            // that one still holds the current figure, because saving must not
            // put the debt back to what it was before it was paid down.
            //
            // The figure it *opened* at, not everything ever advanced on it. The
            // aggregate is what "To pay" and "To receive" already say, so a debt
            // lent at रू 8,000 and topped up by रू 2,000 read रू 10,000 twice on
            // one screen and nowhere said the sum the two of them shook on.
            principalDisplay = money.formatCompact(
                loan.creditLimit ?: loan.openedAt ?: loan.borrowedInAll ?: loan.principal
            ),
            rateText = openingRateText,
            rateHistory = rateHistoryLines(loanId = loan.id),
            // Nothing on file means the date beside a new rate is asking when it
            // starts applying, not when it moved.
            hadRate = loan.annualRate != null || storedChanges.isNotEmpty(),
            hadOpeningRate = loan.annualRate != null,
            // Latched at load, so emptying the rate box to type a new figure
            // cannot take the box away mid-edit. [hasTerms] reads what is
            // currently typed, which is nothing for the moment between clearing
            // a rate and entering the next one — and the whole section vanished
            // in that moment, replaced by an offer to add the interest that was
            // already there.
            termsRevealed = loan.annualRate != null || loan.termMonths != null ||
                loan.emi != null || loan.dueOn != null || storedChanges.isNotEmpty(),
            termText = when {
                months == null -> ""
                wholeYears -> (months / 12).toString()
                else -> months.toString()
            },
            termInYears = wholeYears,
            payEveryText = when {
                gapAtEnd -> ""
                gapInYears -> (gap / 12).toString()
                else -> gap.toString()
            },
            payEveryUnit = when {
                gapAtEnd -> PayEvery.AT_END
                gapInYears -> PayEvery.YEARS
                else -> PayEvery.MONTHS
            },
            emiText = loan.emi?.let { money.toPlainInput(it) }.orEmpty(),
            style = loan.style,
            currencyCode = loan.currencyCode,
            showInDisplayCurrency = loan.showInDisplayCurrency,
            // The stored answer as it stands. Only a debt that must name one
            // falls back to the preselection: on money between people null is a
            // real answer, and re-saving must not invent an account for it.
            payFromAccountId = loan.payFromAccountId
                ?: _state.value.payFromAccountId.takeIf { choice.group != HoldingGroup.PERSON },
            // Left null when it was never answered, so the form says nothing
            // rather than naming an account no money ever passed through.
            disbursedAccountId = loan.disbursedAccountId,
            // Each card's own starting point. A repayment goes where the
            // repayments go; more of the same arrangement moves through the
            // account the arrangement itself moved through.
            prepayAccountId = loan.payFromAccountId,
            moreAccountId = loan.disbursedAccountId ?: loan.payFromAccountId,
            hasMovements = loans.hasMovements(id),
            emiStartsOn = loan.emiStartsOn,
            // The debt's own opt-in. What the schedule is actually stepped in
            // is this and the calendar being read, which [loanStepsInBs] joins.
            interestInBs = loan.recurInBs,
            // Left null on a loan recorded before the app asked, so reopening it
            // does not invent a disbursement date — and therefore a charge.
            disbursedOn = loan.disbursedOn,
            // Null on every card entered before the app asked for it, which
            // leaves it with no expiry — see [Loan.expiresOn]. Guessing one from
            // the day it was written down would retire a card still in use.
            openedOn = loan.openedOn,
            // The day the money moved, which every other date on this form is
            // measured from. `startedOn` stands in where the disbursement was
            // never recorded: it is the day the balance on file started running,
            // which is the earliest day the app knows anything about this debt.
            movedOn = loan.disbursedOn ?: loan.startedOn,
            dueOn = loan.dueOn,
            outstanding = money.formatCompact(loan.outstanding),
            // How much of the debt itself has actually gone. Not derivable from
            // the instalments paid, which are mostly interest early on — this is
            // the number the user is really asking for when they ask how much
            // they have paid off.
            principalCleared = Money(loan.principal.minor - loan.outstanding.minor)
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
            paymentsMade = loan.paymentsMade,
            missedPeriods = loan.arrears.missed,
            paymentsSoFar = loans.paymentsMade(id),
            available = loan.available?.let { money.formatCompact(it) },
            hasDrawnBalance = loan.outstanding.isPositive,
            accruedInterest = loan.accruedInterest
                ?.takeIf { it.isPositive }?.let { money.formatCompact(it) },
            settleToday = loan.accruedInterest
                ?.takeIf { it.isPositive }
                ?.let { money.formatCompact(loan.outstanding + it) },
            principalPaidOutright = loan.principalPaidOutright
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
            interestPaidOutright = loan.interestPaidOutright
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
        )
        maxPayable = loan.settleToday
        loan.color?.let {
            colorPicked = true
            _state.value = _state.value.copy(color = it)
        }
        recompute()
    }

    /**
     * How often this account is credited, as a figure the form can draw. An
     * account that was never asked reads as the default, which is what it has
     * been credited on all along.
     */
    private fun payoutMonthsOf(account: Account): Int =
        SavingsInterest.gapOf(account.interestPayoutMonths ?: SavingsInterest.DEFAULT_EVERY_MONTHS)

    private suspend fun loadAccount(id: String) {
        val account = wallet.findAccount(id) ?: return
        val money = MoneyFormatter(CurrencyOption.byCode(account.currencyCode), grouping = settings.grouping)
        val choice = HoldingChoice.of(account.kind)
        formatter = money
        colorPicked = true
        storedBaseRate = account.annualRate
        storedPayoutMonths = account.interestPayoutMonths
        storedInterestInBs = account.interestInBs
        storedChanges = interest.changesFor(accountId = id)
        val postings = interest.postingsFor(id)
        val movements = wallet.statementFor(id)
        // What a goal has actually had put aside, which is what the bar reads.
        goalSaved = movements.firstOrNull()?.balanceAfter ?: account.openingBalance
        // Where the plan's own payments run through, read off the rule that
        // makes them — one fact, in the one place that acts on it.
        val planAccountId = account.premiumSeriesId
            ?.let { recurrence.findSeries(it) }
            ?.accountId
        openingRateText = (storedChanges.maxByOrNull { it.effectiveFrom }?.annualRate
            ?: account.annualRate)?.toString().orEmpty()
        _state.value = _state.value.copy(
            isEditing = true,
            rateText = openingRateText,
            hadRate = account.annualRate != null || storedChanges.isNotEmpty(),
            hadOpeningRate = account.annualRate != null,
            rateHistory = rateHistoryLines(accountId = id),
            // Shown in whichever unit it divides into cleanly, the way every
            // other length in the app is: "1 year" and not "12 months".
            // Clamped on the way in, because a hand-trimmed backup can carry a
            // figure no period could ever be.
            payoutText = payoutMonthsOf(account)
                .let { if (it % 12 == 0) it / 12 else it }
                .toString(),
            payoutInYears = payoutMonthsOf(account) % 12 == 0,
            // Settled by the credits themselves rather than by a flag the user
            // set: what closes the question is that an answer to it is already
            // on file, and only the postings can say that.
            payoutSettled = postings.isNotEmpty(),
            interestPaid = postings.map { InterestPaidRow(it.on, money.formatCompact(it.amount)) },
            interestTotal = money.formatCompact(Money(postings.sumOf { it.amount.minor }))
                .takeIf { postings.isNotEmpty() },
            accountId = id,
            movementCount = movements.size,
            balanceNow = money.formatCompact(
                movements.firstOrNull()?.balanceAfter ?: account.openingBalance
            ),
            choice = choice,
            groups = HoldingChoice.groupsFor(choice),
            bankHoldings = HoldingChoice.bankHoldingsFor(choice),
            personHoldings = HoldingChoice.personHoldingsFor(choice),
            // The bank where there is one — `institution` — and the account's
            // own name beside it. On everything created before the name field
            // existed, and on cash, the two are the same string and the second
            // is blank.
            name = account.institution ?: account.name,
            holdingName = account.ownName.orEmpty(),
            currencyCode = account.currencyCode,
            institution = account.institution.orEmpty(),
            openingText = money.toPlainInput(account.openingBalance),
            openingDisplay = money.formatCompact(account.openingBalance),
            color = account.color,
            showInDisplayCurrency = account.showInDisplayCurrency,
            // A plan already on file keeps the calendar it was set up in, so the
            // table on its card names the days its rule will actually produce.
            planRecurInBs = account.planRecurInBs,
            // The account's own opt-in, which decides nothing on its own — see
            // [CalendarSystem.forInterest].
            interestInBs = account.interestInBs,
            depositStartedOn = account.depositStartedOn,
            // A goal's saving cannot have been put in before the goal was set
            // up; the same column carries the day for a deposit and a policy.
            movedOn = account.depositStartedOn,
            depositIntoAccountId = account.maturesIntoAccountId,
            // Shown in whichever unit it divides into cleanly, the same way a
            // loan's term is: "2 years" and not "24 months".
            termText = account.depositTermMonths?.let {
                if (it % 12 == 0) (it / 12).toString() else it.toString()
            }.orEmpty(),
            termInYears = account.depositTermMonths?.let { it % 12 == 0 } == true,
            maturityText = account.maturityAmount?.let { money.toPlainInput(it) }.orEmpty(),
            premiumText = account.perPayment?.let { money.toPlainInput(it) }.orEmpty(),
            // How often, read back the way it was given: "every 1 years" rather
            // than "every 12 months", which is what the user actually agreed.
            payEveryText = account.premiumEveryMonths?.let {
                if (it % 12 == 0) (it / 12).toString() else it.toString()
            } ?: _state.value.payEveryText,
            payEveryUnit = when {
                account.premiumEveryMonths == null -> _state.value.payEveryUnit
                account.premiumEveryMonths % 12 == 0 -> PayEvery.YEARS
                else -> PayEvery.MONTHS
            },
            // Where the premiums come from lives on the rule that pays them —
            // one fact, in the one place that acts on it. Read back here so the
            // form can show which account was chosen and hand it back on save.
            payFromAccountId = planAccountId ?: _state.value.payFromAccountId,
            // Both goal movements start there too, and go on being their own
            // answers from then on.
            goalDepositAccountId = planAccountId ?: _state.value.goalDepositAccountId,
            goalWithdrawAccountId = planAccountId ?: _state.value.goalWithdrawAccountId,
        )
        recompute()
    }

    /**
     * Re-reads what the account holds, and nothing else on the form.
     *
     * Called when the editor comes back to the foreground, because the statement
     * is a page of its own now and a movement can be swiped away on it: every
     * balance in that column was worked out from the one above it, so a row
     * leaving restates the figure this card is showing. Without it the reader
     * deletes a payment, presses back, and reads a balance that still counts it.
     *
     * Deliberately **not** a reload of the form. A holding's editor may have
     * half-typed answers in it, and throwing those away as the side effect of
     * returning to the screen would be worse than the stale figure it fixes.
     */
    fun refreshStatement() {
        accountId?.let { id -> viewModelScope.launch { refreshStatement(id) } }
        loanId?.let { id -> viewModelScope.launch { refreshLoan(id) } }
    }

    /**
     * Re-reads what the debt itself says, when this screen comes back to the
     * front.
     *
     * Its statement and its schedule are pages of their own now, and both change
     * the debt: a payment swiped away on one puts money back on the balance, and
     * a carried instalment dated on the other takes it off the payment that was
     * collecting it. Without this the reader does either, presses back, and
     * finds the card still quoting what it quoted before — and the quoted figure
     * read रू 0, because the schedule the form was still holding began with a
     * period nobody had paid.
     *
     * **Only what is settled**, exactly as the account's refresh does: the form
     * may have half-typed answers in it, and reloading the whole thing would
     * throw them away on the way back.
     */
    private suspend fun refreshLoan(id: String) {
        val loan = loans.findLoan(id) ?: return
        val money = MoneyFormatter(CurrencyOption.byCode(loan.currencyCode), grouping = settings.grouping)
        _state.value = _state.value.copy(
            outstanding = money.formatCompact(loan.outstanding),
            principalCleared = Money(loan.principal.minor - loan.outstanding.minor)
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
            paymentsMade = loan.paymentsMade,
            missedPeriods = loan.arrears.missed,
            paymentsSoFar = loans.paymentsMade(id),
            hasMovements = loans.hasMovements(id),
            hasDrawnBalance = loan.outstanding.isPositive,
            accruedInterest = loan.accruedInterest
                ?.takeIf { it.isPositive }?.let { money.formatCompact(it) },
            settleToday = loan.accruedInterest
                ?.takeIf { it.isPositive }
                ?.let { money.formatCompact(loan.outstanding + it) },
            principalPaidOutright = loan.principalPaidOutright
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
            interestPaidOutright = loan.interestPaidOutright
                .takeIf { it.isPositive }?.let { money.formatCompact(it) },
        )
        owed = loan.outstanding
        maxPayable = loan.settleToday
        recompute()
    }

    private suspend fun refreshStatement(id: String) {
        val account = wallet.findAccount(id) ?: return
        val money = MoneyFormatter(CurrencyOption.byCode(account.currencyCode), grouping = settings.grouping)
        val movements = wallet.statementFor(id)
        val balance = movements.firstOrNull()?.balanceAfter ?: account.openingBalance
        // What a goal has actually had put aside, which is what its bar reads.
        goalSaved = balance
        _state.value = _state.value.copy(
            movementCount = movements.size,
            balanceNow = money.formatCompact(balance),
        )
        recompute()
    }

    fun setBankHolding(holding: BankHolding) {
        val current = _state.value
        _state.value = current.copy(
            choice = current.choice.copy(bank = holding),
            // An overdraft has no instalment schedule — you service the interest
            // and the balance sits there until you pay it down. Only a starting
            // point, and only while creating: a saved loan's style is a fact.
            style = if (holding == BankHolding.OVERDRAFT && !current.isEditing) {
                InstalmentStyle.INTEREST_ONLY
            } else {
                current.style
            },
            // A facility's approval date is asked for and has to be answered,
            // so the form answers it: today, which is the day somebody entering
            // a card they have just been given would pick anyway. A required
            // box that opens empty is a refusal waiting to happen on the way
            // out, and this is the one date on the form nothing is recomputed
            // from — see [setOpenedOn] — so a default here cannot restate
            // anything. Only while creating, and only where it has not been
            // answered: reopening a card must not move the day the bank
            // approved it, and neither must tapping back and forth between the
            // chips.
            openedOn = if (holding == BankHolding.OVERDRAFT && !current.isEditing) {
                current.openedOn ?: clock.today()
            } else {
                current.openedOn
            },
            nameError = null,
            amountError = false,
        )
        recompute()
    }

    fun setPersonHolding(holding: PersonHolding) {
        _state.value = _state.value.copy(
            choice = _state.value.choice.copy(person = holding),
            nameError = null,
            amountError = false,
        )
        recompute()
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value, nameError = null, currencyError = null)
    }

    /**
     * What this one holding is called. Never validated: blank is the ordinary
     * answer, and means the row goes on saying what kind it is.
     */
    fun setHoldingName(value: String) {
        _state.value = _state.value.copy(holdingName = value)
    }

    // ------------------------------------------------- money put away for a term

    fun setDepositStartedOn(date: LocalDate) {
        _state.value = _state.value.copy(depositStartedOn = date, depositError = null)
        recompute()
    }

    /**
     * Where the whole of it lands when it comes free.
     *
     * Null is a real answer, offered as its own chip. A deposit is often made
     * before the account it will come back into exists, and plenty of people
     * simply have not decided — the maturity is drawn as a forecast either way,
     * naming the deposit it leaves and saying nothing it does not know about
     * where it goes.
     */
    fun setDepositInto(accountId: String?) {
        _state.value = _state.value.copy(depositIntoAccountId = accountId, depositError = null)
    }

    /**
     * Works the deposit out from whatever has been answered so far.
     *
     * Everything is derived — nothing about a deposit is stored except the four
     * answers — so this runs on every keystroke and the figures on the page are
     * always what the terms currently in the boxes produce.
     */
    private fun recomputeDeposit(current: HoldingEditorState) {
        val principal = formatter.parse(current.openingText)
        // A deposit's rate is fixed for its term — the whole of what the user
        // agreed to — so this reads the rate it opened at and never a later one.
        val rate = if (current.isEditing) {
            storedBaseRate ?: current.rateText.toDoubleOrNull()
        } else {
            current.rateText.toDoubleOrNull()
        }
        val started = current.depositStartedOn
        val months = current.termInMonths
        if (principal == null || principal.minor <= 0L || started == null || months == null) {
            _state.value = current.copy(
                depositMaturityValue = null,
                depositTotalInterest = null,
                depositMaturesOn = null,
            )
            return
        }
        val terms = FixedDeposit.Terms(
            principal = principal,
            annualRate = rate ?: 0.0,
            startedOn = started,
            termMonths = months,
        )
        // Three facts, each said once: what went in, what it earns, and the day
        // it comes free with what it is worth then. There was a fourth — what
        // the deposit had accrued by today — and it was both a repeat of the
        // first and a different arrangement than the one the user agreed to.
        _state.value = current.copy(
            depositTotalInterest = formatter.formatCompact(FixedDeposit.totalInterest(terms)),
            depositMaturityValue = formatter.formatCompact(FixedDeposit.maturityValue(terms)),
            depositMaturesOn = terms.maturesOn,
        )
    }

    // ------------------------------------------------------------- a policy

    /** What the insurer hands over at the end. */
    fun setMaturityAmount(value: String) {
        _state.value = _state.value.copy(
            maturityText = value.amountInput(),
            depositError = null,
        )
        recompute()
    }

    /** What one premium costs. */
    fun setPremium(value: String) {
        _state.value = _state.value.copy(
            premiumText = value.amountInput(),
            depositError = null,
        )
        recompute()
    }

    /**
     * The day it pays out, answered from the other end.
     *
     * The stored fact is the length, so this is turned straight back into
     * months: a policy is agreed for twenty years, not until the 14th. Whole
     * months only, which is why the date shown afterwards may snap a few days —
     * it is the day the term actually ends, and letting the two disagree would
     * mean one of them was decoration.
     *
     * Never shorter than one payment period, which the picker greys out for the
     * same reason: a policy that matures before the rhythm beside it has come
     * round once is a length its own premiums cannot be counted over.
     */
    fun setPolicyMaturesOn(date: LocalDate) {
        val current = _state.value
        val started = current.depositStartedOn ?: clock.today()
        val months = ChronoUnit.MONTHS.between(started, date).toInt()
        if (months < current.monthsPerPayment) return
        _state.value = current.copy(
            // In years where it divides cleanly, exactly as a length typed in
            // the box beside it reads back: "20 years", not "240 months".
            termText = if (months % 12 == 0) (months / 12).toString() else months.toString(),
            termInYears = months % 12 == 0,
            depositError = null,
        )
        recompute()
    }

    /**
     * Works the schedule out from whatever has been answered so far.
     *
     * Nothing about a policy is derived from anything else — what it pays out
     * and what it costs are both facts off the document — so all this does is
     * count: how many premiums the term holds, what they come to, and the day
     * each one falls. That runs on every keystroke, so the card and the table
     * under it always describe the terms currently in the boxes.
     */
    private fun recomputePolicy(current: HoldingEditorState) {
        val premium = formatter.parse(current.premiumText)
        val maturity = formatter.parse(current.maturityText)
        // One premium against the whole payout. Not the *total* of them, which
        // may honestly exceed it — that is a term plan, and the card says so
        // underneath. This is the figure typed into the wrong box: रू 5,000 a
        // month towards रू 500 is not an arrangement anybody has, and every
        // number worked out from it below would be an answer to nothing.
        val tooBig = premium != null && maturity != null &&
            premium.isPositive && maturity.isPositive &&
            premium.minor >= maturity.minor
        val terms = current.policyTerms?.copy(
            premium = premium ?: Money.ZERO,
            maturityAmount = maturity ?: Money.ZERO,
        )
        if (terms == null || terms.payments <= 0 || tooBig) {
            _state.value = current.copy(
                // The premium is still drawn where it is too big, because the
                // card has to say something and the label above it is "you pay
                // each time" — it is the schedule underneath that is withheld.
                premiumDisplay = premium
                    ?.takeIf { tooBig }
                    ?.let { formatter.formatCompact(it) },
                maturityDisplay = maturity?.let { formatter.formatCompact(it) },
                premiumTotal = null,
                premiumCount = 0,
                premiumDates = emptyList(),
                // The earliest day it could mature, where no length has been
                // answered — see [HoldingEditorState.policyEarliestMaturity].
                // The date box is one of the three faces of one answer, and
                // blank it was the only one of them saying nothing.
                policyMaturesOn = terms?.maturesOn ?: current.policyEarliestMaturity,
                policyGain = null,
                policyShortfall = false,
                policyPremiumTooBig = tooBig,
            )
            return
        }
        // What the arrangement is worth, once. Withheld until both figures are
        // in: half-typed, the difference is the whole of the other one and the
        // card would announce a windfall between two keystrokes.
        val gain = terms.gain.takeIf { terms.premium.isPositive && terms.maturityAmount.isPositive }
        _state.value = current.copy(
            premiumDisplay = premium?.let { formatter.formatCompact(it) },
            maturityDisplay = maturity?.let { formatter.formatCompact(it) },
            premiumTotal = formatter.formatCompact(terms.totalPremiums)
                .takeIf { terms.premium.isPositive },
            premiumCount = terms.payments,
            premiumDates = terms.paymentDates(),
            policyMaturesOn = terms.maturesOn,
            policyGain = gain
                ?.takeIf { !it.isZero }
                ?.let { formatter.formatCompact(it.absolute) },
            policyShortfall = gain != null && gain.minor < 0L,
            policyPremiumTooBig = false,
        )
    }

    // --------------------------------------------------------------- a goal

    /**
     * Works a goal out from whatever has been answered so far.
     *
     * The one figure the user gives is what the goal is *for*; everything else
     * on the card is divided out of it. Where the goal already exists, what it
     * holds is read from the balance rather than from the plan — the bar has to
     * say what actually happened, not what was meant to.
     */
    private fun recomputeGoal(current: HoldingEditorState) {
        val target = formatter.parse(current.maturityText)
        val terms = current.goalTerms?.copy(target = target ?: Money.ZERO)
        val saved = goalSaved
        if (terms == null || terms.payments <= 0 || target == null || !target.isPositive) {
            _state.value = current.copy(
                premiumDisplay = null,
                maturityDisplay = target?.let { formatter.formatCompact(it) },
                premiumTotal = null,
                premiumCount = 0,
                premiumDates = emptyList(),
                policyMaturesOn = null,
                goalSaved = null,
                policyGain = null,
                policyShortfall = false,
                policyPremiumTooBig = false,
                goalSavedFraction = 0f,
                goalLeft = null,
                goalReached = false,
            )
            return
        }
        val left = Goal.remaining(saved, target)
        // What either card would produce, worked out here rather than asked of
        // the repository: nothing about it needs storage, and a preview that
        // waits on a query lands after the next keystroke — see the lump-sum
        // card, which had to cancel its own answers for exactly that reason.
        val deposit = formatter.parse(current.goalDepositText)?.takeIf { it.isPositive }
        val withdraw = formatter.parse(current.goalWithdrawText)?.takeIf { it.isPositive }
        val done = terms.paymentDates().count { !it.isAfter(clock.today()) }
        fun readyOn(after: Money): LocalDate = terms.startedOn.plusMonths(
            Goal.termAfter(
                saved = after,
                target = target,
                perPayment = terms.perPayment,
                everyMonths = terms.everyMonths,
                paymentsDone = done,
            ).toLong()
        )
        _state.value = current.copy(
            premiumDisplay = formatter.formatCompact(terms.perPayment),
            maturityDisplay = formatter.formatCompact(target),
            // What the plan actually comes to, which rounding up puts a hair
            // over the goal. Said rather than hidden — a total that did not
            // match twelve times the figure above it would read as an error.
            premiumTotal = formatter.formatCompact(terms.total),
            premiumCount = terms.payments,
            premiumDates = terms.paymentDates(),
            policyMaturesOn = terms.targetOn,
            // A goal holds exactly what is put into it, so there is no
            // difference to state — and cleared rather than left alone, in case
            // the kind was switched from a policy while the form was open.
            policyGain = null,
            policyShortfall = false,
            policyPremiumTooBig = false,
            goalSaved = formatter.formatCompact(saved).takeIf { current.isEditing },
            goalSavedFraction = Goal.progress(saved, target),
            goalLeft = formatter.formatCompact(left).takeIf { current.isEditing },
            goalReached = current.isEditing && !left.isPositive,
            goalDepositAfter = deposit?.let { formatter.formatCompact(saved + it) },
            goalDepositReadyOn = deposit?.let { readyOn(saved + it) },
            // Taking out more than it holds is refused rather than shown: the
            // preview would be a balance the save will not produce.
            goalWithdrawTooMuch = withdraw != null && withdraw.minor > saved.minor,
            goalWithdrawAfter = withdraw
                ?.takeIf { it.minor <= saved.minor }
                ?.let { formatter.formatCompact(saved - it) },
            goalWithdrawReadyOn = withdraw
                ?.takeIf { it.minor <= saved.minor }
                ?.let { readyOn(saved - it) },
        )
    }

    // What a goal costs each time is not asked for and has no box of its own.
    // It was one half of a pair — type a figure and the rhythm follows, answer
    // the rhythm and the figure follows — and what that produced was the same
    // number twice on one screen under the same words, a box saying रू 8,334
    // and a card below saying रू 8,334, with nothing to say which was the
    // answer. It is divided out of the target in [recomputeGoal] and drawn on
    // the card, which is also the only one of the two that can say how many
    // payments there are and the day they reach it. `Goal.everyMonthsFor` is
    // what the box used to run on and is left where it is: it is tested,
    // pure, and the question it answers may well be asked again.

    fun setGoalDeposit(value: String) {
        // No further than the target. Money put into a goal past what it is for
        // is money the goal cannot use, and the bar it feeds only goes to full.
        val left = formatter.parse(_state.value.maturityText)
            ?.let { Money((it.minor - goalSaved.minor).coerceAtLeast(0L)) }
        _state.value = _state.value.copy(goalDepositText = value.capped(left))
        recompute()
    }

    fun setGoalWithdraw(value: String) {
        // And no more out than is in there, for the mirror of the same reason.
        _state.value = _state.value.copy(goalWithdrawText = value.capped(goalSaved))
        recompute()
    }

    fun setGoalMoveDate(date: LocalDate) {
        _state.value = _state.value.copy(goalMoveDate = date)
    }

    fun setGoalDepositAccount(id: String?) {
        _state.value = _state.value.copy(goalDepositAccountId = id)
    }

    fun setGoalWithdrawAccount(id: String?) {
        _state.value = _state.value.copy(goalWithdrawAccountId = id)
    }

    /** Puts money in early, which brings the day the goal is reached forward. */
    fun applyGoalDeposit() = moveGoalMoney(deposit = true)

    /** Takes money back out, which pushes it away again. */
    fun applyGoalWithdraw() = moveGoalMoney(deposit = false)

    private fun moveGoalMoney(deposit: Boolean) = viewModelScope.launch {
        val current = _state.value
        val id = accountId ?: return@launch
        val amount = formatter.parse(
            if (deposit) current.goalDepositText else current.goalWithdrawText
        ) ?: return@launch
        val account = (
            if (deposit) current.goalDepositAccountId else current.goalWithdrawAccountId
            ) ?: return@launch
        if (!deposit && amount.minor > goalSaved.minor) return@launch
        plans.moveGoalMoney(
            goalId = id,
            amount = amount,
            deposit = deposit,
            accountId = account,
            on = current.goalMoveDate,
        )
        // The form stays open and reads the goal back, exactly as a loan's
        // payment card does: what the user wants next is the bar this movement
        // produced, and it is on this screen.
        loadAccount(id)
        _state.value = _state.value.copy(
            goalDepositText = "",
            goalWithdrawText = "",
            message = HoldingMessage(
                if (deposit) R.string.holding_msg_goal_in else R.string.holding_msg_goal_out,
                listOfNotNull(formatter.formatCompact(amount), _state.value.goalSaved),
            ),
        )
    }

    /** Said once. The screen clears it as soon as it has been shown. */
    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Fills the name from a bank already on file. Still editable afterwards. */
    fun pickBank(name: String) {
        _state.value = _state.value.copy(name = name, nameError = null, currencyError = null)
    }

    /**
     * What the rate has been, newest first — but only once it has actually
     * moved. A holding still on the rate it opened at has no history worth the
     * name, and printing "8.25% from the day you opened it" is noise.
     */
    private suspend fun rateHistoryLines(
        accountId: String? = null,
        loanId: String? = null,
    ): List<RateHistoryRow> = interest.changesFor(accountId, loanId)
        .map { RateHistoryRow(it.annualRate, it.effectiveFrom) }
        .sortedByDescending { it.from }

    /** Shows the rate, length and schedule on a loan that was recorded without them. */
    fun revealTerms() {
        _state.value = _state.value.copy(termsRevealed = true)
    }

    fun setCurrency(code: String) {
        formatter = MoneyFormatter(CurrencyOption.byCode(code), grouping = settings.grouping)
        // Cleared here and wherever the name moves: the refusal is about the
        // *pair*, so changing either half is the user answering it.
        _state.value = _state.value.copy(currencyCode = code, currencyError = null)
        recompute()
    }

    fun setOpening(value: String) {
        _state.value = _state.value.copy(openingText = value.amountInput(allowSign = true))
    }

    fun setShowInDisplayCurrency(enabled: Boolean) {
        _state.value = _state.value.copy(showInDisplayCurrency = enabled)
    }

    fun setColor(color: Color) {
        colorPicked = true
        _state.value = _state.value.copy(color = color)
    }

    fun setPrincipal(value: String) {
        _state.value = _state.value.copy(
            principalText = value.amountInput(),
            amountError = false,
        )
        recompute()
    }

    /**
     * The rate. On something that already exists, changing it is the bank having
     * moved it, so the form asks from when — and goes back to not asking the
     * moment the user types the old figure again.
     */
    fun setRate(value: String) {
        val current = _state.value
        val typed = value.filter { it.isDigit() || it == '.' }
        _state.value = current.copy(
            rateText = typed,
            rateChangedOn = when {
                !current.isEditing -> null
                typed == openingRateText -> null
                // An overdraft with nothing drawn has no interest for a date to
                // split: the new figure is simply what the facility charges from
                // now on, and asking when it changed would be asking about a
                // period in which nothing was owed.
                current.isOverdraft && !current.hasDrawnBalance -> null
                // A rate that *moves* is agreed on the day it is agreed, so
                // today: defaulting to the day the money moved would silently
                // charge a debt for months nobody had agreed a rate over.
                //
                // A rate given to a holding that never had one is not a rate
                // that moved, and there the day the money changed hands is
                // exactly what the two of them mean. "We agreed 5% on the money
                // I lent you in Baisakh" charges from Baisakh; today would
                // charge nothing for the months it has already been out, which
                // is the one answer neither party intends.
                else -> current.rateChangedOn
                    ?: (if (current.hadRate) null else current.movedOn)
                    ?: clock.today()
            },
        )
        recompute()
    }

    fun setRateChangedOn(date: LocalDate) {
        _state.value = _state.value.copy(rateChangedOn = date)
    }

    /**
     * How often this bank credits the interest. Nothing is recomputed from it
     * here: no period has closed yet — that is what keeps the box open at all —
     * so there is no figure on screen for it to move until the form is saved.
     */
    fun setPayoutInterval(value: String) {
        _state.value = _state.value.copy(payoutText = value.filter { it.isDigit() })
    }

    fun setPayoutInYears(inYears: Boolean) {
        _state.value = _state.value.copy(payoutInYears = inYears)
    }

    /**
     * Whether this bank counts the holding's months in Nepali ones.
     *
     * Only the opt-in moves; what the app actually counts in is this and the
     * calendar being read — see [HoldingEditorState.loanStepsInBs] and, on the
     * saving side, [CalendarSystem.forInterest].
     */
    fun setInterestInBs(on: Boolean) {
        _state.value = _state.value.copy(interestInBs = on)
    }

    fun setTerm(value: String) {
        _state.value = _state.value.copy(
            termText = value.filter { it.isDigit() },
            // The moment the day the money arrived starts to matter. Today is
            // right for a loan being recorded as it is taken, and it is the only
            // date the app can honestly assume; it stays editable.
            disbursedOn = _state.value.disbursedOn ?: clock.today(),
        ).withDefaultEmiStart()
        recompute()
    }

    fun setTermInYears(inYears: Boolean) {
        _state.value = _state.value.copy(termInYears = inYears).withDefaultEmiStart()
        recompute()
    }

    /**
     * How often the instalment falls. Recomputed rather than only rescheduled:
     * a loan paid every three months accrues three months of interest between
     * payments, so both the instalment and the total cost move with this.
     */
    fun setPayEvery(value: String) {
        _state.value = _state.value
            .copy(payEveryText = value.filter { it.isDigit() })
            .withDefaultEmiStart()
        recompute()
    }

    fun setPayEveryUnit(unit: PayEvery) {
        _state.value = _state.value.copy(payEveryUnit = unit).withDefaultEmiStart()
        recompute()
    }

    /**
     * The first instalment's date, while it is still the app's answer rather
     * than the user's: one whole payment period after the money arrived, which
     * is what a bank does.
     *
     * It follows both of the things that decide it rather than being filled in
     * once. The period, because a loan paid every six months does not start
     * paying next month and one settled at the end is due once, when the term
     * runs out. And the day the money arrived, because that is what the period
     * is counted from — a loan taken last September was answered "one month from
     * today", so somebody entering a year-old debt had to correct a date the
     * form had made no attempt at. A date the user picked themselves is never
     * moved.
     *
     * Only once a length has been given, which is the moment the question starts
     * to have an answer at all.
     */
    private fun HoldingEditorState.withDefaultEmiStart(): HoldingEditorState =
        if (emiStartPicked || termText.isBlank()) {
            this
        } else {
            copy(
                emiStartsOn = Recurrence.addMonths(
                    disbursedOn ?: clock.today(),
                    monthsPerPayment.toLong(),
                    loanStepsInBs,
                )
            )
        }

    fun setStyle(style: InstalmentStyle) {
        _state.value = _state.value.copy(style = style)
        recompute()
    }

    /**
     * Which account the repayments run through. Nullable, because between
     * people there is often no account at all — see [payFromOptional].
     */
    fun setPayFrom(id: String?) {
        _state.value = _state.value.copy(payFromAccountId = id)
    }

    /**
     * The account this one lump sum moves through.
     *
     * Deliberately not written back to the loan. It describes the payment being
     * recorded now, not the arrangement: a debt usually repaid from the bank can
     * still be paid down once in cash, and answering that here must not rewrite
     * where every earlier payment came from.
     */
    fun setPrepayAccount(id: String?) {
        _state.value = _state.value.copy(prepayAccountId = id)
    }

    /** The same question for money added to the debt, and the same rule. */
    fun setMoreAccount(id: String?) {
        _state.value = _state.value.copy(moreAccountId = id)
    }

    /**
     * Where the money landed, or left from, on the day the debt was made.
     *
     * Null is a first-class answer rather than a cleared field: it means the app
     * writes nothing into any account, which is the right outcome for a debt
     * recorded long after it moved.
     */
    fun setDisbursedAccount(id: String?) {
        _state.value = _state.value.copy(disbursedAccountId = id)
    }

    fun setEmiStartsOn(date: LocalDate) {
        emiStartPicked = true
        _state.value = _state.value.copy(emiStartsOn = date)
        recompute()
    }

    /**
     * The day the bank approved a card or an overdraft.
     *
     * Nothing is recomputed: no schedule, no interest and no balance is counted
     * from it — the only thing it decides is the day the facility runs out, and
     * the form states that back from the value in the state.
     */
    fun setOpenedOn(date: LocalDate) {
        _state.value = _state.value.copy(openedOn = date)
    }

    /**
     * The day the money arrived.
     *
     * Recomputes, because it decides two things at once: how many days of
     * interest the first payment settles, and — through that — whether the
     * schedule's own first instalment is the next recovery date rather than
     * this one.
     */
    fun setDisbursedOn(date: LocalDate) {
        // The first instalment follows it, until the user says otherwise. It is
        // the day the period is counted from, so a date moved here and a first
        // EMI left where it was describe an arrangement neither of them meant —
        // and answering the second question is the only way to find that out.
        _state.value = _state.value.copy(disbursedOn = date).withDefaultEmiStart()
        recompute()
    }

    /**
     * Recalculates the schedule as the user types.
     *
     * Both ends of it are shown, because on an equal-principal loan they differ:
     * the first payment is the one that has to be affordable and the last is the
     * one that shows how much the burden eases.
     */
    /**
     * A debt settled in one payment: what goes back, and the interest inside it.
     *
     * [FixedDeposit] does the arithmetic rather than [LoanMath], and that is the
     * point — a rate agreed between two people for an agreed length is simple
     * interest over that length, the same as a deposit, and amortising it would
     * quote an instalment nobody is going to pay. Interest-free is the common
     * case and falls out of a null rate: the total is just what was borrowed.
     *
     * **Unless the rate has not been in force the whole time.** A rate agreed
     * half way through — or added to a debt that never had one — charges the days
     * since it was agreed and not one day more, and the deposit's arithmetic
     * cannot say that: it knows a length and a figure, not when the figure
     * started applying. So a debt with a rate history is charged through the
     * history, which splits at each change and returns nothing at all for the
     * span before the first one. Both agree exactly on the ordinary case of a
     * whole-year term at one rate.
     */
    private fun recomputeOneGo(current: HoldingEditorState, principal: Money?, months: Int?) {
        val rate = if (current.isEditing) {
            storedBaseRate ?: current.rateText.toDoubleOrNull()
        } else {
            current.rateText.toDoubleOrNull()
        }
        if (principal == null || principal.minor <= 0L) {
            _state.value = current.copy(
                totalToRepay = null,
                interestToRepay = null,
                oneGoDueOn = null,
            )
            return
        }
        val terms = FixedDeposit.Terms(
            principal = principal,
            annualRate = rate ?: 0.0,
            // The day it changed hands. It does not move the total on the
            // ordinary path — that arithmetic never accrues day by day — but it
            // is what the length is counted from, so the day it is owed back
            // falls out of the two facts the user gave rather than being asked
            // for a third time. With a rate history it is also where the
            // charging starts from.
            startedOn = current.disbursedOn ?: clock.today(),
            termMonths = months ?: 0,
        )
        val interest = if (storedChanges.isEmpty()) {
            FixedDeposit.totalInterest(terms)
        } else {
            RateSchedule(storedBaseRate ?: 0.0, storedChanges)
                .interest(principal, from = terms.startedOn, to = terms.maturesOn)
        }
        _state.value = current.copy(
            totalToRepay = formatter.formatCompact(principal + interest),
            interestToRepay = formatter.formatCompact(interest).takeIf { interest.isPositive },
            // Only when a length was actually agreed. Most money between people
            // has none, and a date computed from a blank would be an invention.
            oneGoDueOn = terms.maturesOn.takeIf { (months ?: 0) > 0 },
        )
    }

    private fun recompute() {
        val current = _state.value
        // A deposit has its own arithmetic and none of a loan's — no
        // instalment, no schedule, no balance to amortise — so it branches here
        // rather than threading nulls through everything below.
        if (current.isFixedDeposit) {
            recomputeDeposit(current)
            return
        }
        // A policy has none of a loan's arithmetic either: what it pays out is
        // the insurer's figure and what it costs is theirs too, so all that is
        // worked out is the schedule between them.
        if (current.isInsurance) {
            recomputePolicy(current)
            return
        }
        // A goal is the same shape with the division running the other way:
        // one figure given, and what it costs each time worked out from it.
        if (current.isGoal) {
            recomputeGoal(current)
            return
        }
        val principal = formatter.parse(current.principalText)
        val months = termMonths(current)
        // Money between people is handed back in one go, so what it needs is a
        // total rather than an instalment — worked out exactly as a fixed
        // deposit's maturity value is, because it is the same arrangement seen
        // from the other side.
        if (current.paysInOneGo) {
            recomputeOneGo(current, principal, months)
            return
        }
        // Nothing to quote on an overdraft: the figure in the box is a limit,
        // and an instalment computed on money not yet borrowed would be fiction.
        if (current.isOverdraft || principal == null || months == null) {
            _state.value = current.copy(
                quotedEmi = null,
                finalPayment = null,
                totalInterest = null,
                derivedTermMonths = null,
                nextSplitPrincipal = null,
                nextSplitInterest = null,
                scheduleFromHere = emptyList(),
            )
            return
        }
        // The rate this loan *started* at, which is what every figure before the
        // first repricing was charged at. On a loan being created there is only
        // one rate and it is whatever is in the box.
        val rate = if (current.isEditing) {
            storedBaseRate ?: current.rateText.toDoubleOrNull() ?: 0.0
        } else {
            current.rateText.toDoubleOrNull() ?: 0.0
        }
        val typed = formatter.parse(current.emiText)?.takeIf { it.isPositive }
        val gap = current.monthsPerPayment
        // What the bank takes on the first recovery date when it arrives before
        // a whole period has passed: the days since the money landed, in days
        // rather than twelfths, and nothing off the balance. At the rate in force
        // *then* — a repricing next month cannot change what September cost.
        val stub = if (
            BrokenPeriod.applies(
                current.disbursedOn, current.emiStartsOn, gap, months,
                current.loanStepsInBs,
            )
        ) {
            LoanMath.brokenPeriodInterest(
                principal = principal,
                annualRatePercent = rate,
                disbursedEpochDay = current.disbursedOn!!.toEpochDay(),
                firstRecoveryEpochDay = current.emiStartsOn!!.toEpochDay(),
            ).takeIf { it.isPositive }
        } else {
            null
        }
        // The table charges the same days the saved loan will. Anchored on the
        // day the money arrived, so the figures the user is quoted while typing
        // are the ones they get after Save.
        // Every rate this loan has been on, plus the one being typed right now,
        // so the table below shows what saving would actually produce rather
        // than the whole loan recomputed at today's rate.
        val pending = current.rateChangedOn
            ?.let { on -> current.rateText.toDoubleOrNull()?.let { RateChange(on, it) } }
        val rates = (storedChanges + listOfNotNull(pending))
            .distinctBy { it.effectiveFrom }
            .takeIf { it.isNotEmpty() }
            ?.let { RateSchedule(base = rate, changes = it) }
        // The loan's own two facts, not the disbursement — see [storedStartedOn].
        // A lump sum moves the day this balance started running to itself and
        // leaves the days behind it to be collected by the next instalment, and
        // a preview that measured from the disbursement instead quoted a
        // schedule the app was not running.
        val accrual = accrualFor(
            startedOn = storedStartedOn ?: current.disbursedOn ?: clock.today(),
            firstPaymentOn = current.firstInstalmentOn,
            monthsPerPayment = gap,
            carriedInterest = storedCarriedInterest,
            rates = rates,
            inBikramSambat = current.loanStepsInBs,
        )
        val rows = LoanMath.schedule(
            principal, rate, months, typed, current.style, gap, accrual,
            current.missedPeriods,
        )
        // The shape of the schedule is told by the rows that are *payments*. A
        // period nobody paid is a row of zeroes — that is how a missed
        // instalment keeps its place in the numbering — and reading the first
        // one blind quoted "your first payment: रू 0" on any debt whose opening
        // instalment had been swiped away, with the real figure on the very next
        // line down.
        val paying = rows.filter { it.payment.isPositive }
        val first = paying.firstOrNull()?.payment
        val last = paying.lastOrNull()?.payment
        // What is left of the schedule. The instalments already made are history
        // the user cannot change from here, and leading with them would answer
        // "how much of this payment is interest?" for a payment long gone.
        val ahead = rows.drop(current.paymentsMade).map { row ->
            ScheduleRow(
                number = row.number,
                // Counted from the first *full* instalment, not from the bank's
                // first recovery date: where those differ, the day in between
                // settled a broken period and is not one of these rows.
                //
                // Each payment falls one interval after the one before, which on
                // a quarterly loan is three months — the same gap the interest
                // accrues over, so the dates and the arithmetic cannot disagree.
                date = current.firstInstalmentOn?.let {
                    Recurrence.addMonths(
                        it, (row.number - 1).toLong() * gap, current.loanStepsInBs,
                    )
                },
                payment = formatter.formatBare(row.payment),
                principal = formatter.formatBare(row.principal),
                interest = formatter.formatBare(row.interest),
                balance = formatter.formatBare(row.balance),
            )
        }
        // The broken period, at the head of the table where the bank takes it.
        //
        // It is a payment on a date, and the first one there is: money received
        // on 3 September against a bank that recovers on the 20th meets the 20th
        // before a whole period has passed, and what is taken then is the
        // interest for those days with the principal untouched. The table began
        // at the *next* recovery date instead, so a user who had just typed
        // "20 September" as their first EMI date was shown a schedule starting
        // 20 October with a payment they will actually make missing from it.
        //
        // Only while creating. Once the loan exists that charge is a row in the
        // timeline like any other, and this table lists what is left.
        val stubRow = stub?.takeIf { !current.isEditing }?.let {
            ScheduleRow(
                // Unused — it labels a row that has no date, and this one always
                // has: the stub exists only because there is a recovery date for
                // it to fall on.
                number = 0,
                date = current.emiStartsOn,
                payment = formatter.formatBare(it),
                // All interest and no principal, which is the whole of what
                // makes it not an instalment: the balance is where it started.
                principal = formatter.formatBare(Money.ZERO),
                interest = formatter.formatBare(it),
                balance = formatter.formatBare(principal),
            )
        }
        // The broken period is interest over the whole loan too, and counts
        // towards the total exactly when it is drawn as a row — a reader adding
        // up the column would otherwise find the stated total short by it.
        val stubInterest = if (stubRow != null) stub?.minor ?: 0L else 0L
        // The next payment's split, said in prose above the table, so both
        // figures carry their currency where nothing else states it.
        val next = rows.getOrNull(current.paymentsMade)
        _state.value = current.copy(
            quotedEmi = first?.let { formatter.formatCompact(it) },
            nextSplitPrincipal = next?.let { formatter.formatCompact(it.principal) },
            nextSplitInterest = next?.let { formatter.formatCompact(it.interest) },
            scheduleFromHere = listOfNotNull(stubRow) + ahead,
            // Only worth saying when it actually changes. It can go either way: a
            // falling equal-principal schedule, or a level instalment the user has
            // overridden with too small a figure, which leaves a balloon at the end.
            finalPayment = last?.takeIf { first != null && it != first }
                ?.let { formatter.formatCompact(it) },
            finalPaymentIsLower = first != null && last != null && last < first,
            totalInterest = Money(rows.sumOf { it.interest.minor } + stubInterest)
                .takeIf { it.isPositive }?.let { formatter.formatCompact(it) },
            // Said in months, because that is the unit the term box is in — the
            // schedule counts payments, which on a quarterly loan is a third of it.
            derivedTermMonths = (rows.size * gap).takeIf { rows.isNotEmpty() && it != months },
        )
    }

    /**
     * How many months the loan runs.
     *
     * On an equal-principal loan the principal slice decides it: someone paying
     * रू 10,000 a month off रू 500,000 is on a 50-month loan whatever the term
     * box says, and deriving it saves them working it out.
     */
    private fun termMonths(state: HoldingEditorState): Int? {
        if (state.isPrincipalOnly) {
            val perMonth = formatter.parse(state.emiText)?.takeIf { it.isPositive }
            val principal = formatter.parse(state.principalText)
            if (perMonth != null && principal != null) {
                return LoanMath.termForMonthlyPrincipal(
                    principal, perMonth, state.monthsPerPayment,
                )
            }
        }
        return state.termInMonths
    }

    // -------------------------------------------------------------- lump sum

    fun setPrepay(value: String) {
        _state.value = _state.value.copy(prepayText = value.capped(maxPayable))
        previewPrepay()
    }

    /**
     * What is typed, kept at or under [ceiling].
     *
     * Half-typed input is left alone — a lone "." or a trailing one is somebody
     * mid-figure, and a box that rewrote itself under the caret would be
     * unusable. Only a figure that parses and is genuinely too big is replaced,
     * and it is replaced with the ceiling rather than refused, because that is
     * the answer the user was reaching for.
     */
    private fun String.capped(ceiling: Money?): String {
        val digits = amountInput()
        val limit = ceiling?.takeIf { it.isPositive } ?: return digits
        val typed = formatter.parse(digits) ?: return digits
        return if (typed.minor > limit.minor) formatter.toPlainInput(limit) else digits
    }

    /**
     * What the user typed, kept to a figure this currency can actually hold.
     *
     * Two things are dropped: anything that is not part of an amount, and any
     * decimal past the currency's own places — two for most, three for the Gulf
     * dinars, none at all for a currency with no minor unit.
     *
     * The second is the one that was missing. [MoneyFormatter.parse] has always
     * truncated the extra places on the way to a [Money], so a box reading
     * "15000.999999999" was already worth रू 15,000.99 to everything downstream —
     * including the ceiling a payment is clamped to, which is why a figure over
     * the limit could sit in the box looking accepted. Refusing the digits as
     * they are typed says the same thing where the user can see it.
     *
     * Only the fraction is touched. The whole part, a trailing ".", and a lone
     * grouping separator are all somebody mid-figure, and a box that tidied
     * those under the caret would be unusable.
     */
    private fun String.amountInput(allowSign: Boolean = false): String {
        val kept = filter { it.isAmountChar() || (allowSign && it == '-') }
        val point = kept.indexOf('.')
        if (point < 0) return kept
        // A currency with no minor unit has no decimals to type into at all.
        if (formatter.minorUnits == 0) return kept.substring(0, point)
        val fraction = kept.substring(point + 1)
            .filter { it.isDigit() }
            .take(formatter.minorUnits)
        return kept.substring(0, point + 1) + fraction
    }

    /**
     * Shows both outcomes side by side before anything is committed.
     *
     * The previous one is cancelled first. Each keystroke asks the repository a
     * fresh question, and without this they raced: typing "1000" left the answer
     * for "1" on the screen whenever it happened to come back last, so the line
     * promised a balance रू 999 away from what the button would produce.
     */
    private fun previewPrepay() {
        previewJob?.cancel()
        previewJob = launchPreviewPrepay()
    }

    private fun launchPreviewPrepay() = viewModelScope.launch {
        val id = loanId ?: return@launch
        val current = _state.value
        val amount = formatter.parse(current.prepayText)
        if (amount == null || !amount.isPositive) {
            _state.value = _state.value.copy(
                prepayShorterMonths = null,
                prepayLowerEmi = null,
                prepaySavedByShortening = null,
                prepaySavedByLowering = null,
                prepayNewBalance = null,
            )
            return@launch
        }
        val outcome = loans.previewPrepayment(id, amount, current.prepayDate)
        _state.value = _state.value.copy(
            prepayShorterMonths = outcome?.shorterTermMonths,
            prepayLowerEmi = outcome?.sameTermEmi?.let { formatter.formatCompact(it) },
            prepaySavedByShortening = outcome?.interestSavedByShortening
                ?.takeIf { it.isPositive }?.let { formatter.formatCompact(it) },
            prepaySavedByLowering = outcome?.interestSavedByLowering
                ?.takeIf { it.isPositive }?.let { formatter.formatCompact(it) },
            prepayNewBalance = outcome?.newBalance?.let { formatter.formatCompact(it) },
        )
    }

    /**
     * No preview to redo: what the payment is *about* changes nothing about what
     * it leaves owing.
     */
    fun setPrepayNote(value: String) {
        _state.value = _state.value.copy(prepayNote = value)
    }

    fun setPrepayDate(date: LocalDate) {
        _state.value = _state.value.copy(prepayDate = date)
        // The day decides which balance the payment meets, so the two outcomes
        // have to be worked out again: backdating a lump sum past an instalment
        // changes what it leaves owing.
        previewPrepay()
    }

    /**
     * @param keepInstalment true to finish sooner, false to pay less each month.
     *
     * Stays on the form afterwards rather than closing it. Recording a payment
     * is not finishing with the debt — the usual next thing is to look at what
     * it left owing, or to record another — and being thrown back out to the
     * accounts list meant tapping back in to see whether it had worked. The
     * loan is read again so every figure on the screen is the one the payment
     * produced, and the box empties to say it was taken.
     */
    fun applyPrepay(keepInstalment: Boolean) = viewModelScope.launch {
        val id = loanId ?: return@launch
        val current = _state.value
        val amount = formatter.parse(current.prepayText) ?: return@launch
        loans.applyPrepayment(
            loanId = id,
            amount = amount,
            keepInstalment = keepInstalment,
            // This payment's own account, which is what the card asked for.
            accountId = current.prepayAccountId,
            paidOn = current.prepayDate,
            // Blank is nothing said, and the row then carries the debt's own
            // name as it always has.
            note = current.prepayNote,
        )
        // A payment dated forward has been written down and nothing else — see
        // `LoanRepository.applyDuePayments` — so the card must not report money
        // off a balance that has not moved. It says when instead, which is the
        // whole of what the user just told it.
        val ahead = current.prepayDate?.isAfter(clock.today()) == true
        loadLoan(id)
        val after = _state.value
        _state.value = after.copy(
            prepayText = "",
            prepayNote = "",
            prepayNewBalance = null,
            prepayShorterMonths = null,
            prepayLowerEmi = null,
            prepaySavedByShortening = null,
            prepaySavedByLowering = null,
            message = HoldingMessage(
                when {
                    ahead -> R.string.holding_msg_due
                    after.isLent -> R.string.holding_msg_received
                    else -> R.string.holding_msg_paid
                },
                // The balance is deliberately left out of the pending one: it has
                // not moved, and printing it beside the payment would read as the
                // figure the payment produced.
                listOfNotNull(
                    formatter.formatCompact(amount),
                    after.outstanding.takeIf { !ahead },
                ),
            ),
        )
    }

    // ------------------------------------------------------------ more of it

    fun setMore(value: String) {
        val current = _state.value
        val typed = value.amountInput()
        val amount = formatter.parse(typed)
            ?.takeIf { it.isPositive }
        _state.value = current.copy(
            moreText = typed,
            // Worked out here rather than in the repository: nothing has
            // happened yet, and the whole point of the line is to say what
            // would.
            moreNewBalance = amount?.let { extra ->
                owed?.let { formatter.formatCompact(it + extra) }
            },
        )
    }

    fun setMoreNote(value: String) {
        _state.value = _state.value.copy(moreNote = value)
    }

    fun setMoreDate(date: LocalDate) {
        _state.value = _state.value.copy(moreDate = date)
    }

    /** Records more money lent or borrowed on the same arrangement. */
    fun applyMore() = viewModelScope.launch {
        val id = loanId ?: return@launch
        val current = _state.value
        val amount = formatter.parse(current.moreText)?.takeIf { it.isPositive } ?: return@launch
        loans.increaseLoan(
            loanId = id,
            amount = amount,
            accountId = current.moreAccountId,
            date = current.moreDate,
            note = current.moreNote,
        )
        // Read back rather than closed, for the same reason a payment is: what
        // the user wants next is the new balance, and it is on this screen.
        loadLoan(id)
        val after = _state.value
        _state.value = after.copy(
            moreText = "",
            moreNote = "",
            moreNewBalance = null,
            message = HoldingMessage(
                if (after.isLent) R.string.holding_msg_lent_more else R.string.holding_msg_borrowed_more,
                listOfNotNull(formatter.formatCompact(amount), after.outstanding),
            ),
        )
    }

    // ------------------------------------------------------------------ saving

    fun save() = viewModelScope.launch {
        val current = _state.value
        if (current.isLoan) saveLoan(current) else saveAccount(current)
    }

    /**
     * The rate to store *on* the holding.
     *
     * When the bank has moved it, that is still the rate the holding opened at:
     * the new one lives in its own dated row, and overwriting the base would
     * recompute every quarter and every instalment already settled at the old
     * one. Only a holding being given a rate for the first time writes here.
     */
    private fun baseRate(current: HoldingEditorState): Double? =
        if (current.isEditing) storedBaseRate else current.rateText.toDoubleOrNull()

    /** Records the move itself, once the holding it belongs to exists. */
    private suspend fun recordRateChange(current: HoldingEditorState, id: String) {
        val on = current.rateChangedOn ?: return
        // An emptied box means zero, not "nothing to record". The date only
        // appears once the figure has actually been changed, so reaching here
        // with a blank one means a rate that existed has been taken out — and
        // doing nothing about it left the debt quietly charging the old rate.
        val rate = current.rateText.toDoubleOrNull() ?: 0.0
        if (current.isLoan) {
            loans.applyRateChange(id, rate, on)
        } else {
            interest.recordChange(accountId = id, annualRate = rate, effectiveFrom = on)
        }
    }

    /**
     * A policy and the rule that pays for it, saved together.
     *
     * Every one of the four facts is required, because each is load-bearing:
     * without the payout there is nothing to look forward to, without the
     * premium nothing leaves, without the length there is no day it ends, and
     * without an account the premiums have nowhere to come from. A policy
     * missing any of them would sit in the list saying nothing and paying
     * nothing.
     */
    private suspend fun savePolicy(current: HoldingEditorState) {
        val maturity = formatter.parse(current.maturityText)
        val premium = formatter.parse(current.premiumText)
        val missing = when {
            maturity == null || !maturity.isPositive -> R.string.insurance_error_maturity
            premium == null || !premium.isPositive -> R.string.insurance_error_premium
            // Asked here as well as shown on the card: the card is only drawn
            // once both figures are in, and Save must not write a policy the
            // form has already said is impossible.
            premium.minor >= maturity.minor -> R.string.insurance_error_premium_too_big
            current.termInMonths == null -> R.string.insurance_error_term
            current.depositStartedOn == null -> R.string.insurance_error_started
            current.payFromAccountId == null -> R.string.insurance_error_pay_from
            else -> null
        }
        if (missing != null) {
            _state.value = current.copy(depositError = missing)
            return
        }
        val result = plans.savePolicy(
            id = accountId,
            name = current.name,
            currencyCode = current.currencyCode,
            color = current.color,
            showInDisplayCurrency =
                current.showInDisplayCurrency && current.canConvertForDisplay,
            maturityAmount = maturity!!,
            premium = premium!!,
            startedOn = current.depositStartedOn!!,
            termMonths = current.termInMonths!!,
            everyMonths = current.monthsPerPayment,
            payFromAccountId = current.payFromAccountId!!,
            maturesIntoAccountId = current.depositIntoAccountId,
            optedIntoSelectedCalendar = current.usesSelectedCalendar,
        )
        when (result) {
            is SaveResult.Success -> _state.value = current.copy(isSaved = true)
            SaveResult.NameRequired ->
                _state.value = current.copy(nameError = R.string.error_label_name_required)
            else -> Unit
        }
    }

    /**
     * A goal and the plan that reaches it.
     *
     * The same four answers a policy needs, minus the one it does not have: what
     * each payment costs is not asked here, it is divided out of the figure the
     * goal is for. Everything else is required for the same reasons — without a
     * target there is nothing to reach, without a length no day to reach it by,
     * and without an account nothing to put aside from.
     */
    private suspend fun saveGoal(current: HoldingEditorState) {
        val target = formatter.parse(current.maturityText)
        val missing = when {
            target == null || !target.isPositive -> R.string.goal_error_target
            current.termInMonths == null -> R.string.goal_error_term
            current.depositStartedOn == null -> R.string.goal_error_started
            current.payFromAccountId == null -> R.string.goal_error_pay_from
            else -> null
        }
        if (missing != null) {
            _state.value = current.copy(depositError = missing)
            return
        }
        val result = plans.saveGoal(
            id = accountId,
            name = current.name,
            currencyCode = current.currencyCode,
            color = current.color,
            showInDisplayCurrency =
                current.showInDisplayCurrency && current.canConvertForDisplay,
            target = target!!,
            startedOn = current.depositStartedOn!!,
            termMonths = current.termInMonths!!,
            everyMonths = current.monthsPerPayment,
            payFromAccountId = current.payFromAccountId!!,
            maturesIntoAccountId = current.depositIntoAccountId,
            optedIntoSelectedCalendar = current.usesSelectedCalendar,
        )
        when (result) {
            is SaveResult.Success -> _state.value = current.copy(isSaved = true)
            SaveResult.NameRequired ->
                _state.value = current.copy(nameError = R.string.error_label_name_required)
            else -> Unit
        }
    }

    private suspend fun saveAccount(current: HoldingEditorState) {
        // One provider, one wallet per currency.
        //
        // A second Wise in dollars is not a second wallet — it is the one they
        // already have, entered twice. Nothing downstream could tell the two
        // apart: both rows read "Wise · USD" on the accounts page, both chips
        // read "Wise (USD)" on the money form, and the tabs on either of them
        // would offer two chips saying "USD" with no way to know which is which.
        // What the user means is the wallet already on file, and money that
        // belongs in it goes in by correcting its balance.
        //
        // Only wallets. A bank really can hold two savings accounts in one
        // currency, and it has the optional name field to tell them apart with;
        // a wallet has neither.
        if (current.choice.group == HoldingGroup.WALLET && current.walletClashes()) {
            _state.value = current.copy(currencyError = R.string.error_wallet_currency_taken)
            return
        }
        if (current.isInsurance) {
            savePolicy(current)
            return
        }
        if (current.isGoal) {
            saveGoal(current)
            return
        }
        if (current.isFixedDeposit) {
            // The three that describe the arrangement, or none. A deposit missing
            // any of them has no balance the app can work out and no day on which
            // to hand the money back, so saving a half-described one would produce
            // a holding that quietly reads as zero forever.
            //
            // Where it lands is deliberately not among them. It is the one answer
            // the deposit does not need to be described: the maturity is drawn as
            // a forecast, and one that names only the deposit it leaves still says
            // the day and the figure. Refusing to save without it turned a deposit
            // made before its destination account existed into a form that could
            // not be got out of.
            val missing = when {
                current.rateText.toDoubleOrNull() == null && !current.isEditing ->
                    R.string.fd_error_rate
                current.termInMonths == null -> R.string.fd_error_term
                current.depositStartedOn == null -> R.string.fd_error_started
                else -> null
            }
            if (missing != null) {
                _state.value = current.copy(depositError = missing)
                return
            }
        }
        val result = wallet.saveAccount(
            id = accountId,
            annualRate = baseRate(current).takeIf { current.earnsInterest },
            // A half-typed box is not an answer, so an empty one keeps whatever
            // the account already had — and on a settled one the box is showing
            // the stored figure anyway, so saving writes it back unchanged.
            interestPayoutMonths = (current.payoutMonths ?: storedPayoutMonths)
                .takeIf { current.offersPayoutInterval },
            interestInBs = current.usesSelectedCalendar,
            // And what that comes to, on the column every reader of a deposit's
            // term looks at. A deposit's maturity is a pure function of the
            // account row — nothing can reach the setting from there — so the
            // joined answer is stored beside the opt-in, the way a plan's is.
            planRecurInBs = current.usesSelectedCalendar && current.calendarIsNepali,
            // The two columns doing what they were made for: the bank in
            // `institution`, this holding's own name in `name`. Where the user
            // gave no name of its own the bank goes in both, so everything that
            // shows a name — an entry's row, a picker — still shows one, and
            // `ownName` reads them as the same thing and falls back to the kind.
            name = current.holdingOwnName ?: current.name,
            // Guaranteed non-null: an account choice is exactly what is not a loan.
            kind = current.choice.accountKind ?: return,
            currencyCode = current.currencyCode,
            institution = if (current.offersHoldingName) {
                current.name.trim().takeIf { it.isNotEmpty() }
            } else {
                current.institution.takeIf { it.isNotBlank() }
            },
            openingBalance = formatter.parse(current.openingText) ?: Money.ZERO,
            color = current.color,
            // Meaningless for an account already in the display currency, and
            // storing true there would surprise the user if they later changed
            // their display currency.
            showInDisplayCurrency = current.showInDisplayCurrency && current.canConvertForDisplay,
            depositStartedOn = current.depositStartedOn,
            depositTermMonths = current.termInMonths,
            maturesIntoAccountId = current.depositIntoAccountId,
        )
        when (result) {
            is SaveResult.Success -> {
                recordRateChange(current, result.id)
                // A moved interval moves the payout days themselves, and every
                // figure is worked out from those. Nothing has been credited yet
                // — that is what keeps the box open at all — but a period can
                // close while the app is sitting open, and it is owed on the
                // rhythm the user has just given rather than the one they
                // replaced. Only when it actually moved: this walks the whole
                // history of every account, and a save that changed a colour
                // must not pay for that.
                //
                // The same is true of which calendar those days are counted in,
                // and more so: the interval moves the payout days within a year,
                // and this moves the year itself. A quarter that closed on
                // 1 January closes on 1 Baisakh, so every credit the account has
                // ever had is on a day it no longer pays on — and until they are
                // worked out again the account is showing money the bank never
                // paid on that day. Nothing may be left standing that was
                // computed on the answer the user has just replaced.
                val intervalMoved = current.offersPayoutInterval &&
                    current.payoutMonths != null &&
                    current.payoutMonths != storedPayoutMonths
                val calendarMoved = current.offersPayoutInterval &&
                    current.usesSelectedCalendar != storedInterestInBs
                if (current.isEditing && (intervalMoved || calendarMoved)) {
                    interest.postDueInterest()
                }
                _state.value = current.copy(isSaved = true)
            }
            SaveResult.NameRequired ->
                _state.value = current.copy(nameError = R.string.error_label_name_required)
            else -> Unit
        }
    }

    /**
     * One interval after the money arrived, which is what every bank does —
     * there is no instalment on disbursement day. Only a starting point; the
     * form lets it be changed, and [withDefaultEmiStart] is what keeps the box
     * showing this while nobody has.
     */
    private fun defaultEmiStart(): LocalDate = Recurrence.addMonths(
        _state.value.disbursedOn ?: clock.today(),
        _state.value.monthsPerPayment.toLong(),
        _state.value.loanStepsInBs,
    )

    private suspend fun saveLoan(current: HoldingEditorState) {
        val principal = formatter.parse(current.principalText)
        if (principal == null || !principal.isPositive) {
            _state.value = current.copy(amountError = true)
            return
        }
        val months = termMonths(current)
        val result = loans.saveLoan(
            id = loanId,
            name = current.holdingOwnName ?: current.name,
            kind = current.choice.loanKind ?: return,
            direction = current.choice.loanDirection,
            // The bank, in the column made for it, exactly as an account puts it
            // in `institution` — two term loans at one bank are otherwise two
            // rows both reading "Loan", and only their borrower knows which is
            // the car. Written whether or not the debt was given a name of its
            // own, so `ownName` can tell "no name" from "named after the bank"
            // by comparing the two. Null on money between people, whose one
            // name is the person: a lender repeating it says it twice.
            lender = current.name.trim().takeIf {
                current.offersHoldingName && it.isNotEmpty()
            },
            principal = principal,
            currencyCode = current.currencyCode,
            annualRate = baseRate(current),
            termMonths = months,
            paymentEveryMonths = current.monthsPerPayment,
            interestInBs = current.usesSelectedCalendar,
            creditLimit = principal.takeIf { current.isOverdraft },
            // Money between people goes back in one payment on one day, so it
            // gets no instalment and no first-payment date however long it was
            // agreed for — a length there sets the interest, not a schedule.
            emi = if (current.paysInOneGo) null else formatter.parse(current.emiText),
            style = current.style,
            emiStartsOn = if (current.paysInOneGo) {
                null
            } else {
                months?.let { current.emiStartsOn ?: defaultEmiStart() }
            },
            // On money between people this is the day it changed hands, which
            // the form now asks for instead of a day to clear it by: it is what
            // interest is counted from, and the only date either side remembers.
            disbursedOn = current.disbursedOn,
            // On a card or an overdraft, the day the bank approved it — which
            // with the length beside it is the day the facility runs out.
            openedOn = current.openedOn,
            // No longer asked on money between people, and carried through
            // untouched for the loans that already have one — a debt already
            // showing a lump sum falling due in the forecast goes on showing it.
            dueOn = current.dueOn.takeIf { current.paysInOneGo || months == null },
            payFromAccountId = current.payFromAccountId,
            disbursedAccountId = current.disbursedAccountId,
            startedOn = clock.today(),
            // A card is drawn in a colour of its own, like the accounts it sits
            // among on the money form. Every other debt passes null and keeps
            // the colour of its own figure.
            colorArgb = current.color.toArgb().takeIf { current.isOverdraft },
            showInDisplayCurrency = current.showInDisplayCurrency && current.canConvertForDisplay,
            note = null,
        )
        when (result) {
            is SaveResult.Success -> {
                recordRateChange(current, result.id)
                _state.value = current.copy(isSaved = true)
            }
            SaveResult.NameRequired ->
                _state.value = current.copy(nameError = R.string.error_label_name_required)
            SaveResult.AmountRequired -> _state.value = current.copy(amountError = true)
            else -> Unit
        }
    }

}

/**
 * Characters an amount may contain. Both separators are allowed because a user
 * typing on a Nepali keyboard may produce either, and the formatter works out
 * which is the decimal point.
 */
private fun Char.isAmountChar(): Boolean = isDigit() || this == '.' || this == ','
