package com.mywallet.core.date

import dev.shivathapaa.nepalidatepickerkmp.calendar_model.NepaliDateConverter
import dev.shivathapaa.nepalidatepickerkmp.data.NameFormat
import dev.shivathapaa.nepalidatepickerkmp.data.NepaliDatePickerLang
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * A date in the Bikram Sambat calendar.
 *
 * BS is not an arithmetic calendar — month lengths (29–32 days) are fixed
 * astronomically and published each year, so every implementation is a lookup
 * table. This app does not keep its own.
 */
data class BsDate(
    val year: Int,
    val month: Int, // 1 = Baisakh
    val day: Int,
) : Comparable<BsDate> {

    override fun compareTo(other: BsDate): Int = compareValuesBy(
        this, other, BsDate::year, BsDate::month, BsDate::day,
    )

    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)
}

/**
 * Bikram Sambat conversion, delegated entirely to the `nepali-date-picker`
 * library.
 *
 * This used to be a table transcribed by hand. A day-by-day comparison against
 * this library found the two disagreeing across 92 separate windows — roughly
 * 4,100 days between 1944 and 2090, including recent years. They agreed on
 * every Nepali New Year and on today's date, which is why nothing looked wrong
 * on screen, but month boundaries drifted mid-year.
 *
 * Rather than adjudicate row by row without an authoritative patro, the local
 * table was deleted. The library is maintained, widely used, and — critically —
 * also backs the on-screen date picker, so the picker and the app's month
 * arithmetic now cannot disagree with each other.
 */
object BikramSambat {

    /**
     * The library keeps its supported year range internal, so it is discovered
     * once by probing rather than hardcoded — a hardcoded range would silently
     * go stale the first time the library widens its table.
     *
     * A year only counts as supported if *both* its first and last day
     * round-trip. Checking only Baisakh 1 is not enough: the library's Gregorian
     * table ends in 2043, so the last BS year it can start it cannot finish —
     * and a month window running off the end of the table throws mid-scroll.
     */
    private val supportedYears: IntRange by lazy {
        val probe = (1901..2299).filter { year -> roundTrips(year, 1, 1) && lastDayRoundTrips(year) }
        if (probe.isEmpty()) IntRange.EMPTY else probe.first()..probe.last()
    }

    private fun roundTrips(year: Int, month: Int, day: Int): Boolean = runCatching {
        val english = NepaliDateConverter.convertNepaliToEnglish(year, month, day)
        val back = NepaliDateConverter.convertEnglishToNepali(
            english.year, english.month, english.dayOfMonth,
        )
        back.year == year && back.month == month && back.dayOfMonth == day
    }.getOrDefault(false)

    private fun lastDayRoundTrips(year: Int): Boolean = runCatching {
        roundTrips(year, 12, NepaliDateConverter.getTotalDaysInNepaliMonth(year, 12))
    }.getOrDefault(false)

    val MIN_YEAR: Int get() = supportedYears.first
    val MAX_YEAR: Int get() = supportedYears.last

    /** Nepali (Devanagari) digits, for rendering numbers the local way. */
    private val NE_DIGITS = charArrayOf('०', '१', '२', '३', '४', '५', '६', '७', '८', '९')

    fun toNepaliDigits(value: Int): String = buildString {
        for (ch in value.toString()) {
            if (ch.isDigit()) append(NE_DIGITS[ch - '0']) else append(ch)
        }
    }

    /**
     * English transliterations are ours, not the library's.
     *
     * The library renders Shrawan as "Shrawn" and Mangsir as "Mangsir"; the
     * first reads as a typo to anyone who knows the month. Conversion stays
     * delegated — only the spelling is local. The library's own picker dialog
     * still shows its spelling, which is a cosmetic quirk inside third-party UI.
     */
    private val MONTH_NAMES_EN = listOf(
        "Baisakh", "Jestha", "Asar", "Shrawan", "Bhadra", "Ashwin",
        "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra",
    )

    fun monthName(month: Int, nepaliScript: Boolean): String {
        val safe = month.coerceIn(1, 12)
        return if (nepaliScript) {
            NepaliDateConverter.getMonthName(safe, NameFormat.FULL, NepaliDatePickerLang.NEPALI)
        } else {
            MONTH_NAMES_EN[safe - 1]
        }
    }

    fun supports(year: Int): Boolean = year in MIN_YEAR..MAX_YEAR

    /**
     * True when [date] can be converted. Checked by attempting the conversion
     * rather than by comparing against a Gregorian range, because the library
     * owns the bounds and they can move between versions.
     */
    fun supports(date: LocalDate): Boolean = runCatching {
        val bs = fromGregorian(date)
        supports(bs.year) && toGregorian(bs) == date
    }.getOrDefault(false)

    /**
     * The three lookups below are **remembered**, and the app is unusable in
     * Nepali months without it.
     *
     * BS is a table calendar, so every conversion is a search through the
     * library's data — cheap once and ruinous in bulk, which is exactly how this
     * app asks. Stepping a schedule a month at a time (`Recurrence.addMonths`)
     * costs three conversions a step, and a thirty-year loan's table is 360
     * steps: over a thousand table searches to redraw one schedule, and the
     * editor redraws it on **every keystroke**. Measured on a 30-year monthly
     * loan, that table took 57 µs in Gregorian and 1,328 µs in Bikram Sambat —
     * the whole of the difference being conversions, and all of it landing on
     * the typing thread.
     *
     * Safe to remember because these are the definition of a pure function: BS
     * is published years in advance and 3 Bhadra 2076 was 20 August 2019 before
     * this app existed and will be after it. Nothing here can go stale, so there
     * is nothing to invalidate — the only bound is memory, and [remember] caps it.
     *
     * Concurrent maps because a schedule is built on the IO thread while a
     * picker draws the same months on the main one.
     */
    private val toBs = ConcurrentHashMap<Long, BsDate>()
    private val toAd = ConcurrentHashMap<BsDate, LocalDate>()
    private val monthLengths = ConcurrentHashMap<Int, Int>()

    /**
     * The most conversions worth holding on to, each way.
     *
     * A century of days either side of today is well under this, so in practice
     * nothing is ever dropped. It exists so that a caller sweeping the whole
     * supported range — a backup restoring decades of dated rows — cannot grow
     * the map without limit. Cleared wholesale rather than evicted one at a
     * time: dropping the lot costs one rebuild of whatever is on screen, and an
     * LRU would cost a comparison on every hit to save it.
     */
    private const val MAX_REMEMBERED = 100_000

    private inline fun <K : Any, V : Any> ConcurrentHashMap<K, V>.remember(
        key: K,
        compute: () -> V,
    ): V {
        get(key)?.let { return it }
        val value = compute()
        if (size >= MAX_REMEMBERED) clear()
        put(key, value)
        return value
    }

    fun daysInMonth(year: Int, month: Int): Int {
        require(supports(year)) { "BS year $year is outside $MIN_YEAR..$MAX_YEAR" }
        require(month in 1..12) { "BS month must be 1..12, was $month" }
        return monthLengths.remember(year * 12 + month) {
            NepaliDateConverter.getTotalDaysInNepaliMonth(year, month)
        }
    }

    fun daysInYear(year: Int): Int = (1..12).sumOf { daysInMonth(year, it) }

    fun fromGregorian(date: LocalDate): BsDate = toBs.remember(date.toEpochDay()) {
        val c = NepaliDateConverter.convertEnglishToNepali(
            date.year, date.monthValue, date.dayOfMonth,
        )
        BsDate(c.year, c.month, c.dayOfMonth)
    }

    fun toGregorian(bs: BsDate): LocalDate = toAd.remember(bs) {
        val c = NepaliDateConverter.convertNepaliToEnglish(bs.year, bs.month, bs.day)
        LocalDate.of(c.year, c.month, c.dayOfMonth)
    }

    /** First Gregorian day of a BS month — used to build month windows. */
    fun startOfMonth(year: Int, month: Int): LocalDate = toGregorian(BsDate(year, month, 1))

}
