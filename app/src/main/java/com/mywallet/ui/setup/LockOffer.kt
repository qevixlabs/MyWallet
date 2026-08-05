package com.mywallet.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mywallet.R

/**
 * How much of the screen the sheet stands in.
 *
 * Drawn to its content it was a strip along the bottom, which reads as a
 * notice that has already been dismissed. Given about half the screen it is
 * something being offered — with the icon, the question and what it means each
 * on their own line, and the two answers at the foot where the thumb is.
 */
private const val SHEET_HEIGHT = 0.32f

/**
 * The lock, offered once from the bottom of the app the user is already using.
 *
 * Deliberately not a dialog on the way in. A box in the middle of the screen
 * demanding an answer before the app can be seen is a question asked of somebody
 * with no reason yet to want their money guarded — so it waits until they have
 * been in the app a while (see `MainActivity`) and then comes up from the bottom
 * where the thumb already is, over a page still doing its job.
 *
 * **Any tap dismisses it**, on the page behind it or on the sheet itself, which
 * is what makes it a suggestion rather than a question: the only tap that does
 * anything else is the one on the button that turns the lock on. Nothing is
 * written by dismissing, and the switch is still in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockOfferSheet(onEnable: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState()
    // No ripple and no indication: the whole sheet being tappable is a way out,
    // not a control, and a card that flashed under every touch would read as one.
    val interaction = remember { MutableInteractionSource() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            // Centred down the middle: the mark and the question are what the
            // sheet is, and set against the left margin they read as the first
            // row of a form rather than as one thing being offered.
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 20.dp)
                .navigationBarsPadding(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Fingerprint,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.setup_lock_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // The answers sit at the foot of the sheet rather than under the
            // words: it is the bottom of the screen the thumb is nearest, and
            // the space between says the reading is done.
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.setup_lock_skip))
                }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onEnable) {
                    Text(stringResource(R.string.setup_lock_enable))
                }
            }
        }
    }
}
