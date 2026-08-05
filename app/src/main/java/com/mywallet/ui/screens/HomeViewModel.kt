package com.mywallet.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.core.date.DateDisplay
import com.mywallet.core.date.DateWindow
import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.HoldingBreakdown
import com.mywallet.domain.MoneyEntry
import com.mywallet.domain.PeriodSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val monthLabel: String = "",
    /** Gregorian span the month covers, shown small. Null in Gregorian mode. */
    val monthSecondary: String? = null,
    val isCurrentMonth: Boolean = true,
    val canGoForward: Boolean = false,
    val summary: PeriodSummary = PeriodSummary(),
    val breakdown: List<HoldingBreakdown> = emptyList(),
    val recent: List<MoneyEntry> = emptyList(),
    /** Spend per day of the month, in minor units, index 0 = first day. */
    val dailyOut: LongArray = LongArray(0),
    /** What came in per day, indexed the same way. */
    val dailyIn: LongArray = LongArray(0),
    val todayIndex: Int = -1,
    val monthStartLabel: String = "",
    val monthEndLabel: String = "",
    val daysWithSpending: Int = 0,
    val elapsedDays: Int = 0,
    val averagePerDay: Money = Money.ZERO,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = summary.isEmpty && recent.isEmpty()

    // LongArray has identity equals; data class equality must not silently
    // become reference equality or Compose will skip real updates.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HomeUiState) return false
        return monthLabel == other.monthLabel &&
            monthSecondary == other.monthSecondary &&
            isCurrentMonth == other.isCurrentMonth &&
            canGoForward == other.canGoForward &&
            summary == other.summary &&
            breakdown == other.breakdown &&
            recent == other.recent &&
            dailyOut.contentEquals(other.dailyOut) &&
            dailyIn.contentEquals(other.dailyIn) &&
            todayIndex == other.todayIndex &&
            monthStartLabel == other.monthStartLabel &&
            monthEndLabel == other.monthEndLabel &&
            daysWithSpending == other.daysWithSpending &&
            elapsedDays == other.elapsedDays &&
            averagePerDay == other.averagePerDay &&
            isLoading == other.isLoading
    }

    override fun hashCode(): Int {
        var result = monthLabel.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + breakdown.hashCode()
        result = 31 * result + recent.hashCode()
        result = 31 * result + dailyOut.contentHashCode()
        result = 31 * result + dailyIn.contentHashCode()
        result = 31 * result + todayIndex
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val settingsStore: SettingsStore,
    private val months: MonthSelection,
    private val clock: Clock,
) : ViewModel() {

    /**
     * How many months back from today the user has stepped. 0 = this month.
     *
     * Shared with the Timeline — see [MonthSelection] — but never forward: the
     * Timeline may be looking at next March, and Home has nothing to show for a
     * month that has not happened. Coming from one, it opens on this month
     * instead of on a page of blanks.
     */
    private val monthOffset = months.offset.map { it.coerceAtMost(0) }

    /** The month Home is actually showing, which is what its arrows step from. */
    private val shownOffset: Int get() = months.offset.value.coerceAtMost(0)

    val state: StateFlow<HomeUiState> =
        combine(settingsStore.settings, monthOffset) { settings, offset ->
            val display = DateDisplay(settings.calendarSystem)
            display to windowFor(display, offset)
        }.flatMapLatest { (display, window) ->
            // Nothing past today, in any of the three.
            //
            // Home is a record of what happened, and an entry the user dated
            // forward is not that: a salary banked for the 3rd counted itself
            // into this month's income, drew a bar on a day that has not
            // arrived, and sat at the top of "recent" as though it already had.
            // Every other figure on the page stops at today — the balances do,
            // the average does — so this one has to as well, or the page argues
            // with itself. What is still to come is on the Timeline, where each
            // row says it is coming.
            //
            // All three or none: the totals, the curve and the list are the same
            // month said three ways, and cutting one of them would have them
            // disagree about a payment the user can see in one of them.
            val end = minOf(window.endExclusive, clock.today().plusDays(1))
            combine(
                repository.observeSummary(window.start, end),
                repository.observeBreakdown(Direction.OUT, window.start, end),
                repository.observeEntries(window.start, end),
            ) { summary, breakdown, entries ->
                buildState(display, window, summary, breakdown, entries)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    fun showPreviousMonth() {
        months.show(shownOffset - 1)
    }

    /**
     * Never past this month.
     *
     * Home is a record of what happened: what came in, what went out, where it
     * went, and the last few movements. A month that has not arrived has none of
     * that, and filling it with what is *scheduled* made a page of forecasts
     * look like a page of facts. What is coming is the Timeline's job, and it
     * says so on every row.
     */
    fun showNextMonth() {
        if (shownOffset < 0) months.show(shownOffset + 1)
    }

    fun showCurrentMonth() {
        months.show(0)
    }

    /**
     * Steps back one month at a time rather than doing date arithmetic, because
     * in Bikram Sambat "one month earlier" is not a fixed number of days.
     */
    private fun windowFor(display: DateDisplay, offset: Int): DateWindow {
        var window = display.monthWindow(clock.today())
        repeat(kotlin.math.abs(offset)) {
            window = if (offset < 0) display.previousMonth(window) else display.nextMonth(window)
        }
        return window
    }

    private fun buildState(
        display: DateDisplay,
        window: DateWindow,
        summary: PeriodSummary,
        breakdown: List<HoldingBreakdown>,
        entries: List<MoneyEntry>,
    ): HomeUiState {
        val today = clock.today()
        val isCurrent = today in window

        // The curve must agree with the totals above it: only confirmed
        // spending counts — not transfer legs, not corrections, not rows still
        // waiting to be confirmed — and in the display currency, because a line
        // drawn through mixed currencies compares nothing.
        val dailyOut = LongArray(window.dayCount)
        // And what came in, on the same terms. The card compares the two in
        // words — "you spent X more than came in" — so the picture beside that
        // sentence draws both; see [MonthCurve].
        val dailyIn = LongArray(window.dayCount)
        for (entry in entries) {
            if (!entry.counts) continue
            val index = (entry.occurredOn.toEpochDay() - window.start.toEpochDay()).toInt()
            when (entry.direction) {
                Direction.OUT -> if (index in dailyOut.indices) {
                    dailyOut[index] += entry.baseAmount.minor
                }
                Direction.IN -> if (index in dailyIn.indices) {
                    dailyIn[index] += entry.baseAmount.minor
                }
            }
        }

        val elapsed = if (isCurrent) window.elapsedDaysAsOf(today) else window.dayCount
        val daysWithSpending = dailyOut.count { it > 0L }
        // Average over days elapsed, not days in the month — on the 3rd of the
        // month, dividing by 30 would tell the user a comforting lie.
        val average = if (elapsed > 0) Money(summary.moneyOut.minor / elapsed) else Money.ZERO

        return HomeUiState(
            monthLabel = window.label,
            monthSecondary = display.secondaryRange(window),
            isCurrentMonth = isCurrent,
            // Only back to a month that happened, and forward only as far as
            // this one.
            canGoForward = shownOffset < 0,
            summary = summary,
            breakdown = breakdown.take(MAX_BREAKDOWN_ROWS),
            // A transfer is one movement, shown once — see
            // [MoneyEntry.isTransferArrival] — and a balance correction is not
            // a movement at all, so neither is drawn.
            recent = entries
                .filterNot { it.isTransferArrival || it.isBalanceCorrection }
                .take(MAX_RECENT_ROWS),
            dailyOut = dailyOut,
            dailyIn = dailyIn,
            todayIndex = if (isCurrent) elapsed - 1 else -1,
            monthStartLabel = display.dayAndMonth(window.start),
            monthEndLabel = display.dayAndMonth(window.lastDay),
            daysWithSpending = daysWithSpending,
            elapsedDays = elapsed,
            averagePerDay = average,
            isLoading = false,
        )
    }

    private companion object {
        const val MAX_BREAKDOWN_ROWS = 6

        /**
         * Enough of the month to be worth scrolling, newest first.
         *
         * Five was a glance and not a list: on any month with more than a
         * handful of movements it showed the last day or two and sent the reader
         * to another tab for the rest, which is a long way to go to see what
         * they spent on Tuesday. A dozen is a screenful and a bit — far enough
         * back to answer "what have I been paying for lately" on the page that
         * asks it, and still short enough that "See all" means something.
         */
        const val MAX_RECENT_ROWS = 12
    }
}