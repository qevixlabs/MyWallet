package com.mywallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mywallet.R
import com.mywallet.core.date.BikramSambat
import com.mywallet.core.date.BsDate
import com.mywallet.core.date.CalendarSystem
import com.mywallet.ui.LocalAppSettings
import com.mywallet.ui.LocalDateDisplay
import dev.shivathapaa.nepalidatepickerkmp.NepaliDatePickerDialog
import dev.shivathapaa.nepalidatepickerkmp.NepaliDatePickerWithEnglishDate
import dev.shivathapaa.nepalidatepickerkmp.NepaliSelectableDates
import dev.shivathapaa.nepalidatepickerkmp.data.CustomCalendar
import dev.shivathapaa.nepalidatepickerkmp.data.NepaliDateLocale
import dev.shivathapaa.nepalidatepickerkmp.data.NepaliDatePickerLang
import dev.shivathapaa.nepalidatepickerkmp.data.SimpleDate
import dev.shivathapaa.nepalidatepickerkmp.rememberNepaliDatePickerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Opens whichever calendar grid the user reads.
 *
 * Showing a Gregorian grid to someone who has asked for Bikram Sambat makes them
 * do the conversion in their head, which is the whole thing the setting exists to
 * avoid. Shared so that every date in the app is picked the same way.
 */
@Composable
fun WalletDatePicker(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    /**
     * The earliest day that may be chosen, or null where any day will do.
     *
     * Where one date is measured from another — a first instalment from the day
     * the money arrived, a repayment from the day the debt began — the calendar
     * is the cheapest place to say so, and the only one that says it before the
     * mistake is made. Days before it are drawn greyed rather than removed, so
     * the reader can see where the floor is instead of wondering why the month
     * looks short.
     */
    minDate: LocalDate? = null,
    /**
     * The latest day that may be chosen, or null where the future is a real
     * answer — which it usually is, since a payment can be dated forward.
     *
     * Where it is not: a day that has not happened cannot be the day something
     * already did. Drawn exactly as the floor is, greyed rather than removed.
     */
    maxDate: LocalDate? = null,
) {
    val settings = LocalAppSettings.current
    if (settings.calendarSystem == CalendarSystem.BIKRAM_SAMBAT &&
        BikramSambat.supports(selected)
    ) {
        NepaliDatePickerSheet(
            selected = selected,
            onPick = onPick,
            onDismiss = onDismiss,
            minDate = minDate,
            maxDate = maxDate,
        )
    } else {
        GregorianDatePickerSheet(
            selected = selected,
            onPick = onPick,
            onDismiss = onDismiss,
            maxDate = maxDate,
            minDate = minDate,
        )
    }
}

/**
 * A date drawn as something to tap: a bordered box with a calendar icon,
 * shaped like every other input on the form.
 *
 * It used to be a bare text button, which read as a caption rather than a
 * control — beside the quick-pick chips it was the least visible thing on the
 * row and the only one that could actually choose a date.
 */
@Composable
fun DatePickerBox(
    date: LocalDate?,
    placeholder: String,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /** The earliest day offered — see [WalletDatePicker]. */
    minDate: LocalDate? = null,
    /** The latest day offered — see [WalletDatePicker]. */
    maxDate: LocalDate? = null,
) {
    val dates = LocalDateDisplay.current
    var picking by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            // The same paper a live text field is drawn on. Bordered but
            // unfilled, it sat flat on the page beside a column of filled
            // boxes, which is exactly how this form draws a *settled* fact —
            // so the one control on the row that opens a calendar looked like
            // the one thing on it that could not be touched.
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = date?.let { dates.full(it) } ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
            )
            date?.let { dates.secondary(it) }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (picking) {
        WalletDatePicker(
            // Today, unless the floor is still ahead of it — a grid opened on a
            // month where every cell is greyed says nothing about where the
            // answers are.
            selected = date
                ?: (minDate?.coerceAtLeast(LocalDate.now()) ?: LocalDate.now())
                    .let { opening -> maxDate?.let { opening.coerceAtMost(it) } ?: opening },
            onPick = onPick,
            onDismiss = { picking = false },
            minDate = minDate,
            maxDate = maxDate,
        )
    }
}

/**
 * A labelled date, tapped to change it.
 *
 * [onClear] makes the date optional: when it is given the row offers a way back
 * to "not set", because a date field with no way to empty it turns an optional
 * answer into a compulsory one the moment it is touched.
 */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    placeholder: String,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    /** The earliest day offered — see [WalletDatePicker]. */
    minDate: LocalDate? = null,
    /** The latest day offered — see [WalletDatePicker]. */
    maxDate: LocalDate? = null,
) {
    Column(modifier = modifier) {
        SectionHeader(title = label)
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            DatePickerBox(
                date = date,
                placeholder = placeholder,
                onPick = onPick,
                modifier = Modifier.weight(1f),
                minDate = minDate,
                maxDate = maxDate,
            )
            if (date != null && onClear != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
            }
        }
    }
}

/** The Bikram Sambat calendar grid, from the nepali-date-picker library. */
@Composable
private fun NepaliDatePickerSheet(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate?,
    maxDate: LocalDate? = null,
) {
    val bs = remember(selected) { BikramSambat.fromGregorian(selected) }
    // The bounds, asked in the calendar being drawn. The library hands each cell
    // back as a Bikram Sambat date, so the comparison is made after converting
    // it — comparing the two calendars' numbers directly would compare a Baisakh
    // to an April.
    val bounds = remember(minDate, maxDate) {
        if (minDate == null && maxDate == null) {
            null
        } else {
            object : NepaliSelectableDates {
                override fun isSelectableDate(customCalendar: CustomCalendar): Boolean {
                    val day = BikramSambat.toGregorian(
                        BsDate(
                            customCalendar.year,
                            customCalendar.month,
                            customCalendar.dayOfMonth,
                        )
                    )
                    return (minDate == null || !day.isBefore(minDate)) &&
                        (maxDate == null || !day.isAfter(maxDate))
                }

                // A whole year is offered whenever any day in it is, which is
                // the year each bound falls in and everything between them.
                override fun isSelectableYear(year: Int): Boolean =
                    (minDate == null || year >= BikramSambat.fromGregorian(minDate).year) &&
                        (maxDate == null || year <= BikramSambat.fromGregorian(maxDate).year)
            }
        }
    }
    // Always Devanagari, even when the app is in English — asking for the Nepali
    // calendar and getting Latin transliteration is not what anyone means.
    val nepaliLocale = remember { NepaliDateLocale(language = NepaliDatePickerLang.NEPALI) }
    // The English side stays in English: the whole point of the second date is
    // that it is the one you cross-reference against everything else.
    val englishLocale = remember { NepaliDateLocale(language = NepaliDatePickerLang.ENGLISH) }
    // The years offered start at the floor's own, so the arrows and the year
    // list stop where the answers do. Greying the days is not enough on its own:
    // a floor a fortnight old leaves years of months that are entirely dead, and
    // a reader stepping back through them has no way to tell whether they have
    // gone too far or simply not far enough. Months *within* the floor's year
    // are still reachable and still greyed, which is what Material's own
    // minimum-date pickers do.
    val years = remember(minDate, maxDate) {
        val first = minDate
            ?.takeIf { BikramSambat.supports(it) }
            ?.let { BikramSambat.fromGregorian(it).year }
            ?.coerceIn(BikramSambat.MIN_YEAR, BikramSambat.MAX_YEAR)
            ?: BikramSambat.MIN_YEAR
        val last = maxDate
            ?.takeIf { BikramSambat.supports(it) }
            ?.let { BikramSambat.fromGregorian(it).year }
            ?.coerceIn(first, BikramSambat.MAX_YEAR)
            ?: BikramSambat.MAX_YEAR
        first..last
    }
    val pickerState = rememberNepaliDatePickerState(
        initialSelectedDate = SimpleDate(bs.year, bs.month, bs.day),
        yearRange = years,
        nepaliSelectableDates = bounds ?: object : NepaliSelectableDates {},
        locale = nepaliLocale,
    )

    NepaliDatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // Null when the user opened the dialog and confirmed without
                // touching a day — keep whatever was already selected.
                pickerState.selectedDate?.let { picked ->
                    onPick(
                        BikramSambat.toGregorian(
                            BsDate(picked.year, picked.month, picked.dayOfMonth)
                        )
                    )
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        // The title is drawn here, outside the picker, and the picker's own
        // header slots are removed outright. With a title or headline the
        // library reserves the full Material header box, so an emptied
        // headline still held a blank stripe where the date used to be —
        // the space only goes when the header does. The grid needs no
        // headline anyway: every cell already says the day in both
        // calendars, and hand-typing a date the calendar can simply be
        // tapped on was a mode nobody needed.
        Column {
            Text(
                // In whatever language the app is in — the calendar *content*
                // is always Nepali, but the chrome follows the app.
                text = stringResource(R.string.date_select_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
            )
            NepaliDatePickerWithEnglishDate(
                state = pickerState,
                englishDateLocale = englishLocale,
                title = null,
                headline = null,
                showModeToggle = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GregorianDatePickerSheet(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate?,
    maxDate: LocalDate? = null,
) {
    // UTC throughout: the picker deals in millis, and any local offset can push
    // the chosen day onto its neighbour.
    val bounds = remember(minDate, maxDate) {
        if (minDate == null && maxDate == null) {
            null
        } else {
            val from = minDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            val to = maxDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    (from == null || utcTimeMillis >= from) &&
                        (to == null || utcTimeMillis <= to)

                override fun isSelectableYear(year: Int) =
                    (minDate == null || year >= minDate.year) &&
                        (maxDate == null || year <= maxDate.year)
            }
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        // Same reason as the Nepali grid: the years on offer start where the
        // answers do, so paging back does not walk through dead ones — and stop
        // where they do, so paging forward does not either.
        yearRange = remember(minDate, maxDate) {
            val default = DatePickerDefaults.YearRange
            val first = minDate?.year?.coerceIn(default.first, default.last) ?: default.first
            val last = maxDate?.year?.coerceIn(first, default.last) ?: default.last
            first..last
        },
        selectableDates = bounds ?: DatePickerDefaults.AllDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}
