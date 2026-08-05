package com.mywallet.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.AccountWithBalance
import com.mywallet.domain.Loan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AdjustState(
    val accountId: String,
    val accountName: String,
    val currencyCode: String,
    val targetText: String,
)

/**
 * Everything one bank holds, under the bank's own name.
 *
 * A savings account, a fixed deposit, a term loan and an overdraft at the same
 * bank are four rows that used to repeat that bank's name four times and then
 * scatter themselves across three sections. They are one relationship, so the
 * name is said once as a heading and each row underneath says only what it *is*.
 *
 * The heading carries no figure of its own. A bank's holdings run in both
 * directions — a deposit is money, a loan is a debt — and a single number over
 * them could only be a subtraction the user never asked for.
 *
 * [title] is null for the one group that is not a bank: cash, a wallet, and
 * anything else that belongs to nobody. It sorts last, so the list never opens
 * with a nameless section.
 *
 * [titleRes] is the exception to that: a section the app names rather than the
 * user — *Insurance* and *Goals*. Neither is a relationship with anyone, so
 * neither has a name to head it with: a policy's name is the policy's, a goal's
 * is the goal's, and a heading made of either would promise other holdings
 * somewhere that does not exist. Kept as a resource rather than a string because
 * this is a view model, and the words belong to whichever language the screen is
 * being read in.
 */
data class AccountGroup(
    val title: String?,
    val accounts: List<AccountWithBalance>,
    /** This bank's debts — a term loan, an overdraft. Never a person's. */
    val loans: List<Loan>,
    val titleRes: Int? = null,
)

/**
 * What is held in one currency other than the one totals are read in.
 *
 * The headline figure above is a valuation — what the money would come to today
 * if it were converted. These are the amounts themselves, which is what the user
 * actually has, so the card says both rather than only the arithmetic.
 */
data class ForeignHolding(val currencyCode: String, val amount: Money)

data class AccountsUiState(
    val accounts: List<AccountWithBalance> = emptyList(),
    val groups: List<AccountGroup> = emptyList(),
    /**
     * Debts owed to a person, which have no bank to sit under. A bank's own
     * debts are inside its [AccountGroup] instead — the relationship is the
     * thing being listed, not the direction the money runs.
     */
    val personalLoans: List<Loan> = emptyList(),
    /** Money other people owe the user. */
    val lentOut: List<Loan> = emptyList(),
    val owed: Money = Money.ZERO,
    val lent: Money = Money.ZERO,
    val total: Money = Money.ZERO,
    /** Money held in something other than the display currency, per currency. */
    val foreign: List<ForeignHolding> = emptyList(),
    /** True when at least one account could not be converted into the total. */
    val hasUnconvertible: Boolean = false,
    val adjusting: AdjustState? = null,
    /**
     * What day it is, for the one bar that is drawn over time rather than over
     * money — a deposit's term. Injected rather than asked of the wall clock at
     * the row, so the page can be frozen in a test the way everything else that
     * depends on today already can be.
     */
    // Epoch day zero rather than `LocalDate.EPOCH`, which is API 34. It is only
    // ever the value between the view model being constructed and its first
    // emission, where no row is drawn to read it.
    val today: LocalDate = LocalDate.ofEpochDay(0),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val loanRepository: LoanRepository,
    private val settingsStore: SettingsStore,
    private val exchangeRates: ExchangeRateRepository,
    private val clock: Clock,
) : ViewModel() {

    private val adjusting = MutableStateFlow<AdjustState?>(null)

    val state: StateFlow<AccountsUiState> =
        combine(
            repository.observeAccountBalances(),
            loanRepository.observeLoansWithBalance(),
            adjusting,
            settingsStore.settings,
        ) { accounts, loans, adjustState, appSettings ->
            // Debts and loans given are never mixed: one is subtracted from what
            // the user is worth and the other added to it.
            val open = loans.filterNot { it.isClosed }
            val (given, borrowed) = open.partition { it.isLent }
            // A bank's debts are part of what that bank holds; a person's are
            // not, and stay in a section of their own further down.
            val (bankDebts, personalDebts) =
                borrowed.partition { it.kind != LoanKind.PERSONAL }

            // Which bank each row belongs to, matched case-insensitively so
            // "Nabil Bank" and "Nabil bank" are one relationship rather than
            // two headings — and displayed in whichever spelling was seen
            // first, since one of them is what the user typed.
            val titles = linkedMapOf<String, String>()
            fun keyOf(raw: String?): String? {
                val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val key = name.lowercase()
                titles.putIfAbsent(key, name)
                return key
            }
            val accountKeys = accounts.map { row ->
                when (row.account.kind) {
                    // Cash and a wallet belong to nobody. Their own name is not
                    // a bank's, and heading a section with it would invite the
                    // reader to look for the rest of that bank's holdings. A
                    // policy is the same: its name is the policy's, and a
                    // heading made of it would promise other holdings at an
                    // insurer the user never named. A goal has its own section
                    // below and is out of this grouping entirely.
                    AccountKind.WALLET, AccountKind.CASH,
                    AccountKind.INSURANCE, AccountKind.GOAL -> null
                    else -> keyOf(row.account.institution ?: row.account.name)
                }
            }
            val debtKeys = bankDebts.map { keyOf(it.lender ?: it.name) }

            val banks = (accountKeys + debtKeys)
                .filterNotNull()
                .distinct()
                .sortedBy { titles.getValue(it).lowercase() }
                .map { key ->
                    AccountGroup(
                        title = titles.getValue(key),
                        // What the bank holds for the user first, then what it
                        // has lent them: the order money actually reads in.
                        accounts = accounts
                            .filterIndexed { index, _ -> accountKeys[index] == key }
                            .sortedBy { it.account.kind.ordinal },
                        loans = bankDebts
                            .filterIndexed { index, _ -> debtKeys[index] == key }
                            .sortedBy { it.kind.ordinal },
                    )
                }
            // Policies and goals are each their own section, under a heading
            // the app writes. They are arrangements rather than places, and one
            // sitting beside a cash tin reads as somewhere money happens to be
            // rather than something the user set up on purpose.
            val policies = accounts.filter { it.account.isInsurance }
            val goals = accounts.filter { it.account.isGoal }
            // Wallets get one too. eSewa, Khalti and Wise are institutions the
            // user holds money at as much as a bank is — they simply have one
            // holding each, so they had no name to head a section with and fell
            // into "Other" alongside the cash tin. Under their own heading the
            // page reads as a list of *places*, and "Other" is left meaning what
            // it says: the money that is nowhere in particular.
            val wallets = accounts.filter { it.account.kind == AccountKind.WALLET }
            // Everything else with no bank behind it, so the list never opens
            // with a nameless section.
            val loose = accounts.filterIndexed { index, row ->
                accountKeys[index] == null && !row.account.isGoal &&
                    !row.account.isInsurance && row.account.kind != AccountKind.WALLET
            }
            val orphanDebts = bankDebts.filterIndexed { index, _ -> debtKeys[index] == null }
            val groups = banks + listOfNotNull(
                AccountGroup(
                    title = null,
                    titleRes = R.string.accounts_wallets,
                    accounts = wallets,
                    loans = emptyList(),
                ).takeIf { wallets.isNotEmpty() },
                AccountGroup(title = null, accounts = loose, loans = orphanDebts)
                    .takeIf { loose.isNotEmpty() || orphanDebts.isNotEmpty() },
                AccountGroup(
                    title = null,
                    // The same word the kind itself uses: "Insurance" is a
                    // heading and a label in one, and two strings holding it
                    // would be two chances for the list to disagree with the
                    // rows in it.
                    titleRes = R.string.accounts_kind_insurance,
                    accounts = policies,
                    loans = emptyList(),
                ).takeIf { policies.isNotEmpty() },
                AccountGroup(
                    title = null,
                    titleRes = R.string.accounts_goals,
                    accounts = goals,
                    loans = emptyList(),
                ).takeIf { goals.isNotEmpty() },
            )

            // Everything held in something other than the display currency,
            // summed per currency. Only amounts in the same currency are added
            // to each other — a figure made of dollars and euros is in no
            // currency at all.
            val foreign = accounts
                .filterNot {
                    it.account.currencyCode.equals(appSettings.currencyCode, ignoreCase = true)
                }
                .groupBy { it.account.currencyCode.uppercase() }
                .map { (code, rows) ->
                    ForeignHolding(code, Money(rows.sumOf { it.balance.minor }))
                }
                .filterNot { it.amount.isZero }
                .sortedBy { it.currencyCode }

            AccountsUiState(
                accounts = accounts,
                groups = groups,
                personalLoans = personalDebts,
                lentOut = given,
                // Converted before summing, like every other total: adding a
                // dollar debt's own figure to a rupee one would produce a
                // number in no currency. A debt with no rate falls back to its
                // own figure rather than vanishing from what is owed.
                // And what it would take to be *done* with each, which is the
                // balance plus the interest that has run on it: a debt left
                // sitting costs more than its balance to clear, and a total that
                // leaves that out is the one figure the user cannot act on.
                // Identical to the balance on anything with a schedule.
                owed = Money(borrowed.sumOf { (it.settleTodayInBase ?: it.settleToday).minor }),
                lent = Money(given.sumOf { (it.settleTodayInBase ?: it.settleToday).minor }),
                // Only convertible balances are summed, and the shortfall is
                // flagged. Silently dropping an account would show a total that
                // looks complete and is not.
                total = Money(accounts.sumOf { it.balanceInBase?.minor ?: 0L }),
                foreign = foreign,
                hasUnconvertible = accounts.any { it.balanceInBase == null },
                adjusting = adjustState,
                today = clock.today(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    init {
        viewModelScope.launch {
            val base = settingsStore.settings.first().currencyCode
            exchangeRates.warmCache(base)
            exchangeRates.refresh(base)
        }
    }

    fun startAdjust(row: AccountWithBalance) {
        val formatter = MoneyFormatter(CurrencyOption.byCode(row.account.currencyCode), grouping = settingsStore.grouping)
        adjusting.value = AdjustState(
            accountId = row.account.id,
            accountName = row.account.name,
            currencyCode = row.account.currencyCode,
            // Pre-filled with the current figure, so correcting it is an edit
            // rather than recalling the number from scratch.
            targetText = formatter.toPlainInput(row.balance),
        )
    }

    fun dismissAdjust() { adjusting.value = null }

    fun setAdjustTarget(text: String) {
        adjusting.value = adjusting.value?.copy(
            targetText = text.filter { it.isDigit() || it == '.' || it == ',' || it == '-' },
        )
    }

    /**
     * Removes an account and everything that only existed because of it — see
     * [WalletRepository.deleteAccount] for what "everything" covers.
     */
    fun deleteAccount(id: String) = viewModelScope.launch {
        repository.deleteAccount(id)
    }

    /**
     * The same for a debt, which already knew how: deleting a loan takes the
     * payments its schedule wrote with it, or they go on holding an account's
     * balance down for a debt that can no longer be opened.
     */
    fun deleteLoan(id: String) = viewModelScope.launch {
        loanRepository.deleteLoan(id)
    }

    fun confirmAdjust() = viewModelScope.launch {
        val current = adjusting.value ?: return@launch
        val target = MoneyFormatter(CurrencyOption.byCode(current.currencyCode), grouping = settingsStore.grouping)
            .parse(current.targetText) ?: return@launch
        repository.adjustAccountBalance(current.accountId, target, note = null)
        adjusting.value = null
    }
}
