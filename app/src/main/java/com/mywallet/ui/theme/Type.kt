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
 * The day of the month, in the margin of the timeline.
 *
 * This is the one thing on a day's heading that is not a word, so it is the one
 * thing set upright and at a size worth landing on — [DayLabelStyle]'s italic is
 * what says "this is the margin, not a row", and a number leaning over reads as
 * a flourish rather than as a date.
 *
 * **Sized to the two lines beside it**, not to the rows below. The heading is a
 * date, a weekday and the month it falls in, stacked — and the figure is the
 * date *of* that stack, so it stands to its full height rather than sitting on
 * the first line of it. A step down from that, at the size of the payments
 * underneath, and it stopped being the thing the eye lands on: a month scrolled
 * past went back to being a column of near-identical captions, which is the one
 * problem it exists to solve. [lineHeight] is the two lines it spans — 16sp of
 * [DayLabelStyle] and 16sp of `labelSmall` — and the size is what fills them.
 *
 * It costs nothing in height: the figure is drawn across the heading without
 * contributing to it, so a day of one line is no taller for having a date at the
 * head of it. See [DayLabel].
 *
 * **Not tabular**, which is the one place in this app that figures are not.
 * Tabular figures are all one width so that columns of them line up, and the
 * width they share is the widest digit's, so a 1 carries empty space on either
 * side of it. There is no column here to line up — the date sits at the head of
 * its own heading with words after it — and what it needs is to be as wide as it
 * is.
 *
 * **And not tracked in, however tempting.** Devanagari digits carry generous
 * side bearings of their own, so "१६" is drawn with a visible gap down the
 * middle, and closing it with negative letter spacing is the obvious fix. It is
 * also a broken one: past about a point of it Android stops drawing the second
 * digit altogether, and a day of the 16th is headed "१" — a wrong date, which is
 * far worse than a loose one. Tested at −1sp and −2sp, and at the same amounts
 * written in `em`; all four lose the digit. What separates the figure from the
 * words instead is the space after it, which [DayLabel] sets wider than the
 * space inside it.
 */
val DayNumberStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontStyle = FontStyle.Normal,
    // Brought down from 26. It is the bullet a day's rows hang off and it still
    // has to be the thing the eye lands on when a month is scrolled past — but
    // at 26 it was competing with the amounts on the rows underneath it, which
    // are what the page is actually about.
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = 0.sp,
)

/**
 * A row's own name, in every list that draws movements.
 *
 * `bodyLarge` at 16sp, which is what this was, is a *reading* size — right for
 * a paragraph and a size too loud for a column of thirty rows, where the title
 * competed with the figure on the right rather than labelling it. Two points
 * down leaves the hierarchy intact: the line under it is still smaller, and the
 * ink is still darker than the line under it, which is what separates them.
 */
val RowTitleStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Normal,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)

/**
 * What a movement came to, beside the name of the movement.
 *
 * [MoneyRowStyle] at the title's own size, and that is the whole point: on a
 * log the two are one sentence — what happened, and what it cost — and a figure
 * a point larger than the thing it belongs to reads as the row's heading, with
 * the name demoted to a caption under it. Kept apart from [MoneyRowStyle]
 * itself, which still sizes the figures on the Accounts page and Home's
 * breakdown, where the amount genuinely is the heading of its row.
 *
 * The weight and the tabular figures come with it: a column of amounts has to
 * line up on the decimal whatever size it is drawn at.
 */
val RowAmountStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = TABULAR,
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
