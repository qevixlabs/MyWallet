package com.mywallet.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Payments
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 * **A row opens the payment it is a line about.** Every row here is an entry —
 * the money really left an account on a day — so a tap goes to that entry, the
 * way a tap on any other list of movements in the app does. It used to open the
 * *account* instead, which answered a question nobody had arrived with: a reader
 * on this page is checking one payment, and the row that describes it led
 * anywhere except to it.
 *
 * It was withheld altogether before that, on the reasoning that a payment's
 * amount is corrected where it was recorded and that changing a figure here
 * would edit an entry whose effect on the balance lives in the loan. That is a
 * reason for the *editor* to be careful — and it is; a loan's instalment opens
 * with its schedule controls withheld — not a reason for the only list of a
 * debt's payments to be the one list in the app that leads nowhere.
 *
 * A row is also swiped away — every movement, the instalments included. Removing
 * one puts the debt back where the payment found it: a lump sum's money goes back
 * on the balance, and an instalment leaves a period that charged its interest and
 * cleared nothing, which the next payment then collects. See [Arrears].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanLedgerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the payment itself — the entry that recorded it, which is where its
     * date, its amount and the account it left are written down.
     */
    onOpenEntry: (String) -> Unit = {},
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
                        //
                        // The band is *all* that separates them. There used to be a
                        // hairline under every row as well, which is a ladder — and
                        // it made this page and an account's statement, the same
                        // list about the other kind of holding, two different-looking
                        // screens.
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
                        // Every row swipes away, an instalment included — see
                        // [LedgerRow.canDelete]. The flag survives for the day
                        // something here genuinely cannot be undone.
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
                                    onOpenEntry = onOpenEntry,
                                    modifier = banded,
                                )
                            }
                        } else {
                            LedgerRowView(
                                row = row,
                                isLent = state.isLent,
                                kind = state.kind,
                                onOpenEntry = onOpenEntry,
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
        Spacer(Modifier.height(4.dp))
        Text(text = outstanding, style = MaterialTheme.typography.headlineSmall)
        // What the debt comes to in the currency the totals are read in — the
        // second line every foreign figure in the app carries.
        state.outstandingConverted?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.totalPaid?.let {
            Text(
                text = stringResource(
                    if (state.isLent) R.string.loan_ledger_received else R.string.loan_ledger_paid,
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WalletTheme.colors.moneyIn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.totalAdded?.let {
            Text(
                text = stringResource(
                    if (state.isLent) R.string.loan_ledger_lent_more else R.string.loan_ledger_added,
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // Interest carries no date, so it sits here rather than among the rows:
        // no day did it, and a dated line for it would be a payment nobody made.
        state.accruedInterest?.let {
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_interest_accrued_lent
                    } else {
                        R.string.loan_interest_accrued
                    },
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isLent) colors.moneyIn else colors.debt,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.settleToday?.let {
            Text(
                text = stringResource(R.string.loan_settle_today, it),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * One line of the statement: when, what, how much, and what was left.
 *
 * The balance sits under the amount rather than in a column of its own. A phone
 * cannot give four columns enough room to stay readable in either script, and
 * the figure that matters most — what changed hands — must never be the one that
 * gets squeezed.
 */
@Composable
private fun LedgerRowView(
    row: LedgerRow,
    isLent: Boolean,
    /** Which debt this is: what a movement is *called* depends on it. */
    kind: LoanKind?,
    modifier: Modifier = Modifier,
    onOpenEntry: (String) -> Unit = {},
) {
    val dates = LocalDateDisplay.current
    val colors = WalletTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Opens the payment itself. Every row on this page is an entry —
            // [LedgerRow.id] *is* its id — so there is always somewhere to go,
            // and the page it goes to is the one that answers what a reader
            // checking a line on a statement has come to ask.
            .clickable { onOpenEntry(row.id) }
            // Inset from the paper's edge rather than from the page's, the way
            // a card's rows are — see [listPanel].
            .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // With the year, unlike a day header on the timeline. A debt
                // between people can run for years, and "20 Saun" on its own
                // cannot be told from the same day three years earlier — which
                // is exactly the row someone will be disputing.
                text = dates.full(row.date),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // What the movement did, with what the user wrote about it on
                // the end — "Borrowed more - car repair". The note used to sit
                // on a line of its own below, which said the same thing in two
                // places on the ledger and in one on every other list; joined
                // here it is the one sentence the app writes about a payment,
                // written the same way wherever the payment is drawn.
                //
                // **Except on something bought with the facility, where the note
                // is the whole of it.** What the user typed is the name of the
                // thing — "Groceries" — and the words in front of it were the
                // app naming the instrument: "Spent on card · Groceries" on an
                // overdraft, which is not a card and never had one. The kind is
                // already the page's own heading; what a row has to say is which
                // purchase it was. Only where nothing was typed does the plain
                // word stand in, since a row cannot be blank.
                text = listOfNotNull(
                    if (row.kind == LoanMovementKind.SPEND) {
                        row.note ?: stringResource(R.string.loan_movement_spent)
                    } else {
                        stringResource(row.kind.labelRes(isLent, kind)).withNote(row.note)
                    },
                    row.accountName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dates.secondary(row.date)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // What the instalment bought. Early on most of it is interest, and a
            // debt that barely moved after a full payment reads as the app
            // having lost the money unless the row says why.
            if (row.splitPrincipal != null && row.splitInterest != null) {
                Text(
                    text = stringResource(
                        R.string.loan_emi_split,
                        row.splitPrincipal,
                        row.splitInterest,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                // What that payment did to the *debt* is the balance line
                // underneath, which is the column for it: "Left after it" repeats
                // the previous figure on these rows, because servicing interest
                // leaves the balance exactly where it was. That is the honest
                // division of labour, and it is why the sign here no longer tries
                // to say both things at once.
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
            row.balanceAfter?.let {
                Text(
                    text = stringResource(R.string.loan_schedule_left, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
