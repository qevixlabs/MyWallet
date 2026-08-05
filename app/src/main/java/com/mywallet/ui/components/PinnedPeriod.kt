package com.mywallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The period stepper held above the list it steps, and kept there.
 *
 * It used to be the list's first row, so a month with a fortnight of movements
 * in it scrolled the one control saying *which* month is being read clean off
 * the top — and the page then answered a question nothing on screen was asking.
 *
 * It does not give way as the page is read down. That was tried: the header slid
 * up out of the way on a downward drag and came back on the first upward one,
 * the way a website's does. What it produced was a heading that was *usually*
 * absent and reappeared when the reader nudged the page — so "which month is
 * this?" still could not be answered by looking, only by scrolling. The month is
 * the smallest thing on the page and the one every figure below it is relative
 * to; a line of a phone screen is what it is worth.
 *
 * Pinned the same way the timeline's day headings are, and above them: this is a
 * plain `Column`, so the list's own top edge sits directly under the stepper and
 * a `stickyHeader` inside it pins flush there rather than behind it.
 *
 * The header must be opaque, since the rows pass behind it on their way out.
 *
 * @param header the stepper. Drawn once, at the top, whatever the list holds.
 * @param content the list, handed the modifier it should carry.
 */
@Composable
fun PinnedPeriodHeader(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Opaque: the rows travel behind it on their way out.
                .background(MaterialTheme.colorScheme.background),
        ) {
            header()
        }
        content(Modifier.fillMaxSize())
    }
}
