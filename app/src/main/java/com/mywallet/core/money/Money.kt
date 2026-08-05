package com.mywallet.core.money

import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Money is stored as a [Long] count of minor units (paisa, cents) everywhere —
 * database, ViewModels, calculations. Never as Double: 0.1 + 0.2 != 0.3 and a
 * money app that loses a paisa loses the user's trust.
 *
 * The value class means it costs nothing at runtime but the compiler still stops
 * you handing an amount where a count was expected.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(minor + other.minor)
    operator fun minus(other: Money) = Money(minor - other.minor)
    operator fun unaryMinus() = Money(-minor)
    operator fun times(factor: Int) = Money(minor * factor)

    val isZero: Boolean get() = minor == 0L
    val isPositive: Boolean get() = minor > 0L
    val absolute: Money get() = Money(minor.absoluteValue)

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    companion object {
        val ZERO = Money(0L)

        fun sum(values: Iterable<Money>): Money = Money(values.sumOf { it.minor })
    }
}

fun Iterable<Money>.sum(): Money = Money.sum(this)

/**
 * Groups a whole number the way South Asia reads one: three digits at the end,
 * then twos all the way up — 1,00,000 and 1,00,00,00,000, never 100,000.
 *
 * This is deliberately not the locale's own answer. A lakh and a crore are the
 * units the user counts in whichever language the app is set to, and a figure
 * grouped in thousands has to be counted digit by digit before it can be read
 * as either. It applies to every currency for the same reason: the grouping
 * describes the person reading the number, not the money, and two lines of one
 * row grouped two different ways would be harder to compare than either alone.
 */
/**
 * The same grouping, applied to digits being *typed* rather than to a figure
 * already stored.
 *
 * A separate overload rather than a parse-and-reformat, because a box being
 * filled in holds things a [Money] cannot: a half-typed "1000." with nothing
 * after the point, a leading zero the user is about to overtype, and a run of
 * digits longer than a [Long] can hold. Reformatting through the number would
 * silently rewrite all three under the thumb.
 */
/**
 * Which way a run of digits is broken up.
 *
 * Lakhs and crores were unconditional here for a long time, on the reasoning
 * that the grouping describes the reader rather than the money. That is still
 * the reasoning — it has simply been given a better way of asking who is
 * reading. The **calendar** is the app's own answer to that question: somebody
 * reading Bikram Sambat dates is reading a Nepali page and knows १,२३,४५,६७८ at
 * a glance, and somebody reading Gregorian ones is not and does not. The
 * language cannot stand in for it, since the app is read in English by plenty of
 * people who think in lakhs, and the currency cannot either — a Nepali reader
 * with a dollar account groups dollars their own way.
 *
 * One formatter carries one of these, so the two lines of a converted row are
 * never grouped two different ways.
 */
enum class DigitGrouping {
    /** 1,00,000 and 1,00,00,00,000 — three at the end and twos above. */
    SOUTH_ASIAN,

    /** 100,000 and 1,000,000,000 — threes all the way up. */
    INTERNATIONAL,
}

/** Splits [digits] the way [style] asks. */
internal fun group(digits: String, separator: Char, style: DigitGrouping): String =
    when (style) {
        DigitGrouping.SOUTH_ASIAN -> groupSouthAsian(digits, separator)
        DigitGrouping.INTERNATIONAL -> groupInternational(digits, separator)
    }

/**
 * Threes all the way up, by hand for the reason the other one is: a box being
 * typed into holds a half-written figure the platform's own formatter would
 * quietly rewrite.
 */
internal fun groupInternational(digits: String, separator: Char): String {
    if (digits.length <= 3) return digits
    val groups = ArrayList<String>(digits.length / 3 + 1)
    var end = digits.length
    while (end > 0) {
        val start = maxOf(0, end - 3)
        groups.add(digits.substring(start, end))
        end = start
    }
    groups.reverse()
    return groups.joinToString(separator.toString())
}

internal fun groupSouthAsian(digits: String, separator: Char): String {
    if (digits.length <= 3) return digits
    val head = digits.substring(0, digits.length - 3)
    val groups = ArrayList<String>(head.length / 2 + 1)
    var end = head.length
    while (end > 0) {
        val start = maxOf(0, end - 2)
        groups.add(head.substring(start, end))
        end = start
    }
    groups.reverse()
    return groups.joinToString(separator.toString()) + separator + digits.takeLast(3)
}

internal fun groupSouthAsian(value: Long, separator: Char): String =
    groupSouthAsian(value.toString(), separator)

/**
 * A currency the app can display. [minorUnits] is how many decimal places the
 * currency actually uses — JPY has 0, most have 2 — so `Money(1050)` renders as
 * "10.50" for NPR but "1,050" for JPY.
 *
 * [spacedSymbol] is how the currency is written where it is spoken. The rupee
 * sign stands apart from its digits — "रू 100", "₹ 100" — while the dollar,
 * pound and euro signs sit against them: "$100". Getting it wrong does not
 * make a figure unreadable, but it makes every amount in the app read as
 * though it were typed by someone who does not use that money.
 */
data class CurrencyOption(
    val code: String,
    val symbol: String,
    val minorUnits: Int = 2,
    val spacedSymbol: Boolean = false,
) {
    /**
     * How the currency reads in a list of currencies to choose from.
     *
     * The bare code where a currency is written as its own code: some have no
     * symbol of their own, and "AED AED" is the code said twice. One definition,
     * because the choice is offered on the way into the app and again in
     * Settings, and the same currency must not be spelled two ways.
     */
    val pickerLabel: String get() = if (symbol == code) code else "$symbol  $code"

    companion object {
        val NPR = CurrencyOption("NPR", "रू", spacedSymbol = true)
        val INR = CurrencyOption("INR", "₹", spacedSymbol = true)
        val USD = CurrencyOption("USD", "$")
        val EUR = CurrencyOption("EUR", "€")
        val GBP = CurrencyOption("GBP", "£")
        val AUD = CurrencyOption("AUD", "A$")
        val CAD = CurrencyOption("CAD", "C$")
        val JPY = CurrencyOption("JPY", "¥", minorUnits = 0)
        // Written as its own code rather than as "Fr", which is what a Swiss
        // franc is abbreviated to at home and what nobody outside reads as
        // money. Spaced, like every other symbol that is a word rather than a
        // sign.
        val CHF = CurrencyOption("CHF", "CHF", spacedSymbol = true)
        // The Gulf and Asian currencies a Nepali worker is most often paid in.
        // Three of them are **thousandths**, not hundredths: a Bahraini dinar is
        // 1,000 fils, and storing one as two decimal places would put every
        // figure out by a factor of ten. That is the whole reason [minorUnits]
        // is per-currency and not a constant.
        val BHD = CurrencyOption("BHD", "BD", minorUnits = 3, spacedSymbol = true)
        val HKD = CurrencyOption("HKD", "HK$")
        val KWD = CurrencyOption("KWD", "KD", minorUnits = 3, spacedSymbol = true)
        val MYR = CurrencyOption("MYR", "RM", spacedSymbol = true)
        val OMR = CurrencyOption("OMR", "OMR", minorUnits = 3, spacedSymbol = true)
        val QAR = CurrencyOption("QAR", "QR", spacedSymbol = true)
        val SAR = CurrencyOption("SAR", "SR", spacedSymbol = true)
        val SGD = CurrencyOption("SGD", "S$")
        val AED = CurrencyOption("AED", "AED", spacedSymbol = true)

        /**
         * Offered in Settings, in this order: the two the app is written for
         * first, then the majors, then the places its users are most often paid
         * from. Long enough now that Settings offers them in a dropdown rather
         * than as a wrapped field of chips.
         */
        val ALL = listOf(
            NPR, INR, USD, EUR, GBP, AUD, CAD, JPY, CHF,
            BHD, HKD, KWD, MYR, OMR, QAR, SAR, SGD, AED,
        )

        /**
         * What a phone with no history offers: home, the country next door, the
         * currency the world quotes in, and the Gulf.
         *
         * A guess, and it says so by being replaceable — see [shortlist]. Four
         * of eighteen because eighteen chips wrap into five lines of
         * three-letter codes, and a user picking their own currency reads all
         * of them to find the one they were going to pick anyway. The right
         * four are almost always these until the user has said otherwise.
         */
        val SUGGESTED = listOf(NPR, INR, USD, AED)

        /** How many are offered before the rest are asked for. */
        const val SHORTLIST = 4

        /**
         * The few currencies to put in front of the user, most likely first.
         *
         * [used] is what their holdings are actually denominated in, newest
         * first, and it displaces the guesses one at a time: somebody paid in
         * riyals says so once by opening an account in them, and never answers
         * the question again. [selected] leads whatever else happens, because a
         * row of choices that hides the one it is currently on is a row that
         * cannot show its own answer — and the form opens on the display
         * currency, which may be none of the four guesses.
         *
         * Worked out once, when the form loads, and deliberately not again as
         * the user taps: a list that reordered itself under the thumb would move
         * the chip beside the one being aimed at.
         */
        fun shortlist(
            used: List<String>,
            selected: String,
            size: Int = SHORTLIST,
        ): List<CurrencyOption> =
            (listOf(selected) + used + SUGGESTED.map { it.code })
                // Resolved before it is deduplicated, not after. A code this app
                // has never heard of falls back to the rupee, and two entries
                // both reading NPR would draw the same chip twice and leave the
                // shortlist a currency short.
                .map { byCode(it.uppercase()) }
                .distinctBy { it.code }
                .take(size)

        fun byCode(code: String): CurrencyOption =
            ALL.firstOrNull { it.code == code } ?: fromSystem(code)

        /** Fall back to the JDK's data for a currency we do not hardcode. */
        private fun fromSystem(code: String): CurrencyOption = runCatching {
            val c = Currency.getInstance(code)
            CurrencyOption(c.currencyCode, c.symbol, c.defaultFractionDigits.coerceAtLeast(0))
        }.getOrDefault(NPR)
    }
}

/**
 * The symbol as it stands in front of a field the user types into.
 *
 * The same spacing the formatter uses, so a box reading "रू 5000" while it is
 * being filled in and "रू 5,000" once it is saved is one figure being written
 * down rather than two ways of writing it.
 */
val CurrencyOption.inputPrefix: String get() = if (spacedSymbol) "$symbol " else symbol

/**
 * Formats amounts for display. Held as a single object so number grouping and
 * the currency symbol stay identical everywhere in the app.
 */
class MoneyFormatter(
    private val currency: CurrencyOption,
    private val locale: Locale = Locale.getDefault(),
    /**
     * How the digits are broken up — see [DigitGrouping].
     *
     * Defaulted to lakhs and crores, which is what this did unconditionally
     * before there was a choice, so a formatter built without an opinion goes on
     * behaving as the whole app used to.
     */
    val grouping: DigitGrouping = DigitGrouping.SOUTH_ASIAN,
) {
    /**
     * The same formatter for another currency.
     *
     * A row that shows a foreign amount over its converted one builds a second
     * formatter for the top line, and it has to carry this one's locale *and*
     * its grouping: two lines of one figure punctuated two different ways are
     * harder to compare than either alone, which is the whole reason the
     * grouping is a property of the formatter rather than of the currency.
     */
    fun forCurrency(code: String): MoneyFormatter =
        MoneyFormatter(CurrencyOption.byCode(code), locale, grouping)

    /** Which currency this formatter renders, for callers deciding what to show. */
    val currencyCode: String get() = currency.code

    /**
     * How many decimal places this currency actually has — two for most, three
     * for the Gulf dinars, none at all for a currency with no minor unit.
     *
     * Exposed so a box being typed into can refuse the digits [parse] would
     * silently throw away. Truncating on save alone left a field showing
     * "15000.999999999" and a debt taking रू 15,000.99 from it, which is the app
     * and the user reading the same box two different ways.
     */
    val minorUnits: Int get() = currency.minorUnits
    private val scale: Long = POWERS_OF_TEN[currency.minorUnits]
    private val fractionDigits: Int = currency.minorUnits

    // Only the *symbols* come from the locale — the grouping does not. The
    // platform's own grouping is western at every locale we ship (Nepali
    // included: it renders १२,३४५,६७८ and not १,२३,४५,६७८), and the JDK's
    // "#,##,##0" pattern is honoured by ICU on the device but ignored on the
    // desktop JVM the unit tests run on, so a pattern would pass its test and
    // still be wrong on the phone. Grouping is done by hand below instead.
    private val symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(locale)

    /**
     * What separates the groups, so a box being filled in can group its digits
     * the same way the figure it becomes will be grouped.
     *
     * Exposed rather than assumed to be a comma: the symbols come from the
     * locale, and a field that punctuated its digits differently from the amount
     * printed underneath it would read as two ways of writing one number.
     */
    val groupingSeparator: Char get() = symbols.groupingSeparator

    /** "रू 1,250.00" — the full, unambiguous form. Use in lists and detail rows. */
    fun format(money: Money): String = withSymbol(money, withFraction = true)

    /** "रू 1,250" — drops the decimals when they are all zero. Use in headlines. */
    fun formatCompact(money: Money): String {
        val hasFraction = currency.minorUnits > 0 && money.minor % scale != 0L
        return withSymbol(money, withFraction = hasFraction)
    }

    /**
     * Symbol, then digits — with the minus sign in front of *both*.
     *
     * "रू -100" and "$ -100" put the sign where nothing reads it: the eye takes
     * the symbol as the start of the amount, so the minus lands mid-figure and
     * a debit looks like a credit at a glance. The whole amount is one object
     * and the sign belongs in front of it.
     *
     * The space between them is the currency's own — see
     * [CurrencyOption.spacedSymbol].
     */
    private fun withSymbol(money: Money, withFraction: Boolean): String {
        val sign = if (money.minor < 0) symbols.minusSign.toString() else ""
        val gap = if (currency.spacedSymbol) " " else ""
        return "$sign${currency.symbol}$gap${number(money.absolute, withFraction)}"
    }

    /**
     * "1,250.00" — grouped digits, no currency symbol.
     *
     * Only for a column of figures that names its currency once, in its own
     * heading. A symbol repeated down every cell of a repayment table is noise
     * that pushes the digits off the side of a phone, and the digits are the
     * entire point of the table. Anywhere a single amount stands on its own,
     * [format] or [formatCompact] is the honest one: an amount with no currency
     * beside it is a number, not money.
     */
    fun formatBare(money: Money): String = number(money, withFraction = true)

    /**
     * The figure alone: sign, grouped digits, and the decimals if asked for.
     *
     * Built out of the [Long] rather than a Double divided by [scale]. The
     * division was exact at every amount the app can hold, but the rule
     * everywhere else is that money never becomes a Double, and the last place
     * it did was the one place a rounding drift would be invisible.
     */
    private fun number(money: Money, withFraction: Boolean): String {
        val magnitude = money.minor.absoluteValue
        val whole = group(
            (magnitude / scale).toString(), symbols.groupingSeparator, grouping,
        )
        val text = if (withFraction && fractionDigits > 0) {
            val fraction = (magnitude % scale).toString().padStart(fractionDigits, '0')
            "$whole${symbols.decimalSeparator}$fraction"
        } else {
            whole
        }
        val localised = inLocaleDigits(text)
        return if (money.minor < 0) "${symbols.minusSign}$localised" else localised
    }

    /** Latin digits become the locale's own — Devanagari when reading in Nepali. */
    private fun inLocaleDigits(text: String): String {
        val zero = symbols.zeroDigit
        if (zero == '0') return text
        return buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') zero + (c - '0') else c)
        }
    }

    /**
     * Digits only — no symbol, no grouping separators — for pre-filling an
     * editable amount field. Grouping in an input the user is about to retype
     * only gets in the way.
     */
    fun toPlainInput(money: Money): String {
        // Sign handled once, up front. Dividing a negative amount leaves the
        // minus on both halves, and "-41955.-17" is what came out of it.
        val sign = if (money.minor < 0) "-" else ""
        val magnitude = kotlin.math.abs(money.minor)
        val units = magnitude / scale
        if (fractionDigits == 0) return "$sign$units"
        val fraction = magnitude % scale
        return if (fraction == 0L) "$sign$units"
        else "$sign$units.${fraction.toString().padStart(fractionDigits, '0')}"
    }

    /**
     * Parses what the user typed. Deliberately forgiving: strips the currency
     * symbol, spaces and grouping separators, accepts both "." and "," as the
     * decimal mark, and refuses anything left over rather than guessing.
     *
     * Returns null when the input is not a usable amount.
     */
    fun parse(input: String): Money? {
        val cleaned = input.trim()
            .removePrefix(currency.symbol)
            .filterNot { it.isWhitespace() }
            .replace(" ", "")
        if (cleaned.isEmpty()) return null

        val normalised = normaliseSeparators(cleaned) ?: return null
        val negative = normalised.startsWith("-")
        val digits = normalised.removePrefix("-").removePrefix("+")
        if (digits.isEmpty() || digits.any { !it.isDigit() && it != '.' }) return null
        if (digits.count { it == '.' } > 1) return null
        // A bare "." or "-" has no digits at all; without this it would fall
        // through the arithmetic below and quietly parse as zero.
        if (digits.none { it.isDigit() }) return null

        val whole = digits.substringBefore('.').ifEmpty { "0" }
        val fractionRaw = digits.substringAfter('.', "")
        // Extra typed decimals are truncated, not rounded — never silently inflate.
        val fraction = fractionRaw.padEnd(currency.minorUnits, '0').take(currency.minorUnits)

        val wholeMinor = whole.toLongOrNull()?.times(scale) ?: return null
        val fractionMinor = if (fraction.isEmpty()) 0L else fraction.toLongOrNull() ?: return null

        val total = wholeMinor + fractionMinor
        return Money(if (negative) -total else total)
    }

    /**
     * Turns locale-specific grouping into a plain "1234.56". The ambiguous case
     * is a single comma: "1,50" is a decimal in many locales but grouping in
     * none that uses a 2-digit group, so we treat 1-2 trailing digits as decimal.
     */
    private fun normaliseSeparators(raw: String): String? {
        val hasDot = raw.contains('.')
        val hasComma = raw.contains(',')
        return when {
            hasDot && hasComma -> {
                // Whichever appears last is the decimal mark.
                if (raw.lastIndexOf('.') > raw.lastIndexOf(',')) raw.replace(",", "")
                else raw.replace(".", "").replace(',', '.')
            }
            hasComma -> {
                val tail = raw.substringAfterLast(',')
                if (raw.count { it == ',' } == 1 && tail.length <= 2) raw.replace(',', '.')
                else raw.replace(",", "")
            }
            else -> raw
        }
    }

    private companion object {
        val POWERS_OF_TEN = longArrayOf(1L, 10L, 100L, 1_000L, 10_000L)
    }
}
