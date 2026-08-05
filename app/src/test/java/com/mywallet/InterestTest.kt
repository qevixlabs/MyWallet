package com.mywallet

import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.Money
import com.mywallet.domain.Accrual
import com.mywallet.domain.BalanceDay
import com.mywallet.domain.LoanMath
import com.mywallet.domain.RateChange
import com.mywallet.domain.RateSchedule
import com.mywallet.domain.SavingsInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A bank rate belongs to a period, not to an account. These are the sums that
 * go wrong when it is treated as a single number: a quarter's savings interest
 * earned partly at the old rate, and an instalment whose split moves while the
 * instalment itself does not.
 */
class InterestTest {

    @Test
    fun `the rate in force is the last one to have started`() {
        val rates = RateSchedule(
            base = 8.25,
            changes = listOf(
                RateChange(LocalDate.of(2026, 4, 1), 9.0),
                RateChange(LocalDate.of(2026, 7, 1), 7.5),
            ),
        )
        assertEquals(8.25, rates.on(LocalDate.of(2026, 3, 31)), 0.0)
        // The day it starts is charged at the new rate, not the old one.
        assertEquals(9.0, rates.on(LocalDate.of(2026, 4, 1)), 0.0)
        assertEquals(9.0, rates.on(LocalDate.of(2026, 6, 30)), 0.0)
        assertEquals(7.5, rates.on(LocalDate.of(2026, 7, 1)), 0.0)
    }

    @Test
    fun `interest splits at the change rather than averaging across it`() {
        // रू 10,00,000 over January, the rate doubling on the 11th. The 11th
        // itself is charged at the new rate, so it is 9 days at 10% and 22 at
        // 20%: 2,465.75 + 12,054.79. The mean rate of 15% would say 12,739.73,
        // which is out by nearly two thousand rupees on one month.
        val rates = RateSchedule(
            base = 10.0,
            changes = listOf(RateChange(LocalDate.of(2026, 1, 11), 20.0)),
        )
        val split = rates.interest(
            Money(10_00_000_00), from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 2, 1),
        )
        assertEquals(14_520_55, split.minor)

        val flat = RateSchedule(base = 15.0).interest(
            Money(10_00_000_00), from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 2, 1),
        )
        assertEquals(12_739_73, flat.minor)
    }

    @Test
    fun `a fixed rate is left alone`() {
        val rates = RateSchedule(base = 8.25)
        assertTrue(rates.isFixed)
        // 365 days at 8.25% on 1,00,000 is 8,250 — the whole rate, no rounding.
        assertEquals(
            8_250_00,
            rates.interest(
                Money(1_00_000_00),
                from = LocalDate.of(2026, 1, 1),
                to = LocalDate.of(2027, 1, 1),
            ).minor,
        )
    }

    @Test
    fun `interest is paid at the start of every fourth Nepali month`() {
        val days = SavingsInterest.payoutsBetween(
            after = LocalDate.of(2026, 1, 1),
            through = LocalDate.of(2027, 1, 1),
        )
        assertEquals("four payouts in a year", 4, days.size)
        days.forEach { day ->
            val bs = BikramSambat.fromGregorian(day)
            assertEquals("$day should be the 1st of a Nepali month", 1, bs.day)
            assertTrue(
                "$day falls in Nepali month ${bs.month}, which is not a quarter start",
                bs.month in listOf(1, 4, 7, 10),
            )
        }
        // 1 Shrawan 2083 is 17 July 2026 — the day the app's own month strip
        // rolls over, so the two cannot disagree about when a quarter closes.
        assertTrue(LocalDate.of(2026, 7, 17) in days)
    }

    @Test
    fun `in the English calendar the year is cut into English months`() {
        // The same rule said in the reader's own months. Four months at a time
        // is three payouts a year: 1 January, 1 May, 1 September — the 1st of the
        // 1st, 5th and 9th months, counted from the start of the year exactly as
        // Baisakh, Shrawan and Kartik are counted from the start of the Nepali
        // one.
        assertEquals(listOf(1, 5, 9), SavingsInterest.payoutMonths(4))
        val days = SavingsInterest.payoutsBetween(
            after = LocalDate.of(2026, 1, 1),
            through = LocalDate.of(2027, 1, 1),
            everyMonths = 4,
            calendar = CalendarSystem.GREGORIAN,
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 1),
            ),
            days,
        )
        // And each covers the four months behind it: the credit landing on
        // 1 May pays for January through April.
        days.forEach { payout ->
            assertEquals(
                "$payout should pay for four months",
                4,
                SavingsInterest.periodMonths(payout, 4, CalendarSystem.GREGORIAN),
            )
        }
        assertEquals(
            LocalDate.of(2026, 1, 1),
            SavingsInterest.periodStart(
                LocalDate.of(2026, 5, 1), 4, CalendarSystem.GREGORIAN,
            ),
        )
        // The last period of the year runs into the next one, which is where the
        // year restarts rather than the periods drifting on.
        assertEquals(
            LocalDate.of(2026, 9, 1),
            SavingsInterest.periodStart(
                LocalDate.of(2027, 1, 1), 4, CalendarSystem.GREGORIAN,
            ),
        )
    }

    @Test
    fun `an English year of periods comes to the annual rate exactly`() {
        // The same guarantee the Nepali side has: however the year is cut, the
        // pieces add up to one year's interest and no more. The same balance and
        // rate as the Nepali case, so the two can be read against each other —
        // and so twelve monthly slices divide without leaving paisa behind.
        val rates = RateSchedule(base = 9.0)
        listOf(1, 2, 3, 4, 6, 12).forEach { gap ->
            val payouts = SavingsInterest.payoutsBetween(
                after = LocalDate.of(2025, 12, 31),
                through = LocalDate.of(2026, 12, 31),
                everyMonths = gap,
                calendar = CalendarSystem.GREGORIAN,
            )
            val total = payouts.sumOf { payout ->
                SavingsInterest.earned(
                    opening = Money(10_00_000_00),
                    changes = emptyList(),
                    rates = rates,
                    periodStart = SavingsInterest.periodStart(
                        payout, gap, CalendarSystem.GREGORIAN,
                    )!!,
                    payout = payout,
                    monthsInPeriod = SavingsInterest.periodMonths(
                        payout, gap, CalendarSystem.GREGORIAN,
                    )!!,
                ).minor
            }
            assertEquals("every $gap months should come to 9% of the year", 90_000_00, total)
        }
    }

    @Test
    fun `reading a Nepali patro does not move a bank's quarters on its own`() {
        // The rule the whole opt-in exists for. A bank closing its quarters on
        // 1 January does not start closing them on 1 Baisakh because its
        // customer prefers a Nepali calendar — and most Nepali banks work in
        // English months whatever their passbook is printed in. So the display
        // setting alone decides nothing.
        assertEquals(
            CalendarSystem.GREGORIAN,
            CalendarSystem.forInterest(optedIn = false, setting = CalendarSystem.BIKRAM_SAMBAT),
        )
        // Nor does the opt-in on its own: somebody who has said their bank counts
        // in Nepali months but is reading International dates is shown
        // International periods, so the page and the passbook cannot disagree
        // about which day a quarter closes.
        assertEquals(
            CalendarSystem.GREGORIAN,
            CalendarSystem.forInterest(optedIn = true, setting = CalendarSystem.GREGORIAN),
        )
        assertEquals(
            CalendarSystem.GREGORIAN,
            CalendarSystem.forInterest(optedIn = false, setting = CalendarSystem.GREGORIAN),
        )
        // Both together, and only both.
        assertEquals(
            CalendarSystem.BIKRAM_SAMBAT,
            CalendarSystem.forInterest(optedIn = true, setting = CalendarSystem.BIKRAM_SAMBAT),
        )
    }

    @Test
    fun `a day already paid is not paid again`() {
        val payout = LocalDate.of(2026, 7, 17)
        val again = SavingsInterest.payoutsBetween(after = payout, through = payout)
        assertTrue("the watermark day itself must not come back", again.isEmpty())
    }

    @Test
    fun `the interval decides how many payouts a year has`() {
        val year = LocalDate.of(2026, 1, 1) to LocalDate.of(2027, 1, 1)
        // Every gap that divides twelve gives that many payouts, all of them on
        // the 1st of a Nepali month counted from Baisakh.
        listOf(1 to 12, 2 to 6, 3 to 4, 4 to 3, 6 to 2, 12 to 1).forEach { (gap, count) ->
            val days = SavingsInterest.payoutsBetween(year.first, year.second, gap)
            assertEquals("every $gap months should pay $count times a year", count, days.size)
            days.forEach { day ->
                assertEquals("$day should be the 1st", 1, BikramSambat.fromGregorian(day).day)
            }
        }
    }

    @Test
    fun `a gap that does not divide the year leaves a short period at the end of it`() {
        // Five months: Baisakh, Bhadra, Magh — and then the year restarts rather
        // than the periods drifting across the calendar for ever. So the last
        // period of the year is two months and pays two twelfths, not five.
        assertEquals(listOf(1, 6, 11), SavingsInterest.payoutMonths(5))
        val baisakh = SavingsInterest.payoutsBetween(
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1), everyMonths = 5,
        ).single()
        assertEquals(1, BikramSambat.fromGregorian(baisakh).month)
        assertEquals(2, SavingsInterest.periodMonths(baisakh, 5))
        // And the one before it is a whole five.
        val magh = SavingsInterest.periodStart(baisakh, 5)!!
        assertEquals(11, BikramSambat.fromGregorian(magh).month)
        assertEquals(5, SavingsInterest.periodMonths(magh, 5))
    }

    @Test
    fun `a period pays its own share of the year's rate on money held throughout`() {
        // 9% a year is 2.25% a quarter — not 9% × 92 ÷ 365, which would be
        // 2.2685% and pay रू 18.50 too much on this balance.
        val start = LocalDate.of(2026, 4, 14)
        val payout = LocalDate.of(2026, 7, 17)
        val earned = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = emptyList(),
            rates = RateSchedule(base = 9.0),
            periodStart = start,
            payout = payout,
        )
        assertEquals(22_500_00, earned.minor)

        // The same three months paid half-yearly are half of the year's rate,
        // and paid monthly a twelfth — the share follows the period, and the
        // days it happens to contain have nothing to say about it.
        assertEquals(
            45_000_00,
            SavingsInterest.earned(
                opening = Money(10_00_000_00),
                changes = emptyList(),
                rates = RateSchedule(base = 9.0),
                periodStart = start,
                payout = payout,
                monthsInPeriod = 6,
            ).minor,
        )
        assertEquals(
            7_500_00,
            SavingsInterest.earned(
                opening = Money(10_00_000_00),
                changes = emptyList(),
                rates = RateSchedule(base = 9.0),
                periodStart = start,
                payout = payout,
                monthsInPeriod = 1,
            ).minor,
        )
    }

    @Test
    fun `a year of periods pays the annual rate however it is cut up`() {
        // The property that makes the slice-of-the-year convention right: four
        // quarters, twelve months and two halves all come to 9% on a balance
        // held throughout. A day-count convention would not, and neither would
        // one that paid the same slice for a short trailing period.
        listOf(1, 2, 3, 4, 6, 12).forEach { gap ->
            val payouts = SavingsInterest.payoutsBetween(
                LocalDate.of(2026, 4, 13), LocalDate.of(2027, 4, 13), gap,
            )
            val total = payouts.sumOf { payout ->
                SavingsInterest.earned(
                    opening = Money(10_00_000_00),
                    changes = emptyList(),
                    rates = RateSchedule(base = 9.0),
                    periodStart = SavingsInterest.periodStart(payout, gap)!!,
                    payout = payout,
                    monthsInPeriod = SavingsInterest.periodMonths(payout, gap)!!,
                ).minor
            }
            assertEquals("every $gap months should still come to 9% a year", 90_000_00, total)
        }
    }

    @Test
    fun `money that arrives halfway earns half the quarter`() {
        // The quarter is 94 days. रू 10,00,000 throughout earns 2.25%; another
        // रू 10,00,000 arriving with 47 days to go earns half of that.
        val start = LocalDate.of(2026, 4, 14)
        val payout = LocalDate.of(2026, 7, 17)
        val earned = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = listOf(BalanceDay(start.plusDays(47), 10_00_000_00)),
            rates = RateSchedule(base = 9.0),
            periodStart = start,
            payout = payout,
        )
        assertEquals(22_500_00 + 11_250_00, earned.minor)
    }

    @Test
    fun `the day before the payout earns, and the payout day does not`() {
        // The payout opens the next quarter, so money withdrawn on it was there
        // for the whole of this one.
        val start = LocalDate.of(2026, 4, 14)
        val payout = LocalDate.of(2026, 7, 17)
        val whole = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = listOf(BalanceDay(payout, -10_00_000_00)),
            rates = RateSchedule(base = 9.0),
            periodStart = start, payout = payout,
        )
        assertEquals(22_500_00, whole.minor)

        // Taken out the day before, and that last day earns nothing.
        val short = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = listOf(BalanceDay(payout.minusDays(1), -10_00_000_00)),
            rates = RateSchedule(base = 9.0),
            periodStart = start, payout = payout,
        )
        assertTrue(short.minor < whole.minor)
    }

    @Test
    fun `a rate that moves mid-quarter is honoured for the days it applied`() {
        val start = LocalDate.of(2026, 4, 14)
        val payout = LocalDate.of(2026, 7, 17)
        val earned = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = emptyList(),
            rates = RateSchedule(
                base = 9.0,
                changes = listOf(RateChange(start.plusDays(47), 5.0)),
            ),
            periodStart = start,
            payout = payout,
        )
        // Half the quarter at 2.25% and half at 1.25%.
        assertEquals(11_250_00 + 6_250_00, earned.minor)
    }

    @Test
    fun `the opening day's own movements belong in the opening balance`() {
        // The contract, and the trap behind it: a movement dated on the first
        // day is *not* a movement here — the caller must already have it in
        // [opening], because it was there for every day of the quarter. Last
        // quarter's interest is credited on this quarter's first day, so this is
        // the commonest deposit there is, and it fell between the two.
        val start = LocalDate.of(2026, 4, 14)
        val payout = LocalDate.of(2026, 7, 17)
        val ignored = SavingsInterest.earned(
            opening = Money(10_00_000_00),
            changes = listOf(BalanceDay(start, 10_00_000_00)),
            rates = RateSchedule(base = 9.0),
            periodStart = start, payout = payout,
        )
        assertEquals("a first-day movement is not double counted", 22_500_00, ignored.minor)

        val folded = SavingsInterest.earned(
            opening = Money(20_00_000_00),
            changes = emptyList(),
            rates = RateSchedule(base = 9.0),
            periodStart = start, payout = payout,
        )
        assertEquals("and earns the whole quarter once folded in", 45_000_00, folded.minor)
    }

    @Test
    fun `an overdrawn day earns nothing rather than costing something`() {
        val earned = SavingsInterest.earned(
            opening = Money(-5_000_00),
            changes = emptyList(),
            rates = RateSchedule(base = 12.0),
            periodStart = LocalDate.of(2026, 4, 14),
            payout = LocalDate.of(2026, 7, 17),
        )
        assertEquals(0L, earned.minor)
    }

    @Test
    fun `a quarter opens three Nepali months before it pays`() {
        val payout = LocalDate.of(2026, 7, 17)          // 1 Shrawan 2083
        val start = SavingsInterest.periodStart(payout)!!
        val bs = BikramSambat.fromGregorian(start)
        assertEquals("the quarter opens on the 1st", 1, bs.day)
        assertEquals("of Baisakh", 1, bs.month)
        // And it is itself a payout day: quarters butt up against each other
        // with no day belonging to both or to neither.
        assertTrue(start in SavingsInterest.payoutsBetween(start.minusDays(1), payout))
    }

    @Test
    fun `a rate rise moves the split, never the instalment`() {
        // The seven-year loan, with the bank moving 8.25% to 10% on 20 Jan 2026 —
        // partway through the fourth instalment's period.
        val principal = Money(27_00_000_00)
        val emi = LoanMath.emi(principal, 8.25, termMonths = 84)!!
        val dates = Accrual(
            from = LocalDate.of(2025, 9, 20),
            firstPaymentOn = LocalDate.of(2025, 10, 20),
        )
        val fixed = LoanMath.schedule(principal, 8.25, 84, emi = emi, accrual = dates)
        val floating = LoanMath.schedule(
            principal, 8.25, 84, emi = emi,
            accrual = dates.copy(
                rates = RateSchedule(
                    base = 8.25,
                    changes = listOf(RateChange(LocalDate.of(2026, 1, 20), 10.0)),
                ),
            ),
        )

        // Up to the change the two schedules are the same loan.
        assertEquals(fixed[2].balance.minor, floating[2].balance.minor)

        // From it, every instalment is the same size and buys less of the debt.
        val n = 3
        assertEquals("the borrower still pays the same", emi.minor, floating[n].payment.minor)
        assertEquals(emi.minor, fixed[n].payment.minor)
        assertTrue(
            "a higher rate takes more of the payment as interest",
            floating[n].interest.minor > fixed[n].interest.minor,
        )
        assertTrue(
            "and leaves less to clear the balance",
            floating[n].principal.minor < fixed[n].principal.minor,
        )
        assertEquals(
            "payment is still interest plus principal",
            floating[n].payment.minor,
            floating[n].interest.minor + floating[n].principal.minor,
        )

        // Which is what makes the loan run longer at the same instalment.
        val longer = LoanMath.tenureAfterPrepayment(
            outstanding = principal, annualRatePercent = 8.25, emi = emi,
            accrual = dates.copy(
                rates = RateSchedule(
                    base = 8.25,
                    changes = listOf(RateChange(LocalDate.of(2026, 1, 20), 10.0)),
                ),
            ),
        )!!
        assertTrue("a dearer loan takes longer to clear, got $longer", longer > 84)
    }
}
