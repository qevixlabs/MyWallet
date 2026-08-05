package com.mywallet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The typographic idea: **money is set in tabular figures, everywhere.**
 *
 * `tnum` gives every digit the same advance width, so a column of amounts lines
 * up on the decimal without any layout tricks — the difference between a list
 * that reads as a ledger and one that reads as ransom-note text. `lnum` keeps
 * lining figures so digits sit on the baseline at a consistent height.
 *
 * Large amounts also get negative tracking: default spacing makes big numerals
 * look loose and uncertain, and this is the number the user came to read.
 */
private const val TABULAR = "tnum, lnum"

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val WalletTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
            lineHeightStyle = TightLineHeight,
        ),
        headlineMedium = headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
        // Section eyebrows: small, wide-tracked, uppercase at the call site.
        labelSmall = labelSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
        ),
    )
}

/**
 * The name of the thing being looked at: a screen reached through a back arrow,
 * or a bank whose holdings are listed under it.
 *
 * One size for both, and deliberately smaller than the headline a tab uses for
 * its own name — a page you arrived at is subordinate to the page you left, and
 * a title as large as that one competed with it. The screens had drifted to
 * three different sizes besides, which read as three different kinds of screen;
 * the bank names had drifted the other way, into a grey caption so small that
 * nothing beneath it looked like it belonged to it.
 */
val TitleStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
)

/** The one true style for a headline amount. */
val MoneyHeadlineStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 40.sp,
    lineHeight = 46.sp,
    letterSpacing = (-1.2).sp,
    fontFeatureSettings = TABULAR,
)

/** Amounts in a list row — must align vertically with the rows above and below. */
val MoneyRowStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = TABULAR,
)

/**
 * The day a block of rows falls on, written in the margin of the list.
 *
 * Smaller than the rows underneath it and italic, because a day is not one of
 * the things in the list — it is where they are written down. Material's own
 * `bodySmall` metrics, stated here rather than read from the theme, so that the
 * figures beside it can be set in exactly the same words: see [DayTotalStyle].
 */
val DayLabelStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontStyle = FontStyle.Italic,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
)

/**
 * What a day came to, beside the day itself.
 *
 * [DayLabelStyle] with tabular figures, which is the one thing money never gives
 * up — the totals still have to line up down the column of days. It used to be
 * [MoneySmallStyle], a size larger and upright, so the loudest thing on a day's
 * heading was the pair of figures rather than the day: the heading is the margin
 * of the list, and both halves of it belong to the margin.
 */
val DayTotalStyle: TextStyle = DayLabelStyle.copy(fontFeatureSettings = TABULAR)

/** Supporting amounts: day totals, breakdown figures. */
val MoneySmallStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = TABULAR,
)
