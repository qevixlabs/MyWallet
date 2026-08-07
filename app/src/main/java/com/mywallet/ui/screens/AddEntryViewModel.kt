package com.mywallet.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mywallet.R
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.repo.Clock
import com.mywallet.data.repo.EntryDeletion
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.PlanRepository
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.SaveResult
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Account
import com.mywallet.domain.Loan
import com.mywallet.domain.Shortlist
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which field to point the user at when a save is refused. */
enum class EntryError {
    NONE, NOTE, AMOUNT, TRANSFER_ACCOUNTS, NO_RATE,

    /** More than the card has left to draw against — see [SaveResult.OverLimit]. */
    OVER_LIMIT,
}

data class AddEntryUiState(
    val isEditing: Boolean = false,
    val direction: Direction = Direction.OUT,
    val amountText: String = "",
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val accounts: List<Account> = emptyList(),
    /**
     * The cards and overdrafts money can be spent straight from.
     *
     * A facility is not a place money sits, which is why a debt is otherwise
     * absent from this form — but a card *is* how the money leaves, and asking
     * the user to record a purchase as a drawdown on the debt's own screen and
     * then again as spending was two entries for one thing. Only these: a term
     * loan is repaid, never spent from.
     */
    val cards: List<Loan> = emptyList(),
    val selectedAccountId: String? = null,
    /**
     * Whether **Not recorded** is one of the answers in the account row.
     *
     * It is the answer the debt's own cards already accept — plenty of money
     * between people is cash, and naming an account it never touched is worse
     * than naming none (`payFromOptional`). What this form did with such a row
     * when it was reopened from a debt's statement was quietly stamp the
     * shortlist's default on it, so a movement recorded against no account came
     * back showing one and Save moved the money through a bank it had never
     * been near. The question a form asks about a fact must have the answer the
     * fact already carries among its options, or reopening it is an edit.
     *
     * Set for a movement against a debt, and for any row that already has no
     * account whatever it is — the second is not a guess about what may be left
     * blank but a refusal to change an answer this form was only asked to show.
     * An ordinary expense still has to say where the money came from: there is
     * no debt behind it to carry the figure, and a payment from nowhere would
     * be a balance the app could not put anywhere.
     */
    val accountOptional: Boolean = false,
    /**
     * The card this movement is paid from, when it is one. Exactly one of this
     * and [selectedAccountId] is ever set: money comes from one place.
     */
    val selectedCardId: String? = null,
    /**
     * Moving money between two of the user's own accounts rather than in or out
     * of the wallet. Then [selectedAccountId] is the source and [toAccountId]
     * the destination, and no label applies: a transfer is not a kind of
     * spending.
     */
    val isTransfer: Boolean = false,
    val toAccountId: String? = null,
    /**
     * How many holdings could stand at one end of a transfer, counted without
     * regard to the direction the form happens to be on. See [canTransfer].
     */
    val transferEnds: Int = 0,
    /** What will land in the other account, when the two currencies differ. */
    val transferPreview: String? = null,
    /** Set when editing a transfer that already exists. */
    val transferId: String? = null,
    /**
     * Currency of the amount being typed, not necessarily the display one.
     *
     * Never asked for: it is whichever currency the chosen account holds. A
     * separate question could only be answered a second, contradictory way —
     * dollars typed against a rupee account is not a currency the movement has,
     * it is a mistake — and the account chips already print the code.
     */
    val currencyCode: String = "NPR",
    val baseCurrencyCode: String = "NPR",
    /** Live preview of the converted amount, or null when no conversion needed. */
    val convertedPreview: String? = null,
    /** When on, saving also creates a repeating rule from this entry. */
    val repeats: Boolean = false,
    /**
     * Whether a rule is already on file behind this entry.
     *
     * Not [repeats], which is the checkbox and follows the user's hand: somebody
     * who unticks it and then thinks better of deleting has still got the rule,
     * and the question the bin asks has to be about what is actually there.
     */
    val hasSeries: Boolean = false,
    /**
     * Whether this rule steps in the calendar being read.
     *
     * Asked, with the same switch every bank holding is asked with, and **off
     * unless the user says otherwise** — the answer a holding's own opt-in
     * defaults to, and now the answer here. It was hard-wired to yes on the
     * reasoning that a rule the user writes is their own rhythm and should
     * follow the patro they read; what that cost is a schedule that changes
     * shape according to a display setting nobody associated with it, with
     * nothing on the form saying so.
     *
     * Stored on the series rather than read back from the setting, so a rule
     * written today goes on meaning the months it was written in — see
     * `recurring_series.uses_selected_calendar`.
     */
    val usesSelectedCalendar: Boolean = false,
    /** Whether the calendar being read is Bikram Sambat. */
    val calendarIsNepali: Boolean = false,
    /**
     * Whether the app is being read in Nepali, which is the other half of
     * [asksCalendar] — see `AppSettings.readsNepali`.
     */
    val languageIsNepali: Boolean = false,
    /**
     * True when this entry is one instalment of a loan. The rule behind it
     * belongs to the loan — its dates and amount are derived from the
     * amortisation schedule — so the repeat controls are withheld: rewriting
     * the rule from one edited occurrence would move every future payment out
     * from under the loan's own figures.
     */
    val isLoanInstalment: Boolean = false,
    /**
     * The overdraft an existing entry drew its money from, when editing one.
     * A drawdown is borrowing, not income, so reopening it must look like the
     * form that created it: no label to pick, no repeat to set — just where
     * the money came from, said rather than asked.
     */
    val existingDrawdownName: String? = null,
    /**
     * The card or overdraft an existing purchase was spent from, when editing
     * one.
     *
     * Said rather than asked, exactly as a drawdown's is. The facility is
     * carrying this figure in its drawn balance, so it is not a question this
     * form can reopen: answering it with a bank account would leave the card
     * owing money for a purchase that no longer says it was made on the card.
     * The amount, the day and what it was for are all still the user's to
     * correct — see [LoanRepository.spendOnCard].
     */
    val existingCardName: String? = null,
    /**
     * The arrangement whose schedule wrote this movement — a policy's premium, a
     * deposit's instalment, a goal's contribution — when editing one.
     *
     * Its rhythm and its two ends were agreed on the arrangement's own card and
     * are carried by a rule the arrangement owns, so this form states them
     * instead of offering them: re-answering "into" would move a premium off the
     * policy that is counting it, and unticking a repeat this form did not write
     * would stop a schedule the policy still expects. What is left is a
     * correction to one date's figure, which is what the user came for.
     */
    val planPaymentName: String? = null,
    /**
     * Editing one date of a repeating payment, not the rule behind it.
     *
     * The repeat controls are the rule's and are withheld — unticking them here
     * would stop a schedule the user only meant to correct one date of — and a
     * line says which act this is. The save already does the right thing: the
     * row keeps its series and is marked confirmed, so the rule's next edit
     * cannot rewrite a figure the user has stated by hand.
     */
    val isSingleOccurrence: Boolean = false,
    val interval: RecurrenceInterval = RecurrenceInterval.MONTHLY,
    /** Null means it repeats indefinitely. */
    val repeatUntil: LocalDate? = null,
    val error: EntryError = EntryError.NONE,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
    /**
     * True when a delete — or a correction that would move the same balance —
     * was refused because a later lump sum off the same debt is on file. See
     * [Reversal.LaterPaymentFirst].
     *
     * One flag for both, because it is one refusal said in one set of words:
     * what the app cannot do is move a figure that a later payment was measured
     * against, and whether the user was removing it or correcting it does not
     * change what has to happen first.
     */
    val deleteBlocked: Boolean = false,
) {
    /**
     * The accounts this form offers — the places money sits, and nothing else.
     *
     * A goal is an arrangement rather than a place, and its money moves through
     * its own Deposit and Withdraw cards; a debt is a balance rather than a row
     * for money to sit in, and every movement against one is made on the debt's
     * own screen. Neither is offered here.
     *
     * The exception is an entry that already names a goal, which stays in the
     * row while it is the answer: a form that cannot show what it is set to is
     * worse than one that offers a chip it would not offer again. [accounts]
     * keeps them all either way, so every lookup below still finds one.
     */
    val accountChips: List<Account>
        get() = accounts.filter {
            !it.isGoal || it.id == selectedAccountId || it.id == toAccountId
        }

    val selectedAccount: Account?
        get() = accounts.firstOrNull { it.id == selectedAccountId }

    val selectedCard: Loan?
        get() = cards.firstOrNull { it.id == selectedCardId }

    /**
     * Whether a card may be the source of this movement.
     *
     * Money **out** and the paying half of a transfer, and nothing else. A card
     * cannot receive income — money arriving on one is a repayment, which is the
     * card's own screen — and a transfer *into* a card is that same repayment
     * wearing a different name.
     */
    val offersCards: Boolean
        get() = cards.isNotEmpty() && (isTransfer || direction == Direction.OUT)

    val toAccount: Account?
        get() = accounts.firstOrNull { it.id == toAccountId }

    /**
     * Nothing to move money between yet.
     *
     * Both ends of a transfer are accounts, so the count is of accounts: a debt
     * is a balance rather than a place money can sit, and moving anything
     * against one is the debt's own screen's to do.
     */
    val canTransfer: Boolean get() = transferEnds > 1

    /**
     * Whether to draw the account row at all — which is whenever there is one
     * to draw.
     *
     * It used to be withheld for a *single* account, on the reasoning that one
     * account is not a question but an answer already filled in. What that
     * produced was a form with no "Paid from" on it at all: somebody with one
     * bank account and a term loan — whose instalments are the loan's own screen
     * to change, so it is offered in neither row — opened the money form and saw
     * no holding named anywhere, on the page that was about to move one. Their
     * Accounts page meanwhile listed every bank they had, so the form read as
     * having lost them.
     *
     * A form has to say what it is going to debit even when there is nothing to
     * decide. One chip, already selected, is that sentence, and it costs a line.
     */
    val showsAccountRow: Boolean
        get() = !isExistingCardSpend && !isPlanPayment &&
            (accountChips.isNotEmpty() || offersCards)

    /**
     * Whether to ask what this movement was for at all.
     *
     * Everywhere but on the movements the app named itself: money taken from a
     * debt and money spent on a facility are called after the arrangement they
     * belong to — a drawdown's row reads "Taken from Dad" with nothing typed —
     * so an empty box asking what it is for is a question already answered on
     * the row. It comes back the moment there is an answer in it, since a
     * purchase is named after what was bought and that word is the user's to
     * correct.
     */
    val showsNote: Boolean
        get() = note.isNotBlank() || !(isExistingDrawdown || isExistingCardSpend)

    /** True while editing one date of an arrangement's own schedule. */
    val isPlanPayment: Boolean get() = planPaymentName != null

    /**
     * True for a purchase on a card or overdraft already on file, reopened.
     *
     * Where it was spent from is then a fact on the row rather than a row of
     * chips — see [existingCardName].
     */
    val isExistingCardSpend: Boolean get() = existingCardName != null

    /**
     * True for a drawdown already on file, being reopened.
     *
     * A drawdown is borrowing rather than income, so it is shown the way it was
     * written: no label to pick and no repeat to set. Nothing writes a new one
     * from this form any more — an overdraft is drawn on from its own screen —
     * but the ones already recorded still open here.
     */
    val isExistingDrawdown: Boolean get() = existingDrawdownName != null

    /**
     * A repeating rule cannot be written against a drawdown.
     *
     * It re-bases the debt as it is written, and a rule that did that once a
     * month would report a debt the user never took.
     */
    val canRepeat: Boolean
        get() = !isExistingDrawdown && !isPlanPayment && !isSingleOccurrence &&
            selectedCardId == null

    /**
     * Whether the calendar question is worth asking at all.
     *
     * Only where somebody might think in Nepali months at all — the calendar
     * being read is the strong signal, and the language is the other: a reader in
     * Nepali on Gregorian dates is still somebody a subscription starting on
     * 1 Baisakh means something to, and the switch is how they say so. To an
     * English reader on English dates both answers step the same months, so the
     * question is about nothing and the app simply counts in Gregorian.
     *
     * And only while *writing* a rule: changing it on one already running
     * rebuilds every unconfirmed occurrence from the rule's own start, which is a
     * great deal to do to somebody who opened the form to correct an amount. The
     * same two conditions a holding's own opt-in is gated on.
     */
    val offersRepeatCalendar: Boolean
        get() = !isEditing && (calendarIsNepali || languageIsNepali)

    /**
     * What the months are actually counted in, which is the opt-in and the
     * setting together — see [CalendarSystem.forInterest]. This is the name the
     * line under the switch states, so it follows the switch rather than the
     * setting: unticked, a rule steps English months whatever patro is on
     * screen, and the sentence has to say the same.
     */
    val effectiveCalendarNameRes: Int
        get() = if (usesSelectedCalendar && calendarIsNepali) {
            R.string.calendar_name_nepali
        } else {
            R.string.calendar_name_english
        }
}

@HiltViewModel
class AddEntryViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val settingsStore: SettingsStore,
    private val exchangeRates: ExchangeRateRepository,
    private val recurrence: RecurrenceRepository,
    private val loans: LoanRepository,
    // Asked one question only: whether the rule behind an occurrence belongs to
    // an arrangement — a policy, a deposit, a goal — rather than to the user.
    private val plans: PlanRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * The row this form is editing.
     *
     * A `var`, and the one reason is repeating money: tapping the June
     * occurrence of a monthly bill opens **the rule**, not that occurrence, so
     * [loadExisting] moves this to the rule's own anchor entry. Everything
     * downstream — the save, the delete — then acts on the rule the user is
     * looking at rather than on the row they happened to tap.
     */
    private var entryId: String? =
        savedStateHandle.get<String>(Routes.ARG_ENTRY_ID)?.takeIf { it.isNotBlank() }

    /**
     * Edit the tapped occurrence itself rather than the rule it came from.
     *
     * Set by the account statement and nowhere else — see
     * [Routes.ARG_OCCURRENCE]. Everywhere the page is about the plan, a
     * repeating row still opens its rule.
     */
    private val editOccurrence: Boolean =
        savedStateHandle.get<String>(Routes.ARG_OCCURRENCE) == "true"

    private val _state = MutableStateFlow(AddEntryUiState(date = clock.today()))
    val state: StateFlow<AddEntryUiState> = _state.asStateFlow()

    private var formatter = MoneyFormatter(CurrencyOption.NPR, grouping = settingsStore.grouping)

    /** Set when the entry being edited belongs to a repeating rule. */
    private var editingSeriesId: String? = null

    /**
     * The arrangement's own rule behind the occurrence being edited, held apart
     * from [editingSeriesId] on purpose: that field is the handle the repeat
     * controls rewrite, and this rule is not this form's to rewrite. What it is
     * for is the save — the corrected legs have to keep pointing at the rule, or
     * a premium corrected by a rupee would fall out of the schedule the policy
     * counts. See [AddEntryUiState.planPaymentName].
     */
    private var planSeriesId: String? = null

    /**
     * The rule behind a single occurrence being corrected from the statement,
     * held for the same reason [planSeriesId] is: the saved legs have to keep
     * naming it, and the repeat controls that would rewrite it were never drawn.
     */
    private var occurrenceSeriesId: String? = null

    /**
     * What the user actually uses, most first, for the few chips each row shows
     * before it offers the rest.
     *
     * Read once, when the form opens. A ranking that refreshed as the user
     * worked would reorder the row under the thumb — see [Shortlist.order].
     */
    private var accountRanking: List<String> = emptyList()

    /**
     * The answers the form opened on, which lead their rows.
     *
     * Pinned once and never moved by a tap, for the same reason the ranking is
     * read once. Without it, reopening an entry filed against something the user
     * rarely touches would put its own answer past the end of the shortlist:
     * the form would be unable to show what it was already set to.
     */
    private var pinnedAccountId: String? = null

    private companion object {
        /**
         * Remembered across screens rather than persisted: it only needs to
         * survive between two entries in the same sitting, and a stale
         * preference after a reinstall would be worse than no preference.
         */
        @Volatile var lastUsedAccountId: String? = null
    }


    init {
        val initialDirection = runCatching {
            Direction.valueOf(savedStateHandle.get<String>(Routes.ARG_DIRECTION) ?: "OUT")
        }.getOrDefault(Direction.OUT)
        // Which tab the button opened on. The form keeps its segmented button,
        // so this is where it starts rather than what it is.
        val startsOnTransfer =
            savedStateHandle.get<String>(Routes.ARG_TRANSFER)?.toBoolean() == true
        _state.value = _state.value.copy(
            direction = initialDirection,
            isTransfer = startsOnTransfer,
        )
        if (startsOnTransfer) autoSelectTransferDestination()

        viewModelScope.launch {
            // Settings must land before the existing entry is read: formatting
            // the stored amount back into the field needs the right currency.
            val stored = settingsStore.settings.first()
            val base = stored.currencyCode
            formatter = MoneyFormatter(CurrencyOption.byCode(base), grouping = settingsStore.grouping)
            _state.value = _state.value.copy(
                baseCurrencyCode = base,
                currencyCode = base,
                calendarIsNepali = stored.calendarSystem == CalendarSystem.BIKRAM_SAMBAT,
                languageIsNepali = stored.readsNepali,
            )
            // Rates are refreshed here, not at save time, so the conversion
            // preview is already correct by the time the user taps Save.
            exchangeRates.warmCache(base)
            exchangeRates.refresh(base)
            entryId?.let { loadExisting(it) }
            updateConvertedPreview()
        }
        // Before the lists arrive, so the first thing drawn is already in the
        // order the user reads it in. Three cheap grouped counts, not a walk.
        viewModelScope.launch {
            val accountUse = repository.accountsByUse()
            accountRanking = accountUse.map { it.id }
            reorder()
        }
        // The cards money can be spent straight from. Watched rather than read
        // once: a purchase changes what is left to draw, and a form still
        // offering yesterday's headroom would refuse a save with no reason on
        // screen.
        viewModelScope.launch {
            loans.observeLoansWithBalance().collect { all ->
                _state.value = _state.value.copy(
                    // A facility the bank has retired is not somewhere money can
                    // be spent from, so it is not offered as one — the card
                    // would refuse the purchase this chip is about to record.
                    // What is owed on it is untouched: an expired card with a
                    // balance is still a debt, still listed and still repaid, on
                    // its own screen. A card whose approval day the app was never
                    // told has no expiry and is offered exactly as before.
                    cards = all.filter {
                        it.isOverdraft && !it.isClosed && it.creditLimit != null &&
                            !it.hasExpired(clock.today())
                    },
                )
            }
        }
        viewModelScope.launch {
            repository.observeAccounts().collect { accounts ->
                // Fixed deposits are absent from every list here, including both
                // ends of a transfer. Money cannot be spent from one until it
                // comes free, and it cannot be added to one either — a deposit
                // is an amount agreed on a day, not a pot. The one movement it
                // ever makes is its own maturity, which the timeline draws.
                //
                // A policy is out for the same reason: its premiums are its own
                // rule's to make and its payout falls on one known day, so money
                // typed into it by hand would be a payment the schedule never
                // made. A goal is kept in the list rather than offered: it is
                // fed from its own Deposit and Withdraw cards, and this is where
                // an entry that already names one finds it again.
                val usable = accounts.filter { !it.isFixedDeposit && !it.isInsurance }
                _state.value = _state.value.copy(
                    accounts = usable,
                    // Both ends of a transfer are places money sits, so this
                    // counts the places. A goal is not one of them.
                    transferEnds = usable.count { !it.isGoal },
                )
                autoSelectAccount()
                reorder()
            }
        }
    }

    /**
     * Puts each row in the order the user is likeliest to want it.
     *
     * Called wherever a list arrives or the ranking does, and nowhere else —
     * never from a tap. See [Shortlist.order] for why the answer the form opened
     * on leads and nothing afterwards moves.
     */
    private fun reorder() {
        val current = _state.value
        _state.value = current.copy(
            accounts = Shortlist.order(current.accounts, accountRanking, pinnedAccountId) { it.id },
        )
    }

    private suspend fun loadExisting(requested: String) {
        val id = ruleAnchorFor(requested)
        // A transfer is two rows; opening either must show the one movement.
        repository.findTransfer(id)?.let { detail ->
            // Pinned before anything is drawn, so both ends lead their rows. An
            // account rarely used would otherwise sit past the end of the
            // shortlist and the form could not show what it was already set to.
            pinnedAccountId = detail.fromAccountId
            _state.value = _state.value.copy(
                isEditing = true,
                isTransfer = true,
                transferId = detail.transferId,
                selectedAccountId = detail.fromAccountId,
                toAccountId = detail.toAccountId,
                currencyCode = detail.currencyCode,
                amountText = MoneyFormatter(CurrencyOption.byCode(detail.currencyCode), grouping = settingsStore.grouping)
                    .toPlainInput(detail.amount),
                date = detail.occurredOn,
                note = detail.note.orEmpty(),
            )
            detail.seriesId?.let { seriesId ->
                // The rule may belong to an arrangement rather than to the user:
                // a premium, a deposit's instalment, a goal's own contribution.
                // Then its rhythm and its two ends are the arrangement's — see
                // [AddEntryUiState.planPaymentName] — and the rule stays out of
                // editingSeriesId so saving touches this one date alone, exactly
                // as a loan's instalment is handled below.
                val plan = plans.findPlanBySeries(seriesId)
                if (plan != null) {
                    planSeriesId = seriesId
                    _state.value = _state.value.copy(planPaymentName = plan)
                    return@let
                }
                // A single date of a repeating transfer, from the statement:
                // the rule is untouched and the corrected legs keep naming it.
                if (editOccurrence) {
                    occurrenceSeriesId = seriesId
                    _state.value = _state.value.copy(isSingleOccurrence = true)
                    return@let
                }
                editingSeriesId = seriesId
                recurrence.findSeries(seriesId)?.let { series ->
                    _state.value = _state.value.copy(
                        repeats = !series.isPaused,
                        hasSeries = true,
                        usesSelectedCalendar = series.usesSelectedCalendar,
                        interval = series.interval,
                        repeatUntil = series.endOn?.let { LocalDate.ofEpochDay(it) },
                    )
                }
            }
            reorder()
            updateTransferPreview()
            return
        }
        val entry = repository.findEntry(id) ?: return
        pinnedAccountId = entry.accountId
        // Reopening a drawdown must look like the form that created it: the
        // overdraft it came from is a fact to show, not a label to ask for.
        val drawnFromName = if (entry.loanId != null &&
            entry.isAdjustment && entry.direction == Direction.IN
        ) {
            loans.findLoan(entry.loanId)?.name
        } else {
            null
        }
        // And a purchase made on a card opens on that card. It names no account
        // — that is what makes it a card spend — so without this the form fell
        // back to whichever bank account happened to be first, said the money
        // had left there, and would have moved the purchase off the facility
        // still carrying it the moment Save was pressed.
        val spentFrom = entry.takeIf { it.isCardSpend }?.loanId
        // Whether this row is allowed to name no account at all — see
        // [AddEntryUiState.accountOptional]. A movement against a debt is,
        // because the card that recorded it offered exactly that answer; and so
        // is anything already saved without one, whatever it turns out to be.
        //
        // Off `loanId` rather than `belongsToLoan`, which is the wider test and
        // the wrong one to reach for here: it is read off the debt's *name*,
        // which the row this form loads does not carry. An instalment stores no
        // loan either way and is caught by the second half whenever it needs to
        // be — one made in cash has no account to have lost.
        val accountOptional = entry.loanId != null || entry.accountId == null
        _state.value = _state.value.copy(
            isEditing = true,
            existingDrawdownName = drawnFromName,
            existingCardName = spentFrom?.let { loans.findLoan(it)?.name },
            selectedCardId = spentFrom,
            direction = entry.direction,
            // Editing shows the raw number, not a formatted one — the user is
            // about to change it, and grouping separators get in the way.
            amountText = formatter.toPlainInput(entry.amount),
            // **Not recorded** is an answer here — see [accountOptional].
            accountOptional = accountOptional,
            // Nothing on a card spend, which is the whole of how one is known.
            // Left standing at the shortlist's default it would be a second
            // source alongside the card, and [save] would have to guess.
            selectedAccountId = when {
                spentFrom != null -> null
                // Where none is a real answer it is taken exactly as written,
                // with no falling back to whatever the shortlist had already
                // put there: the fallback is what turned "no account" into the
                // first bank on the row the moment the form was opened.
                accountOptional -> entry.accountId
                else -> entry.accountId ?: _state.value.selectedAccountId
            },
            currencyCode = entry.currencyCode,
            date = entry.occurredOn,
            note = entry.note.orEmpty(),
        )

        // Reopening a repeating entry must show it as repeating. Without this
        // the box comes back unticked, and saving would quietly detach the
        // entry from its rule.
        entry.seriesId?.let { seriesId ->
            // Unless the rule pays a loan. Then it is the loan's, not this
            // entry's: leaving it editable here would let one corrected
            // occurrence rewrite the whole schedule's dates and amount, and
            // unticking the box would stop instalments on a debt that still
            // owes them. The entry stays linked; only the controls are
            // withheld, and editingSeriesId stays null so saving touches the
            // entry alone.
            if (loans.findLoanBySeries(seriesId) != null) {
                _state.value = _state.value.copy(isLoanInstalment = true)
                return@let
            }
            // The statement's single-date correction: the rule's controls stay
            // its own, editingSeriesId stays null so saving touches this row
            // alone, and the save's copy keeps the row on its rule.
            if (editOccurrence) {
                _state.value = _state.value.copy(isSingleOccurrence = true)
                return@let
            }
            editingSeriesId = seriesId
            recurrence.findSeries(seriesId)?.let { series ->
                _state.value = _state.value.copy(
                    repeats = !series.isPaused,
                    hasSeries = true,
                    usesSelectedCalendar = series.usesSelectedCalendar,
                    interval = series.interval,
                    repeatUntil = series.endOn?.let { LocalDate.ofEpochDay(it) },
                )
            }
        }
        reorder()
    }

    /**
     * The row that actually gets edited when [requested] is tapped: for an
     * occurrence of a repeating rule, the rule's own anchor entry.
     *
     * A rule is one thing and its occurrences are its output. The form used to
     * show the tapped occurrence's date beside the *rule's* repeat settings, and
     * saving wrote both — so opening June's row to correct the amount silently
     * moved the whole rule's start to June, and the schedule marched forward a
     * month every time it was looked at. Meanwhile there was no way to reach the
     * rule at all from any row but the first, and nothing on the screen said
     * which row that was.
     *
     * So every occurrence opens the rule. The anchor is its earliest surviving
     * occurrence — the same one a *projected* row already opens, which is what
     * makes a tap mean the same thing whether the date has arrived or not.
     *
     * A **loan's** instalment is untouched: it opens as itself, with the repeat
     * controls withheld, because that schedule belongs to the debt and is
     * changed on the debt's own screen. So is a row whose rule has since been
     * deleted, which is simply an entry.
     */
    private suspend fun ruleAnchorFor(requested: String): String {
        // The statement's ask: this one date, not the rule. The rule's own
        // controls are withheld on the form for the same reason — see
        // [AddEntryUiState.isSingleOccurrence].
        if (editOccurrence) return requested
        val seriesId = repository.findEntry(requested)?.seriesId
            ?: repository.findTransfer(requested)?.seriesId
            ?: return requested
        if (loans.findLoanBySeries(seriesId) != null) return requested
        val anchor = repository.anchorEntryForSeries(seriesId) ?: return requested
        entryId = anchor
        return anchor
    }

    /**
     * Pays this movement from a card instead of an account.
     *
     * Exactly one source at a time, so choosing a card clears the account and
     * vice versa: money comes from one place, and a form showing two selected
     * chips would be asking the user which of its own answers it meant.
     */
    fun selectCard(id: String) {
        val card = _state.value.cards.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            selectedCardId = id,
            selectedAccountId = null,
            currencyCode = card?.currencyCode ?: _state.value.currencyCode,
            // A card purchase is one purchase. A rule that made one every month
            // would report a debt the user never took.
            repeats = false,
            error = EntryError.NONE,
        )
    }

    fun selectAccount(id: String) {
        val account = _state.value.accounts.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            selectedAccountId = id,
            selectedCardId = null,
            // Money moving through a USD account is in dollars — that is the
            // whole of the answer, which is why the form no longer asks it.
            currencyCode = account?.currencyCode ?: _state.value.currencyCode,
            error = EntryError.NONE,
        )
        afterEndChanged(id)
    }

    /**
     * Puts the answer back to none — see [AddEntryUiState.accountOptional].
     *
     * Offered only where none is a real answer, and it has to be *chosen* rather
     * than reached by tapping the selected chip again: a form whose answers turn
     * themselves off when touched twice makes a stray tap indistinguishable from
     * a decision, on a row that decides which balance the money moves.
     *
     * The currency is left exactly as it was. It came from the account the money
     * ran through, and money that ran through no account is still in whatever
     * currency it was in — a debt in dollars is settled in dollars whether or
     * not a bank was involved.
     */
    fun clearAccount() {
        _state.value = _state.value.copy(
            selectedAccountId = null,
            selectedCardId = null,
            error = EntryError.NONE,
        )
    }

    /**
     * The three things that follow whichever end was just answered: what the
     * amount is denominated in, whether the destination is still a different
     * account, and what the movement now converts to.
     */
    private fun afterEndChanged(id: String) {
        updateConvertedPreview()
        // The source cannot also be the destination.
        if (_state.value.toAccountId == id) {
            _state.value = _state.value.copy(toAccountId = null)
            autoSelectTransferDestination()
        }
        updateTransferPreview()
    }

    /**
     * Shows what the typed amount comes to in the display currency, so the user
     * sees the conversion before committing rather than being surprised by it
     * in the totals afterwards.
     */
    private fun updateConvertedPreview() = viewModelScope.launch {
        val current = _state.value
        if (current.currencyCode.equals(current.baseCurrencyCode, ignoreCase = true)) {
            _state.value = _state.value.copy(convertedPreview = null)
            return@launch
        }
        val typed = MoneyFormatter(CurrencyOption.byCode(current.currencyCode), grouping = settingsStore.grouping)
            .parse(current.amountText)
        if (typed == null || typed.minor <= 0L) {
            _state.value = _state.value.copy(convertedPreview = null)
            return@launch
        }
        val converted = exchangeRates.convert(
            amountMinor = typed.minor,
            from = current.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(current.currencyCode).minorUnits,
            base = current.baseCurrencyCode,
            baseMinorUnits = CurrencyOption.byCode(current.baseCurrencyCode).minorUnits,
        )
        _state.value = _state.value.copy(
            convertedPreview = if (converted.isExact) formatter.format(converted.amount) else null,
        )
    }

    /** Defaults to the account used last, which is nearly always the right one. */
    private fun autoSelectAccount() {
        val current = _state.value
        if (current.selectedAccountId != null) return
        // **Never over a row already on file.** A default is what a form offers
        // when nobody has answered yet; an entry being corrected has an answer,
        // and "none" is one of them. The account list arrives from a flow, so
        // this runs again every time it does — which is how a movement saved
        // against no account came back with a bank on it however carefully
        // [loadExisting] had cleared it.
        if (current.isEditing) return
        val remembered = lastUsedAccountId
        val pick = current.accountChips.firstOrNull { it.id == remembered }
            ?: current.accountChips.firstOrNull()
            ?: return
        _state.value = current.copy(
            selectedAccountId = pick.id,
            currencyCode = if (entryId == null) pick.currencyCode else current.currencyCode,
        )
        // What the form opened on leads its row for the rest of the session.
        if (pinnedAccountId == null) pinnedAccountId = pick.id
    }

    /**
     * Switches between money in, money out and a transfer.
     *
     * The transfer fields are kept rather than cleared when switching away, so
     * changing one's mind twice does not lose what was typed.
     */
    fun setTransferMode(enabled: Boolean) {
        _state.value = _state.value.copy(isTransfer = enabled, error = EntryError.NONE)
        if (enabled) {
            autoSelectTransferDestination()
            updateTransferPreview()
        }
    }

    fun selectToAccount(id: String) {
        val account = _state.value.accounts.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            toAccountId = id,
            error = EntryError.NONE,
        )
        // The amount is denominated at the paying end, so the destination moves
        // it only when the paying end has nothing to say about it.
        if (account != null && _state.value.selectedAccountId == null) {
            _state.value = _state.value.copy(currencyCode = account.currencyCode)
        }
        updateTransferPreview()
    }

    /** Picks the first account that is not the source, so the form starts valid. */
    private fun autoSelectTransferDestination() {
        val current = _state.value
        if (current.toAccountId != null && current.toAccountId != current.selectedAccountId) return
        val pick = current.accountChips.firstOrNull { it.id != current.selectedAccountId } ?: return
        _state.value = current.copy(toAccountId = pick.id)
    }

    /**
     * Shows what will actually arrive when the two ends hold different
     * currencies — $100 leaving Wise is not $100 arriving at a Nepali bank.
     *
     * Only between two accounts. A debt at either end is written as a single row
     * in the debt's own currency, so there is no second figure to preview — and
     * the save refuses the pairing rather than inventing one. See
     * [saveHoldingTransfer].
     */
    private fun updateTransferPreview() = viewModelScope.launch {
        val current = _state.value
        val from = current.selectedAccount
        val to = current.toAccount
        if (!current.isTransfer || from == null || to == null ||
            from.currencyCode.equals(to.currencyCode, ignoreCase = true)
        ) {
            _state.value = _state.value.copy(transferPreview = null)
            return@launch
        }
        val typed = MoneyFormatter(CurrencyOption.byCode(from.currencyCode), grouping = settingsStore.grouping)
            .parse(current.amountText)
        if (typed == null || typed.minor <= 0L) {
            _state.value = _state.value.copy(transferPreview = null)
            return@launch
        }
        val converted = exchangeRates.convert(
            amountMinor = typed.minor,
            from = from.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(from.currencyCode).minorUnits,
            base = to.currencyCode,
            baseMinorUnits = CurrencyOption.byCode(to.currencyCode).minorUnits,
        )
        _state.value = _state.value.copy(
            transferPreview = if (converted.isExact) {
                MoneyFormatter(CurrencyOption.byCode(to.currencyCode), grouping = settingsStore.grouping).format(converted.amount)
            } else {
                null
            },
        )
    }

    fun setDirection(direction: Direction) {
        val current = _state.value
        if (current.direction == direction) return
        _state.value = current.copy(direction = direction, error = EntryError.NONE)
    }

    fun setAmount(text: String) {
        // Accept digits and one decimal mark only; silently ignoring the rest
        // is less jarring than showing an error for every stray character.
        val filtered = text.filter { it.isDigit() || it == '.' || it == ',' }
        _state.value = _state.value.copy(amountText = filtered, error = EntryError.NONE)
        updateConvertedPreview()
        updateTransferPreview()
    }


    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(date = date)
    }

    fun setRepeats(enabled: Boolean) {
        _state.value = _state.value.copy(repeats = enabled)
    }

    /** Whether this rule counts its months in the calendar being read. */
    fun setUsesSelectedCalendar(enabled: Boolean) {
        _state.value = _state.value.copy(usesSelectedCalendar = enabled)
    }

    fun setInterval(interval: RecurrenceInterval) {
        _state.value = _state.value.copy(interval = interval)
    }

    fun setNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun save() = viewModelScope.launch {
        val current = _state.value
        // Asked of every movement, transfers included: the note is what the row
        // will be called, and a list of rows with nothing to call them is the
        // state this replaced.
        //
        // **Except of a movement the app named itself.** Money taken from a debt
        // and money spent on a card are already called after the arrangement
        // they belong to — the row reads "Taken from Dad" with nothing typed —
        // and those rows are opened here now, from the debt's own statement.
        // Demanding a note before the amount could be corrected would be the
        // form insisting on a field it did not ask for when the row was written.
        if (current.note.isBlank() && current.showsNote) {
            _state.value = current.copy(error = EntryError.NOTE)
            return@launch
        }
        if (current.isTransfer) {
            saveTransfer(current)
            return@launch
        }
        // Parse in the entry's own currency: a JPY amount has no decimals, and
        // parsing it with the display currency's rules would be off by 100.
        val amount = MoneyFormatter(CurrencyOption.byCode(current.currencyCode), grouping = settingsStore.grouping)
            .parse(current.amountText)
        if (amount == null || amount.minor <= 0L) {
            _state.value = current.copy(error = EntryError.AMOUNT)
            return@launch
        }
        // Spent straight from a card: the money never touches an account, the
        // facility owes it, and its headroom shrinks by the same figure. Written
        // by the debt rather than as an ordinary entry, because those are one
        // act — see [LoanRepository.spendOnCard].
        val card = current.selectedCardId
        val result = if (card != null) {
            loans.spendOnCard(
                loanId = card,
                amount = amount,
                date = current.date,
                note = current.note,
                // Correcting the purchase this form was opened on, rather than
                // recording a second one. Null while writing a new one, which is
                // every purchase typed from this screen.
                id = entryId,
            )
        } else {
            repository.saveEntry(
                id = entryId,
                amount = amount,
                currencyCode = current.currencyCode,
                direction = current.direction,
                occurredOn = current.date,
                accountId = current.selectedAccountId,
                note = current.note,
            )
        }
        if (result is SaveResult.OverLimit) {
            _state.value = current.copy(error = EntryError.OVER_LIMIT)
            return@launch
        }
        // The debt would not let this figure move — see [SaveResult]. Said in
        // the same words the swipe is refused in, and nothing is written.
        if (result is SaveResult.LaterPaymentFirst) {
            _state.value = current.copy(deleteBlocked = true)
            return@launch
        }
        if (card != null) {
            _state.value = _state.value.copy(isSaved = true)
            return@launch
        }
        when (result) {
            is SaveResult.Success -> {
                lastUsedAccountId = current.selectedAccountId
                // The entry the user just typed is a real, confirmed one; the
                // rule starts from the *next* occurrence, so turning on repeat
                // never silently duplicates what was just saved.
                val savedEntryId = result.id
                val seriesId = editingSeriesId
                if (current.repeats) {
                    val newSeriesId = recurrence.saveSeries(
                        id = seriesId,
                        amount = amount,
                        currencyCode = current.currencyCode,
                        direction = current.direction,
                        interval = current.interval,
                        startOn = current.date,
                        endOn = current.repeatUntil,
                        accountId = current.selectedAccountId,
                        note = current.note.trim().takeIf { it.isNotEmpty() },
                        // Only a brand-new rule needs its first occurrence
                        // suppressed; editing one must not skip a month.
                        firstOccurrenceAlreadyRecorded = seriesId == null,
                        usesSelectedCalendar = current.usesSelectedCalendar,
                    )
                    // Link the entry to its rule so reopening shows it as
                    // recurring instead of silently offering to make another.
                    repository.linkEntryToSeries(savedEntryId, newSeriesId)
                    editingSeriesId = newSeriesId
                } else if (seriesId != null) {
                    // Unticking the box stops future occurrences. Entries
                    // already confirmed stay — they happened.
                    recurrence.deleteSeries(seriesId)
                    repository.linkEntryToSeries(savedEntryId, null)
                    editingSeriesId = null
                }
                _state.value = current.copy(isSaved = true)
            }
            else -> _state.value = current.copy(error = EntryError.AMOUNT)
        }
    }

    /**
     * Saves a transfer, and the rule behind it when this one repeats.
     *
     * The rule is written *before* the pair of entries so that the entries can
     * carry its id from the start. Linking them afterwards would leave a window
     * in which the movement looked like a one-off, and reopening it in that state
     * would have offered to create a second rule.
     */
    private suspend fun saveTransfer(current: AddEntryUiState) {
        // A transfer *out of a card* is a cash advance: money drawn against the
        // facility and landing in an account. The app already has that movement
        // — it is what the card's own screen calls drawing on it — so this is
        // the same act reached from the form the user was already on, and not a
        // second kind of row. Unlike a purchase it is an **adjustment**: the
        // money arrived somewhere, so a month lived on the card must not read as
        // an expensive one until it is actually spent.
        val card = current.selectedCard
        if (card != null) {
            val landsIn = current.toAccount
            if (landsIn == null) {
                _state.value = current.copy(error = EntryError.TRANSFER_ACCOUNTS)
                return
            }
            val drawn = MoneyFormatter(CurrencyOption.byCode(card.currencyCode), grouping = settingsStore.grouping)
                .parse(current.amountText)
            if (drawn == null || drawn.minor <= 0L) {
                _state.value = current.copy(error = EntryError.AMOUNT)
                return
            }
            val available = card.available
            if (available != null && drawn.minor > available.minor) {
                _state.value = current.copy(error = EntryError.OVER_LIMIT)
                return
            }
            loans.drawFromOverdraft(
                loanId = card.id,
                amount = drawn,
                intoAccountId = landsIn.id,
                date = current.date,
                note = current.note,
            )
            _state.value = _state.value.copy(isSaved = true)
            return
        }
        // One date of an arrangement's own schedule. Its two ends came off the
        // rule and were never re-asked — the far end is the arrangement itself,
        // which this form's account list deliberately does not hold, so looking
        // either up in it would refuse a save the user was invited to make. The
        // ids the occurrence was loaded with are the answer, and the legs keep
        // naming the arrangement's rule.
        if (current.isPlanPayment) {
            val fromId = current.selectedAccountId
            val toId = current.toAccountId
            if (fromId == null || toId == null) {
                _state.value = current.copy(error = EntryError.TRANSFER_ACCOUNTS)
                return
            }
            val paid = MoneyFormatter(
                CurrencyOption.byCode(current.currencyCode),
                grouping = settingsStore.grouping,
            ).parse(current.amountText)
            if (paid == null || paid.minor <= 0L) {
                _state.value = current.copy(error = EntryError.AMOUNT)
                return
            }
            val result = repository.saveTransfer(
                transferId = current.transferId,
                fromAccountId = fromId,
                toAccountId = toId,
                amount = paid,
                occurredOn = current.date,
                note = current.note,
                seriesId = planSeriesId,
            )
            _state.value = if (result is SaveResult.Success) {
                current.copy(isSaved = true)
            } else {
                current.copy(error = EntryError.AMOUNT)
            }
            return
        }
        val from = current.selectedAccount
        val to = current.toAccount
        if (from == null || to == null || from.id == to.id) {
            _state.value = current.copy(error = EntryError.TRANSFER_ACCOUNTS)
            return
        }
        val amount = MoneyFormatter(CurrencyOption.byCode(from.currencyCode), grouping = settingsStore.grouping)
            .parse(current.amountText)
        if (amount == null || amount.minor <= 0L) {
            _state.value = current.copy(error = EntryError.AMOUNT)
            return
        }
        // Refused rather than written at 1:1. What lands in the other account is
        // computed from the rate, and with no rate on file the two halves would
        // be recorded as the same number — रू 1,000 leaving one account and
        // $1,000 arriving in the other, as fact, in both balances and in the
        // timeline. A movement the app cannot value is one it must not record.
        if (!from.currencyCode.equals(to.currencyCode, ignoreCase = true) &&
            exchangeRates.rate(from.currencyCode, to.currencyCode) == null
        ) {
            _state.value = current.copy(error = EntryError.NO_RATE)
            return
        }

        val seriesId = if (current.repeats) {
            recurrence.saveSeries(
                id = editingSeriesId,
                amount = amount,
                currencyCode = from.currencyCode,
                direction = Direction.OUT,
                interval = current.interval,
                startOn = current.date,
                endOn = current.repeatUntil,
                accountId = from.id,
                note = current.note.trim().takeIf { it.isNotEmpty() },
                isAdjustment = true,
                transferToAccountId = to.id,
                firstOccurrenceAlreadyRecorded = editingSeriesId == null,
                usesSelectedCalendar = current.usesSelectedCalendar,
            )
        } else {
            editingSeriesId?.let { recurrence.deleteSeries(it) }
            // A rule this form was not editing survives the save untouched —
            // the repeat box was never drawn for it — and the corrected legs
            // keep naming it: an arrangement's own schedule, or the rule behind
            // the one date being corrected from a statement.
            planSeriesId ?: occurrenceSeriesId
        }
        editingSeriesId = seriesId
            .takeIf { planSeriesId == null && occurrenceSeriesId == null }

        val result = repository.saveTransfer(
            transferId = current.transferId,
            fromAccountId = from.id,
            toAccountId = to.id,
            amount = amount,
            occurredOn = current.date,
            note = current.note,
            seriesId = seriesId,
        )
        _state.value = when (result) {
            is SaveResult.Success -> {
                lastUsedAccountId = from.id
                current.copy(isSaved = true)
            }
            SaveResult.AccountRequired -> current.copy(error = EntryError.TRANSFER_ACCOUNTS)
            else -> current.copy(error = EntryError.AMOUNT)
        }
    }

    /**
     * Removes what this form is about — which for a repeating payment is the
     * whole arrangement, not one date of it.
     *
     * The two ways to delete now mean two different things, deliberately. A
     * **swipe** on the timeline or on Reminders acts on the row it is under: one
     * date, this month's, and the rule carries on. Opening the entry and using
     * the bin acts on what the form is showing — and the form is showing the
     * *rule*, not an occurrence: every date opens its anchor (see
     * [ruleAnchorFor]), the repeat controls are its own, and saving here
     * rewrites every occurrence still to come. Removing one date from a screen
     * that edits all of them was the odd answer.
     *
     * So every occurrence goes, the ones already recorded included, and the rule
     * with them. One at a time and through the ordinary door, so each puts back
     * whatever it moved — a balance, a debt's principal, a goal's length — and
     * oldest first, because that door refuses a lump sum while a later one is
     * still on file and walking forwards never meets that.
     *
     * The one answer that is not "gone" keeps the form open: closing it on a
     * refusal would leave the user looking at a row they had just been told they
     * could not remove.
     */
    fun delete() = viewModelScope.launch {
        val id = entryId ?: return@launch
        val ids = editingSeriesId
            ?.let { repository.entriesForSeries(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(id)
        for (each in ids) {
            when (repository.deleteEntry(each)) {
                EntryDeletion.Done -> Unit
                EntryDeletion.LaterPaymentFirst -> {
                    _state.value = _state.value.copy(deleteBlocked = true)
                    return@launch
                }
            }
        }
        // The rule last: it is what would otherwise generate the dates again on
        // the next launch, and stopping it before its rows are gone would leave
        // `materialiseDue` writing replacements for the ones being removed.
        editingSeriesId?.let { recurrence.deleteSeries(it) }
        _state.value = _state.value.copy(isDeleted = true)
    }

    fun dismissDeleteBlocked() {
        _state.value = _state.value.copy(deleteBlocked = false)
    }
}
