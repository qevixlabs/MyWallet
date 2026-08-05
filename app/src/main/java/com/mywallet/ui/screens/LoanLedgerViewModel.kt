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
    /** What was left owing afterwards, or null when the app cannot say. */
    val balanceAfter: String?,
    /** What an instalment was made of, when the schedule knows. */
    val splitPrincipal: String? = null,
    val splitInterest: String? = null,
    /**
     * The holding the money passed through, so the row can open it — the bank it
     * was paid from, or the cash tin it was handed out of. Null on a payment
     * that names none, which leads nowhere.
     */
    val accountId: String? = null,
    val accountName: String? = null,
    /** Only when it says something the loan's own name does not. */
    val note: String? = null,
    val increases: Boolean = false,
    /**
     * Whether this row can be swiped away.
     *
     * Every row can, an instalment included. It was withheld on the reasoning
     * that an instalment is the loan's own rule speaking and the way to change
     * what the schedule says is to change the schedule — but the timeline has
     * always let one go, and a payment that never came out of the account is a
     * fact about the world rather than an opinion about the schedule. Refusing
     * it here made the same row deletable on one screen and not on the other,
     * and the screen that refused was the one showing what removing it would do
     * to the debt.
     *
     * What the schedule does about it is [Arrears]: the period charges its
     * interest, clears no principal, and the next payment collects both.
     */
    val canDelete: Boolean = true,
)

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
 * A row cannot be *edited* here: a payment's amount is corrected where it was
 * recorded, and a row that quietly reopened an entry would offer to change a
 * figure whose effect on the balance lives in the loan rather than in the entry.
 * It can be **removed**, which is a different act and the one this page was
 * missing — a mistyped lump sum could be swiped out of the timeline, but the
 * balance it had taken off the debt stayed off, and this is the only screen where
 * both facts are visible at once.
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
        // A row is printed in the currency it was actually paid in, which is
        // normally the loan's — but an instalment the user corrected by hand
        // need not be, and stamping it with the loan's symbol would misname it.
        val paidIn = if (currencyCode.equals(loan.currencyCode, ignoreCase = true)) {
            own
        } else {
            MoneyFormatter(CurrencyOption.byCode(currencyCode), grouping = settings.grouping)
        }
        return LedgerRow(
            id = entryId,
            date = date,
            kind = kind,
            amount = paidIn.formatCompact(amount),
            converted = base.formatCompact(baseAmount)
                .takeIf { !currencyCode.equals(baseCode, ignoreCase = true) },
            balanceAfter = balanceAfter?.let { own.formatCompact(it) },
            splitPrincipal = principalPart?.let { own.formatCompact(it) },
            splitInterest = interestPart?.let { own.formatCompact(it) },
            accountId = accountId,
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
