package com.mywallet.data.repo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mywallet.core.money.Money
import com.mywallet.core.money.CurrencyOption
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.dao.RecurringSeriesDao
import com.mywallet.data.db.dao.MoneyEntryRow
import com.mywallet.data.db.dao.HoldingTotal
import com.mywallet.data.db.dao.HoldingUseRow
import com.mywallet.data.db.dao.RateChangeDao
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.fx.Converted
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Account
import com.mywallet.domain.AccountMovement
import com.mywallet.domain.AccountWithBalance
import com.mywallet.domain.DayGroup
import com.mywallet.domain.HoldingBreakdown
import com.mywallet.domain.MoneyEntry
import com.mywallet.domain.PeriodSummary
import com.mywallet.domain.Shortlist
import com.mywallet.domain.payableHoldings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One transfer, as the form needs to show it. */
data class TransferDetail(
    val transferId: String,
    val fromAccountId: String?,
    val toAccountId: String?,
    val amount: Money,
    val currencyCode: String,
    val occurredOn: LocalDate,
    val note: String?,
    val seriesId: String?,
)

/**
 * What removing one row did.
 *
 * A delete is not always only a delete. A row that moved a debt's own stored
 * figure has to put it back, and there is one case the app refuses rather than
 * answers approximately — see [Reversal].
 */
sealed interface EntryDeletion {
    /** Gone, along with everything it caused. */
    data object Done : EntryDeletion

    /** A lump sum with a later one still on file. See [Reversal.LaterPaymentFirst]. */
    data object LaterPaymentFirst : EntryDeletion
}

/** Why a save was rejected, so the UI can point at the right field. */
/**
 * What the practice row is worth.
 *
 * Small enough to be obviously not the user's own spending, and not zero: an
 * entry of nothing is refused by [WalletRepository.saveEntry], which is the same
 * check that stops a real one being saved blank.
 */
private const val PRACTICE_ENTRY_MINOR = 100L

/** The dot beside the demo holding. Grey, so it claims none of the holding colours. */
private val DEMO_ACCOUNT_COLOR = Color(0xFF8A8F98)

sealed interface SaveResult {
    data class Success(val id: String) : SaveResult
    data object AmountRequired : SaveResult
    data object NameRequired : SaveResult
    data object AccountRequired : SaveResult

    /**
     * More than the card has left to draw against.
     *
     * Refused rather than clamped: a purchase the card would have declined is
     * not a purchase, and silently writing a smaller one would be the app
     * deciding what the user bought.
     */
    data object OverLimit : SaveResult
}

/**
 * The single door between the UI and storage.
 *
 * Everything the app shows — home, timeline, breakdowns — reads from the two
 * tables behind this class. Features added later (bills, goals, loans) become
 * new *kinds* of entry rather than new stores.
 */
@Singleton
class WalletRepository @Inject constructor(
    private val entryDao: MoneyEntryDao,
    private val accountDao: AccountDao,
    private val seriesDao: RecurringSeriesDao,
    private val loanDao: LoanDao,
    private val rateChangeDao: RateChangeDao,
    private val exchangeRates: ExchangeRateRepository,
    private val transfers: TransferRecorder,
    // The repositories and not the DAOs: putting a debt back where a deleted
    // payment found it is arithmetic about schedules, and stopping a rule means
    // discarding what it has already produced. Both are safe to depend on —
    // nothing either of them needs comes back to this class.
    private val loans: LoanRepository,
    private val recurrence: RecurrenceRepository,
    // A goal's length is derived from what is in it, so anything that moves one
    // has to work it out again — including a delete. Its own card cannot be
    // reached from here (PlanRepository depends on this class), which is why the
    // arithmetic lives in a class of its own.
    private val goalTerms: GoalTermKeeper,
    // Interest is derived from the movements, so anything that writes one into a
    // day a period was already counted over has to ask for it again. It used to
    // be worked out at launch and nowhere else, which is why an account could
    // hold a year of back-dated movements and no interest at all.
    private val interest: InterestRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {


    // --------------------------------------------------------------- entries

    fun observeEntries(start: LocalDate, endExclusive: LocalDate): Flow<List<MoneyEntry>> =
        entryDao.observeBetween(start.toEpochDay(), endExclusive.toEpochDay())
            .withLoanNames()

    /**
     * Names the loan behind each row that belongs to one, the way projections
     * already do. The day an instalment comes due it turns from a projection
     * into a real row, and the row must not change what it calls itself in the
     * process.
     *
     * Every row that belongs to a loan is named — through the loan's own rule,
     * or through the loan it points at directly. Which *kind* of row it is,
     * and therefore how it is drawn, the row itself decides: only one that came
     * from the rule is an instalment, and [MoneyEntry] separates the rest into
     * additions and payments aimed at one half of the debt.
     *
     * Naming used to stop at instalments and overdraft draws, which left money
     * lent to a person reading as an ordinary payment out, and a lump sum off
     * a debt reading as money simply spent — which neither of them is allowed to
     * carry in the first place.
     */
    private fun Flow<List<MoneyEntryRow>>.withLoanNames(): Flow<List<MoneyEntry>> =
        combine(loanDao.observeAll()) { rows, loans ->
            val bySeries = loans.filter { it.seriesId != null }.associateBy { it.seriesId }
            val byId = loans.associateBy { it.id }
            rows.map { row ->
                val loan = row.entry.seriesId?.let { bySeries[it] }
                    ?: row.entry.loanId?.let { byId[it] }
                row.toDomain(
                    loanName = loan?.name,
                    loanKind = loan?.kind,
                    belongsToLoanId = loan?.id,
                )
            }
        }

    fun observeSummary(start: LocalDate, endExclusive: LocalDate): Flow<PeriodSummary> =
        entryDao.observeTotals(start.toEpochDay(), endExclusive.toEpochDay()).map { totals ->
            PeriodSummary(
                moneyIn = totals.firstOrNull { it.direction == Direction.IN }
                    ?.let { Money(it.totalMinor) } ?: Money.ZERO,
                moneyOut = totals.firstOrNull { it.direction == Direction.OUT }
                    ?.let { Money(it.totalMinor) } ?: Money.ZERO,
            )
        }

    fun observeBreakdown(
        direction: Direction,
        start: LocalDate,
        endExclusive: LocalDate,
    ): Flow<List<HoldingBreakdown>> =
        entryDao.observeTotalsByAccount(direction, start.toEpochDay(), endExclusive.toEpochDay())
            .map { rows -> rows.toBreakdown() }

    suspend fun findEntry(id: String): MoneyEntry? = entryDao.findById(id)?.toDomain()

    /**
     * Saves money in or out.
     *
     * The conversion into the display currency happens here, once, and both the
     * converted figure and the rate used are stored. That is what keeps a
     * closed month closed: a $10 charge entered in March keeps March's rate
     * forever instead of quietly re-valuing itself every time the rate moves.
     */
    suspend fun saveEntry(
        id: String?,
        amount: Money,
        currencyCode: String,
        direction: Direction,
        occurredOn: LocalDate,
        accountId: String?,
        note: String?,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired

        val baseCode = settings.settings.first().currencyCode
        val existing = id?.let { entryDao.findById(it) }

        val isAdjustment = existing?.isAdjustment == true

        // Editing an entry whose currency and amount are unchanged must not
        // re-fetch a rate — that would silently re-value an old entry, which is
        // exactly what locking the rate is meant to prevent.
        val reuseRate = existing != null &&
            existing.currencyCode.equals(currencyCode, ignoreCase = true) &&
            existing.baseCurrencyCode.equals(baseCode, ignoreCase = true) &&
            existing.amountMinor == amount.minor

        val converted = if (reuseRate) {
            Converted(Money(existing.baseAmountMinor), existing.rateToBase, isExact = true)
        } else {
            exchangeRates.convert(
                amountMinor = amount.minor,
                from = currencyCode,
                fromMinorUnits = CurrencyOption.byCode(currencyCode).minorUnits,
                base = baseCode,
                baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
            )
        }

        val now = clock.nowMillis()
        val entity = existing?.copy(
            amountMinor = amount.minor,
            currencyCode = currencyCode.uppercase(),
            baseAmountMinor = converted.amount.minor,
            rateToBase = converted.rate,
            baseCurrencyCode = baseCode,
            direction = direction,
            occurredOn = occurredOn.toEpochDay(),
            accountId = accountId,
            // Saving an edit takes the row off the rule. It was the rule's own
            // words until now and the rule was free to rewrite it; a user who
            // has just corrected the date or the amount has stated what really
            // happened, and the next edit to the rule must not throw that away.
            status = EntryStatus.CONFIRMED,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = now,
        ) ?: MoneyEntryEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amount.minor,
            currencyCode = currencyCode.uppercase(),
            baseAmountMinor = converted.amount.minor,
            rateToBase = converted.rate,
            baseCurrencyCode = baseCode,
            direction = direction,
            occurredOn = occurredOn.toEpochDay(),
            accountId = accountId,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
        entryDao.upsert(entity)
        // Money the user has dated into a period the bank has already paid on
        // changes what that period earned. The earlier of the two days, because
        // an entry dragged forward out of a closed period changes it just as much
        // as one dropped into it.
        interest.repostIfBefore(
            minOf(occurredOn, existing?.let { LocalDate.ofEpochDay(it.occurredOn) } ?: occurredOn)
        )
        // Here rather than at the caller, so no path can forget it. Money now
        // reaches a goal from the entry form as well as from the goal's own
        // card, and a length that did not follow would go on promising a day the
        // saving had already beaten. Both ends of an edit, because moving an
        // entry off one holding and onto another changes what each of them holds.
        setOfNotNull(entity.accountId, existing?.accountId).forEach { goalTerms.reterm(it) }
        return SaveResult.Success(entity.id)
    }

    // ------------------------------------------------------------- transfers

    /**
     * Moves money between two accounts. See [TransferRecorder] for why this is
     * two entries and why neither is income or spending.
     */
    suspend fun saveTransfer(
        transferId: String?,
        fromAccountId: String,
        toAccountId: String,
        amount: Money,
        occurredOn: LocalDate,
        note: String?,
        seriesId: String? = null,
    ): SaveResult = transfers.record(
        transferId = transferId,
        fromAccountId = fromAccountId,
        toAccountId = toAccountId,
        amount = amount,
        date = occurredOn,
        note = note,
        seriesId = seriesId,
    ).also {
        interest.repostIfBefore(occurredOn)
        // Either end may be a goal — money moved into one from the bank, or back
        // out of it — and both have to feel it. See [GoalTermKeeper].
        goalTerms.reterm(fromAccountId)
        goalTerms.reterm(toAccountId)
    }

    /** The whole movement behind one of its two rows, for the editor. */
    suspend fun findTransfer(entryId: String): TransferDetail? {
        val entry = entryDao.findById(entryId) ?: return null
        val transferId = entry.transferId ?: return null
        val legs = entryDao.entriesForTransfer(transferId)
        val out = legs.firstOrNull { it.direction == Direction.OUT } ?: return null
        val into = legs.firstOrNull { it.direction == Direction.IN } ?: return null
        return TransferDetail(
            transferId = transferId,
            fromAccountId = out.accountId,
            toAccountId = into.accountId,
            // The amount the user typed was what left, so that is what the form
            // must show — not the converted figure that arrived.
            amount = Money(out.amountMinor),
            currencyCode = out.currencyCode,
            occurredOn = LocalDate.ofEpochDay(out.occurredOn),
            note = out.note,
            seriesId = out.seriesId,
        )
    }

    // -------------------------------------------------------------- accounts

    fun observeAccounts(): Flow<List<Account>> =
        accountDao.observeActive().map { list -> list.map { it.toDomain() } }

    /**
     * Accounts with balances, each also converted to the display currency.
     *
     * Conversion uses *current* rates rather than the locked per-entry ones: a
     * balance is what the money is worth now, not what it was worth when each
     * transaction happened.
     *
     * @param asOf the day to count movements up to, for a page asking about a
     *   month that has been and gone. Today by default, which is what every
     *   list of what the user has means by a balance. The conversion is still
     *   today's rate — there is only one rate on file, and a month's balance
     *   restated at some invented historical rate would be a worse answer than
     *   an honest one at the rate the app actually knows.
     */
    fun observeAccountBalances(asOf: LocalDate? = null): Flow<List<AccountWithBalance>> =
        combine(
            accountDao.observeActive(),
            accountDao.observeBalances((asOf ?: clock.today()).toEpochDay()),
            settings.settings,
        ) { accounts, balances, appSettings ->
            Triple(accounts, balances, appSettings.currencyCode)
        }.map { (accounts, balances, baseCode) ->
            val byId = balances.associateBy { it.accountId }
            accounts.map { account ->
                val domain = account.toDomain()
                // A fixed deposit needs no special case here. Nothing may touch
                // it, so no entry ever names it and this falls through to the
                // opening balance — the money the user put in, which is the
                // money they have. It is locked, not gone, so it belongs in the
                // row and in the total like anything else they own.
                //
                // The interest is deliberately absent until the deposit comes
                // free. It is not theirs to spend before then, and a balance
                // creeping up day by day was a savings account wearing a
                // deposit's name.
                val raw = byId[account.id]?.balanceMinor
                    ?: account.openingBalanceMinor
                val inBase = if (account.currencyCode.equals(baseCode, ignoreCase = true)) {
                    Money(raw)
                } else {
                    val converted = exchangeRates.convert(
                        amountMinor = raw,
                        from = account.currencyCode,
                        fromMinorUnits = CurrencyOption.byCode(account.currencyCode).minorUnits,
                        base = baseCode,
                        baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
                    )
                    if (converted.isExact) converted.amount else null
                }
                AccountWithBalance(domain, Money(raw), inBase)
            }
        }

    suspend fun findAccount(id: String): Account? = accountDao.findById(id)?.toDomain()

    /**
     * The currencies this user's holdings are denominated in, the one most of
     * them are in first — what a form offers before it offers all seventeen.
     * Recency breaks the ties only; see `CurrencyUseRow`.
     */
    suspend fun currenciesInUse(): List<String> =
        accountDao.currenciesInUse().map { it.code }

    /**
     * How much each account has been used, for the few chips a form puts in
     * front of the rest. See [HoldingUseRow] for why this is counted from the
     * movements rather than kept as a tally of taps.
     */
    suspend fun accountsByUse(): List<Shortlist.Use> =
        accountDao.byUse().map { Shortlist.Use(it.id, it.uses, it.lastOn) }

    /**
     * Everything that has happened to one account so far, newest first, each row
     * with what the balance stood at once it had.
     *
     * The balances elsewhere in the app are totals; this is the working. Without
     * it an account that reads less than the user expects has nothing behind it
     * to check — the number is simply wrong and there is nowhere to look. It was
     * a back-dated loan charge that made that concrete: रू 10,984.93 left a
     * savings account holding रू 10,000 and the only evidence was the minus sign.
     *
     * Stops at today, because the balance it explains does — see
     * [MoneyEntryDao.forAccount]. Each row carries the whole entry rather than a
     * title worked out here, so the statement names a movement in the same words
     * the timeline does; see `entryTitle`.
     */
    suspend fun statementFor(accountId: String): List<AccountMovement> {
        val account = accountDao.findById(accountId) ?: return emptyList()
        val loans = loanDao.observeAll().first()
        val bySeries = loans.filter { it.seriesId != null }.associateBy { it.seriesId }
        val byId = loans.associateBy { it.id }

        var running = account.openingBalanceMinor
        return entryDao.forAccount(accountId, clock.today().toEpochDay()).map { row ->
            val entry = row.entry
            val signed = if (entry.direction == Direction.IN) {
                entry.amountMinor
            } else {
                -entry.amountMinor
            }
            running += signed
            val instalmentOf = entry.seriesId?.let { bySeries[it] }
            val loan = instalmentOf ?: entry.loanId?.let { byId[it] }
            AccountMovement(
                entry = row.toDomain(
                    loanName = loan?.name,
                    loanKind = loan?.kind,
                    belongsToLoanId = loan?.id,
                ),
                balanceAfter = Money(running),
                fromLoanSchedule = instalmentOf != null,
            )
            // Newest first, like every other list of what happened in the app.
        }.reversed()
    }

    /**
     * Works every entry's converted figure out again, in the currency totals are
     * now read in.
     *
     * Each row keeps two amounts: what was actually paid, in the currency it was
     * paid in, and what that came to in the display currency at the time. The
     * second is frozen on purpose — a $10 charge from March keeps March's rate,
     * so a closed month stays closed — but it is only *usable* while it still
     * names the currency being displayed. The moment the user switches to
     * dollars, every stored figure is rupees, and drawing it under a dollar sign
     * states a number that is wrong by a factor of a hundred and forty.
     *
     * So the switch restates them. It converts from each row's **own** amount
     * rather than from the frozen figure — that is the fact, and going through
     * the old conversion would round twice — and it happens once, on the change,
     * rather than on every read: the month totals and the breakdown are
     * summed in SQL, and a sum cannot convert its rows one at a time.
     *
     * At today's rate for every row, which is the only rate the app has. That is
     * a real loss of precision on old entries and it is the honest one available:
     * the alternative is showing March's rupees as dollars.
     *
     * A row whose currency has no rate on file is left exactly as it was. A
     * figure the app cannot convert is one it must not invent.
     */
    /**
     * Whether this phone has anything of the user's on it.
     *
     * Asked once, at launch, before anything else runs — it decides whether the
     * opening questions, the hints and the lock offer are owed. Every holding
     * counts now: nothing is seeded any more, so the first account on the phone
     * is one the user made, and an app that has one is an app that has been
     * used. It counted from *two* while a Cash account was created on first run,
     * or everybody would have looked like they had a history before they had
     * done anything at all.
     */
    suspend fun hasHistory(): Boolean =
        accountDao.count() > 0 || entryDao.count() > 0 || loanDao.count() > 0

    /**
     * Whether the user has a holding of their own, and whether anything has
     * been recorded — asked at the moment a lesson is about to write the row it
     * teaches on.
     *
     * The opening decides what it owes when the questions are answered, and the
     * user may be several minutes and several accounts past that by the time
     * they walk onto the tab it owes something to: after Start over they land on
     * Accounts, add two banks, open the Timeline — and the app would demonstrate
     * deleting a row on a page that is already theirs. A lesson taught on a real
     * row it invented is only honest on a page with nothing in it.
     */
    suspend fun hasAnyHolding(): Boolean = accountDao.count() > 0 || loanDao.count() > 0

    suspend fun hasAnyEntry(): Boolean = entryDao.count() > 0

    /**
     * Writes the one row the swipe lesson is taught on, and answers with its id.
     *
     * A real entry, filed the way any other is: the gesture being taught is the
     * one that deletes a payment, and a row drawn to look like one would have to
     * be special-cased by every list it appeared in — and could not be swiped
     * away by the very code the lesson is about.
     *
     * Small, dated today so it lands in the month the timeline opens on, and put
     * against the first holding money can actually move through — or against
     * none, which is a shape the app already has.
     */
    suspend fun addPracticeEntry(note: String): String? {
        // Whichever holding money can move through, and none is a real answer:
        // nothing is seeded any more, so a phone can genuinely have no account
        // when this is asked — the demo holding the accounts lesson opened has
        // usually just been swiped away. An entry naming no holding is a shape
        // the app already has, and the row it draws is the row being taught on.
        val account = accountDao.observeActive().first()
            .map { it.toDomain() }
            .payableHoldings()
            .firstOrNull()
        val base = settings.settings.first().currencyCode
        // Null, and the id comes back out: a new row is given one here rather
        // than taking the one it was called with, so an id made up by the caller
        // would name a row that was never written — and the lesson, which ends
        // when its row goes, would be over before it started.
        val saved = saveEntry(
            id = null,
            amount = Money(PRACTICE_ENTRY_MINOR),
            currencyCode = account?.currencyCode ?: base,
            direction = Direction.OUT,
            occurredOn = clock.today(),
            accountId = account?.id,
            note = note,
        )
        return (saved as? SaveResult.Success)?.id
    }

    /**
     * Takes the practice row back, if it is still there.
     *
     * The same door a swipe goes through, so a lesson skipped and a lesson
     * finished leave the figures in exactly the same place — and a row already
     * swiped away is simply not found, which is the ordinary outcome.
     */
    suspend fun removePracticeEntry(id: String) {
        if (entryDao.findById(id) == null) return
        deleteEntry(id)
    }

    /** Whether the practice row is still on file. See `MoneyEntryDao.observeExists`. */
    fun observeEntryExists(id: String): Flow<Boolean> =
        entryDao.observeExists(id).map { it > 0 }

    /**
     * The holding the accounts lesson is taught on, and its id.
     *
     * A real bank account for the reason the practice entry is a real entry:
     * the gesture being taught is the one that removes a holding, and the row
     * has to be the row that gesture works on. Named for what it is, opened at
     * nothing, and in the currency everything else is read in.
     */
    suspend fun addDemoAccount(name: String): String? {
        // Anything left of a lesson that was never finished goes first. A phone
        // killed part way through the opening keeps its demo holding, and the
        // next launch would open a second one beside it — so the sweep is here,
        // at the one moment another is about to be written, rather than as a
        // launch job that would go hunting through a working database.
        //
        // Narrow on purpose: the same name, the same kind, and nothing has ever
        // touched it. A holding with a movement against it is somebody's own,
        // whatever it is called.
        accountDao.activeNamed(name, AccountKind.SAVINGS)
            .filter { it.openingBalanceMinor == 0L && entryDao.countForAccount(it.id) == 0 }
            .forEach { deleteAccount(it.id) }
        val base = settings.settings.first().currencyCode
        val saved = saveAccount(
            id = null,
            name = name,
            kind = AccountKind.SAVINGS,
            currencyCode = base,
            institution = name,
            openingBalance = Money.ZERO,
            color = DEMO_ACCOUNT_COLOR,
            showInDisplayCurrency = false,
        )
        return (saved as? SaveResult.Success)?.id
    }

    /** Takes the demo holding back, if the swipe has not already. */
    suspend fun removeDemoAccount(id: String) {
        if (accountDao.findById(id) == null) return
        deleteAccount(id)
    }

    /** Whether the demo holding is still on file. */
    fun observeAccountExists(id: String): Flow<Boolean> =
        accountDao.observeExists(id).map { it > 0 }

    suspend fun restateBaseCurrency(code: String) {
        val base = code.uppercase()
        val stale = entryDao.withOtherBaseCurrency(base)
        if (stale.isEmpty()) return
        val baseUnits = CurrencyOption.byCode(base).minorUnits
        val now = clock.nowMillis()
        val restated = stale.mapNotNull { entry ->
            val converted = exchangeRates.convert(
                amountMinor = entry.amountMinor,
                from = entry.currencyCode,
                fromMinorUnits = CurrencyOption.byCode(entry.currencyCode).minorUnits,
                base = base,
                baseMinorUnits = baseUnits,
            )
            if (!converted.isExact) return@mapNotNull null
            entry.copy(
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = base,
                // Bumped, because the row really did change: a backup taken
                // afterwards has to win over the copy on another device.
                updatedAt = now,
            )
        }
        if (restated.isNotEmpty()) entryDao.upsertAll(restated)
    }

    suspend fun saveAccount(
        id: String?,
        name: String,
        kind: AccountKind,
        currencyCode: String,
        institution: String?,
        openingBalance: Money,
        color: Color,
        showInDisplayCurrency: Boolean,
        /** What the bank pays on it, when the user has said. Null earns nothing. */
        annualRate: Double? = null,
        /**
         * How often the bank credits that interest, in Nepali months.
         *
         * Asked beside the rate on the one kind of holding that earns period by
         * period, and settled the moment a period has actually been credited —
         * the form stops offering it, and what arrives here is then the figure
         * that is already stored. Null on everything else, which is what it
         * means to have no interest periods at all.
         */
        interestPayoutMonths: Int? = null,
        /**
         * Whether this bank cuts those periods into Nepali months.
         *
         * The account's own opt-in and nothing more: what the periods are
         * actually counted in is this *and* the calendar being read — see
         * [com.mywallet.core.date.CalendarSystem.forInterest]. Kept beside the
         * interval because they are one question about the same bank.
         */
        interestInBs: Boolean = false,
        /**
         * A fixed deposit's terms. All together or all null: they describe one
         * arrangement, and a deposit missing any of them has no balance the app
         * can work out and no day on which to hand the money back.
         */
        depositStartedOn: LocalDate? = null,
        depositTermMonths: Int? = null,
        maturesIntoAccountId: String? = null,
        /**
         * What the arrangement is aimed at, and what each payment towards it
         * costs. Written only on a holding that has a plan behind it — a policy
         * or a goal.
         *
         * The rule that actually moves the money is not written here — it cannot
         * be, since it has to point at an account that does not exist until this
         * returns. [PlanRepository] writes it afterwards and links the two.
         */
        maturityAmount: Money? = null,
        perPayment: Money? = null,
        premiumEveryMonths: Int? = null,
        planRecurInBs: Boolean = false,
    ): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.NameRequired
        val now = clock.nowMillis()
        val existing = id?.let { accountDao.findById(it) }
        // Carried only on a holding with a term: a deposit, a policy, a goal.
        // Anything else that had them would be a row whose kind was changed
        // underneath it, and a stray maturity date on a savings account would
        // project money leaving it that never leaves.
        val hasTerm = kind == AccountKind.FIXED_DEPOSIT || kind == AccountKind.INSURANCE ||
            kind == AccountKind.GOAL
        val hasPlan = kind == AccountKind.INSURANCE || kind == AccountKind.GOAL
        // The only two kinds the bank pays period by period. A deposit has a rate
        // and no periods — it earns simple interest across its whole term — and
        // an interval left on one changed from savings would credit it twice.
        val hasPeriods = kind == AccountKind.SAVINGS || kind == AccountKind.CURRENT
        val entity = existing?.copy(
            name = trimmed,
            kind = kind,
            currencyCode = currencyCode,
            institution = institution?.trim()?.takeIf { it.isNotEmpty() },
            openingBalanceMinor = openingBalance.minor,
            colorArgb = color.toArgb(),
            showInDisplayCurrency = showInDisplayCurrency,
            // The rate it opened at. Once the bank moves it the new figure lives
            // in its own dated row and this one stops changing — see
            // [InterestRepository].
            annualRate = annualRate,
            interestPayoutMonths = if (hasPeriods) interestPayoutMonths else null,
            interestInBs = hasPeriods && interestInBs,
            depositStartedOn = if (hasTerm) depositStartedOn?.toEpochDay() else null,
            depositTermMonths = if (hasTerm) depositTermMonths else null,
            maturesIntoAccountId = if (hasTerm) maturesIntoAccountId else null,
            maturityAmountMinor = if (hasPlan) maturityAmount?.minor else null,
            premiumMinor = if (hasPlan) perPayment?.minor else null,
            premiumEveryMonths = if (hasPlan) premiumEveryMonths else null,
            planRecurInBs = planRecurInBs,
            updatedAt = now,
        ) ?: AccountEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            kind = kind,
            currencyCode = currencyCode,
            institution = institution?.trim()?.takeIf { it.isNotEmpty() },
            openingBalanceMinor = openingBalance.minor,
            colorArgb = color.toArgb(),
            showInDisplayCurrency = showInDisplayCurrency,
            annualRate = annualRate,
            interestPayoutMonths = if (hasPeriods) interestPayoutMonths else null,
            interestInBs = hasPeriods && interestInBs,
            depositStartedOn = if (hasTerm) depositStartedOn?.toEpochDay() else null,
            depositTermMonths = if (hasTerm) depositTermMonths else null,
            maturesIntoAccountId = if (hasTerm) maturesIntoAccountId else null,
            maturityAmountMinor = if (hasPlan) maturityAmount?.minor else null,
            premiumMinor = if (hasPlan) perPayment?.minor else null,
            premiumEveryMonths = if (hasPlan) premiumEveryMonths else null,
            planRecurInBs = planRecurInBs,
            sortOrder = accountDao.nextSortOrder(),
            createdAt = now,
            updatedAt = now,
        )
        accountDao.upsert(entity)
        return SaveResult.Success(entity.id)
    }

    /**
     * Removes an account and everything that only existed because of it.
     *
     * Five things go, and each of them was a way for a deleted account to keep
     * affecting the app's figures:
     *
     *  - every movement that named it, both halves of any transfer included —
     *    otherwise the money still counts towards a month's spending, and the
     *    partner row shows money arriving from nowhere;
     *  - every repeating rule at either end of it, or the next launch quietly
     *    materialises another occurrence for a holding that is gone;
     *  - every rate it was ever on, which the table's foreign key would have
     *    cascaded had this been a real delete rather than a tombstone;
     *  - the pointer from any deposit or policy that was told to mature into
     *    it, deliberately not a foreign key and so nobody else's job to clear;
     *  - the account a debt is paid from or was disbursed into, on any loan
     *    that named it. The debt itself stays: it is owed to a bank, not to an
     *    account.
     *
     * Tombstoned rather than erased, like every other delete here, so a backup
     * taken on another device cannot bring the whole account back.
     */
    suspend fun deleteAccount(accountId: String) {
        val now = clock.nowMillis()
        // Rules first: one of them may still be mid-materialisation, and a rule
        // stopped after its rows were tombstoned would simply write new ones.
        seriesDao.softDeleteForAccount(accountId, now)
        entryDao.softDeleteForAccount(accountId, now)
        rateChangeDao.softDeleteForAccount(accountId, now)
        accountDao.detachMaturityTarget(accountId, now)
        loanDao.detachAccount(accountId, now)
        accountDao.softDelete(accountId, now)
    }

    /**
     * Moves an account's balance to [target] by writing a correction entry.
     *
     * This is what makes cash workable: money spent from a wallet that was
     * never recorded as income is normal, and the honest fix is to say "there
     * is actually this much left" rather than invent an income that never
     * happened. The correction is flagged so it never appears as earnings or
     * spending in any breakdown.
     */
    suspend fun adjustAccountBalance(accountId: String, target: Money, note: String?): SaveResult {
        val account = accountDao.findById(accountId) ?: return SaveResult.AccountRequired
        val balances = accountDao.observeBalances(clock.today().toEpochDay()).first()
        val current = balances.firstOrNull { it.accountId == accountId }?.balanceMinor
            ?: account.openingBalanceMinor
        val delta = target.minor - current
        if (delta == 0L) return SaveResult.Success(accountId)

        val now = clock.nowMillis()
        val entity = MoneyEntryEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = kotlin.math.abs(delta),
            currencyCode = account.currencyCode,
            baseAmountMinor = 0L,
            rateToBase = 1.0,
            baseCurrencyCode = account.currencyCode,
            direction = if (delta > 0) Direction.IN else Direction.OUT,
            occurredOn = clock.today().toEpochDay(),
            accountId = accountId,
            isAdjustment = true,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
        entryDao.upsert(entity)
        return SaveResult.Success(entity.id)
    }

    /**
     * Everything that has happened so far, converted to the display currency —
     * the figure the forward projection counts up or down from.
     */
    fun observeConfirmedBalance(): Flow<Money> =
        combine(
            accountDao.observeBalances(clock.today().toEpochDay()),
            accountDao.observeActive(),
            settings.settings,
        ) { balances, accounts, appSettings ->
            Triple(balances, accounts, appSettings.currencyCode)
        }.map { (balances, accounts, baseCode) ->
            val byId = accounts.associateBy { it.id }
            var total = 0L
            for (row in balances) {
                val account = byId[row.accountId] ?: continue
                total += if (account.currencyCode.equals(baseCode, ignoreCase = true)) {
                    row.balanceMinor
                } else {
                    exchangeRates.convert(
                        amountMinor = row.balanceMinor,
                        from = account.currencyCode,
                        fromMinorUnits = CurrencyOption.byCode(account.currencyCode).minorUnits,
                        base = baseCode,
                        baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
                    ).takeIf { it.isExact }?.amount?.minor ?: 0L
                }
            }
            Money(total)
        }

    /**
     * Attaches an entry to the repeating rule it belongs to.
     *
     * Without this the entry you typed while ticking "Recurring payment" is
     * never linked to the rule it created — only the occurrences generated
     * afterwards are. Reopening it would then show the box unticked, which is
     * both confusing and a trap: saving again would create a second rule.
     */
    suspend fun linkEntryToSeries(entryId: String, seriesId: String?) =
        entryDao.setSeries(entryId, seriesId, clock.nowMillis())

    /**
     * Soft delete, so the snackbar's Undo has something to bring back.
     *
     * Reports the rule this entry anchored, if it was the one that created it —
     * deleting the entry you ticked "Recurring payment" on must stop the future
     * payments too, or they keep appearing in Next payments for something you
     * just removed. Deleting a *generated* occurrence only removes that one, and
     * leaves the rule running.
     *
     * The one door every screen deletes through, and it undoes the whole of what
     * the row caused rather than leaving each list to remember a piece of it:
     *
     *  - **the debt it moved.** A lump sum, a drawdown or money lent on an
     *    existing arrangement each rewrote a loan's own principal figure, and
     *    removing the row used to leave the debt exactly as the payment had made
     *    it — the money reappeared in the account and stayed off the balance.
     *    Four lists can delete such a row and none of them knew.
     *  - **the rule it created**, when this was the entry the rule was written
     *    from. Not when a loan owns the rule: a loan's schedule outlives any one
     *    of its instalments, and stopping it is the loan editor's job.
     */
    suspend fun deleteEntry(id: String): EntryDeletion {
        val entry = entryDao.findById(id)
        val now = clock.nowMillis()
        val seriesId = entry?.seriesId
        // Asked before the delete, while the row is still there to be the answer.
        val anchor = seriesId?.let { entryDao.anchorEntryForSeries(it) }

        // The debt first, because it can refuse. A row tombstoned ahead of the
        // refusal would take the payment out of the timeline and leave the
        // balance it produced standing.
        if (entry != null && loans.revertMovement(entry) == Reversal.LaterPaymentFirst) {
            return EntryDeletion.LaterPaymentFirst
        }

        // Half a transfer is not a thing: deleting either row deletes the movement.
        if (entry?.transferId != null) {
            entryDao.softDeleteTransfer(entry.transferId, now)
        } else {
            entryDao.softDelete(id, now)
        }

        if (seriesId != null &&
            anchor == id &&
            seriesDao.findById(seriesId) != null &&
            loanDao.findBySeries(seriesId) == null
        ) {
            recurrence.deleteSeries(seriesId)
        }
        entry?.let {
            interest.repostIfBefore(LocalDate.ofEpochDay(it.occurredOn))
            // The money is out of the goal again, so the day it is reached moves
            // back out with it. Left alone, a contribution added and taken back
            // would shorten the goal for good.
            it.accountId?.let { account -> goalTerms.reterm(account) }
        }
        return EntryDeletion.Done
    }

    /**
     * The entry a repeating rule was created from, so a projected occurrence can
     * be opened for editing — the rule is edited through its own entry.
     */
/**
     * Every occurrence of one rule that is still on file.
     *
     * Exposed for the delete that removes a repeating arrangement outright
     * rather than one date of it; the rows are then taken back one at a time
     * through [deleteEntry], so each puts back whatever it moved.
     */
    suspend fun entriesForSeries(seriesId: String): List<String> =
        entryDao.entriesForSeries(seriesId)

        suspend fun anchorEntryForSeries(seriesId: String): String? =
        entryDao.anchorEntryForSeries(seriesId)

    suspend fun undoDeleteEntry(id: String) {
        val now = clock.nowMillis()
        val entry = entryDao.findById(id)
        // findById skips tombstones, so a deleted transfer leg has to be restored
        // by id first; that is enough to find its partner.
        entryDao.restore(id, now)
        val restored = entry ?: entryDao.findById(id)
        val transferId = restored?.transferId
        if (transferId != null) entryDao.restoreTransfer(transferId, now)
        // And the debt goes back to what the payment had made it. Undo has to
        // reach as far as the delete did, or taking back a lump sum by mistake
        // would leave the row in the timeline and the balance risen anyway.
        restored?.let {
            loans.reapplyMovement(it)
            interest.repostIfBefore(LocalDate.ofEpochDay(it.occurredOn))
            it.accountId?.let { account -> goalTerms.reterm(account) }
        }
    }

    // --------------------------------------------------------------- mapping

    private fun AccountEntity.toDomain() = Account(
        id = id,
        name = name,
        kind = kind,
        currencyCode = currencyCode,
        institution = institution,
        openingBalance = Money(openingBalanceMinor),
        color = Color(colorArgb),
        showInDisplayCurrency = showInDisplayCurrency,
        annualRate = annualRate,
        interestPayoutMonths = interestPayoutMonths,
        interestInBs = interestInBs,
        depositStartedOn = depositStartedOn?.let { LocalDate.ofEpochDay(it) },
        depositTermMonths = depositTermMonths,
        maturesIntoAccountId = maturesIntoAccountId,
        maturityAmount = maturityAmountMinor?.let { Money(it) },
        perPayment = premiumMinor?.let { Money(it) },
        premiumEveryMonths = premiumEveryMonths,
        premiumSeriesId = premiumSeriesId,
        planRecurInBs = planRecurInBs,
        isArchived = isArchived,
    )

    private fun MoneyEntryRow.toDomain(
        loanName: String? = null,
        loanKind: LoanKind? = null,
        belongsToLoanId: String? = null,
    ) = MoneyEntry(
        loanName = loanName,
        loanKind = loanKind,
        loanId = entry.loanId,
        belongsToLoanId = belongsToLoanId ?: entry.loanId,
        loanPart = entry.loanPart,
        id = entry.id,
        status = entry.status,
        seriesId = entry.seriesId,
        amount = Money(entry.amountMinor),
        currencyCode = entry.currencyCode,
        baseAmount = Money(entry.baseAmountMinor),
        accountId = entry.accountId,
        accountName = accountName,
        accountInstitution = accountInstitution,
        accountKind = accountKind,
        accountCurrency = accountCurrency,
        accountSiblings = accountSiblings,
        isAdjustment = entry.isAdjustment,
        direction = entry.direction,
        occurredOn = LocalDate.ofEpochDay(entry.occurredOn),
        note = entry.note,
        transferId = entry.transferId,
        showInDisplayCurrency = accountPrefersDisplay ?: true,
        transferPartnerAmount = transferPartnerMinor?.let { Money(it) },
        transferPartnerCurrency = transferPartnerCurrency,
        // Which end this row is depends on its direction: the half that leaves
        // is the source, the half that arrives is the destination. Resolving it
        // once here keeps every list from having to reason about it.
        transferFromName = if (entry.direction == Direction.OUT) {
            accountName
        } else {
            transferPartnerAccountName
        },
        transferToName = if (entry.direction == Direction.OUT) {
            transferPartnerAccountName
        } else {
            accountName
        },
    )

    private fun MoneyEntryEntity.toDomain() = MoneyEntry(
        id = id,
        status = status,
        seriesId = seriesId,
        loanId = loanId,
        amount = Money(amountMinor),
        currencyCode = currencyCode,
        baseAmount = Money(baseAmountMinor),
        accountId = accountId,
        accountName = null,
        isAdjustment = isAdjustment,
        direction = direction,
        occurredOn = LocalDate.ofEpochDay(occurredOn),
        note = note,
        transferId = transferId,
    )

    private fun List<HoldingTotal>.toBreakdown(): List<HoldingBreakdown> {
        val grandTotal = sumOf { it.totalMinor }
        return map { row ->
            HoldingBreakdown(
                accountId = row.accountId,
                accountName = row.accountName,
                accountInstitution = row.accountInstitution,
                accountKind = row.accountKind,
                accountCurrency = row.accountCurrency,
                accountSiblings = row.accountSiblings,
                loanKind = row.loanKind,
                color = row.colorArgb?.let { Color(it) },
                total = Money(row.totalMinor),
                // Only where the slice is of one currency. Two currencies added
                // together is a figure that is true nowhere, so a mixed slice
                // says only what it comes to in the display currency — which is
                // the one thing that can honestly be summed. Whether this is a
                // *second* figure is the screen's question, not this one's: it
                // is the same currency as the total on a phone whose display
                // currency it already is.
                ownTotal = Money(row.ownTotalMinor).takeIf { row.currencyCount == 1 },
                ownCurrency = row.ownCurrency?.takeIf { row.currencyCount == 1 },
                entryCount = row.entryCount,
                share = if (grandTotal <= 0L) 0f else row.totalMinor.toFloat() / grandTotal,
            )
        }
    }
}

/**
 * Groups a flat list into the timeline's day sections, oldest day first.
 *
 * Forwards, because the timeline is a plan and is read in the order the money
 * moves — the days still to come sit under these and carry a running balance,
 * which only reads as running in the direction it accumulates. Every *other*
 * list of movements in the app is newest-first and stays that way: those are
 * records, where the last thing that happened belongs at the top.
 *
 * The rows inside a day are turned round with it. They arrive newest-created
 * first, which is what the query gives Home and Reminders; leaving them that
 * way would have each day read backwards inside a page reading forwards.
 */
fun List<MoneyEntry>.groupByDay(): List<DayGroup> =
    groupBy { it.occurredOn }
        .entries
        .sortedBy { it.key }
        .map { (date, entries) ->
            DayGroup(
                date = date,
                entries = entries.reversed(),
                // Adjustments are shown in the day but left out of its totals:
                // a transfer or a balance correction is neither earned nor
                // spent, and counting one would contradict every other figure.
                moneyIn = Money(
                    entries.filter { it.counts && it.direction == Direction.IN }
                        .sumOf { it.baseAmount.minor }
                ),
                moneyOut = Money(
                    entries.filter { it.counts && it.direction == Direction.OUT }
                        .sumOf { it.baseAmount.minor }
                ),
            )
        }

/** Injected so tests can freeze time instead of racing the wall clock. */
interface Clock {
    fun nowMillis(): Long
    fun today(): LocalDate
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun today(): LocalDate = LocalDate.now()
}
