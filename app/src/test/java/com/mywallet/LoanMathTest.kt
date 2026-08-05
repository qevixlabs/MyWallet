package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.domain.Accrual
import com.mywallet.domain.BalanceChange
import com.mywallet.domain.BrokenPeriod
import com.mywallet.domain.LoanMath
import com.mywallet.domain.RateChange
import com.mywallet.domain.RateSchedule
import com.mywallet.domain.accrualFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * EMI arithmetic checked against figures a bank would quote. A loan tracker
 * that is a few rupees out every month erodes trust in every other number.
 */
class LoanMathTest {

    /** रू 10,00,000 at 12% over 10 years — a textbook worked example. */
    @Test
    fun `emi matches the standard reducing-balance formula`() {
        val emi = LoanMath.emi(Money(1_000_000_00), 12.0, 120)!!
        // 14,347.09 per month by the closed-form formula.
        assertEquals(14_347_09, emi.minor)
    }

    @Test
    fun `a one-year loan at 10 percent gives the expected instalment`() {
        val emi = LoanMath.emi(Money(120_000_00), 10.0, 12)!!
        assertEquals(10_549_91, emi.minor)
    }

    @Test
    fun `zero interest simply divides the principal`() {
        val emi = LoanMath.emi(Money(120_000_00), 0.0, 12)!!
        assertEquals(10_000_00, emi.minor)
    }

    @Test
    fun `the schedule clears the balance to exactly zero`() {
        val principal = Money(1_000_000_00)
        val rows = LoanMath.schedule(principal, 12.0, 120)

        assertEquals(120, rows.size)
        assertEquals("loan must end at exactly zero", 0L, rows.last().balance.minor)
    }

    @Test
    fun `principal repaid across the schedule equals the amount borrowed`() {
        val principal = Money(1_000_000_00)
        val rows = LoanMath.schedule(principal, 12.0, 120)
        assertEquals(principal.minor, rows.sumOf { it.principal.minor })
    }

    @Test
    fun `every payment is interest plus principal, with nothing lost to rounding`() {
        LoanMath.schedule(Money(750_000_00), 9.5, 60).forEach { row ->
            assertEquals(
                "instalment ${row.number} does not add up",
                row.payment.minor,
                row.interest.minor + row.principal.minor,
            )
        }
    }

    @Test
    fun `interest falls and principal rises as the balance reduces`() {
        val rows = LoanMath.schedule(Money(500_000_00), 11.0, 36)
        val first = rows.first()
        val last = rows.last()
        assertTrue("interest should fall over the term", last.interest < first.interest)
        assertTrue("principal should rise over the term", last.principal > first.principal)
    }

    @Test
    fun `outstanding follows the schedule, not a naive subtraction`() {
        val principal = Money(1_000_000_00)
        val afterOneYear = LoanMath.outstanding(principal, 12.0, 120, periodsElapsed = 12)

        // Naive "12 payments off the principal" would say 1,000,000 - 172,165 =
        // 827,835. Reducing balance leaves far more owing, because most of the
        // first year went on interest.
        val naive = principal.minor - (LoanMath.emi(principal, 12.0, 120)!!.minor * 12)
        assertTrue(
            "outstanding ${afterOneYear.minor} should exceed the naive $naive",
            afterOneYear.minor > naive,
        )
        assertTrue(afterOneYear.minor in 92_000_000..96_000_000)
    }

    @Test
    fun `outstanding is zero once every payment is made`() {
        assertEquals(
            0L,
            LoanMath.outstanding(Money(240_000_00), 8.0, 24, periodsElapsed = 24).minor,
        )
    }

    @Test
    fun `total interest is positive and grows with the rate`() {
        val cheap = LoanMath.totalInterest(Money(500_000_00), 8.0, 60)
        val dear = LoanMath.totalInterest(Money(500_000_00), 16.0, 60)
        assertTrue(cheap.minor > 0)
        assertTrue("a higher rate must cost more", dear.minor > cheap.minor)
    }

    @Test
    fun `an informal loan with no schedule just subtracts what was repaid`() {
        assertEquals(
            Money(40_000_00),
            LoanMath.outstandingSimple(Money(100_000_00), Money(60_000_00)),
        )
        // Overpaying settles the loan rather than going negative.
        assertEquals(
            Money.ZERO,
            LoanMath.outstandingSimple(Money(100_000_00), Money(120_000_00)),
        )
    }

    @Test
    fun `nonsense inputs are refused rather than guessed at`() {
        assertNull(LoanMath.emi(Money(100_000_00), 12.0, 0))
        assertNull(LoanMath.emi(Money(0), 12.0, 12))
        assertTrue(LoanMath.schedule(Money(100_000_00), 12.0, 0).isEmpty())
    }

    // ------------------------------------------------------- instalment styles

    @Test
    fun `an equal-principal loan repays the same principal every month`() {
        val rows = LoanMath.schedule(
            principal = Money(500_000_00),
            annualRatePercent = 12.0,
            termMonths = 50,
            style = InstalmentStyle.PRINCIPAL_ONLY,
        )
        assertEquals(50, rows.size)
        assertEquals(0L, rows.last().balance.minor)
        // Every principal slice is identical…
        assertEquals(1, rows.map { it.principal.minor }.distinct().size)
        // …and the payment falls, because the interest is charged on less each
        // month. That is the whole reason to choose this over a level EMI.
        assertTrue(
            "the payment must fall month by month",
            rows.last().payment < rows.first().payment,
        )
    }

    @Test
    fun `equal principal costs less interest than a level instalment`() {
        val principal = Money(500_000_00)
        val level = LoanMath.totalInterest(principal, 12.0, 60)
        val equalPrincipal = LoanMath.totalInterest(
            principal, 12.0, 60, style = InstalmentStyle.PRINCIPAL_ONLY,
        )
        assertTrue(
            "paying principal down faster must cost less: $equalPrincipal vs $level",
            equalPrincipal < level,
        )
    }

    @Test
    fun `naming the monthly principal decides how long the loan runs`() {
        // 5 lakh at 10,000 a month is 50 months, whatever anyone types in a term box.
        assertEquals(50, LoanMath.termForMonthlyPrincipal(Money(500_000_00), Money(10_000_00)))
        // A remainder still needs its own final month.
        assertEquals(51, LoanMath.termForMonthlyPrincipal(Money(505_000_00), Money(10_000_00)))
        assertNull(LoanMath.termForMonthlyPrincipal(Money(500_000_00), Money.ZERO))
    }

    @Test
    fun `an interest-only loan leaves the balance where it was until the end`() {
        val principal = Money(300_000_00)
        val rows = LoanMath.schedule(
            principal = principal,
            annualRatePercent = 12.0,
            termMonths = 12,
            style = InstalmentStyle.INTEREST_ONLY,
        )
        assertEquals(12, rows.size)
        // 1% of 3 lakh, every month, for eleven months.
        rows.dropLast(1).forEach { row ->
            assertEquals(3_000_00, row.payment.minor)
            assertEquals(0L, row.principal.minor)
            assertEquals(principal, row.balance)
        }
        // The last one is the whole loan again, plus that month's interest.
        assertEquals(principal, rows.last().principal)
        assertEquals(0L, rows.last().balance.minor)

        // Eleven months of servicing leave the debt untouched.
        assertEquals(
            principal,
            LoanMath.outstanding(
                principal, 12.0, 12, periodsElapsed = 11, style = InstalmentStyle.INTEREST_ONLY,
            ),
        )
    }

    @Test
    fun `the quoted instalment means a different thing in each style`() {
        val principal = Money(120_000_00)
        val level = LoanMath.instalment(principal, 12.0, 12, InstalmentStyle.LEVEL_EMI)!!
        val slice = LoanMath.instalment(principal, 12.0, 12, InstalmentStyle.PRINCIPAL_ONLY)!!
        val interest = LoanMath.instalment(principal, 12.0, 12, InstalmentStyle.INTEREST_ONLY)!!

        assertEquals("a twelfth of the principal", 10_000_00, slice.minor)
        assertEquals("one month of interest", 1_200_00, interest.minor)
        // The level instalment sits between: more than bare principal, less than
        // principal plus a full month of interest on the whole sum.
        assertTrue(level > slice)
        assertTrue(level < slice + interest)
    }

    @Test
    fun `an interest-free loan has no interest-only instalment to pay`() {
        assertNull(LoanMath.instalment(Money(50_000_00), 0.0, 10, InstalmentStyle.INTEREST_ONLY))
    }

    // ------------------------------------------------- how often it is paid

    @Test
    fun `a quarterly loan is paid a third as often and clears the same balance`() {
        val principal = Money(1_200_000_00)
        val rows = LoanMath.schedule(principal, 12.0, 36, monthsPerPayment = 3)

        assertEquals("three years, paid quarterly, is twelve payments", 12, rows.size)
        assertEquals("it must still end at exactly zero", 0L, rows.last().balance.minor)
        assertEquals(principal.minor, rows.sumOf { it.principal.minor })
    }

    @Test
    fun `paying less often costs more interest, because it accrues in between`() {
        val principal = Money(1_200_000_00)
        val monthly = LoanMath.totalInterest(principal, 12.0, 36)
        val quarterly = LoanMath.totalInterest(principal, 12.0, 36, monthsPerPayment = 3)
        val yearly = LoanMath.totalInterest(principal, 12.0, 36, monthsPerPayment = 12)

        // The balance sits untouched for longer each time, so more interest is
        // charged on it. A user who switches to quarterly and sees the same
        // total would rightly stop believing the figure.
        assertTrue("quarterly must cost more than monthly", quarterly > monthly)
        assertTrue("yearly must cost more than quarterly", yearly > quarterly)
    }

    @Test
    fun `a yearly instalment charges a whole year of interest`() {
        // 10% on 10 lakh, interest only, once a year: exactly one lakh.
        val yearly = LoanMath.instalment(
            Money(1_000_000_00), 10.0, 36, InstalmentStyle.INTEREST_ONLY, monthsPerPayment = 12,
        )!!
        assertEquals(100_000_00, yearly.minor)
        // The same loan paid monthly costs a twelfth of that each time.
        val monthly = LoanMath.instalment(
            Money(1_000_000_00), 10.0, 36, InstalmentStyle.INTEREST_ONLY,
        )!!
        assertEquals(8_333_33, monthly.minor)
    }

    @Test
    fun `a term that does not divide evenly still gets a final payment`() {
        // Ten months paid quarterly is four payments, the last one short —
        // rounding down would end the schedule with money still owing.
        assertEquals(4, LoanMath.payments(10, 3))
        assertEquals(0L, LoanMath.schedule(
            Money(100_000_00), 9.0, 10, monthsPerPayment = 3,
        ).last().balance.minor)
    }

    @Test
    fun `a derived term is measured in months however often it is paid`() {
        // 5 lakh at 10,000 a quarter is 50 payments — which is 150 months, not
        // 50. The term box is in months, so that is what comes back.
        assertEquals(
            150,
            LoanMath.termForMonthlyPrincipal(Money(500_000_00), Money(10_000_00), 3),
        )
    }

    // ------------------------------------------------------------ prepayment

    @Test
    fun `keeping the instalment after a prepayment shortens the term`() {
        val outstanding = Money(800_000_00)
        val emi = LoanMath.emi(Money(1_000_000_00), 12.0, 120)!!
        val before = LoanMath.tenureAfterPrepayment(outstanding, 12.0, emi)!!
        val after = LoanMath.tenureAfterPrepayment(Money(600_000_00), 12.0, emi)!!
        assertTrue("paying a lump sum must shorten the term", after < before)
    }

    @Test
    fun `keeping the term after a prepayment lowers the instalment`() {
        val emi = LoanMath.emi(Money(1_000_000_00), 12.0, 120)!!
        val lower = LoanMath.emiAfterPrepayment(Money(600_000_00), 12.0, 96)!!
        assertTrue("a smaller balance over the same months must cost less", lower < emi)
    }

    @Test
    fun `an instalment that cannot cover the interest is refused`() {
        // 12% on 10 lakh is 10,000 a month in interest alone. Paying 5,000
        // never clears it, and reporting some enormous tenure would be a lie.
        assertNull(LoanMath.tenureAfterPrepayment(Money(1_000_000_00), 12.0, Money(5_000_00)))
    }

    @Test
    fun `prepayment comparison offers both routes and saves interest either way`() {
        val emi = LoanMath.emi(Money(1_000_000_00), 12.0, 120)!!
        val outcome = LoanMath.comparePrepayment(
            outstanding = Money(900_000_00),
            annualRatePercent = 12.0,
            currentEmi = emi,
            remainingMonths = 108,
            prepayment = Money(200_000_00),
        )!!

        assertEquals(Money(700_000_00), outcome.newBalance)
        assertTrue(outcome.shorterTermMonths!! < 108)
        assertTrue(outcome.sameTermEmi!! < emi)
        assertTrue("shortening must save interest", outcome.interestSavedByShortening!!.minor > 0)
        assertTrue("lowering must save interest", outcome.interestSavedByLowering!!.minor > 0)
        assertTrue(
            "shortening the term saves more than lowering the instalment",
            outcome.interestSavedByShortening!! > outcome.interestSavedByLowering!!,
        )
    }

    @Test
    fun `paying the whole balance clears the loan`() {
        val outcome = LoanMath.comparePrepayment(
            outstanding = Money(500_000_00),
            annualRatePercent = 10.0,
            currentEmi = Money(10_000_00),
            remainingMonths = 60,
            prepayment = Money(500_000_00),
        )!!
        assertEquals(Money.ZERO, outcome.newBalance)
        assertEquals(0, outcome.shorterTermMonths)
    }

    @Test
    fun `an interest-free loan clears in whole instalments`() {
        assertEquals(10, LoanMath.tenureAfterPrepayment(Money(100_000_00), 0.0, Money(10_000_00)))
        // A remainder still costs a final, smaller payment.
        assertEquals(11, LoanMath.tenureAfterPrepayment(Money(105_000_00), 0.0, Money(10_000_00)))
    }

    @Test
    fun `an overridden instalment still splits into interest and the loan itself`() {
        val principal = Money(100_000_00)
        // The bank quotes 8,791.59 for a year at 10%, but the user rounds it
        // up to 10,000 with "use this amount instead".
        val rows = LoanMath.schedule(principal, 10.0, 12, emi = Money(10_000_00))
        // Interest is still charged on what is owed; everything above it goes
        // against the loan. First month: 833.33 interest on the full balance.
        assertEquals(833_33, rows.first().interest.minor)
        assertEquals(9_166_67, rows.first().principal.minor)
        rows.dropLast(1).forEach { row ->
            assertEquals(10_000_00, row.payment.minor)
            assertEquals(row.payment.minor, row.interest.minor + row.principal.minor)
        }
        // Paying more each time clears the loan early and exactly.
        assertTrue("should clear before 12 payments", rows.size < 12)
        assertEquals(0L, rows.last().balance.minor)
        // And the outstanding figure follows the overridden schedule, so a
        // confirmed payment deducts what the user actually pays.
        assertEquals(
            rows[1].balance,
            LoanMath.outstanding(principal, 10.0, 12, periodsElapsed = 2, emi = Money(10_000_00)),
        )
    }

    @Test
    fun `overdraft interest meters the drawn balance per day`() {
        // रू 1,00,000 drawn at 10% for 73 days = 100000 × 0.10 × 73/365 = रू 2,000.
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 0, deltaMinor = 100_000_00)),
            annualRatePercent = 10.0,
            asOfEpochDay = 73,
        )
        assertEquals(2_000_00, accrued.minor)
    }

    @Test
    fun `a repayment stops the meter on the part repaid`() {
        // Drawn 1,00,000 on day 0, half repaid on day 73: 2,000 for the first
        // stretch, then 50,000 × 0.10 × 73/365 = 1,000 for the second.
        val accrued = LoanMath.accruedInterest(
            changes = listOf(
                BalanceChange(epochDay = 0, deltaMinor = 100_000_00),
                BalanceChange(epochDay = 73, deltaMinor = -50_000_00),
            ),
            annualRatePercent = 10.0,
            asOfEpochDay = 146,
        )
        assertEquals(3_000_00, accrued.minor)
    }

    @Test
    fun `money drawn today has cost nothing yet`() {
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 100, deltaMinor = 50_000_00)),
            annualRatePercent = 12.0,
            asOfEpochDay = 100,
        )
        assertEquals(0L, accrued.minor)
    }

    @Test
    fun `what was borrowed survives a lump sum rewriting the balance`() {
        // रू 1,00,000 borrowed, रू 5,000 paid off: the loan now stores 95,000
        // and nothing else, so the figure the borrower knows has to be walked
        // back out of the movements.
        val paid = listOf(BalanceChange(epochDay = 100, deltaMinor = -5_000_00))
        assertEquals(
            1_00_000_00,
            LoanMath.totalAdvanced(Money(95_000_00), paid).minor,
        )
    }

    @Test
    fun `borrowing more adds to what was borrowed rather than hiding in it`() {
        // Started at 1,00,000, paid 5,000, borrowed 500 more: 95,500 owed and
        // 1,00,500 taken on in all.
        val steps = listOf(
            BalanceChange(epochDay = 100, deltaMinor = -5_000_00),
            BalanceChange(epochDay = 200, deltaMinor = 500_00),
        )
        assertEquals(1_00_500_00, LoanMath.totalAdvanced(Money(95_500_00), steps).minor)
    }

    @Test
    fun `a debt whose arrival was recorded counts that row as the amount`() {
        // The money landing in an account is itself one of the movements, so the
        // balance opens at nothing and the whole debt is that first row.
        val steps = listOf(
            BalanceChange(epochDay = 0, deltaMinor = 1_00_000_00),
            BalanceChange(epochDay = 100, deltaMinor = -5_000_00),
        )
        assertEquals(1_00_000_00, LoanMath.totalAdvanced(Money(95_000_00), steps).minor)
    }

    @Test
    fun `a rate agreed part-way through charges only the days since`() {
        // रू 1,00,000 owed from day 0 with no rate agreed until day 292: only
        // the days from then on are charged — 292 through 365, which is 74 of
        // them, because the day a rate starts is charged at it — so
        // 100000 × 0.10 × 74/365 and not a full year's रू 10,000.
        val agreed = LocalDate.ofEpochDay(292)
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 0, deltaMinor = 100_000_00)),
            // The debt itself never had a rate: everything it charges is in the
            // history, which is exactly the shape "add interest later" produces.
            annualRatePercent = 0.0,
            asOfEpochDay = 365,
            rates = RateSchedule(base = 0.0, changes = listOf(RateChange(agreed, 10.0))),
        )
        assertEquals(2_027_40, accrued.minor)
    }

    @Test
    fun `a rate that moves is split at the day it moved`() {
        // Half the year at 10% and half at 20% on रू 1,00,000: the day the new
        // rate starts is charged at the new one, so 182 days then 183.
        val moved = LocalDate.ofEpochDay(183)
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 0, deltaMinor = 100_000_00)),
            annualRatePercent = 10.0,
            asOfEpochDay = 365,
            rates = RateSchedule(base = 10.0, changes = listOf(RateChange(moved, 20.0))),
        )
        // 100000 × (0.10 × 182 + 0.20 × 183) / 365, to the paisa.
        val expected = Math.round(100_000_00 * (0.10 * 182 + 0.20 * 183) / 365.0)
        assertEquals(expected, accrued.minor)
    }

    @Test
    fun `an interest-free facility accrues nothing`() {
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 0, deltaMinor = 100_000_00)),
            annualRatePercent = 0.0,
            asOfEpochDay = 365,
        )
        assertEquals(0L, accrued.minor)
    }

    @Test
    fun `the opening balance is what is left when the changes are walked back out`() {
        // Lent 10,000; 2,000 more went out, then 3,000 came back — 9,000 today.
        val opening = LoanMath.openingBalance(
            outstanding = Money(9_000_00),
            changes = listOf(
                BalanceChange(epochDay = 10, deltaMinor = 2_000_00),
                BalanceChange(epochDay = 40, deltaMinor = -3_000_00),
            ),
        )
        assertEquals(10_000_00, opening.minor)
    }

    @Test
    fun `an overdraft opens at nothing without being told to`() {
        // Every rupee of an overdraft is one of its own withdrawals, so walking
        // the changes back out of the drawn balance has to land on zero.
        val opening = LoanMath.openingBalance(
            outstanding = Money(50_000_00),
            changes = listOf(
                BalanceChange(epochDay = 0, deltaMinor = 80_000_00),
                BalanceChange(epochDay = 30, deltaMinor = -30_000_00),
            ),
        )
        assertEquals(0L, opening.minor)
    }

    /**
     * The broken period, against a bank's own figure.
     *
     * रू 27,00,000 at 8.25% handed over on 3 September 2025, first recovered on
     * the 20th: the statement says रू 10,984.93, which is 18 days — both end
     * days counted. Seventeen would be रू 10,374.66, and रू 610 short.
     */
    @Test
    fun `the broken period is charged in days, counting both ends`() {
        val disbursed = LocalDate.of(2025, 9, 3).toEpochDay()
        val recovered = LocalDate.of(2025, 9, 20).toEpochDay()
        assertEquals(
            10_984_93,
            LoanMath.brokenPeriodInterest(Money(27_00_000_00), 8.25, disbursed, recovered).minor,
        )
    }

    @Test
    fun `no gap, no broken period`() {
        val day = LocalDate.of(2025, 9, 20).toEpochDay()
        // Recovered the day the money arrived, and recovered before it: neither
        // can owe interest, and a negative day count must not produce a credit.
        assertEquals(
            0L,
            LoanMath.brokenPeriodInterest(Money(27_00_000_00), 8.25, day, day - 1).minor,
        )
        assertEquals(
            0L,
            LoanMath.brokenPeriodInterest(Money(27_00_000_00), 0.0, day - 10, day).minor,
        )
    }

    @Test
    fun `a stub pushes the schedule to the next recovery date, a full period does not`() {
        val disbursed = LocalDate.of(2025, 9, 3)
        val stubbed = LocalDate.of(2025, 9, 20)
        assertTrue(BrokenPeriod.applies(disbursed, stubbed, monthsPerPayment = 1))
        assertEquals(
            LocalDate.of(2025, 10, 20),
            BrokenPeriod.firstInstalment(disbursed, stubbed, monthsPerPayment = 1),
        )

        // The ordinary arrangement: the first instalment a whole month out. It
        // is an instalment, not a stub, and the schedule starts on it.
        val clean = LocalDate.of(2025, 10, 3)
        assertFalse(BrokenPeriod.applies(disbursed, clean, monthsPerPayment = 1))
        assertEquals(clean, BrokenPeriod.firstInstalment(disbursed, clean, monthsPerPayment = 1))

        // Nothing is inferred for a loan with no disbursement date on file.
        assertFalse(BrokenPeriod.applies(null, stubbed, monthsPerPayment = 1))
        assertEquals(stubbed, BrokenPeriod.firstInstalment(null, stubbed, monthsPerPayment = 1))

        // A quarterly loan's whole period is three months, so the same
        // seventeen-day gap is still a stub — and the schedule starts a quarter
        // after the stub, not a month.
        assertTrue(BrokenPeriod.applies(disbursed, stubbed, monthsPerPayment = 3))
        assertEquals(
            LocalDate.of(2025, 12, 20),
            BrokenPeriod.firstInstalment(disbursed, stubbed, monthsPerPayment = 3),
        )
    }

    @Test
    fun `a debt settled in one payment has no broken period to push it out`() {
        // A one-year loan repaid in a single payment: the gap between payments
        // is the loan itself. Whatever day that payment falls on is the day the
        // whole debt comes due, so it cannot be "early" — and pushing it a
        // period later, as a stub would, would move it a whole year out.
        val disbursed = LocalDate.of(2026, 1, 3)
        val due = LocalDate.of(2026, 6, 20)
        assertFalse(
            BrokenPeriod.applies(disbursed, due, monthsPerPayment = 12, termMonths = 12),
        )
        assertEquals(
            due,
            BrokenPeriod.firstInstalment(disbursed, due, monthsPerPayment = 12, termMonths = 12),
        )

        // The same dates on a loan that really is paid every twelve months over
        // five years: that first payment settles a stub, exactly as before.
        assertTrue(
            BrokenPeriod.applies(disbursed, due, monthsPerPayment = 12, termMonths = 60),
        )
    }

    @Test
    fun `one payment period as long as the term is principal plus simple interest`() {
        // What "pay it all at the end" has to come to: रू 10,000 borrowed at
        // 10% for a year is रू 11,000 handed back on one day. It falls out of
        // the ordinary schedule rather than needing arithmetic of its own — a
        // single period one year long accrues one year of interest.
        val rows = LoanMath.schedule(
            principal = Money(10_000_00),
            annualRatePercent = 10.0,
            termMonths = 12,
            monthsPerPayment = 12,
        )
        assertEquals(1, rows.size)
        assertEquals(11_000_00L, rows.single().payment.minor)
        assertEquals(10_000_00L, rows.single().principal.minor)
        assertEquals(1_000_00L, rows.single().interest.minor)
        assertEquals(0L, rows.single().balance.minor)

        // And the figure the form quotes for it is that same payment.
        assertEquals(
            11_000_00L,
            LoanMath.emi(Money(10_000_00), 10.0, termMonths = 12, monthsPerPayment = 12)!!.minor,
        )
    }

    @Test
    fun `a single payment counted in days charges the days that actually pass`() {
        // The same loan once it has dates to count between. A year is 365 days
        // from 10 January, so the interest is the same रू 1,000 — the twelfths
        // and the days agree on a whole year, and part-years are where they
        // part company.
        val disbursed = LocalDate.of(2026, 1, 10)
        val due = LocalDate.of(2027, 1, 10)
        val rows = LoanMath.schedule(
            principal = Money(10_000_00),
            annualRatePercent = 10.0,
            termMonths = 12,
            monthsPerPayment = 12,
            accrual = accrualFor(disbursed, due, monthsPerPayment = 12)!!,
        )
        assertEquals(1, rows.size)
        assertEquals(11_000_00L, rows.single().payment.minor)
        assertEquals(0L, rows.single().balance.minor)
    }

    /**
     * A real seven-year loan, walked the way the app walks it.
     *
     * रू 27,00,000 at 8.25% handed over on 3 September 2025 and recovered on
     * the 20th of each month. The payment on 20 September settles the broken
     * period alone; the eighty-four instalments run from 20 October. A
     * रू 4,30,000 lump sum lands on 30 June 2026, taken as "keep the same
     * payment, finish sooner" — nine instalments had fallen by then, and the
     * tenth, on 20 July 2026, came off the reduced balance.
     *
     * The point of pinning it is the *order*: the lump sum meets the balance as
     * it stood on the day it was paid, and the instalments after that day come
     * off the reduced figure. Re-basing at today instead — which is what the app
     * used to do — applies the payment to a balance those instalments had
     * already brought down and counts them twice.
     */
    @Test
    fun `a backdated lump sum re-bases on the day it was paid`() {
        val principal = Money(27_00_000_00)
        val rate = 8.25
        val emi = LoanMath.emi(principal, rate, termMonths = 84)!!
        assertEquals("the instalment a bank would quote", 42_419_86, emi.minor)

        // The stub on 20 September is interest and nothing else, so the loan
        // still owes every rupee of its principal when the schedule begins.
        assertEquals(
            10_984_93,
            LoanMath.brokenPeriodInterest(
                principal, rate,
                LocalDate.of(2025, 9, 3).toEpochDay(),
                LocalDate.of(2025, 9, 20).toEpochDay(),
            ).minor,
        )

        // The stub carried the days to 20 September, so the schedule's own first
        // period is the whole one ending on the first instalment.
        val accrual = Accrual(
            from = LocalDate.of(2025, 9, 20),
            firstPaymentOn = LocalDate.of(2025, 10, 20),
        )
        // 20 Oct 2025 .. 20 Jun 2026, the nine the lump sum arrived behind.
        val billed = LoanMath.outstanding(
            principal, rate, 84, periodsElapsed = 9, emi = emi, accrual = accrual,
        )
        assertEquals(24_78_829_34, billed.minor)

        // Ten days ran between that instalment and the payment, charged on the
        // larger balance. They are carried, not capitalised: the payment comes
        // off the principal in full and the 20 July instalment collects them.
        val paidOn = LocalDate.of(2026, 6, 30)
        val carried = LoanMath.accruedSince(
            billed, rate, from = LocalDate.of(2026, 6, 20), to = paidOn,
        )
        assertEquals(5_602_83, carried.minor)

        val afterLump = Money(billed.minor - 4_30_000_00)
        assertEquals("the balance falls by exactly what was paid", 20_48_829_34, afterLump.minor)

        // The re-based loan runs from the day the money moved to the next
        // instalment, which is twenty days rather than a whole month.
        val rebased = Accrual(
            from = paidOn,
            firstPaymentOn = LocalDate.of(2026, 7, 20),
            carriedInterest = carried,
        )
        val newTerm = LoanMath.tenureAfterPrepayment(afterLump, rate, emi, accrual = rebased)!!
        assertEquals("same instalment, fewer of them", 59, newTerm)

        // The 20 July instalment: twenty days on the new balance plus the ten
        // carried, and the rest off the principal.
        val rows = LoanMath.schedule(
            afterLump, rate, newTerm, emi = emi, accrual = rebased,
        )
        assertEquals(14_864_66, rows[0].interest.minor)
        assertEquals(27_555_20, rows[0].principal.minor)
        assertEquals(20_21_274_14, rows[0].balance.minor)
    }

    @Test
    fun `interest is charged for the days that actually pass`() {
        // February and March are not the same month, and a schedule that charges
        // both a twelfth of a year says they are. रू 12,00,000 at 12%: a twelfth
        // is 12,000 flat, while the 28 days to 28 February are 11,046.58.
        val principal = Money(12_00_000_00)
        val byDays = LoanMath.schedule(
            principal, 12.0, termMonths = 12, emi = Money(1_06_619_00),
            accrual = Accrual(
                from = LocalDate.of(2026, 1, 31),
                firstPaymentOn = LocalDate.of(2026, 2, 28),
            ),
        )
        assertEquals(11_046_58, byDays[0].interest.minor)

        val byTwelfths = LoanMath.schedule(
            principal, 12.0, termMonths = 12, emi = Money(1_06_619_00),
        )
        assertEquals(12_000_00, byTwelfths[0].interest.minor)
    }

    @Test
    fun `a schedule charging days still clears to exactly zero`() {
        val principal = Money(10_00_000_00)
        val rows = LoanMath.schedule(
            principal, 12.0, termMonths = 120,
            accrual = Accrual(
                from = LocalDate.of(2026, 1, 15),
                firstPaymentOn = LocalDate.of(2026, 2, 15),
            ),
        )
        assertEquals(120, rows.size)
        assertEquals("loan must end at exactly zero", 0L, rows.last().balance.minor)
        assertEquals(principal.minor, rows.sumOf { it.principal.minor })
    }

    @Test
    fun `accrual starts where the current balance did, and only when it falls mid-period`() {
        val first = LocalDate.of(2026, 3, 20)
        // Entered long after it began: started_on sits on the first instalment
        // itself, and taking that literally would charge that payment nothing.
        assertEquals(
            LocalDate.of(2026, 2, 20),
            accrualFor(first, first, monthsPerPayment = 1)?.from,
        )
        // Disbursed before the period began — the days between were the stub's.
        assertEquals(
            LocalDate.of(2026, 2, 20),
            accrualFor(LocalDate.of(2026, 1, 3), first, monthsPerPayment = 1)?.from,
        )
        // Re-based partway through: the next instalment charges only the days
        // since, on the reduced balance.
        assertEquals(
            LocalDate.of(2026, 2, 28),
            accrualFor(LocalDate.of(2026, 2, 28), first, monthsPerPayment = 1)?.from,
        )
        assertNull(accrualFor(first, null, monthsPerPayment = 1))
    }

    @Test
    fun `an open-ended debt meters from the day it opened`() {
        // रू 50,000 lent at 12% with no end date, half back after 73 days:
        // 50,000 × 0.12 × 73/365 = 1,200, then 25,000 over the next 73 = 600.
        val changes = listOf(BalanceChange(epochDay = 73, deltaMinor = -25_000_00))
        val opening = LoanMath.openingBalance(Money(25_000_00), changes)
        val accrued = LoanMath.accruedInterest(
            changes = listOf(BalanceChange(epochDay = 0, deltaMinor = opening.minor)) + changes,
            annualRatePercent = 12.0,
            asOfEpochDay = 146,
        )
        assertEquals(50_000_00, opening.minor)
        assertEquals(1_800_00, accrued.minor)
    }
    @Test
    fun `a schedule counted in Nepali months falls on Nepali days`() {
        // 1 Shrawan 2082 is 17 July 2025, and the instalment after it is 1 Bhadra
        // — 17 August, which is 31 days on. Stepped in Gregorian months the same
        // anchor gives 17 August too, so a second step is what tells them apart:
        // 1 Ashwin 2082 is 17 September, while three Gregorian months from
        // 17 July is also 17 October... the months a Nepali year is cut into are
        // simply not the months an English one is, and the periods this file
        // charges interest over have to be the ones the rule produces.
        val start = LocalDate.of(2025, 7, 17)
        val bs = Accrual(from = start, firstPaymentOn = start, inBikramSambat = true)
        val greg = Accrual(from = start, firstPaymentOn = start)

        // Twelve payments of one month each, and the last of them: a Nepali year
        // from 1 Shrawan 2082 is 1 Shrawan 2083, which is 17 July 2026 — a day
        // the Gregorian answer agrees with only by coincidence of the anchor.
        // What differs is everything in between, so the whole run is compared.
        val bsDates = LoanMath.schedule(
            Money(1_00_000_00), 10.0, termMonths = 12, accrual = bs,
        )
        val gregDates = LoanMath.schedule(
            Money(1_00_000_00), 10.0, termMonths = 12, accrual = greg,
        )
        assertEquals(12, bsDates.size)
        // The two schedules charge different interest, because the months they
        // measure are different lengths — which is the whole point of storing
        // which calendar a debt counts in.
        assertTrue(
            "a Nepali year of periods should not bill identically to an English one",
            bsDates.map { it.interest } != gregDates.map { it.interest },
        )
        // And both still clear the debt to exactly zero.
        assertEquals(0L, bsDates.last().balance.minor)
        assertEquals(0L, gregDates.last().balance.minor)
    }

    @Test
    fun `a broken period is measured in the schedule's own months`() {
        // Money received 1 Shrawan 2082 (17 Jul 2025) with the first recovery on
        // 1 Bhadra (17 Aug): one whole Nepali month, so there is no stub. Asked
        // in Gregorian months it is 31 days, which is also a whole month — the
        // two agree here. Move the recovery to 15 Aug and both say "short".
        val disbursed = LocalDate.of(2025, 7, 17)
        assertFalse(
            BrokenPeriod.applies(disbursed, LocalDate.of(2025, 8, 17), 1, null, true),
        )
        assertTrue(
            BrokenPeriod.applies(disbursed, LocalDate.of(2025, 8, 15), 1, null, true),
        )
        // Where they disagree is a longer gap. Three Nepali months from
        // 1 Shrawan is 1 Kartik — 18 October 2025 — while three Gregorian months
        // is 17 October. A first recovery on 17 October is therefore a whole
        // period in English months and a stub in Nepali ones, and a schedule that
        // asked the wrong calendar would either invent an interest charge or miss
        // one.
        assertFalse(
            BrokenPeriod.applies(disbursed, LocalDate.of(2025, 10, 17), 3, null, false),
        )
        assertTrue(
            BrokenPeriod.applies(disbursed, LocalDate.of(2025, 10, 17), 3, null, true),
        )
    }

    /**
     * An instalment can be swiped away, and the money is late rather than gone.
     *
     * The period charges its days and clears nothing, so the balance stands
     * exactly where the payment before it left it — and the next payment asks
     * for two instalments and settles two periods of interest.
     */
    @Test
    fun `a missed instalment is collected by the next payment`() {
        val principal = Money(1_00_000_00)
        val emi = LoanMath.emi(principal, 12.0, 12)!!
        val accrual = Accrual(
            from = LocalDate.of(2026, 1, 1),
            firstPaymentOn = LocalDate.of(2026, 2, 1),
        )
        val paid = LoanMath.schedule(principal, 12.0, 12, emi, accrual = accrual)
        val skipped = LoanMath.schedule(
            principal, 12.0, 12, emi, accrual = accrual, missed = setOf(3),
        )

        // Everything before the missed period is untouched: it happened.
        assertEquals(paid[1].balance.minor, skipped[1].balance.minor)
        // The period itself pays nothing and leaves the balance where it was.
        assertEquals(0L, skipped[2].payment.minor)
        assertEquals(skipped[1].balance.minor, skipped[2].balance.minor)
        // The next one asks for both instalments, and settles both periods'
        // interest — so it costs more in interest than a payment on time would.
        assertEquals(2 * emi.minor, skipped[3].payment.minor)
        assertTrue(skipped[3].interest.minor > paid[3].interest.minor)
        // Two payments' worth of principal, less the extra interest the month
        // the balance sat still cost.
        assertTrue(skipped[3].balance.minor > paid[3].balance.minor)

        // And what the whole loan costs goes up, not down: the balance carried a
        // month longer, which is exactly the point of not forgiving it.
        val before = paid.sumOf { it.interest.minor }
        val after = skipped.sumOf { it.interest.minor }
        assertTrue("a missed month makes the loan cost more", after > before)
    }

    /** A schedule the payments have caught up with is only owed its own rows. */
    @Test
    fun `outstanding counts the periods that ran, not the payments made`() {
        val principal = Money(1_00_000_00)
        val emi = LoanMath.emi(principal, 12.0, 12)!!
        val accrual = Accrual(
            from = LocalDate.of(2026, 1, 1),
            firstPaymentOn = LocalDate.of(2026, 2, 1),
        )
        // Four periods have gone by and the third of them went unpaid. The
        // balance is the fourth row's — the one that collected both — and not
        // the third payment's, which is where counting payments would land.
        val owed = LoanMath.outstanding(
            principal, 12.0, 12, periodsElapsed = 4, emi = emi,
            accrual = accrual, missed = setOf(3),
        )
        val rows = LoanMath.schedule(
            principal, 12.0, 12, emi, accrual = accrual, missed = setOf(3),
        )
        assertEquals(rows[3].balance.minor, owed.minor)
        // Before that catch-up payment lands, three periods have run and the
        // debt is exactly where two payments left it.
        assertEquals(
            rows[1].balance.minor,
            LoanMath.outstanding(
                principal, 12.0, 12, periodsElapsed = 3, emi = emi,
                accrual = accrual, missed = setOf(3),
            ).minor,
        )
    }

}
