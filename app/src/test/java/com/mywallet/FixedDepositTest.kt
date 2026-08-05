package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.domain.FixedDeposit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A fixed deposit is money the user cannot touch until a known day, and the one
 * holding they have a printed figure from the bank to check the app against. So
 * the app computes what the bank computes: simple interest over the whole term,
 * the arithmetic Everest Bank's own calculator does.
 */
class FixedDepositTest {

    private fun terms(
        principal: Long = 5_00_000_00L,
        rate: Double = 9.0,
        started: String = "2026-01-01",
        months: Int = 12,
    ) = FixedDeposit.Terms(
        principal = Money(principal),
        annualRate = rate,
        startedOn = LocalDate.parse(started),
        termMonths = months,
    )

    /** The reference's own line: `total = principal × rate × years + principal`. */
    private fun reference(principal: Long, rate: Double, years: Double): Long =
        Math.round(principal * rate / 100.0 * years) + principal

    @Test
    fun `the maturity figure is the bank's own`() {
        // रू 5,00,000 at 9% for a year. Nothing compounds, so this is 9% of the
        // deposit and not a paisa more.
        assertEquals(Money(45_000_00), FixedDeposit.totalInterest(terms()))
        assertEquals(Money(5_45_000_00), FixedDeposit.maturityValue(terms()))
    }

    @Test
    fun `it agrees with the reference calculator across the range`() {
        // The whole reason this is simple interest rather than something better:
        // a figure that disagreed with the certificate would read as the bank
        // having short-changed the user.
        listOf(
            Triple(1_00_000_00L, 10.05, 12),
            Triple(5_00_000_00L, 9.0, 3),
            Triple(27_00_000_00L, 8.25, 60),
            Triple(50_000_00L, 7.35, 18),
            Triple(12_34_567_00L, 11.5, 84),
        ).forEach { (principal, rate, months) ->
            val expected = reference(principal, rate, months / 12.0)
            assertEquals(
                "Rs $principal at $rate% for $months months",
                expected,
                FixedDeposit.maturityValue(terms(principal, rate, months = months)).minor,
            )
        }
    }

    @Test
    fun `a longer term earns proportionally more, with nothing compounding`() {
        // Twice the term is exactly twice the interest. If anything compounded
        // it would be more than twice, which is how this test would catch it.
        val oneYear = FixedDeposit.totalInterest(terms(months = 12)).minor
        val twoYears = FixedDeposit.totalInterest(terms(months = 24)).minor
        assertEquals(oneYear * 2, twoYears)
    }

    @Test
    fun `the day it comes free is the day it went in plus the term`() {
        assertEquals(LocalDate.parse("2027-01-01"), terms(months = 12).maturesOn)
        assertEquals(LocalDate.parse("2026-07-01"), terms(months = 6).maturesOn)
        // Stepping in months, not days: a deposit made on the 31st matures on
        // the last day of a short month rather than spilling into the next.
        assertEquals(
            LocalDate.parse("2026-02-28"),
            terms(started = "2026-01-31", months = 1).maturesOn,
        )
    }

    @Test
    fun `an interest-free deposit earns nothing and matures at its face value`() {
        val fd = terms(rate = 0.0)
        assertEquals(Money.ZERO, FixedDeposit.totalInterest(fd))
        assertEquals(Money(5_00_000_00), FixedDeposit.maturityValue(fd))
    }

    @Test
    fun `a deposit holds what was put into it, and the interest lands at the end`() {
        // The whole point of the shape: nothing creeps up day by day. The
        // deposit is the deposit until the day it comes free, and the interest
        // arrives in one piece on that day. A figure climbing towards maturity
        // looked precise and described a savings account instead.
        val fd = terms()
        assertEquals(Money(5_00_000_00), fd.principal)
        assertEquals(Money(45_000_00), FixedDeposit.totalInterest(fd))
        assertEquals(fd.principal + FixedDeposit.totalInterest(fd), FixedDeposit.maturityValue(fd))
    }
}
