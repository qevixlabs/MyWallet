package com.mywallet

import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.DigitGrouping
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.core.money.groupInternational
import com.mywallet.core.money.groupSouthAsian
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Amount parsing is where a money app quietly loses people's data: a "1,50"
 * read as 150 rather than 1.50 is off by a hundred and nobody notices until the
 * totals are wrong.
 */
class MoneyTest {

    private val npr = MoneyFormatter(CurrencyOption.NPR, Locale.US)
    private val jpy = MoneyFormatter(CurrencyOption.JPY, Locale.US)

    @Test
    fun `plain integers become minor units`() {
        assertEquals(Money(1_200_00), npr.parse("1200"))
        assertEquals(Money(0), npr.parse("0"))
    }

    @Test
    fun `decimals are read exactly`() {
        assertEquals(Money(1_250), npr.parse("12.50"))
        assertEquals(Money(1_205), npr.parse("12.05"))
        assertEquals(Money(1_200), npr.parse("12.0"))
    }

    @Test
    fun `grouping separators are stripped`() {
        assertEquals(Money(1_234_567_00), npr.parse("1,234,567"))
        assertEquals(Money(1_234_56), npr.parse("1,234.56"))
    }

    @Test
    fun `a lone comma with two digits is a decimal mark, not grouping`() {
        // European entry: "1,50" means one and a half, never one hundred fifty.
        assertEquals(Money(1_50), npr.parse("1,50"))
    }

    @Test
    fun `extra decimal places are truncated, never rounded up`() {
        // Rounding up would invent money the user never spent.
        assertEquals(Money(1_299), npr.parse("12.999"))
    }

    @Test
    fun `whitespace and the currency symbol are ignored`() {
        assertEquals(Money(50_00), npr.parse(" 50 "))
        assertEquals(Money(50_00), npr.parse("रू50"))
    }

    @Test
    fun `nonsense is rejected rather than guessed at`() {
        assertNull(npr.parse(""))
        assertNull(npr.parse("abc"))
        assertNull(npr.parse("12.3.4"))
        assertNull(npr.parse("."))
    }

    @Test
    fun `zero-decimal currencies do not gain a fractional part`() {
        assertEquals(Money(1200), jpy.parse("1200"))
        assertEquals("¥1,200", jpy.formatCompact(Money(1200)))
    }

    @Test
    fun `compact formatting drops decimals only when they are all zero`() {
        assertEquals("रू 1,200", npr.formatCompact(Money(1_200_00)))
        assertEquals("रू 1,200.50", npr.formatCompact(Money(1_200_50)))
    }

    @Test
    fun `plain input round-trips through parsing`() {
        listOf(Money(1_200_00), Money(1_200_50), Money(5), Money(0)).forEach { amount ->
            assertEquals(amount, npr.parse(npr.toPlainInput(amount)))
        }
    }

    @Test
    fun `a negative amount carries one minus sign, not one per half`() {
        // A cash balance below zero is normal; "-41955.-17" was not.
        assertEquals("-41955.17", npr.toPlainInput(Money(-41_955_17)))
        assertEquals("-41955", npr.toPlainInput(Money(-41_955_00)))
        assertEquals("-500", jpy.toPlainInput(Money(-500)))
    }

    @Test
    fun `digits are grouped in lakhs and crores, not thousands`() {
        // Three at the end and twos above it. The platform's own grouping is
        // western at every locale we ship, so this cannot come from the locale.
        assertEquals("रू 1,00,000", npr.formatCompact(Money(1_00_000_00)))
        assertEquals("रू 12,34,567.89", npr.format(Money(12_34_567_89)))
        assertEquals("रू 1,00,00,000", npr.formatCompact(Money(1_00_00_000_00)))
        assertEquals("रू 1,00,00,00,000", npr.formatCompact(Money(1_00_00_00_000_00)))
        // Under a lakh nothing changes; a bare thousand is still a thousand.
        assertEquals("रू 999", npr.formatCompact(Money(999_00)))
        assertEquals("रू 9,999", npr.formatCompact(Money(9_999_00)))
    }

    @Test
    fun `a foreign currency is grouped the same way`() {
        // The grouping describes the person reading the row, not the money. Two
        // lines of one row grouped differently would be harder to compare.
        val usd = MoneyFormatter(CurrencyOption.USD, Locale.US)
        assertEquals("$9,00,000", usd.formatCompact(Money(9_00_000_00)))
        assertEquals("¥12,34,567", jpy.formatCompact(Money(12_34_567)))
    }

    @Test
    fun `the rupee stands apart from its digits and the dollar does not`() {
        // How each currency is written where it is spoken. A figure set the
        // other way round reads as though it were typed by someone who does
        // not use that money.
        val inr = MoneyFormatter(CurrencyOption.INR, Locale.US)
        val usd = MoneyFormatter(CurrencyOption.USD, Locale.US)
        assertEquals("रू 100", npr.formatCompact(Money(100_00)))
        assertEquals("₹ 100", inr.formatCompact(Money(100_00)))
        assertEquals("$100", usd.formatCompact(Money(100_00)))
    }

    @Test
    fun `reading in Nepali keeps the locale's own numerals`() {
        val nepali = MoneyFormatter(CurrencyOption.NPR, Locale.forLanguageTag("ne-NP"))
        assertEquals("रू १,२३,४५,६७८.९०", nepali.format(Money(1_23_45_678_90)))
    }

    @Test
    fun `a negative figure carries its sign in front of the whole amount`() {
        // Not "रू -1,00,000.50": the symbol reads as the start of the figure,
        // so a minus sitting behind it lands mid-amount and a debit looks like
        // a credit at a glance.
        val usd = MoneyFormatter(CurrencyOption.USD, Locale.US)
        assertEquals("-रू 1,00,000.50", npr.format(Money(-1_00_000_50)))
        assertEquals("-$1,00,000.50", usd.format(Money(-1_00_000_50)))
        assertEquals("-रू 1,00,000", npr.formatCompact(Money(-1_00_000_00)))
    }

    @Test
    fun `sums stay exact where floating point would drift`() {
        // 0.1 + 0.2 in Double is 0.30000000000000004; in minor units it is 30.
        val total = Money.sum(listOf(Money(10), Money(20)))
        assertEquals(Money(30), total)
    }

    private fun codes(used: List<String>, selected: String) =
        CurrencyOption.shortlist(used, selected).map { it.code }

    @Test
    fun `a phone with no holdings is offered the four guesses`() {
        assertEquals(listOf("NPR", "INR", "USD", "AED"), codes(emptyList(), "NPR"))
    }

    @Test
    fun `a currency the user holds displaces a guess`() {
        // Somebody paid in riyals said so by opening an account in them, and is
        // not asked to go looking for SAR again.
        assertEquals(listOf("NPR", "SAR", "INR", "USD"), codes(listOf("SAR", "NPR"), "NPR"))
    }

    @Test
    fun `the newest holdings lead, and the guesses fall off the end`() {
        assertEquals(
            listOf("NPR", "QAR", "MYR", "SAR"),
            codes(listOf("QAR", "MYR", "SAR", "NPR"), "NPR"),
        )
    }

    @Test
    fun `whatever is selected is always on the list`() {
        // The form opens on the display currency, which may be none of the
        // four guesses — and a row of chips that cannot show its own answer
        // reads as though nothing has been chosen.
        assertEquals(listOf("EUR", "NPR", "INR", "USD"), codes(emptyList(), "EUR"))
        assertEquals("KWD", codes(listOf("SAR", "QAR", "MYR", "SGD"), "KWD").first())
    }

    @Test
    fun `a currency held twice is offered once`() {
        assertEquals(listOf("USD", "NPR", "INR", "AED"), codes(listOf("USD", "USD", "NPR"), "USD"))
    }

    @Test
    fun `a figure being typed groups the way the figure it becomes does`() {
        // The box and the amount printed under it are one number written twice.
        assertEquals("1,00,000", groupSouthAsian("100000", ','))
        assertEquals("10,000", groupSouthAsian("10000", ','))
        assertEquals("1,00,00,00,000", groupSouthAsian("1000000000", ','))
        assertEquals("999", groupSouthAsian("999", ','))
        assertEquals("", groupSouthAsian("", ','))
    }

    @Test
    fun `a box mid-figure keeps what a stored amount could not hold`() {
        // Leading zeros the user is about to overtype, and a run of digits
        // longer than a Long — both of which a parse-and-reformat would rewrite
        // under the thumb.
        assertEquals("00,000", groupSouthAsian("00000", ','))
    }

    @Test
    fun `international grouping breaks into threes all the way up`() {
        assertEquals("100,000", groupInternational("100000", ','))
        assertEquals("10,000", groupInternational("10000", ','))
        assertEquals("1,000,000,000", groupInternational("1000000000", ','))
        assertEquals("999", groupInternational("999", ','))
        assertEquals("", groupInternational("", ','))
    }

    @Test
    fun `the grouping a calendar asks for`() {
        // The whole of the rule: the calendar is the app's answer to "who is
        // reading this?", and nothing else is consulted.
        assertEquals(DigitGrouping.SOUTH_ASIAN, CalendarSystem.BIKRAM_SAMBAT.grouping)
        assertEquals(DigitGrouping.INTERNATIONAL, CalendarSystem.GREGORIAN.grouping)
    }

    @Test
    fun `a formatter groups the way it was asked to`() {
        val npr = CurrencyOption.byCode("NPR")
        assertEquals(
            "1,00,000.00",
            MoneyFormatter(npr, Locale.ENGLISH, DigitGrouping.SOUTH_ASIAN)
                .formatBare(Money(10_000_000)),
        )
        assertEquals(
            "100,000.00",
            MoneyFormatter(npr, Locale.ENGLISH, DigitGrouping.INTERNATIONAL)
                .formatBare(Money(10_000_000)),
        )
    }

    @Test
    fun `a formatter for another currency keeps the grouping`() {
        // Two lines of one converted row must not be punctuated two ways.
        val base = MoneyFormatter(
            CurrencyOption.byCode("NPR"), Locale.ENGLISH, DigitGrouping.INTERNATIONAL,
        )
        assertEquals(DigitGrouping.INTERNATIONAL, base.forCurrency("USD").grouping)
        assertEquals(
            "99,99,99,99,99,99,99,99,99,999",
            groupSouthAsian("9".repeat(21), ','),
        )
    }

    @Test
    fun `a code the app has never heard of does not eat a place`() {
        // It resolves to the rupee, which is already there — so it dedupes away
        // rather than drawing NPR twice and leaving the row a currency short.
        val shortlist = CurrencyOption.shortlist(listOf("ZZZ"), "NPR")
        assertEquals(CurrencyOption.SHORTLIST, shortlist.size)
        assertEquals(listOf("NPR", "INR", "USD", "AED"), shortlist.map { it.code })
    }
}
