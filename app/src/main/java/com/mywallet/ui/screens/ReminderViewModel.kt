package com.mywallet.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.ReminderRepository
import com.mywallet.data.repo.Reminders
import com.mywallet.data.repo.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class ReminderUiState(
    val reminders: Reminders = Reminders(),
    /**
     * The day the list starts from, injected rather than asked of the system.
     *
     * The screen names it "Today" and the next one "Tomorrow", and those words
     * have to agree with the dates the rows were gathered for — a screen reading
     * the wall clock for itself would disagree with them across midnight.
     */
    val today: LocalDate = LocalDate.ofEpochDay(0),
    /** The day being looked at: today, or one the user has stepped forward to. */
    val day: LocalDate = LocalDate.ofEpochDay(0),
    val isLoading: Boolean = true,
) {
    val isToday: Boolean get() = day == today
    val canGoBack: Boolean get() = day > today
    val canGoForward: Boolean get() = day < today.plusDays(ReminderViewModel.MAX_DAYS_AHEAD)
}

/**
 * What wants doing on one day: today, or a day the user has stepped to.
 *
 * **One day and no window.** The lead time used to widen this page as well as
 * the notification, so with a day's warning asked for every payment was drawn on
 * two consecutive pages — the day before it and the day itself — and a reader
 * stepping forward met the same rent twice and read it as the app having
 * recorded it twice. The warning is what a *notification* is for; a page you
 * stepped to is a question about that day. So this tab reads no setting at all
 * now, which is also what stops the two drifting apart: there is nothing left
 * here to disagree with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val reminders: ReminderRepository,
    private val wallet: WalletRepository,
    private val loans: LoanRepository,
    private val recurrence: RecurrenceRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * Bumped after a change the list cannot observe for itself.
     *
     * A deleted entry drops out on its own, the rows being a Room flow — but a
     * *skipped* occurrence is a projection, computed on each emission from a
     * marker row the query behind this page does not read. Without this the
     * payment stayed on screen until something else moved.
     */
    private val refresh = MutableStateFlow(0)

    /** The row a swipe removed, so the snackbar's Undo has something to undo. */
    private var lastDeleted: String? = null
    private var lastSkipped: String? = null

    private val _blockedByLaterPayment = MutableStateFlow(false)

    /**
     * A lump sum cannot be taken back while a later one is on file — see
     * [WalletRepository.deleteEntry]. Reported here exactly as the timeline
     * reports it, since the same row can be swiped from either page.
     */
    val blockedByLaterPayment: StateFlow<Boolean> = _blockedByLaterPayment.asStateFlow()

    /**
     * How many days forward the user has stepped. 0 = today.
     *
     * Deliberately not shared with [MonthSelection], which Home and the timeline
     * hold between them: those two are one month seen two ways, and this is a
     * different question about a different unit. It is not persisted either, for
     * the reason the month is not — a tab opened tomorrow should open on the day
     * it is.
     */
    private val dayOffset = MutableStateFlow(0)

    val state: StateFlow<ReminderUiState> = dayOffset
        .combine(refresh) { offset, _ -> offset }
        .flatMapLatest { offset ->
            val day = clock.today().plusDays(offset.toLong())
            // Real rows first, from Room, so the page redraws itself the moment
            // one is added or deleted. The projections are computed rather than
            // stored, so they are worked out again on every emission — which is
            // also what keeps a rule the user has just edited from being shown
            // as it was.
            //
            // One day, from its own morning to the next: see
            // [ReminderRepository.lastDay] for why the lead time is the
            // notification's and not this page's.
            wallet
                .observeEntries(day, day.plusDays(1))
                .map { entries ->
                    ReminderUiState(
                        reminders = reminders.onDay(day, entries),
                        today = clock.today(),
                        day = day,
                        isLoading = false,
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderUiState())

    /**
     * Take a movement back, and say so.
     *
     * The same door the timeline's swipe goes through, deliberately: a row here
     * and the same row there are one entry, and a delete that put a debt's
     * balance back on one page and not the other would be two answers to one
     * question. See [WalletRepository.deleteEntry], which is where the balance a
     * payment moved is put back.
     */
    fun delete(entryId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        when (wallet.deleteEntry(entryId)) {
            EntryDeletion.Done -> {
                lastDeleted = entryId
                lastSkipped = null
                onDone()
            }
            EntryDeletion.LaterPaymentFirst -> _blockedByLaterPayment.value = true
        }
        refresh.value++
    }

    /**
     * Drop one occurrence of a rule, which is not a row and so cannot be
     * deleted — it is a date the rule would otherwise go on producing. The
     * marker written in its place is what blocks it; see
     * [RecurrenceRepository.skipOccurrence].
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
        lastDeleted?.let { wallet.undoDeleteEntry(it) }
        lastSkipped?.let { recurrence.unskipOccurrence(it) }
        lastDeleted = null
        lastSkipped = null
        refresh.value++
    }

    fun dismissBlocked() { _blockedByLaterPayment.value = false }

    fun showPreviousDay() { showDay(dayOffset.value - 1) }

    fun showNextDay() { showDay(dayOffset.value + 1) }

    fun showToday() { showDay(0) }

    /**
     * Open on a particular day, for a tapped notification.
     *
     * The note may be about tomorrow — that is what asking to be warned early
     * means — and this page answers for one day, so a tap that landed on today
     * would open a list the payment it named is not in. Clamped by [showDay] like
     * every other way of getting here, so a day out of range simply comes to the
     * nearest one this page can show rather than being refused: the tap must land
     * somewhere.
     */
    fun showDayOn(date: LocalDate) {
        showDay(ChronoUnit.DAYS.between(clock.today(), date).toInt())
    }

    /**
     * Clamped here as well as by the arrows being disabled, because the arrows
     * are drawn from a state that lags a tap: two quick presses at the edge both
     * see the enabled one.
     */
    private fun showDay(offset: Int) {
        dayOffset.value = offset.coerceIn(0, MAX_DAYS_AHEAD.toInt())
    }

    companion object {
        /**
         * How far ahead the stepper goes, in days from today.
         *
         * A month of warning is as much as this page can usefully answer: past
         * it the question stops being "what wants doing" and becomes "what does
         * next month look like", which is the timeline's, and it steps in months
         * rather than making the user tap thirty times to get there.
         */
        const val MAX_DAYS_AHEAD = 30L
    }

    /**
     * What a projected row should open — the loan whose instalment it is, or the
     * entry its rule was written from. Answered exactly as the timeline answers
     * it, so the same row leads to the same place from either page.
     */
    suspend fun resolve(seriesId: String): ProjectionTarget {
        loans.findLoanBySeries(seriesId)?.let { return ProjectionTarget.LoanEditor(it.id) }
        return wallet.anchorEntryForSeries(seriesId)
            ?.let { ProjectionTarget.Entry(it) }
            ?: ProjectionTarget.Rule(seriesId)
    }

}
