package com.mywallet

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.repo.groupByDay
import com.mywallet.domain.MoneyEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A day's two figures have to add up the rows drawn under them.
 *
 * They used to be an income-and-spending summary, which leaves out every
 * adjustment — and money lent to somebody, money borrowed and a repayment
 * coming back are all adjustments that the timeline *draws*. So a day whose
 * only movement was "I lent Sita रू 8,000" showed the row and no total at all,
 * and the two directional chips hid it as well.
 */
class DayGroupTest {

    private val day = LocalDate.of(2026, 7, 21)

    @Test
    fun `a day of money lent totals what it lists`() {
        val groups = listOf(lent(Money(500_000))).groupByDay()

        assertEquals(1, groups.size)
        assertEquals(Money(500_000), groups.single().moneyOut)
        assertEquals(Money.ZERO, groups.single().moneyIn)
    }

    @Test
    fun `money borrowed is the day's money in`() {
        val groups = listOf(borrowed(Money(5_000_000))).groupByDay()

        assertEquals(Money(5_000_000), groups.single().moneyIn)
        assertEquals(Money.ZERO, groups.single().moneyOut)
    }

    @Test
    fun `an ordinary payment and a loan movement are added together`() {
        val groups = listOf(
            spent(Money(130_000)),
            lent(Money(500_000)),
        ).groupByDay()

        // The whole point: the header equals the column beneath it, whatever
        // mixture of rows the day happens to hold.
        assertEquals(Money(630_000), groups.single().moneyOut)
    }

    private fun spent(amount: Money) = entry(amount, Direction.OUT, isAdjustment = false)

    /** Money handed to somebody — an adjustment, and money genuinely out. */
    private fun lent(amount: Money) = entry(amount, Direction.OUT, isAdjustment = true)

    /** Money taken from somebody — an adjustment, and money genuinely in. */
    private fun borrowed(amount: Money) = entry(amount, Direction.IN, isAdjustment = true)

    private fun entry(amount: Money, direction: Direction, isAdjustment: Boolean) = MoneyEntry(
        id = "e-${amount.minor}-$direction",
        amount = amount,
        currencyCode = "NPR",
        baseAmount = amount,
        accountId = "A1",
        accountName = "Nabil Bank",
        isAdjustment = isAdjustment,
        direction = direction,
        occurredOn = day,
        note = null,
    )
}
