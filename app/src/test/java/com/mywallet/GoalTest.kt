package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.domain.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A goal names one figure and the app divides it up.
 *
 * The division is the whole of the arithmetic, and the direction it rounds is
 * the whole of the decision: a plan that reaches the goal is worth a few paisa
 * over, and one that stops short is worth nothing at all.
 */
class GoalTest {

    private fun terms(
        target: Long = 1_50_000_00L,
        started: String = "2026-07-29",
        months: Int = 18,
        every: Int = 1,
    ) = Goal.Terms(
        target = Money(target),
        startedOn = LocalDate.parse(started),
        termMonths = months,
        everyMonths = every,
    )

    @Test
    fun `what to put aside is the goal divided by the payments`() {
        // रू 1,50,000 over eighteen monthly contributions.
        val goal = terms()
        assertEquals(18, goal.payments)
        assertEquals(Money(8_333_34L), goal.perPayment)
        assertEquals(LocalDate.parse("2028-01-29"), goal.targetOn)
    }

    @Test
    fun `it rounds up, so the plan reaches the goal rather than stopping short`() {
        // Twelve times रू 8,333.33 is रू 99,999.96 — four paisa short of the
        // thing being saved for. Rounding up overshoots by eight instead, which
        // is the direction that cannot disappoint.
        val goal = terms(target = 1_00_000_00L, months = 12)
        assertEquals(Money(8_333_34L), goal.perPayment)
        assertTrue("the plan must reach the goal", goal.total.minor >= 1_00_000_00L)
        assertEquals(Money(1_00_000_08L), goal.total)
    }

    @Test
    fun `a goal that divides cleanly comes to exactly itself`() {
        val goal = terms(target = 1_20_000_00L, months = 12)
        assertEquals(Money(10_000_00L), goal.perPayment)
        assertEquals(Money(1_20_000_00L), goal.total)
    }

    @Test
    fun `the rhythm decides how many payments there are`() {
        assertEquals(6, terms(months = 18, every = 3).payments)
        assertEquals(2, terms(months = 18, every = 12).payments)
        assertEquals(Money(75_000_00L), terms(months = 18, every = 12).perPayment)
    }

    @Test
    fun `the first contribution is on the day the goal starts`() {
        val dates = terms(months = 3).paymentDates()
        assertEquals(
            listOf(
                LocalDate.parse("2026-07-29"),
                LocalDate.parse("2026-08-29"),
                LocalDate.parse("2026-09-29"),
            ),
            dates,
        )
        assertEquals(LocalDate.parse("2026-09-29"), terms(months = 3).lastPaymentOn)
        // And the goal is due one whole gap after the last one goes in.
        assertEquals(LocalDate.parse("2026-10-29"), terms(months = 3).targetOn)
    }

    @Test
    fun `progress is what is in it against what it is for`() {
        val target = Money(1_00_000_00L)
        assertEquals(0f, Goal.progress(Money.ZERO, target))
        assertEquals(0.25f, Goal.progress(Money(25_000_00L), target))
        assertEquals(1f, Goal.progress(target, target))
        // Past the target is the goal reached, not 130% of a bar.
        assertEquals(1f, Goal.progress(Money(1_30_000_00L), target))
        // And a goal with nothing to reach cannot be part of the way there.
        assertEquals(0f, Goal.progress(Money(500_00L), Money.ZERO))
    }

    @Test
    fun `what is left never goes below nothing`() {
        val target = Money(1_00_000_00L)
        assertEquals(Money(75_000_00L), Goal.remaining(Money(25_000_00L), target))
        assertEquals(Money.ZERO, Goal.remaining(target, target))
        // Saving past the goal leaves nothing to go, not a negative amount.
        assertEquals(Money.ZERO, Goal.remaining(Money(1_30_000_00L), target))
    }

    @Test
    fun `putting money in early brings the goal forward`() {
        // रू 5,00,000 over a year, monthly: रू 41,666.67 a time, one paid. A
        // lakh dropped in leaves रू 3,58,333.33 to find, which is nine more
        // contributions rather than eleven — so the term falls from twelve
        // months to ten and the goal arrives two months sooner. That is the
        // whole reason anyone deposits early, and the contribution itself must
        // not move: it is what the user said they can manage.
        val target = Money(5_00_000_00L)
        val perPayment = Money(41_666_67L)
        assertEquals(
            10,
            Goal.termAfter(
                saved = Money(1_41_666_67L),
                target = target,
                perPayment = perPayment,
                everyMonths = 1,
                paymentsDone = 1,
            ),
        )
    }

    @Test
    fun `taking money back out pushes it away`() {
        val target = Money(5_00_000_00L)
        val perPayment = Money(41_666_67L)
        val before = Goal.termAfter(Money(83_333_34L), target, perPayment, 1, 2)
        val after = Goal.termAfter(Money(41_666_67L), target, perPayment, 1, 2)
        assertEquals(12, before)
        assertEquals(13, after)
    }

    @Test
    fun `a deposit that finishes the goal ends the plan there`() {
        // Nothing left to save, so nothing more is scheduled: the term stops at
        // the contributions that were actually made.
        assertEquals(
            2,
            Goal.termAfter(
                saved = Money(1_00_000_00L),
                target = Money(1_00_000_00L),
                perPayment = Money(10_000_00L),
                everyMonths = 1,
                paymentsDone = 2,
            ),
        )
        // And never nothing at all, whatever the arithmetic says: a rule with a
        // term of zero months has nowhere to stop.
        assertEquals(
            1,
            Goal.termAfter(Money(1_00_000_00L), Money(1_00_000_00L), Money(10_000_00L), 1, 0),
        )
    }

    @Test
    fun `a chosen saving picks the rhythm that reaches the same goal`() {
        // The other half of the pair the form offers. रू 1,20,000 in a year at
        // रू 30,000 a time is four payments, which is one every three months.
        val target = Money(1_20_000_00L)
        assertEquals(3, Goal.everyMonthsFor(target, Money(30_000_00L), 12))
        assertEquals(6, Goal.everyMonthsFor(target, Money(60_000_00L), 12))
        assertEquals(1, Goal.everyMonthsFor(target, Money(10_000_00L), 12))
        // More than the goal in one go is one payment, and the whole term is the
        // gap: there is nothing after it.
        assertEquals(12, Goal.everyMonthsFor(target, Money(2_00_000_00L), 12))
        // And a figure so small it would need more payments than there are
        // months snaps to the tightest rhythm there is.
        assertEquals(1, Goal.everyMonthsFor(target, Money(100_00L), 12))
    }

    @Test
    fun `the pair agrees with itself in both directions`() {
        // Whichever side the user answers, the other must produce a plan that
        // still reaches the goal — the two boxes are one answer seen twice.
        val target = Money(1_20_000_00L)
        listOf(10_000_00L, 20_000_00L, 30_000_00L, 60_000_00L).forEach { typed ->
            val every = Goal.everyMonthsFor(target, Money(typed), 12)
            val terms = terms(target = target.minor, months = 12, every = every)
            assertTrue(
                "saving $typed every $every months must still reach the goal",
                terms.total.minor >= target.minor,
            )
        }
    }

    @Test
    fun `a goal with no term has no plan at all`() {
        // What a half-filled form produces. Nothing counted, nothing to put
        // aside, and no last payment to point a rule at.
        val empty = terms(months = 0)
        assertEquals(0, empty.payments)
        assertEquals(Money.ZERO, empty.perPayment)
        assertEquals(Money.ZERO, empty.total)
        assertEquals(emptyList<LocalDate>(), empty.paymentDates())
    }
}
