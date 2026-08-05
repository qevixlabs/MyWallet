package com.mywallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mywallet.R
import com.mywallet.ui.theme.DayLabelStyle
import com.mywallet.ui.theme.WalletTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.foundation.layout.statusBarsPadding

/** U+202F. Named because an invisible literal in source is a maintenance trap. */
private const val NARROW_NO_BREAK_SPACE = '\u202F'

/**
 * An amount, rendered as one unbreakable object.
 *
 * An earlier version set the currency symbol smaller and dimmer than the
 * digits, which looked better but measured badly: the mixed-size line's
 * intrinsic width came out narrower than the width it actually needed to draw,
 * so amounts silently truncated to "रू 8…" or wrapped to "रू 1,62" / "0". A
 * money app showing a confidently wrong number is worse than a plain one, so
 * the symbol and the digits are now set at the same size, joined by a
 * no-break space so no layout pass can ever separate them.
 */
@Composable
fun MoneyText(
    formatted: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * Shrink the type rather than wrap or truncate when the amount is too wide.
     *
     * Needed for the headline: Devanagari numerals are noticeably wider than
     * Latin ones, so "रू ३,२३०" at 40sp overflows a card that fits "रू 3,230"
     * comfortably.
     */
    autoShrink: Boolean = false,
) {
    val text = remember(formatted) {
        AnnotatedString(formatted.replace(' ', NARROW_NO_BREAK_SPACE))
    }

    if (autoShrink) {
        BasicText(
            text = text,
            style = style.merge(color = color),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = style.fontSize * 0.5f,
                maxFontSize = style.fontSize,
                stepSize = 1.sp,
            ),
            modifier = modifier,
        )
    } else {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = modifier,
        )
    }
}

/**
 * Both figures of a transfer that crossed currencies — "$900 → रू 1,38,587".
 *
 * The arrow is drawn rather than typed. As a character it comes from whichever
 * font on the phone happens to carry it, which is not the one setting the
 * digits beside it: it landed on the wrong optical line and read as though it
 * had slipped to the bottom of the row. An icon is measured and centred against
 * the text it sits between, so the three parts read as one figure.
 */
@Composable
fun MoneyRoute(
    lead: String,
    partner: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MoneyText(formatted = lead, style = style, color = color)
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowRightAlt,
            contentDescription = null,
            tint = color,
            // Sized from the type rather than fixed, so the arrow keeps its
            // proportion wherever the pair is drawn.
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(with(LocalDensity.current) { style.fontSize.toDp() * 1.1f }),
        )
        MoneyText(formatted = partner, style = style, color = color)
    }
}

/**
 * The character a route is written with — "Wise → Nabil Bank".
 *
 * Written into the strings the view models and rows build, and swapped for a
 * drawn arrow by [RouteText] at the moment they are set. Kept as one named
 * constant so the two ends cannot drift apart.
 */
const val ROUTE_ARROW: Char = '→'

private const val ARROW_INLINE_ID = "route-arrow"

/**
 * A line of text that may contain a route, with the arrow drawn rather than set.
 *
 * As a character U+2192 comes from whichever font on the phone happens to carry
 * it, which is not the one setting the words around it: it sat low and small
 * against Devanagari and Latin alike and read as a glyph that had slipped to the
 * bottom of the line. The same fix [MoneyRoute] makes for figures — an icon is
 * measured against the text it sits between — done inline so it survives being
 * joined into a subtitle with everything else the row has to say.
 */
@Composable
fun RouteText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    if (!text.contains(ROUTE_ARROW)) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val annotated = remember(text) {
        buildAnnotatedString {
            text.split(ROUTE_ARROW).forEachIndexed { index, part ->
                if (index > 0) appendInlineContent(ARROW_INLINE_ID, ROUTE_ARROW.toString())
                append(part)
            }
        }
    }
    val arrowTint = color.takeIf { it != Color.Unspecified }
        ?: style.color.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current
    Text(
        text = annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent = mapOf(
            ARROW_INLINE_ID to InlineTextContent(
                // Sized in em so the arrow keeps its proportion wherever the
                // line is drawn, and centred on the text rather than the
                // baseline — an arrow sitting on the baseline reads as pointing
                // downwards, which is the whole complaint.
                Placeholder(
                    width = 1.4.em,
                    height = 1.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowRightAlt,
                    contentDescription = null,
                    tint = arrowTint,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        ),
        modifier = modifier,
    )
}

/** Small uppercase eyebrow above a section. Tracks wide so it reads as a label. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    /**
     * Whether a rule is drawn above the heading.
     *
     * A long form is a stack of unlike questions — what it is, what it earns,
     * which account it is paid from, what it looks like — separated by nothing
     * but a gap, and a gap is also what separates a field from the sentence
     * explaining it. The rule is what says a *new* question starts here.
     *
     * It is the same rule and the same gap the card footers use
     * ([CardFooterAction]): one distance in the app between a rule and the thing
     * under it, so a section heading and a way out of a card are not two
     * different-looking separations. **Not on the first section of a form** —
     * there is nothing above it to be separated from, and under an app bar the
     * rule reads as part of the bar.
     */
    divider: Boolean = false,
    /**
     * The mark of what this section is a section of, ahead of the words.
     *
     * A page of these is a column of short grey capitals that differ only in
     * which word they spell, and a reader looking for one of them has to read
     * the others to rule them out. The glyph is what lets the eye land on the
     * right one without doing that.
     *
     * Sized to the cap height of the label rather than to an icon's usual 24,
     * and in the label's own ink: it is one word of the heading, not a control
     * beside it. No content description — the heading says the same thing in
     * words immediately after it.
     *
     * Null wherever the heading is the only one on its page, or is a sub-heading
     * inside a section that already carries a mark: a second glyph under the
     * first says the two are unrelated things.
     */
    icon: ImageVector? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (divider) {
            Hairline()
            Spacer(Modifier.height(FOOTER_GAP))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/**
 * The day a block of rows falls on, written in the margin of the list.
 *
 * Smaller than the rows underneath it and italic, because a day is not one of
 * the things in the list — it is where they are written down. At the same size
 * and weight as an entry it read as that day's first row, and a scroll through a
 * busy month had no rhythm at all.
 *
 * One definition, used by every list that groups by day: a month's real entries
 * and the days still to come are the same list read in two directions, and a
 * heading that was quiet on one and loud on the other made them look like two
 * different pages. [secondary] is the weekday and the other calendar's date,
 * quieter again.
 */
@Composable
fun DayLabel(
    primary: String,
    secondary: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = primary,
            // One named style, so the figures drawn beside this on the same
            // heading can be set in exactly it — see [DayTotalStyle].
            style = DayLabelStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        secondary?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The name of a block of rows that is not a list of movements — "Accounts",
 * "What you owe".
 *
 * Set larger and heavier than [SectionHeader]'s eyebrow, and in sentence case
 * rather than uppercase, because these headings separate the two halves of the
 * timeline: everything above is what happened on a day, everything below is
 * where it leaves each holding. As small grey capitals they read as one more
 * caption in a list of rows, and the page had no visible seam.
 *
 * The seam itself is no longer a rule under the log: each block below it is a
 * card of its own now, which says where one block ends and the next begins
 * without a line drawn across the page to say it twice.
 */
/**
 * How far a [GroupHeader]'s words sit from its left edge when it carries a mark
 * — the glyph plus the gap after it. Named so anything drawn under the words can
 * line up with them instead of with the glyph.
 */
val GROUP_HEADER_GUTTER = 26.dp

/** The mark's own size, the rest of the gutter being the gap after it. */
private val GROUP_HEADER_ICON = 18.dp

@Composable
fun GroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * The mark of what this block is a block of.
     *
     * The same glyph the Accounts tab heads the same holdings with, because
     * these are the same holdings: a bank found by its mark on one tab has to
     * be found by that mark on the other, exactly as it is found by its colour.
     * In the quiet ink rather than the heading's own, so it stays a mark beside
     * a title instead of a second word in it.
     */
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(GROUP_HEADER_ICON),
            )
            Spacer(Modifier.width(GROUP_HEADER_GUTTER - GROUP_HEADER_ICON))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * How many rows of a statement are drawn before the reader has to ask for more.
 *
 * A debt that has been running for years, or a salary account a decade old, has
 * hundreds of movements behind it, and the answer somebody opens the list for is
 * almost always in the last fortnight. Composing all of them to reach it costs a
 * visible pause on the older holdings — and the page they land on is a scrollbar
 * the width of a hair, which says nothing about where in it they are.
 */
const val PAGE_SIZE = 15

/**
 * The way to the next page of a list.
 *
 * Deliberately the same thing the currency row's "Show more" is, and for the same
 * reason: it is an action rather than one more row, so it is the words in the
 * colour that means "this does something", with no border of its own. A button
 * with a fill would read as the end of the list rather than as a way past it.
 */
@Composable
fun SeeMore(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.see_more),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            // A thumb's worth of target, since this one sits at the foot of a
            // list with nothing below it to aim at instead.
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * The way *out* of a card, at its foot: a rule, then one full-width action.
 *
 * Two cards end this way — a holding's, whose transactions are a page of their
 * own, and a debt's, whose payments are — and they are the same offer, so they
 * are drawn by the same code. The rule is what says the card has stopped stating
 * figures and started offering somewhere to go; without it each read as one more
 * line of the balance above.
 *
 * **The spacing is symmetric about the rule**, which is the whole reason this is
 * a component and not two hand-built footers. Both were built out of a spacer, a
 * rule and a `TextButton`, and a `TextButton` carries a 40dp minimum height plus
 * the card's own bottom padding underneath it — so the rule sat a dozen pixels
 * below the figures and sixty above the card's edge, and the footer read as a
 * band of empty paper with a word floating in it. One [FOOTER_GAP] above the
 * rule, one below it, and one under the words: the caller's card therefore has
 * to give up its **bottom** padding, which is what [cardWithFooter] is for.
 *
 * The chevron points *along* rather than down, because this opens a page. It was
 * an expand/collapse arrow on the holding's card while the list was drawn inline.
 */
@Composable
fun CardFooterAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(FOOTER_GAP))
    Hairline()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = FOOTER_GAP),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The one distance in the app between a rule and what it separates — a card's
 * footer action, and a section heading inside a form. Two separations drawn at
 * two gaps read as two different ideas.
 */
internal val FOOTER_GAP = 14.dp

/**
 * What a card ending in a [CardFooterAction] passes as its content padding: its
 * usual inset, less the bottom, which the footer supplies itself so the rule
 * sits the same distance from the figures above it as the words below it sit
 * from the card's edge.
 */
fun cardWithFooter(inset: Dp): PaddingValues =
    PaddingValues(start = inset, top = inset, end = inset, bottom = 0.dp)

/**
 * The look of a field that can still be typed into: paper of its own on the
 * page's grey.
 *
 * The one visual job a form has is telling a live field from a settled one, and
 * for a while it did it backwards — the read-only boxes were filled and the
 * editable ones sat flat on the page. Fill means "write here"; everything else
 * is a label.
 *
 * Shared rather than private to the holding editor, which is where it started:
 * the money form's own boxes had none of it, so the two forms said opposite
 * things about the same kind of field two taps apart.
 */
@Composable
fun editableFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/**
 * The look of a chip that is an answer still to be given.
 *
 * Material draws an unselected chip as an outline on whatever is behind it,
 * which on a form is the page itself — so a row of currencies, accounts or
 * intervals read as a row of *disabled* pills beside the filled boxes above
 * them, and the one thing on the line that could be tapped looked like the one
 * thing that could not.
 *
 * So it takes the same paper [editableFieldColors] gives a live box: fill means
 * "this is live", whether the answer is typed or tapped. Only the unselected
 * state is set — the selected one keeps Material's own container, which is what
 * tells the answer given from the answers on offer.
 */
@Composable
fun pickableChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/**
 * A row of chips that shows a few and keeps the rest behind one word.
 *
 * Four rows on the entry form ask a question with more answers than fit on a
 * phone — which account, which debt or goal, what for, and the same again at the
 * far end of a transfer. All of them wrapped into four or five lines of
 * near-identical pills, so picking the account somebody was always going to pick
 * meant reading past every other one they own.
 *
 * [items] arrives already in the order the user is likeliest to want it — see
 * `Shortlist.order` — and this draws the first [shortlist] of them. The rest
 * **follow** when asked for rather than being redrawn in another order:
 * expanding a list must not move what the eye is already on.
 *
 * The word is deliberately **not a chip**. Every other pill in the row is an
 * answer, and one shaped like them reading "Show more" is one more answer to
 * read past, which is the opposite of what it is there for. So it is what the
 * rest of the app makes an action out of: the words in the colour that means
 * "this does something", with no border of its own, centred against the chips
 * and padded to their height so what the thumb aims at is the size of what it
 * sits beside.
 *
 * There is no way back. Once every answer is on screen there is nothing left to
 * ask for, and folding them away again is a way back to a shorter list nobody
 * wants once they have gone past it.
 *
 * Saved rather than remembered, because the keyboard opening under the amount
 * field is a configuration change on some phones, and a row that folded itself
 * back up there would lose the chip the user had just gone looking for.
 */
@Composable
fun <T> ShortlistChips(
    items: List<T>,
    shortlist: Int,
    modifier: Modifier = Modifier,
    chip: @Composable (T) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) items else items.take(shortlist)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { item -> chip(item) }
        if (!showAll && items.size > shortlist) {
            Text(
                text = stringResource(R.string.accounts_currency_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showAll = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The coloured dot that stands in for a label everywhere it is mentioned. */
@Composable
fun LabelDot(
    color: Color?,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = color ?: MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
    )
}

/** A hairline divider that is lighter than Material's default. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(WalletTheme.colors.hairline)
    )
}

/**
 * A hairline with the ink taken out of it in even bites.
 *
 * Where [Hairline] separates two rows of one thing, this separates a heading
 * from what it is a heading *of* — the day and its payments. A solid rule there
 * says the two are unrelated; a broken one says the sheet carries on underneath.
 */
@Composable
fun DashedRule(
    modifier: Modifier = Modifier,
    color: Color = WalletTheme.colors.hairline,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
            ),
        )
    }
}

/** The height of the tear between two days, dashes and scissors together. */
val DAY_TEAR = 22.dp

/**
 * The tear between one day and the next.
 *
 * The Timeline is a log of what money did on which day, its own tab is marked
 * with a receipt, and each day already sits on a sheet of its own paper — so the
 * gap between two sheets is where one receipt ends and the next begins. Drawn as
 * the thing that separates them on paper: a line of perforations with the mark
 * for cutting at the head of it.
 *
 * It replaces a plain 12dp gap, which said the days were apart without saying
 * they were days, and left a screenful of sheets reading as one long list that
 * happened to have breaks in it.
 *
 * **Once on the page, at the one join that is really a cut** — where the log of
 * the days ends and what the month leaves each holding begins. It was drawn
 * between every pair of days for a while, and at that scale it stopped saying
 * anything: a month of daily payments was a column of scissors, and a mark for
 * cutting on every join is a mark for cutting nowhere. A tear at the top or the
 * foot of the page is a sheet torn off nothing, so it is withheld at both.
 */
@Composable
fun Perforation(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DAY_TEAR)
            .padding(horizontal = LIST_PANEL_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCut,
            contentDescription = null,
            tint = WalletTheme.colors.hairline,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        DashedRule(modifier = Modifier.weight(1f))
    }
}

/**
 * The short rule between two movements of the same day.
 *
 * A day's block had nothing dividing it at all — three payments on one sheet of
 * paper ran together, and the only thing saying where one ended was the gap its
 * own padding left. A full-width rule is the wrong answer: that is what the day
 * heading uses, and repeating it between the rows would put the same division
 * between a day and its payments as between one payment and the next, so a day
 * of three would read as three days.
 *
 * What keeps it from reading as the end of the day is where it is drawn rather
 * than how long it is: inside the row's own padding, so it starts and stops where
 * the words do, while the day's own rule runs the full width of the paper it is
 * on. It ran a little over half the width at first, on the reasoning that a short
 * rule reads as a break inside a block — and a rule that stops in the middle of
 * nowhere reads as one that failed to finish. Full width across the words is the
 * same sentence said cleanly.
 */
@Composable
fun RowSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .clip(RoundedCornerShape(0.5.dp))
            .background(WalletTheme.colors.hairline)
    )
}

/**
 * The heading over a list on a page reached by a back arrow — a debt's
 * payments, a holding's transactions, a schedule.
 *
 * It was [SectionHeader], the small grey eyebrow a *form* separates its
 * questions with, and two things were wrong with that. It is 11sp uppercase in
 * the muted ink, so the one line saying what a whole page is a list of was set
 * smaller than every row beneath it — the same fault [GroupHeader] was written
 * to fix on the timeline, where small grey capitals over a block of rows read as
 * one more caption in the list rather than as the name of it. And it carries no
 * padding of its own, so each of the three pages supplied its own and they had
 * drifted to three different gaps above the card: 40dp, 28dp and 8dp, on three
 * pages built to look like each other.
 *
 * So it is [GroupHeader]'s type, and the spacing is **here** rather than at the
 * call sites. The margin is the page's own 20dp, which is where the paper below
 * it starts and where every heading on a tab already sits; the rows are inset
 * further because they are on the paper, which is a different thing from being
 * beside it.
 *
 * [explain] is the line some of these pages put under the heading — what the
 * list is, and what a swipe on it does. Part of the header because it is part of
 * the heading's block, and leaving it to the caller is what let the gap under it
 * drift too.
 */
@Composable
fun ListPageHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    explain: String? = null,
) {
    Column(
        modifier = modifier.padding(
            start = LIST_PANEL_INSET,
            end = LIST_PANEL_INSET,
            top = LIST_HEADER_TOP,
            bottom = if (explain == null) LIST_HEADER_BOTTOM else 4.dp,
        )
    ) {
        GroupHeader(title = title, icon = icon)
        explain?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Under the *words*, not under the mark. The glyph sits in a
                // gutter of its own, and a second line starting back at the page
                // margin left the block with two left edges a mark's width
                // apart — near enough to read as a mistake rather than as an
                // indent. Everything the heading says now lines up under the
                // heading.
                modifier = Modifier.padding(start = GROUP_HEADER_GUTTER),
            )
            Spacer(Modifier.height(LIST_HEADER_BOTTOM))
        }
    }
}

/**
 * One gap above every inner page's list heading, so the three cannot drift.
 *
 * Small, because the heading is not opening a new part of the page — it names
 * the rows directly under it, and the card above has already left its own margin
 * below. At 20 the two gaps added up to a band of empty page between the balance
 * and the list it explains, which reads as something missing rather than as a
 * separation.
 */
private val LIST_HEADER_TOP = 8.dp

/** And one under it, before the paper starts. */
private val LIST_HEADER_BOTTOM = 8.dp

/** The app's card surface. One definition so corners and elevation never drift. */
@Composable
fun WalletCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A swipe asking before it acts.
 *
 * One dialog for every list that removes something, because the question is the
 * same wherever it is asked and only the words change. A swipe is a gesture a
 * thumb makes by accident on the way past a row, and the lists it is offered on
 * are somebody's financial records: the delete that follows is worth a sentence
 * first even where an Undo follows it, since a snackbar is gone in four seconds
 * and a row nobody noticed leaving is one nobody thinks to bring back.
 *
 * The exception is the tutorial, where the app has just asked for the gesture
 * and a question about it would be the lesson interrupting itself.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * What the acting button says. Delete on a row that is really there; a date
     * still to come is *skipped*, and a button reading Delete beside a question
     * asking about skipping is the dialog disagreeing with itself.
     */
    confirmLabel: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel ?: stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Why a payment would not go.
 *
 * One dialog for the four lists that can remove a movement — the timeline, an
 * account's statement, a debt's own statement, the entry form — because it is one
 * answer, and four wordings of it would be four descriptions of the same rule.
 * A refused swipe springs back with nothing said, which reads as the gesture
 * having missed; this is the part the user can act on.
 */
@Composable
fun LaterPaymentFirstDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_blocked_title)) },
        text = { Text(stringResource(R.string.delete_blocked_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

/**
 * Swipe a row away, right to left.
 *
 * One direction only. Rows in this app are all tappable, and a two-way swipe on
 * a list of someone's financial records makes an accidental delete far too easy.
 *
 * [onSwiped] is *not* "it is gone": the box never settles into a dismissed
 * state, so what happens next — a soft delete with Undo behind it, or a dialog
 * asking first — belongs to the caller. Letting it settle leaves the row showing
 * red until the list catches up, and a row brought back by Undo comes back still
 * wearing it.
 *
 * [background] must be opaque and must match whatever the row sits on. The red
 * is always composed directly behind the content, so a transparent row shows it
 * at rest — which reads as a stuck delete no amount of swiping clears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(
    rowKey: Any,
    onSwiped: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    // Keyed on the row: LazyColumn reuses composables across rows, and a shared
    // state left the red background stuck open on whichever row landed in that
    // slot next.
    val state = key(rowKey) {
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) onSwiped()
                false
            },
            // Must travel half the row, so a stray horizontal movement while
            // scrolling cannot delete anything.
            positionalThreshold = { total -> total * 0.5f },
        )
    }

    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // fillMaxSize, not fillMaxWidth: the red must cover the row's full
            // height or it shows as a band floating behind the text.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        content = {
            Box(modifier = Modifier.fillMaxWidth().background(background)) { content() }
        },
    )
}

/**
 * An empty screen states what to do next, in the interface's voice.
 * It is an invitation, not an apology.
 *
 * [icon] is the mark of the list that is empty — the tab's own glyph, or the
 * one the rows it is waiting for would have carried. Two sentences centred in
 * the middle of an otherwise blank page read as an error message however
 * carefully they are worded, because a blank page with words on it is what an
 * error looks like; a quiet glyph above them is what says the page is working
 * and has nothing to show yet. It is drawn in a soft disc rather than alone,
 * or a single outlined shape floating over the fold reads as a control that
 * failed to load. Optional, and decorative: the two lines under it say the
 * whole of it in words, so it carries no content description.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(64.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

/**
 * A proportional rule under a breakdown row.
 *
 * Deliberately not a pie or donut: comparing lengths on a shared baseline is
 * easier than comparing angles, and it costs one line per row instead of a
 * legend the user has to cross-reference.
 */
@Composable
fun ShareBar(
    share: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(WalletTheme.colors.stripTrack, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(share.coerceIn(0f, 1f))
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

/**
 * Animates a form section into view the moment its condition becomes true.
 *
 * Only the entrance is animated. The sections this wraps sit in columns spaced
 * with `Arrangement.spacedBy`, and a settled-but-invisible AnimatedVisibility
 * would still occupy a slot there, doubling the gap — so the `if` around the
 * call keeps owning removal, and removal is instant. Appearing is where the
 * abruptness was: a tap on "Transfer" grew the form by four fields in a single
 * frame.
 */
@Composable
fun Reveal(content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visibleState = remember {
            androidx.compose.animation.core.MutableTransitionState(false)
        }.apply { targetState = true },
        enter = androidx.compose.animation.fadeIn(
            androidx.compose.animation.core.tween(220)
        ) + androidx.compose.animation.expandVertically(
            androidx.compose.animation.core.tween(
                260, easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            expandFrom = androidx.compose.ui.Alignment.Top,
        ),
    ) { content() }
}

/**
 * A length, read back with the unit it was given in: "1 months".
 *
 * The unit is part of the answer rather than a question of its own, so on a
 * field nobody is typing into it belongs inside the box beside the figure. While
 * the box *is* being typed into it comes back out, because a suffix sitting in
 * the middle of the digits is in the way of them.
 *
 * Shared rather than written twice: a loan's term, a deposit's, a policy's and
 * the interest interval in Settings are all the same question, and a unit that
 * read back one way on one screen and another way on the next would be two
 * widgets pretending to be one.
 */
fun termShown(value: String, unit: String, editing: Boolean): String =
    if (editing || value.isBlank()) value else "$value ${unit.lowercase()}"

/**
 * Months or years, offered only while the length beside them is being typed.
 *
 * They used to sit under every form permanently, restating on a filled-in field
 * what its own value already said. The choice is made once, in the moment the
 * number is entered, so that is the only moment they appear.
 *
 * [onKeepFocus] puts the cursor back in the box afterwards. Without it the chip
 * takes the focus, the row it is in closes behind the tap, and changing months
 * to years costs three taps instead of one.
 */
@Composable
fun TermUnitChips(
    inYears: Boolean,
    onPick: (Boolean) -> Unit,
    onKeepFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Reveal {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier,
        ) {
            androidx.compose.material3.FilterChip(
                selected = !inYears,
                onClick = { onPick(false); onKeepFocus() },
                label = { Text(stringResource(R.string.loan_term_months)) },
                colors = pickableChipColors(),
            )
            androidx.compose.material3.FilterChip(
                selected = inYears,
                onClick = { onPick(true); onKeepFocus() },
                label = { Text(stringResource(R.string.loan_term_years)) },
                colors = pickableChipColors(),
            )
        }
    }
}

/**
 * Whether this arrangement counts its months in the calendar the app is set to.
 *
 * The one control for a question asked in five places — a savings account's
 * interest periods, a debt's instalments, a repeating entry, a policy's premiums,
 * a goal's contributions — because it is one question and five wordings of it
 * would be five descriptions of the same rule.
 *
 * **Off does not mean "no calendar", it means the English one.** Reading dates in
 * Nepali is a preference; a bank closing its quarters on 1 Baisakh is a fact
 * about the arrangement, and most do not. So the line underneath names what is
 * actually being counted in rather than restating the switch, which is the half
 * of the answer a reader can act on.
 *
 * **And what it counts is the caller's to say** ([explain]). One control asked in
 * six places is one *question*, not one sentence: a goal has no EMI, a loan pays
 * no interest into anything, and a line naming every arrangement at once — "EMI
 * or interest calculation" — was a sentence at least half of which was about
 * some other form. Only the site knows which of the six it is, so only the site
 * can name it.
 *
 * Drawn as the switch row Settings uses for the app lock: the words are part of
 * the target rather than a caption beside it, so the whole row is what the thumb
 * aims at, and the explanation sits under both.
 */
@Composable
fun DefaultCalendarSwitch(
    checked: Boolean,
    /** What the months are actually counted in — English, or Nepali. */
    effectiveCalendarName: String,
    /** What this form counts in them, as a resource taking that name. */
    @androidx.annotation.StringRes explain: Int,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        ) {
            Text(
                text = stringResource(R.string.holding_interest_calendar),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(
            text = stringResource(explain, effectiveCalendarName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A little air under the line, wherever this row is drawn. The field
        // below it used to start immediately under the explanation, which read
        // as a caption belonging to that field rather than to the switch above
        // it. Small on purpose: the line and the switch are one control, so a
        // full gap here would break them apart to fix a lesser fault.
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * The faint band that tells one row from the next in a long list.
 *
 * Every other row, and nothing more: a movement's row is three lines of text
 * with a figure at the end, and once the colour beside it was gone — colour
 * belongs to a holding now — a page of them ran together. What separates them
 * is the band rather than a rule, because a rule between every pair is a
 * ladder and this has to be read past, not read.
 *
 * **The tint claims to mean nothing** — a row is not *about* anything by being
 * the second one — which is why it is a breath of the page's own ink rather
 * than a hue or a named surface.
 *
 * It is [WalletColors.rowBand] and therefore **one value per scheme**, which is
 * the part that was wrong for a long while: it was a single four-percent alpha
 * over `onSurface`, and four percent of white on a near-black page is a clear
 * step where four percent of near-black on cool paper is invisible. The value
 * read as scheme-independent and had in fact only ever been looked at in the
 * dark one, so the light scheme had no banding at all.
 *
 * Applied before the row's own padding, so the band runs to both edges of the
 * page. Inset it would read as a card, and a list of cards is a different page.
 *
 * **Not on the Timeline**, which is the one list already cut into days by a
 * sticky header with a rule under it: the band was a second grouping laid over a
 * page that had one, and the two did not line up. Every list that reads this is
 * one flat run of rows with nothing dividing it, which is where it earns its
 * keep — and it is why there is no longer a "band only from N rows" threshold,
 * since none of them group by anything.
 */
@Composable
fun rowStripe(index: Int): Color =
    if (index % 2 == 1) WalletTheme.colors.rowBand else Color.Transparent

/** What a [WalletCard] holds its content in from its own edges. */
val CARD_INSET = 20.dp

/**
 * Lets one row of a table inside a card paint out to the card's own edges.
 *
 * A band has to reach both edges of whatever it is banding — inset, it reads as
 * a stripe laid on the paper rather than as one row of it, which is the same
 * reason [rowStripe] goes on before a row's padding everywhere else. Inside a
 * card there is no "before": the card has already inset its content by
 * [CARD_INSET], and a row cannot pad itself by a negative amount.
 *
 * So the row is measured [CARD_INSET] wider at each end and placed back by that
 * much, while reporting the width it always had — the column above and below it
 * is laid out exactly as before, and only the paint spills. Pad the content back
 * in by the same amount afterwards, or the figures move out from under the
 * headings they belong to.
 *
 * Only safe in the middle of a card: the spill is a square rectangle and the
 * card's corners are round, so a row flush with the top or bottom would paint
 * outside them.
 */
fun Modifier.cardBleed(inset: Dp = CARD_INSET): Modifier = this.layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                constraints.maxWidth + extra
            },
        )
    )
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-inset.roundToPx(), 0)
    }
}

/** How far a list's paper is held off the sides of the page. */
val LIST_PANEL_INSET = 20.dp

/** What a row is inset from the paper's own edge, the way a card's rows are. */
val LIST_PANEL_ROW_INSET = 16.dp

/** The corner the paper is cut to — the radius every card in the app uses. */
private val LIST_PANEL_CORNER = 20.dp

/**
 * The paper a run of movements is laid on: Home's recent list, Reminders, and
 * each day of the timeline.
 *
 * Those three pages drew their rows straight onto the page, which gave a
 * screenful of movements no edge anywhere — the list was the same colour as the
 * background behind it, and the page read pale in both schemes. What says "these
 * rows are a list" is the sheet under them, in [WalletColors.listSurface]: a
 * shade set *into* the page rather than the white card the Accounts tab raises
 * off it, so the two pages stay two pages.
 *
 * Cut to the card radius at whichever ends are the ends of the list, and square
 * in the middle, so a lazy list can paint its paper one row at a time and still
 * come out as one sheet. That is the whole reason this is a modifier rather than
 * a wrapper: a `LazyColumn` cannot put a box around items it has not composed.
 *
 * The clip goes on before the fill, so a row's ripple stops at the rounded
 * corner instead of painting over it.
 *
 * @param paint false where the row paints its own background at the same colour
 *   — a swipeable row must, or the red shows through the gap it opens.
 */
@Composable
fun Modifier.listPanel(
    first: Boolean = true,
    last: Boolean = true,
    paint: Boolean = true,
): Modifier = this
    .padding(horizontal = LIST_PANEL_INSET)
    .clip(
        RoundedCornerShape(
            topStart = if (first) LIST_PANEL_CORNER else 0.dp,
            topEnd = if (first) LIST_PANEL_CORNER else 0.dp,
            bottomStart = if (last) LIST_PANEL_CORNER else 0.dp,
            bottomEnd = if (last) LIST_PANEL_CORNER else 0.dp,
        )
    )
    .then(if (paint) Modifier.background(WalletTheme.colors.listSurface) else Modifier)


/**
 * Where every alert in this app appears: the **top** of the page, not the foot.
 *
 * Material puts a snackbar at the bottom, and on this app that is the one place
 * it cannot go. The bottom of every tab is a navigation bar with a floating
 * button sitting on it, and the `Scaffold` stacks its snackbar directly above
 * that slot — so "Deleted" arrived under the reader's own thumb, on top of the
 * control they had just used, and the add menu opening six options tall shot it
 * halfway up the page (see [AddHoldingMenu], which had to be given zero height
 * for exactly this reason). A message about a row the user was reading also has
 * no business at the far end of the page from that row.
 *
 * At the top it is out of the thumb's way, clear of the button and the menu, and
 * in the corner the eye already goes to for the month and the page's own title.
 *
 * It is drawn **over** the page rather than in the `Scaffold`'s snackbar slot,
 * because that slot is positioned from the bottom edge: a host told to fill the
 * height is placed at `layoutHeight - itsOwnHeight - bottomBar`, which lands it
 * off the top of the screen by the height of the navigation bar. So the caller
 * wraps its `Scaffold` in a [Box] and hangs this off the top instead, which owes
 * nothing to how tall anything else is.
 *
 * Inset by the status bar alone. It deliberately floats over a top app bar where
 * there is one: a snackbar is a thing laid on the page for a moment, and pushing
 * the page's own title down to make room for it would move the layout under the
 * reader every time something was said.
 */
@Composable
fun TopSnackbar(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = state,
        modifier = modifier.statusBarsPadding(),
    )
}
