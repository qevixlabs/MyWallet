package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.domain.Insurance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * A policy is two figures off a document and a schedule between them.
 *
 * Nothing here derives one figure from the other — an endowment pays back more
 * than the premiums and a term plan pays back nothing, and neither is anything
 * the app could compute. What it does compute is the count and the dates, and
 * both are easy to get wrong by one.
 */
class InsuranceTest {

    private fun terms(
        premium: Long = 21_500_00L,
        maturity: Long = 10_00_000_00L,
        started: String = "2026-04-14",
        months: Int = 240,
        every: Int = 12,
    ) = Insurance.Terms(
        premium = Money(premium),
        maturityAmount = Money(maturity),
        startedOn = LocalDate.parse(started),
        termMonths = months,
        everyMonths = every,
    )

    @Test
    fun `a twenty year policy paid yearly has twenty premiums`() {
        // Twenty, not twenty-one: the first falls on the day it is taken out and
        // the last a year before it matures. An off-by-one here is a whole
        // premium the app either invents or forgets.
        val policy = terms()
        assertEquals(20, policy.payments)
        assertEquals(LocalDate.parse("2046-04-14"), policy.maturesOn)
        assertEquals(LocalDate.parse("2045-04-14"), policy.lastPaymentOn)
        assertEquals(Money(4_30_000_00L), policy.totalPremiums)
    }

    @Test
    fun `the first premium falls on the day the policy starts`() {
        val dates = terms().paymentDates()
        assertEquals(20, dates.size)
        assertEquals(LocalDate.parse("2026-04-14"), dates.first())
        assertEquals(LocalDate.parse("2045-04-14"), dates.last())
    }

    @Test
    fun `a term that does not divide by the gap keeps its last premium`() {
        // Eighteen months paid yearly is two premiums — one on day one and one a
        // year later, with six months of cover left to run. Dividing the two
        // lengths would drop the second and quietly stop paying a policy still
        // in force.
        assertEquals(2, terms(months = 18).payments)
        assertEquals(
            listOf(LocalDate.parse("2026-04-14"), LocalDate.parse("2027-04-14")),
            terms(months = 18).paymentDates(),
        )
    }

    @Test
    fun `a term as long as the gap is one premium`() {
        assertEquals(1, terms(months = 12).payments)
        assertEquals(LocalDate.parse("2026-04-14"), terms(months = 12).lastPaymentOn)
    }

    @Test
    fun `monthly and quarterly count every occurrence`() {
        assertEquals(240, terms(every = 1).payments)
        assertEquals(80, terms(every = 3).payments)
        assertEquals(40, terms(every = 6).payments)
    }

    @Test
    fun `a policy with no term has no schedule at all`() {
        // What a half-filled form produces, and what a restored backup can
        // produce for good. Nothing counted, nothing dated, and no last payment
        // to point a rule at.
        assertEquals(0, terms(months = 0).payments)
        assertNull(terms(months = 0).lastPaymentOn)
        assertEquals(emptyList<LocalDate>(), terms(months = 0).paymentDates())
        assertEquals(Money.ZERO, terms(months = 0).totalPremiums)
    }

    @Test
    fun `what it pays out is never worked out from what it costs`() {
        // The whole reason both figures are asked for. An endowment hands back
        // more than the premiums and a term plan hands back nothing, and the
        // difference is the insurer's business.
        val endowment = terms(premium = 21_500_00L, maturity = 10_00_000_00L)
        assertEquals(Money(4_30_000_00L), endowment.totalPremiums)
        assertEquals(Money(10_00_000_00L), endowment.maturityAmount)
    }

    @Test
    fun `the gain is what comes back beyond every premium put in`() {
        // The one question a policy is bought on, and the only figure on its card
        // that is neither typed in nor printed on the document: ten lakh back
        // against 4,30,000 paid over twenty years.
        assertEquals(Money(5_70_000_00L), terms().gain)
    }

    @Test
    fun `a term plan hands back less than it costs and says so`() {
        // Negative is a real answer here, not a sign of a bad sum. A term plan
        // pays out nothing, and a figure that could only read "more" would
        // describe every policy as an investment.
        val termPlan = terms(premium = 5_000_00L, maturity = 0L, months = 240, every = 12)
        assertEquals(Money(1_00_000_00L), termPlan.totalPremiums)
        assertEquals(Money(-1_00_000_00L), termPlan.gain)
    }
}
