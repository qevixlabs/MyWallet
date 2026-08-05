package com.mywallet.ui.components

import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mywallet.ui.theme.TutorialLight

/**
 * Air left around the thing being lit, and deliberately none of it.
 *
 * A hole larger than its subject shows the page around it undimmed, which on
 * the floating button read as a white tile behind the plus — a shape the app
 * does not have, drawn around the one thing the card is pointing at. The hole
 * is the subject's own rectangle, so what is lit is the button and nothing else.
 */
private val SPOTLIGHT_PADDING = 0.dp

/** The gap between the hole and the tip of the pointer. */
private val POINTER_GAP = 10.dp

private val POINTER_WIDTH = 20.dp
private val POINTER_HEIGHT = 10.dp

/** The page margin the card keeps, matching every card in the app. */
private val CARD_MARGIN = 20.dp

private val CARD_SHAPE = RoundedCornerShape(20.dp)

/** How big the two edge arrows are drawn. See [CoachMark]'s `showEdgeArrows`. */
private val EDGE_ARROW = 40.dp

/** What the card gives up at each side so the arrows are not drawn on top of it. */
private val ARROW_ROOM = 36.dp

/**
 * How round the hole is cut, and therefore how round whatever shows through it
 * has to be.
 *
 * Public because the two are one shape: a square-cornered row inside a rounded
 * hole leaves four triangles of itself out in the scrim, dimmed — which on a
 * light row over a dark page reads as a grey halo hugging the card. Anything
 * lit by a spotlight clips to this. Rounder than the row would shave its edges;
 * squarer would leave the page showing at its corners.
 */
val SPOTLIGHT_CORNER = 16.dp

/**
 * One thing said once, with a finger pointed at the thing it is about.
 *
 * A button that opens six choices leaves no mark on the page saying so, and a
 * note in the margin of a screen is a note about the screen, which is not the
 * same as a note about *that*. This dims the page, leaves the one thing it is
 * talking about lit, and points at it.
 *
 * Three things make it land on the right pixels:
 *
 * - [target] is in **window** coordinates, straight off `boundsInWindow()`, and
 *   the popup is placed at the window's own origin — so the scrim's canvas and
 *   the row's rectangle are in one coordinate space. Anything measured against
 *   the *screen* would be out by the status bar.
 * - It is a [Popup] rather than a box inside the page, because the page stops
 *   above the bottom bar. A dimming that leaves the tabs bright reads as a page
 *   that has half loaded, and the tabs would still be tappable underneath it.
 * - The card goes **below** a row in the top half of the window and above one in
 *   the bottom half, so it never covers the thing it is pointing at. Which side
 *   the pointer is on follows from that, and nothing has to measure the card to
 *   decide it.
 *
 * A null [target] — the row is scrolled out of sight, or the list is empty — is
 * drawn as the card alone with no hole and no pointer. It is still worth saying;
 * it just has nothing to point at.
 */
@Composable
fun CoachMark(
    title: String,
    body: String,
    target: Rect?,
    /** What the one button says — "Got it", and nothing else to decide. */
    actionLabel: String,
    onAction: () -> Unit,
    /**
     * Draws an arrow at each side of the screen, level with what is lit.
     *
     * For the one lesson that is about the *page* rather than about a control:
     * a spotlight on the dates says which figure changes, and says nothing at
     * all about the drag that changes it. The two arrows are where the gesture
     * starts and ends, which is the part no card can say in words as quickly.
     */
    showEdgeArrows: Boolean = false,
    /**
     * Lets the swipe this card is *about* actually happen, one period at a time.
     *
     * The month lesson used to be the one card that asked for a gesture and then
     * ate it: the popup has the touch, so a reader who did exactly as they were
     * told saw the card vanish and the month sit exactly where it was. Nothing
     * on screen said whether they had done it right, or indeed done anything —
     * which is the one thing a lesson has to answer. Withholding the step was
     * meant to keep them from "reading about a month they are no longer on", and
     * that worry answers itself: the card goes in the same gesture, so there is
     * nothing left to read.
     *
     * The other cards leave it null and a swipe simply dismisses them, which is
     * right — a lesson about deleting a row has nothing to say about the month.
     * Given, it is [swipeBetweenPeriods] itself rather than a second reading of
     * the same gesture, so the lesson is taught with the very modifier the page
     * uses: same 72dp step, same direction, and no way for the two to drift.
     */
    onStepPeriod: ((Int) -> Unit)? = null,
) {
    // Back and a tap anywhere both mean "enough of this", which is the same as
    // the button: there is one thing to say and one way to be done with it.
    val onDismiss = onAction
    // (0,0) is the window's top-left. The popup is asked for no particular
    // position relative to its anchor: it covers the window, and everything
    // inside it is placed from the target's own coordinates.
    val atWindowOrigin = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        }
    }

    Popup(
        popupPositionProvider = atWindowOrigin,
        onDismissRequest = onDismiss,
        // Flags rather than `focusable = true`, for one flag: without
        // FLAG_LAYOUT_NO_LIMITS a popup's window stops at the navigation bar
        // inset, and the strip of page below the tabs stayed bright — a band of
        // undimmed background under a dimmed page, which reads as a dim that has
        // not finished. Zero for the rest means focusable, so the back gesture
        // closes it the way it closes everything else drawn over the page.
        properties = PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            dismissOnBackPress = true,
        ),
    ) {
        // Faded in rather than slammed on. It arrives at the same moment as the
        // tab it belongs to, which cross-fades over 200ms, and a scrim that was
        // simply there read as the page having failed to draw. The flag is
        // flipped after the first composition on purpose: animateFloatAsState
        // takes its first value as its target, so starting it at one would draw
        // the scrim already black.
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val fade by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "coach-mark-fade",
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade }
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val padPx = with(density) { SPOTLIGHT_PADDING.toPx() }

            val hole = target?.let {
                Rect(
                    left = (it.left - padPx).coerceAtLeast(0f),
                    top = (it.top - padPx).coerceAtLeast(0f),
                    right = (it.right + padPx).coerceAtMost(widthPx),
                    bottom = (it.bottom + padPx).coerceAtMost(heightPx),
                )
            }

            // The corner the floating button is cut to. Rounder than the button
            // would shave its edges; squarer would leave four lit crumbs at its
            // corners where the page shows through.
            val holeRadius = with(density) { SPOTLIGHT_CORNER.toPx() }
            // One path with the hole cut out of it, rather than a scrim and a
            // clear-blend layer: even-odd fill needs no offscreen compositing,
            // and the row underneath shows through at full brightness.
            val cut = remember(hole, widthPx, heightPx) {
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, widthPx, heightPx))
                    hole?.let { addRoundRect(RoundRect(it, CornerRadius(holeRadius))) }
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // A tap anywhere is taken as "understood". The scrim has to
                    // eat the gesture either way — the row behind it must not be
                    // opened by a tap aimed at the card — and swallowing it
                    // silently reads as a frozen screen.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    // A swipe means the same as a tap — except on the one card
                    // that is *about* a swipe, where it means what it says: the
                    // month steps, and the card goes in the same gesture. Doing
                    // the second without the first is what made the lesson read
                    // as broken; see [onStepPeriod].
                    .then(
                        if (onStepPeriod != null) {
                            Modifier.swipeBetweenPeriods(
                                onPrevious = { onStepPeriod(-1); onDismiss() },
                                onNext = { onStepPeriod(1); onDismiss() },
                            )
                        } else {
                            Modifier.pointerInput(Unit) {
                                detectHorizontalDragGestures { _, _ -> onDismiss() }
                            }
                        }
                    )
            ) {
                drawPath(cut, color = Color.Black.copy(alpha = 0.62f))
            }

            // Below a row near the top, above one near the bottom: the card must
            // never cover the row it is pointing at.
            val below = hole == null || hole.bottom < heightPx * 0.55f
            val pointerAt = hole?.let { rect ->
                val centre = with(density) { rect.center.x.toDp() }
                // Kept off the card's rounded corners, where a pointer would
                // grow out of thin air. The range is checked rather than
                // trusted: coerceIn throws when its bounds cross over, which on
                // a window too narrow for a card plus two corners would be a
                // crash where the honest answer is "no pointer".
                val lowest = CARD_MARGIN + 14.dp
                val highest = maxWidth - CARD_MARGIN - 14.dp - POINTER_WIDTH
                if (highest <= lowest) {
                    null
                } else {
                    (centre - POINTER_WIDTH / 2).coerceIn(lowest, highest) - CARD_MARGIN
                }
            }

            // At the sides of the *screen*, half way down it, level with the
            // card between them: this lesson is about the width of the page and
            // not about any control on it, so there is nothing to spotlight and
            // the two arrows are what the card is pointing at. Lighting the
            // dates was the first try and said the wrong thing twice over — it
            // named the figure that changes while saying nothing about the drag
            // that changes it, and the stepper is so nearly the full width that
            // both arrows landed inside the very spotlight, where a mark reads
            // as part of the control.
            if (showEdgeArrows) {
                EdgeFlow(
                    pointingLeft = true,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp)
                        .size(width = EDGE_ARROW, height = EDGE_ARROW),
                )
                EdgeFlow(
                    pointingLeft = false,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .size(width = EDGE_ARROW, height = EDGE_ARROW),
                )
            }

            when {
                hole == null -> Callout(
                    title = title,
                    body = body,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    pointerAt = null,
                    pointerAbove = false,
                    // Stood off the sides when the arrows are out, so the three
                    // read as one thing — an arrow, the words, an arrow — rather
                    // than as a card with two marks stuck to its corners.
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = if (showEdgeArrows) ARROW_ROOM else 0.dp),
                )
                below -> Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(with(density) { hole.bottom.toDp() } + POINTER_GAP))
                    Callout(
                        title = title,
                        body = body,
                        actionLabel = actionLabel,
                        onAction = onAction,
                        pointerAt = pointerAt,
                        pointerAbove = true,
                    )
                }
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = with(density) { (heightPx - hole.top).toDp() } + POINTER_GAP
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Callout(
                        title = title,
                        body = body,
                        actionLabel = actionLabel,
                        onAction = onAction,
                        pointerAt = pointerAt,
                        pointerAbove = false,
                    )
                }
            }
        }
    }
}

/**
 * The card itself, with the pointer growing out of whichever edge faces the row.
 *
 * [pointerAt] is measured from the card's own left edge, so the caller works in
 * window coordinates and this works in the card's.
 */
@Composable
private fun Callout(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    pointerAt: Dp?,
    pointerAbove: Boolean,
    modifier: Modifier = Modifier,
) {
    // The card is paper laid on a dimmed page, and it is the same paper in both
    // schemes: in the dark one a surface-coloured card on a 62% black scrim is
    // a dark shape on a dark page, which is the opposite of what a card drawing
    // the eye to one thing is for. See [TutorialLight].
    TutorialLight {
    val surface = MaterialTheme.colorScheme.surface
    Column(modifier = modifier.padding(horizontal = CARD_MARGIN)) {
        if (pointerAbove) pointerAt?.let { Pointer(at = it, up = true, color = surface) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = surface, shape = CARD_SHAPE)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Said outright, because nothing here is a Surface and the
                // content colour therefore comes from whatever is outside the
                // card. In the dark scheme that is white — on white paper.
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
        if (!pointerAbove) pointerAt?.let { Pointer(at = it, up = false, color = surface) }
    }
    }
}

/** The triangle that turns a card into a finger. Drawn, so it scales with nothing. */
@Composable
private fun Pointer(at: Dp, up: Boolean, color: Color) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .offset(x = at)
                .size(width = POINTER_WIDTH, height = POINTER_HEIGHT)
        ) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                }
                close()
            }
            drawPath(path, color = color)
        }
    }
}

/**
 * The same card, over a page that can still be *used*.
 *
 * The lesson at the end of the opening is one the reader has to make rather
 * than read, and [CoachMark] cannot carry it: the hole in its scrim is a hole
 * only to the eye, because a [Popup] is a window of its own and takes every
 * touch that lands on it. The row could be lit and pointed at and not swiped.
 *
 * So the dim here is drawn in the app's own composition and, more to the point,
 * **there is no node over the hole at all** — the touch-eating rectangles are
 * the four around it. What is lit is genuinely live: the swipe that the card is
 * asking for lands on the row itself, and every touch that misses stops here.
 *
 * A tap outside is *not* taken as "understood", which is where this parts from
 * the modal card. A finger reaching for a row and catching the page beside it
 * would have thrown the lesson away mid-gesture; the two ways out are making
 * the swipe and the button, which is why the button is there.
 *
 * [target] is in this overlay's own coordinates — see `WalletApp`, which
 * subtracts the overlay's origin from what the row reports.
 */
@Composable
fun PracticeSpotlight(
    title: String,
    body: String,
    target: Rect?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val hole = target?.let {
            Rect(
                left = it.left.coerceAtLeast(0f),
                top = it.top.coerceAtLeast(0f),
                right = it.right.coerceAtMost(widthPx),
                bottom = it.bottom.coerceAtMost(heightPx),
            )
        }?.takeIf { it.width > 0f && it.height > 0f }

        val holeRadius = with(density) { SPOTLIGHT_CORNER.toPx() }
        val cut = remember(hole, widthPx, heightPx) {
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, widthPx, heightPx))
                hole?.let { addRoundRect(RoundRect(it, CornerRadius(holeRadius))) }
            }
        }
        // Drawing only. A Canvas with nothing listening for pointers is not a
        // hit target, so this dims the page without taking anything from it —
        // which is the whole reason the eaters below are separate.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawPath(cut, color = Color.Black.copy(alpha = 0.62f))
        }

        if (hole == null) {
            Eat(left = 0f, top = 0f, right = widthPx, bottom = heightPx)
        } else {
            Eat(left = 0f, top = 0f, right = widthPx, bottom = hole.top)
            Eat(left = 0f, top = hole.bottom, right = widthPx, bottom = heightPx)
            Eat(left = 0f, top = hole.top, right = hole.left, bottom = hole.bottom)
            Eat(left = hole.right, top = hole.top, right = widthPx, bottom = hole.bottom)
        }

        val below = hole == null || hole.bottom < heightPx * 0.55f
        val pointerAt = hole?.let { rect ->
            val centre = with(density) { rect.center.x.toDp() }
            val lowest = CARD_MARGIN + 14.dp
            val highest = maxWidth - CARD_MARGIN - 14.dp - POINTER_WIDTH
            if (highest <= lowest) {
                null
            } else {
                (centre - POINTER_WIDTH / 2).coerceIn(lowest, highest) - CARD_MARGIN
            }
        }

        when {
            hole == null -> Callout(
                title = title,
                body = body,
                actionLabel = actionLabel,
                onAction = onAction,
                pointerAt = null,
                pointerAbove = false,
                modifier = Modifier.align(Alignment.Center),
            )
            below -> Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(with(density) { hole.bottom.toDp() } + POINTER_GAP))
                Callout(
                    title = title,
                    body = body,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    pointerAt = pointerAt,
                    pointerAbove = true,
                )
            }
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = with(density) { (heightPx - hole.top).toDp() } + POINTER_GAP),
                contentAlignment = Alignment.BottomStart,
            ) {
                Callout(
                    title = title,
                    body = body,
                    actionLabel = actionLabel,
                    onAction = onAction,
                    pointerAt = pointerAt,
                    pointerAbove = false,
                )
            }
        }
    }
}

/**
 * One of the four rectangles around the lit row, and all any of them does is
 * swallow what lands on it.
 *
 * Four rather than one with a hole in it, because a hole in a touch target is
 * not a thing Compose has: what is not covered by a node is what stays live.
 */
@Composable
private fun BoxScope.Eat(left: Float, top: Float, right: Float, bottom: Float) {
    if (right <= left || bottom <= top) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(
                x = with(density) { left.toDp() },
                y = with(density) { top.toDp() },
            )
            .size(
                width = with(density) { (right - left).toDp() },
                height = with(density) { (bottom - top).toDp() },
            )
            // Swallowed, never taken as an answer: a finger reaching for the row
            // and catching the page beside it must not end the lesson.
            .pointerInput(Unit) { detectTapGestures { } }
    )
}

/** How long one pulse takes to travel from the inner chevron to the outer one. */
private const val FLOW_MILLIS = 1400

/** The three chevrons of one edge. */
private const val FLOW_CHEVRONS = 3

/**
 * The gesture, moving.
 *
 * This is the one lesson with nothing to spotlight: what it is about is the
 * width of the page rather than any control on it, so the two arrows at the
 * screen's edges *are* what the card points at. Drawn still, they were a pair
 * of glyphs — the reader had to be told in words that they meant a drag, which
 * is the sentence the card was already spending, and a page that says "swipe"
 * with two motionless marks on it is asking to be taken as decoration.
 *
 * So the mark moves the way the thing it describes moves: three chevrons at
 * each side, lit one after another from the inside out, which is a pulse running
 * off the edge of the page. The eye reads travel from that without being told,
 * and the direction it travels in is the direction the page goes.
 *
 * Drawn by hand rather than animated as an icon for the same reason the chip on
 * the card face is: it is three strokes, and what has to be animated is not the
 * glyph but the *relationship between* three of them, which no single icon can
 * express however it is transformed.
 *
 * Both edges run the same animation rather than mirrored halves of one, because
 * the gesture genuinely goes both ways — left for the next month, right for the
 * previous — and an animation that only flowed one way would be teaching half
 * of it.
 */
@Composable
private fun EdgeFlow(pointingLeft: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "edgeFlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        // One past the last chevron, so the pulse leaves the page before it
        // starts again rather than jumping back to the middle.
        targetValue = FLOW_CHEVRONS + 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = FLOW_MILLIS, easing = LinearEasing),
        ),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val step = 9.dp.toPx()
        val armW = 7.dp.toPx()
        val armH = 9.dp.toPx()
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cy = size.height / 2f
        // The direction the chevrons point, which is also the direction the
        // pulse travels: away from the page, because that is where it goes.
        val dir = if (pointingLeft) -1f else 1f
        val innermost = if (pointingLeft) size.width - armW else armW

        repeat(FLOW_CHEVRONS) { i ->
            // A narrow bump around the moment the pulse passes this one. The
            // floor is what keeps all three visible while it is elsewhere: a
            // chevron that goes out entirely reads as a glyph blinking rather
            // than as one thing moving past three.
            val distance = kotlin.math.abs(phase - i)
            val alpha = (1f - distance / 1.1f).coerceIn(0f, 1f) * 0.75f + 0.25f
            val tipX = innermost + dir * i * step
            val path = Path().apply {
                moveTo(tipX - dir * armW, cy - armH)
                lineTo(tipX, cy)
                lineTo(tipX - dir * armW, cy + armH)
            }
            drawPath(path = path, color = Color.White.copy(alpha = alpha), style = stroke)
        }
    }
}
