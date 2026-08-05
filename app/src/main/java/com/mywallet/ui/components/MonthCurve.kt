package com.mywallet.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mywallet.ui.theme.WalletTheme

/**
 * The month, drawn as what has been spent so far against how far through it we
 * are.
 *
 * This replaces a tick per day, which was the app's signature element for a long
 * while and had three things wrong with it that only showed up on real months:
 *
 *  - **It was drawn in the primary blue** while every money-out figure in the
 *    app is red. A picture of a month's *spending* in the colour the app uses
 *    for nothing of the kind was the one place the palette contradicted itself.
 *  - **Sparse months read as noise.** Four days' spending in a thirty-day month
 *    is four hairlines standing on an empty rule, which looks like a chart that
 *    failed to load rather than like a quiet month.
 *  - **It answered a question the page is not asking.** Home asks *am I all
 *    right this month?* — which is about pace, and pace is exactly what a
 *    per-day chart cannot show: the reader has to add the bars up by eye to
 *    find out whether they are ahead or behind.
 *
 * A running total answers it directly. The line's *steepness* is the rhythm the
 * ticks used to show — a heavy day is a jump, a quiet week is a flat run — so
 * nothing is lost, and where it has got to by today is the answer to the
 * question actually being asked.
 *
 * Still no legend, no axis and no chart library. Height is the only encoding,
 * and the sentence underneath says in words what the shape means.
 *
 * **The track runs the whole month and the line stops at today.** That is the
 * other half of what the ticks did: the strip's length was the real length of
 * the month, so a 32-day Nepali month was visibly longer than a 30-day one and
 * the calendar setting stopped being an abstract preference. The gap between
 * where the line ends and where the track does is now how far through the month
 * the reader is, which is a second free reading the ticks never gave.
 *
 * **Both directions, on one scale.** The card this sits in leads with what went
 * out and says what came in on the line under it, and its whole verdict is the
 * comparison between them — so a picture of the month that drew only half of
 * that was answering a narrower question than the words around it. The two are
 * measured against the *same* peak deliberately: separately scaled they would
 * each fill the height and a month that earned a fortune would look exactly like
 * one that earned nothing, which is the one thing the pair is there to tell
 * apart. Income is drawn only when there is some — a flat line along the floor
 * says nothing and reads as a second axis.
 *
 * @param dailyOut spend per day in minor units, indexed from day 1 of the month.
 * @param dailyIn what came in per day, indexed the same way.
 * @param todayIndex zero-based index of today, or -1 when the month is not current.
 */
@Composable
fun MonthCurve(
    dailyOut: LongArray,
    dailyIn: LongArray,
    todayIndex: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val trackColor = WalletTheme.colors.stripTrack
    // The app's own two directions, so a curve means here what a figure means
    // everywhere else on the page.
    val outColor = WalletTheme.colors.moneyOut
    val inColor = WalletTheme.colors.moneyIn
    // The running totals, and how far along them we are allowed to draw. Past
    // today there is nothing to say: a flat run to the end of the month would
    // read as a fortnight of spending nothing rather than as a fortnight that
    // has not happened.
    val cumulative = remember(dailyOut) {
        var running = 0L
        LongArray(dailyOut.size) { running += dailyOut[it]; running }
    }
    val cumulativeIn = remember(dailyIn) {
        var running = 0L
        LongArray(dailyIn.size) { running += dailyIn[it]; running }
    }
    val lastDrawn = if (todayIndex in dailyOut.indices) todayIndex else dailyOut.lastIndex
    val peak = if (lastDrawn >= 0) {
        maxOf(
            cumulative.getOrElse(lastDrawn) { 0L },
            cumulativeIn.getOrElse(lastDrawn) { 0L },
        )
    } else {
        0L
    }

    val progress by animateFloatAsState(
        targetValue = if (peak > 0L) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "monthCurveGrowth",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
    ) {
        val days = dailyOut.size
        if (days == 0) return@Canvas

        val baseline = size.height - 8.dp.toPx()
        val top = 4.dp.toPx()
        val span = baseline - top
        // One slot per day, and the point sits in the middle of its slot: a day
        // is a span of time rather than an instant, and anchoring day one to the
        // very edge made the first day of the month look like the moment before
        // it began.
        val slot = size.width / days
        fun xOf(day: Int) = slot * (day + 0.5f)

        // The track spans every day of the month whether or not it has arrived.
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, baseline),
            size = Size(size.width, 1.5.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )

        if (peak <= 0L || lastDrawn < 0) return@Canvas

        fun draw(totals: LongArray, color: Color, washed: Boolean) {
            val points = (0..lastDrawn).map { day ->
                val fraction = totals[day].toFloat() / peak.toFloat()
                Offset(xOf(day), baseline - fraction * span * progress)
            }
            if (points.size > 1) {
                // The wash under the line, fading out downwards so it reads as
                // depth rather than as a second block of colour with an edge of
                // its own. Only under the spending: two washes on one chart
                // overlap into a third colour that means nothing, and what went
                // out is the figure the card leads with.
                if (washed) {
                    val area = Path().apply {
                        moveTo(points.first().x, baseline)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, baseline)
                        close()
                    }
                    drawPath(
                        path = area,
                        brush = Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.22f), Color.Transparent),
                            startY = top,
                            endY = baseline,
                        ),
                    )
                }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = line,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            // Where it has got to. On a month with one day's movement in it this
            // is the whole chart, which is why it is drawn whether or not there
            // was a line to put it at the end of.
            drawCircle(color = color, radius = 3.5.dp.toPx(), center = points.last())
        }

        // Income first, so the spending's own wash and line sit over it: what
        // the card leads with is what went out.
        if (cumulativeIn.getOrElse(lastDrawn) { 0L } > 0L) {
            draw(cumulativeIn, inColor, washed = false)
        }
        draw(cumulative, outColor, washed = true)
    }
}

/**
 * The curve plus its two end labels and a plain-language caption.
 *
 * The caption is the point: a picture the user has to decode is worse than no
 * picture, so the sentence always says what the shape means.
 */
@Composable
fun MonthCurveSection(
    dailyOut: LongArray,
    dailyIn: LongArray,
    todayIndex: Int,
    startLabel: String,
    endLabel: String,
    /**
     * The line under the chart, or null for none.
     *
     * Nullable because it used to count the days spent on underneath itself — a
     * sentence restating in words what the picture had just drawn. All that is
     * left is the empty case, where there is no line to read and the caption is
     * the only thing saying why.
     */
    caption: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Column(modifier = modifier) {
        MonthCurve(
            dailyOut = dailyOut,
            dailyIn = dailyIn,
            todayIndex = todayIndex,
            contentDescription = contentDescription,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
