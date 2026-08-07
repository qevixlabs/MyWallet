package com.mywallet.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.InterestRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.AccountMovement
import com.mywallet.domain.INTEREST_POSTING_SUFFIX
import com.mywallet.ui.labelRes
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountStatementState(
    /** What the holding is called, named the way its row on Accounts names it. */
    val name: String = "",
    /** Which of the bank's products, when the name did not already say it. */
    val kindRes: Int? = null,
    val balance: String = "",
    /** What the bank has added to it, said once above the list. */
    val interestTotal: String? = null,
    val rows: List<StatementRow> = emptyList(),
    /** Set when a swipe was refused — see [Reversal.LaterPaymentFirst]. */
    val blockedByLaterPayment: Boolean = false,
    /**
     * How many movements this screen has removed since it opened. A counter and
     * not a flag, for the reason a debt's ledger keeps one: two deletes in a row
     * are two things to report, and a boolean already true the second time would
     * say nothing.
     */
    val deletedCount: Int = 0,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

/**
 * Everything that has touched one account, with what each movement left behind.
 *
 * The balance everywhere else in the app is a total; this is the working. Without
 * it an account reading less than expected has nothing behind it to check — the
 * figure is simply wrong and there is nowhere to look.
 *
 * It is the account's answer to what a debt's ledger answers, and it is built the
 * same way and drawn on the same shape of page. It used to be a collapsible block
 * inside the holding's editor: a list that is not lazy, on a form that is already
 * several screens long, pushing the colour picker and Save below it.
 */
@HiltViewModel
class AccountStatementViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val interest: InterestRepository,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val accountId: String? =
        savedStateHandle.get<String>(Routes.ARG_ACCOUNT_ID)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(AccountStatementState())
    val state: StateFlow<AccountStatementState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val id = accountId ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        val account = wallet.findAccount(id) ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        // The account's own currency throughout, exactly as its editor shows it:
        // every balance in the column below comes off a figure in that currency.
        val money = MoneyFormatter(CurrencyOption.byCode(account.currencyCode), grouping = settings.grouping)
        val postings = interest.postingsFor(id)
        val movements = wallet.statementFor(id)
        _state.value = AccountStatementState(
            // What the user called this one, or failing that the bank — the same
            // reading its row on the Accounts page uses.
            name = account.ownName ?: account.institution ?: account.name,
            // Only when the title has not already said it: a holding named
            // "Savings" under the heading "Savings" says one thing twice.
            kindRes = account.kind.labelRes().takeIf { account.ownName != null },
            balance = money.formatCompact(
                movements.firstOrNull()?.balanceAfter ?: account.openingBalance
            ),
            interestTotal = money.formatCompact(Money(postings.sumOf { it.amount.minor }))
                .takeIf { postings.isNotEmpty() },
            rows = movements.toRows(money, id),
            deletedCount = _state.value.deletedCount,
            isLoading = false,
        )
    }

    /**
     * Removes one movement.
     *
     * Everything the row itself did — the debt it may have moved, the goal whose
     * length it changed — belongs to [WalletRepository.deleteEntry]. What has to
     * happen here is a reload: every balance in the column beside the rows was
     * worked out from the one above it, so one row leaving restates the whole
     * list and the figure at the top of the page.
     */
    fun delete(entryId: String) = viewModelScope.launch {
        when (wallet.deleteEntry(entryId)) {
            EntryDeletion.Done -> {
                lastDeleted = entryId
                load()
                _state.value = _state.value.copy(deletedCount = _state.value.deletedCount + 1)
            }
            EntryDeletion.LaterPaymentFirst ->
                _state.value = _state.value.copy(blockedByLaterPayment = true)
        }
    }

    /** The row the last delete took, for as long as the offer to undo stands. */
    private var lastDeleted: String? = null

    /**
     * Puts the last deleted row back, balance and all.
     *
     * This page had the snackbar and not the offer, which is the wrong way round
     * for the list it is: a statement is read *because* a figure looked wrong,
     * so it is the page a thumb is most likely to swipe something off by
     * accident. [WalletRepository.undoDeleteEntry] is the same door the timeline
     * uses, so whatever the delete put back on a debt or took off a goal is
     * taken back with the row rather than left behind.
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

    /** Every movement, formatted. */
    private fun List<AccountMovement>.toRows(
        money: MoneyFormatter,
        id: String,
    ): List<StatementRow> = map {
        val loan = it.entry.belongsToLoanId
        StatementRow(
            entry = it.entry,
            amount = money.formatCompact(it.amount),
            balanceAfter = money.formatCompact(it.balanceAfter),
            canDelete = !it.fromLoanSchedule,
            opens = when {
                // Interest the app credited to *this* account opens nothing: it
                // is the app's own working rather than anything anyone typed,
                // and it is rewritten from the account's own terms every time a
                // dated figure around it moves — so a form offering to correct
                // it would be offering to type over the next recalculation.
                it.entry.id.startsWith("$id$INTEREST_POSTING_SUFFIX") -> null
                // Everything else opens the movement itself, the payments
                // against a debt included. A statement is a list of what passed
                // through this account, and a row on it is one of them; the debt
                // a payment settles is opened from the debt, and the account
                // from the account. See `openEntry`, which every other list
                // reaches the same verdict through.
                else -> StatementTarget.Entry(it.entry.id)
            },
        )
    }
}
