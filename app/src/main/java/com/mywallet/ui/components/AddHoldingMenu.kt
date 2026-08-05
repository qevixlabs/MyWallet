package com.mywallet.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mywallet.R
import com.mywallet.domain.HoldingGroup
import kotlinx.coroutines.delay

/**
 * What is being added, asked at the button that adds it.
 *
 * The form used to open on a row of chips reading Bank · Wallet · Cash · A
 * person · Insurance · Goal, and every one of them changed what the rest of the
 * page asked — so the first thing a user did on the "Add account" screen was
 * decide what screen they wanted. That is a question, and a question belongs in
 * front of the form rather than inside it. Answered here, the form opens knowing
 * the answer and never shows the row again.
 *
 * It was asked in a modal sheet for a while, and a sheet is the wrong shape for
 * it: a sheet arrives from the bottom of the screen as a page of its own, which
 * is a lot of movement for six words and leaves the button that was tapped
 * hidden underneath it. These grow out of the button instead — nearest one
 * first, so the eye follows them up from the thumb — and the button stays where
 * it is and turns into the way back out. The answer is one tap from where the
 * question was asked.
 *
 * Each option keeps its line of explanation. This is the one moment in the app
 * where the user may genuinely not know which of two things they have — a policy
 * and a goal both take money in every month — and the line under each says
 * which is which.
 *
 * Drawn in the floating button's own slot, so it needs no idea where that button
 * is: the column simply grows upwards from it. The dimming behind it is
 * [AddHoldingScrim], which belongs to the page and not to this.
 */
@Composable
fun AddHoldingMenu(
    expanded: Boolean,
    onPick: (HoldingGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Composed for as long as it takes to close, or the options would vanish
    // rather than leave: the state this animates from is gone the frame after
    // the tap.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) visible = true else { delay(CLOSE_TOTAL_MS); visible = false }
    }
    if (!visible) return

    Column(
        // One width for all six, taken from the longest of them: the column is
        // its widest child ([IntrinsicSize.Max], capped below) and every option
        // fills it. Stretched to the width of the page they read as a sheet with
        // gaps cut into it, and each at its own width they read as six unrelated
        // things — one block, hanging off the button, is neither.
        modifier = modifier
            .padding(bottom = 14.dp)
            // The cap goes here rather than on each option, because this is where
            // the width is decided: [IntrinsicSize.Max] enforces the constraints
            // it is given, so a line of explanation too long for the phone wraps
            // inside a capped column instead of running off the side of it.
            .widthIn(max = 324.dp)
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CHOICES.forEachIndexed { index, choice ->
            AddMenuOption(
                icon = choice.icon,
                titleRes = choice.titleRes,
                subtitleRes = choice.subtitleRes,
                expanded = expanded,
                // Out from the button and back into it: the last option is the
                // one beside the thumb, so it is the first to appear and the
                // last to go.
                openDelayMillis = (CHOICES.lastIndex - index) * OPEN_STAGGER_MS,
                closeDelayMillis = index * CLOSE_STAGGER_MS,
                onClick = { onPick(choice.group) },
            )
        }
    }
}

/**
 * What is being recorded, asked at the button that records it.
 *
 * The same menu the accounts button opens, for the same reason: money out, money
 * in and a transfer are three different forms — different questions, different
 * ends, different words — and which one it is was the first thing the user had
 * to answer on a page that had already opened on a guess. Answered here, the
 * form arrives on the right tab.
 *
 * The form keeps its segmented button. This is where a movement *starts*, not a
 * decision it is held to: changing one's mind is a tap, and taking that away to
 * avoid asking twice would be worse than asking twice.
 */
@Composable
fun AddEntryMenu(
    expanded: Boolean,
    /**
     * Whether a transfer is possible at all. False on a phone with one place for
     * money to sit, where the option is dropped rather than drawn dead: the form
     * behind it greys the tab out and says why underneath, and this menu has
     * nowhere to put that sentence.
     */
    canTransfer: Boolean,
    onPick: (EntryStart) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) visible = true else { delay(CLOSE_TOTAL_MS); visible = false }
    }
    if (!visible) return

    Column(
        modifier = modifier
            .padding(bottom = 14.dp)
            .widthIn(max = 324.dp)
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val choices = ENTRY_CHOICES.filter { canTransfer || it.start != EntryStart.TRANSFER }
        choices.forEachIndexed { index, choice ->
            AddMenuOption(
                icon = choice.icon,
                titleRes = choice.titleRes,
                subtitleRes = choice.subtitleRes,
                expanded = expanded,
                openDelayMillis = (choices.lastIndex - index) * OPEN_STAGGER_MS,
                closeDelayMillis = index * CLOSE_STAGGER_MS,
                onClick = { onPick(choice.start) },
            )
        }
    }
}

/** Which of the money form's three tabs the button opened on. */
enum class EntryStart { OUT, IN, TRANSFER }

private data class EntryChoice(
    val start: EntryStart,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

/** Spending first: it is what most of the rows in the timeline are. */
private val ENTRY_CHOICES = listOf(
    EntryChoice(
        EntryStart.OUT,
        Icons.AutoMirrored.Outlined.CallMade,
        R.string.add_money_out,
        R.string.add_entry_out,
    ),
    EntryChoice(
        EntryStart.IN,
        Icons.AutoMirrored.Outlined.CallReceived,
        R.string.add_money_in,
        R.string.add_entry_in,
    ),
    EntryChoice(
        EntryStart.TRANSFER,
        Icons.Outlined.SwapHoriz,
        R.string.add_direction_transfer,
        R.string.add_entry_transfer,
    ),
)

/**
 * The dimming behind the options.
 *
 * Drawn over the page rather than over the whole window, which leaves the button
 * itself at full strength — it is the way out of the menu, and a close button
 * behind a scrim reads as disabled. A tap anywhere on it closes without choosing
 * anything, which is what a tap outside a menu has always meant.
 */
@Composable
fun AddHoldingScrim(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) visible = true else { delay(CLOSE_TOTAL_MS); visible = false }
    }
    if (!visible) return

    val fade = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        fade.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(if (expanded) 200 else CLOSE_MS, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(fade.value)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f))
            .clickable(
                // No ripple: this is the page being tapped away, not a control.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
    )
}

/** One option: what it is, what it is for, and a picture to find it by. */
private data class AddChoice(
    val group: HoldingGroup,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

/**
 * In the order the form's chips were in, which is roughly the order people
 * think of them: where most money is, then where the rest of it is, then the
 * two arrangements that pay themselves.
 */
private val CHOICES = listOf(
    AddChoice(
        HoldingGroup.BANK,
        Icons.Outlined.AccountBalance,
        R.string.accounts_kind_bank,
        R.string.add_holding_bank,
    ),
    AddChoice(
        HoldingGroup.WALLET,
        Icons.Outlined.AccountBalanceWallet,
        R.string.accounts_kind_wallet,
        R.string.add_holding_wallet,
    ),
    AddChoice(
        HoldingGroup.CASH,
        Icons.Outlined.Payments,
        R.string.accounts_kind_cash,
        R.string.add_holding_cash,
    ),
    AddChoice(
        HoldingGroup.PERSON,
        Icons.Outlined.Person,
        R.string.accounts_kind_person,
        R.string.add_holding_person,
    ),
    AddChoice(
        HoldingGroup.INSURANCE,
        Icons.Outlined.Shield,
        R.string.accounts_kind_insurance,
        R.string.add_holding_insurance,
    ),
    AddChoice(
        HoldingGroup.GOAL,
        Icons.Outlined.Savings,
        R.string.accounts_kind_goal,
        R.string.add_holding_goal,
    ),
)

/** How long one option takes to arrive, and how far apart they arrive. */
private const val OPEN_MS = 240
private const val OPEN_STAGGER_MS = 30

/**
 * Leaving is quicker than arriving and closer together — the user has already
 * decided, and an exit that takes as long as the entrance feels like the app
 * arguing about it.
 */
private const val CLOSE_MS = 140
private const val CLOSE_STAGGER_MS = 18

/** The last option is still going after the first one has gone. */
private const val CLOSE_TOTAL_MS = (CLOSE_MS + CLOSE_STAGGER_MS * 5).toLong()

@Composable
private fun AddMenuOption(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    expanded: Boolean,
    openDelayMillis: Int,
    closeDelayMillis: Int,
    onClick: () -> Unit,
) {
    // An [Animatable] starting at zero rather than `animateFloatAsState`, which
    // takes its first value as its target and would draw the option already in
    // place before animating nothing.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) {
                tween(OPEN_MS, delayMillis = openDelayMillis, easing = FastOutSlowInEasing)
            } else {
                tween(CLOSE_MS, delayMillis = closeDelayMillis, easing = FastOutSlowInEasing)
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        // See-through, so the page underneath is dimmed rather than replaced:
        // these are floating over the accounts list for a moment, not standing in
        // for it. The floor is legibility — the words sit over whatever happens
        // to be behind them, and the scrim is the only thing keeping them apart.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                // Scaled and slid out of the button, which sits off the bottom
                // right corner of every one of these.
                val scale = 0.88f + 0.12f * progress.value
                scaleX = scale
                scaleY = scale
                translationY = (1f - progress.value) * 20.dp.toPx()
                transformOrigin = TransformOrigin(1f, 1f)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // The whole card is the target, not the words on it: the option is
            // wider than what is written on it, and a tap on the empty end of it
            // has to count.
            //
            // More room at the end than at the start, which is what widens the
            // whole block: the column is measured from the longest option, so air
            // asked for here is air every option gets, and asking for it at the
            // end leaves the words where they were rather than pushing them in.
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 36.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // The icon on its own tinted patch, so the six read as a list of
            // things rather than as six lines of text with decoration in front
            // of them.
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
