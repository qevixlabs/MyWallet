package com.mywallet.data.repo

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.mywallet.R
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.BalanceChangeRow
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.dao.RateChangeDao
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.RateChangeEntity
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.BalanceDay
import com.mywallet.domain.INTEREST_POSTING_SUFFIX
import com.mywallet.domain.HoldingPalette
import com.mywallet.domain.ProjectedEntry
import com.mywallet.domain.RateChange
import com.mywallet.domain.RateSchedule
import com.mywallet.domain.SavingsInterest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a rate has been, and the money that follows from it.
 *
 * Two things live here because they are the same fact seen from either side: a
 * bank moves a rate, and afterwards a savings account earns a different amount
 * each quarter while a loan's instalment buys a different amount of principal.
 * The rate history itself is shared — see [RateChangeEntity] — and this is the
 * one door to it.
 */
@Singleton
class InterestRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rateDao: RateChangeDao,
    private val accountDao: AccountDao,
    private val entryDao: MoneyEntryDao,
    private val exchangeRates: ExchangeRateRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /** Fires whenever any rate moves, so figures built on one can be redrawn. */
    fun observeRateRevision() = rateDao.observeRevision()

    /** What the rate has been on a holding, ready for the arithmetic. */
    suspend fun scheduleFor(
        baseRate: Double?,
        accountId: String? = null,
        loanId: String? = null,
    ): RateSchedule = RateSchedule(
        base = baseRate ?: 0.0,
        changes = rateDao.forHolding(accountId, loanId).map {
            RateChange(LocalDate.ofEpochDay(it.effectiveFrom), it.annualRate)
        },
    )

    /** One quarter the bank paid: the day it landed, and how much. */
    data class Posting(val on: LocalDate, val amount: Money)

    /**
     * Every quarter's interest this account has actually been paid, newest
     * first. Empty until the first quarter closes, which is what keeps the
     * expander off a screen with nothing behind it.
     */
    suspend fun postingsFor(accountId: String): List<Posting> =
        entryDao.postingsWithIdPrefix(accountId, postingPrefix(accountId)).map {
            Posting(LocalDate.ofEpochDay(it.occurredOn), Money(it.amountMinor))
        }

    /** Every rate a holding has been on, oldest first, for showing its history. */
    suspend fun changesFor(accountId: String? = null, loanId: String? = null): List<RateChange> =
        rateDao.forHolding(accountId, loanId).map {
            RateChange(LocalDate.ofEpochDay(it.effectiveFrom), it.annualRate)
        }

    /**
     * Records a rate the bank moved to, from the day it took effect.
     *
     * One row per effective day: a user correcting the rate they just typed is
     * fixing a mistake, not describing a second change on the same morning.
     */
    suspend fun recordChange(
        accountId: String? = null,
        loanId: String? = null,
        annualRate: Double,
        effectiveFrom: LocalDate,
    ) {
        val now = clock.nowMillis()
        val existing = rateDao.forHolding(accountId, loanId)
            .firstOrNull { it.effectiveFrom == effectiveFrom.toEpochDay() }
        rateDao.upsert(
            RateChangeEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                accountId = accountId,
                loanId = loanId,
                annualRate = annualRate,
                effectiveFrom = effectiveFrom.toEpochDay(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        // Nothing has to be unwound here. An account's interest is worked out
        // from the rate in force on each day, so a change lands on periods
        // already paid — and [postDueInterest] works every period out again from
        // the day the account's history starts, rewriting each posting in place.
        // Rolling a watermark back and tombstoning the rows was the old answer to
        // the same question, and it only ever covered this one way of getting a
        // stale figure; see the note there for the other two.
        //
        // But it does have to be *run*. A rate the user corrects on a Tuesday
        // changes what every closed period since earned, and leaving that to the
        // next launch means the figure on screen disagrees with the rate printed
        // above it until the app is killed.
        if (accountId != null) postDueInterest()
    }

    /**
     * Gives every account the payout interval this phone has been crediting it
     * on, once, on the first launch after the question moved onto the account.
     *
     * How often the bank pays is a fact about the account and is asked beside
     * its rate — somebody with savings at two banks has two answers, and the one
     * setting this replaces could only ever be right about one of them. But a
     * phone that answered the old question has real interest on file worked out
     * from it, and no migration can read a preference: without this, an account
     * credited half-yearly for two years would silently move to quarterly on
     * upgrade and every posting it had would be swept and rewritten.
     *
     * Runs before [postDueInterest] on launch, and does nothing at all on a
     * phone that never answered — those accounts have been credited on the
     * default all along, which is what a null column already means.
     */
    suspend fun adoptStoredPayoutInterval() {
        val stored = settings.takeInterestPayoutMonths() ?: return
        accountDao.adoptPayoutMonths(stored, clock.nowMillis())
    }

    /**
     * How often this account's interest is credited, counted in the months of
     * whichever calendar the user reads — see [SavingsInterest].
     *
     * Null means the question was never put to it — every account that predates
     * the column — and those have been credited quarterly, so that is what they
     * go on being credited on.
     */
    private fun AccountEntity.payoutMonths(): Int =
        SavingsInterest.gapOf(interestPayoutMonths ?: SavingsInterest.DEFAULT_EVERY_MONTHS)

    /**
     * Works the interest out again when money has moved on a day that a closed
     * period was counted over.
     *
     * The trigger, and the reason it is needed at all: interest is derived from
     * the movements, and until now it was only ever derived at launch. A loan
     * taken last September and entered today writes eleven months of movements
     * into a savings account in one go — the money arriving, the broken-period
     * charge, every instalment since — and every one of them lands inside a
     * period the bank has already paid on. The account showed the right balance
     * and no interest at all, and stayed that way until the app was next killed
     * and reopened.
     *
     * Only for a day *before* today. A period closes on its payout day and is
     * counted up to it exclusive, so nothing dated today or later can be inside
     * one — and the ordinary case, an entry added for this morning, must not pay
     * for a walk over every account's whole history.
     */
    suspend fun repostIfBefore(on: LocalDate?) {
        if (on == null || !on.isBefore(clock.today())) return
        postDueInterest()
    }

    /**
     * Pays every period's interest that has come due, from the day the account's
     * history starts to today.
     *
     * Safe on every launch, and deliberately **not** incremental. Each posting's
     * id is derived from the account and the day, so working a period out again
     * rewrites that one row: running the whole history is idempotent, and it is
     * the only thing that stays right when the past changes underneath it. Three
     * ways it does, each of which produced a figure the passbook disagreed with
     * while this started at a watermark instead:
     *
     *  - **A movement lands behind the watermark.** A loan disbursed last
     *    September, entered today, credits रू 27,00,000 to an account on a day
     *    three closed periods ago — and the bank has been paying interest on it
     *    ever since. Every one of those periods has to be worked out again.
     *  - **A rate is corrected**, which changes what every day since was earning.
     *  - **The payout interval is changed**, which moves the payout days
     *    themselves. Postings left on the old schedule are swept below, because
     *    their day is no longer a day the bank pays on. The form settles the
     *    interval the moment a period has actually been credited, so this is now
     *    a restored backup or an upgrade adopting the old setting rather than
     *    anything the user can do to a working account — but the sweep stays,
     *    because those two can still move the days.
     *
     * Interest is worked out on the balance the account actually held each day
     * and at the rate in force on each of those days, then paid in on the first
     * day of the month after the period closes — which is when the bank
     * pays it. The entry is ordinary money in: interest really is earned, and it
     * belongs in the month's income like anything else.
     *
     * **A period's own credit is money the next period earns on**, so each one is
     * added to the working balance as it is worked out. That is what a bank does
     * — the credit lands in the account and starts earning the same day — and it
     * is the reason the account's *stored* postings are taken out of the balance
     * first: left in, the run would be reading its own previous answers, and each
     * launch would nudge every figure a little further. Taken out and put back as
     * they are recomputed, the run says the same thing every time.
     */
    suspend fun postDueInterest() {
        val today = clock.today()
        val now = clock.nowMillis()
        val current = settings.settings.first()
        val baseCode = current.currencyCode

        for (account in accountDao.earningInterest()) {
            // Each account's own arrangement: two banks pay on two rhythms, and
            // the figure in a passbook follows the one that bank is on.
            val everyMonths = account.payoutMonths()
            // Whose months this bank cuts its year into — the account's own
            // answer, and only where that is the calendar being read. See
            // [CalendarSystem.forInterest].
            val calendar = CalendarSystem.forInterest(account.interestInBs, current.calendarSystem)
            val rates = scheduleFor(account.annualRate, accountId = account.id)
            val movements = accountDao.movements(account.id, today.toEpochDay())
            // From the day the account's rate first meant anything. Never from
            // the beginning of time: an account given a rate today has not been
            // earning silently for years.
            val from = earliestRateDay(account, movements)
            val payouts = SavingsInterest.payoutsBetween(from, today, everyMonths, calendar)
            sweepStrayPostings(account.id, payouts, now)
            if (payouts.isEmpty()) continue

            val balance = movementsWithoutInterest(account.id, today).toMutableList()
            for (payout in payouts) {
                val earned = periodInterest(account, rates, balance, payout, everyMonths, calendar)
                if (earned.isPositive) {
                    writeInterest(account, earned, payout, baseCode, now)
                    balance += BalanceDay(payout, earned.minor)
                } else {
                    // A period that has stopped being worth anything — the rate
                    // was taken back, or the money was not there after all. The
                    // row it wrote last time has to go, or the account keeps a
                    // credit the bank never made.
                    entryDao.softDelete(postingId(account.id, payout), now)
                }
            }
            accountDao.setInterestPostedThrough(account.id, payouts.last().toEpochDay(), now)
        }
    }

    /**
     * Everything that has moved this account except the interest it was paid.
     *
     * The postings are the app's own previous answers, and a run that read them
     * back would be building this one on top of the last. They are put back one
     * at a time as each period is worked out — see [postDueInterest].
     */
    private suspend fun movementsWithoutInterest(
        accountId: String,
        until: LocalDate,
    ): List<BalanceDay> {
        val rows = accountDao.movementsExcept(
            accountId, until.toEpochDay(), postingPrefix(accountId),
        )
        return rows.map { BalanceDay(LocalDate.ofEpochDay(it.occurredOn), it.deltaMinor) }
    }

    /**
     * Withdraws any interest this account was paid on a day it is no longer paid
     * on.
     *
     * Only the interval moves those days, and it moves all of them: switching
     * from quarterly to half-yearly leaves half the postings sitting on dates the
     * bank has no period ending on. They cannot be rewritten — nothing computes
     * them any more — so they are found by their derived id and tombstoned.
     */
    private suspend fun sweepStrayPostings(
        accountId: String,
        payouts: List<LocalDate>,
        now: Long,
    ) {
        val days = payouts.mapTo(mutableSetOf()) { it.toEpochDay() }
        entryDao.postingsWithIdPrefix(accountId, postingPrefix(accountId))
            .filter { it.occurredOn !in days }
            .forEach { entryDao.softDelete(it.id, now) }
    }

    /**
     * Interest not yet paid, out to [horizon] — one row per account per payout.
     *
     * A quarter's interest is as much a scheduled payment as an EMI is, and the
     * user asking "where will my money be at the end of Kartik?" is asking a
     * question the bank's own credit is part of the answer to. Computed rather
     * than stored, like every other projection: the figure moves every time the
     * balance does, and a stored one would be stale by the next entry.
     *
     * The balance is taken as it stands, because what has not happened yet
     * cannot be counted — money the user has not deposited earns nothing, and
     * guessing that they will is how a forecast turns into a promise.
     */
    suspend fun projectDue(horizon: LocalDate): List<ProjectedEntry> {
        val today = clock.today()
        if (!today.isBefore(horizon)) return emptyList()
        val current = settings.settings.first()
        val baseCode = current.currencyCode
        val projected = mutableListOf<ProjectedEntry>()

        for (account in accountDao.earningInterest()) {
            val everyMonths = account.payoutMonths()
            // The same calendar the credits themselves are cut in: a forecast on
            // a different rhythm from the postings it continues would put a
            // credit on a day the bank has no period ending on.
            val calendar = CalendarSystem.forInterest(account.interestInBs, current.calendarSystem)
            val rates = scheduleFor(account.annualRate, accountId = account.id)
            val movements = accountDao.movements(account.id, horizon.toEpochDay())
            val paid = account.interestPostedThrough?.let { LocalDate.ofEpochDay(it) }
                ?: earliestRateDay(account, movements)
            // Anything still unpaid, including a period that closed while the
            // app was shut: `postDueInterest` will write it, and until it does
            // the forecast should not pretend it is not owed.
            val payouts =
                SavingsInterest.payoutsBetween(maxOf(paid, today), horizon, everyMonths, calendar)
            if (payouts.isEmpty()) continue

            // What the account actually holds, postings included: those have
            // been paid and are earning. Each period still to come then adds its
            // own credit, because the one after it will earn on that too.
            val balance = movements
                .map { BalanceDay(LocalDate.ofEpochDay(it.occurredOn), it.deltaMinor) }
                .toMutableList()
            for (payout in payouts) {
                val earned = periodInterest(account, rates, balance, payout, everyMonths, calendar)
                if (!earned.isPositive) continue
                balance += BalanceDay(payout, earned.minor)
                val converted = exchangeRates.convert(
                    amountMinor = earned.minor,
                    from = account.currencyCode,
                    fromMinorUnits = CurrencyOption.byCode(account.currencyCode).minorUnits,
                    base = baseCode,
                    baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
                )
                projected += ProjectedEntry(
                    // Not a repeating rule, and nothing to open if it is tapped:
                    // the schedule belongs to the bank, not to anything the user
                    // wrote. [isInterest] is what stops the row offering to stop
                    // a rule that does not exist.
                    seriesId = postingId(account.id, payout),
                    date = payout,
                    amount = earned,
                    currencyCode = account.currencyCode,
                    baseAmount = converted.amount,
                    direction = Direction.IN,
                    // The one projection the app names itself: nobody typed it,
                    // and a bank's quarter of interest is a kind of movement
                    // rather than a note.
                    title = context.getString(R.string.entry_interest),
                    accountId = account.id,
                    accountName = account.name,
                    accountInstitution = account.institution,
                    showInDisplayCurrency = account.showInDisplayCurrency,
                    note = null,
                    isAdjustment = false,
                    isInterest = true,
                )
            }
        }
        return projected
    }

    /**
     * One period's interest on one account.
     *
     * Always the *whole* period, whatever day the app happens to have caught up
     * from: the months are the bank's, not the app's, and money that was only
     * there for part of them earns only that part. Working it out from a
     * watermark instead would pay a full period's rate for a fortnight.
     */
    private fun periodInterest(
        account: AccountEntity,
        rates: RateSchedule,
        movements: List<BalanceDay>,
        payout: LocalDate,
        everyMonths: Int,
        calendar: CalendarSystem,
    ): Money {
        val start = SavingsInterest.periodStart(payout, everyMonths, calendar)
            ?: return Money.ZERO
        // How much of the year this period is. Usually [everyMonths], and less
        // where the year ran out first — a gap that does not divide twelve leaves
        // a short period at the end of it, and a short period pays less.
        val months = SavingsInterest.periodMonths(payout, everyMonths, calendar)
            ?: return Money.ZERO
        // Money that landed *on* the opening day counts as opening balance: it
        // was there for every day of the period. It falls between the two
        // otherwise — [SavingsInterest.earned] only treats movements after the
        // first day as movements — and the previous period's own interest,
        // credited on that very day, is exactly such a deposit.
        val opening = Money(
            account.openingBalanceMinor +
                movements.filter { !it.date.isAfter(start) }.sumOf { it.deltaMinor }
        )
        return SavingsInterest.earned(
            opening = opening,
            changes = movements,
            rates = rates,
            periodStart = start,
            payout = payout,
            monthsInPeriod = months,
        )
    }

    /**
     * The day this account's interest starts running.
     *
     * **What decides it is whether the account opened at a rate, not whether a
     * change was later recorded against it.** `annual_rate` means "the rate it
     * opened at", so an account that has one has been earning since the app
     * first knew about it — the day it was created *or* the day of its oldest
     * movement, whichever is earlier. An account opened today with a rate has
     * not been silently earning for years, which is what the creation day
     * guards; but one the user has told the app held रू 27,00,000 since last
     * September has, and the bank has been paying quarters on it. The interest
     * is not invented there — it is worked out on money the app was told was in
     * the account, on the days it was told it was there.
     *
     * Reading the first *rate change* as the start is what this replaced, and it
     * silently destroyed interest the moment a bank repriced: a savings account
     * opened at 5% and moved to 6.5% in April lost every quarter before April —
     * रू 68,793 of it on a real seven-year loan's account, three whole postings
     * that simply never appeared. Worse, it ran the wrong way round: recording a
     * rate *rise* left the account earning less than recording nothing at all.
     * [scheduleFor] already carries `annualRate` as the schedule's base, so the
     * days before the first change were always priced correctly — they were just
     * never reached.
     *
     * The first change is still the honest answer where the rate lives *only* in
     * the history: a rate agreed on a holding that never had one charges from the
     * day it was agreed and not one day earlier, which is exactly what a null
     * `annual_rate` beside a dated change means.
     */
    private suspend fun earliestRateDay(
        account: AccountEntity,
        movements: List<BalanceChangeRow>,
    ): LocalDate {
        if (account.annualRate == null) {
            val earliest = rateDao.forHolding(account.id, null).minByOrNull { it.effectiveFrom }
            earliest?.let { return LocalDate.ofEpochDay(it.effectiveFrom) }
        }
        val opened = account.createdAt / MILLIS_PER_DAY
        return LocalDate.ofEpochDay(
            minOf(opened, movements.minOfOrNull { it.occurredOn } ?: opened)
        )
    }

    /**
     * Writes one quarter's interest into the account.
     *
     * The id is derived from the account and the day, so working the same
     * quarter out again — after a rate correction, say — rewrites that row
     * instead of adding a second one beside it.
     */
    private suspend fun writeInterest(
        account: AccountEntity,
        amount: Money,
        on: LocalDate,
        baseCode: String,
        now: Long,
    ) {
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = account.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(account.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        entryDao.upsert(
            MoneyEntryEntity(
                id = postingId(account.id, on),
                amountMinor = amount.minor,
                currencyCode = account.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                direction = Direction.IN,
                occurredOn = on.toEpochDay(),
                accountId = account.id,
                isAdjustment = false,
                status = EntryStatus.CONFIRMED,
                note = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    private fun postingId(accountId: String, on: LocalDate): String =
        "${postingPrefix(accountId)}${on.toEpochDay()}"

    /**
     * What every posting on one account has at the front of its id.
     *
     * The one mark a posting carries. It used to carry a label as well, and the
     * label was what the queries matched on; with labels gone this derived id is
     * both how a period is rewritten in place *and* how a posting is told from
     * the user's own movements — which it always had to be, since the id is what
     * makes the recomputation idempotent.
     */
    private fun postingPrefix(accountId: String): String = "$accountId$POSTING_SUFFIX"

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val POSTING_SUFFIX = INTEREST_POSTING_SUFFIX
    }
}
