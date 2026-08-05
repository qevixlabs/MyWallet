package com.mywallet.domain

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.InstalmentStyle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The two ways a prepayment can be applied, so they can be compared.
 *
 * Shortening the term almost always saves more interest, but lowering the
 * instalment frees up monthly cash. Neither is universally right, so the app
 * shows both rather than choosing for the user.
 */
data class PrepaymentOutcome(
    val newBalance: Money,
    /** Months left if the instalment stays the same. */
    val shorterTermMonths: Int?,
    /** Instalment if the end date stays the same. */
    val sameTermEmi: Money?,
    val interestSavedByShortening: Money?,
    val interestSavedByLowering: Money?,
)

/** A day a facility's balance moved: positive for a draw, negative for a repayment. */
data class BalanceChange(val epochDay: Long, val deltaMinor: Long)

/**
 * The dates a schedule needs before it can charge interest for the days that
 * actually pass.
 *
 * @param from the day interest starts running on this balance — when the money
 *   arrived, or the day a lump sum re-based the loan.
 * @param firstPaymentOn the first instalment. Every later one falls a payment
 *   interval after it.
 * @param carriedInterest interest that had already run on the balance this one
 *   replaced, and which the first instalment still has to collect.
 *
 *   A lump sum paid ten days after an instalment finds those ten days already
 *   charged on the *old*, larger balance. The payment itself comes off the
 *   principal in full — a borrower who pays रू 4,30,000 expects the balance to
 *   fall by रू 4,30,000 — so the days behind it are carried and collected with
 *   the next instalment, exactly as they appear on a statement. Folding them
 *   into the principal instead compounds them, which on a loan this size was
 *   रू 25 out.
 */
data class Accrual(
    val from: LocalDate,
    val firstPaymentOn: LocalDate,
    val carriedInterest: Money = Money.ZERO,
    /**
     * What the rate has been, day by day, when the bank has moved it.
     *
     * Null on a fixed-rate loan, where the single rate passed alongside says
     * everything. On a floating one the instalment stays exactly where it is —
     * that is what the borrower's standing order pays — and the change lands
     * entirely inside the split: more interest and less principal, or the
     * reverse, and a term that lengthens or shortens to match.
     */
    val rates: RateSchedule? = null,
    /**
     * Whether a "month" of this schedule is a Nepali month.
     *
     * The loan's own copy of its rule's answer (`loan.recur_in_bs`), carried
     * here because the periods this file measures interest over have to fall on
     * the very days the rule produces. Step them in different months and the
     * timeline says one date and the schedule table another, for the same
     * instalment.
     *
     * False is the default and what every loan written before this existed
     * keeps, so no schedule already on file moves.
     */
    val inBikramSambat: Boolean = false,
)

/** How one EMI payment divides between what you owe and what it costs. */
data class Instalment(
    val number: Int,
    val payment: Money,
    val interest: Money,
    val principal: Money,
    /** What is still owed after this payment. */
    val balance: Money,
)

/**
 * Loan arithmetic, on a reducing balance.
 *
 * Interest is charged on what is *still owed*, so early payments are mostly
 * interest and later ones mostly principal. This is how Nepali banks compute
 * home and personal loans, and it always costs less than a flat rate at the same
 * quoted percentage — quoting one and computing the other is the classic way a
 * loan tracker misleads.
 *
 * **Interest is charged for the days that actually pass**, at `rate ÷ 365` a
 * day, whenever the caller can say when the payments fall — see [Accrual]. A
 * period is not a twelfth of a year: February is not March, and a lump sum paid
 * ten days after an instalment leaves ten days of interest on the old balance
 * that a monthly convention silently forgives. Against a real seven-year loan
 * the twelfths were रू 322 out over ten months; by days it is रू 25.
 *
 * The **quoted instalment is still the monthly formula** ([emi]), because that
 * is what a bank quotes and what the borrower's statement says. Banks quote in
 * twelfths and accrue in days; so does this.
 *
 * Without an [Accrual] — a loan with no dates on file — the old twelfths are
 * used, because there is nothing to count days between.
 *
 * Everything works in minor units. A schedule built from Doubles drifts by a
 * paisa a payment and ends owing something absurd like -0.07 at the end.
 */
object LoanMath {

    /**
     * How many instalments a term of [termMonths] contains when one falls every
     * [monthsPerPayment] months.
     *
     * Rounded up: a 10-month term paid quarterly is four payments, the last one
     * short. Rounding down would end the schedule with money still owing.
     */
    fun payments(termMonths: Int, monthsPerPayment: Int): Int =
        ceilDiv(termMonths.toLong(), monthsPerPayment.coerceAtLeast(1).toLong()).toInt()

    /**
     * The interest rate for one instalment period, as a fraction.
     *
     * A quarterly loan accrues three months of interest between payments, which
     * is why every figure here is derived per *payment* and not per month — and
     * why paying the same loan quarterly costs more in total than paying it
     * monthly.
     */
    private fun periodRate(annualRatePercent: Double, monthsPerPayment: Int): Double =
        (annualRatePercent / 100.0 * monthsPerPayment.coerceAtLeast(1) / 12.0).coerceAtLeast(0.0)

    /**
     * The level payment that clears [principal] over [termMonths], one payment
     * every [monthsPerPayment] months.
     *
     *     EMI = P·r·(1+r)^n / ((1+r)^n − 1)
     *
     * At zero interest that formula divides by zero, so the interest-free case
     * is handled separately rather than nudged with an epsilon.
     */
    fun emi(
        principal: Money,
        annualRatePercent: Double,
        termMonths: Int,
        monthsPerPayment: Int = 1,
    ): Money? {
        if (termMonths <= 0 || principal.minor <= 0L) return null
        val n = payments(termMonths, monthsPerPayment)
        if (n <= 0) return null
        if (annualRatePercent <= 0.0) {
            // Rounded up, so the final payment is the small one. Rounding down
            // would leave a stub owing after the last instalment.
            return Money(ceilDiv(principal.minor, n.toLong()))
        }
        val rate = periodRate(annualRatePercent, monthsPerPayment)
        val growth = (1.0 + rate).pow(n)
        val payment = principal.minor * rate * growth / (growth - 1.0)
        return Money(payment.roundToLong())
    }

    /**
     * The recurring figure for a loan in the given [style] — what the user is
     * quoted, and what the standing instalment pays.
     *
     * It means something different in each style, which is the point: a level
     * instalment is the whole payment, an equal-principal one is the slice of
     * principal with interest added on top, and an interest-only one is the
     * interest with the principal still waiting.
     */
    fun instalment(
        principal: Money,
        annualRatePercent: Double,
        termMonths: Int,
        style: InstalmentStyle,
        monthsPerPayment: Int = 1,
    ): Money? = when (style) {
        InstalmentStyle.LEVEL_EMI -> emi(principal, annualRatePercent, termMonths, monthsPerPayment)
        InstalmentStyle.PRINCIPAL_ONLY ->
            if (termMonths <= 0 || principal.minor <= 0L) {
                null
            } else {
                Money(ceilDiv(principal.minor, payments(termMonths, monthsPerPayment).toLong()))
            }
        // Interest-free and interest-only together mean nothing is due at all,
        // which is not a loan the app can schedule.
        InstalmentStyle.INTEREST_ONLY -> periodInterest(
            principal, annualRatePercent, monthsPerPayment,
        ).takeIf { it.isPositive }
    }

    /**
     * Interest for the **broken period** — the days between the money arriving
     * and the bank's first recovery date.
     *
     * A bank recovers on a fixed day of the month, and a loan handed over on the
     * 3rd meets the 20th before a whole month has passed. What is taken on that
     * first date is not an instalment: it is the interest for those days alone,
     * charged in days rather than in twelfths, and the principal is untouched.
     * The full schedule starts at the next recovery date.
     *
     * Counted on **both** end days, which is how the charge actually lands: रू
     * 27,00,000 at 8.25% disbursed on 3 September and recovered on 20 September
     * is 18 days, 27,00,000 × 0.0825 × 18 ÷ 365 = रू 10,984.93 — the figure on
     * the statement. Counting the difference between the dates gives 17 and is
     * रू 610 short.
     *
     * Returns zero when there is no gap, so a loan whose first instalment is a
     * full period out — the ordinary case — is charged nothing extra.
     */
    fun brokenPeriodInterest(
        principal: Money,
        annualRatePercent: Double,
        disbursedEpochDay: Long,
        firstRecoveryEpochDay: Long,
    ): Money {
        val days = firstRecoveryEpochDay - disbursedEpochDay + 1
        if (days <= 0L || annualRatePercent <= 0.0 || principal.minor <= 0L) return Money.ZERO
        return Money((principal.minor * annualRatePercent / 100.0 * days / 365.0).roundToLong())
    }

    /**
     * How many days each instalment charges for.
     *
     * A period runs from the day *after* the previous payment through the
     * payment day itself, so consecutive periods neither overlap nor leave a day
     * uncharged, and the days between two payment dates is exactly their
     * difference. The first period is the exception in kind rather than in
     * arithmetic: it runs from the day the money arrived, which is charged, and
     * [brokenPeriodInterest] adds the one day that makes that true.
     */
    private fun periodSpans(
        accrual: Accrual,
        total: Int,
        monthsPerPayment: Int,
    ): List<Pair<LocalDate, LocalDate>> {
        val gap = monthsPerPayment.coerceAtLeast(1).toLong()
        val spans = ArrayList<Pair<LocalDate, LocalDate>>(total)
        var previous = accrual.from
        for (n in 0 until total) {
            val due = Recurrence.addMonths(accrual.firstPaymentOn, n * gap, accrual.inBikramSambat)
            spans += previous to maxOf(due, previous)
            previous = due
        }
        return spans
    }

    /**
     * Interest that has run on [balance] since the last instalment — what
     * settling early actually costs on top of the balance itself.
     *
     * A statement's balance is only true on the day it was billed. Between
     * instalments the debt goes on earning interest for the lender, so a lump
     * sum paid ten days after one meets those ten days as well; a payoff figure
     * that ignored them would clear less of the debt than the user was told.
     */
    fun accruedSince(
        balance: Money,
        annualRatePercent: Double,
        from: LocalDate,
        to: LocalDate,
    ): Money {
        val days = ChronoUnit.DAYS.between(from, to)
        if (days <= 0L || annualRatePercent <= 0.0 || balance.minor <= 0L) return Money.ZERO
        return Money((balance.minor * annualRatePercent / 100.0 * days / 365.0).roundToLong())
    }

    /** The interest one instalment period accrues on [balance]. */
    fun periodInterest(
        balance: Money,
        annualRatePercent: Double,
        monthsPerPayment: Int = 1,
    ): Money = Money(
        (balance.minor * periodRate(annualRatePercent, monthsPerPayment)).roundToLong()
    )

    /**
     * How long a loan lasts, in months, when the user names the principal they
     * pay each time rather than the number of months.
     */
    fun termForMonthlyPrincipal(
        principal: Money,
        monthlyPrincipal: Money,
        monthsPerPayment: Int = 1,
    ): Int? {
        if (principal.minor <= 0L || monthlyPrincipal.minor <= 0L) return null
        val n = ceilDiv(principal.minor, monthlyPrincipal.minor).toInt()
        return n * monthsPerPayment.coerceAtLeast(1)
    }

    /**
     * The full repayment schedule.
     *
     * The last instalment is adjusted to whatever actually clears the balance.
     * A level EMI almost never divides the principal exactly, and a schedule
     * that ends at ±3 paisa instead of zero is the kind of detail that makes a
     * user stop trusting every other number in the app.
     *
     * @param emi the recurring figure, meaning whatever [instalment] means for
     *   this [style]. Null falls back to the computed one.
     * @param missed the periods that went unpaid — see [Arrears]. Each of them
     *   is a row that charges its interest and pays nothing, so the balance
     *   holds where it was; both the money and the days are collected by the
     *   next period that is paid, which asks for that many instalments more.
     */
    fun schedule(
        principal: Money,
        annualRatePercent: Double,
        termMonths: Int,
        emi: Money? = null,
        style: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
        monthsPerPayment: Int = 1,
        accrual: Accrual? = null,
        missed: Set<Int> = emptySet(),
    ): List<Instalment> {
        if (termMonths <= 0 || principal.minor <= 0L) return emptyList()
        val total = payments(termMonths, monthsPerPayment)
        if (total <= 0) return emptyList()
        val monthlyRate = periodRate(annualRatePercent, monthsPerPayment)
        val spans = accrual?.let { periodSpans(it, total, monthsPerPayment) }
        val rates = accrual?.rates
        val daily = annualRatePercent / 100.0 / 365.0
        // The interest instalment [n] costs, counted in real days where the
        // caller could say when the payments fall, and in twelfths where it
        // could not — and at whatever rate was in force across those days.
        val interestFor: (Int, Long) -> Long = { n, balance ->
            val span = spans?.get(n - 1)
            val period = when {
                span == null -> (balance * monthlyRate).roundToLong()
                rates != null -> rates.interest(Money(balance), span.first, span.second).minor
                else -> {
                    val days = ChronoUnit.DAYS.between(span.first, span.second)
                    (balance * daily * days).roundToLong()
                }
            }
            // The days that ran on the balance this schedule replaced fall due
            // with its first instalment and nowhere else.
            if (n == 1) period + (accrual?.carriedInterest?.minor ?: 0L) else period
        }
        val rows = mutableListOf<Instalment>()
        var balance = principal.minor
        // What a run of missed periods leaves for the next payment to collect:
        // the days they charged and never settled, and the instalments
        // themselves. Both are handed on rather than forgiven — the money is
        // late, not gone — and both are cleared the moment a payment lands.
        var carried = 0L
        var owed = 0L
        // One period nobody paid: it charges its days onto [carried] and leaves
        // the balance exactly where it found it. Drawn as a row of zeroes rather
        // than left out, so a row's number goes on naming its own period — every
        // caller indexes these by position.
        fun skipped(n: Int): Instalment =
            Instalment(n, Money.ZERO, Money.ZERO, Money.ZERO, Money(balance))

        when (style) {
            InstalmentStyle.LEVEL_EMI -> {
                val payment = emi
                    ?: emi(principal, annualRatePercent, termMonths, monthsPerPayment)
                    ?: return emptyList()
                for (n in 1..total) {
                    val interest = interestFor(n, balance)
                    if (n in missed) {
                        carried += interest
                        owed += payment.minor
                        rows += skipped(n)
                        continue
                    }
                    val isLast = n == total
                    // Everything this payment settles: its own days, plus the days
                    // of any period skipped since the last one that was paid.
                    val charged = interest + carried
                    // The final payment settles the balance exactly, whatever the
                    // level EMI happens to be.
                    val due = if (isLast) balance + charged else payment.minor + owed
                    val principalPart = (due - charged).coerceAtMost(balance)
                    balance -= principalPart
                    rows += Instalment(
                        number = n,
                        payment = Money(principalPart + charged),
                        interest = Money(charged),
                        principal = Money(principalPart),
                        balance = Money(balance),
                    )
                    carried = 0L
                    owed = 0L
                    if (balance <= 0L) break
                }
            }

            InstalmentStyle.PRINCIPAL_ONLY -> {
                // A fixed slice of principal, plus whatever interest has accrued
                // since the last one. The payment therefore falls each time.
                val perMonth = (emi?.minor ?: ceilDiv(principal.minor, total.toLong()))
                if (perMonth <= 0L) return emptyList()
                for (n in 1..total) {
                    val interest = interestFor(n, balance)
                    if (n in missed) {
                        carried += interest
                        owed += perMonth
                        rows += skipped(n)
                        continue
                    }
                    val charged = interest + carried
                    val principalPart = (perMonth + owed).coerceAtMost(balance)
                    balance -= principalPart
                    rows += Instalment(
                        number = n,
                        payment = Money(principalPart + charged),
                        interest = Money(charged),
                        principal = Money(principalPart),
                        balance = Money(balance),
                    )
                    carried = 0L
                    owed = 0L
                    if (balance <= 0L) break
                }
            }

            InstalmentStyle.INTEREST_ONLY -> {
                // The balance does not move until the end, so every payment costs
                // the same and the last one is the whole loan again.
                for (n in 1..total) {
                    val period = emi?.minor ?: interestFor(n, balance)
                    if (n in missed) {
                        carried += period
                        rows += skipped(n)
                        continue
                    }
                    val interest = period + carried
                    val principalPart = if (n == total) balance else 0L
                    balance -= principalPart
                    rows += Instalment(
                        number = n,
                        payment = Money(principalPart + interest),
                        interest = Money(interest),
                        principal = Money(principalPart),
                        balance = Money(balance),
                    )
                    carried = 0L
                }
            }
        }
        return rows
    }

    /**
     * What is still owed once [periodsElapsed] of the schedule have run.
     *
     * Derived from the schedule rather than by subtracting payments from the
     * principal: most of an early payment is interest, so naive subtraction
     * would show the loan clearing far faster than it does.
     *
     * Periods rather than payments, because the two stopped being the same
     * number the moment an instalment could be swiped away: a period that went
     * unpaid is still a row of the schedule, and one that collected a missed one
     * is still a single row. See [Arrears] and [schedule]'s `missed`.
     */
    fun outstanding(
        principal: Money,
        annualRatePercent: Double,
        termMonths: Int,
        periodsElapsed: Int,
        emi: Money? = null,
        style: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
        monthsPerPayment: Int = 1,
        accrual: Accrual? = null,
        missed: Set<Int> = emptySet(),
    ): Money {
        if (periodsElapsed <= 0) return principal
        val rows = schedule(
            principal, annualRatePercent, termMonths, emi, style, monthsPerPayment, accrual,
            missed,
        )
        if (rows.isEmpty()) return principal
        return rows.getOrNull(periodsElapsed - 1)?.balance ?: Money.ZERO
    }

    /** Total interest paid across the whole term. */
    fun totalInterest(
        principal: Money,
        annualRatePercent: Double,
        termMonths: Int,
        emi: Money? = null,
        style: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
        monthsPerPayment: Int = 1,
        accrual: Accrual? = null,
    ): Money = Money(
        schedule(principal, annualRatePercent, termMonths, emi, style, monthsPerPayment, accrual)
            .sumOf { it.interest.minor }
    )

    /**
     * Months needed to clear [outstanding] while keeping paying [emi].
     *
     *     n = −ln(1 − B·r/EMI) / ln(1+r)
     *
     * This is the "keep the instalment, finish sooner" answer to a prepayment.
     * Returns null when the instalment does not even cover the monthly interest,
     * because then the debt grows and no number of payments clears it — a case
     * that must be refused rather than reported as some huge tenure.
     */
    fun tenureAfterPrepayment(
        outstanding: Money,
        annualRatePercent: Double,
        emi: Money,
        monthsPerPayment: Int = 1,
        accrual: Accrual? = null,
    ): Int? {
        if (outstanding.minor <= 0L) return 0
        if (emi.minor <= 0L) return null
        val gap = monthsPerPayment.coerceAtLeast(1)
        if (annualRatePercent <= 0.0) {
            return ceilDiv(outstanding.minor, emi.minor).toInt() * gap
        }
        // Walked rather than solved once the days matter. The closed form below
        // assumes every period is the same length, which is the one thing daily
        // accrual gives up: 28 days in February and 31 in March clear different
        // amounts of principal, and the answer is a whole number of payments
        // anyway.
        if (accrual != null) {
            val daily = annualRatePercent / 100.0 / 365.0
            var balance = outstanding.minor
            var previous = accrual.from
            var paid = 0
            while (balance > 0L && paid < MAX_PAYMENTS) {
                val due = maxOf(
                    Recurrence.addMonths(
                        accrual.firstPaymentOn, paid.toLong() * gap, accrual.inBikramSambat,
                    ),
                    previous,
                )
                val period = accrual.rates
                    ?.interest(Money(balance), previous, due)?.minor
                    ?: (balance * daily * ChronoUnit.DAYS.between(previous, due)).roundToLong()
                val interest = period +
                    if (paid == 0) accrual.carriedInterest.minor else 0L
                // The instalment does not even cover the interest, so the debt
                // grows and no number of payments clears it.
                if (emi.minor <= interest) return null
                balance += interest - emi.minor
                previous = due
                paid++
            }
            return if (balance > 0L) null else paid * gap
        }

        val rate = periodRate(annualRatePercent, gap)
        val accrued = outstanding.minor * rate
        if (emi.minor <= accrued) return null

        val periods = -ln(1.0 - (outstanding.minor * rate / emi.minor)) / ln(1.0 + rate)
        // Rounded up: a fractional period is still a payment the borrower makes.
        return ceil(periods).toInt().coerceAtLeast(1) * gap
    }

    /**
     * The instalment that clears [outstanding] over [remainingMonths] — the
     * "keep the end date, pay less each time" answer to a prepayment.
     */
    fun emiAfterPrepayment(
        outstanding: Money,
        annualRatePercent: Double,
        remainingMonths: Int,
        monthsPerPayment: Int = 1,
    ): Money? = emi(outstanding, annualRatePercent, remainingMonths, monthsPerPayment)

    /**
     * What a prepayment saves, both ways, so the two options can be compared
     * before choosing.
     */
    fun comparePrepayment(
        outstanding: Money,
        annualRatePercent: Double,
        currentEmi: Money,
        remainingMonths: Int,
        prepayment: Money,
        monthsPerPayment: Int = 1,
        accrual: Accrual? = null,
    ): PrepaymentOutcome? {
        if (prepayment.minor <= 0L) return null
        val gap = monthsPerPayment.coerceAtLeast(1)
        val reduced = Money((outstanding.minor - prepayment.minor).coerceAtLeast(0L))
        val before = totalInterest(
            outstanding, annualRatePercent, remainingMonths, currentEmi,
            monthsPerPayment = gap, accrual = accrual,
        )
        if (reduced.isZero) {
            return PrepaymentOutcome(
                newBalance = reduced,
                shorterTermMonths = 0,
                sameTermEmi = Money.ZERO,
                interestSavedByShortening = before,
                interestSavedByLowering = before,
            )
        }
        val shorter = tenureAfterPrepayment(reduced, annualRatePercent, currentEmi, gap, accrual)
        val lowerEmi = emiAfterPrepayment(reduced, annualRatePercent, remainingMonths, gap)

        return PrepaymentOutcome(
            newBalance = reduced,
            shorterTermMonths = shorter,
            sameTermEmi = lowerEmi,
            interestSavedByShortening = shorter?.let {
                Money(
                    (before.minor - totalInterest(
                        reduced, annualRatePercent, it, currentEmi,
                        monthsPerPayment = gap, accrual = accrual,
                    ).minor).coerceAtLeast(0L)
                )
            },
            interestSavedByLowering = lowerEmi?.let {
                Money(
                    (before.minor - totalInterest(
                        reduced, annualRatePercent, remainingMonths, it,
                        monthsPerPayment = gap, accrual = accrual,
                    ).minor).coerceAtLeast(0L)
                )
            },
        )
    }

    /**
     * Outstanding on a loan with no schedule — an informal one with no interest,
     * no term and no fixed instalment. It is simply what is left after what has
     * been repaid.
     */
    fun outstandingSimple(principal: Money, repaid: Money): Money =
        Money((principal.minor - repaid.minor).coerceAtLeast(0L))

    /**
     * What the balance opened at, given where it stands today and every step it
     * took to get there.
     *
     * Only the present figure is a fact: a lump sum rewrites a loan's principal
     * in place, so the sum it began at is no longer stored anywhere. Walking the
     * changes back out of today's balance recovers it — and for an overdraft,
     * which genuinely opens at nothing and is made entirely of its own
     * withdrawals, the same arithmetic lands on zero without being told to.
     */
    fun openingBalance(outstanding: Money, changes: List<BalanceChange>): Money =
        Money(outstanding.minor - changes.sumOf { it.deltaMinor })

    /**
     * Everything ever put out on this arrangement: what it opened at, plus
     * anything added to it since — before any of it came back.
     *
     * This is what "the loan amount" means to the person who took it, and it is
     * not [Money] the loan still stores anywhere. A lump sum re-bases the debt in
     * place — `principal := remaining` — so the moment रू 5,000 is paid off
     * रू 1,00,000, the only figure on file is रू 95,000, and a form labelling
     * that "Loan amount" tells the borrower they borrowed less than they did.
     *
     * The additions have to be added back in rather than left inside the opening
     * figure, because the debt arriving is itself one of them: a debt whose
     * disbursement was recorded opens at nothing and is made entirely of that
     * first row, exactly as an overdraft is made of its withdrawals.
     */
    fun totalAdvanced(outstanding: Money, changes: List<BalanceChange>): Money = Money(
        openingBalance(outstanding, changes).minor +
            changes.filter { it.deltaMinor > 0L }.sumOf { it.deltaMinor }
    )

    /**
     * Interest accrued day by day on a balance that moves — an overdraft,
     * where money is drawn and repaid at will and there is no schedule to
     * read a figure off.
     *
     * Each day charges the balance *actually drawn that day* at the annual
     * rate over 365, which is how a bank meters an overdraft. Simple rather
     * than compounded: the app cannot know when the bank capitalises, and
     * understating slightly is better than inventing a compounding date.
     * A draw made today has accrued nothing yet.
     */
    fun accruedInterest(
        changes: List<BalanceChange>,
        annualRatePercent: Double,
        asOfEpochDay: Long,
        /**
         * The rate history, when the figure has moved. Each run of days is then
         * charged at the rate in force on it rather than at one rate throughout
         * — the same split [RateSchedule.interest] does everywhere else. It also
         * covers the debt whose rate was agreed *after* it was written down:
         * there the holding's own figure is none, and every rate it charges is
         * in here.
         */
        rates: RateSchedule? = null,
    ): Money {
        if (changes.isEmpty()) return Money.ZERO
        if (rates == null && annualRatePercent <= 0.0) return Money.ZERO
        val daily = annualRatePercent / 100.0 / 365.0
        val sorted = changes.sortedBy { it.epochDay }
        var balance = 0L
        var accrued = 0.0
        var day = sorted.first().epochDay
        // One run of days at one balance, charged through the rate history when
        // there is one and at the single figure when there is not.
        fun charge(from: Long, to: Long) {
            if (balance <= 0L || to <= from) return
            accrued += if (rates != null) {
                rates.interest(
                    Money(balance),
                    LocalDate.ofEpochDay(from),
                    LocalDate.ofEpochDay(to),
                ).minor.toDouble()
            } else {
                balance * daily * (to - from)
            }
        }
        for (change in sorted) {
            if (change.epochDay > day) {
                charge(day, change.epochDay)
                day = change.epochDay
            }
            balance += change.deltaMinor
        }
        charge(day, asOfEpochDay)
        return Money(accrued.roundToLong())
    }

    private fun ceilDiv(value: Long, divisor: Long): Long =
        if (divisor == 0L) 0L else (value + divisor - 1) / divisor

    /** A hundred years of monthly payments: a backstop, not a limit. */
    private const val MAX_PAYMENTS = 1_200
}
