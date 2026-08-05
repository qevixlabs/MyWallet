package com.mywallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mywallet.ui.theme.OnDarkPanel
import com.mywallet.ui.theme.WalletTheme

/** The same corner every card in the app is cut to. */
private val CARD_CORNER = 20.dp

/** The shortest the face may be drawn, whatever it has to say. */
private val CARD_MIN_HEIGHT = 180.dp

/**
 * The quiet ink on the card face — its label, and the lines under the headline
 * that are notes about the figure rather than the figure itself.
 *
 * Not the dark scheme's `onSurfaceVariant`, which is what those took at first.
 * That colour is a cool grey chosen to be read against a near-black page, and
 * the face is neither near-black nor grey: over the blue it lost most of its
 * contrast and over the green corner it went muddy, so the two lines a reader
 * has to work hardest for — what is held in another currency, and what it all
 * comes to — were the least legible things on the card.
 *
 * White at a little under three-quarters instead. It is the *same* ink the
 * gradient is already carrying, so it holds the same distance from the face at
 * both ends of the sweep, which no opaque colour can do; and it stays clearly
 * under the headline, which is the one thing on the card drawn at full strength.
 */
val CardFaceMuted = Color.White.copy(alpha = 0.72f)

/**
 * The face of a payment card, and the one place in the app that draws one.
 *
 * It exists for a single figure — what the user actually holds, at the top of
 * the Accounts tab. That number is the one thing on the page nobody has to read
 * a label to want, and it was set on the same white card as everything else, so
 * the page opened on four identical rectangles and the eye had to be told by the
 * type size which of them mattered. A card face is the one shape a reader
 * already knows carries a balance, and it says so before a word of it is read.
 *
 * Two rules keep it from becoming decoration:
 *
 *  - **It is drawn, not depicted.** The chip and the contactless arcs are a few
 *    strokes on a `Canvas` rather than artwork — nothing to keep in step with
 *    the theme, nothing to redraw at four densities, and no image asset whose
 *    licence somebody has to remember. They are also the *only* marks on it: a
 *    magnetic stripe, a signature panel and a network logo would each be a
 *    picture of a card rather than a card, and none of the three would be
 *    saying anything about the user's money.
 *  - **Nothing on it is a real card.** It carries no number, no name and no
 *    expiry, and it must not grow one: this is a total across every holding the
 *    user has, and dressing it as a particular bank's card would be the app
 *    inventing an account.
 *
 * Everything inside is drawn in [OnDarkPanel], because the face is dark in both
 * schemes and the ink that reads on it is therefore the dark scheme's whatever
 * the phone is set to.
 */
@Composable
fun BankCardFace(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Read before the dark override below, or the face would be the dark
    // scheme's stops on a phone set to light.
    val face = WalletTheme.colors.cardFace
    Box(
        modifier = modifier
            .fillMaxWidth()
            // A card is an object with a shape of its own, and it has to keep
            // that shape on a phone with nothing on it yet. Drawn to its
            // content, an account list with no debts and nothing held abroad
            // left the face two lines tall — a coloured strip across the top of
            // the page rather than a card, and exactly the state a new user sees
            // first. The floor is a little under a real card's proportion at
            // this width, so the lines that do arrive grow it downwards rather
            // than being cramped into it.
            .heightIn(min = CARD_MIN_HEIGHT)
            // Clipped first, so both the gradient and the rings below stop at
            // the corner instead of painting past it.
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(Brush.linearGradient(face))
            .drawBehind {
                // Two soft rings running off the right edge — what gives the
                // face its depth, and the whole of its decoration. Drawn after
                // the gradient and before the content, so they sit *in* the
                // card rather than over the figures.
                //
                // They are white at a few percent rather than a colour of their
                // own: the gradient travels from indigo to green across them,
                // and any tint that looked right at one end was a stain at the
                // other.
                val r = size.height * 0.78f
                drawCircle(
                    color = Color.White.copy(alpha = 0.055f),
                    radius = r,
                    center = Offset(size.width * 0.92f, size.height * 0.10f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.045f),
                    radius = r * 0.68f,
                    center = Offset(size.width * 1.04f, size.height * 0.92f),
                )
            },
    ) {
        OnDarkPanel {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChipGlyph()
                    ContactlessGlyph()
                }
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

/**
 * The gold contact plate, in five strokes.
 *
 * Proportioned rather than detailed: at the size this is drawn, a chip is a warm
 * rounded rectangle with a grid across it, and anything finer than that is
 * texture nobody sees. The grid is a wash of its own brown rather than black,
 * which at this size read as dirt on the gold.
 */
@Composable
private fun ChipGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 34.dp, height = 26.dp)) {
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFFF2DCA0), Color(0xFFC9A24A)),
            ),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        val contact = Color(0xFF7A5E1E).copy(alpha = 0.45f)
        val w = 1.dp.toPx()
        listOf(0.34f, 0.66f).forEach { y ->
            drawLine(
                color = contact,
                start = Offset(0f, size.height * y),
                end = Offset(size.width, size.height * y),
                strokeWidth = w,
            )
        }
        listOf(0.32f, 0.68f).forEach { x ->
            drawLine(
                color = contact,
                start = Offset(size.width * x, 0f),
                end = Offset(size.width * x, size.height),
                strokeWidth = w,
            )
        }
    }
}

/**
 * The three arcs every contactless card carries.
 *
 * Struck from a centre off the left edge so they read as one wave opening
 * rightwards; drawn from the same centre, or the gaps between them would not be
 * even. Kept at a little over half white — at full strength it competed with the
 * figure below it, which is the only thing on the card anybody came for.
 */
@Composable
private fun ContactlessGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 22.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val ink = Color.White.copy(alpha = 0.55f)
        val centre = Offset(0f, size.height / 2f)
        repeat(3) { i ->
            val radius = size.height * (0.24f + 0.19f * i)
            drawArc(
                color = ink,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = stroke,
            )
        }
    }
}

/**
 * One of the card's supporting lines with a mark ahead of it.
 *
 * The lines under the headline run in three directions — what is owed, what is
 * owed to the user, and the two netted off — and at `bodyMedium` on a dark face
 * the only thing telling them apart was a colour. That is the one signal a
 * reader may not have, which is why every amount in this app already carries a
 * sign; the mark is the same argument applied to a line rather than a figure.
 *
 * No content description: the words beside it say what it is, and a screen
 * reader announcing "arrow, upward" ahead of them would be reading the
 * punctuation aloud.
 */
@Composable
fun CardFaceLine(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        text()
    }
}
