package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import com.mywallet.domain.Loan
import com.mywallet.domain.LoanEntryFact
import com.mywallet.domain.LoanLedger
import com.mywallet.domain.LoanMath
import com.mywallet.domain.LoanMovementKind
import com.mywallet.domain.MovementReversal
import com.mywallet.domain.reversal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The statement a loan produces for itself.
 *
 * The balance column is the whole reason it exists — "you owe रू 9,000" is
 * settled between two people by walking back through the dates that got there —
 * so these check what each row was left at, not merely that the rows appear.
 */
class LoanLedgerTest {

    private val day1 = LocalDate.of(2026, 1, 10)
    private val day2 = LocalDate.of(2026, 2, 20)
    private val day3 = LocalDate.of(2026, 3, 5)

    @Test
    fun `an informal loan is walked back from what is owed today`() {
        // Lent 10,000; 3,000 came back, then 2,000 more went out.
        val loan = loan(principal = Money(9_000_00), outstanding = Money(9_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                repaid(day1, Money(3_000_00)),
                lentMore(day2, Money(2_000_00)),
            ),
        )

        // Newest first, like every other list of what happened in the app.
        assertEquals(listOf(day2, day1), rows.map { it.date })
        assertEquals(LoanMovementKind.INCREASE, rows[0].kind)
        assertEquals(9_000_00, rows[0].balanceAfter!!.minor)
        // 10,000 less the 3,000 that came back — the figure the loan carried
        // before the top-up, which is nowhere on file and has to be walked to.
        assertEquals(7_000_00, rows[1].balanceAfter!!.minor)
    }

    @Test
    fun `interest serviced on its own leaves the balance exactly where it was`() {
        val loan = loan(principal = Money(50_000_00), outstanding = Money(50_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                serviced(day1, Money(500_00)),
                serviced(day2, Money(500_00)),
            ),
        )

        assertEquals(LoanMovementKind.INTEREST, rows[0].kind)
        // Both of them, and the balance the debt started at. Paying interest is
        // what it costs to owe the money, not a payment towards owing less.
        assertEquals(50_000_00, rows[0].balanceAfter!!.minor)
        assertEquals(50_000_00, rows[1].balanceAfter!!.minor)
    }

    @Test
    fun `instalments on a scheduled loan take their balance from the schedule`() {
        val emi = LoanMath.emi(Money(120_000_00), 10.0, 12)!!
        val loan = loan(
            principal = Money(120_000_00),
            outstanding = Money(110_000_00),
            rate = 10.0,
            termMonths = 12,
            emi = emi,
            style = InstalmentStyle.LEVEL_EMI,
            seriesId = "s",
        )
        val schedule = LoanMath.schedule(Money(120_000_00), 10.0, 12, emi)

        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(instalment(day1, emi), instalment(day2, emi)),
        )

        // Not the payment subtracted from the balance: most of an early
        // instalment is interest, and subtracting it would clear the loan years
        // early — the same reason the current figure comes from LoanMath.
        assertEquals(schedule[0].balance.minor, rows[1].balanceAfter!!.minor)
        assertEquals(schedule[1].balance.minor, rows[0].balanceAfter!!.minor)
        assertEquals(schedule[1].principal.minor, rows[0].principalPart!!.minor)
        assertEquals(schedule[1].interest.minor, rows[0].interestPart!!.minor)
    }

    @Test
    fun `instalments paid before the loan was re-based carry no balance`() {
        val emi = LoanMath.emi(Money(120_000_00), 10.0, 12)!!
        val loan = loan(
            principal = Money(120_000_00),
            outstanding = Money(120_000_00),
            rate = 10.0,
            termMonths = 12,
            emi = emi,
            seriesId = "s",
        )
        val rows = LoanLedger.of(
            // The lump sum that re-based it landed on day2, so the day1
            // instalment was paid against a principal that no longer exists.
            loan = loan,
            countingFrom = day2,
            facts = listOf(instalment(day1, emi), instalment(day3, emi)),
        )

        assertNull(rows[1].balanceAfter)
        assertEquals(
            LoanMath.schedule(Money(120_000_00), 10.0, 12, emi)[0].balance.minor,
            rows[0].balanceAfter!!.minor,
        )
    }

    @Test
    fun `a payment in another currency stops the balance rather than guessing`() {
        val loan = loan(principal = Money(9_000_00), outstanding = Money(9_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                repaid(day1, Money(3_000_00)),
                repaid(day2, Money(10_00)).copy(currencyCode = "USD"),
            ),
        )

        // What is owed after the last row is still a fact. What was owed before
        // it would need that day's rate applied to a debt held in another
        // currency, and a guessed rate in this column is worse than a blank.
        assertEquals(9_000_00, rows[0].balanceAfter!!.minor)
        assertNull(rows[1].balanceAfter)
    }

    @Test
    fun `a repayment on money lent out is an instalment, not the debt growing`() {
        val loan = loan(
            principal = Money(5_000_00),
            outstanding = Money(5_000_00),
            direction = LoanDirection.LENT,
            seriesId = "s",
        )
        // Money coming back from someone the user lent to is an adjustment —
        // it is their own money returning, not income — so the adjustment flag
        // alone cannot be what marks a row as the debt growing.
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                fact(day1, Money(1_000_00), isAdjustment = true, fromSeries = true),
            ),
        )

        assertEquals(LoanMovementKind.INSTALMENT, rows[0].kind)
    }

    // ------------------------------------------------------------- fixtures

    private fun fact(
        date: LocalDate,
        amount: Money,
        isAdjustment: Boolean = false,
        part: LoanPart? = null,
        fromSeries: Boolean = false,
    ) = LoanEntryFact(
        entryId = "e-$date-${amount.minor}",
        date = date,
        createdAt = date.toEpochDay(),
        amount = amount,
        currencyCode = "NPR",
        baseAmount = amount,
        isAdjustment = isAdjustment,
        part = part,
        fromSeries = fromSeries,
        accountId = null,
        accountName = null,
        note = null,
    )

    // ---- what removing a row has to do to the debt --------------------------
    //
    // Only some of a debt's history is stored, so only some deletions have
    // anything to put back — and the two lists that ask (the statement, and the
    // delete behind a swipe) have to reach the same verdict about one row.

    @Test
    fun `removing a lump sum puts the money back on the debt`() {
        assertEquals(
            MovementReversal.ADD_BACK,
            kindOf(repaid(day1, Money(1_000_00))).reversal(),
        )
    }

    @Test
    fun `removing more borrowed takes it off again`() {
        assertEquals(
            MovementReversal.TAKE_OFF,
            kindOf(lentMore(day1, Money(2_000_00))).reversal(),
        )
    }

    @Test
    fun `removing the debt arriving forgets the account and leaves the debt`() {
        // The one row that is the borrowing itself. It is an adjustment carrying
        // no part — identical in shape to more being borrowed — so it is known
        // only by its derived id, and taking it off the balance would erase a
        // debt that is still owed.
        assertEquals(
            MovementReversal.FORGET_ACCOUNT,
            LoanLedger.kindOf(
                isOpening = true,
                part = null,
                fromSeries = false,
                isAdjustment = true,
            ).reversal(),
        )
    }

    @Test
    fun `removing interest serviced leaves the balance alone`() {
        // It never moved the balance, which is the point of servicing interest.
        assertEquals(
            MovementReversal.NONE,
            kindOf(serviced(day1, Money(500_00))).reversal(),
        )
    }

    @Test
    fun `removing an instalment needs nothing put back`() {
        // What is owed on a schedule is worked out from how many payments are on
        // file, so one fewer row is already one fewer payment.
        assertEquals(
            MovementReversal.NONE,
            kindOf(instalment(day1, Money(1_200_00))).reversal(),
        )
    }

    @Test
    fun `a repayment on money lent out is an instalment and not the debt growing`() {
        // The trap this pins: money coming back on a loan the user gave is an
        // adjustment *and* comes from the loan's rule. Asking about the
        // adjustment first would file every repayment as more being lent, and
        // deleting one would then add it to what is owed.
        val fact = fact(day1, Money(1_000_00), isAdjustment = true, fromSeries = true)
        assertEquals(LoanMovementKind.INSTALMENT, kindOf(fact))
        assertEquals(MovementReversal.NONE, kindOf(fact).reversal())
    }

    /**
     * A debt whose disbursement row carries no derived id — restored from a file
     * another tool wrote, or imported. The statement has to reach the same
     * verdict about it anyway: it is the Loan, not more borrowed.
     */
    @Test
    fun `the earliest increase on the loan's own start day is the debt arriving`() {
        val loan = loan(principal = Money(10_000_00), outstanding = Money(8_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                lentMore(day1, Money(10_000_00)),
                repaid(day2, Money(2_000_00)),
                lentMore(day3, Money(500_00)),
            ),
        )

        // Newest first: the top-up, the repayment, then the debt itself.
        assertEquals(LoanMovementKind.INCREASE, rows[0].kind)
        assertEquals(LoanMovementKind.PRINCIPAL, rows[1].kind)
        assertEquals(LoanMovementKind.OPENING, rows[2].kind)
    }

    /**
     * And it stops there. A top-up on a debt whose disbursement was never
     * recorded has nothing left distinguishing it from the borrowing, and the
     * app must not invent the difference — the guard is that a debt cannot have
     * been paid before it existed.
     */
    @Test
    fun `an increase with a movement in front of it is not the debt arriving`() {
        val loan = loan(principal = Money(9_000_00), outstanding = Money(9_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(
                repaid(day1, Money(3_000_00)),
                lentMore(day2, Money(2_000_00)),
            ),
        )

        assertEquals(LoanMovementKind.INCREASE, rows[0].kind)
    }

    /** Nor is one borrowed after the day the loan's own figures count from. */
    @Test
    fun `an increase after the loan started is not the debt arriving`() {
        val loan = loan(principal = Money(10_000_00), outstanding = Money(10_000_00))
        val rows = LoanLedger.of(
            loan = loan,
            countingFrom = day1,
            facts = listOf(lentMore(day2, Money(10_000_00))),
        )

        assertEquals(LoanMovementKind.INCREASE, rows[0].kind)
    }

    private fun kindOf(fact: LoanEntryFact): LoanMovementKind = LoanLedger.kindOf(
        isOpening = fact.isOpening,
        part = fact.part,
        fromSeries = fact.fromSeries,
        isAdjustment = fact.isAdjustment,
    )

    /**
     * The entry form asks this to decide whether a row is money *taken from* a
     * debt, and a repayment that answered yes reopened describing the opposite
     * act — "Taken from abc. Borrowed money is not income" over money that had
     * come back the other way.
     */
    @Test
    fun `money handed back on a debt is not a drawdown, whatever its adjustment flag says`() {
        // What a real drawdown looks like: money in, against a facility, an
        // adjustment, and carrying no part.
        assertTrue(
            LoanLedger.isDrawdown(
                loanId = "L3", part = null, isAdjustment = true, isMoneyIn = true,
            )
        )
        // A repayment on money lent out is written exactly the same way but for
        // the part, which is the whole of the difference.
        assertFalse(
            LoanLedger.isDrawdown(
                loanId = "L3", part = LoanPart.PRINCIPAL, isAdjustment = true, isMoneyIn = true,
            )
        )
        assertFalse(
            LoanLedger.isDrawdown(
                loanId = "L3", part = LoanPart.INTEREST, isAdjustment = true, isMoneyIn = true,
            )
        )
        // And the three it always refused: money going the other way, a row
        // that names no debt, and one that is not an adjustment.
        assertFalse(
            LoanLedger.isDrawdown(
                loanId = "L3", part = null, isAdjustment = true, isMoneyIn = false,
            )
        )
        assertFalse(
            LoanLedger.isDrawdown(
                loanId = null, part = null, isAdjustment = true, isMoneyIn = true,
            )
        )
        assertFalse(
            LoanLedger.isDrawdown(
                loanId = "L3", part = null, isAdjustment = false, isMoneyIn = true,
            )
        )
    }

    private fun repaid(date: LocalDate, amount: Money) =
        fact(date, amount, part = LoanPart.PRINCIPAL)

    private fun serviced(date: LocalDate, amount: Money) =
        fact(date, amount, part = LoanPart.INTEREST)

    private fun lentMore(date: LocalDate, amount: Money) =
        fact(date, amount, isAdjustment = true)

    private fun instalment(date: LocalDate, amount: Money) =
        fact(date, amount, fromSeries = true)

    private fun loan(
        principal: Money,
        outstanding: Money,
        rate: Double? = null,
        termMonths: Int? = null,
        emi: Money? = null,
        style: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
        seriesId: String? = null,
        direction: LoanDirection = LoanDirection.LENT,
    ) = Loan(
        id = "l",
        name = "Sita",
        kind = LoanKind.PERSONAL,
        direction = direction,
        style = style,
        lender = null,
        principal = principal,
        outstanding = outstanding,
        currencyCode = "NPR",
        annualRate = rate,
        termMonths = termMonths,
        paymentEveryMonths = 1,
        emi = emi,
        emiStartsOn = null,
        dueOn = null,
        // No schedule dates, so interest falls back to twelfths.
        startedOn = LocalDate.of(2026, 1, 1),
        payFromAccountId = null,
        seriesId = seriesId,
        isClosed = false,
    )
}
