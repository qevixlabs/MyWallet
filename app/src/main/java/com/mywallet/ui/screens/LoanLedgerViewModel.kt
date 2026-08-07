package com.mywallet.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Loan
import com.mywallet.domain.LoanMovement
import com.mywallet.domain.LoanMovementKind
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * One line of the statement, ready to draw.
 *
 * Every figure is already a string, like [ScheduleRow]: the screen prints what
 * happened and does no arithmetic on money.
 */
data class LedgerRow(
    val id: String,
    val date: LocalDate,
    val kind: LoanMovementKind,
    /** In the currency the payment was actually made in. */
    val amount: String,
    /** What that came to in the display currency, when the two differ. */
    val converted: String?,
    /**
     * The working behind the row, drawn on its other face — see [hasWorking].
     *
     * Four figures that add up in front of the reader: what was owed when the
     * payment arrived, how much of it the lender kept as interest, how much
     * actually came off the debt, and what that left. They used to be scattered
     * across the front of the row — two of them crushed into a sentence under
     * the date and the third stranded in the amount column — where they read as
     * three unrelated remarks rather than as one sum. Any of them may be null on
     * its own: the app states the parts of the arithmetic it can stand behind
     * and leaves out the ones it cannot.
     */
    val balanceBefore: String? = null,
    val workingInterest: String? = null,
    val workingPrincipal: String? = null,
    /** What was left owing afterwards, or null when the app cannot say. */
    val balanceAfter: String?,
    /**
     * The day a lump sum re-set this loan, on the rows that fall before it.
     *
     * Those rows were paid against a schedule that is not on file any more: a
     * lump sum rewrites the balance, the term, the carried interest and where
     * the rule starts, all in place, so the debt they were instalments *of* no
     * longer exists to be read. The ledger stops its walk at the current basis
     * for exactly that reason, and the rows above the lump sum come back with
     * nothing on them.
     *
     * **Which left half a statement quietly refusing to turn over.** A tap did
     * nothing on them and the page gave no reason, so a reader who had just
     * turned one row over and watched the next ignore them had every reason to
     * think the app had broken. The rows still turn; what they show is the fact,
     * stated. Nothing is invented to fill the gap — the split of a payment made
     * against a schedule nobody kept cannot be worked out from what is left, and
     * a plausible figure in the one column a user checks against their lender is
     * worse than an honest sentence.
     *
     * A date rather than a finished string, unlike every money figure here: which
     * calendar it is read in belongs to the screen, exactly as [date] does.
     */
    val replacedOn: LocalDate? = null,
    /**
     * The holding the money passed through, named on the row — the bank it was
     * paid from, or the cash tin it was handed out of. Null on a payment that
     * names none: one the app generated for a day before it was told about the
     * debt, or a lump sum handed over in cash with no account set up for it.
     *
     * Named only. A tap opens the *payment*, which is what a reader checking a
     * line has come for and which names this account itself; the id the row used
     * to carry so it could open the holding is gone with the behaviour.
     */
    val accountName: String? = null,
    /** Only when it says something the loan's own name does not. */
    val note: String? = null,
    val increases: Boolean = false,
) {
    /**
     * The debt itself arriving: the money that made the loan, which is what the
     * whole account is about rather than something that happened to it.
     *
     * **Nothing may be done to this row.** It does not delete, it does not open,
     * and on a debt whose rows turn over it does not turn. Every other line on
     * the page is an event the loan collected and can be taken back off it; this
     * one *is* the loan, and the three things a row otherwise offers all come out
     * wrong on it. Deleting it would leave a debt with a balance, a schedule and
     * a year of instalments behind it and no money ever having been borrowed —
     * a hole rather than a correction. Editing it would let the figure the whole
     * arrangement is derived from be retyped on a page that cannot rebuild the
     * arrangement from it; the loan's own editor is where that number lives, and
     * it is the first box on it. And turning it over would show a sum with
     * nothing in it: no interest was charged and no principal came off, because
     * before this row there was no debt to charge interest on.
     *
     * The way to undo a loan that should never have been written down is to
     * delete the loan, which the accounts list offers and which says outright
     * that it takes every payment with it.
     */
    val isOpening: Boolean get() = kind == LoanMovementKind.OPENING

    /**
     * Whether this row can be swiped away.
     *
     * Every payment can, an instalment included. It was withheld on instalments
     * for a while on the reasoning that an instalment is the loan's own rule
     * speaking and the way to change what the schedule says is to change the
     * schedule — but the timeline has always let one go, and a payment that
     * never came out of the account is a fact about the world rather than an
     * opinion about the schedule. What the schedule does about it is [Arrears]:
     * the period charges its interest, clears no principal, and the next payment
     * collects both.
     */
    val canDelete: Boolean get() = !isOpening

    /**
     * Whether there is a second face worth turning the row over for.
     *
     * A row the app can say nothing about behind — a payment in another currency,
     * whose effect on a debt held in this one cannot be stated without inventing
     * a rate — does not flip at all. A card that turns to a blank back is worse
     * than one that does not turn.
     */
    val hasWorking: Boolean
        get() = balanceBefore != null ||
            workingInterest != null ||
            workingPrincipal != null ||
            balanceAfter != null ||
            // A row with no figures still turns where there is a reason it has
            // none — see [replacedOn]. Saying why is a second face worth having.
            replacedOn != null
}

data class LoanLedgerState(
    val name: String = "",
    val kind: LoanKind? = null,
    val isLent: Boolean = false,
    val isOverdraft: Boolean = false,
    val outstanding: String? = null,
    val outstandingConverted: String? = null,
    /** Everything that has gone towards it, interest included. */
    val totalPaid: String? = null,
    /** Everything that has been added to it since it started. */
    val totalAdded: String? = null,
    /**
     * Interest built up and not yet settled, on a debt that meters it day by day
     * rather than carrying it inside an instalment.
     *
     * It belongs at the top of the statement rather than among the rows: nothing
     * happened on any particular day to cause it, and a dated line for it would
     * be the app inventing a payment nobody made.
     */
    val accruedInterest: String? = null,
    /** The balance and that interest together — what it takes to be done today. */
    val settleToday: String? = null,
    val rows: List<LedgerRow> = emptyList(),
    /** Set when a swipe was refused — see [Reversal.LaterPaymentFirst]. */
    val blockedByLaterPayment: Boolean = false,
    /**
     * How many movements this screen has removed since it opened.
     *
     * A counter rather than a flag, because two deletes in a row are two things
     * to report and a boolean already true the second time would say nothing.
     */
    val deletedCount: Int = 0,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

/**
 * Everything that has actually happened to one loan.
 *
 * The editor answers "what are the terms and what is left?"; this answers "what
 * have we each done about it?" — which is the question money between people
 * really turns on.
 *
 * A row cannot be *edited* here, and that is the design rather than an omission.
 * Every line on this page is the app's own record of what a debt did — an
 * instalment its rule wrote, the interest its rate charged, the balance its
 * schedule arrived at — and each figure is read back out of the loan rather than
 * stored on the row. Opening one in the entry form offered to retype an
 * instalment that the schedule would go on quoting its own figure for, which is
 * not a correction but a second opinion the page has no way to honour. A payment
 * whose *amount* was mistyped is corrected where it was recorded, on the card
 * that recorded it.
 *
 * What a row does instead is **turn over** and show its working, and it can be
 * **removed** — a different act and the one this page was missing, since a
 * mistyped lump sum could always be swiped out of the timeline while the balance
 * it had taken off the debt stayed off, and this is the only screen where both
 * facts are visible at once.
 */
@HiltViewModel
class LoanLedgerViewModel @Inject constructor(
    private val loans: LoanRepository,
    private val wallet: WalletRepository,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val loanId: String? =
        savedStateHandle.get<String>(Routes.ARG_LOAN_ID)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(LoanLedgerState())
    val state: StateFlow<LoanLedgerState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val id = loanId ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        val history = loans.history(id) ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        val loan = history.loan
        val baseCode = settings.settings.first().currencyCode
        // The loan's own currency throughout, exactly as the editor shows it: a
        // debt in dollars was paid in dollars, and every balance in the column
        // below comes off a dollar figure.
        val own = MoneyFormatter(CurrencyOption.byCode(loan.currencyCode), grouping = settings.grouping)
        val base = MoneyFormatter(CurrencyOption.byCode(baseCode), grouping = settings.grouping)

        _state.value = LoanLedgerState(
            name = loan.name,
            kind = loan.kind,
            isLent = loan.isLent,
            isOverdraft = loan.isOverdraft,
            outstanding = own.formatCompact(loan.outstanding),
            outstandingConverted = loan.outstandingInBase?.let { base.formatCompact(it) },
            totalPaid = loan
                .total(history.movements) { !it.increases }
                ?.let { own.formatCompact(it) },
            totalAdded = loan
                .total(history.movements) { it.kind == LoanMovementKind.INCREASE }
                ?.let { own.formatCompact(it) },
            accruedInterest = loan.accruedInterest
                ?.takeIf { it.isPositive }?.let { own.formatCompact(it) },
            settleToday = loan.accruedInterest
                ?.takeIf { it.isPositive }
                ?.let { own.formatCompact(loan.outstanding + it) },
            rows = history.movements.map { it.toRow(loan, own, base, baseCode) },
            isLoading = false,
        )
    }

    /**
     * What the rows matching [predicate] come to, or null when none do.
     *
     * Only rows in the loan's own currency are counted. Adding a dollar payment
     * to a rupee one produces a number in no currency at all, and a total that
     * quietly did it would be wrong in exactly the case the user is least able
     * to spot.
     */
    private fun Loan.total(
        movements: List<LoanMovement>,
        predicate: (LoanMovement) -> Boolean,
    ): Money? = movements
        .filter(predicate)
        .filter { it.currencyCode.equals(currencyCode, ignoreCase = true) }
        .sumOf { it.amount.minor }
        .takeIf { it > 0L }
        ?.let { Money(it) }

    private fun LoanMovement.toRow(
        loan: Loan,
        own: MoneyFormatter,
        base: MoneyFormatter,
        baseCode: String,
    ): LedgerRow {
        val sameCurrency = currencyCode.equals(loan.currencyCode, ignoreCase = true)
        // A row is printed in the currency it was actually paid in, which is
        // normally the loan's — but an instalment the user corrected by hand
        // need not be, and stamping it with the loan's symbol would misname it.
        val paidIn = if (sameCurrency) {
            own
        } else {
            MoneyFormatter(CurrencyOption.byCode(currencyCode), grouping = settings.grouping)
        }
        // What the payment was made of, for the row's other face. The schedule
        // fills this for an instalment and for nothing else, because it is the
        // only thing that can: the payment figure alone cannot say how much of
        // it the lender kept. But two kinds need no schedule to be sure of —
        // a lump sum is all principal and a serviced charge is all interest,
        // which is what those movements *are* rather than a guess about them —
        // and leaving their face bare would hide the plainest arithmetic on the
        // page behind the hardest.
        //
        // Only where the payment was made in the debt's own currency, though.
        // Every other figure on that face is a balance in the loan's money, and
        // a dollar payment set among them under the loan's own symbol would be
        // a wrong number in the column a user checks hardest.
        val interestOf = interestPart
            ?: amount.takeIf { sameCurrency && kind == LoanMovementKind.INTEREST }
        val principalOf = principalPart
            ?: amount.takeIf { sameCurrency && kind == LoanMovementKind.PRINCIPAL }
        return LedgerRow(
            id = entryId,
            date = date,
            kind = kind,
            amount = paidIn.formatCompact(amount),
            converted = base.formatCompact(baseAmount)
                .takeIf { !currencyCode.equals(baseCode, ignoreCase = true) },
            balanceBefore = balanceBefore?.let { own.formatCompact(it) },
            workingInterest = interestOf?.let { own.formatCompact(it) },
            workingPrincipal = principalOf?.let { own.formatCompact(it) },
            balanceAfter = balanceAfter?.let { own.formatCompact(it) },
            // Paid against a schedule a later lump sum replaced — see
            // [LedgerRow.replacedOn]. Told by the day the loan's own figures
            // count from, which is the day the last re-basing moved it to: a
            // debt that has never been re-based counts from the day it was
            // borrowed, and nothing is in front of that.
            replacedOn = loan.startedOn.takeIf {
                loan.amortises && date < it && balanceAfter == null
            },
            accountName = accountName,
            // An instalment's note defaults to the loan's own name, which the
            // heading above already carries. Only a note the user wrote
            // themselves says anything a second line is worth spending on.
            note = note?.takeIf { it != loan.name },
            increases = increases,
        )
    }

    /**
     * Removes one movement and puts the debt back where it was.
     *
     * Everything about the reversal — the balance, the term, where the rule stops
     * — belongs to the repository; this reloads afterwards, because the figure at
     * the top of the screen and the balance column beside every row are all
     * computed from the loan and have just changed.
     */
    fun delete(entryId: String) = viewModelScope.launch {
        when (wallet.deleteEntry(entryId)) {
            EntryDeletion.Done -> {
                lastDeleted = entryId
                load()
                // Counted rather than flagged: two deletes in a row are two
                // things to report, and a boolean that was already true the
                // second time would say nothing.
                _state.value = _state.value.copy(deletedCount = _state.value.deletedCount + 1)
            }
            EntryDeletion.LaterPaymentFirst ->
                _state.value = _state.value.copy(blockedByLaterPayment = true)
        }
    }

    /** The row the last delete took, for as long as the offer to undo stands. */
    private var lastDeleted: String? = null

    /**
     * Puts the last deleted payment back, and the debt with it.
     *
     * A payment removed from here is the one delete in the app that moves a
     * *balance* as well as a list — a lump sum put back on, a drawdown taken off
     * — so a mis-swipe here costs more than a row. [WalletRepository.deleteEntry]
     * already knows how to reverse itself, which is what makes the offer honest:
     * undoing is the same arithmetic backwards rather than a second guess at it.
     */
    fun undoDelete() = viewModelScope.launch {
        lastDeleted?.let {
            wallet.undoDeleteEntry(it)
            lastDeleted = null
            load()
        }
    }

    fun dismissBlocked() {
        _state.value = _state.value.copy(blockedByLaterPayment = false)
    }
}
