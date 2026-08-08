package com.mywallet.data.repo

import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.dao.RecurringSeriesDao
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.db.entity.RecurringSeriesEntity
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Arrears
import com.mywallet.domain.ProjectedEntry
import com.mywallet.domain.Recurrence
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repeating money: salary on the 1st, EMI on the 5th, a subscription monthly.
 *
 * Two different things happen either side of today:
 *
 *  - dates that have **arrived** become real rows, counting immediately towards
 *    every balance and every debt, and editable or deletable individually —
 *    the real world never quite matches the rule;
 *  - dates still in the **future** are computed on demand and never stored, so
 *    editing the rule instantly corrects every projection and there is no
 *    drawer of stale rows to reconcile.
 *
 * A materialised row is marked `EXPECTED`, which no longer means "does not
 * count" — see [EntryStatus]. It means the rule still owns the row, so changing
 * the rule may rewrite it; the moment the user edits one it becomes theirs.
 */
@Singleton
class RecurrenceRepository @Inject constructor(
    private val seriesDao: RecurringSeriesDao,
    private val entryDao: MoneyEntryDao,
    private val accountDao: AccountDao,
    // The DAO, not LoanRepository — that one depends on this class, and the
    // timeline only needs to know which rules pay a loan.
    private val loanDao: LoanDao,
    private val exchangeRates: ExchangeRateRepository,
    private val transfers: TransferRecorder,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    suspend fun findSeries(id: String): RecurringSeriesEntity? = seriesDao.findById(id)

    /**
     * What a rule was owed between two days, and what it actually got.
     *
     * One question asked in two places — the debt, which has to say what is
     * still to pay, and this class, which has to write the row that collects it
     * — so it is answered once here. See [Arrears] for what the two answers mean
     * together.
     */
    suspend fun arrears(series: RecurringSeriesEntity, from: LocalDate, to: LocalDate): Arrears {
        val due = dueDates(series, from, to)
        if (due.isEmpty()) return Arrears.NONE
        val paid = seriesDao.paidOccurrenceDays(
            series.id, from.toEpochDay(), to.toEpochDay(), series.direction,
        )
        // What the user has already dated forward. It settles no period today —
        // the money has not gone — but the next scheduled payment must not ask
        // for what is already promised. See [Arrears.carriedForward].
        val later = seriesDao.paidOccurrenceDays(
            series.id, to.toEpochDay() + 1, Long.MAX_VALUE, series.direction,
        )
        return Arrears.of(
            dueDates = due,
            paidDays = paid.toSet(),
            paidCount = paid.size,
            paidLater = later.size,
        )
    }

    /**
     * The day a rule's arrears are counted from, or null where it is owed
     * nothing it can be late with.
     *
     * A schedule the app **replays** for the user has to add up — a loan's
     * instalments, a policy's premiums, a goal's contributions — so an
     * occurrence swiped away is money that is late rather than money that never
     * happened, and the next one collects it. A rule the user wrote themselves
     * is the opposite: deleting a month's salary says it did not arrive, and
     * doubling next month's would be the app inventing income. It is the same
     * line [watchedFrom] draws, for the same reason.
     *
     * A debt counts from the day its current balance started running, because
     * that is where its schedule is counted from and a lump sum moves it. A plan
     * has no such day and counts from the rule's own start.
     */
    private suspend fun arrearsFrom(series: RecurringSeriesEntity): LocalDate? {
        loanDao.findBySeries(series.id)?.let { return LocalDate.ofEpochDay(it.startedOn) }
        val paysAPlan = series.transferToAccountId
            ?.let { accountDao.findById(it)?.kind } in PLANS
        return LocalDate.ofEpochDay(series.startOn).takeIf { paysAPlan }
    }

    /**
     * How many occurrences this rule owes on top of the one falling on [date] —
     * see [arrearsFrom] and [Arrears].
     */
    private suspend fun carriedInto(series: RecurringSeriesEntity, date: LocalDate): Int =
        arrearsFrom(series)
            ?.let { arrears(series, it, date.minusDays(1)).carriedForward }
            ?: 0

    /** Every date a rule fell due on between two days, in order. */
    fun dueDates(series: RecurringSeriesEntity, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to < from) return emptyList()
        return Recurrence.occurrencesBetween(
            start = LocalDate.ofEpochDay(series.startOn),
            interval = series.interval,
            from = from,
            to = to,
            endOn = series.endOn?.let { LocalDate.ofEpochDay(it) },
            everyMonths = series.intervalMonths,
            inBikramSambat = series.recurInBs,
        )
    }

    /**
     * The day the app started watching the account this rule pays from.
     *
     * Occurrences before it name **no account** — the difference between a loan
     * that adds up and a bank balance that does not. A loan taken a year ago and
     * entered today owes twelve instalments that really were paid, and the
     * schedule needs them; but they left an account whose balance the user has
     * since told the app directly, and debiting it again subtracts a year of
     * payments from a figure that already had them taken out.
     *
     * Answered here rather than inside [materialiseDue] because two things write
     * occurrences of a rule now — that one, and a missed instalment being put
     * back on the day it was really paid — and a rule this narrow, obeyed in one
     * place and forgotten in the other, is a rule the app breaks.
     */
    private suspend fun watchedFrom(series: RecurringSeriesEntity): LocalDate {
        val loan = loanDao.findBySeries(series.id)
        val paysAPlan = series.transferToAccountId
            ?.let { accountDao.findById(it)?.kind } in PLANS
        val writtenOn = LocalDate.ofEpochDay(series.createdAt / MILLIS_PER_DAY)
        if (loan == null && !paysAPlan) {
            // **A rule the user wrote themselves has no cutoff at all.**
            //
            // The rule above is about schedules the app replays *for* the user —
            // a loan's instalments, a policy's premiums — where the rows have to
            // exist for the debt or the policy to add up, and the account they
            // came out of is one the app was not watching. Generalising it to
            // every rule was wrong, and this is what it looked like: somebody
            // writes "रू 100 a month from Nabil Bank, from 14 April 2025", and
            // gets one row that debits the account and fifteen that name
            // nothing. Those fifteen still count as spending, so the month says
            // money went out and no holding is any poorer; the account's own
            // statement lists one of the sixteen; and the balance is short by
            // the other fifteen.
            //
            // Nobody back-dates a rule by accident. Doing it *is* the user
            // saying this has been coming out of that account since then, and
            // the app's job is to write down what they said.
            return LocalDate.MIN
        }
        // Normally the day the rule was written down. But a loan whose
        // disbursement the user recorded *into that same account* starts it
        // earlier: answering "money received on: Nabil Bank" for a day last
        // September is the user saying the app is that account's bookkeeper from
        // that day. The balance they typed is what it held *before* the money
        // landed, not what it holds now — so the instalments since have not been
        // taken out of it, and leaving them out left an account holding a whole
        // year's worth of payments it had really made.
        return loan?.disbursedOn
            ?.takeIf { loan.disbursedAccountId == series.accountId }
            ?.let { minOf(writtenOn, LocalDate.ofEpochDay(it)) }
            ?: writtenOn
    }

    /**
     * Which account an occurrence of [series] dated [date] may name — the rule's
     * own, or none where the app was not yet watching it. See [watchedFrom].
     */
    suspend fun accountForOccurrence(series: RecurringSeriesEntity, date: LocalDate): String? =
        series.accountId.takeIf { date >= watchedFrom(series) }

    suspend fun saveSeries(
        id: String?,
        amount: Money,
        currencyCode: String,
        direction: Direction,
        interval: RecurrenceInterval,
        startOn: LocalDate,
        endOn: LocalDate?,
        accountId: String?,
        note: String?,
        /**
         * Months between occurrences, when [interval] cannot say the gap — a loan
         * repaid every two months, or in one payment at the end of its term. Null
         * leaves the named interval in charge, which is every rule the user
         * writes by hand.
         */
        intervalMonths: Int? = null,
        /**
         * True when occurrences move a balance without being income or spending:
         * a transfer, or repayment of money the user lent out.
         */
        isAdjustment: Boolean = false,
        /** Set to make this a transfer rule; [accountId] is then the source. */
        transferToAccountId: String? = null,
        /**
         * True when the occurrence on [startOn] already exists as a real entry —
         * the case where the user ticked "repeat" while adding it.
         *
         * Without this the series immediately materialises an expected copy of
         * the row that was just confirmed, and the day shows the salary twice.
         * The start date still anchors the day-of-month; only generation skips
         * ahead.
         */
        firstOccurrenceAlreadyRecorded: Boolean = false,
        /**
         * Whether this rule follows whichever calendar the app is set to.
         *
         * The **opt-in**, not the effective answer: what the dates are stepped
         * in is this and the setting together, worked out here so that every
         * caller answers the one question the user was actually asked. A rule
         * the user writes by hand says yes — the months they set it up in are
         * the months they read — and a bank's schedule says no, because a bank
         * debits on a fixed day of the English month whatever patro its customer
         * prefers. See [CalendarSystem.forInterest].
         */
        usesSelectedCalendar: Boolean = false,
        /**
         * Whether to turn the dates that have arrived into rows before returning.
         *
         * False for a rule a *loan* writes. The loan row does not exist yet at
         * that point — its id is being written around this call — so a
         * materialisation here cannot see which account the money was disbursed
         * into, and every back-dated instalment came out named no account at
         * all. [LoanRepository.saveLoan] runs it once the loan is on file.
         */
        materialiseNow: Boolean = true,
        /**
         * Whether to rebuild this rule's unconfirmed occurrences from its own
         * start rather than from today.
         *
         * The answer to "apply this change from…", asked whenever the user
         * changes how often an existing rule falls — see
         * `AddEntryViewModel.applyFrom`. Both answers are defensible and they
         * are not the same: counted from the start, a rule that has just become
         * yearly falls on past anniversaries it never fell on before, and those
         * dates become real rows that move a balance. Counted from today it
         * simply begins its new rhythm at the next payment.
         *
         * Confirmed rows are never touched either way — they happened.
         */
        rebuildFromStart: Boolean = false,
    ): String {
        val now = clock.nowMillis()
        val existing = id?.let { seriesDao.findById(it) }
        // The two halves joined: what this rule is actually stepped in.
        val recurInBs = CalendarSystem.forInterest(
            usesSelectedCalendar, settings.settings.first().calendarSystem,
        ) == CalendarSystem.BIKRAM_SAMBAT
        val entity = existing?.copy(
            amountMinor = amount.minor,
            currencyCode = currencyCode.uppercase(),
            direction = direction,
            interval = interval,
            intervalMonths = intervalMonths?.takeIf { it > 0 },
            startOn = startOn.toEpochDay(),
            endOn = endOn?.toEpochDay(),
            accountId = accountId,
            transferToAccountId = transferToAccountId,
            isAdjustment = isAdjustment,
            recurInBs = recurInBs,
            usesSelectedCalendar = usesSelectedCalendar,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = now,
        ) ?: RecurringSeriesEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amount.minor,
            currencyCode = currencyCode.uppercase(),
            direction = direction,
            interval = interval,
            intervalMonths = intervalMonths?.takeIf { it > 0 },
            startOn = startOn.toEpochDay(),
            endOn = endOn?.toEpochDay(),
            accountId = accountId,
            transferToAccountId = transferToAccountId,
            isAdjustment = isAdjustment,
            recurInBs = recurInBs,
            usesSelectedCalendar = usesSelectedCalendar,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            materialisedThrough = if (firstOccurrenceAlreadyRecorded) startOn.toEpochDay() else null,
            createdAt = now,
            updatedAt = now,
        )
        seriesDao.upsert(entity)

        // Unconfirmed rows from the old rule are discarded. Confirmed ones stay:
        // they are things that actually happened, and changing next month's
        // salary must not rewrite last month's.
        if (existing != null) {
            val today = clock.today()
            // **Changing which calendar the rule counts in goes back to the
            // start**, where every other change starts at today.
            //
            // The difference is what the old rows *are*. A rule whose amount
            // changes produced rows that were right when they were written, so
            // they stand. A rule whose calendar changes produced rows on the
            // wrong days — 14 May was never what "monthly from 1 Baisakh" meant,
            // it only looked like it — and they are still `EXPECTED`, which is
            // the rule's own words rather than anything the user has taken over.
            // Leaving them would keep the very duplicate this fixes: one payment
            // drawn twice inside Baisakh, for as long as the history lasts.
            // [rebuildFromStart] joins it, for the same reason said the other way
            // round: a rule whose *rhythm* changed also produced rows on days it
            // no longer falls on, and the user has said they want it counted from
            // the beginning.
            val recalendared = existing.recurInBs != recurInBs
            val fromStart = recalendared || rebuildFromStart
            val from = if (fromStart) minOf(existing.startOn, entity.startOn) else today.toEpochDay()
            seriesDao.discardExpectedFrom(entity.id, from)
            // The watermark has to come back with them.
            //
            // It records how far occurrences have been turned into real rows, and
            // the rows for today onwards have just been thrown away. Left where it
            // was, materialiseDue believes today is already done and never rebuilds
            // it — which is how a loan's instalment vanished from the timeline and
            // from the balance the moment the loan was saved again.
            val rebuildFrom = if (fromStart) from - 1 else today.minusDays(1).toEpochDay()
            if ((existing.materialisedThrough ?: Long.MIN_VALUE) > rebuildFrom) {
                seriesDao.setMaterialisedThrough(entity.id, rebuildFrom, now)
            }
        }
        if (materialiseNow) materialiseDue()
        return entity.id
    }

    /**
     * Moves where a rule stops, without disturbing anything else about it.
     *
     * A floating loan's term moves whenever the bank moves the rate, and its
     * instalment does not — so the one thing to correct is the last day it
     * falls. Going through [saveSeries] would discard and rebuild the
     * occurrences for no reason.
     */
    suspend fun setEndOn(id: String, endOn: LocalDate?) {
        seriesDao.setEndOn(id, endOn?.toEpochDay(), clock.nowMillis())
    }

    /**
     * Moves which calendar a rule counts its months in.
     *
     * Every unconfirmed occurrence from the rule's own start is discarded and
     * the watermark rolled back with them, which is what every other edit that
     * *re-calendars* a rule does: an amount that changes leaves rows that were
     * right when they were written, but a calendar that changes leaves them on
     * days the rule never meant. Confirmed rows are the user's own and stay.
     *
     * Used when a debt that counts in Nepali months meets a calendar setting
     * that has moved under it — see [LoanRepository.recalendarSchedules].
     */
    suspend fun setRecurInBs(id: String, recurInBs: Boolean) {
        val series = seriesDao.findById(id) ?: return
        if (series.recurInBs == recurInBs) return
        val now = clock.nowMillis()
        seriesDao.upsert(series.copy(recurInBs = recurInBs, updatedAt = now))
        seriesDao.discardExpectedFrom(id, series.startOn)
        // And the watermark back with them, exactly as [saveSeries] rolls it
        // back when it re-calendars a rule: left where it was, materialiseDue
        // believes every day up to it is already written and the replacements
        // are never generated at all.
        if ((series.materialisedThrough ?: Long.MIN_VALUE) > series.startOn - 1) {
            seriesDao.setMaterialisedThrough(id, series.startOn - 1, now)
        }
        materialiseDue()
    }

    suspend fun setPaused(id: String, paused: Boolean) {
        val now = clock.nowMillis()
        seriesDao.setPaused(id, paused, now)
        if (paused) seriesDao.discardExpectedFrom(id, clock.today().toEpochDay())
    }

    suspend fun deleteSeries(id: String) {
        seriesDao.softDelete(id, clock.nowMillis())
        // Only unconfirmed future rows go; confirmed history is untouched.
        seriesDao.discardExpectedFrom(id, clock.today().toEpochDay())
    }

    /**
     * Drops one date out of a rule, leaving the rule itself alone.
     *
     * A date that has arrived is a real row and is removed by removing it. A
     * date still to come is not a row at all — it is computed from the rule
     * every time the timeline is drawn — so there is nothing to tombstone and
     * the swipe had nowhere to land. Deleting the whole rule is the wrong answer
     * to "not this month", and editing it to end early is a different one again.
     *
     * What is written instead is the occurrence itself, already tombstoned:
     * `occurrenceCount` counts deleted rows precisely so a date the user threw
     * away is never generated again, and [projectForward] now asks the same
     * question before drawing one. The row is `CONFIRMED` rather than `EXPECTED`
     * because that is what it is — the user taking this occurrence over from the
     * rule — and because a rule edit discards its own `EXPECTED` rows, which
     * would quietly bring the skipped date back.
     *
     * @return the marker's id, so [unskipOccurrence] can take it back, or null
     *   when the rule has gone in the meantime.
     */
    suspend fun skipOccurrence(seriesId: String, date: LocalDate): String? {
        val series = seriesDao.findById(seriesId) ?: return null
        val now = clock.nowMillis()
        val id = UUID.randomUUID().toString()
        entryDao.upsert(
            MoneyEntryEntity(
                id = id,
                amountMinor = series.amountMinor,
                currencyCode = series.currencyCode,
                // No conversion is captured. Every figure on this row is there so
                // it can say which occurrence it stands for; none of them is ever
                // summed, because the row is deleted from the moment it is
                // written, and a rate frozen onto a payment that never happened
                // would be a fact about nothing.
                baseAmountMinor = 0,
                rateToBase = 1.0,
                baseCurrencyCode = series.currencyCode,
                direction = series.direction,
                occurredOn = date.toEpochDay(),
                accountId = series.accountId,
                isAdjustment = series.isAdjustment,
                seriesId = series.id,
                status = EntryStatus.CONFIRMED,
                note = series.note,
                createdAt = now,
                updatedAt = now,
                deletedAt = now,
            )
        )
        return id
    }

    /**
     * Puts a skipped date back.
     *
     * A real delete, unlike every other undo in the app: the marker is what
     * blocks the date, so a tombstoned marker would go on blocking it.
     */
    suspend fun unskipOccurrence(markerId: String) {
        entryDao.hardDelete(markerId)
    }

    /**
     * Creates `EXPECTED` rows for every occurrence that has come due since the
     * series was last processed.
     *
     * Safe to call on every launch: `materialisedThrough` makes it a no-op once
     * a day's occurrences exist, so opening the app twice cannot double up a
     * salary.
     */
    suspend fun materialiseDue() {
        val today = clock.today()
        val baseCode = settings.settings.first().currencyCode
        val now = clock.nowMillis()

        for (series in seriesDao.activeSeries()) {
            val start = LocalDate.ofEpochDay(series.startOn)
            val destination = series.transferToAccountId
            // The day the app started watching the account this rule pays from.
            // Occurrences before it are history the app was not there for — see
            // [watchedFrom], which is where that rule and its one exception live.
            val watchedFrom = watchedFrom(series)
            val from = series.materialisedThrough
                ?.let { LocalDate.ofEpochDay(it).plusDays(1) }
                ?: start
            if (from > today) continue

            val due = Recurrence.occurrencesBetween(
                start = start,
                interval = series.interval,
                from = from,
                to = today,
                endOn = series.endOn?.let { LocalDate.ofEpochDay(it) },
                everyMonths = series.intervalMonths,
                inBikramSambat = series.recurInBs,
            )

            for (date in due) {
                // Never twice for the same day, whatever the watermark says. This
                // is checked against the rows themselves — including ones the user
                // deleted, which must stay deleted.
                if (seriesDao.occurrenceCount(series.id, date.toEpochDay()) > 0) continue
                // An occurrence of a *schedule the app replays* — a loan's, a
                // policy's — dated before the rule was written names **no
                // account**, and this is the difference between a loan that adds
                // up and a bank balance that does not. See [watchedFrom] above,
                // which is where the rule and its one exception live, and which
                // is wide open for a rule the user wrote by hand.
                //
                // A loan taken a year ago and entered today owes twelve
                // instalments that really were paid — the schedule needs them, or
                // the debt reads a year behind. But they left an account the app
                // was not watching, and whose balance the user has since told it
                // directly. Debiting it again subtracts a year of payments from a
                // figure that already had them taken out, and the account goes
                // negative for money that is long gone. The same reason nothing is
                // recorded *arriving* when a loan is created.
                //
                // A transfer rule owes the user two rows, converted at the rate
                // on the day it actually falls due — not the day the rule was
                // written.
                if (destination != null && series.accountId != null) {
                    // Both halves of a back-dated transfer follow that rule,
                    // with one exception: a holding the app opened itself and
                    // is the only bookkeeper for. A policy and a goal are both
                    // that — what either holds is the payments made into it and
                    // nothing else, and the user has no way to correct it by
                    // hand — so a payment from before the holding was entered
                    // still lands in it, while the account it left is left
                    // alone.
                    val watched = date >= watchedFrom
                    val intoPlan = !watched && accountDao.findById(destination)?.kind in PLANS
                    transfers.record(
                        transferId = null,
                        fromAccountId = series.accountId,
                        toAccountId = destination,
                        // A premium swiped away is late, not gone: the policy is
                        // still owed it, so this one collects it — exactly as a
                        // debt's instalment does. See [carriedInto].
                        amount = Money(series.amountMinor * (1 + carriedInto(series, date))),
                        date = date,
                        note = series.note,
                        status = EntryStatus.EXPECTED,
                        seriesId = series.id,
                        debitsSource = watched,
                        creditsDestination = watched || intoPlan,
                    )
                    continue
                }
                // An instalment the user swiped away is money that is late, not
                // money that is gone: the principal it would have cleared is
                // still owed and has gone on charging interest, so this payment
                // asks for it as well. रू 10,000 dropped from July makes August
                // रू 20,000, and dropping August in turn makes September
                // रू 30,000 — see [Arrears], which the debt's own table and the
                // timeline's projection read the same way, or the row written
                // here would disagree with the two screens that promised it.
                //
                // Asked afresh for each date, and the rows written above are
                // already on file by the time the next one asks, so a back-dated
                // loan materialising a year at once still writes each instalment
                // at its own figure.
                val amountMinor = series.amountMinor * (1 + carriedInto(series, date))
                val converted = exchangeRates.convert(
                    amountMinor = amountMinor,
                    from = series.currencyCode,
                    fromMinorUnits = CurrencyOption.byCode(series.currencyCode).minorUnits,
                    base = baseCode,
                    baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
                )
                entryDao.upsert(
                    MoneyEntryEntity(
                        id = UUID.randomUUID().toString(),
                        amountMinor = amountMinor,
                        currencyCode = series.currencyCode,
                        baseAmountMinor = converted.amount.minor,
                        rateToBase = converted.rate,
                        baseCurrencyCode = baseCode,
                        direction = series.direction,
                        occurredOn = date.toEpochDay(),
                        accountId = series.accountId.takeIf { date >= watchedFrom },
                        isAdjustment = series.isAdjustment,
                        seriesId = series.id,
                        status = EntryStatus.EXPECTED,
                        note = series.note,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
            seriesDao.setMaterialisedThrough(series.id, today.toEpochDay(), now)
        }
    }

    /**
     * Puts the account back on occurrences generated without one, for rules the
     * user wrote themselves. See [MoneyEntryDao.adoptOrphanedOccurrences].
     *
     * Run on launch beside [materialiseDue], because it is the same job seen
     * from the other end: that one stops writing accountless rows, this one
     * repairs the ones already written. Interest is posted after both, since the
     * rows it repairs are movements on an account that may be earning.
     */
    suspend fun adoptOrphanedOccurrences() {
        entryDao.adoptOrphanedOccurrences(clock.nowMillis())
    }

    /**
     * Everything still to come, between tomorrow and [horizon], in date order.
     *
     * Computed, never stored — so a rule the user edits is reflected the moment
     * they save it.
     */
    suspend fun projectForward(horizon: LocalDate): List<ProjectedEntry> {
        val today = clock.today()
        val appSettings = settings.settings.first()
        val baseCode = appSettings.currencyCode
        val accounts = accountDao.observeActive().first().associateBy { it.id }
        // How many holdings each bank name covers, counted once for the whole
        // walk: a projection is named exactly as the row it becomes, and that
        // name says which of a bank's products it is only where the bank has
        // more than one. See `holdingDisplayName`.
        val siblings = accounts.values
            .groupingBy { (it.institution ?: it.name).lowercase() }
            .eachCount()
        val loanSeries = loanDao.loanSeries().associateBy { it.seriesId }

        val projected = mutableListOf<ProjectedEntry>()
        for (series in seriesDao.activeSeries()) {
            // Start after whichever is later: today, or the last date already
            // turned into a real row. Both matter. Anything up to the watermark
            // exists as an entry the user can see and confirm, so projecting it
            // as well showed the same payment twice in one month — once as
            // something that has happened and once as something still to come.
            val materialised = series.materialisedThrough?.let { LocalDate.ofEpochDay(it) }
            val from = maxOf(today, materialised ?: today).plusDays(1)
            val due = Recurrence.occurrencesBetween(
                start = LocalDate.ofEpochDay(series.startOn),
                interval = series.interval,
                from = from,
                to = horizon,
                endOn = series.endOn?.let { LocalDate.ofEpochDay(it) },
                everyMonths = series.intervalMonths,
                inBikramSambat = series.recurInBs,
            )
            if (due.isEmpty()) continue
            // A date the user has already dropped is not still to come. The
            // watermark cannot say so — a skipped date lies past it and nothing
            // else about the rule has changed — so the rows themselves are
            // asked, tombstones and all, exactly as [materialiseDue] asks before
            // writing one. Once for the whole window rather than once per date.
            // See [skipOccurrence].
            val taken = seriesDao
                .occurrenceDays(series.id, from.toEpochDay(), horizon.toEpochDay())
                .toSet()
            val dates = due.filterNot { it.toEpochDay() in taken }
            if (dates.isEmpty()) continue

            // Future money is converted at today's rate — there is no rate for a
            // date that has not happened, and pretending otherwise would be a
            // forecast dressed up as a fact.
            val converted = exchangeRates.convert(
                amountMinor = series.amountMinor,
                from = series.currencyCode,
                fromMinorUnits = CurrencyOption.byCode(series.currencyCode).minorUnits,
                base = baseCode,
                baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
            )
            val destination = series.transferToAccountId
            val account = series.accountId?.let { accounts[it] }
            val loan = loanSeries[series.id]
            // Occurrences this schedule was owed and never got — a debt's
            // instalments, a policy's premiums, a goal's contributions. They
            // fall on the **first** date still to come and on no other: that
            // payment collects them, and every one after it is the ordinary
            // figure again. Drawn here rather than only once the day arrives,
            // because somebody looking at next month has to see what is going to
            // be asked for — and it is the same figure `materialiseDue` will
            // write. See [Arrears].
            val owed = carriedInto(series, dates.first())
            val firstDue = dates.first()

            // What the other half will be worth where it lands, and the currency
            // it lands in.
            //
            // Denominated in the *destination's* own currency, not the display
            // one. Money arriving in a dollar wallet arrives as dollars, and
            // stamping it with the display currency made it invisible to that
            // wallet's own-currency projection — which only counts occurrences
            // denominated in the currency it holds — so a scheduled transfer
            // into it never moved its balance.
            //
            // Computed once per rule rather than per date: it comes off the same
            // conversion every occurrence uses. Both halves carry it, because
            // the *paying* half is the one drawn and it needs both figures to
            // show the whole movement.
            val to = destination?.let { accounts[it] }
            val arrivingCode = destination?.let { to?.currencyCode ?: baseCode }
            val arriving = arrivingCode?.let { code ->
                if (code.equals(baseCode, ignoreCase = true)) {
                    converted.amount
                } else {
                    exchangeRates.convert(
                        amountMinor = converted.amount.minor,
                        from = baseCode,
                        fromMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
                        base = code,
                        baseMinorUnits = CurrencyOption.byCode(code).minorUnits,
                    ).amount
                }
            }

            dates.forEach { date ->
                // The arrears ride on the first date and nowhere else. The
                // converted figure is scaled rather than converted again: it is
                // the same rate on the same day, and asking twice would leave
                // the two halves of one row a paisa apart.
                val instalments = if (owed > 0 && date == firstDue) 1 + owed else 1
                projected += ProjectedEntry(
                    seriesId = series.id,
                    date = date,
                    amount = Money(series.amountMinor * instalments),
                    currencyCode = series.currencyCode,
                    baseAmount = Money(converted.amount.minor * instalments),
                    direction = series.direction,
                    title = null,
                    accountId = series.accountId,
                    accountName = account?.name,
                    accountInstitution = account?.institution,
                    accountKind = account?.kind,
                    accountCurrency = account?.currencyCode,
                    accountSiblings = account?.let { siblings[(it.institution ?: it.name).lowercase()] },
                    // The account's own answer, not a default. A dollar
                    // instalment that reads as dollars once it has been paid has
                    // to read as dollars while it is still due.
                    showInDisplayCurrency = account?.showInDisplayCurrency ?: true,
                    note = series.note,
                    isAdjustment = series.isAdjustment || destination != null,
                    transferFromName = destination?.let { account?.name },
                    transferToName = destination?.let { accounts[it]?.name },
                    transferPartnerAmount = arriving?.let { Money(it.minor * instalments) },
                    transferPartnerCurrency = arrivingCode,
                    loanName = loan?.name,
                    loanKind = loan?.kind,
                    isPlanPayment = to?.kind in PLANS,
                )
                // The arriving half. Valued at the same figure in the display
                // currency as the half that left: it is the same money, and any
                // difference would be a forecast of an exchange-rate move.
                if (destination != null && arriving != null) {
                    projected += ProjectedEntry(
                        seriesId = series.id,
                        date = date,
                        // Scaled with the half that pays: a premium collecting a
                        // missed one moves twice the money, and it lands in the
                        // policy as the same twice.
                        amount = Money(arriving.minor * instalments),
                        currencyCode = arrivingCode,
                        baseAmount = Money(converted.amount.minor * instalments),
                        showInDisplayCurrency = to?.showInDisplayCurrency ?: true,
                        direction = Direction.IN,
                        title = null,
                        accountId = destination,
                        accountName = to?.name,
                        accountInstitution = to?.institution,
                        accountKind = to?.kind,
                        accountCurrency = to?.currencyCode,
                        accountSiblings = to?.let { siblings[(it.institution ?: it.name).lowercase()] },
                        note = series.note,
                        isAdjustment = true,
                        transferFromName = account?.name,
                        transferToName = to?.name,
                        // The half that pays, seen from here. No drawn row reads
                        // it, but a real transfer carries the pair on both rows
                        // and a projection that carried it on one would be the
                        // odd one out the next time something looks.
                        transferPartnerAmount = Money(series.amountMinor * instalments),
                        transferPartnerCurrency = series.currencyCode,
                        isTransferArrival = true,
                    )
                }
            }
        }
        return projected.sortedWith(compareBy({ it.date }, { it.seriesId }))
    }

    private companion object {
        /**
         * The holdings the app is the only record of, and which therefore keep
         * their side of a back-dated transfer.
         */
        val PLANS = listOf(AccountKind.INSURANCE, AccountKind.GOAL)

        /**
         * Storage keeps timestamps in millis and dates in days.
         *
         * The division is in UTC while the dates are local, so on a day the two
         * disagree this reads one day early — and an occurrence dated that day
         * keeps its account rather than losing it. That is the harmless
         * direction: the worst case is one instalment counted against a balance
         * the user was about to correct anyway.
         */
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
