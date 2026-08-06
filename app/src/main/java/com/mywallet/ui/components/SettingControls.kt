package com.mywallet.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mywallet.R
import kotlinx.coroutines.flow.first

/**
 * One setting: what it is called, and the controls that answer it.
 *
 * Two screens ask settings — the Settings tab and the questions put on the way
 * in — and they are the same questions, so they are drawn by the same code. A
 * currency chosen on the opening screen and the same currency changed a week
 * later must not look like two different decisions.
 *
 * Which is why the heading is named by its **string resource** rather than by
 * the finished words. The group looks its own mark up from that id ([iconFor]),
 * so the two screens cannot be given different marks for the same question, and
 * a heading added to one of them cannot arrive without one. Passing the words in
 * would have left the icon a second argument for every caller to remember.
 */
@Composable
fun SettingsGroup(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
    /**
     * Whether the group draws its own card and keeps the page's margin.
     *
     * False inside a surface that is already one — the overlay the app opens on
     * is a single panel, and cards inside a card read as panels inside a panel
     * with the page nowhere to be seen.
     */
    boxed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = if (boxed) modifier.padding(horizontal = 20.dp) else modifier) {
        SectionHeader(title = stringResource(title), icon = iconFor(title))
        Spacer(Modifier.height(10.dp))
        if (boxed) WalletCard(content = { content() }) else Column(content = content)
    }
}

/**
 * The mark for one settings heading.
 *
 * Settings is the longest list of near-identical grey capitals in the app —
 * ten of them, each a single word — and finding the one you came for meant
 * reading down the column. Each glyph says what its section is *about* before
 * the word is read.
 *
 * Two of them are deliberately not the obvious choice. **Backup is not a
 * cloud**: the app promises no server and no account, its own manifest turns
 * Android's Auto Backup off to keep that true, and a cloud on the one screen
 * that writes the user's whole financial history to a file would say the
 * opposite in the one place it matters most. It is a box that gets put
 * somewhere. And **Reminders is a clock rather than a bell**, because the bell
 * belongs to Notifications two sections above it and the question here is *when*
 * — the hour, and how many days' warning.
 *
 * Null for anything with no heading of its own, so a caller that passes some
 * other string simply gets the plain eyebrow rather than a wrong mark.
 */
@Composable
private fun iconFor(@StringRes title: Int): ImageVector? = when (title) {
    R.string.settings_theme -> Icons.Outlined.Palette
    R.string.settings_language -> Icons.Outlined.Translate
    R.string.settings_calendar -> Icons.Outlined.CalendarMonth
    R.string.settings_currency -> Icons.Outlined.CurrencyExchange
    R.string.settings_notifications -> Icons.Outlined.NotificationsNone
    R.string.settings_reminders -> Icons.Outlined.Schedule
    R.string.settings_lock -> Icons.Outlined.Lock
    R.string.settings_backup -> Icons.Outlined.Inventory2
    R.string.settings_ads -> Icons.Outlined.PrivacyTip
    R.string.settings_about -> Icons.Outlined.Info
    R.string.settings_start_over -> Icons.Outlined.RestartAlt
    else -> null
}

/**
 * What a card actually does, in one quiet line under its controls.
 *
 * One definition rather than a Text at each site: every card that had an
 * explanation had written its own, and they had drifted to three different
 * colours. Set below the controls, not above them — the chips are what the card
 * is *for*, and a sentence between the heading and them pushed the answer down
 * the page behind an explanation nobody needed twice.
 */
@Composable
fun Explain(@StringRes text: Int) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A control and the line that explains it, side by side instead of stacked.
 *
 * [Explain] sets its sentence under the controls because a row of chips takes
 * the width of the card and leaves nothing to put beside it. A picker does not:
 * it is a small box with most of the card to the right of it, and a sentence
 * set underneath left that space empty and spent a second line on it. The
 * explanation is what the box means, so beside it is where it belongs.
 *
 * Middles aligned rather than tops, because the two are one line of the card
 * read across, not a heading with a note under it.
 */
@Composable
fun ExplainedRow(
    @StringRes explain: Int,
    control: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        control()
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row of chips. One tap, no dropdowns — fewer places to get lost. */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * The same choice as [ChoiceRow] where there are too many answers to lay
 * out flat, drawn as a box the thumb can see.
 *
 * A row of plain text with a chevron beside it reads as a *stated* answer —
 * the shape the app uses everywhere else for a fact that has settled and
 * cannot be changed — so the one setting on the page that is a control looked
 * like the one thing on the page that was not, and nothing said otherwise
 * until it had been tapped. A bordered, filled box is the app's own mark for
 * a field, and a shape the eye already knows how to read is worth more than a
 * row that saves a few dp.
 *
 * **It is a box the size of its answer, not the size of the page.** A field
 * running the full width of the card promises a sentence and holds three
 * letters, and at a text field's own height it is the tallest thing on a page
 * of chips that are shorter. So it is drawn by hand rather than as an
 * [OutlinedTextField]: nothing is ever typed into it, and a real text field
 * cannot go below its own minimum height or stop asking for the width it was
 * built to hold prose in.
 *
 * The menu keeps Material's own size — the width of the box it came out of —
 * and opens **scrolled to the answer already on file**: seventeen currencies do
 * not fit on a phone, and a user paid in dirhams, last of the seventeen, would
 * otherwise open the list on a screenful of currencies that are not theirs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoicePicker(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val here = options.indexOfFirst { it.first == selected }
    val width = fieldWidthFor(options.map { it.second })
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chevron-turn",
    )

    LaunchedEffect(open, here) {
        if (!open || here <= 0) return@LaunchedEffect
        // Not before the menu has been measured, or the scroll has no extent
        // yet and clamps every offset to nothing.
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        // One row above the answer, so it reads as a place in a list rather
        // than as the top of one.
        scroll.scrollTo(with(density) { ((here - 1) * MENU_ROW_HEIGHT.toPx()).toInt() })
    }

    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                // PrimaryNotEditable: the box is the way into the menu and
                // nothing is ever typed into it, so a tap must open the list
                // and never the keyboard.
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .width(width)
                .height(FIELD_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn),
            )
        }
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            scrollState = scroll,
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                    // The tick marks where the list currently stands, so the
                    // menu can be opened to read the answer as well as change it.
                    trailingIcon = if (value == selected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    contentPadding = MENU_ROW_PADDING,
                )
            }
        }
    }
}

/**
 * How wide the box stands: enough for its **widest** answer, never less than
 * [FIELD_WIDTH] and never more than [FIELD_MAX_WIDTH].
 *
 * The widest of all of them rather than the one currently showing, because a
 * box that wrapped its own content would change size as the answer did — "AED"
 * and "HK$  HKD" are not the same length — and a control that moves when it is
 * used is one the thumb has to find again. Measured rather than guessed at,
 * since the same list is read in two languages: "English (AD)" and
 * "नेपाली (BS)" do not agree about how much room a calendar needs, and a
 * fixed box wide enough for one clipped the other.
 *
 * The floor keeps a picker of short answers standing among the chips on the
 * cards either side of it rather than shrinking to a stub; the ceiling stops a
 * long answer running off a narrow phone, where clipping one label is the
 * lesser fault.
 */
@Composable
private fun fieldWidthFor(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    return remember(labels, style, density) {
        val widest = labels.maxOfOrNull { label ->
            measurer.measure(label, style, maxLines = 1).size.width
        } ?: 0
        val text = with(density) { widest.toDp() }
        (text + FIELD_TRIMMINGS).coerceIn(FIELD_WIDTH, FIELD_MAX_WIDTH)
    }
}

/** What the box spends on something other than the answer: padding, and the chevron. */
private val FIELD_TRIMMINGS = 14.dp + 10.dp + 24.dp + 8.dp

private val FIELD_WIDTH = 168.dp
private val FIELD_MAX_WIDTH = 260.dp
private val FIELD_HEIGHT = 44.dp

/**
 * What one row of the menu stands, which is what the scroll above counts in.
 * Material's own default for a menu item, named here because the offset is
 * arithmetic and a magic number in it would be unreadable.
 */
private val MENU_ROW_HEIGHT = 48.dp

private val MENU_ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
