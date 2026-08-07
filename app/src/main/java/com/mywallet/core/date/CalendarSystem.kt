package com.mywallet.core.date

import com.mywallet.core.money.DigitGrouping
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Which calendar the user reads dates in.
 *
 * Storage is always Gregorian [LocalDate] (and epoch-day in the database) —
 * this only changes what gets drawn on screen and where a "month" begins and
 * ends. Keeping the conversion at the edge means no query, sum or sort has to
 * know a second calendar exists.
 */
enum class CalendarSystem {
    GREGORIAN,
    BIKRAM_SAMBAT;

    /**
     * How digits are punctuated for somebody reading this calendar.
     *
     * Lakhs and crores were applied to every figure in the app whatever the
     * settings said, on the reasoning that the grouping describes the reader
     * rather than the money. Still true — this is simply a better way of asking
     * who the reader is. Somebody reading Bikram Sambat dates is reading a
     * Nepali page and takes १,२३,४५,६७८ at a glance; somebody reading Gregorian
     * ones is not and has to count the digits. Neither the language nor the
     * currency can stand in for it: the app is read in English by plenty of
     * people who think in lakhs, and a Nepali reader with a dollar account
     * groups dollars their own way.
     *
     * It lives on the calendar rather than beside it so there is exactly one
     * answer to derive from, and no way for a second setting to disagree.
     */
    val grouping: DigitGrouping
        get() = when (this) {
            BIKRAM_SAMBAT -> DigitGrouping.SOUTH_ASIAN
            GREGORIAN -> DigitGrouping.INTERNATIONAL
        }

    companion object {
        fun fromKey(key: String?): CalendarSystem =
            entries.firstOrNull { it.name == key } ?: GREGORIAN

        /**
         * Which calendar a bank is actually counting in.
         *
         * Two answers make one, and the order matters. Reading dates in Bikram
         * Sambat is a *display* choice; a bank closing its quarters on 1 Baisakh
         * or debiting an instalment on 1 Shrawan is a fact about the
         * arrangement, and most Nepali banks do neither — they work in English
         * months and print English dates on the statement. Inferring one from
         * the other would move every payout day and every due date of every
         * holding the moment somebody switched their patro.
         *
         * So the holding carries an opt-in and this is where the two meet:
         * Bikram Sambat only when the holding says so *and* that is the calendar
         * being read. Unticked is [GREGORIAN] whatever the setting says, which is
         * the default and the answer for almost everybody.
         */
        fun forInterest(optedIn: Boolean, setting: CalendarSystem): CalendarSystem =
            if (optedIn && setting == BIKRAM_SAMBAT) BIKRAM_SAMBAT else GREGORIAN
    }
}

/**
 * A half-open range of days, `[start, endExclusive)`, plus a human label.
 *
 * Half-open because it makes the database query trivially correct — no
 * "is the last day inclusive?" bug at month boundaries.
 */
data class DateWindow(
    val start: LocalDate,
    val endExclusive: LocalDate,
    val label: String,
) {
    val lastDay: LocalDate get() = endExclusive.minusDays(1)
    val dayCount: Int get() = (endExclusive.toEpochDay() - start.toEpochDay()).toInt()

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive

    /** How many days of this window have already happened, at least 1. */
    fun elapsedDaysAsOf(today: LocalDate): Int = when {
        today < start -> 0
        today >= endExclusive -> dayCount
        else -> (today.toEpochDay() - start.toEpochDay()).toInt() + 1
    }
}

/**
 * Turns dates into the words the user sees, in whichever calendar they picked.
 *
 * One object owns every date string in the app so a calendar switch cannot
 * leave half the screens showing the other system.
 */
class DateDisplay(
    val system: CalendarSystem,
    private val locale: Locale = Locale.getDefault(),
) {
    /**
     * Bikram Sambat is always rendered in Devanagari, whatever language the app
     * interface is in.
     *
     * Someone who asks for the Nepali calendar wants the Nepali calendar — a BS
     * date in Latin script and Arabic numerals is a transliteration nobody
     * actually reads, and every printed patro shows it this way. The rest of the
     * interface stays in whatever language was chosen.
     */
    private val nepaliScript: Boolean = system == CalendarSystem.BIKRAM_SAMBAT

    private val gregorianFull = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val gregorianDayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    /**
     * "Aug 2026" — the month abbreviated, not spelled out.
     *
     * This heads the stepper on Home and the Timeline, which is a line already
     * carrying two arrows and, once the reader has stepped away from now, a way
     * back to it. Spelled out, "September 2026" left no room for the rest and was
     * clipped to "September 202" — the one line every figure below it is
     * relative to, losing its year. Nobody reads that heading for the *word*:
     * they read it to know which month they are looking at, and three letters
     * answer that as completely as nine.
     *
     * Bikram Sambat is untouched. Its month names are short already, and they
     * have no accepted abbreviation — "श्राव" is not a thing anybody writes.
     */
    private val gregorianMonthYear = DateTimeFormatter.ofPattern("MMM yyyy", locale)
    /**
     * The weekday, in Nepali on a Nepali page whatever language the interface is
     * in — the same rule the dates themselves follow, and for the same reason:
     * somebody reading a patro reads शनिबार, and "Saturday" over a Devanagari
     * date is half a translation. The names come from the platform's own Nepali
     * data rather than a list kept here, which is where every other month and
     * day name in this app comes from.
     */
    private val weekday = DateTimeFormatter.ofPattern(
        "EEEE",
        if (nepaliScript) NEPALI else locale,
    )
    /**
     * The two halves of [dayAndMonth], available separately.
     *
     * Formatted through the locale exactly as the joined form is, so a day drawn
     * on its own and the same day drawn inside a fuller date cannot come out in
     * two different sets of digits on the same page.
     */
    private val gregorianDay = DateTimeFormatter.ofPattern("d", locale)
    private val gregorianMonth = DateTimeFormatter.ofPattern("MMM", locale)

    /** Falls back to Gregorian outside the BS table rather than throwing at the user. */
    private fun useBs(date: LocalDate): Boolean =
        system == CalendarSystem.BIKRAM_SAMBAT && BikramSambat.supports(date)

    private fun number(value: Int): String =
        if (nepaliScript) BikramSambat.toNepaliDigits(value) else value.toString()

    /** "26 Jul 2026" or "१० श्रावण २०८३" */
    fun full(date: LocalDate): String =
        if (useBs(date)) {
            val bs = BikramSambat.fromGregorian(date)
            "${number(bs.day)} ${BikramSambat.monthName(bs.month, nepaliScript)} ${number(bs.year)}"
        } else {
            date.format(gregorianFull)
        }

    /** "26 Jul" or "१० श्रावण" — for day headers where the year is obvious. */
    fun dayAndMonth(date: LocalDate): String =
        if (useBs(date)) {
            val bs = BikramSambat.fromGregorian(date)
            "${number(bs.day)} ${BikramSambat.monthName(bs.month, nepaliScript)}"
        } else {
            date.format(gregorianDayMonth)
        }

    /**
     * "26" or "१०" — the day of the month alone, for a list that draws the date
     * as a figure in its own margin rather than as a line of words.
     *
     * In whichever calendar is being read, which is the whole point: on a page
     * of Bikram Sambat the big figure a reader counts days by has to be the
     * Nepali one, in the digits the rest of that date is printed in. Where a
     * date falls outside the BS table the calendar has already fallen back to
     * Gregorian for that day — see [useBs] — and this falls back with it, rather
     * than printing a Gregorian day in Devanagari.
     */
    fun dayNumber(date: LocalDate): String =
        if (useBs(date)) number(BikramSambat.fromGregorian(date).day) else date.format(gregorianDay)

    /** "Jul" or "श्रावण" — the other half of [dayAndMonth]. */
    fun monthName(date: LocalDate): String =
        if (useBs(date)) {
            BikramSambat.monthName(BikramSambat.fromGregorian(date).month, nepaliScript)
        } else {
            date.format(gregorianMonth)
        }

    /** "July 2026" or "श्रावण २०८३" — the heading above a month's figures. */
    fun monthAndYear(date: LocalDate): String =
        if (useBs(date)) {
            val bs = BikramSambat.fromGregorian(date)
            "${BikramSambat.monthName(bs.month, nepaliScript)} ${number(bs.year)}"
        } else {
            date.format(gregorianMonthYear)
        }

    fun weekdayName(date: LocalDate): String = date.format(weekday)

    /**
     * The Gregorian date, for showing in small type beside a Nepali one — the
     * way a printed patro prints the English date in the corner of each cell.
     *
     * Null in Gregorian mode, where it would just repeat the primary date.
     */
    fun secondary(date: LocalDate): String? =
        if (useBs(date)) date.format(gregorianFull) else null

    /** Short form of [secondary], for day headers where space is tight. */
    fun secondaryShort(date: LocalDate): String? =
        if (useBs(date)) date.format(gregorianDayMonth) else null

    /**
     * The Gregorian span a Bikram Sambat month covers, e.g. "17 Jul – 16 Aug".
     * A BS month straddles two Gregorian ones, so a single month name would be
     * a lie in one direction or the other.
     */
    fun secondaryRange(window: DateWindow): String? {
        if (system != CalendarSystem.BIKRAM_SAMBAT) return null
        val start = window.start.format(gregorianDayMonth)
        val end = window.lastDay.format(gregorianDayMonth)
        val year = window.lastDay.year
        return "$start – $end $year"
    }

    /**
     * The month [date] falls in, as a window over Gregorian days.
     *
     * In BS mode this is a Nepali month, so it will start mid-way through a
     * Gregorian one — which is the entire point of the setting for someone whose
     * salary and rent follow the Nepali month.
     */
    fun monthWindow(date: LocalDate): DateWindow =
        if (useBs(date)) {
            val bs = BikramSambat.fromGregorian(date)
            val start = BikramSambat.startOfMonth(bs.year, bs.month)
            val end = start.plusDays(BikramSambat.daysInMonth(bs.year, bs.month).toLong())
            DateWindow(start, end, monthAndYear(start))
        } else {
            val start = date.withDayOfMonth(1)
            val end = start.plusMonths(1)
            DateWindow(start, end, monthAndYear(start))
        }

    /** The month window immediately before [window]. */
    fun previousMonth(window: DateWindow): DateWindow = monthWindow(window.start.minusDays(1))

    /** The month window immediately after [window]. */
    fun nextMonth(window: DateWindow): DateWindow = monthWindow(window.endExclusive)

    private companion object {
        /** For the words a Nepali page is written in — see [weekday]. */
        val NEPALI: Locale = Locale.forLanguageTag("ne-NP")
    }
}
