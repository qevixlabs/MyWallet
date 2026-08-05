package com.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.mywallet.core.date.DateDisplay
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.settings.AppSettings
import com.mywallet.domain.Loan
import com.mywallet.ui.screens.LoanOutlook
import java.util.Locale

/**
 * Formatting is app-wide state, not per-screen state.
 *
 * If each screen built its own formatter, changing the currency or calendar
 * would update some screens and not others. Providing them once at the root
 * means one setting change re-renders everything that shows money or a date.
 */
val LocalMoneyFormatter = staticCompositionLocalOf {
    MoneyFormatter(CurrencyOption.NPR)
}

val LocalDateDisplay = staticCompositionLocalOf {
    DateDisplay(com.mywallet.core.date.CalendarSystem.GREGORIAN)
}

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

@Composable
fun ProvideFormatters(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    // Read from the configuration so a language change recreates the formatters:
    // Locale.getDefault() alone would not re-trigger composition.
    val locale: Locale = remember(configuration) {
        @Suppress("DEPRECATION")
        configuration.locales.get(0) ?: Locale.getDefault()
    }

    // The grouping follows the *calendar*, which is the app's own answer to
    // "who is reading this?" — see [DigitGrouping]. Everything else that formats
    // money takes its style from this one formatter rather than asking again.
    val moneyFormatter = remember(settings.currencyCode, settings.calendarSystem, locale) {
        MoneyFormatter(
            currency = CurrencyOption.byCode(settings.currencyCode),
            locale = locale,
            grouping = settings.calendarSystem.grouping,
        )
    }
    val dateDisplay = remember(settings.calendarSystem, locale) {
        DateDisplay(settings.calendarSystem, locale)
    }

    CompositionLocalProvider(
        LocalMoneyFormatter provides moneyFormatter,
        LocalDateDisplay provides dateDisplay,
        LocalAppSettings provides settings,
        content = content,
    )
}

/**
 * The formatter a loan's figures should be printed with.
 *
 * A debt in dollars is a dollar debt unless the user said otherwise, so the
 * loan's own currency is the default and [base] is used only when they asked for
 * it and a rate existed. Paired with [outstandingShown] and friends, which pick
 * the matching figure — the two must always be chosen together, or a converted
 * number gets a foreign symbol.
 */
@Composable
fun Loan.formatter(base: MoneyFormatter): MoneyFormatter =
    if (readInBase) base else remember(currencyCode, base) { base.forCurrency(currencyCode) }

val Loan.outstandingShown: Money get() = if (readInBase) outstandingInBase!! else outstanding

/**
 * What it would take to be done with this debt today, in the currency the row is
 * printed in.
 *
 * This is the figure a list gives a debt when it can only give it one: the
 * balance on its own leaves out interest the user will have to hand over with
 * it, and on a debt that has been sitting for months that gap is the whole
 * point of having a rate on file. Identical to [outstandingShown] on anything
 * with a schedule, where the interest lives inside the instalments.
 */
val Loan.settleShown: Money
    get() = if (readInBase) settleTodayInBase ?: outstandingInBase!! else settleToday

/**
 * A projected balance in the same currency the row is printed in.
 *
 * Converted at the rate that produced [Loan.outstandingInBase], so the projected
 * figure and the current one are comparable — a projection converted at some
 * other rate would look like the debt had moved when only the rate had.
 */
val LoanOutlook.shownAfter: Money
    get() {
        if (!loan.readInBase) return after
        val current = loan.outstanding.minor
        if (current == 0L) return loan.outstandingInBase ?: after
        val rate = (loan.outstandingInBase ?: return after).minor.toDouble() / current
        return Money(kotlin.math.round(after.minor * rate).toLong())
    }

/**
 * What a debt read in its own currency comes to in [base]'s, or null when the
 * row already prints that figure.
 *
 * The same two-line shape an entry row uses: the currency the debt is actually
 * in on top, what it is worth underneath.
 */
fun Loan.convertedOutstanding(base: MoneyFormatter): Money? {
    if (readInBase) return null
    if (currencyCode.equals(base.currencyCode, ignoreCase = true)) return null
    return outstandingInBase
}

/**
 * The same projected figure in [base]'s currency, or null when the row is
 * already printing one.
 *
 * A debt in dollars that the user reads in dollars still has to say what it
 * comes to in the currency the totals beside it are in — the same two-line shape
 * an entry row uses. Converted at the rate that produced [Loan.outstandingInBase]
 * rather than a fresh one, so the two figures on the row describe one moment.
 */
fun LoanOutlook.convertedAfter(base: MoneyFormatter): Money? {
    if (loan.readInBase) return null
    if (loan.currencyCode.equals(base.currencyCode, ignoreCase = true)) return null
    val inBase = loan.outstandingInBase ?: return null
    val current = loan.outstanding.minor
    if (current == 0L) return inBase
    val rate = inBase.minor.toDouble() / current
    return Money(kotlin.math.round(after.minor * rate).toLong())
}

/**
 * What the outstanding figure is measured against on a row.
 *
 * On an overdraft that is the approved limit, not the balance: "रू 5,00,000 of
 * रू 8,00,000" says how much headroom is left, while measuring the drawn amount
 * against itself would always read as fully used.
 */
val Loan.principalShown: Money
    get() = if (isOverdraft) {
        (if (readInBase) creditLimitInBase else null) ?: creditLimit ?: principal
    } else {
        // What was borrowed, not what is left of it: a lump sum rewrites
        // [principal] in place, so on a debt that has had one it is the balance
        // over again — and "रू 95,000 of रू 95,000" beside a debt of रू 1,00,000
        // told the borrower they had borrowed less than they did.
        (if (readInBase) principalInBase else null) ?: borrowedInAll ?: principal
    }

val Loan.emiShown: Money? get() = if (readInBase) emiInBase ?: emi else emi

/**
 * The rate this debt charges *now*, which is not always the one it opened at.
 *
 * A debt written down as a bare amount and given a rate afterwards has nothing
 * on the holding itself — everything it charges is in the dated changes — so a
 * row reading only the opening figure said "no interest" about a loan that had
 * been charging for months.
 */
val Loan.rateShown: Double? get() = (rates?.latest() ?: annualRate)?.takeIf { it > 0.0 }
