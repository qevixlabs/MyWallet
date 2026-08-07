package com.mywallet.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.domain.LoanMovementKind
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.components.ConfirmDeleteDialog
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.LaterPaymentFirstDialog
import com.mywallet.ui.components.PAGE_SIZE
import com.mywallet.ui.components.ListPageHeader
import com.mywallet.ui.components.SeeMore
import com.mywallet.ui.components.SwipeToDelete
import com.mywallet.ui.components.WalletCard
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.labelRes
import com.mywallet.ui.withNote
import com.mywallet.ui.theme.DayNumberStyle
import com.mywallet.ui.theme.MoneySmallStyle
import com.mywallet.ui.theme.TitleStyle
import com.mywallet.ui.theme.WalletTheme
import com.mywallet.ui.components.TopSnackbar
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarResult

/**
 * What has actually happened between the user and one debt.
 *
 * "You owe रू 6,000" is the end of a story the user also needs to be able to
 * tell: रू 1,000 on the 4th, another रू 3,000 a fortnight later, and रू 2,000
 * more lent out in between. Each row therefore carries what was left owing
 * afterwards, so any single line can be checked against the other person's
 * memory of it — which is the only thing money between people is ever settled
 * by.
 *
 * **A row turns over and shows its working.** A tap does not open an editor: the
 * rows on this page are the app's own record of what the debt did — an instalment
 * its rule wrote, the interest its rate charged, the balance its schedule arrived
 * at — and every figure on them is read back out of the loan rather than stored
 * on the row. Sending a tap to the entry form offered to retype an instalment the
 * schedule would go on quoting its own figure for, which is a second opinion this
 * page has no way to honour. An amount that was genuinely mistyped is corrected on
 * the card that recorded it, which is where the app took it in.
 *
 * What a reader actually arrives here with is *why did रू 10,984 barely move the
 * balance?*, and the row now answers it in place. The two facts that answer it
 * used to be on the front: the split crushed into a sentence under the date, and
 * what was left stranded at the foot of the amount column — three unrelated
 * remarks in three corners of one row, none of them saying they were parts of a
 * single sum. Turned over, they are that sum, with the figure it was worked out
 * *from* — which the front never carried at all and without which the reader had
 * the answer and neither of the numbers behind it. See [LedgerRowWorking].
 *
 * A row is swiped away — every payment, the instalments included. Removing one
 * puts the debt back where the payment found it: a lump sum's money goes back on
 * the balance, and an instalment leaves a period that charged its interest and
 * cleared nothing, which the next payment then collects. See [Arrears]. **The one
 * row that stays is the debt arriving**, which is not something that happened to
 * the loan but the loan itself — see [LedgerRow.canDelete].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanLedgerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoanLedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A page at a time. A debt several years old has a hundred rows behind it and
    // the payment being checked is nearly always one of the last few; the rest are
    // a scroll nobody asked for. Saved rather than remembered so a rotation does
    // not fold the reader back to the top of a list they had walked down.
    var shown by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    // A swipe asks first here for the reason it asks on the timeline: this list
    // has no Undo behind it at all, and a payment removed by a stray thumb moves
    // the debt.
    var confirming by remember { mutableStateOf<String?>(null) }
    // Which rows are showing their working. A list rather than a single id, so
    // two instalments can be turned over at once: what a reader is usually doing
    // with this page is watching the split move from one payment to the next,
    // and a page that turned the last row back every time it turned a new one
    // would answer that question one half at a time. Saved for the reason the
    // page count is: a rotation must not fold the reader's work back up.
    val turned = rememberSaveable { mutableStateListOf<String>() }
    // And says so once it has gone, the way every other list that removes a
    // movement does. A row vanishing from under the thumb with nothing said
    // reads as the page having glitched rather than as the delete landing.
    val snackbar = remember { SnackbarHostState() }
    val deleted = stringResource(R.string.deleted_snackbar)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.deletedCount) {
        if (state.deletedCount > 0) {
            // With the offer to undo, because this is a list a thumb swipes by
            // accident and the row it takes moves a balance. See
            // [LoanLedgerViewModel.undoDelete].
            val result = snackbar.showSnackbar(
                message = deleted,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
        }
    }
    // The alert goes over the page from the top; see [TopSnackbar].
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.name,
                                style = TitleStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Which of the bank's products, or that this is money
                            // with a person. One name can cover three holdings, and
                            // a statement that did not say which is unreadable.
                            state.kind?.let {
                                Text(
                                    text = stringResource(it.labelRes()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                state.outstanding?.let { outstanding ->
                    item {
                        LedgerSummary(state = state, outstanding = outstanding)
                    }
                }

                if (state.isEmpty) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.loan_ledger_empty_title),
                            body = stringResource(R.string.loan_ledger_empty),
                            // What this page lists is payments, and this is the
                            // glyph a payment wears everywhere else in the app.
                            icon = Icons.Outlined.Payments,
                        )
                    }
                }

                if (state.rows.isNotEmpty()) {
                    item {
                        ListPageHeader(
                            title = stringResource(R.string.loan_ledger_title),
                            icon = Icons.Outlined.Payments,
                            // A debt on a schedule has to say the second half of it:
                            // a deleted instalment is money that is late rather than
                            // money forgiven, and the next payment collects both.
                            // Money between people has no schedule to say that about
                            // — a missed instalment is a thing it cannot have — so
                            // its line stops at what the swipe does and what comes
                            // back, which is the whole of what happens there.
                            explain = stringResource(
                                if (state.kind == LoanKind.PERSONAL) {
                                    R.string.loan_ledger_explain_person
                                } else {
                                    R.string.loan_ledger_explain
                                }
                            ),
                        )
                    }
                    val shownRows = state.rows.take(shown)
                    itemsIndexed(shownRows, key = { _, it -> it.id }) { index, row ->
                        // Banded like every other list of movements — see
                        // [rowStripe]. Inside the swipe, so a drag still uncovers
                        // the red rather than this.
                        val banded = Modifier.background(rowStripe(index))
                        // On the same paper Home and Reminders lay their movements
                        // on, and the same paper the account statement — the same
                        // list about the other kind of holding — is laid on. See
                        // [listPanel].
                        val paper = Modifier.listPanel(
                            first = index == 0,
                            last = index == shownRows.lastIndex,
                            // A swipeable row paints it itself, below, or the page
                            // rather than the red shows through the gap.
                            paint = !row.canDelete,
                        )
                        // Every payment swipes away, an instalment included; the
                        // debt arriving does not — see [LedgerRow.canDelete].
                        val turn = {
                            if (!turned.remove(row.id)) turned.add(row.id)
                            Unit
                        }
                        if (row.canDelete) {
                            SwipeToDelete(
                                rowKey = row.id,
                                onSwiped = { confirming = row.id },
                                background = WalletTheme.colors.listSurface,
                                modifier = paper,
                            ) {
                                LedgerRowView(
                                    row = row,
                                    isLent = state.isLent,
                                    kind = state.kind,
                                    turned = row.id in turned,
                                    onTurn = turn,
                                    modifier = banded,
                                )
                            }
                        } else {
                            LedgerRowView(
                                row = row,
                                isLent = state.isLent,
                                kind = state.kind,
                                turned = row.id in turned,
                                onTurn = turn,
                                modifier = paper.then(banded),
                            )
                        }
                    }
                    // The way to the next page, at the foot of the one being read.
                    // Inset to the words above it rather than to the page margin, so
                    // it reads as the end of this list and not as a row of its own.
                    if (shown < state.rows.size) {
                        item {
                            SeeMore(
                                onClick = { shown += PAGE_SIZE },
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        TopSnackbar(snackbar, Modifier.align(Alignment.TopCenter))
    }

    confirming?.let { id ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.entry_delete_title),
            body = stringResource(R.string.entry_delete_body),
            onConfirm = {
                confirming = null
                viewModel.delete(id)
            },
            onDismiss = { confirming = null },
        )
    }

    if (state.blockedByLaterPayment) {
        LaterPaymentFirstDialog(onDismiss = viewModel::dismissBlocked)
    }
}

/** Where it stands now, and what got it there. */
@Composable
private fun LedgerSummary(state: LoanLedgerState, outstanding: String) {
    val colors = WalletTheme.colors
    WalletCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        // The eyebrow wears the mark of what this figure is — drawn on a
        // facility, coming back from a person, or owed to a bank — the way
        // every card on the holding's own editor heads itself.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    state.isOverdraft -> Icons.Outlined.CreditCard
                    state.isLent -> Icons.AutoMirrored.Outlined.CallReceived
                    else -> Icons.Outlined.Payments
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    when {
                        state.isOverdraft -> R.string.loan_drawn_title
                        state.isLent -> R.string.loan_owed_to_you_title
                        else -> R.string.loan_owed_title
                    }
                ).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(text = outstanding, style = MaterialTheme.typography.headlineSmall)
        // What the debt comes to in the currency the totals are read in — the
        // second line every foreign figure in the app carries. Unmarked: it is
        // the headline said again, not a fact of its own.
        state.outstandingConverted?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Each line below carries a small quiet mark of what kind of fact it
        // is, so the card reads as a keyed summary rather than a paragraph.
        // The marks stay in the quiet ink whatever colour the words take: the
        // words carry the judgement, the mark only says which fact.
        state.totalPaid?.let {
            SummaryLine(
                icon = Icons.Outlined.Check,
                text = stringResource(
                    if (state.isLent) R.string.loan_ledger_received else R.string.loan_ledger_paid,
                    it,
                ),
                color = colors.moneyIn,
                topPadding = 6.dp,
            )
        }
        state.totalAdded?.let {
            SummaryLine(
                icon = Icons.Outlined.Add,
                text = stringResource(
                    if (state.isLent) R.string.loan_ledger_lent_more else R.string.loan_ledger_added,
                    it,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                topPadding = 2.dp,
            )
        }
        // Interest carries no date, so it sits here rather than among the rows:
        // no day did it, and a dated line for it would be a payment nobody made.
        state.accruedInterest?.let {
            SummaryLine(
                icon = Icons.Outlined.Percent,
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_interest_accrued_lent
                    } else {
                        R.string.loan_interest_accrued
                    },
                    it,
                ),
                color = if (state.isLent) colors.moneyIn else colors.debt,
                topPadding = 6.dp,
            )
        }
        state.settleToday?.let {
            SummaryLine(
                icon = Icons.Outlined.Payments,
                text = stringResource(R.string.loan_settle_today, it),
                color = MaterialTheme.colorScheme.onSurface,
                topPadding = 2.dp,
            )
        }
    }
}

/** One fact of the summary: a quiet mark, then the sentence. */
@Composable
private fun SummaryLine(
    icon: ImageVector,
    text: String,
    color: Color,
    topPadding: Dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = topPadding),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * The day a payment fell on, at the head of its own row — the timeline's big
 * figure worn the way Home's rows wear their mark, since this list has no day
 * groups to hang a heading on: a debt moves a handful of times a year, and a
 * heading over every single row was a page of headings.
 *
 * The figure alone, in whichever calendar is being read. Which month and which
 * year it counts in — a debt's statement spans years, and "20" alone cannot be
 * told from the same day three years earlier — is the tail of the row's own
 * subtext, where the rest of what the payment has to say already is.
 *
 * As wide as it is and no wider — the timeline's own lesson: a gutter sized
 * for two digits stranded the single-digit days a gap away from their own
 * words, and the words shifting a few points between rows is the cheaper
 * price.
 */
@Composable
private fun LedgerRowDate(date: java.time.LocalDate) {
    val dates = LocalDateDisplay.current
    Text(
        text = dates.dayNumber(date),
        style = DayNumberStyle,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/**
 * One line of the statement, and the working behind it on the other side.
 *
 * **Both faces are always composed, one of them invisible**, so the box is as
 * tall as the taller of the two whichever way it is showing. Drawing only the
 * face in view is a row that changes height in the middle of a turn — the rows
 * below it jump a few points as the card goes edge-on, which reads as the list
 * having reflowed rather than as one card turning. The cost is a few extra lines
 * of text laid out per row and nothing else; the hidden face is taken out of the
 * semantics tree as well, or every row would be read aloud twice.
 *
 * A row with nothing behind it does not turn at all — see [LedgerRow.hasWorking].
 * A card that turns to a blank back is worse than one that does not turn.
 */
@Composable
private fun LedgerRowView(
    row: LedgerRow,
    isLent: Boolean,
    /** Which debt this is: what a movement is *called* depends on it. */
    kind: LoanKind?,
    /** Whether this row is showing its working rather than the payment. */
    turned: Boolean,
    onTurn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept as the state rather than unwrapped by `by`: read inside a
    // `graphicsLayer` block the angle is a draw-phase read, so a turning row
    // repaints without recomposing — read out here it would recompose every
    // row on the page once per frame of the animation.
    val turn = animateFloatAsState(
        targetValue = if (turned) 180f else 0f,
        // Long enough to read as an object turning over rather than as the row
        // having been swapped for a different one, which is the whole of what
        // the animation is for: the reader has to see that these figures belong
        // to the line they just tapped.
        animationSpec = tween(durationMillis = 360),
        label = "ledgerRowTurn",
    )
    val turnLabel = stringResource(
        if (turned) R.string.loan_working_hide else R.string.loan_working_show
    )
    Box(
        // The shorter face sits in the middle of the taller one's height rather
        // than at the top of it. Left to the default the front of a three-line
        // row hung from the top of a box measured for the back's four, and every
        // unturned row on the page read as having a blank line under it.
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            // Says what the tap does to a reader who cannot see the card turn.
            // A row is not a button and takes no role: what it is is a line of a
            // statement, and the label is what happens if you touch it.
            .then(
                if (row.hasWorking) {
                    Modifier.clickable(onClickLabel = turnLabel) { onTurn() }
                } else {
                    Modifier
                }
            )
            .graphicsLayer {
                rotationY = turn.value
                // Well back from the default. Close in, a full-width row turning
                // on its own axis swings its far edge most of the way across the
                // page and reads as a door rather than as a card.
                cameraDistance = 24f * density
            },
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = if (turn.value > 90f) 0f else 1f }
                .then(if (turned) Modifier.clearAndSetSemantics {} else Modifier)
        ) {
            LedgerRowFace(row = row, isLent = isLent, kind = kind)
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    // Turned back the other way, or the words come out mirrored.
                    rotationY = 180f
                    alpha = if (turn.value > 90f) 1f else 0f
                }
                .then(if (turned) Modifier else Modifier.clearAndSetSemantics {})
        ) {
            LedgerRowWorking(row = row, isLent = isLent)
        }
    }
}

/**
 * The face of the row: when, what, and how much.
 *
 * What the payment *did* is no longer here. The split under the date and the
 * balance under the amount were two thirds of one sum drawn in two corners of
 * the row, and between them they made the front of every instalment five lines
 * deep. They are on the back now, where they add up — see [LedgerRowWorking].
 */
@Composable
private fun LedgerRowFace(
    row: LedgerRow,
    isLent: Boolean,
    kind: LoanKind?,
) {
    val colors = WalletTheme.colors
    val dates = LocalDateDisplay.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Inset from the paper's edge rather than from the page's, the way
            // a card's rows are — see [listPanel].
            .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 12.dp),
        // Centred, the way every other list's rows are: the day's figure sits
        // in the middle of the lines it heads, not hung from the first one —
        // top-aligned it read as belonging to the title alone, with the rest
        // of the row dangling under it.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerRowDate(date = row.date)
        // A share of the figure's own size, exactly as the timeline spaces its
        // day from the words beside it — see [DayLabel]. A fixed 12dp on top
        // of a fixed-width tile left the single-digit days a gulf from their
        // own words.
        Spacer(Modifier.width(with(LocalDensity.current) { DayNumberStyle.fontSize.toDp() / 3 }))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // What the movement did, with what the user wrote about it on
                // the end — "Borrowed more - car repair": the one sentence the
                // app writes about a payment, leading the row's words now that
                // the date is the figure beside them. The date lines the row
                // used to carry — one calendar on top and the other two lines
                // below — are that figure's block. See [LedgerRowDate].
                //
                // **Except on something bought with the facility, where the note
                // is the whole of it.** What the user typed is the name of the
                // thing — "Groceries" — and the words in front of it were the
                // app naming the instrument: "Spent on card · Groceries" on an
                // overdraft, which is not a card and never had one. The kind is
                // already the page's own heading; what a row has to say is which
                // purchase it was. Only where nothing was typed does the plain
                // word stand in, since a row cannot be blank.
                text = if (row.kind == LoanMovementKind.SPEND) {
                    row.note ?: stringResource(R.string.loan_movement_spent)
                } else {
                    stringResource(row.kind.labelRes(isLent, kind)).withNote(row.note)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            // The holding the money passed through, on its own quiet line the
            // way every other list names it.
            row.accountName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // And the rest of the date, always the last line of the words: the
            // month with its year, since a debt's statement spans them — and,
            // reading Nepali, the English date joined on with a dash rather
            // than dotted off as a fact of its own: "पौष २०८२ - 1 Jan" is one
            // date said twice. The day itself is the big figure beside the
            // row; this line is what that figure alone cannot say.
            Text(
                text = dates.monthAndYear(row.date) +
                    (dates.secondaryShort(row.date)?.let { " - $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // What was handed over, signed as a payment. Interest serviced on
                // its own is one: रू 10,984.93 really left the account and is
                // gone, and drawn unsigned and uncoloured among a column of
                // signed payments it read as a transfer — as though nothing had
                // happened — while the card above it was already counting the
                // same figure into "paid so far, interest included".
                //
                // What that payment did to the *debt* is the other face of the
                // row, which is the place for it: turned over, a serviced charge
                // says it was owing so much, all of the payment was interest, and
                // so much is owing still — the same figure top and bottom, which
                // is the fact, stated. That is the honest division of labour, and
                // it is why the sign here does not try to say both things at once.
                text = if (row.increases) "+" + row.amount else "−" + row.amount,
                style = MoneySmallStyle,
                color = when {
                    // More borrowed is a debt growing; more lent is the user's
                    // own money going out. Painting the second one in the debt
                    // colour would call an asset a liability.
                    row.increases -> if (isLent) colors.moneyOut else colors.debt
                    else -> colors.moneyIn
                },
            )
            row.converted?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The back of the row: the sum the front is the answer to.
 *
 * Four lines, in the order the arithmetic actually runs — what was owed when the
 * payment landed, what the lender took out of it first, what was left over to
 * come off the debt, and what that leaves owing. The last is ruled off from the
 * three above it and set in the money type, because it is the answer and the
 * others are the working; without the rule the block reads as four remarks in a
 * column and a reader has to work out for themselves which of them is the total.
 *
 * **Any line may be missing and none of them is invented to fill the gap.** The
 * app knows an instalment's split only from the schedule, which is the only thing
 * that can say how much of a payment the lender kept; a lump sum and a serviced
 * charge need no schedule, being all principal and all interest by definition;
 * and a loan re-based by a lump sum has no record of the balance it used to
 * carry. A face of two true lines is worth more than four with a guess in it.
 *
 * **The day stays where it was**, at the head of both faces. It is what a row is
 * called on this page, and a card that turned to a set of figures with nothing
 * naming which payment they belong to would be a different row rather than the
 * back of this one.
 */
@Composable
private fun LedgerRowWorking(row: LedgerRow, isLent: Boolean) {
    val colors = WalletTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerRowDate(date = row.date)
        Spacer(Modifier.width(with(LocalDensity.current) { DayNumberStyle.fontSize.toDp() / 3 }))
        Column(modifier = Modifier.weight(1f)) {
            row.balanceBefore?.let {
                WorkingLine(
                    label = stringResource(
                        if (isLent) R.string.loan_working_before_lent else R.string.loan_working_before
                    ),
                    value = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Interest first, because it is charged first: the lender takes what
            // the period cost and only what is over comes off the debt. Coloured
            // the way the summary card colours the interest built up on the same
            // page — money the borrower is out and the lender is up.
            row.workingInterest?.let {
                WorkingLine(
                    label = stringResource(R.string.loan_working_interest),
                    value = it,
                    color = if (isLent) colors.moneyIn else colors.debt,
                )
            }
            row.workingPrincipal?.let {
                WorkingLine(
                    label = stringResource(
                        if (isLent) {
                            R.string.loan_working_principal_lent
                        } else {
                            R.string.loan_working_principal
                        }
                    ),
                    value = it,
                    // The one part of the payment that bought anything, in the
                    // colour the page already gives what has been paid off.
                    color = colors.moneyIn,
                )
            }
            row.balanceAfter?.let {
                // Ruled off only when there is working above it to rule off. On
                // a row that can say nothing but the balance, a line over the
                // single figure would be a total of nothing.
                if (row.balanceBefore != null ||
                    row.workingInterest != null ||
                    row.workingPrincipal != null
                ) {
                    Hairline(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
                WorkingLine(
                    label = stringResource(
                        if (isLent) R.string.loan_working_after_lent else R.string.loan_working_after
                    ),
                    value = it,
                    color = MaterialTheme.colorScheme.onSurface,
                    isAnswer = true,
                )
            }
        }
    }
}

/**
 * One line of the working: what it is on the left, how much on the right.
 *
 * The label is weighted and the figure is not, so a long label in either script
 * gives way and the money keeps its width — the same division every row on this
 * page makes between its words and its amount.
 */
@Composable
private fun WorkingLine(
    label: String,
    value: String,
    color: Color,
    /** The total, set in the money type so it is found without being read. */
    isAnswer: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = if (isAnswer) MoneySmallStyle else MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * What to call a movement.
 *
 * Money going the other way needs its own words: "Instalment" is what the user
 * pays a bank and what a friend pays them, but "Borrowed more" and "Lent more"
 * are opposite events, and a row that got them the wrong way round would say the
 * debt had grown when it had been given.
 */
private fun LoanMovementKind.labelRes(isLent: Boolean, kind: LoanKind?): Int = when (this) {
    LoanMovementKind.INSTALMENT ->
        if (isLent) R.string.loan_movement_received else R.string.loan_movement_instalment
    // What the button that recorded it says, on every debt and both ways round
    // — see `loanMovementLabel`, which has to reach the same verdict: a row
    // called one thing here and another on the timeline is one payment wearing
    // two names. It read "Off the balance" and "Off what they owe", which named
    // the act in words the user had not seen on the screen they made it on.
    LoanMovementKind.PRINCIPAL -> R.string.prepay_title
    LoanMovementKind.INTEREST ->
        if (isLent) R.string.loan_movement_interest_lent else R.string.loan_movement_interest
    // What the card actually bought, where the user said so. A row reading
    // "Spent" down a column of them distinguishes nothing; the note is what
    // tells one purchase from the next, and the ledger falls back to the word
    // only where there is none.
    LoanMovementKind.SPEND -> R.string.loan_movement_spent
    LoanMovementKind.INCREASE -> when {
        kind == LoanKind.OVERDRAFT -> R.string.loan_movement_drawn
        isLent -> R.string.loan_movement_lent_more
        else -> R.string.loan_movement_borrowed_more
    }
    // The debt itself arriving. Named for what happened rather than for what it
    // did to the balance: "Borrowed more" on the row that is the borrowing reads
    // as a debt that has already grown past what was agreed.
    LoanMovementKind.OPENING ->
        if (isLent) R.string.loan_movement_lent else R.string.loan_movement_borrowed
}
