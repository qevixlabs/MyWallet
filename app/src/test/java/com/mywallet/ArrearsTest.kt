package com.mywallet

import com.mywallet.domain.Arrears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Which instalments a schedule was owed and never got.
 *
 * The rule is read off the rows rather than remembered anywhere: a scheduled
 * date with no surviving payment on it is a period that went unpaid. What the
 * count and the days each decide is the point — see [Arrears.of].
 */
class ArrearsTest {

    private fun dates(count: Int): List<LocalDate> =
        (0 until count).map { LocalDate.of(2026, 1, 1).plusMonths(it.toLong()) }

    private fun days(vararg months: Int): Set<Long> =
        months.map { LocalDate.of(2026, it, 1).toEpochDay() }.toSet()

    @Test
    fun `a schedule that has been paid owes nothing`() {
        val due = dates(6)
        val arrears = Arrears.of(due, days(1, 2, 3, 4, 5, 6), paidCount = 6)
        assertTrue(arrears.isEmpty)
        assertEquals(0, arrears.carriedForward)
        assertEquals(6, arrears.periodsDue)
    }

    @Test
    fun `one instalment deleted is owed by the next payment`() {
        // July's row is gone; nothing has been paid since.
        val arrears = Arrears.of(dates(7), days(1, 2, 3, 4, 5, 6), paidCount = 6)
        assertEquals(setOf(7), arrears.missed)
        assertEquals(1, arrears.carriedForward)
        assertEquals(7, arrears.periodsDue)
    }

    @Test
    fun `a second one deleted asks for three at once`() {
        val arrears = Arrears.of(dates(7), days(1, 2, 3, 4, 5), paidCount = 5)
        assertEquals(setOf(6, 7), arrears.missed)
        assertEquals(2, arrears.carriedForward)
    }

    /**
     * Once the catch-up payment has landed, nothing is owed — but the period
     * that went unpaid is still a period that went unpaid, and stays so for as
     * long as the loan runs. Its interest was never collected on its own day,
     * and a balance recomputed as though it had never happened would be light.
     */
    @Test
    fun `a period stays missed after the arrears are caught up`() {
        // Six paid, July deleted, August paid — as one payment of double.
        val arrears = Arrears.of(dates(8), days(1, 2, 3, 4, 5, 6, 8), paidCount = 7)
        assertEquals(setOf(7), arrears.missed)
        assertEquals("August paid, so nothing is carried on", 0, arrears.carriedForward)
        assertEquals(8, arrears.periodsDue)
    }

    /**
     * The count decides how many, the days decide which. They disagree whenever
     * an instalment's date was corrected by a day or two: it then sits on no
     * scheduled date at all, and trusting the days alone would tell a borrower
     * who paid two days late that they owed double next month.
     */
    @Test
    fun `a payment made off its own date settles the period anyway`() {
        val paidOffDate = days(1, 2, 3, 4, 5)
            .toMutableSet()
            .apply { add(LocalDate.of(2026, 6, 3).toEpochDay()) }
        val arrears = Arrears.of(dates(6), paidOffDate, paidCount = 6)
        assertTrue(arrears.isEmpty)
    }
}
