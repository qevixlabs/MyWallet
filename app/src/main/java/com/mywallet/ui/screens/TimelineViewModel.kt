package com.mywallet.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.core.date.DateDisplay
import com.mywallet.core.date.DateWindow
import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.MaturityRepository
import com.mywallet.domain.AccountWithBalance
import com.mywallet.data.repo.InterestRepository
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.repo.groupByDay
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.AccountProjection
import com.mywallet.domain.DayGroup
import com.mywallet.domain.Loan
import com.mywallet.domain.MoneyEntry
import com.mywallet.domain.ProjectedDay
import com.mywallet.domain.ProjectedEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Which slice of the story the user is looking at. */
enum class TimelineFilter { ALL, IN, OUT }

/**
 * A debt as this month leaves it.
 *
 * [after] is what will be owed once the payments scheduled between today and the
 * end of the month being viewed have run — the same horizon the account
 * projections use, so the two figures describe the same moment.
 */
data class LoanOutlook(val loan: Loan, val after: Money) {
    /** True when the month's schedule actually moves it. */
    val changes: Boolean get() = after != loan.outstanding
}

/**
 * Whether this debt came into existence inside [window].
 *
 * The day the money changed hands is the answer wherever it was recorded, and a
 * facility's approval day is the answer for a card, which is handed no money at
 * all. `startedOn` is the fallback and the least of the three: it moves to the
 * day of a lump sum, so on a debt that has been paid down it names a re-basing
 * rather than a beginning — which is still a month that moved the debt, so it is
 * a harmless answer to give and the only one some debts have.
 */
private fun Loan.beganIn(window: DateWindow): Boolean =
    (disbursedOn ?: openedOn ?: startedOn) in window

/** Where tapping a projected payment should go. */
sealed interface ProjectionTarget {
    /** The entry the repeating rule was created from. */
    data class Entry(val id: String) : ProjectionTarget

    /** The loan whose instalment this is. */
    data class LoanEditor(val id: String) : ProjectionTarget

    /**
     * A rule with nothing left behind it, so the only thing to offer is stopping
     * it.
     *
     * This is how a payment outlives the thing that created it: rules written
     * before entries were linked to them have no anchor, so deleting the entry
     * could not stop the rule and the payment kept appearing for something the
     * user had already thrown away — with no way to get rid of it.
     */
    data class Rule(val seriesId: String) : ProjectionTarget
}

/**
 * One month of money, past and future in the same list.
 *
 * The split between "history" and "what is coming" was a tab boundary the user
 * had to learn: an EMI due next week lived in one place and the same EMI once
 * paid lived in another. It is one month-shaped view now — real entries for the
 * days that have happened, scheduled ones for the days that have not, and what
 * both do to the balances.
 */
data class TimelineUiState(
    val monthLabel: String = "",
    /** The Gregorian span, when the primary label is a Nepali month. */
    val monthSecondary: String? = null,
    val isCurrentMonth: Boolean = true,
    val canGoForward: Boolean = false,
    val days: List<DayGroup> = emptyList(),
    /** Scheduled payments falling inside this month that have not happened yet. */
    val projectedDays: List<ProjectedDay> = emptyList(),
    /** Each account now, and once this month's scheduled payments have run. */
    val accounts: List<AccountProjection> = emptyList(),
    /** What a bank is owed, as this month leaves it. */
    val loans: List<LoanOutlook> = emptyList(),
    /** And what a person is — kept apart, as the Accounts page keeps them. */
    val personalLoans: List<LoanOutlook> = emptyList(),
    val lentOut: List<LoanOutlook> = emptyList(),
) {
    val isEmpty: Boolean get() = days.isEmpty() && projectedDays.isEmpty()
    val hasSchedule: Boolean get() = projectedDays.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val recurrence: RecurrenceRepository,
    private val interest: InterestRepository,
    private val maturities: MaturityRepository,
    private val loans: LoanRepository,
    private val settings: SettingsStore,
    private val months: MonthSelection,
    private val clock: Clock,
) : ViewModel() {

    private val _filter = MutableStateFlow(TimelineFilter.ALL)
    val filter: StateFlow<TimelineFilter> = _filter.asStateFlow()

    /**
     * How many months from today the user has stepped. 0 = this month.
     *
     * Shared with Home — see [MonthSelection]. The two pages are one month seen
     * two ways, and "See all" landed on today whichever month it was tapped in.
     */
    private val monthOffset = months.offset

    /** Bumped after a change the projection cannot observe for itself. */
    private val refresh = MutableStateFlow(0)

    private var lastDeleted: String? = null

    /**
     * The marker left behind by a skipped occurrence, so Undo can take it back.
     *
     * Held apart from [lastDeleted] because the two are undone by opposite acts:
     * a deleted entry is restored, and a skip is a row the app wrote that has to
     * go outright. Only ever one of the two is set.
     */
    private var lastSkipped: String? = null

    /**
     * True when a swipe was refused because a later lump sum is on file.
     *
     * The swipe has already sprung back by the time the repository answers, so
     * the reason has to be stated somewhere the user will see it — a row that
     * simply reappears reads as the gesture having failed to register.
     */
    private val _blockedByLaterPayment = MutableStateFlow(false)
    val blockedByLaterPayment: StateFlow<Boolean> = _blockedByLaterPayment.asStateFlow()

    val state: StateFlow<TimelineUiState> =
        combine(settings.settings, monthOffset, refresh) { appSettings, offset, _ ->
            val display = DateDisplay(appSettings.calendarSystem)
            display to windowFor(display, offset)
        }.flatMapLatest { (display, window) ->
            // Real entries are loaded from tomorrow at the earliest as well as
            // from the window's start.
            //
            // An entry the user recorded for a day that has not arrived yet is a
            // fact, not a forecast: no rule produced it, so `projectForward`
            // will never return it, and `observeBalances` cuts at today so the
            // current balance does not hold it either. It fell through both, and
            // a salary already banked for the 3rd simply never reached the
            // account it was paid into.
            val entriesFrom = minOf(window.start, clock.today().plusDays(1))
            // A month that has been and gone is answered as it stood, not as it
            // stands. Every debt below is wound back to the last day of it, so
            // stepping backwards climbs the balance by one instalment a month
            // exactly as stepping forwards brings it down — and the debts stop
            // contradicting the account balances beside them, which the month's
            // own schedule has always moved. Null for this month and for any
            // still to come: there the question is where the schedule leaves it,
            // which is what the projections below answer.
            val asOf = window.lastDay.takeIf { it.isBefore(clock.today()) }
            combine(
                repository.observeEntries(entriesFrom, window.endExclusive),
                repository.observeConfirmedBalance(),
                repository.observeAccountBalances(asOf),
                loans.observeLoansWithBalance(asOf),
                _filter,
            ) { entries, balance, accounts, loanRows, filter ->
                val today = clock.today()

                // Everything scheduled between today and the end of the month
                // being viewed — not just the part inside it. A future month's
                // balance has to have felt the months in between, or it would be
                // the right list of payments against the wrong starting figure.
                // The rules the user wrote, plus the one the bank keeps: a
                // quarter's interest lands on a date and moves an account, so
                // the running balance and "where your money will be" have to
                // feel it exactly as they feel an EMI. A deposit coming free or
                // a policy paying out is the third: one known day on which a
                // large sum leaves one holding and lands in another.
                val projected = recurrence.projectForward(window.lastDay) +
                    interest.projectDue(window.lastDay) +
                    maturities.maturingBetween(window.lastDay)

                // Entries already recorded for days still to come. Both halves
                // of a transfer are kept: the arriving one is what moves the
                // account it lands in, and dropping it would leave the money
                // having left one account and reached none.
                val ahead = entries.filter { it.occurredOn > today }
                val aheadByDate = ahead
                    .groupBy { it.occurredOn }
                    .mapValues { (_, rows) -> Money(rows.sumOf { it.signedBaseAmount.minor }) }

                val projectedByDate = projected.groupBy { it.date }
                var running = balance
                val runningByDay = (projectedByDate.keys + aheadByDate.keys)
                    .sorted()
                    .map { date ->
                        val rows = projectedByDate[date].orEmpty()
                        // The running balance feels both halves of a transfer —
                        // it has to, or the two accounts would not net out. Only
                        // the drawn rows drop the arriving one. It feels what is
                        // already recorded for that day too, or it would
                        // contradict the entries printed underneath it.
                        running += Money(rows.sumOf { it.signedBaseAmount.minor })
                        running += aheadByDate[date] ?: Money.ZERO
                        ProjectedDay(
                            date,
                            rows.filter { it.matches(filter) && !it.isTransferArrival },
                            running,
                        )
                    }

                // Oldest first, like the rest of this page: the balance on each
                // day is where the account stands once that day has run, so the
                // column only reads as a running one in the direction it runs.
                val inWindow = runningByDay
                    .filter { it.date in window && it.entries.isNotEmpty() }
                    .sortedBy { it.date }

                // One movement, one row. Both halves are stored and both move a
                // balance, but a timeline that lists a transfer twice reads as
                // money appearing from nowhere and going somewhere else.
                // Balance corrections are dropped entirely: they justify an
                // account's figure rather than record anything happening, and a
                // labelless row saying nothing was all they could draw.
                val visible = entries.filter {
                    it.occurredOn in window && it.matches(filter) &&
                        !it.isTransferArrival && !it.isBalanceCorrection
                }

                // Where each account lands once this month has run. Only payments
                // that name an account can move one — an unassigned payment cannot
                // be attributed without guessing.
                val byAccount = projected
                    .filter { it.accountId != null }
                    .groupBy { it.accountId!! }
                val deltaByAccount = byAccount
                    .mapValues { (_, rows) -> rows.sumOf { it.signedBaseAmount.minor } }
                // The same, from the entries already recorded for days still to
                // come. Kept apart from the projections above only because they
                // arrive from a different place; a balance cannot tell the
                // difference and must not.
                val aheadByAccount = ahead
                    .filter { it.accountId != null }
                    .groupBy { it.accountId!! }

                // Debts, moved on by the same payments the accounts above have
                // already felt. A month is shown after its schedule has run, so
                // a balance that ignored this month's instalments would sit
                // directly under an account balance that did not, contradicting
                // it — and it was the loan that looked wrong.
                // Only a loan's own instalments move a debt. Interest paid into
                // a savings account is grouped by a synthetic id that matches no
                // loan, but leaving it in this map would be an accident waiting
                // for the day one collides.
                val dueBySeries = projected.filterNot { it.isInterest }.groupBy { it.seriesId }
                // Which debts this month actually moved — the same question the
                // accounts below are asked, and for the same reason. A page
                // about September listing every debt the user has ever taken put
                // the one September touched among four it did not, and left the
                // reader to work out which. A debt is moved by an instalment
                // that has already been paid, by one still to come, or by any
                // other movement against it — a lump sum, a drawdown, more
                // borrowed on the same arrangement.
                val loanOfSeries = loanRows.mapNotNull { l -> l.seriesId?.let { it to l.id } }
                    .toMap()
                //
                // Asked of the *filtered* rows, so the blocks below the log list
                // the holdings whatever is on screen moved. With Money out
                // picked, the log is a month's spending and the debts under it
                // were the whole set regardless — a debt nothing had gone out to
                // sat beneath a list it appeared nowhere in. The figures beside
                // each are still the month's own: what is drawn is which
                // holdings to draw, not what happened to them.
                //
                // **And an arrangement that began inside the window was moved by
                // it**, whether or not it wrote a row. That is the largest thing
                // that ever happens to a holding — it goes from not existing to
                // owing what it owes — and it is exactly the case with nothing to
                // find it by: money lent to a person last November with no
                // interest, no schedule and no account named writes no entry at
                // all, so the one month it belongs to more than any other was the
                // one month the page did not list it. The same is true of a
                // deposit put away, a policy taken out, a goal started and a card
                // approved. Only for [TimelineFilter.ALL]: a beginning that put
                // no row in the log has no business under a log narrowed to money
                // going one way, which is the same rule the filtering above obeys.
                val everything = filter == TimelineFilter.ALL
                val touchedLoans = buildSet {
                    entries.forEach {
                        if (it.occurredOn in window && it.matches(filter)) {
                            it.belongsToLoanId?.let(::add)
                        }
                    }
                    projected.forEach {
                        if (it.date in window && it.matches(filter)) {
                            loanOfSeries[it.seriesId]?.let(::add)
                        }
                    }
                    if (everything) {
                        loanRows.forEach { if (it.beganIn(window)) add(it.id) }
                    }
                }
                val open = loanRows
                    .filterNot { it.isClosed }
                    .filter { it.id in touchedLoans }
                    .map { loan ->
                        val due = loan.seriesId?.let { dueBySeries[it] }.orEmpty()
                        // And whatever lump sum the user has promised for a day
                        // inside this window. It is a real row the log has already
                        // drawn, and the debt underneath would otherwise sit
                        // contradicting it — the same reason an account's forward
                        // balance counts an entry dated forward. Nothing has been
                        // taken off the debt itself yet; that happens on the day.
                        val promised = loan.pendingBy(window.lastDay)
                        LoanOutlook(
                            loan = loan,
                            after = Money(
                                (
                                    loan.outstandingAfter(
                                        extraPayments = due.size,
                                        extraRepaid = Money(due.sumOf { it.amount.minor }),
                                    ).minor - promised.minor
                                    ).coerceAtLeast(0L)
                            ),
                        )
                    }
                val (given, borrowed) = open.partition { it.loan.isLent }
                val (personal, fromBanks) =
                    borrowed.partition { it.loan.kind == LoanKind.PERSONAL }

                // Which holdings this month touched at all. Both halves of a
                // transfer count — the arriving one is dropped from the drawn
                // rows but the account it landed in still moved — and so does a
                // payment that has not happened yet, because the list beneath
                // states where the month leaves each balance.
                //
                // Through the filter, like the debts above: the blocks are the
                // other half of the page the chips narrow, and an account that
                // only ever received money had no business under a log filtered
                // down to what went out. A transfer moves no holding into either
                // of those two answers, which is the same thing [matches] says
                // about the rows themselves.
                val touched = buildSet {
                    entries.forEach {
                        if (it.occurredOn in window && it.matches(filter)) {
                            it.accountId?.let(::add)
                        }
                    }
                    projected.forEach {
                        if (it.date in window && it.matches(filter)) it.accountId?.let(::add)
                    }
                    // And the arrangements that began inside it — see the note
                    // above the debts. Only the three kinds that *have* a day
                    // they began on: a deposit, a policy and a goal each start
                    // on a day the user gave, where a bank account or a cash tin
                    // has only the day it was typed into the app, which is a fact
                    // about the app rather than about the money.
                    if (everything) {
                        accounts.forEach { row ->
                            row.account.depositStartedOn
                                ?.takeIf { it in window }
                                ?.let { add(row.account.id) }
                        }
                    }
                }

                TimelineUiState(
                    monthLabel = window.label,
                    monthSecondary = display.secondaryRange(window),
                    isCurrentMonth = today in window,
                    // No ceiling. A plan can run for twenty years — a policy, a
                    // loan, a goal — and a stepper that stopped two years out
                    // could not be walked to the month any of them ends in.
                    canGoForward = true,
                    days = visible.groupByDay(),
                    projectedDays = inWindow,
                    // The holdings this month actually moved, whatever kind they
                    // are — a bank account, a wallet, a cash tin, a policy, a
                    // goal. A month is a story about some of the user's money
                    // and not all of it: a list of every holding they own put
                    // the two accounts September touched among five it did not,
                    // and left the reader to work out which was which. Anything
                    // untouched is the same figure it was last month, which the
                    // Accounts page already says.
                    accounts = accounts.filter { it.account.id in touched }.map { row ->
                        val code = row.account.currencyCode
                        // The account's own delta, from the payments actually
                        // denominated in its currency. Anything else in there is
                        // already a converted figure and adding it to a dollar
                        // balance would produce a number in no currency at all.
                        val ownDelta = Money(
                            (
                                byAccount[row.account.id]
                                    ?.filter { it.currencyCode.equals(code, ignoreCase = true) }
                                    ?.sumOf { it.signedAmount.minor } ?: 0L
                                ) + (
                                aheadByAccount[row.account.id]
                                    ?.filter { it.currencyCode.equals(code, ignoreCase = true) }
                                    ?.sumOf { it.signedAmount.minor } ?: 0L
                                )
                        )
                        val delta = Money(
                            (deltaByAccount[row.account.id] ?: 0L) +
                                (
                                    aheadByAccount[row.account.id]
                                        ?.sumOf { it.signedBaseAmount.minor } ?: 0L
                                    )
                        )
                        // A deposit empties on one known day rather than
                        // drifting with entries, so its forward figure is that
                        // day and not a sum. Subtracting the projected maturity
                        // from what it holds gave a *negative* balance, short by
                        // exactly the interest — the money leaving is the
                        // deposit plus its interest, and only the deposit was
                        // ever in there.
                        val matures = row.account.maturesOn
                        val handedBack = matures != null &&
                            matures.isAfter(today) && !matures.isAfter(window.lastDay)
                        // A policy and a goal empty on the same known day, and
                        // until they do their payments go on arriving — so
                        // unlike a deposit they move with the projections. All
                        // three drop to nothing rather than to what is left
                        // after subtracting the payout: what leaves is the whole
                        // arrangement, and on a policy only the premiums were
                        // ever in there.
                        val emptiesOnItsDay = row.account.isFixedDeposit ||
                            row.account.isInsurance || row.account.isGoal
                        val afterOwn = when {
                            handedBack && emptiesOnItsDay -> Money.ZERO
                            row.account.isFixedDeposit -> row.balance
                            else -> row.balance + ownDelta
                        }
                        AccountProjection(
                            accountId = row.account.id,
                            name = row.account.name,
                            institution = row.account.institution,
                            kind = row.account.kind,
                            currencyCode = code,
                            showInDisplayCurrency = row.account.showInDisplayCurrency,
                            color = row.account.color,
                            now = row.balanceInBase,
                            // Converted at the same rate today's figure was, so
                            // the two describe one moment rather than differing
                            // by an exchange-rate move nobody asked about.
                            after = if (row.account.isFixedDeposit || handedBack) {
                                row.convertLike(afterOwn)
                            } else {
                                row.balanceInBase?.let { it + delta }
                            },
                            nowOwn = row.balance,
                            afterOwn = afterOwn,
                        )
                    },
                    loans = fromBanks,
                    personalLoans = personal,
                    lentOut = given,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    private fun windowFor(display: DateDisplay, offset: Int): DateWindow {
        var window = display.monthWindow(clock.today())
        repeat(kotlin.math.abs(offset)) {
            window = if (offset < 0) display.previousMonth(window) else display.nextMonth(window)
        }
        return window
    }

    /**
     * Whether a row survives the current filter.
     *
     * Adjustments are shown under "everything" — a transfer or a balance
     * correction is a real thing that happened — but they are never money in or
     * money out, so the two directional filters skip them.
     */
    private fun MoneyEntry.matches(filter: TimelineFilter): Boolean = when (filter) {
        TimelineFilter.ALL -> true
        TimelineFilter.IN -> direction == Direction.IN && !isAdjustment
        TimelineFilter.OUT -> direction == Direction.OUT && !isAdjustment
    }

    private fun ProjectedEntry.matches(filter: TimelineFilter): Boolean = when (filter) {
        TimelineFilter.ALL -> true
        TimelineFilter.IN -> direction == Direction.IN && !isAdjustment
        TimelineFilter.OUT -> direction == Direction.OUT && !isAdjustment
    }

    fun showPreviousMonth() { months.show(monthOffset.value - 1) }

    fun showNextMonth() { months.show(monthOffset.value + 1) }

    fun showCurrentMonth() { months.show(0) }

    fun setFilter(filter: TimelineFilter) { _filter.value = filter }

    /**
     * What a projected payment should open.
     *
     * A projection is not a row in the database — it is computed from a rule — so
     * "edit it" means editing whatever produced it: the loan, or the entry the
     * rule was created from.
     */
    suspend fun resolveProjection(seriesId: String): ProjectionTarget {
        loans.findLoanBySeries(seriesId)?.let { return ProjectionTarget.LoanEditor(it.id) }
        return repository.anchorEntryForSeries(seriesId)
            ?.let { ProjectionTarget.Entry(it) }
            ?: ProjectionTarget.Rule(seriesId)
    }

    /** Stops a repeating rule for good, along with everything it would produce. */
    fun stopSeries(seriesId: String) = viewModelScope.launch {
        recurrence.deleteSeries(seriesId)
        refresh.value++
    }

    /**
     * Deletes an entry, remembering it so Undo can bring it back.
     *
     * The rule it may have created and the debt it may have moved are the
     * repository's business — see [WalletRepository.deleteEntry]. What is left
     * here is telling the screen which of the two things happened, since the
     * swipe is already over by the time the answer comes back.
     *
     * @param onDone called when the row is gone, so the snackbar can offer Undo.
     */
    fun delete(entryId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        when (repository.deleteEntry(entryId)) {
            EntryDeletion.Done -> {
                lastDeleted = entryId
                lastSkipped = null
                onDone()
            }
            // Nothing was removed, so there is nothing to undo and no snackbar
            // to show — the screen says why instead.
            EntryDeletion.LaterPaymentFirst -> _blockedByLaterPayment.value = true
        }
        refresh.value++
    }

    /**
     * Drops one date of a repeating rule that has not arrived yet.
     *
     * The row swiped is a projection rather than an entry, so there is nothing
     * to delete — see [RecurrenceRepository.skipOccurrence], which writes the
     * occurrence already tombstoned so the date is not generated again. The rule
     * carries on untouched, which is what separates this from stopping it.
     */
    fun skip(seriesId: String, date: LocalDate, onDone: () -> Unit) = viewModelScope.launch {
        recurrence.skipOccurrence(seriesId, date)?.let { marker ->
            lastSkipped = marker
            lastDeleted = null
            onDone()
        }
        refresh.value++
    }

    fun undoDelete() = viewModelScope.launch {
        lastDeleted?.let { repository.undoDeleteEntry(it) }
        lastSkipped?.let { recurrence.unskipOccurrence(it) }
        lastDeleted = null
        lastSkipped = null
        refresh.value++
    }

    fun dismissBlocked() { _blockedByLaterPayment.value = false }
}

/**
 * Another figure from this holding, in the currency its converted one is in.
 *
 * Uses the ratio that produced the *current* converted balance rather than
 * asking for a fresh rate, so a projection and the balance above it describe one
 * moment. Converting again would move the forward figure by an exchange-rate
 * step nobody asked about, and it would read as the money having changed.
 */
private fun AccountWithBalance.convertLike(amount: Money): Money? {
    val inBase = balanceInBase ?: return null
    if (balance.minor == 0L) return if (amount.minor == 0L) Money.ZERO else null
    val rate = inBase.minor.toDouble() / balance.minor
    return Money(Math.round(amount.minor * rate))
}
