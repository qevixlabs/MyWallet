package com.mywallet.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoanScheduleState(
    val name: String = "",
    val kind: LoanKind? = null,
    /** Stated once above the table, since the figures inside it drop their symbol. */
    val currencySymbol: String = "",
    val rows: List<ScheduleRow> = emptyList(),
    /** Every payment handed over so far, so the reader knows where the table starts. */
    val paymentsMade: Int = 0,
    /** Interest over the whole schedule, said in full because the column is bare digits. */
    val totalInterest: String? = null,
    /**
     * How many instalments the first row is collecting on top of its own.
     *
     * Zero for almost every debt. It is worth a line of its own when it is not:
     * a payment that is suddenly double is the app looking wrong unless the page
     * says why.
     */
    val carriedForward: Int = 0,
    /**
     * The days the carried instalments were due on, oldest first — one per
     * missed period, and never the row's own date.
     *
     * They name the date fields in the split: "give me two dates" is a question
     * about nothing, and "the one due 1 Jul, and the one due 1 Aug" is one
     * somebody can answer.
     */
    val carriedDates: List<LocalDate> = emptyList(),
    /** What one instalment is worth, so the split says what each date writes. */
    val instalment: String? = null,
    /** The earliest day a split may be dated — the day the money changed hands. */
    val movedOn: LocalDate? = null,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

/**
 * What a debt still has to pay, as a table.
 *
 * A page rather than a panel inside the editor, for the reasons an account's
 * transactions became one: the editor is a single scrolling `Column`, so nothing
 * in it is lazy and a seven-year loan composed eighty-four rows the moment the
 * toggle was tapped; and opening it pushed the colour picker and Save a screen
 * and a half down a form that is already long. It is built to match the debt's
 * own statement — one back arrow, the name in the bar, the rows underneath —
 * because two pages answering "what has this loan done and what will it do?"
 * must not look like two different kinds of screen.
 */
@HiltViewModel
class LoanScheduleViewModel @Inject constructor(
    private val loans: LoanRepository,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val loanId: String? =
        savedStateHandle.get<String>(Routes.ARG_LOAN_ID)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(LoanScheduleState())
    val state: StateFlow<LoanScheduleState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val schedule = loanId?.let { loans.scheduleFor(it) } ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        // The debt's own currency, exactly as its statement and its editor show
        // it: a loan in dollars is repaid in dollars, and every figure in the
        // column below comes off a dollar balance.
        val money = MoneyFormatter(CurrencyOption.byCode(schedule.currencyCode), grouping = settings.grouping)
        _state.value = LoanScheduleState(
            name = schedule.name,
            kind = schedule.kind,
            currencySymbol = CurrencyOption.byCode(schedule.currencyCode).symbol,
            rows = schedule.rows.map { row ->
                ScheduleRow(
                    number = row.instalment.number,
                    date = row.date,
                    payment = money.formatBare(row.instalment.payment),
                    principal = money.formatBare(row.instalment.principal),
                    interest = money.formatBare(row.instalment.interest),
                    balance = money.formatBare(row.instalment.balance),
                )
            },
            paymentsMade = schedule.paymentsMade,
            totalInterest = money.formatCompact(schedule.totalInterest),
            carriedForward = schedule.arrears.carriedForward,
            carriedDates = schedule.carriedDates,
            instalment = money.formatCompact(schedule.instalment),
            movedOn = schedule.movedOn,
            isLoading = false,
        )
    }

    /**
     * Puts the missed instalments back on the days they were really paid.
     *
     * Reloads rather than patching the state: the payment at the head of the
     * table, every balance under it and the interest the loan costs are all
     * computed from the debt, and all of them have just moved.
     */
    fun split(dates: List<LocalDate>) = viewModelScope.launch {
        val id = loanId ?: return@launch
        loans.splitArrears(id, dates)
        load()
    }
}
