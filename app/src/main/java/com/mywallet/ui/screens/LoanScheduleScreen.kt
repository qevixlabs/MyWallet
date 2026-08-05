package com.mywallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.components.DateField
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.GROUP_HEADER_GUTTER
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_INSET
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.PAGE_SIZE
import com.mywallet.ui.components.ListPageHeader
import com.mywallet.ui.components.SeeMore
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.labelRes
import com.mywallet.ui.theme.TitleStyle
import java.time.LocalDate

/**
 * Every instalment a debt still owes, as a table.
 *
 * The point of it is the shape rather than any single row: the interest slice
 * falling and the balance following it down is the whole reason a loan costs what
 * it does, and none of that is visible from one instalment figure. Columns are
 * what make a shape legible — three stacked sentences per payment, which is what
 * this was before it was a table, is a list of facts you have to hold in your
 * head to compare.
 *
 * Dated, because "payment 34" means nothing and "Mangsir 2085" means a great
 * deal; and with the year, because a five-year schedule passes "20 Saun" five
 * times. Numbered only where there is no date to use instead.
 *
 * The figures drop their currency symbol and state it once above the table. Four
 * columns of "रू 92,041.74" do not fit a phone in either script, and the digits
 * are what the table is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanScheduleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoanScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A page at a time, like the statement this page is built to match. A
    // seven-year loan is eighty-four rows and the reader has usually come to see
    // the next two. Saved rather than remembered so a rotation does not fold
    // them back to the top of a list they had walked down.
    var shown by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    var splitting by remember { mutableStateOf(false) }

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
                        // Which of the bank's products this is. One name can
                        // cover three holdings, and a schedule that did not say
                        // which is unreadable.
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
            if (state.isEmpty) {
                item {
                    EmptyState(
                        title = stringResource(R.string.loan_schedule_done_title),
                        body = stringResource(R.string.loan_schedule_done),
                        // The one empty page in the app that is good news: a
                        // schedule with nothing left in it is a debt paid off,
                        // not a list waiting to be filled.
                        icon = Icons.Outlined.TaskAlt,
                    )
                }
            }

            if (state.rows.isNotEmpty()) {
                item { ScheduleNotes(state) }
                item {
                    // The column headings are the top of the table's own paper,
                    // the way a timeline day's heading is the top of that day's
                    // — see [listPanel]. A table of figures read down a column
                    // has more use for an edge than any other list in the app.
                    Column(modifier = Modifier.listPanel(first = true, last = false)) {
                        Row(
                            modifier = Modifier.padding(
                                start = LIST_PANEL_ROW_INSET,
                                end = LIST_PANEL_ROW_INSET,
                                top = 10.dp,
                            )
                        ) {
                            HeaderCell(
                                stringResource(R.string.loan_schedule_col_date), DATE_WEIGHT, false,
                            )
                            HeaderCell(
                                stringResource(R.string.loan_schedule_col_payment),
                                FIGURE_WEIGHT,
                                true,
                            )
                            HeaderCell(
                                stringResource(R.string.loan_schedule_col_interest),
                                FIGURE_WEIGHT,
                                true,
                            )
                            HeaderCell(
                                stringResource(R.string.loan_schedule_col_left), LEFT_WEIGHT, true,
                            )
                            Spacer(Modifier.width(SPLIT_SLOT))
                        }
                        Spacer(Modifier.height(6.dp))
                        Hairline(inset = LIST_PANEL_ROW_INSET)
                    }
                }
                val shownRows = state.rows.take(shown)
                itemsIndexed(shownRows, key = { _, it -> it.number }) { index, row ->
                    // Banded like every other list in the app that is read down a
                    // column — see [rowStripe]. A schedule is exactly the case the
                    // band was made for: a screenful of near-identical rows of
                    // digits, with nothing between them and no day headings to cut
                    // it into blocks, where losing your place by one line means
                    // reading the wrong month's balance.
                    //
                    // Only the first row can ever be the one collecting arrears:
                    // they fall on the next payment due and on no other.
                    ScheduleRowView(
                        row = row,
                        splits = index == 0 && state.carriedForward > 0,
                        onSplit = { splitting = true },
                        modifier = Modifier
                            .listPanel(first = false, last = index == shownRows.lastIndex)
                            .background(rowStripe(index)),
                    )
                }
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

    if (splitting && state.carriedDates.isNotEmpty()) {
        SplitArrearsDialog(
            state = state,
            onConfirm = {
                splitting = false
                viewModel.split(it)
            },
            onDismiss = { splitting = false },
        )
    }
}

/** What the table is and is not, before the first figure. */
@Composable
private fun ScheduleNotes(state: LoanScheduleState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        // What the list actually holds. On a debt with payments behind it these
        // are the ones left — the rows already paid are history nobody can
        // change from here, and are dropped — so "Every payment" would name a
        // list this is not. A debt that has paid nothing yet really does show
        // every one of them.
        //
        // Outside the padded block below, because the heading carries the page's
        // margin itself — see [ListPageHeader], which is where the gap above and
        // below every one of these now lives.
        ListPageHeader(
            title = stringResource(
                if (state.paymentsMade > 0) {
                    R.string.loan_schedule_remaining
                } else {
                    R.string.loan_schedule_title
                }
            ),
            // A schedule is dates a payment falls on, which is what separates
            // it from the debt's own statement two taps away — that one lists
            // payments and takes the payments mark.
            icon = Icons.Outlined.EventRepeat,
        )
        // Everything under the heading is indented under its *words*, exactly as
        // the one line [ListPageHeader] draws for itself is. These are that line
        // — there are simply three of them, each conditional, which is why they
        // are drawn here rather than passed in. Starting at the page margin left
        // the block with two left edges a mark's width apart, which reads as a
        // mistake rather than as an indent; the same page's sibling two taps away
        // ("Payment by payment") has always lined up.
        Column(
            modifier = Modifier.padding(
                start = LIST_PANEL_INSET + GROUP_HEADER_GUTTER,
                end = LIST_PANEL_INSET,
                bottom = 16.dp,
            )
        ) {
        // What has already been handed over, so a loan opened halfway through
        // says where in it the table starts. Every payment counts — the
        // instalments, the lump sums, and the charge for the broken first
        // period — and not just the instalments against the current balance.
        if (state.paymentsMade > 0) {
            Text(
                text = stringResource(R.string.loan_schedule_made, state.paymentsMade),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        state.totalInterest?.let {
            Text(
                text = stringResource(R.string.loan_total_interest, it),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        // Why the first payment is bigger than the rest. Without it a row of
        // double the usual figure reads as the app having lost count, and the
        // one thing the reader needs to know — that a payment was missed and
        // this one collects it — is nowhere on the page.
        //
        // And that the row can be split, because the mark on it is a glyph: an
        // icon in a table of digits says "this does something" and nothing about
        // what, and one sentence here is cheaper than making it discoverable.
        if (state.carriedForward > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.loan_schedule_arrears,
                    state.carriedForward,
                    state.carriedForward,
                ) + " " + stringResource(R.string.loan_schedule_split_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        }
    }
}

/**
 * One instalment: when, how much, what of it is interest, and what is left.
 *
 * [splits] marks the one row that is collecting instalments somebody missed, and
 * gives it the way back out of that — a payment of double the usual figure is the
 * right default, since money that is late is still owed, but it is not always
 * what happened. The mark is the split glyph rather than a word: it sits in a
 * table of digits with no room for one, and it is the only tappable thing on the
 * page, so what it does is one tap away from being found out.
 */
@Composable
private fun ScheduleRowView(
    row: ScheduleRow,
    modifier: Modifier = Modifier,
    splits: Boolean = false,
    onSplit: () -> Unit = {},
) {
    val dates = LocalDateDisplay.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .let { if (splits) it.clickable(onClick = onSplit) else it }
            // Inset from the paper's edge rather than from the page's, so the
            // figures line up under the headings above them — see [listPanel].
            .padding(
                start = LIST_PANEL_ROW_INSET,
                end = LIST_PANEL_ROW_INSET,
                top = 10.dp,
                bottom = 10.dp,
            ),
    ) {
        FigureCell(
            text = row.date?.let { dates.full(it) }
                ?: stringResource(R.string.loan_schedule_nth, row.number),
            weight = DATE_WEIGHT,
            end = false,
            emphasis = true,
        )
        FigureCell(row.payment, FIGURE_WEIGHT, end = true, emphasis = true)
        FigureCell(row.interest, FIGURE_WEIGHT, end = true, emphasis = false)
        FigureCell(row.balance, LEFT_WEIGHT, end = true, emphasis = true)
        // A slot of its own on every row, filled on one. Drawn inside the row's
        // width rather than hung off the end of it, so the four columns line up
        // down the page whether or not anything is owed — a table whose figures
        // shifted sideways on one line would read as a misprint.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(SPLIT_SLOT),
        ) {
            if (splits) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                    contentDescription = stringResource(R.string.loan_schedule_split),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Dating the instalments a payment is collecting, so they stop being collected.
 *
 * One field per missed instalment, each named by the day it was **due** — "give
 * me two dates" is a question about nothing, and "the one due 1 Jul" is one
 * somebody can answer. The payment's own instalment is not among them: it is not
 * missing, and offering to date it would ask the user to re-answer the row they
 * are looking at.
 *
 * Only a day is asked for. What an instalment is worth is the schedule's answer
 * and not the user's, and a field for it would let the two disagree.
 *
 * Optional, every one of them: leaving a field empty is the ordinary answer —
 * that instalment really is still owed and this payment really does collect it —
 * so a split of one out of three is a sentence the form can say.
 */
@Composable
private fun SplitArrearsDialog(
    state: LoanScheduleState,
    onConfirm: (List<LocalDate>) -> Unit,
    onDismiss: () -> Unit,
) {
    val dates = LocalDateDisplay.current
    val picked = remember(state.carriedDates) {
        mutableStateListOf<LocalDate?>().apply { repeat(state.carriedDates.size) { add(null) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.loan_schedule_split_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(
                        R.string.loan_schedule_split_body,
                        state.instalment.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.carriedDates.forEachIndexed { index, due ->
                    DateField(
                        // Which instalment this field is about, said as the day
                        // it was due — the one thing that tells two identical
                        // figures apart.
                        label = stringResource(
                            R.string.loan_schedule_split_due,
                            dates.full(due),
                        ),
                        date = picked.getOrNull(index),
                        placeholder = stringResource(R.string.loan_schedule_split_hint),
                        onPick = { picked[index] = it },
                        onClear = { picked[index] = null },
                        // The day the money changed hands: nothing can have been
                        // repaid before the debt existed. There is deliberately
                        // no ceiling — "I will pay July's on the 20th" is a real
                        // answer, and the whole point of asking is to say when.
                        // A day still to come settles no period today, so the
                        // debt goes on owing it; what it does is stop the next
                        // scheduled payment asking for it as well. See
                        // [Arrears.carriedForward].
                        minDate = state.movedOn,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked.filterNotNull()) },
                enabled = picked.any { it != null },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The width the split mark reserves on every row.
 *
 * Its own slot rather than space taken from the last column: what is left owing
 * is the widest figure on the row and the only one that can still run to six
 * digits, and it is the column a reader follows down the page.
 */
private val SPLIT_SLOT = 26.dp

// The date needs the most room in either script; what is left owing is the
// widest figure, because it is the only one that can still be six digits long.
private const val DATE_WEIGHT = 1.25f
private const val FIGURE_WEIGHT = 0.95f
private const val LEFT_WEIGHT = 1.1f

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, end: Boolean) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = Modifier.weight(weight),
    )
}

/**
 * One cell. [emphasis] separates the figures a user checks against a statement
 * from the one that only explains them — the interest column is the smallest
 * number on the row and the least often looked up.
 */
@Composable
private fun RowScope.FigureCell(text: String, weight: Float, end: Boolean, emphasis: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (emphasis) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        modifier = Modifier.weight(weight),
    )
}
