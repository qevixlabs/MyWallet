package com.mywallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mywallet.R

/**
 * Steps through months, in whichever calendar the user reads.
 *
 * Shared by Home and the timeline: both are month-shaped views of the same
 * money, and two separate controls would have drifted apart in wording and in
 * how far each would let you travel.
 */
@Composable
fun MonthSelector(
    label: String,
    secondary: String?,
    canGoForward: Boolean,
    showBackToNow: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = true,
) {
    PeriodSelector(
        label = label,
        secondary = secondary,
        previousDescription = stringResource(R.string.home_previous_month),
        nextDescription = stringResource(R.string.home_next_month),
        backToNowLabel = stringResource(R.string.home_back_to_now),
        canGoForward = canGoForward,
        showBackToNow = showBackToNow,
        onPrevious = onPrevious,
        onNext = onNext,
        onBackToNow = onBackToNow,
        modifier = modifier,
        canGoBack = canGoBack,
    )
}

/**
 * Steps through single days, in whichever calendar the user reads.
 *
 * The same control as [MonthSelector] and deliberately so — Reminders is a
 * day-shaped view of the same money the timeline shows a month of, and two
 * steppers drawn differently would read as two unrelated ideas. Only the words
 * differ, because "Previous month" over a day stepper is the wrong noun.
 */
@Composable
fun DaySelector(
    label: String,
    secondary: String?,
    canGoForward: Boolean,
    canGoBack: Boolean,
    showBackToToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PeriodSelector(
        label = label,
        secondary = secondary,
        previousDescription = stringResource(R.string.reminders_previous_day),
        nextDescription = stringResource(R.string.reminders_next_day),
        backToNowLabel = stringResource(R.string.reminders_back_to_today),
        canGoForward = canGoForward,
        showBackToNow = showBackToToday,
        onPrevious = onPrevious,
        onNext = onNext,
        onBackToNow = onBackToToday,
        modifier = modifier,
        canGoBack = canGoBack,
    )
}

/**
 * The same step, made by dragging the page instead of reaching for an arrow.
 *
 * Home, Reminders and the timeline are each one period at a time, and the only
 * way to the next one was a 48dp target in the top corner — a stretch on a
 * phone held one-handed, and the last place a thumb rests. Dragging the page
 * left is the next period and right is the previous one, which is the direction
 * a filmstrip moves and the way every calendar on the phone already reads.
 *
 * **Whatever the page holds keeps the gesture.** This sits on the outside of
 * the list, so anything inside it that wants a horizontal drag is offered the
 * touch first and this one never begins: a drag that starts on a timeline row
 * is that row's delete — in *either* direction, since the row claims the touch
 * before it is clear which way the finger is going — and the filter chips above
 * them still scroll sideways. Nothing arranges that here. It is Compose's own
 * ordering, a child that consumes cancelling the parent still waiting for its
 * slop, and it is the whole reason the gesture is attached to the list rather
 * than to a box drawn over the page: over the page it would win everything, and
 * a swipe meant for a payment would step the month instead of opening the red.
 *
 * The system's back gesture owns the screen edges and is deliberately left
 * alone: nothing here asks for those few millimetres, so a swipe in from the
 * edge still leaves the page rather than stepping it.
 *
 * It steps the moment the drag passes [SWIPE_STEP] rather than waiting for the
 * finger to lift, so the label starts moving under the thumb — which is the
 * only thing that says the page can be dragged at all. One step per gesture:
 * carrying on to the other side of the screen is the same drag, not six months.
 *
 * How far each page may travel is not asked here. The view models already clamp
 * their own answer — Home refuses a month that has not arrived, Reminders holds
 * to its thirty days — because the arrows are drawn from a state that lags a
 * tap, and a second copy of that rule living in a gesture is exactly how the
 * two come to disagree.
 */
fun Modifier.swipeBetweenPeriods(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = pointerInput(Unit) {
    val step = SWIPE_STEP.toPx()
    var travelled = 0f
    var stepped = false
    detectHorizontalDragGestures(
        onDragStart = { travelled = 0f; stepped = false },
        onDragCancel = { travelled = 0f; stepped = false },
        onHorizontalDrag = { _, delta ->
            if (!stepped) {
                travelled += delta
                // Left for the next period, right for the previous one: the
                // page is being pulled aside to bring the next one in.
                if (travelled <= -step) {
                    stepped = true
                    onNext()
                } else if (travelled >= step) {
                    stepped = true
                    onPrevious()
                }
            }
        },
    )
}

/**
 * How far the page travels before it steps.
 *
 * Comfortably past the touch slop a horizontal drag has to clear first, so a
 * finger wandering sideways on the way down a long list cannot step the month,
 * and short enough to make with a thumb without shifting grip. A length rather
 * than a share of the width, so the drag is the same on every phone: measured
 * as a fraction it would ask most of the larger screen, where the reach the
 * gesture exists to save is already the worst.
 */
private val SWIPE_STEP = 72.dp

/**
 * The shape both steppers share: an arrow either side of where you are, and a
 * way back to now once you have wandered off it.
 *
 * Private, and the two public wrappers above supply their own nouns — a single
 * control taking three string parameters at every call site would put the
 * wording in the screens, which is exactly where it drifted apart before.
 */
@Composable
private fun PeriodSelector(
    label: String,
    secondary: String?,
    previousDescription: String,
    nextDescription: String,
    backToNowLabel: String,
    canGoForward: Boolean,
    showBackToNow: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToNow: () -> Unit,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepArrow(
            shown = canGoBack,
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            description = previousDescription,
            onClick = onPrevious,
        )
        // The label glides when the month steps, so the change reads as
        // movement through time rather than text being swapped in place.
        AnimatedContent(
            targetState = label to secondary,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 4 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "period",
            // The month takes what is left after the arrows and the way back,
            // rather than being measured before them and clipped by what
            // follows: "September 2026" came out as "September 202" the moment
            // "Back to this month" appeared beside it, which is the one line on
            // the page every figure below is relative to losing its year. The
            // way back wraps instead — it is a way out of where the reader has
            // wandered, not the thing they are reading.
            // `fill = false` is the whole of it: weighted, so the month is
            // measured against what is *left* rather than being clipped by what
            // comes after it — and non-filling, so with room to spare it stays
            // its own width and the arrows go on hugging it. Filling, the label
            // stretched and shoved the forward arrow against the screen edge on
            // every ordinary month, which is the common case paying for the rare
            // one.
            modifier = Modifier.weight(1f, fill = false),
        ) { (month, sub) ->
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(text = month, style = MaterialTheme.typography.titleLarge)
                sub?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        StepArrow(
            shown = canGoForward,
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            description = nextDescription,
            onClick = onNext,
        )
        if (showBackToNow) {
            TextButton(onClick = onBackToNow) {
                Text(backToNowLabel)
            }
        }
    }
}

/**
 * One end of the stepper: the arrow, or the room it would have taken.
 *
 * Greyed out, it was a control the thumb still aimed at and the eye still read
 * as a way forward — a page that says "you may go this way" and then does
 * nothing when tapped is worse than one that says nothing. Absent, there is no
 * question to answer.
 *
 * **But it keeps its place.** Removed outright, the heading beside it slides
 * across the page the moment the edge is reached and slides back on the first
 * step away from it — so the one line every figure below is relative to moves
 * under the reader as they walk the months, and the thumb has to find the other
 * arrow again each time. A gap the size of the button costs nothing and holds
 * everything still.
 */
@Composable
private fun StepArrow(
    shown: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    if (shown) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = description)
        }
    } else {
        // Not a gap — a mark saying the page stops here.
        //
        // An empty slot was the first answer, and on Reminders it is the one
        // the page *opens* on: today is as far back as it goes, so the first
        // thing anybody sees is a date sitting an arrow's width from the margin
        // with nothing to its left, which reads as a control that failed to
        // draw rather than as an edge.
        //
        // So the chevron stays and stops being a control: no button around it,
        // no ripple, no target, and a fifth of the ink. That is a long way from
        // the greyed-out `enabled = false` this replaced, which kept the whole
        // 48dp button and Material's own disabled ink — near enough to a live
        // one that the thumb went for it.
        Box(
            modifier = Modifier.size(ARROW_SLOT),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            )
        }
    }
}

/** What an [IconButton] measures, so the gap left by a hidden arrow matches it. */
private val ARROW_SLOT = 48.dp
