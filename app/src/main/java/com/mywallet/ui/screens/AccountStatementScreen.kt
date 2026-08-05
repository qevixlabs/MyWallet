package com.mywallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Receipt
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.ui.components.ConfirmDeleteDialog
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.LaterPaymentFirstDialog
import com.mywallet.ui.components.PAGE_SIZE
import com.mywallet.ui.components.ListPageHeader
import com.mywallet.ui.components.SeeMore
import com.mywallet.ui.components.SwipeToDelete
import com.mywallet.ui.components.WalletCard
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.theme.TitleStyle
import com.mywallet.ui.theme.WalletTheme
import com.mywallet.ui.components.TopSnackbar
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarResult

/**
 * Everything that has touched one account, newest first, with what each movement
 * left behind.
 *
 * A page of its own, and the same page a debt's payments get — see
 * [LoanLedgerScreen], which this is deliberately built to match: one back arrow,
 * the holding's name in the bar, where it stands now in a card, then the rows.
 * Two lists that answer the same question about two kinds of holding must not be
 * two different-looking screens.
 *
 * It was a collapsible block inside the holding's editor. Three things were
 * wrong with that: the editor is one long scrolling `Column`, so the list is not
 * lazy and every row of a decade-old salary account composed the moment the
 * toggle was tapped; opening it pushed the colour picker and Save a screen and a
 * half down a form that was already long; and the column a reader opens it for —
 * the running balance — was read through a viewport a third of a page tall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountStatementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** A movement opens where it was recorded — see [StatementTarget]. */
    onOpenEntry: (String) -> Unit = {},
    onOpenLoan: (String) -> Unit = {},
    viewModel: AccountStatementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A page at a time. Saved rather than remembered, so a rotation does not
    // fold the reader back to the top of a list they had walked down.
    var shown by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    // A swipe asks first, exactly as it does on the timeline and on a debt's own
    // statement: one gesture, one question, wherever the row is read.
    var confirming by remember { mutableStateOf<String?>(null) }
    // And says so once it has gone. A row vanishing from under the thumb with
    // nothing said reads as the page having glitched rather than as the delete
    // landing. Counted, not flagged — see [AccountStatementState.deletedCount].
    val snackbar = remember { SnackbarHostState() }
    val deleted = stringResource(R.string.deleted_snackbar)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.deletedCount) {
        if (state.deletedCount > 0) {
            // With the offer to undo, because this is a list a thumb swipes by
            // accident and the row it takes moves a balance. See
            // [AccountStatementViewModel.undoDelete].
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
                            state.kindRes?.let {
                                Text(
                                    text = stringResource(it),
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
                if (!state.isLoading) {
                    item { StatementSummary(state) }
                }

                if (state.isEmpty) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.statement_title),
                            body = stringResource(R.string.statement_empty),
                            // The mark the rows this page is waiting for would have
                            // carried — the same one the tab they are listed on
                            // wears.
                            icon = Icons.Outlined.Receipt,
                        )
                    }
                }

                if (state.rows.isNotEmpty()) {
                    item {
                        ListPageHeader(
                            title = stringResource(R.string.statement_every),
                            icon = Icons.Outlined.Receipt,
                            // What the list is and what can be done to it. The swipe
                            // is the only thing here that leaves no mark on the row
                            // it acts on, which is why it is the part said in words.
                            explain = stringResource(R.string.statement_explain),
                        )
                    }
                    val shownRows = state.rows.take(shown)
                    itemsIndexed(shownRows, key = { _, it -> it.id }) { index, row ->
                        // Where this row goes when it is tapped, or null for one that
                        // has nowhere to go — see [StatementRow.opens].
                        val open: (() -> Unit)? = when (val target = row.opens) {
                            is StatementTarget.Entry -> ({ onOpenEntry(target.entryId) })
                            is StatementTarget.Loan -> ({ onOpenLoan(target.loanId) })
                            null -> null
                        }
                        // Banded like every other list of movements — see
                        // [rowStripe]. Inside the swipe, so a drag still uncovers the
                        // red rather than this.
                        val banded = Modifier.background(rowStripe(index))
                        // On the same paper Home and Reminders lay their movements
                        // on — see [listPanel]. One sheet, painted a row at a time,
                        // and it ends at the last row shown: what is past that is a
                        // button asking for the next page, not part of this one.
                        val paper = Modifier.listPanel(
                            first = index == 0,
                            last = index == shownRows.lastIndex,
                            // A swipeable row paints it itself, below, or the page
                            // rather than the red shows through the gap.
                            paint = !row.canDelete,
                        )
                        // Swipeable except one instalment of a loan's own schedule,
                        // which belongs to the schedule rather than to this page.
                        if (row.canDelete) {
                            SwipeToDelete(
                                rowKey = row.id,
                                onSwiped = { confirming = row.id },
                                background = WalletTheme.colors.listSurface,
                                modifier = paper,
                            ) {
                                StatementRowView(row = row, onClick = open, modifier = banded)
                            }
                        } else {
                            StatementRowView(
                                row = row,
                                onClick = open,
                                modifier = paper.then(banded),
                            )
                        }
                    }
                    // The way to the next page, at the foot of the one being read.
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

/** Where the account stands now, and what the bank has added to it. */
@Composable
private fun StatementSummary(state: AccountStatementState) {
    WalletCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.statement_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = state.balance, style = MaterialTheme.typography.headlineSmall)
        // The quarters themselves are rows in the list below — they are entries
        // like any other — so this is a total and not a second copy of them.
        state.interestTotal?.let {
            Text(
                text = stringResource(R.string.interest_paid_line, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
