package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.domain.BankHolding
import com.mywallet.domain.HoldingChoice
import com.mywallet.domain.HoldingGroup
import com.mywallet.domain.Loan
import com.mywallet.domain.LoanMath
import com.mywallet.domain.PersonHolding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The single "what is it?" question decides which table the thing lands in.
 * Getting that mapping wrong would file a loan as an account you could spend
 * from, so it is worth pinning down.
 */
class HoldingChoiceTest {

    @Test
    fun `bank savings and current are accounts, not debts`() {
        val savings = HoldingChoice(HoldingGroup.BANK, BankHolding.SAVINGS)
        val current = HoldingChoice(HoldingGroup.BANK, BankHolding.CURRENT)

        assertFalse(savings.isLoan)
        assertFalse(current.isLoan)
        assertEquals(AccountKind.SAVINGS, savings.accountKind)
        assertEquals(AccountKind.CURRENT, current.accountKind)
        assertNull(savings.loanKind)
        assertNull(current.loanKind)
    }

    @Test
    fun `a bank loan and money from a person are both debts`() {
        val bankLoan = HoldingChoice(HoldingGroup.BANK, BankHolding.LOAN)
        val fromPerson = HoldingChoice(HoldingGroup.PERSON)

        assertTrue(bankLoan.isLoan)
        assertTrue(fromPerson.isLoan)
        assertEquals(LoanKind.BANK, bankLoan.loanKind)
        assertEquals(LoanKind.PERSONAL, fromPerson.loanKind)
        assertNull("a loan is not somewhere you spend from", bankLoan.accountKind)
        assertNull(fromPerson.accountKind)
    }

    @Test
    fun `wallet and cash ignore whatever the bank sub-choice happens to be`() {
        // The sub-choice is remembered while another group is selected, so it can
        // still say LOAN. That must not turn cash into a debt.
        val cash = HoldingChoice(HoldingGroup.CASH, BankHolding.LOAN)
        val wallet = HoldingChoice(HoldingGroup.WALLET, BankHolding.LOAN)

        assertFalse(cash.isLoan)
        assertFalse(wallet.isLoan)
        assertEquals(AccountKind.CASH, cash.accountKind)
        assertEquals(AccountKind.WALLET, wallet.accountKind)
    }

    @Test
    fun `every stored kind maps back to the answer that produced it`() {
        AccountKind.entries.forEach { kind ->
            assertEquals("$kind must round-trip", kind, HoldingChoice.of(kind).accountKind)
        }
        LoanKind.entries.forEach { kind ->
            LoanDirection.entries.forEach { direction ->
                val choice = HoldingChoice.of(kind, direction)
                assertEquals("$kind must round-trip", kind, choice.loanKind)
                // A bank never borrows from the user, so that pairing collapses to
                // borrowed rather than being stored as something impossible.
                val expected = if (kind == LoanKind.PERSONAL) direction else LoanDirection.BORROWED
                assertEquals("$kind $direction must round-trip", expected, choice.loanDirection)
            }
        }
    }

    @Test
    fun `an existing row cannot be moved between the two tables`() {
        val account = HoldingChoice.of(AccountKind.SAVINGS)
        val loan = HoldingChoice.of(LoanKind.BANK, LoanDirection.BORROWED)

        // Editing an account never offers "from a person", and its bank options
        // never include a loan: the balance would have nowhere to go.
        assertFalse(HoldingGroup.PERSON in HoldingChoice.groupsFor(account))
        assertFalse(BankHolding.LOAN in HoldingChoice.bankHoldingsFor(account))
        // Nor does it offer a current account, which is no longer a thing the
        // app asks about.
        assertEquals(listOf(BankHolding.SAVINGS), HoldingChoice.bankHoldingsFor(account))

        // The editor does not offer the kind at all once a row exists — it is
        // shown as a statement of fact. These lists are the backstop underneath
        // that: even if something did offer a change, it could never move a row
        // between the two tables.
        assertEquals(
            listOf(HoldingGroup.BANK, HoldingGroup.PERSON),
            HoldingChoice.groupsFor(loan),
        )
        assertEquals(
            listOf(BankHolding.LOAN, BankHolding.OVERDRAFT),
            HoldingChoice.bankHoldingsFor(loan),
        )

        // Adding something new is the only time every answer is available.
        assertEquals(HoldingGroup.entries, HoldingChoice.groupsFor(null))
        assertEquals(PersonHolding.entries, HoldingChoice.personHoldingsFor(null))
    }

    @Test
    fun `a current account is only offered to one that already is one`() {
        // Dropped from the form — MyWallet keeps a record of money, not a copy of
        // a bank's product list — but an account entered as current must not
        // silently become savings the next time it is opened.
        assertEquals(
            listOf(
                BankHolding.SAVINGS,
                BankHolding.FIXED_DEPOSIT,
                BankHolding.LOAN,
                BankHolding.OVERDRAFT,
            ),
            HoldingChoice.bankHoldingsFor(null),
        )
        assertEquals(
            listOf(BankHolding.SAVINGS, BankHolding.CURRENT),
            HoldingChoice.bankHoldingsFor(HoldingChoice.of(AccountKind.CURRENT)),
        )
    }

    @Test
    fun `a fixed deposit cannot be relabelled into anything else`() {
        // An account can move between savings and current, and a debt between
        // bank and person, because both are only namings. A deposit is not: its
        // balance moves by a rule rather than by entries, nothing can be spent
        // from it, and it has a day on which the whole of it leaves. Calling it
        // savings afterwards would leave a maturity pointing at nothing.
        val deposit = HoldingChoice(HoldingGroup.BANK, BankHolding.FIXED_DEPOSIT)

        assertTrue(deposit.isFixedDeposit)
        assertTrue(!deposit.isLoan)
        assertEquals(AccountKind.FIXED_DEPOSIT, deposit.accountKind)
        assertEquals(listOf(HoldingGroup.BANK), HoldingChoice.groupsFor(deposit))
        assertEquals(listOf(BankHolding.FIXED_DEPOSIT), HoldingChoice.bankHoldingsFor(deposit))
        // And reopening one comes back as itself.
        assertEquals(deposit, HoldingChoice.of(AccountKind.FIXED_DEPOSIT))
    }

    @Test
    fun `a policy is its own kind and stays one`() {
        // It has a schedule of premiums behind it and a day it pays out, so
        // calling it a wallet afterwards would leave both pointing at a holding
        // that has neither. It is not at a bank either: nothing about it is one
        // of several things under one name.
        val policy = HoldingChoice(HoldingGroup.INSURANCE)

        assertTrue(!policy.isLoan)
        assertTrue(policy.isInsurance)
        assertEquals(AccountKind.INSURANCE, policy.accountKind)
        assertEquals(listOf(HoldingGroup.INSURANCE), HoldingChoice.groupsFor(policy))
        // And reopening one comes back as itself.
        assertEquals(policy, HoldingChoice.of(AccountKind.INSURANCE))
    }

    @Test
    fun `a goal is its own kind and stays one`() {
        // A plan behind it and a day it is meant to be reached, neither of which
        // survives being called a wallet.
        val goal = HoldingChoice(HoldingGroup.GOAL)

        assertTrue(!goal.isLoan)
        assertTrue(goal.isGoal)
        assertEquals(AccountKind.GOAL, goal.accountKind)
        assertEquals(listOf(HoldingGroup.GOAL), HoldingChoice.groupsFor(goal))
        assertEquals(goal, HoldingChoice.of(AccountKind.GOAL))
    }

    @Test
    fun `an overdraft is a debt of the bank's, not an account to spend from`() {
        val overdraft = HoldingChoice(HoldingGroup.BANK, BankHolding.OVERDRAFT)

        assertTrue(overdraft.isLoan)
        assertEquals(LoanKind.OVERDRAFT, overdraft.loanKind)
        assertNull("an overdraft has no balance to spend down", overdraft.accountKind)
        // A bank never borrows from the user, whichever way the person-side
        // choice happens to be left.
        assertEquals(LoanDirection.BORROWED, overdraft.loanDirection)
    }

    @Test
    fun `an overdraft owes what was drawn, and offers back what was not`() {
        val od = loan(
            kind = LoanKind.OVERDRAFT,
            principal = Money(120_000_00),
            creditLimit = Money(500_000_00),
        )
        assertTrue(od.isOverdraft)
        assertEquals("headroom is the limit less what is drawn", Money(380_000_00), od.available)

        // Drawn to the limit there is nothing left, and an over-drawn facility
        // reports zero rather than a negative amount to take out.
        assertEquals(Money.ZERO, od.copy(outstanding = Money(500_000_00)).available)
        assertEquals(Money.ZERO, od.copy(outstanding = Money(600_000_00)).available)

        // A term loan has no headroom to report, whatever is stored on it.
        assertNull(loan(kind = LoanKind.BANK, principal = Money(120_000_00)).available)
    }

    @Test
    fun `a month's scheduled instalments move the balance the timeline shows`() {
        // 2 lakh at 9% over 24 months. The first instalment is mostly principal
        // only after years — right now most of it is interest.
        val emi = LoanMath.emi(Money(200_000_00), 9.0, 24)!!
        val loan = loan(
            kind = LoanKind.BANK,
            principal = Money(200_000_00),
            rate = 9.0,
            termMonths = 24,
            emi = emi,
            style = InstalmentStyle.LEVEL_EMI,
            seriesId = "s",
        )

        // Nothing scheduled, nothing moves.
        assertEquals(loan.outstanding, loan.outstandingAfter(0, Money.ZERO))

        val afterOne = loan.outstandingAfter(1, Money.ZERO)
        assertTrue("one instalment must reduce the balance", afterOne < loan.outstanding)
        // …but by the principal slice only, not the whole payment: the interest
        // is a cost, not a repayment.
        val naive = Money(loan.outstanding.minor - emi.minor)
        assertTrue(
            "only the principal part comes off: $afterOne should exceed $naive",
            afterOne > naive,
        )
        assertTrue("three cost more than one", loan.outstandingAfter(3, Money.ZERO) < afterOne)
    }

    @Test
    fun `an informal loan with no schedule projects by what is repaid`() {
        val loan = loan(
            kind = LoanKind.PERSONAL,
            principal = Money(15_000_00),
            seriesId = "s",
        )
        assertEquals(Money(10_000_00), loan.outstandingAfter(1, Money(5_000_00)))
    }

    @Test
    fun `an overdraft is not moved by a schedule it does not have`() {
        val od = loan(
            kind = LoanKind.OVERDRAFT,
            principal = Money(120_000_00),
            creditLimit = Money(500_000_00),
        )
        assertEquals(od.outstanding, od.outstandingAfter(3, Money(50_000_00)))
    }

    @Test
    fun `borrowing and lending are opposite sides of the same form`() {
        val borrowed = HoldingChoice(HoldingGroup.PERSON, person = PersonHolding.BORROWED)
        val lent = HoldingChoice(HoldingGroup.PERSON, person = PersonHolding.LENT)

        assertTrue("both are loans", borrowed.isLoan && lent.isLoan)
        assertEquals(LoanKind.PERSONAL, borrowed.loanKind)
        assertEquals(LoanKind.PERSONAL, lent.loanKind)
        assertFalse(borrowed.isLent)
        assertTrue(lent.isLent)

        // A bank loan is always money the user took, whatever the person-side
        // choice happens to be left on.
        val bankLoan = HoldingChoice(HoldingGroup.BANK, BankHolding.LOAN, PersonHolding.LENT)
        assertFalse("a bank does not borrow from you", bankLoan.isLent)
        assertEquals(LoanDirection.BORROWED, bankLoan.loanDirection)
    }

    @Test
    fun `an existing loan cannot be flipped between borrowed and lent`() {
        val lent = HoldingChoice.of(LoanKind.PERSONAL, LoanDirection.LENT)
        // Reversing it would turn every recorded repayment into its opposite.
        assertEquals(listOf(PersonHolding.LENT), HoldingChoice.personHoldingsFor(lent))
    }

    private fun loan(
        kind: LoanKind,
        principal: Money,
        creditLimit: Money? = null,
        rate: Double? = null,
        termMonths: Int? = null,
        emi: Money? = null,
        style: InstalmentStyle = InstalmentStyle.INTEREST_ONLY,
        seriesId: String? = null,
    ) = Loan(
        id = "l",
        name = "Nabil Bank",
        kind = kind,
        direction = LoanDirection.BORROWED,
        style = style,
        lender = null,
        principal = principal,
        creditLimit = creditLimit,
        outstanding = principal,
        currencyCode = "NPR",
        annualRate = rate ?: 14.0,
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
