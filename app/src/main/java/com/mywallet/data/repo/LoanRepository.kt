package com.mywallet.data.repo

import androidx.compose.ui.graphics.Color
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.isCardSpend
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.core.date.CalendarSystem
import com.mywallet.domain.Accrual
import com.mywallet.domain.Arrears
import com.mywallet.domain.BalanceChange
import com.mywallet.domain.BrokenPeriod
import com.mywallet.domain.Instalment
import com.mywallet.domain.LOAN_DISBURSEMENT_SUFFIX
import com.mywallet.domain.Loan
import com.mywallet.domain.LoanEntryFact
import com.mywallet.domain.LoanLedger
import com.mywallet.domain.LoanMath
import com.mywallet.domain.MovementReversal
import com.mywallet.domain.reversal
import com.mywallet.domain.LoanMovement
import com.mywallet.domain.PrepaymentOutcome
import com.mywallet.domain.RateSchedule
import com.mywallet.domain.Recurrence
import com.mywallet.domain.accrualFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A loan with its own statement: what happened to it, newest first. */
data class LoanHistory(
    val loan: Loan,
    val movements: List<LoanMovement>,
)

/** One instalment of a schedule, on the day it falls. */
data class DatedInstalment(
    val instalment: Instalment,
    /**
     * Null only on a debt whose dates the app cannot say — one entered with no
     * first-payment date at all — where the schedule is numbered instead.
     */
    val date: LocalDate?,
)

/**
 * A debt's schedule from here on: the payments still to come, and what they add
 * up to.
 *
 * The rows already made are dropped rather than greyed. They are history the
 * reader cannot change from this page, and leading with them answers "how much
 * of this payment is interest?" for a payment long gone. What is left is the
 * question the page is opened for.
 */
data class LoanSchedule(
    val name: String,
    val kind: LoanKind,
    val currencyCode: String,
    val rows: List<DatedInstalment>,
    /** Every payment handed over so far, however it was made. */
    val paymentsMade: Int,
    /** Interest across the whole schedule, the periods already paid included. */
    val totalInterest: Money,
    /** What the schedule was owed and never got — see [Arrears]. */
    val arrears: Arrears,
    /**
     * The days the instalments the first row is collecting were due on, oldest
     * first — one per missed period it carries, and the row's own date is not
     * among them.
     *
     * Carried so the split can name which instalment each date field stands for.
     * "Give me two dates" is a question about nothing; "the one due 1 Jul, and
     * the one due 1 Aug" is a question somebody can answer.
     */
    val carriedDates: List<LocalDate> = emptyList(),
    /** What one instalment is worth, which is what each split date writes. */
    val instalment: Money = Money.ZERO,
    /**
     * The earliest day a split may be dated: the day the money changed hands,
     * or the day the balance on file started running where that was never
     * recorded. The same floor every other date on a debt's form is measured
     * from — see `HoldingEditorState.movedOn`.
     */
    val movedOn: LocalDate? = null,
)

/**
 * Whether a movement's effect on a loan could be taken back.
 *
 * Deleting the row is never the whole of the job. A lump sum, more borrowed, a
 * drawdown — each one rewrote the loan's own principal figure in place, and a
 * timeline row removed without that figure being put back leaves a debt that
 * disagrees with every payment listed under it.
 */
sealed interface Reversal {
    /** The loan is back where it was — or this row never moved it. */
    data object Done : Reversal

    /**
     * A lump sum with a later one still on file, on a debt that amortises.
     *
     * Each lump sum re-bases the loan on the balance it met, so the balances
     * after it were worked out from the reduced figure. Putting the money back
     * on the *current* balance would be adding it at the wrong date and quietly
     * forgiving the interest the instalments since charged on it. The later
     * payment has to go first, and the user is told so rather than handed a debt
     * that is a few thousand rupees out.
     */
    data object LaterPaymentFirst : Reversal
}

@Singleton
class LoanRepository @Inject constructor(
    private val loanDao: LoanDao,
    private val entryDao: MoneyEntryDao,
    private val recurrence: RecurrenceRepository,
    private val interest: InterestRepository,
    private val exchangeRates: ExchangeRateRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    fun observeLoans(): Flow<List<LoanEntity>> = loanDao.observeAll()

    /** A single loan with its outstanding balance, for the editor. */
    suspend fun findLoan(id: String): Loan? =
        loanDao.findById(id)?.let { it.toDomain(outstandingOf(it)) }

    /**
     * What is still owed on [entity].
     *
     * Bank loans follow the amortisation schedule; informal ones simply subtract
     * what has been repaid. Using the schedule matters — after a year of a
     * ten-year loan, subtracting payments from the principal would understate
     * the debt by a wide margin, because most of that year went on interest.
     *
     * Instalments are counted from [LoanEntity.startedOn], the day the current
     * principal figure was set, so a loan re-based by a lump sum is not credited
     * twice for the payments that came before it.
     *
     * @param asOf the day to answer for, when the question is about the past. A
     *   lump sum paid three weeks ago met the balance as it stood *then*, and
     *   the instalments since have come off the reduced figure — asking for
     *   today's balance would apply the payment to a debt it never saw.
     *
     * **The stored figure describes today and nothing else**, which is what makes
     * a past day a question rather than a lookup. A schedule answers for any day
     * by itself — it is counted from dates — but everything a schedule does not
     * account for was written straight into `principal_minor`: a lump sum
     * re-bases the debt in place, a top-up adds to it, a drawdown and a card
     * purchase raise it. Ask such a debt what it was worth in November and it
     * answers with what it is worth now, which is how रू 8,000 lent to somebody
     * read रू 18,000 in the month before the second रू 10,000 was handed over.
     * [rewound] is the correction, and it is applied to every shape:
     *
     *  - **A running total is walked backwards from today**, movement by
     *    movement, exactly as `LoanLedger` walks it for the statement. It has to
     *    be backwards: the figure it opened at is no longer anywhere on file and
     *    only the present balance is a fact.
     *  - **A schedule needs only the movements it cannot see** put back, since it
     *    works out the rest from the rule's own dates.
     */
    private suspend fun outstandingOf(entity: LoanEntity, asOf: LocalDate? = null): Money {
        val onFile = onCurrentBasis(entity, asOf)
        if (asOf == null || entity.isClosed) return onFile
        return rewound(entity, asOf, onFile)
    }

    /**
     * What the figures on file say, reading them as they stand.
     *
     * Right for today always, and right for a past day only where nothing has
     * rewritten the loan's stored balance since — see [outstandingOf].
     */
    private suspend fun onCurrentBasis(entity: LoanEntity, asOf: LocalDate?): Money {
        val principal = Money(entity.principalMinor)
        val since = entity.startedOn
        val until = asOf?.toEpochDay() ?: Long.MAX_VALUE
        return when {
            entity.isClosed -> Money.ZERO
            // An overdraft owes exactly what has been drawn on it. There is no
            // schedule to amortise: withdrawals raise the figure and repayments
            // lower it, and both are written straight to principal_minor.
            entity.kind == LoanKind.OVERDRAFT -> principal
            entity.seriesId == null -> principal
            entity.termMonths != null && entity.termMonths > 0 -> {
                val arrears = entity.arrears(asOf)
                LoanMath.outstanding(
                    principal = principal,
                    annualRatePercent = entity.annualRate ?: 0.0,
                    termMonths = entity.termMonths,
                    periodsElapsed = arrears.periodsDue,
                    emi = entity.emiMinor?.let { Money(it) },
                    style = entity.instalmentStyle,
                    monthsPerPayment = entity.paymentEveryMonths,
                    accrual = entity.accrual(),
                    missed = arrears.missed,
                )
            }
            else -> LoanMath.outstandingSimple(
                principal, Money(loanDao.repaidSince(entity.seriesId, since, until)),
            )
        }
    }

    /**
     * [onFile] with everything that happened after [asOf] taken back off it.
     *
     * Two shapes, because a debt keeps its balance two ways — the same split
     * `LoanLedger` makes for the balance column on a statement.
     *
     * **Without a schedule** the balance is a plain running total, so every dated
     * movement after the day asked about is undone: the second रू 10,000 lent to
     * somebody in December comes back off, and November reads रू 8,000 again.
     * [onFile] is asked for *today* rather than for [asOf] on this path, since
     * walking back from today is the only direction that works — the figure the
     * debt opened at is not on file.
     *
     * **With one**, only what the schedule cannot see has to be put back: a lump
     * sum and a top-up rewrote `principal_minor` in place, and the instalments
     * either side are counted from the rule's dates and look after themselves.
     * Where the debt had not been re-based yet on the day being asked about, the
     * basis it was on then is [asFirstWrittenDown] and the schedule answers from
     * it exactly, so the months behind a lump sum go on climbing an instalment at
     * a time. Where that cannot be rebuilt, what is left is the balance the
     * payment actually met — the last figure the app can stand behind, and the
     * one thing the reader is asking about: no deduction is shown in a month
     * before the money moved.
     */
    private suspend fun rewound(entity: LoanEntity, asOf: LocalDate, onFile: Money): Money {
        val flip = entity.loanDirection == LoanDirection.LENT
        fun signed(delta: Long) = if (flip) -delta else delta
        if (!amortises(entity)) {
            val today = onCurrentBasis(entity, null)
            val since = loanDao.balanceChanges(entity.id, entity.seriesId)
                .filter { it.occurredOn > asOf.toEpochDay() }
                .sumOf { signed(it.deltaMinor) }
            return Money((today.minor - since).coerceAtLeast(0L))
        }
        val opening = "${entity.id}$DISBURSEMENT_SUFFIX"
        val since = loanDao.basisChangesAfter(entity.id, asOf.toEpochDay(), opening)
        if (since.isEmpty()) return onFile
        // Nothing had touched the basis yet on the day being asked about — every
        // re-basing this debt has ever had is still ahead of it — so what it was
        // on then is the basis it was written down with, and that one can be
        // rebuilt.
        val ever = loanDao.basisChangesAfter(entity.id, Long.MIN_VALUE, opening)
        if (since.size == ever.size) {
            asFirstWrittenDown(entity, asOf)?.let { return it }
        }
        return Money(
            (onFile.minor - since.sumOf { signed(it.deltaMinor) }).coerceAtLeast(0L)
        )
    }

    /**
     * What this debt stood at on [asOf], on the basis it was written down with —
     * or null when the app cannot rebuild that basis.
     *
     * A lump sum overwrites five things at once — the balance, the term, the
     * first-instalment date, the carried interest and where the rule starts — so
     * a re-based loan carries no record of the schedule it used to be on, and
     * "what did I owe last March" has nothing to read. Four of the five can be
     * put back from figures the re-basing deliberately leaves alone:
     *
     *  - **What was taken** is `advanced_minor`, written once on the way in.
     *  - **When it started running** is the day the money changed hands.
     *  - **When the first instalment fell** is the current one stepped back a
     *    period at a time until it is the first after the money arrived. That
     *    works because the instalment keeps the day it has always fallen on —
     *    [applyPrepayment] is careful about exactly that — so the whole schedule,
     *    before and after, sits on one cycle.
     *  - **The term** is however long that balance takes to clear at this
     *    instalment, which is the same call that shortened it in the first place.
     *
     * The fifth is the instalment itself, and it is the one thing this cannot
     * check: a borrower who chose to *lower* the instalment rather than finish
     * sooner changed the figure the rebuild leans on, and nothing on file says
     * what it used to be. Which is why this is only ever reached one re-basing
     * back, where the instalment is either untouched or the caller's safer answer
     * takes over.
     */
    private suspend fun asFirstWrittenDown(entity: LoanEntity, asOf: LocalDate): Money? {
        val principal = entity.advancedMinor?.takeIf { it > 0L }?.let { Money(it) } ?: return null
        val moved = entity.disbursedOn?.let { LocalDate.ofEpochDay(it) } ?: return null
        val emi = entity.emiMinor?.let { Money(it) }?.takeIf { it.isPositive } ?: return null
        val recovery = entity.emiStartsOn?.let { LocalDate.ofEpochDay(it) } ?: return null
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        val bs = entity.stepsInBs()
        // Back down the one cycle every instalment of this debt has ever fallen
        // on, to the first that is after the money arrived — which is the bank's
        // first recovery, stub or not.
        var first = recovery
        while (Recurrence.addMonths(first, -gap.toLong(), bs) > moved) {
            first = Recurrence.addMonths(first, -gap.toLong(), bs)
        }
        val accrual = accrualFor(
            startedOn = moved,
            // A stub first recovery charges only the days since the money
            // arrived; the schedule itself starts a period later. The same call
            // `accrual()` makes, so the rebuilt basis charges the days the
            // original one did.
            firstPaymentOn = BrokenPeriod.firstInstalment(
                moved, first, gap, entity.termMonths, inBikramSambat = bs,
            ),
            monthsPerPayment = gap,
            rates = interest.scheduleFor(entity.annualRate, loanId = entity.id)
                .takeIf { !it.isFixed },
            inBikramSambat = bs,
        ) ?: return null
        val term = LoanMath.tenureAfterPrepayment(
            outstanding = principal,
            annualRatePercent = entity.annualRate ?: 0.0,
            emi = emi,
            monthsPerPayment = gap,
            accrual = accrual,
        ) ?: return null
        // How many of that schedule's instalments had fallen by the day being
        // asked about. Counted from the rule's own dates rather than from the
        // rows, exactly as `arrears` counts them, and with nothing marked missed:
        // which periods went unpaid before a re-basing is not something the app
        // keeps, and guessing would be worse than treating the schedule as met.
        val periods = Recurrence.occurrencesBetween(
            start = accrual.firstPaymentOn,
            interval = RecurrenceInterval.MONTHLY,
            from = accrual.firstPaymentOn,
            to = asOf,
            everyMonths = gap,
            inBikramSambat = bs,
        ).size
        return LoanMath.outstanding(
            principal = principal,
            annualRatePercent = entity.annualRate ?: 0.0,
            termMonths = term,
            periodsElapsed = periods,
            emi = emi,
            style = entity.instalmentStyle,
            monthsPerPayment = gap,
            accrual = accrual,
        )
    }

    /**
     * Where this debt stands in its own schedule: how many periods have run, and
     * which of them went unpaid.
     *
     * Asked of the **rule** rather than of the payments on file, and that is the
     * whole change an instalment being deletable forced. The two used to be the
     * same number — every scheduled date becomes a row the moment it arrives —
     * so counting the rows answered "how far in is this loan?". Once one can be
     * swiped away it stops answering: the count drops by one and the whole
     * schedule slides back a month, so the next instalment is drawn on a date
     * that has already been and gone. The dates are what have actually happened;
     * the rows say which of them were paid. See [Arrears].
     *
     * @param asOf a day in the past to answer for, when the question is about a
     *   month that has been and gone. Never past today: nothing after it has had
     *   a chance to be paid, so every period beyond would read as missed.
     */
    private suspend fun LoanEntity.arrears(asOf: LocalDate? = null): Arrears {
        val sid = seriesId ?: return Arrears.NONE
        val until = minOf(asOf ?: clock.today(), clock.today())
        val series = recurrence.findSeries(sid)
            // A debt whose rule has gone has no dates left to count, so the rows
            // are all there is — which is what this counted all along.
            ?: return Arrears(
                periodsDue = loanDao.paymentsSince(sid, startedOn, until.toEpochDay()),
            )
        return recurrence.arrears(series, LocalDate.ofEpochDay(startedOn), until)
    }

    /**
     * The days this loan's instalments charge interest for, and at what rate.
     *
     * A suspending property would be neater to read than a function, but the
     * rate history has to be fetched, so this is a call.
     */
    /**
     * Puts every opted-in debt's rule back in step with the calendar now set.
     *
     * The schedule a debt is stepped in is its opt-in *and* the setting, so
     * changing the setting changes it — and the rule that generates the dates
     * carries the effective answer, not the opt-in. Left alone, the timeline
     * would go on producing occurrences in the calendar that was set when the
     * debt was saved while the debt's own table drew them in the new one: the
     * two disagreeing about the same instalment, which is the exact failure the
     * flag exists to prevent.
     *
     * Only debts that have opted in are touched. For everybody else the
     * effective answer is Gregorian either way and there is nothing to restate,
     * which is what keeps a calendar switch free for almost every user.
     * `saveSeries` rebuilds the unconfirmed occurrences itself when the flag
     * moves — see the note there about a rule that has been re-calendared.
     */
    /**
     * Writes the row for the debt arriving on any debt on file that has not got
     * one.
     *
     * The row used to be written only where the user named an account for the
     * money to land in, so on every debt where they did not — which is most of
     * them, that field being the one they leave blank on purpose — the month the
     * loan was taken out listed everything it has cost since and nothing about
     * the loan itself. It is written for all of them now, naming an account or
     * not; this is what carries that to the debts already on file, since nothing
     * else would until each was opened and saved again.
     *
     * **Only where there is no row at all**, tombstone included — which is the
     * one read in the app besides the backup merge that has to see deleted rows.
     * Deleting the debt arriving is the user saying they do not want it on the
     * page, and a launch that put it back every time would be the app arguing
     * with them once a day.
     */
    /**
     * Re-bases each debt on any payment whose day has arrived and which has not
     * been acted on yet.
     *
     * The other half of letting a payment be dated forward. A lump sum the user
     * has promised for the 30th is written down when they say so and left there —
     * there is no balance on a day that has not arrived — and this is what folds
     * it in the first time the app opens on or after it. The same shape as
     * `materialiseDue`, and it runs beside it for the same reason: it belongs to
     * the launch rather than to whichever tab happened to be opened.
     *
     * **What makes a payment pending is its date against `started_on`**, and
     * nothing is written down to say so. A re-basing moves that day to the day of
     * the payment, so every lump sum already folded in sits on it or behind it
     * and only one still owed sits ahead — which stays true across a restore, a
     * reinstall and a backup taken on another phone, where a flag would not.
     *
     * It also quietly repairs the one case the old immediate re-base got wrong:
     * a lump sum recorded *behind* one already on file re-bases on the balance it
     * met, which discards the later payment's effect — and the later payment is
     * then ahead of the mark again, so this puts it back on, in order.
     *
     * Oldest first, because each is applied to the balance the one before it
     * left; and through [applyPrepayment]'s own arithmetic rather than a copy of
     * it, so a payment folded in on the day pays exactly what it would have paid
     * on the day it was recorded.
     */
    suspend fun applyDuePayments() {
        val today = clock.today()
        for (entity in loanDao.activeLoans()) {
            var loan = entity
            for (row in loanDao.duePrincipalPayments(entity.id, today.toEpochDay())) {
                if (row.occurredOn <= loan.startedOn) continue
                val on = LocalDate.ofEpochDay(row.occurredOn)
                val amount = Money(row.deltaMinor)
                if (!amount.isPositive) continue
                if (loan.kind == LoanKind.OVERDRAFT) {
                    val paid = Money(amount.minor.coerceAtMost(loan.principalMinor))
                    loan = loan.copy(
                        principalMinor = loan.principalMinor - paid.minor,
                        startedOn = maxOf(loan.startedOn, row.occurredOn),
                        updatedAt = clock.nowMillis(),
                    )
                    loanDao.upsert(loan)
                } else {
                    rebase(loan, amount, on)
                    loan = loanDao.findById(entity.id) ?: break
                }
            }
        }
    }

    suspend fun writeMissingDisbursements() {
        val now = clock.nowMillis()
        for (entity in loanDao.activeLoans()) {
            val on = entity.disbursedOn?.let { LocalDate.ofEpochDay(it) } ?: continue
            val id = "${entity.id}$DISBURSEMENT_SUFFIX"
            if (entryDao.findAnyById(id) != null) continue
            // **What changed hands, not what is owed.** `principal_minor` stops
            // meaning "the sum borrowed" the moment a lump sum re-bases the debt
            // or more is borrowed on it — which is exactly the state every debt
            // this backfill reaches is likely to be in. `advanced_minor` is
            // everything ever put out, so what it opened at is that less whatever
            // was added since.
            val flip = entity.loanDirection == LoanDirection.LENT
            val changes = loanDao.basisChangesAfter(entity.id, Long.MIN_VALUE, id)
                .map { if (flip) -it.deltaMinor else it.deltaMinor }
            val added = changes.filter { it > 0L }.sum()
            val amount = when {
                entity.advancedMinor != null -> Money(entity.advancedMinor - added)
                // Nothing has moved the stored figure, so it is still the one the
                // debt was written down with. A debt from before the column that
                // records what was advanced *and* with something behind it has no
                // honest answer left, and inventing one would put the shrunken
                // figure on the page as the sum somebody agreed to.
                changes.isEmpty() -> Money(entity.principalMinor)
                else -> continue
            }
            writeDisbursement(entity, on, now, amount)
        }
    }

    suspend fun recalendarSchedules() {
        for (entity in loanDao.activeLoans()) {
            if (!entity.recurInBs) continue
            val seriesId = entity.seriesId ?: continue
            val series = recurrence.findSeries(seriesId) ?: continue
            val steps = entity.stepsInBs()
            if (series.recurInBs == steps) continue
            recurrence.setRecurInBs(seriesId, steps)
        }
    }

    /**
     * Which calendar a holding that has opted in is actually counted in.
     *
     * The opt-in alone is not the answer and neither is the setting: a bank only
     * counts in Nepali months if its borrower said so *and* that is the calendar
     * being read. See [CalendarSystem.forInterest].
     */
    private suspend fun effectiveCalendar(optedIn: Boolean): CalendarSystem =
        CalendarSystem.forInterest(optedIn, settings.settings.first().calendarSystem)

    /** Whether this debt's schedule steps in Nepali months, right now. */
    private suspend fun LoanEntity.stepsInBs(): Boolean =
        effectiveCalendar(recurInBs) == CalendarSystem.BIKRAM_SAMBAT

    private suspend fun LoanEntity.accrual(): Accrual? = accrualFor(
        startedOn = LocalDate.ofEpochDay(startedOn),
        firstPaymentOn = BrokenPeriod.firstInstalment(
            disbursedOn?.let { LocalDate.ofEpochDay(it) },
            emiStartsOn?.let { LocalDate.ofEpochDay(it) },
            paymentEveryMonths,
            termMonths,
            inBikramSambat = stepsInBs(),
        ),
        monthsPerPayment = paymentEveryMonths,
        carriedInterest = Money(carriedInterestMinor),
        // Only worth carrying when the bank has actually moved it; a fixed-rate
        // loan is described completely by the single figure beside it.
        rates = interest.scheduleFor(annualRate, loanId = id).takeIf { !it.isFixed },
        // The schedule's own months. Every date below is stepped through it, so
        // the periods this charges interest over land on the very days the rule
        // produces.
        inBikramSambat = stepsInBs(),
    )

    /**
     * The next instalment strictly after [on], on the day it has always fallen.
     *
     * Read from the loan's own repeating rule, which is the schedule the bank
     * actually debits; the stored first-instalment date is the fallback for a
     * loan whose rule has gone. Asked in one place because two callers need the
     * same answer — the preview and the save — and a preview that guessed a
     * different date would promise a balance the save would not produce.
     */
    private suspend fun nextInstalmentAfter(entity: LoanEntity, on: LocalDate): LocalDate? {
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        val anchor = entity.seriesId
            ?.let { recurrence.findSeries(it) }
            ?.let { LocalDate.ofEpochDay(it.startOn) }
            ?: entity.accrual()?.firstPaymentOn
            ?: return null
        // Stepped by the loan's own gap in months, whatever it is. The named
        // intervals cannot say "every two months", and falling back to monthly
        // would offer a next instalment on a day the bank never debits.
        return Recurrence.nextAfter(
            anchor, RecurrenceInterval.MONTHLY, after = on, everyMonths = gap,
        )
    }

    /**
     * Interest that has run on the balance since the last instalment before [on].
     *
     * What a lump sum paid between instalments finds already charged, on the
     * larger balance. It is collected with the next instalment rather than added
     * to the debt — see [Accrual.carriedInterest].
     */
    private suspend fun accruedBefore(entity: LoanEntity, on: LocalDate): Money {
        val accrual = entity.accrual() ?: return Money.ZERO
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        // Periods rather than payments: what these days ran *from* is the last
        // date the schedule billed, and one whose instalment was deleted still
        // billed. Counting the payments would walk back to a date a month before
        // the debt was really last billed on, and charge that month twice.
        val paid = entity.arrears(on).periodsDue
        // The last payment date on or before [on]; before the first one, the day
        // the balance itself started running.
        val lastPaid = if (paid <= 0) {
            accrual.from
        } else {
            Recurrence.addMonths(
                accrual.firstPaymentOn, (paid - 1).toLong() * gap, accrual.inBikramSambat,
            )
        }
        // Through the rate history, so a lump sum paid days after the bank moved
        // the rate is charged at the rate those days were actually on.
        return interest.scheduleFor(entity.annualRate, loanId = entity.id)
            .interest(outstandingOf(entity, asOf = on), from = lastPaid, to = on)
    }

    /**
     * Whether anything has actually happened to this loan yet.
     *
     * Asked so the editor can withhold the way into the statement rather than
     * offering a page that turns out to be empty — on money between people that
     * is the normal state for a while, since nothing is scheduled and nothing
     * has been paid back yet.
     */
    suspend fun hasMovements(loanId: String): Boolean {
        val entity = loanDao.findById(loanId) ?: return false
        return loanDao.movementCount(loanId, entity.seriesId) > 0
    }

    /**
     * How many payments have actually been handed over against this debt.
     *
     * Deliberately not [Loan.paymentsMade], which counts instalments since
     * `started_on` and is the *schedule's* own bookkeeping: a lump sum re-bases
     * the loan and moves that day to itself, so the figure drops to nothing and
     * the form said "1 already paid before this" under a debt with a year of
     * payments behind it. And it never counted the charge for the broken first
     * period, which comes from no series at all.
     *
     * This is the question the user is asking: every instalment, every lump sum,
     * and every interest charge serviced on its own, since the debt began.
     * Classified by [LoanLedger.kindOf] rather than by a condition of its own,
     * so it agrees with the statement the user can open and count for themselves.
     */
    suspend fun paymentsMade(loanId: String): Int {
        val entity = loanDao.findById(loanId) ?: return 0
        return loanDao.movements(loanId, entity.seriesId).count { row ->
            LoanLedger.kindOf(
                isOpening = row.entry.id == "$loanId$DISBURSEMENT_SUFFIX",
                part = row.entry.loanPart,
                fromSeries = row.entry.seriesId != null &&
                    row.entry.seriesId == entity.seriesId,
                isAdjustment = row.entry.isAdjustment,
                isSpend = row.entry.isCardSpend,
            ).isPayment
        }
    }

    /**
     * What is left of a debt's schedule, on a page of its own.
     *
     * The same arithmetic the editor previews while a loan is being *created* —
     * it must be, or a borrower would read one figure while typing and another
     * one after saving — but built from the loan on file rather than from the
     * boxes, because by the time this page can be opened the boxes are settled
     * text and the loan has a history the form knows nothing about: what it has
     * been re-based to, what a lump sum left carried, and which instalments went
     * unpaid.
     *
     * Null on anything with no schedule to show: an overdraft, a bare IOU, money
     * between people handed back in one go.
     */
    suspend fun scheduleFor(loanId: String): LoanSchedule? {
        val entity = loanDao.findById(loanId) ?: return null
        val term = entity.termMonths ?: return null
        if (term <= 0 || entity.kind == LoanKind.OVERDRAFT || entity.isClosed) return null
        // A debt handed back in one go has no schedule to list — money between
        // people, which is most of them, and a term loan whose payment period is
        // the whole term. A null instalment is how the app says so, and it is
        // load-bearing: computing one here would draw a table of payments nobody
        // ever agreed to make.
        if (entity.emiMinor == null || entity.paymentEveryMonths >= term) return null
        val accrual = entity.accrual()
        val arrears = entity.arrears()
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        val rows = LoanMath.schedule(
            principal = Money(entity.principalMinor),
            annualRatePercent = entity.annualRate ?: 0.0,
            termMonths = term,
            emi = Money(entity.emiMinor),
            style = entity.instalmentStyle,
            monthsPerPayment = entity.paymentEveryMonths,
            accrual = accrual,
            missed = arrears.missed,
        )
        if (rows.isEmpty()) return null
        val stepsInBs = entity.stepsInBs()
        return LoanSchedule(
            name = entity.name,
            kind = entity.kind,
            currencyCode = entity.currencyCode,
            // Every period that has fallen due is behind us — the ones that went
            // unpaid included, since their money is inside the payment that
            // collects them and drawing them again would ask for it twice.
            rows = rows.drop(arrears.periodsDue).map { row ->
                DatedInstalment(
                    instalment = row,
                    // Counted from the first *full* instalment, not from the
                    // bank's first recovery date: where those differ, the day in
                    // between settled a broken period and is not one of these
                    // rows. Each payment falls one interval after the one before,
                    // in the schedule's own months, so the dates here and the
                    // days the interest was charged over cannot disagree.
                    date = accrual?.firstPaymentOn?.let {
                        Recurrence.addMonths(it, (row.number - 1).toLong() * gap, stepsInBs)
                    },
                )
            },
            paymentsMade = paymentsMade(loanId),
            totalInterest = Money(rows.sumOf { it.interest.minor }),
            arrears = arrears,
            // The days those carried instalments were due on. They are the run
            // of missed periods at the end of what has fallen due, which is
            // exactly what `carriedForward` counts, so the last few due dates
            // are them — read from the rule, because the schedule's own row
            // numbering and the rule's dates are the same sequence.
            carriedDates = entity.seriesId
                ?.takeIf { arrears.carriedForward > 0 }
                ?.let { recurrence.findSeries(it) }
                ?.let { series ->
                    recurrence
                        .dueDates(series, LocalDate.ofEpochDay(entity.startedOn), clock.today())
                        .takeLast(arrears.carriedForward)
                }
                .orEmpty(),
            // Never null here: the guard above returns for a debt with no
            // instalment, so a fallback would only hide that rule moving.
            instalment = Money(entity.emiMinor),
            movedOn = LocalDate.ofEpochDay(entity.disbursedOn ?: entity.startedOn),
        )
    }

    /**
     * Puts missed instalments back on the days they were really paid, so the
     * next payment stops collecting them.
     *
     * Rolling a deleted instalment onto the next one is the right default —
     * money that is late is still owed — but it is not always what happened. The
     * bank often took it a fortnight late, or on some day the borrower's
     * standing order finally cleared, and then the honest record is a payment on
     * *that* day rather than a doubled figure next month. Only the day is asked
     * for: what an instalment is worth is the schedule's answer, not the user's.
     *
     * Each date writes an occurrence of the loan's own rule, exactly as
     * [RecurrenceRepository.materialiseDue] would have — the same amount, the
     * same rule, and the account decided by the same cutoff, since a row dated
     * before the app was watching that account must not debit it however it came
     * to be written. `CONFIRMED` rather than `EXPECTED`, because this is the user
     * taking the row over from the rule: a rule edit discards its own `EXPECTED`
     * rows, and these would quietly go missing again.
     *
     * Dates outside `[the day the money moved, today]` are dropped rather than
     * clamped. A day that has not arrived cannot be the day something already
     * happened — the arrears would stand and there would be a phantom payment
     * beside them — and the pickers grey both bounds out, so a date landing here
     * outside them is a caller's mistake and not a user's.
     */
    suspend fun splitArrears(loanId: String, dates: List<LocalDate>) {
        if (dates.isEmpty()) return
        val entity = loanDao.findById(loanId) ?: return
        val series = entity.seriesId?.let { recurrence.findSeries(it) } ?: return
        val today = clock.today()
        val floor = LocalDate.ofEpochDay(entity.disbursedOn ?: entity.startedOn)
        // No more of them than are actually owed: two date fields answered on a
        // row collecting two is the whole of what this can do, and anything past
        // that would be a payment against nothing.
        val owed = entity.arrears().carriedForward
        val usable = dates
            .filter { !it.isBefore(floor) && !it.isAfter(today) }
            .sorted()
            .take(owed)
        if (usable.isEmpty()) return
        val now = clock.nowMillis()
        val baseCode = settings.settings.first().currencyCode
        for (date in usable) {
            val converted = exchangeRates.convert(
                amountMinor = series.amountMinor,
                from = series.currencyCode,
                fromMinorUnits = CurrencyOption.byCode(series.currencyCode).minorUnits,
                base = baseCode,
                baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
            )
            entryDao.upsert(
                MoneyEntryEntity(
                    id = UUID.randomUUID().toString(),
                    amountMinor = series.amountMinor,
                    currencyCode = series.currencyCode,
                    baseAmountMinor = converted.amount.minor,
                    rateToBase = converted.rate,
                    baseCurrencyCode = baseCode,
                    direction = series.direction,
                    occurredOn = date.toEpochDay(),
                    accountId = recurrence.accountForOccurrence(series, date),
                    isAdjustment = series.isAdjustment,
                    seriesId = series.id,
                    status = EntryStatus.CONFIRMED,
                    note = series.note,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        // Each one landed in an account on a day a closed period was counted
        // over, so what that period earned has to be worked out again.
        usable.minOrNull()?.let { interest.repostIfBefore(it) }
    }

    /** The loan a repeating rule pays for, if it pays for one. */
    suspend fun findLoanBySeries(seriesId: String): Loan? =
        loanDao.findBySeries(seriesId)?.let { it.toDomain(outstandingOf(it)) }

    /**
     * A loan and everything that has happened to it, for the ledger.
     *
     * The two are read together because they have to agree: the last row's
     * balance is the loan's own outstanding figure, and reading them from two
     * different moments would let the bottom of the table contradict the top.
     */
    suspend fun history(loanId: String): LoanHistory? {
        val entity = loanDao.findById(loanId) ?: return null
        val loan = entity.toDomain(outstandingOf(entity))
        val facts = loanDao.movements(loanId, entity.seriesId).map { row ->
            LoanEntryFact(
                entryId = row.entry.id,
                date = LocalDate.ofEpochDay(row.entry.occurredOn),
                createdAt = row.entry.createdAt,
                amount = Money(row.entry.amountMinor),
                currencyCode = row.entry.currencyCode,
                baseAmount = Money(row.entry.baseAmountMinor),
                isAdjustment = row.entry.isAdjustment,
                part = row.entry.loanPart,
                // The debt arriving, not more of it: told apart by the id the
                // entry was written with, since nothing about its shape differs.
                isOpening = row.entry.id == "${entity.id}$DISBURSEMENT_SUFFIX",
                isSpend = row.entry.isCardSpend,
                fromSeries = row.entry.seriesId != null &&
                    row.entry.seriesId == entity.seriesId,
                accountId = row.entry.accountId,
                accountName = row.accountName,
                note = row.entry.note,
            )
        }
        return LoanHistory(
            loan = loan,
            movements = LoanLedger.of(
                loan = loan,
                countingFrom = LocalDate.ofEpochDay(entity.startedOn),
                facts = facts,
            ),
        )
    }

    /**
     * The instalment a lender would quote, or null when the loan has no schedule.
     * Exposed so the form can show it live as the user types.
     */
    fun quotedInstalment(
        principal: Money,
        annualRate: Double?,
        termMonths: Int?,
        style: InstalmentStyle,
        monthsPerPayment: Int = 1,
    ): Money? {
        if (termMonths == null || termMonths <= 0) return null
        return LoanMath.instalment(
            principal, annualRate ?: 0.0, termMonths, style, monthsPerPayment,
        )
    }

    /**
     * Every loan with its outstanding balance worked out.
     *
     * Recomputed when an entry changes as well as when a loan does: the balance
     * comes from the payments made against the loan, and watching the loan table
     * alone left a debt frozen at yesterday's figure while the account it is paid
     * from had already moved. See [LoanDao.observeEntryRevision].
     */
    /**
     * @param asOf the day to answer for, when the question is about a month
     *   that has already been and gone. The timeline steps backwards as well as
     *   forwards, and a debt drawn under June's payments has to say what was
     *   owed at the end of June — a row still reading today's balance sat under
     *   an account balance that had been wound back and contradicted it.
     *
     *   It moves the *schedule* back, which is the figure every list prints:
     *   one fewer instalment counted for each month stepped over, so the debt
     *   climbs going backwards exactly as it fell coming forwards. Interest
     *   metered day by day on a debt with no schedule still reads today — no
     *   past-month row prints it, and a balance walked back out of dated
     *   movements is a different question from this one.
     */
    fun observeLoansWithBalance(asOf: LocalDate? = null): Flow<List<Loan>> =
        combine(
            loanDao.observeAll(),
            loanDao.observeEntryRevision(),
            // A rate the bank moved changes what every instalment since has
            // bought, so the debts have to be worked out again.
            interest.observeRateRevision(),
        ) { rows, _, _ -> rows }
            .map { rows ->
                rows.map { entity -> entity.toDomain(outstandingOf(entity, asOf), asOf) }
            }

    /**
     * Creates a loan and schedules its instalment as an ordinary repeating
     * series.
     *
     * Nothing is recorded arriving in an account unless the user says where.
     * Borrowing does not reliably put money anywhere the app can see — plenty of
     * loans are disbursed straight to a seller, and plenty were taken years
     * before the user installed this — so the app records the debt and leaves the
     * account to be corrected by whoever knows what actually landed in it.
     * [disbursedAccountId] is how they say so when they do know, and the
     * movement is written only when it is answered. See [writeDisbursement].
     *
     * @param paymentEveryMonths months between instalments. Feeds both the
     *   arithmetic and the repeating rule, so the two cannot disagree about how
     *   often the loan is paid.
     * @param creditLimit the approved ceiling, for an overdraft. An overdraft is
     *   the one kind where [principal] is not what the user typed: they name the
     *   limit, and the balance starts at zero and moves as they draw on it.
     */
    suspend fun saveLoan(
        id: String?,
        name: String,
        kind: LoanKind,
        direction: LoanDirection,
        lender: String?,
        principal: Money,
        currencyCode: String,
        annualRate: Double?,
        termMonths: Int?,
        paymentEveryMonths: Int,
        creditLimit: Money? = null,
        emi: Money?,
        style: InstalmentStyle,
        emiStartsOn: LocalDate?,
        disbursedOn: LocalDate?,
        /**
         * The day a card or an overdraft was approved — see the column. Ignored
         * on every other kind of debt, which has no shelf life to work out.
         */
        openedOn: LocalDate? = null,
        dueOn: LocalDate?,
        payFromAccountId: String?,
        disbursedAccountId: String? = null,
        startedOn: LocalDate,
        showInDisplayCurrency: Boolean,
        /**
         * The colour a card is drawn in. Only a card asks — see the column —
         * and null anywhere else leaves the debt on the colour of its own
         * figure, which is what every debt had before this.
         */
        colorArgb: Int? = null,
        /**
         * Whether this bank counts the debt's months in Nepali ones. The
         * borrower's own answer about their arrangement, not a reading of the
         * calendar setting — see [CalendarSystem.forInterest].
         */
        interestInBs: Boolean = false,
        note: String?,
    ): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.NameRequired
        val isOverdraft = kind == LoanKind.OVERDRAFT
        // An overdraft is approved before it is used, so a zero balance is the
        // normal state of a new one. What it must have is a ceiling.
        if (isOverdraft) {
            if (creditLimit == null || creditLimit.minor <= 0L) return SaveResult.AmountRequired
        } else if (principal.minor <= 0L) {
            return SaveResult.AmountRequired
        }

        val now = clock.nowMillis()
        val existing = id?.let { loanDao.findById(it) }
        val loanId = existing?.id ?: UUID.randomUUID().toString()
        val gap = paymentEveryMonths.coerceAtLeast(1)
        // What is actually owed. On an overdraft that is whatever has been drawn
        // so far, never the figure in the form — editing the limit must not
        // rewrite the balance.
        val owed = if (isOverdraft) {
            Money(existing?.principalMinor ?: 0L)
        } else {
            principal
        }

        // Money between people is handed back in one go — nobody sets up an EMI
        // with their sister — so no instalment is computed for one however long
        // it was agreed for. A length there sets the interest, not a schedule,
        // and a null instalment beside a due date is what makes the forecast
        // carry the whole balance as a single occurrence.
        val betweenPeople = kind == LoanKind.PERSONAL
        // The instalment: the lender's own figure if given, otherwise ours.
        val instalment = if (betweenPeople) {
            null
        } else {
            emi ?: quotedInstalment(principal, annualRate, termMonths, style, gap)
        }
        // Whether this debt's months are Nepali ones — the borrower's own answer
        // about their bank, stored as the opt-in it is, and the effective
        // calendar derived from it and the setting together. See
        // [CalendarSystem.forInterest]: a bank debits on a fixed day of the
        // English month whatever patro is being read, so the display choice
        // alone must never move a due date.
        val recurInBs = interestInBs
        val stepsInBs = effectiveCalendar(recurInBs) == CalendarSystem.BIKRAM_SAMBAT

        // Where the amortisation really begins. On a loan whose first recovery
        // date falls short of a whole period, the payment on that date settles
        // the days since the money arrived and nothing else — see [BrokenPeriod].
        val firstRecoveryOn = emiStartsOn.takeIf { !isOverdraft }
        val stubApplies =
            BrokenPeriod.applies(disbursedOn, firstRecoveryOn, gap, termMonths, stepsInBs)
        val firstInstalmentOn =
            BrokenPeriod.firstInstalment(disbursedOn, firstRecoveryOn, gap, termMonths, stepsInBs)

        // What a payment towards this debt is filed under.
        //
        var seriesId = existing?.seriesId
        // What the standing payment is worth. On an equal-principal loan the
        // payment falls each time, so this is the first one — the largest — and
        // each occurrence waits to be confirmed and corrected anyway.
        val scheduledAmount = if (termMonths != null && termMonths > 0) {
            LoanMath.schedule(principal, annualRate ?: 0.0, termMonths, emi, style, gap)
                .firstOrNull()?.payment ?: instalment
        } else {
            instalment
        }
        // A loan with no instalments but a date to clear by gets a rule with a
        // single occurrence for the whole balance. It costs nothing extra — the
        // projection, the account it comes out of, and confirming it when it
        // happens all already work — and a lump sum falling due next month is
        // precisely what the forecast is for.
        val settlesInOneGo = instalment == null && dueOn != null
        if (isOverdraft) {
            // Deliberately no rule at all. An overdraft has nothing due on any
            // date until money is taken from it, so a projected payment here
            // would be the app inventing a debt the user has not incurred.
            seriesId = null
        } else if (settlesInOneGo) {
            seriesId = recurrence.saveSeries(
                id = seriesId,
                amount = principal,
                currencyCode = currencyCode,
                direction = if (direction == LoanDirection.LENT) Direction.IN else Direction.OUT,
                isAdjustment = direction == LoanDirection.LENT,
                interval = RecurrenceInterval.MONTHLY,
                startOn = dueOn,
                // Ends the day it starts, which is how a rule says "just once".
                endOn = dueOn,
                usesSelectedCalendar = recurInBs,
                accountId = payFromAccountId,
                note = trimmed,
                // Held back until the loan itself is on file — see below.
                materialiseNow = false,
            )
        } else if (scheduledAmount != null && instalment != null && firstInstalmentOn != null) {
            seriesId = recurrence.saveSeries(
                id = seriesId,
                amount = scheduledAmount,
                currencyCode = currencyCode,
                // An instalment on money the user lent out arrives rather than
                // leaves, and is not income: most of it is their own money coming
                // back. See [recordPayment].
                direction = if (direction == LoanDirection.LENT) Direction.IN else Direction.OUT,
                isAdjustment = direction == LoanDirection.LENT,
                // The gap in months, exactly as the loan states it, so the dates
                // the rule produces and the periods the arithmetic charges for
                // are the same thing said twice rather than two answers.
                interval = RecurrenceInterval.MONTHLY,
                intervalMonths = gap,
                // The first *full* instalment, which on a loan whose first
                // recovery is a broken period is one interval after it. The
                // stub itself is not an instalment and must not be one of the
                // schedule's payments — counting it would clear the loan a
                // month early having paid off no principal at all.
                startOn = firstInstalmentOn,
                // A fixed-term loan stops paying itself once it is cleared,
                // rather than quietly running forever. Counted in payments and
                // then converted back to months, so a quarterly loan ends after
                // its last instalment and not two months early.
                endOn = termMonths?.let {
                    Recurrence.addMonths(
                        firstInstalmentOn,
                        ((LoanMath.payments(it, gap) - 1) * gap).toLong(),
                        stepsInBs,
                    )
                },
                accountId = payFromAccountId,
                note = trimmed,
                usesSelectedCalendar = recurInBs,
                materialiseNow = false,
            )
        }

        val entity = LoanEntity(
            id = loanId,
            name = trimmed,
            kind = kind,
            loanDirection = direction,
            lender = lender?.trim()?.takeIf { it.isNotEmpty() },
            principalMinor = owed.minor,
            currencyCode = currencyCode.uppercase(),
            annualRate = annualRate?.takeIf { it > 0.0 },
            termMonths = termMonths?.takeIf { it > 0 },
            paymentEveryMonths = gap,
            creditLimitMinor = creditLimit?.minor.takeIf { isOverdraft },
            colorArgb = colorArgb.takeIf { isOverdraft },
            emiMinor = instalment?.minor.takeIf { !isOverdraft },
            instalmentStyle = style,
            emiStartsOn = emiStartsOn?.toEpochDay()
                .takeIf { !isOverdraft && !betweenPeople },
            disbursedOn = disbursedOn?.toEpochDay(),
            // Only a facility has one, and it stays editable there: nothing is
            // computed from it but the day the card runs out, so correcting it —
            // or moving it on when the bank renews — rewrites no history.
            openedOn = openedOn?.toEpochDay().takeIf { isOverdraft },
            // A fact about the last lump sum, not about the form: editing the
            // rate or the term must not discharge interest already run.
            carriedInterestMinor = existing?.carriedInterestMinor ?: 0L,
            // The rule's own answer, kept beside it so every reader of this
            // loan's dates can see it without reaching for the series.
            recurInBs = recurInBs,
            dueOn = dueOn?.toEpochDay().takeIf { !isOverdraft },
            payFromAccountId = payFromAccountId,
            // Asked on the way in, wherever there is a day the money moved, and
            // kept afterwards because more of the same arrangement moves
            // through it. Never on an overdraft: money leaves one by being
            // drawn, which is a dated movement of its own.
            disbursedAccountId = disbursedAccountId.takeIf { !isOverdraft }
                ?: existing?.disbursedAccountId,
            // What was taken, written once on the way in and never again from
            // this form. On an existing debt the figure in the box is today's
            // balance — a lump sum rewrote it — so passing it through would
            // restate the sum borrowed as whatever is left of it. An old loan
            // carries a null and keeps it: see [LoanEntity.advancedMinor].
            advancedMinor = if (existing == null) {
                owed.minor.takeIf { !isOverdraft }
            } else {
                existing.advancedMinor
            },
            seriesId = seriesId,
            // An existing loan keeps the day its principal figure was set.
            // Re-dating it to today would drop every instalment already paid out
            // of the count, and the balance would jump back up to the principal.
            //
            // The other direction matters just as much: a loan is often typed in
            // long after its schedule began, and an instalment confirmed for one
            // of those earlier dates is a payment against this very principal.
            // The cutoff therefore never sits after the schedule's own start —
            // left at the day of entry, a first EMI dated before it would be
            // silently dropped from the count and the debt would never fall.
            // A re-based loan is unaffected: its next instalment is always after
            // the re-base day, so the minimum keeps the re-base cutoff.
            startedOn = listOfNotNull(
                existing?.startedOn ?: startedOn.toEpochDay(),
                disbursedOn?.toEpochDay().takeIf { existing == null },
                emiStartsOn?.toEpochDay().takeIf { !isOverdraft },
                dueOn?.toEpochDay().takeIf { !isOverdraft },
            ).min(),
            showInDisplayCurrency = showInDisplayCurrency,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        loanDao.upsert(entity)
        // Only on the way in. A debt already on file has had this recorded once,
        // and its principal figure is no longer the sum that changed hands — a
        // lump sum rewrites it in place — so rewriting the movement from the
        // form would restate history as whatever is owed today.
        if (existing == null) {
            writeDisbursement(entity = entity, on = disbursedOn, now = now)
        }
        writeBrokenPeriod(
            entity = entity,
            applies = stubApplies && !isOverdraft,
            disbursedOn = disbursedOn,
            firstRecoveryOn = firstRecoveryOn,
            now = now,
        )
        // The instalments, now that there is a loan to read them against.
        //
        // The rule was written a few lines above and asked not to generate
        // anything yet: the loan whose disbursement account decides whether a
        // back-dated instalment names an account did not exist at that point, so
        // every one of them came out naming none — the whole of a savings
        // account that took a रू 27,00,000 loan in September and never paid a
        // rupee of it back. See [RecurrenceRepository.materialiseDue].
        recurrence.materialiseDue()
        // And then the interest those movements earned.
        //
        // A back-dated loan is the largest thing in the app that writes into an
        // account it has never touched before: the money arriving last September,
        // the broken-period charge, and every instalment since — eleven months of
        // movements, all of them inside periods the bank has already paid
        // interest on. Nothing asked for that interest to be worked out, because
        // it only ever was at launch, so the account sat there with the right
        // balance and no interest until the app was next killed and reopened.
        interest.postDueInterest()
        return SaveResult.Success(loanId)
    }

    /**
     * The money changing hands on the day the debt was made.
     *
     * Written only when the user names the account, and never inferred. Nothing
     * about a loan says where the money went — a bank loan is as often paid
     * straight to a seller as into an account, and one entered years late sits
     * against a balance the user has already corrected by hand — so a credit the
     * app invented would leave them hunting for money that never landed. What
     * the field does is let them say when it *did*: "I lent Sita 8,000" almost
     * always means 8,000 left an account on a day, and a disbursement that
     * really was paid into the salary account is the same fact about a bank
     * loan. Leaving it out made the user record the same movement twice, once as
     * the debt and once by hand.
     *
     * An overdraft has none of this: nothing arrives from one until it is drawn
     * on, and that writes its own dated movement.
     *
     * An **adjustment**, both ways round and for the same reason a drawdown is:
     * borrowing is not earning and lending is not spending. Only balances move;
     * the month's income and spending stay honest.
     *
     * It carries `loan_id` and no `loan_part`, which is exactly what
     * [increaseLoan] writes — the ledger reads it as the debt's opening
     * movement, and the interest meter walks back through it to the day the
     * money actually moved. Its id is derived from the loan's so it can never be
     * written twice.
     *
     * Unlike an instalment or a broken-period charge, this one may name an
     * account for a day before the app was told about the loan. Those two are
     * generated *for* the user from a schedule, where a back-dated debit silently
     * subtracts money the account has long since been corrected for; this is the
     * user answering "where did it go", on an optional field they leave blank
     * precisely when the balance already accounts for it.
     */
    private suspend fun writeDisbursement(
        entity: LoanEntity,
        on: LocalDate?,
        now: Long,
        /**
         * What changed hands, where that is no longer [LoanEntity.principalMinor].
         *
         * It is the principal on the way in, which is the only time this is called
         * from a save — and it stops being the principal the moment anything
         * happens to the debt, so the backfill works it out and passes it.
         */
        amount: Money = Money(entity.principalMinor),
    ) {
        if (entity.kind == LoanKind.OVERDRAFT) return
        val date = on ?: return
        if (!amount.isPositive) return

        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = entity.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(entity.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        entryDao.upsert(
            MoneyEntryEntity(
                id = "${entity.id}$DISBURSEMENT_SUFFIX",
                amountMinor = amount.minor,
                currencyCode = entity.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                // Borrowing puts money in the account; lending takes it out.
                direction = if (entity.loanDirection == LoanDirection.LENT) {
                    Direction.OUT
                } else {
                    Direction.IN
                },
                occurredOn = date.toEpochDay(),
                // Null where the user did not say, which is a real answer and no
                // longer a reason to withhold the row: an entry naming no holding
                // moves no balance, and the debt arriving is the largest thing
                // that ever happens to it. Withheld, the month a loan was taken
                // out listed everything it has cost since and nothing about the
                // loan itself — the timeline's own account of September had the
                // interest for eighteen days in it and no sign of the
                // रू 27,00,000 those days were charged on.
                accountId = entity.disbursedAccountId,
                isAdjustment = true,
                status = EntryStatus.CONFIRMED,
                loanId = entity.id,
                // Deliberately no loan_part: this is not a payment against the
                // debt, it is the debt arriving. Tagging it PRINCIPAL would count
                // the money as having paid off the very loan it created.
                note = entity.name,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    /**
     * Records — or withdraws — the payment that settles the broken period.
     *
     * It is written as **interest serviced on its own**, because that is exactly
     * what it is: money leaves the account and the balance does not move, which
     * is already how the app treats interest paid outright. Filing it as an
     * instalment would have the schedule count a payment that cleared no
     * principal, and the debt would read a month ahead of itself forever.
     *
     * The id is derived from the loan's, so saving the form again corrects the
     * row rather than adding a second one, and moving the disbursement date
     * rewrites the charge instead of leaving the old one behind. Withdrawn as a
     * tombstone when the dates stop implying a broken period at all.
     */
    /**
     * The day the app became [accountId]'s bookkeeper as far as this debt goes.
     *
     * The day the loan was written down, normally: rows the app generates for
     * itself before that came out of an account it was not watching, whose
     * balance the user has since told it directly, and debiting it again
     * subtracts money long gone.
     *
     * Earlier where the disbursement was recorded **into that same account** —
     * that is the user saying the money landed there on a day, which makes the
     * balance they typed what it held *before* it, and everything the schedule
     * has taken since has not come out of it yet.
     *
     * Written once and read by both places that generate such rows, because a
     * charge that names an account beside instalments that do not is an account
     * that pays the interest and never the loan.
     */
    private fun LoanEntity.watchedFrom(accountId: String): LocalDate {
        val written = LocalDate.ofEpochDay(createdAt / MILLIS_PER_DAY)
        val landed = disbursedOn
            ?.takeIf { disbursedAccountId == accountId }
            ?.let { LocalDate.ofEpochDay(it) }
        return landed?.let { minOf(written, it) } ?: written
    }

    private suspend fun writeBrokenPeriod(
        entity: LoanEntity,
        applies: Boolean,
        disbursedOn: LocalDate?,
        firstRecoveryOn: LocalDate?,
        now: Long,
    ) {
        val entryId = "${entity.id}$BROKEN_PERIOD_SUFFIX"
        val interest = if (applies && disbursedOn != null && firstRecoveryOn != null) {
            LoanMath.brokenPeriodInterest(
                principal = Money(entity.principalMinor),
                annualRatePercent = entity.annualRate ?: 0.0,
                disbursedEpochDay = disbursedOn.toEpochDay(),
                firstRecoveryEpochDay = firstRecoveryOn.toEpochDay(),
            )
        } else {
            Money.ZERO
        }
        if (!interest.isPositive || firstRecoveryOn == null) {
            entryDao.softDelete(entryId, now)
            return
        }
        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = interest.minor,
            from = entity.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(entity.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        entryDao.upsert(
            MoneyEntryEntity(
                id = entryId,
                amountMinor = interest.minor,
                currencyCode = entity.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                // Money going out on a debt is spending, and money coming in on
                // one lent out is genuinely earned — interest received is the one
                // part of a repayment that is not the user's own money returning.
                direction = if (entity.loanDirection == LoanDirection.LENT) {
                    Direction.IN
                } else {
                    Direction.OUT
                },
                occurredOn = firstRecoveryOn.toEpochDay(),
                // The same rule the instalments follow, and for the same reason:
                // a broken period settled before the loan was ever entered came
                // out of an account the app was not watching, whose balance the
                // user has since corrected by hand. This charge is often the
                // largest single thing a back-dated loan writes — on a seven-year
                // loan entered a year late it is रू 10,984.93 against a savings
                // account holding रू 10,000 — and it slipped through the fix that
                // covered the instalments, because it is written here rather than
                // generated from the rule. It follows the same exception too: a
                // disbursement recorded into that account makes the app its
                // bookkeeper from the day the money landed.
                accountId = entity.payFromAccountId
                    ?.takeIf { firstRecoveryOn >= entity.watchedFrom(it) },
                status = EntryStatus.CONFIRMED,
                loanId = entity.id,
                loanPart = LoanPart.INTEREST,
                note = entity.name,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    /**
     * Applies a lump-sum prepayment and re-bases the loan on what is left.
     *
     * A bank does not keep amortising the original principal after a
     * prepayment; it recomputes from the reduced balance. So the loan is
     * rewritten as a fresh one for the remaining amount, and the instalment
     * count starts again — otherwise the schedule and the payments made would
     * describe two different loans.
     *
     * The prepayment itself is recorded as a real payment out, because it is.
     *
     * @param accountId the account the money actually left, defaulting to the
     *   one the instalment is taken from.
     * @param paidOn the day the money actually moved, which is not always
     *   today: a debt between people is often written down after the fact, and
     *   a ledger of payments is only worth reading if the dates are the real
     *   ones. It dates the whole re-base, not just the entry — see below.
     * @param note what this one was about, in the user's words. Blank or null is
     *   nothing said, and the row then carries the debt's own name as every
     *   movement against it always has — which is what the lists read as "the
     *   app wrote this, not the user". See `MoneyEntry.ownNote`.
     */
    suspend fun applyPrepayment(
        loanId: String,
        amount: Money,
        keepInstalment: Boolean,
        accountId: String? = null,
        paidOn: LocalDate? = null,
        note: String? = null,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired
        val entity = loanDao.findById(loanId) ?: return SaveResult.AccountRequired

        // An overdraft has no schedule to rewrite: paying it down simply lowers
        // what has been drawn, and the headroom grows back by the same amount.
        if (entity.kind == LoanKind.OVERDRAFT) {
            return repayOverdraft(entity, amount, accountId, paidOn, note)
        }

        val now = clock.nowMillis()
        val today = clock.today()
        // The day the loan changes shape is the day the money moved, not the day
        // it was typed in. A lump sum paid on the 30th cleared the balance on the
        // 30th, and the instalments since have come off the reduced figure;
        // re-basing at today would apply the payment to a balance those
        // instalments had already brought down, and count them twice.
        val rebasedOn = paidOn ?: today

        // **A payment the user has dated forward is written down and not acted
        // on.** It has not happened: there is no balance yet on a day that has
        // not arrived, and a debt reduced by money still in the account would be
        // wrong on every screen until the day came round. So the row is
        // recorded — the timeline draws it in the month it falls in, exactly as
        // it draws a salary banked for the 3rd — and [applyDuePayments] re-bases
        // the debt the first time the app opens on or after that day. This used
        // to be clamped to today instead, which silently overwrote the answer the
        // user had just given.
        if (rebasedOn > today) {
            recordPayment(
                amount = amount,
                currencyCode = entity.currencyCode,
                accountId = accountId,
                date = rebasedOn,
                note = entity.noteFor(note),
                now = now,
                loanId = loanId,
                part = LoanPart.PRINCIPAL,
                loanDirection = entity.loanDirection,
            )
            return SaveResult.Success(loanId)
        }
        return rebase(
            entity, amount, rebasedOn, keepInstalment, accountId, record = true, note = note,
        )
    }

    /**
     * What to write in a movement's note: what the user said about this one, or
     * the debt's own name where they said nothing.
     *
     * The fallback is not decoration — it is what every movement against a debt
     * has always carried, and the lists tell the app's own writing from the
     * user's by comparing the two (`MoneyEntry.ownNote`). Blank is nothing said:
     * a box the user tabbed through must not become a note reading "".
     */
    private fun LoanEntity.noteFor(note: String?): String =
        note?.trim()?.takeIf { it.isNotEmpty() } ?: name

    /**
     * Re-bases a debt on a lump sum, and records the money leaving unless it has
     * already been written down.
     *
     * Split out of [applyPrepayment] so that [applyDuePayments] can fold in a
     * payment the user dated forward without recording it a second time — the row
     * was written the day they promised it. One copy of the arithmetic, so a
     * payment folded in on its own day pays exactly what it would have paid on
     * the day it was recorded.
     */
    private suspend fun rebase(
        entity: LoanEntity,
        amount: Money,
        rebasedOn: LocalDate,
        keepInstalment: Boolean = true,
        accountId: String? = null,
        record: Boolean = false,
        note: String? = null,
    ): SaveResult {
        val now = clock.nowMillis()
        val loanId = entity.id
        // The balance the payment met, as billed at the last instalment. It comes
        // off in full: a borrower who pays रू 4,30,000 expects to owe रू 4,30,000
        // less. The days that had run since that instalment were charged on the
        // larger balance and are collected with the next one — carried, not
        // capitalised.
        val owedThen = outstandingOf(entity, asOf = rebasedOn)
        val carried = accruedBefore(entity, rebasedOn)
        val newBalance = Money((owedThen.minor - amount.minor).coerceAtLeast(0L))
        val rate = entity.annualRate ?: 0.0
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        // Periods rather than payments — see [arrears]. A month whose instalment
        // was deleted has still gone by, and the term left runs from where the
        // schedule stands, not from how many times money changed hands.
        val paid = entity.arrears(rebasedOn).periodsDue
        // Periods elapsed, expressed back in months, is what the term is counted
        // in — subtracting a payment count from a month count would shorten a
        // quarterly loan by three times what has actually been paid off it.
        val remaining = entity.termMonths?.let { (it - paid * gap).coerceAtLeast(gap) }
        val currentEmi = entity.emiMinor?.let { Money(it) }

        // Record the money actually leaving, before the loan is rewritten.
        // Tagged as principal: a lump sum buys nothing but balance. It is a
        // record only — the reduction itself lives in the loan's new principal
        // figure below, so nothing subtracts this entry a second time. Skipped
        // when the row is already there, which is a payment being folded in on
        // the day it was promised for.
        if (record) {
            recordPayment(
                amount = amount,
                currencyCode = entity.currencyCode,
                // Exactly the account the caller named, with no fall back to the
                // loan's own. The form asks per payment now, and a lump sum handed
                // over in cash the app does not track must not quietly debit the
                // account the instalments come from. Null writes the row without an
                // account: the payment happened, and the ledger has to show it.
                accountId = accountId,
                date = rebasedOn,
                note = entity.noteFor(note),
                now = now,
                loanId = loanId,
                part = LoanPart.PRINCIPAL,
                loanDirection = entity.loanDirection,
            )
        }

        if (newBalance.isZero) {
            loanDao.upsert(entity.copy(principalMinor = 0L, isClosed = true, updatedAt = now))
            entity.seriesId?.let { recurrence.setPaused(it, true) }
            return SaveResult.Success(loanId)
        }

        // The instalment keeps the day it has always fallen on. A loan paid on
        // the 10th goes on being paid on the 10th: that is the day the bank
        // debits, the day the borrower has arranged their month around, and the
        // day every payment already recorded is dated. This used to restart the
        // schedule one interval after *today*, so paying a lump sum on the 30th
        // silently moved the EMI to the 30th of every month afterwards, and the
        // projection stopped agreeing with the standing order.
        val emiStart = nextInstalmentAfter(entity, rebasedOn)
            ?: Recurrence.addMonths(rebasedOn, gap.toLong(), entity.stepsInBs())
        // The re-based loan's own days: from the day the money moved to the next
        // instalment, which is a part-period rather than a whole one, plus the
        // days already run on the balance it replaced.
        val rebased = accrualFor(
            rebasedOn, emiStart, gap, carried, inBikramSambat = entity.stepsInBs(),
        )

        val newTerm: Int?
        val newEmi: Money?
        when (entity.instalmentStyle) {
            // The principal slice stays what the user pays; a smaller balance
            // simply takes fewer months to get through.
            InstalmentStyle.PRINCIPAL_ONLY -> {
                newEmi = currentEmi
                newTerm = currentEmi?.let {
                    LoanMath.termForMonthlyPrincipal(newBalance, it, gap)
                }
            }
            // Nothing about the end date changes — the principal is still due
            // then — but a smaller balance costs less interest each time.
            InstalmentStyle.INTEREST_ONLY -> {
                newTerm = remaining
                newEmi = LoanMath.periodInterest(newBalance, rate, gap).takeIf { it.isPositive }
            }
            InstalmentStyle.LEVEL_EMI -> if (keepInstalment && currentEmi != null) {
                newTerm = LoanMath.tenureAfterPrepayment(
                    newBalance, rate, currentEmi, gap, accrual = rebased,
                )
                newEmi = currentEmi
            } else {
                newTerm = remaining
                newEmi = remaining?.let { LoanMath.emiAfterPrepayment(newBalance, rate, it, gap) }
            }
        }

        val seriesId = if (newEmi != null && newTerm != null) {
            recurrence.saveSeries(
                id = entity.seriesId,
                amount = newEmi,
                currencyCode = entity.currencyCode,
                direction = if (entity.loanDirection == LoanDirection.LENT) {
                    Direction.IN
                } else {
                    Direction.OUT
                },
                isAdjustment = entity.loanDirection == LoanDirection.LENT,
                interval = RecurrenceInterval.MONTHLY,
                intervalMonths = gap,
                startOn = emiStart,
                usesSelectedCalendar = entity.recurInBs,
                endOn = Recurrence.addMonths(
                    emiStart,
                    ((LoanMath.payments(newTerm, gap) - 1) * gap).toLong(),
                    entity.stepsInBs(),
                ),
                accountId = entity.payFromAccountId,
                note = entity.name,
            )
        } else {
            entity.seriesId
        }

        val rebasedLoan = entity.copy(
            principalMinor = newBalance.minor,
            termMonths = newTerm,
            emiMinor = newEmi?.minor,
            emiStartsOn = emiStart.toEpochDay(),
            carriedInterestMinor = carried.minor,
            seriesId = seriesId,
            // Re-based on the day the money moved. The new balance was worked
            // out from what was owed *then*, so the instalments before that
            // day are already inside it and must not be counted again — and
            // the ones since are payments against the reduced figure, which
            // is exactly what this cutoff lets them be.
            startedOn = rebasedOn.toEpochDay(),
            updatedAt = now,
        )
        loanDao.upsert(rebasedLoan)
        // A debt owed in one go asks for whatever is left of it. Its rule is one
        // occurrence carrying the whole balance, and a payment that moved the
        // balance without moving the rule left the old, larger lump sitting in
        // the forecast on the day it falls due.
        rewriteOneGoSeries(rebasedLoan)
        return SaveResult.Success(loanId)
    }

    /**
     * Takes back whatever [entry] did to its loan's stored balance.
     *
     * Called on the way into a delete, wherever the delete happens — the
     * timeline, an account's statement, the loan's own. The entry itself is
     * tombstoned by [WalletRepository.deleteEntry]; what has to happen here is
     * everything that is *not* derived from the surviving rows:
     *
     *  - a **lump sum** was subtracted from `principal_minor`, so it goes back
     *    on, and the term is worked out again from the restored balance;
     *  - **more borrowed, more lent or a drawdown** was added to it, so it comes
     *    off;
     *  - the **debt arriving** moved no balance at all — it is the money landing
     *    in an account — so only the account it named is forgotten;
     *  - **interest serviced on its own** never moved the balance either, which
     *    is the whole point of servicing it;
     *  - an **instalment** needs nothing: what is owed on a schedule is worked
     *    out from how many payments are on file, and one fewer row is one fewer
     *    payment.
     *
     * The day the loan was re-based on, the interest it carried and the day its
     * next instalment falls are all deliberately left where they are. They
     * describe the balance being restored — which is what the loan owed on that
     * day — so the instalments since go on counting against it exactly as they
     * did, and a period charges the same days it always did.
     */
    suspend fun revertMovement(entry: MoneyEntryEntity): Reversal =
        moveWithMovement(entry, undo = true)

    /**
     * Puts it back, for the Undo behind a swipe.
     *
     * The exact inverse of [revertMovement] and written as one function with it:
     * a delete the user takes back seconds later must leave the debt where it
     * started, and two hand-written mirror images of this arithmetic would be
     * two chances for it not to.
     */
    suspend fun reapplyMovement(entry: MoneyEntryEntity) {
        moveWithMovement(entry, undo = false)
    }

    private suspend fun moveWithMovement(entry: MoneyEntryEntity, undo: Boolean): Reversal {
        val loanId = entry.loanId ?: return Reversal.Done
        val entity = loanDao.findById(loanId) ?: return Reversal.Done

        // What this row was, decided by the same function the statement uses —
        // one rule, so a row cannot be called a lump sum on the screen and the
        // debt arriving by the delete.
        val kind = LoanLedger.kindOf(
            // Known from the row's own id, since the debt arriving is otherwise
            // indistinguishable from more of it being borrowed.
            isOpening = entry.id == "$loanId$DISBURSEMENT_SUFFIX",
            part = entry.loanPart,
            fromSeries = entry.seriesId != null && entry.seriesId == entity.seriesId,
            isAdjustment = entry.isAdjustment,
            // Left out once, and it mattered: a purchase on a card has no rule,
            // no account and no part, so without this it was read as an
            // instalment — the one kind whose reversal is to do nothing. Deleting
            // रू 2,000 of groceries took the spending off the month and left the
            // card owing the रू 2,000 for ever.
            isSpend = entry.isCardSpend,
        )

        when (kind.reversal()) {
            MovementReversal.NONE -> return Reversal.Done
            // The account it named, and nothing else. The debt stands: the field
            // records where the money went, and a debt whose disbursement was
            // never recorded is the normal state of one entered late.
            MovementReversal.FORGET_ACCOUNT -> {
                loanDao.upsert(
                    entity.copy(
                        disbursedAccountId = if (undo) null else entry.accountId,
                        updatedAt = clock.nowMillis(),
                    )
                )
                return Reversal.Done
            }
            MovementReversal.ADD_BACK, MovementReversal.TAKE_OFF -> Unit
        }

        // A movement recorded in some other currency cannot be added back to a
        // balance held in this one without a rate for the day it happened, and a
        // debt built on a guessed rate is worse than one that does not move.
        // Nothing in the app writes such a row; this is the backstop.
        if (!entry.currencyCode.equals(entity.currencyCode, ignoreCase = true)) {
            return Reversal.Done
        }

        val paid = kind.reversal() == MovementReversal.ADD_BACK
        // A payment the user dated forward and then thought better of. It is a
        // row and nothing more until its day comes — see [applyDuePayments] —
        // so there is nothing on the debt to take back, and putting the money on
        // would leave them owing more than they did before they typed it.
        // The same mark that pass reads: a lump sum still ahead of `started_on`
        // has not been folded in.
        if (paid && entry.occurredOn > entity.startedOn) return Reversal.Done
        if (paid && undo && amortises(entity)) {
            val later = loanDao.principalPaymentsAfter(
                loanId = loanId,
                day = entry.occurredOn,
                createdAt = entry.createdAt,
                exceptId = entry.id,
            )
            if (later > 0) return Reversal.LaterPaymentFirst
        }
        // Undoing a payment puts the money back on the debt and undoing an
        // addition takes it off; putting either one back does the opposite.
        val restores = if (undo) paid else !paid
        shiftBalance(
            entity = entity,
            byMinor = if (restores) entry.amountMinor else -entry.amountMinor,
            // Taking back a top-up un-advances that money; taking back a payment
            // does not, having never advanced any. Without the distinction, a
            // deleted repayment would grow the sum the borrower is told they took.
            advances = !paid,
        )
        return Reversal.Done
    }

    /**
     * Corrects what was handed over on the day the debt was taken.
     *
     * The one row whose figure is the *loan's* rather than the entry's: the
     * disbursement is the debt arriving, so what it says and what the loan says
     * it owes are two faces of one fact. Reversing it deliberately does nothing
     * to the balance — see [moveWithMovement], where an opening only forgets the
     * account it landed in, because a debt entered late has no disbursement row
     * at all and must still stand. So when the row itself is corrected, this is
     * what carries the difference across.
     *
     * Through [shiftBalance] like every other movement, so the schedule, the sum
     * ever advanced and the day the debt clears all follow the way they do
     * anywhere else.
     */
    suspend fun restateOpening(loanId: String, byMinor: Long) {
        if (byMinor == 0L) return
        val entity = loanDao.findById(loanId) ?: return
        shiftBalance(entity = entity, byMinor = byMinor, advances = true)
    }

    /** True when this debt's balance follows an amortisation schedule. */
    private fun amortises(entity: LoanEntity): Boolean =
        entity.seriesId != null && (entity.termMonths ?: 0) > 0

    /**
     * Moves a loan's stored balance by [byMinor] and puts its schedule back in
     * step with it.
     *
     * Three things follow from the balance and have to move with it:
     *
     *  - **The term**, on a debt with a schedule. The instalment does not move —
     *    that is this app's rule everywhere, and the figure the borrower knows
     *    by heart — so what gives is how many payments are left. Recomputed from
     *    the restored balance with [LoanMath.tenureAfterPrepayment], which is the
     *    same call that shortened it in the first place.
     *  - **Where the rule stops**, or the schedule would go on paying past the
     *    day the debt clears, or stop before it.
     *  - **What a debt due in one go asks for.** Its rule carries the whole
     *    balance as a single occurrence, so a balance that moved and a rule that
     *    did not would put the old lump in next month's forecast.
     *
     * A balance reaching zero closes the loan and pauses its rule, and a balance
     * leaving zero reopens both.
     */
    private suspend fun shiftBalance(
        entity: LoanEntity,
        byMinor: Long,
        /**
         * Whether this movement is money being *advanced* rather than repaid, so
         * that the sum ever taken moves with the balance — see
         * [LoanEntity.advancedMinor].
         */
        advances: Boolean = false,
    ) {
        val now = clock.nowMillis()
        val restored = Money((entity.principalMinor + byMinor).coerceAtLeast(0L))
        // An overdraft with nothing drawn is not a debt that has been cleared —
        // it is an open facility at rest, and the bank has not withdrawn it. It
        // is the one kind whose balance passes through zero as a matter of
        // course, and closing it there would take the facility off the accounts
        // page for having been paid off.
        val cleared = restored.isZero && entity.kind != LoanKind.OVERDRAFT
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        val emi = entity.emiMinor?.let { Money(it) }

        val term = if (amortises(entity) && emi != null && !cleared) {
            LoanMath.tenureAfterPrepayment(
                outstanding = restored,
                annualRatePercent = entity.annualRate ?: 0.0,
                emi = emi,
                monthsPerPayment = gap,
                accrual = entity.accrual(),
                // Left where it was when the instalment cannot clear the
                // restored balance at all. A null term would take the debt off
                // its own schedule, which is a larger claim than this is making.
            ) ?: entity.termMonths
        } else {
            entity.termMonths
        }

        val moved = entity.copy(
            principalMinor = restored.minor,
            termMonths = term,
            advancedMinor = if (advances) {
                entity.advancedMinor?.plus(byMinor)?.coerceAtLeast(0L)
            } else {
                entity.advancedMinor
            },
            // Reopened when money goes back on a debt that had been cleared: a
            // closed loan whose last payment is being taken back is a loan that
            // is owed again.
            isClosed = if (entity.kind == LoanKind.OVERDRAFT) entity.isClosed else cleared,
            updatedAt = now,
        )
        loanDao.upsert(moved)

        entity.seriesId?.let { seriesId ->
            recurrence.setPaused(seriesId, cleared)
            if (cleared) return@let
            if (term != null && emi != null) {
                recurrence.findSeries(seriesId)?.let { series ->
                    recurrence.setEndOn(
                        seriesId,
                        Recurrence.addMonths(
                            LocalDate.ofEpochDay(series.startOn),
                            ((LoanMath.payments(term, gap) - 1) * gap).toLong(),
                            entity.stepsInBs(),
                        ),
                    )
                }
            }
        }
        rewriteOneGoSeries(moved)
    }

    /**
     * Restates what a debt due in one go asks for on the day it is owed.
     *
     * Its rule is a single occurrence carrying the whole balance, so anything
     * that moves the balance has to move it too — otherwise the forecast goes on
     * showing the sum that was owed before the payment. Does nothing on any debt
     * with instalments, where each occurrence is one payment and the balance is
     * none of its business.
     */
    private suspend fun rewriteOneGoSeries(entity: LoanEntity) {
        val seriesId = entity.seriesId ?: return
        if (entity.emiMinor != null) return
        val due = entity.dueOn?.let { LocalDate.ofEpochDay(it) } ?: return
        val lent = entity.loanDirection == LoanDirection.LENT
        recurrence.saveSeries(
            id = seriesId,
            amount = outstandingOf(entity),
            currencyCode = entity.currencyCode,
            direction = if (lent) Direction.IN else Direction.OUT,
            isAdjustment = lent,
            interval = RecurrenceInterval.MONTHLY,
            startOn = due,
            // Ends the day it starts, which is how a rule says "just once".
            endOn = due,
            accountId = entity.payFromAccountId,
            note = entity.name,
        )
    }

    /**
     * The bank moved the rate on a floating loan, from [effectiveFrom].
     *
     * The instalment does not move. That is the whole shape of a floating loan
     * in Nepal: the standing order goes on paying the same figure, and the
     * change lands inside it — more of each payment is interest and less clears
     * the balance, or the reverse. What gives instead is the **term**, so it is
     * recomputed here and the repeating rule's end date moves with it. Leaving
     * the term alone would describe a schedule that no longer clears the debt.
     *
     * Nothing is written to the timeline, because nothing moved: no money
     * changes hands on the day a rate changes. What changed is visible where it
     * happened — in the split of every instalment from that day on, which the
     * loan's own statement prints payment by payment.
     */
    suspend fun applyRateChange(
        loanId: String,
        annualRate: Double,
        effectiveFrom: LocalDate,
    ): SaveResult {
        val entity = loanDao.findById(loanId) ?: return SaveResult.AccountRequired
        // Zero is a real answer and the only way to stop interest: the two of
        // them agreed to drop it, or it was recorded by mistake. It used to be
        // refused along with a negative rate, which left no way to take back a
        // rate once given — the figure could be retyped but never removed.
        //
        // Dated on the day the rate it replaces started, [InterestRepository]
        // overwrites that row rather than adding a second one, so the interest
        // is not merely stopped but never charged at all.
        if (annualRate < 0.0) return SaveResult.AmountRequired
        interest.recordChange(loanId = loanId, annualRate = annualRate, effectiveFrom = effectiveFrom)

        val emi = entity.emiMinor?.let { Money(it) } ?: return SaveResult.Success(loanId)
        val gap = entity.paymentEveryMonths.coerceAtLeast(1)
        val paid = entity.arrears().periodsDue
        val fresh = loanDao.findById(loanId) ?: return SaveResult.Success(loanId)
        val accrual = fresh.accrual() ?: return SaveResult.Success(loanId)
        // How many payments are left, from where the schedule stands now: the
        // balance after those already made, walked forward at the new rates.
        val remaining = LoanMath.tenureAfterPrepayment(
            outstanding = outstandingOf(fresh),
            annualRatePercent = fresh.annualRate ?: 0.0,
            emi = emi,
            monthsPerPayment = gap,
            accrual = accrual.copy(
                from = Recurrence.addMonths(
                    accrual.firstPaymentOn, (paid.toLong() - 1) * gap, accrual.inBikramSambat,
                ),
                firstPaymentOn = Recurrence.addMonths(
                    accrual.firstPaymentOn, paid.toLong() * gap, accrual.inBikramSambat,
                ),
                carriedInterest = Money.ZERO,
            ),
        ) ?: return SaveResult.AmountRequired

        val now = clock.nowMillis()
        val term = paid * gap + remaining
        loanDao.upsert(fresh.copy(termMonths = term, updatedAt = now))
        // The rule has to stop where the schedule now stops.
        entity.seriesId?.let { seriesId ->
            recurrence.findSeries(seriesId)?.let { series ->
                val start = LocalDate.ofEpochDay(series.startOn)
                recurrence.setEndOn(
                    seriesId,
                    Recurrence.addMonths(
                        start,
                        ((LoanMath.payments(term, gap) - 1) * gap).toLong(),
                        fresh.stepsInBs(),
                    ),
                )
            }
        }
        return SaveResult.Success(loanId)
    }

    /**
     * Money taken out of an overdraft.
     *
     * Two things happen and they are deliberately separate: the debt grows by
     * what was drawn, and the same amount lands in a real account. The entry is
     * an **adjustment** — borrowing is not earning, and letting a drawdown into
     * the income breakdown would make a month of living on the overdraft look
     * like a good one.
     *
     * Refused when it would breach the approved limit. The bank would refuse it
     * too, and a tracker that quietly allowed it would report headroom the user
     * does not have.
     *
     * @param intoAccountId where the money actually landed.
     */
    /**
     * Money spent straight from a card, within its limit.
     *
     * The one movement that is spending *and* a balance going up: nothing lands
     * anywhere, the money is gone, and the facility owes it. रू 2,000 of
     * groceries on a रू 50,000 card leaves रू 48,000 to draw against and starts
     * metering interest on the रू 2,000 from today — the same day-by-day meter a
     * drawdown starts, since it is the same drawn balance.
     *
     * It names **no account** by construction, which is also how it is told
     * apart from everything else against the debt — see
     * `MoneyEntryEntity.isCardSpend`. Refused above the limit rather than
     * clamped: a purchase the card would have declined is not a purchase.
     *
     * **[id] corrects one already recorded** rather than writing a second. A
     * purchase is now opened from the card's own statement, and a form that
     * saved by inserting would have answered "I typed 2,000 and meant 1,200"
     * with two purchases and a facility owing 3,200. What the balance moves by
     * is the difference between the two figures, and the limit is checked
     * against that difference for the same reason: correcting रू 2,000 down to
     * रू 1,200 on a full card must not be refused for exceeding it.
     */
    suspend fun spendOnCard(
        loanId: String,
        amount: Money,
        date: LocalDate,
        note: String?,
        /** The purchase being corrected, or null to record a new one. */
        id: String? = null,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired
        val entity = loanDao.findById(loanId) ?: return SaveResult.AccountRequired
        if (entity.kind != LoanKind.OVERDRAFT) return SaveResult.AccountRequired
        val limit = entity.creditLimitMinor ?: return SaveResult.AmountRequired
        // What this purchase is already costing the facility, so only the change
        // is weighed against the limit and only the change moves the balance.
        // Zero for a new one, and zero for one being moved here from another
        // card — that one is taken off its old facility below.
        val existing = id?.let { entryDao.findById(it) }?.takeIf { it.isCardSpend }
        val already = existing?.takeIf { it.loanId == loanId }?.amountMinor ?: 0L
        if (entity.principalMinor - already + amount.minor > limit) return SaveResult.OverLimit

        val now = clock.nowMillis()
        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = entity.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(entity.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        entryDao.upsert(
            MoneyEntryEntity(
                // The purchase being corrected keeps its own id, and with it its
                // place in every list that already shows it. A new id would
                // leave the old row behind and the facility owing both.
                id = existing?.id ?: UUID.randomUUID().toString(),
                amountMinor = amount.minor,
                currencyCode = entity.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                // Spending, and marked as such: the month really was that
                // expensive. Everything else that puts a debt up is an
                // adjustment, because money arrived somewhere to balance it.
                direction = Direction.OUT,
                occurredOn = date.toEpochDay(),
                accountId = null,
                isAdjustment = false,
                status = EntryStatus.CONFIRMED,
                loanId = loanId,
                note = entity.noteFor(note),
                // The day it was written down is a fact about the row and does
                // not change because a figure on it was corrected. It is also
                // what orders two movements made on one date — see
                // [LoanEntryFact.createdAt] — so rewriting it would move a
                // corrected purchase to the end of its own day.
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        // Moved here from another card: that one stops carrying it. Done through
        // [shiftBalance] rather than by writing the figure, so the facility it
        // leaves is put back in step the same way every other reversal does it.
        existing?.takeIf { it.loanId != null && it.loanId != loanId }?.let { moved ->
            loanDao.findById(moved.loanId!!)?.let { from ->
                shiftBalance(entity = from, byMinor = -moved.amountMinor, advances = true)
            }
        }
        loanDao.upsert(
            entity.copy(
                principalMinor = entity.principalMinor - already + amount.minor,
                updatedAt = now,
            )
        )
        return SaveResult.Success(loanId)
    }

    suspend fun drawFromOverdraft(
        loanId: String,
        amount: Money,
        intoAccountId: String,
        date: LocalDate,
        note: String?,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired
        val entity = loanDao.findById(loanId) ?: return SaveResult.AccountRequired
        if (entity.kind != LoanKind.OVERDRAFT) return SaveResult.AccountRequired

        val drawn = Money(entity.principalMinor)
        val limit = entity.creditLimitMinor?.let { Money(it) } ?: return SaveResult.AmountRequired
        if (drawn.minor + amount.minor > limit.minor) return SaveResult.AmountRequired

        val now = clock.nowMillis()
        val trimmedNote = entity.noteFor(note)
        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = entity.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(entity.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        val entryId = UUID.randomUUID().toString()
        entryDao.upsert(
            MoneyEntryEntity(
                id = entryId,
                amountMinor = amount.minor,
                currencyCode = entity.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                direction = Direction.IN,
                occurredOn = date.toEpochDay(),
                accountId = intoAccountId,
                isAdjustment = true,
                status = EntryStatus.CONFIRMED,
                loanId = loanId,
                note = trimmedNote,
                createdAt = now,
                updatedAt = now,
            )
        )
        loanDao.upsert(
            entity.copy(principalMinor = drawn.minor + amount.minor, updatedAt = now)
        )
        interest.repostIfBefore(date)
        return SaveResult.Success(entryId)
    }

    /**
     * More money on the same arrangement: another रू 2,000 lent to the person
     * who already owes you 8,000, or more borrowed from them.
     *
     * Money between people grows in instalments as often as it shrinks, and
     * until now the only way to record it was a second loan under the same
     * name — two rows the user then had to add up themselves.
     *
     * The entry is an **adjustment**, in both directions and for the same
     * reason a drawdown is: borrowing is not earning, and lending is not
     * spending. Only the balance moves; the month's figures stay honest.
     *
     * Refused on anything with an amortisation schedule. Adding to the principal
     * of a term loan while leaving its instalment and term alone would leave a
     * schedule that no longer clears the debt — a bank top-up re-bases the whole
     * loan, the way [applyPrepayment] does in the other direction, and that is a
     * different operation from this one. An overdraft grows by being drawn on:
     * see [drawFromOverdraft]. The form offers this on neither.
     *
     * What says a debt has such a schedule is its **instalment**, not its
     * length. This asked for a term of zero, and money between people is exactly
     * the debt that has a length and no schedule: agreeing to pay Sita back
     * within a year sets `term_months` and leaves `emi_minor` null by design, so
     * lending her another रू 2,000 was silently refused — the card took the
     * figure, said nothing, and left the balance where it was. A `null` instalment
     * is what makes a debt one that settles in one go, which is a balance a
     * top-up simply moves.
     *
     * @param accountId where the money went or came from, defaulting to the
     *   account the arrangement was made through. Optional: the debt is the fact
     *   being recorded, and a user who has not set up the account it passed
     *   through should still be able to record it.
     */
    suspend fun increaseLoan(
        loanId: String,
        amount: Money,
        accountId: String?,
        date: LocalDate,
        note: String?,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired
        val entity = loanDao.findById(loanId) ?: return SaveResult.AccountRequired
        if (entity.kind == LoanKind.OVERDRAFT) return SaveResult.AmountRequired
        if (entity.emiMinor != null) return SaveResult.AmountRequired

        val now = clock.nowMillis()
        val lent = entity.loanDirection == LoanDirection.LENT
        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = entity.currencyCode,
            fromMinorUnits = CurrencyOption.byCode(entity.currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        val entryId = UUID.randomUUID().toString()
        entryDao.upsert(
            MoneyEntryEntity(
                id = entryId,
                amountMinor = amount.minor,
                currencyCode = entity.currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                // Lending it out is money leaving; borrowing it is money
                // arriving. The account has to feel it either way.
                direction = if (lent) Direction.OUT else Direction.IN,
                occurredOn = date.toEpochDay(),
                // Exactly what the caller named. The form defaults it to the
                // account this arrangement already moves through — another
                // रू 2,000 lent leaves the account the first रू 8,000 left — and
                // then lets the user say otherwise for this one movement.
                accountId = accountId,
                isAdjustment = true,
                status = EntryStatus.CONFIRMED,
                loanId = loanId,
                // Deliberately no loan_part. That column marks a payment as all
                // principal or all interest, and this is not a payment at all —
                // tagging it PRINCIPAL would count money going out as money paid
                // off the very debt it created.
                note = entity.noteFor(note),
                createdAt = now,
                updatedAt = now,
            )
        )
        val grown = entity.copy(
            principalMinor = entity.principalMinor + amount.minor,
            // More borrowed on the same arrangement is more advanced on it, so
            // this is the one thing that moves the sum taken. A loan that has no
            // answer on file gains none here: the figure would be the top-up
            // alone, which is a smaller lie than none but a lie all the same.
            advancedMinor = entity.advancedMinor?.plus(amount.minor),
            updatedAt = now,
        )
        loanDao.upsert(grown)

        // A loan that settles in one go carries the whole balance as its single
        // occurrence. Left at the old figure, the forecast would go on showing
        // the smaller lump falling due on the day it is owed.
        rewriteOneGoSeries(grown)
        interest.repostIfBefore(date)
        return SaveResult.Success(entryId)
    }

    /**
     * Paying an overdraft down.
     *
     * The money leaving is spending — repaying a debt always is — while the
     * balance simply falls. Nothing is re-based and no schedule is recomputed,
     * because an overdraft never had one.
     */
    private suspend fun repayOverdraft(
        entity: LoanEntity,
        amount: Money,
        accountId: String?,
        paidOn: LocalDate? = null,
        note: String? = null,
    ): SaveResult {
        val now = clock.nowMillis()
        val today = clock.today()
        val on = paidOn ?: today
        val paid = Money(amount.minor.coerceAtMost(entity.principalMinor))
        recordPayment(
            amount = paid,
            currencyCode = entity.currencyCode,
            accountId = accountId,
            date = on,
            note = entity.noteFor(note),
            now = now,
            loanId = entity.id,
            part = LoanPart.PRINCIPAL,
            loanDirection = entity.loanDirection,
            // **Settling a card is not spending.** Every rupee of it was already
            // counted on the day it was spent — see [LoanMovementKind.SPEND] —
            // and counting it again would say a रू 2,000 bag of groceries cost
            // रू 4,000. What it is instead is money moving from the bank to the
            // facility, which is exactly what an adjustment means everywhere
            // else. A term loan is the opposite and unaffected: nothing was
            // counted when the money was borrowed, so the repayment is where the
            // spending is recognised.
            asAdjustment = true,
        )
        // Dated forward, it is a row and nothing else until the day comes —
        // see the note in [applyPrepayment].
        if (on > today) return SaveResult.Success(entity.id)
        loanDao.upsert(
            entity.copy(
                principalMinor = entity.principalMinor - paid.minor,
                // The day the last repayment was folded in, which is how a
                // pending one is told from a settled one — see
                // [applyDuePayments]. Never backwards: a facility's balance is a
                // plain running total and its repayments are order-independent,
                // so moving the mark back would offer one of them up to be
                // subtracted twice.
                startedOn = maxOf(entity.startedOn, on.toEpochDay()),
                updatedAt = now,
            )
        )
        return SaveResult.Success(entity.id)
    }

    /**
     * A repayment: money leaving on a debt, or arriving on a loan the user gave.
     *
     * The two are not mirror images in the breakdowns, and deliberately so.
     * Repaying a bank is money out of the user's world for good, so it counts as
     * spending. But principal coming back from a friend was always the user's own
     * money — booking it as income would mean they had *earned* something by
     * getting their own back, and every income figure would drift upwards each
     * time a loan was repaid. Interest received is different: that really is
     * earnings, and it counts.
     */
    private suspend fun recordPayment(
        amount: Money,
        currencyCode: String,
        accountId: String?,
        date: LocalDate,
        note: String,
        now: Long,
        loanId: String? = null,
        part: LoanPart? = null,
        loanDirection: LoanDirection = LoanDirection.BORROWED,
        /** Forced on where the spending was already counted — see the caller. */
        asAdjustment: Boolean = false,
    ) {
        val lent = loanDirection == LoanDirection.LENT
        val baseCode = settings.settings.first().currencyCode
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = currencyCode,
            fromMinorUnits = CurrencyOption.byCode(currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        entryDao.upsert(
            MoneyEntryEntity(
                id = UUID.randomUUID().toString(),
                amountMinor = amount.minor,
                currencyCode = currencyCode.uppercase(),
                baseAmountMinor = converted.amount.minor,
                rateToBase = converted.rate,
                baseCurrencyCode = baseCode,
                direction = if (lent) Direction.IN else Direction.OUT,
                occurredOn = date.toEpochDay(),
                accountId = accountId,
                isAdjustment = asAdjustment || (lent && part != LoanPart.INTEREST),
                status = EntryStatus.CONFIRMED,
                loanId = loanId,
                loanPart = part,
                note = note,
                createdAt = now,
                updatedAt = now,
            )
        )
        // A lump sum handed over weeks ago left the account weeks ago, and the
        // period it fell in has been paid interest on the larger balance.
        interest.repostIfBefore(date)
    }

    /**
     * What a prepayment of [amount] would do, both ways, without applying it.
     *
     * Answered as of [paidOn], the same day [applyPrepayment] will re-base on,
     * so the balance the preview promises is the balance the user gets.
     */
    suspend fun previewPrepayment(
        loanId: String,
        amount: Money,
        paidOn: LocalDate? = null,
    ) = run {
        val entity = loanDao.findById(loanId)
        if (entity == null) {
            null
        } else {
            val rebasedOn = minOf(paidOn ?: clock.today(), clock.today())
            val owedThen = outstandingOf(entity, asOf = rebasedOn)
            val carried = accruedBefore(entity, rebasedOn)
            val gap = entity.paymentEveryMonths.coerceAtLeast(1)
            // The same question the save asks, answered the same way, or the
            // preview would promise a term the save does not produce.
            val paid = entity.arrears(rebasedOn).periodsDue
            val remaining = entity.termMonths?.let { (it - paid * gap).coerceAtLeast(gap) } ?: 0
            val emi = entity.emiMinor?.let { Money(it) } ?: Money.ZERO
            if (entity.instalmentStyle == InstalmentStyle.LEVEL_EMI) {
                LoanMath.comparePrepayment(
                    outstanding = owedThen,
                    annualRatePercent = entity.annualRate ?: 0.0,
                    currentEmi = emi,
                    remainingMonths = remaining,
                    prepayment = amount,
                    monthsPerPayment = gap,
                    // The same days the save will charge, so the balance the
                    // preview promises is the balance the user gets.
                    accrual = accrualFor(
                        rebasedOn, nextInstalmentAfter(entity, rebasedOn), gap, carried,
                        inBikramSambat = entity.stepsInBs(),
                    ),
                )
            } else {
                // "Finish sooner or pay less each month?" is a question about a
                // level instalment. On the other styles the answer is already
                // decided by how the loan works, so only the new balance is
                // worth showing.
                PrepaymentOutcome(
                    newBalance = Money((owedThen.minor - amount.minor).coerceAtLeast(0L)),
                    shorterTermMonths = null,
                    sameTermEmi = null,
                    interestSavedByShortening = null,
                    interestSavedByLowering = null,
                )
            }
        }
    }

    suspend fun setClosed(id: String, closed: Boolean) {
        loanDao.setClosed(id, closed, clock.nowMillis())
        // A cleared loan stops generating instalments.
        loanDao.findById(id)?.seriesId?.let { recurrence.setPaused(it, closed) }
    }

    /**
     * Deletes a loan and everything it put in the timeline.
     *
     * The instalments go with it, past and future. The future ones stop because
     * the rule stops; the past ones have to be taken deliberately, because the
     * app generated them itself from the loan's schedule the moment each date
     * arrived. A deleted loan that left ten EMIs behind left ten payments to a
     * debt that could no longer be opened, still holding an account balance
     * down — and no way to be rid of them but to swipe each one out of the
     * timeline.
     *
     * Closing a loan is the other answer, and unaffected: a debt that was really
     * paid off keeps every payment that cleared it. See [setClosed].
     */
    suspend fun deleteLoan(id: String) {
        val loan = loanDao.findById(id)
        val now = clock.nowMillis()
        loanDao.softDelete(id, now)
        loan?.seriesId?.let { recurrence.deleteSeries(it) }
        loanDao.softDeleteEntries(id, loan?.seriesId, now)
        // Everything it put in an account goes with it, back-dated rows
        // included, so whatever interest those earned has to go too.
        interest.postDueInterest()
    }

    /**
     * @param asOf the day [outstanding] was worked out for. Only the payment
     *   counts read it, and they have to: [Loan.outstandingAfter] recomputes the
     *   schedule from them, so a balance asked of June sitting beside a count of
     *   every payment made since would answer with today's figure the moment
     *   anything projected it forward.
     */
    private suspend fun LoanEntity.toDomain(outstanding: Money, asOf: LocalDate? = null): Loan {
        val until = asOf?.toEpochDay() ?: Long.MAX_VALUE
        // Converted whenever the two currencies differ, whatever the loan asks
        // to be *read* in.
        //
        // Those are two different questions and they used to be one. The flag
        // says which figure leads on the row — a dollar loan someone thinks of
        // in dollars leads in dollars — but every total is summed from the
        // converted figure, because that is the only figure that can be summed.
        // Tied together, a rupee debt under a dollar display had no converted
        // figure at all, so "to pay" fell back to its own and added रू 10,50,000
        // into a total wearing a dollar sign.
        val baseCode = settings.settings.first().currencyCode
        val converts = !currencyCode.equals(baseCode, ignoreCase = true)
        // Asked once: both the figure shown and the meter below need it.
        val interestServiced = Money(loanDao.interestPaidOutright(id))
        // And once for the rate history, which three answers below depend on.
        val rates = interest.scheduleFor(annualRate, loanId = id)
        // The dated steps this balance took, fetched once for the two answers
        // that walk them: what the debt started at, and what it has cost.
        val meters = metersInterest(rates)
        val steps = if (meters || keepsRunningTotal) balanceSteps() else emptyList()
        val accrued = if (meters) {
            meteredInterest(outstanding, interestServiced, rates, steps)
        } else {
            null
        }
        // Where the schedule stands and what it is owed — one answer, because
        // the count of periods behind us and the set of them that went unpaid
        // are only ever right together.
        val arrears = arrears(asOf)
        return Loan(
            id = id,
            name = name,
            kind = kind,
            direction = loanDirection,
            style = instalmentStyle,
            lender = lender,
            principal = Money(principalMinor),
            creditLimit = creditLimitMinor?.let { Money(it) },
            outstanding = outstanding,
            currencyCode = currencyCode,
            annualRate = annualRate,
            termMonths = termMonths,
            paymentEveryMonths = paymentEveryMonths,
            emi = emiMinor?.let { Money(it) },
            emiStartsOn = emiStartsOn?.let { LocalDate.ofEpochDay(it) },
            disbursedOn = disbursedOn?.let { LocalDate.ofEpochDay(it) },
            openedOn = openedOn?.let { LocalDate.ofEpochDay(it) },
            dueOn = dueOn?.let { LocalDate.ofEpochDay(it) },
            startedOn = LocalDate.ofEpochDay(startedOn),
            carriedInterest = Money(carriedInterestMinor),
            recurInBs = recurInBs,
            // Carried onto the domain object too, or everything that projects a
            // debt forward from here — the month's outlook, the ledger — would
            // walk the schedule at the rate the loan was taken at and quietly
            // disagree with the balance printed beside it.
            rates = rates.takeIf { !it.isFixed },
            payFromAccountId = payFromAccountId,
            disbursedAccountId = disbursedAccountId,
            seriesId = seriesId,
            paymentsMade = arrears.periodsDue,
            arrears = arrears,
            repaid = Money(seriesId?.let { loanDao.repaidSince(it, startedOn, until) } ?: 0L),
            isClosed = isClosed,
            color = colorArgb?.let { Color(it) },
            showInDisplayCurrency = showInDisplayCurrency,
            outstandingInBase = if (converts) toBase(outstanding, baseCode) else null,
            principalInBase = if (converts) toBase(Money(principalMinor), baseCode) else null,
            creditLimitInBase = if (converts) {
                creditLimitMinor?.let { toBase(Money(it), baseCode) }
            } else {
                null
            },
            emiInBase = if (converts) emiMinor?.let { toBase(Money(it), baseCode) } else null,
            // Cut at today, like every other figure the debt states about
            // itself: a lump sum the user has promised for the 25th has not been
            // paid, and counting it here read "paid off early: रू 5,80,000" beside
            // a balance that had only felt रू 5,30,000 of it.
            principalPaidOutright = Money(
                loanDao.principalPaidOutright(id, clock.today().toEpochDay())
            ),
            // What the user has promised for a day still to come. Not in
            // [outstanding] by design — see the field — and read by the timeline
            // for the month each one falls in.
            pendingPayments = loanDao
                .pendingPrincipalPayments(id, clock.today().toEpochDay())
                .map { BalanceChange(it.occurredOn, it.deltaMinor) },
            interestPaidOutright = interestServiced,
            // What was actually borrowed, which stops being [principal] the
            // moment a lump sum re-bases the debt in place.
            //
            // The dated movements answer it wherever the balance is a running
            // total, and that stays the preferred answer: it is derived from what
            // is on file, so a movement added or taken back afterwards is already
            // in it. A schedule cannot be walked back — its instalments cleared
            // principal and interest together — so there the stored figure is the
            // only one there is, and a debt from before the column has neither.
            borrowedInAll = LoanMath.totalAdvanced(outstanding, steps)
                .takeIf { keepsRunningTotal && it.isPositive }
                ?: advancedMinor?.let { Money(it) },
            // What it opened at, which is the same walk stopped one step earlier:
            // the balance before any dated movement, plus the debt's own arrival
            // where that was recorded. Everything borrowed *since* is deliberately
            // left out — see [Loan.openedAt].
            openedAt = if (keepsRunningTotal) {
                Money(
                    LoanMath.openingBalance(outstanding, steps).minor +
                        (loanDao.disbursedAmount("$id$DISBURSEMENT_SUFFIX") ?: 0L)
                ).takeIf { it.isPositive }
            } else {
                null
            },
            accruedInterest = accrued,
            // Converted at the same rate the balance was, so what it would take
            // to settle can be added into a total in the display currency.
            accruedInterestInBase = if (converts) accrued?.let { toBase(it, baseCode) } else null,
        )
    }

    /**
     * Whether this debt's balance is a running total rather than a schedule.
     *
     * The same distinction [outstandingOf] makes, and it decides whether
     * `principal_minor` still means "the sum borrowed": on a running total a
     * lump sum rewrites it in place, and only the dated movements know what it
     * began at. An overdraft is excluded because it opens at nothing by
     * definition — what it shows beside its name is the approved limit.
     */
    private val LoanEntity.keepsRunningTotal: Boolean
        get() = kind != LoanKind.OVERDRAFT && !(seriesId != null && (termMonths ?: 0) > 0)

    /**
     * Every dated step this debt's balance took, signed as a *debt* moves:
     * money arriving raises it, money leaving brings it down.
     *
     * Lending runs the other way — the query signs a borrowed balance — so the
     * flip happens here, once, for every caller that walks these.
     */
    private suspend fun LoanEntity.balanceSteps(): List<BalanceChange> =
        loanDao.balanceChanges(id, seriesId).map { row ->
            BalanceChange(
                row.occurredOn,
                if (loanDirection == LoanDirection.LENT) -row.deltaMinor else row.deltaMinor,
            )
        }

    /**
     * Whether this loan's interest has to be counted out day by day.
     *
     * A loan with a schedule already answers the question: its interest is
     * inside the instalments, and stating it again would double the cost. What
     * is left is every debt with a rate and nothing to pay it back on — an
     * overdraft, and the shape most money between people takes once a rate is
     * agreed but no end date ever is. Those had a rate the app quietly did
     * nothing with: it drew "12%" on the row and never said what the twelve per
     * cent had come to.
     *
     * Both halves of "no schedule" have to hold. A term alone builds one, and so
     * does an instalment on its own — [saveLoan] will write a repeating rule for
     * a loan with an instalment and no term, and its payments carry the interest.
     */
    private fun LoanEntity.metersInterest(rates: RateSchedule): Boolean =
        // Any rate ever on file, not just the one the debt opened at: money
        // between people is often written down first and given a rate later,
        // and asking the opening figure said such a debt was interest-free.
        !isClosed && rates.everCharged &&
            (kind == LoanKind.OVERDRAFT || (termMonths == null && emiMinor == null))

    /**
     * Interest built up on the balance as it actually stood, day by day, less
     * whatever has already been serviced.
     *
     * The history is walked back out of what is owed *now* rather than forward
     * from the loan's principal figure, because a lump sum rewrites that figure
     * in place: only the present balance and the dated steps behind it are
     * facts. The opening figure is dated from the earliest thing on file about
     * the loan — the day it was created, or an earlier day it was said to have
     * started — so a debt entered with a date in the past is metered from then
     * and not from the moment it was typed in.
     */
    private suspend fun LoanEntity.meteredInterest(
        outstanding: Money,
        interestServiced: Money,
        rates: RateSchedule,
        changes: List<BalanceChange>,
    ): Money {
        val opened = minOf(
            startedOn,
            // The day the money actually moved, which [startedOn] stops being
            // the moment a lump sum re-bases the debt: that moves it to the day
            // of the payment, and the meter then began there and quietly forgave
            // every day of interest before it — रू 7,095 on a year-old debt of
            // रू 1,00,000 paid down last month. A lump sum is all principal by
            // design; it settles no interest, and the schedule side carries
            // those days forward for exactly the same reason.
            disbursedOn ?: startedOn,
            changes.minOfOrNull { it.epochDay } ?: startedOn,
            createdAt / MILLIS_PER_DAY,
        )
        val gross = LoanMath.accruedInterest(
            changes = listOf(
                BalanceChange(opened, LoanMath.openingBalance(outstanding, changes).minor)
            ) + changes,
            annualRatePercent = annualRate ?: 0.0,
            asOfEpochDay = clock.today().toEpochDay(),
            // Through the history, so a rate agreed part-way through charges
            // only the days since it was agreed — and one agreed on a debt that
            // never had one charges at all.
            rates = rates,
        )
        // What is still to pay. Interest already serviced left the account and
        // never touched the balance, so it comes off the meter and nothing else.
        return Money((gross.minor - interestServiced.minor).coerceAtLeast(0L))
    }

    /**
     * [amount] in [baseCode], or null when there is no rate for it. Null rather
     * than the unconverted figure: a dollar amount printed with a rupee symbol
     * is worse than admitting the rate is missing.
     */
    private suspend fun LoanEntity.toBase(amount: Money, baseCode: String): Money? {
        val converted = exchangeRates.convert(
            amountMinor = amount.minor,
            from = currencyCode,
            fromMinorUnits = CurrencyOption.byCode(currencyCode).minorUnits,
            base = baseCode,
            baseMinorUnits = CurrencyOption.byCode(baseCode).minorUnits,
        )
        return if (converted.isExact) converted.amount else null
    }

    private companion object {
        /** Storage keeps timestamps in millis and dates in days; this converts. */
        const val MILLIS_PER_DAY = 86_400_000L

        /** Makes the broken-period entry's id from the loan's, so it is rewritable. */
        const val BROKEN_PERIOD_SUFFIX = "-broken-period"

        /**
         * The same trick for the day the money changed hands: one row, ever.
         * Shared with the domain, because the timeline has to recognise that row
         * as the debt arriving rather than as more of it being borrowed.
         */
        const val DISBURSEMENT_SUFFIX = LOAN_DISBURSEMENT_SUFFIX
    }
}
