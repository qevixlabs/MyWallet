package com.mywallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.data.repo.Reminder
import com.mywallet.domain.ProjectedEntry
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.components.ConfirmDeleteDialog
import com.mywallet.ui.components.DaySelector
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.LaterPaymentFirstDialog
import com.mywallet.ui.components.PinnedPeriodHeader
import com.mywallet.ui.components.SwipeToDelete
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.components.swipeBetweenPeriods
import com.mywallet.ui.theme.WalletTheme

/**
 * What wants doing: today, and anything close enough that the user asked to be
 * warned about it early.
 *
 * One flat list, soonest first, drawn exactly as Home draws a movement — what it
 * is, what it says about itself, and the day it falls on underneath. There are
 * no "Today"/"Tomorrow" headings and no day sections: this is not a plan for the
 * week, which is what the Timeline is, and grouping it by day made it read as
 * one. A payment simply joins the list when it comes within the lead time, and
 * its own date line says which morning it lands on.
 *
 * The day being asked about is the heading, and the only heading — the same
 * arrangement Home and the Timeline use, where the period on the stepper is what
 * the page is about and nothing above it repeats the name of the tab the reader
 * tapped to get here.
 *
 * Rows already written down are drawn like any other. Today's occurrences become
 * real entries the moment the app opens — every balance already counts them — so
 * a page that showed only what was still outstanding would go blank at exactly
 * the moment the user opened it to check.
 *
 * There is deliberately no total at the foot of it. What the page answers is
 * *what wants doing*, one payment at a time, and a sum of a few days' payments is
 * a figure with nothing to compare it against: it is neither a month's spending,
 * which Home gives, nor what will be left afterwards, which the Timeline gives.
 * The notification still counts one, because there it is the whole message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    onOpenEntry: (String) -> Unit,
    onOpenLoan: (String) -> Unit,
    onOpenProjection: (String) -> Unit,
    /**
     * Called once a row is gone, so the shell can say so and offer the Undo.
     * The same lambda the timeline is given: one movement removed from either
     * page has to be reported the same way.
     */
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: ReminderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dates = LocalDateDisplay.current
    val blocked by viewModel.blockedByLaterPayment.collectAsStateWithLifecycle()
    // A swipe asks first, exactly as it does on the timeline. This page lists
    // the payments a reader has come to check rather than to edit, so a thumb
    // catching a row on the way down the list is if anything likelier here.
    var confirming by remember { mutableStateOf<String?>(null) }
    var skipping by remember { mutableStateOf<ProjectedEntry?>(null) }

    // The day the page is answering for, and the way to ask about another one —
    // at the top and drawn exactly as Home and the timeline draw their month,
    // because it is the same idea one unit down. It is the whole heading: a
    // title reading "Reminders" over a tab already labelled Reminders said
    // nothing the tab had not, and the line under it explained the page to a
    // reader who was on it. The date is the one thing up there worth reading,
    // which is the arrangement the other two list pages already settled on —
    // and, like theirs, it is held above the list rather than scrolled off it.
    PinnedPeriodHeader(
        modifier = modifier.fillMaxSize(),
        header = {
            DaySelector(
                label = dates.full(state.day),
                secondary = listOfNotNull(
                    dates.weekdayName(state.day),
                    dates.secondary(state.day),
                ).joinToString(" · "),
                canGoForward = state.canGoForward,
                canGoBack = state.canGoBack,
                showBackToToday = !state.isToday,
                onPrevious = viewModel::showPreviousDay,
                onNext = viewModel::showNextDay,
                onBackToToday = viewModel::showToday,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            )
        },
    ) { listModifier ->
    LazyColumn(
        // Dragging the page sideways steps the day, exactly as the arrows above
        // do — the same gesture the two month pages take, one unit down, since
        // this is the same stepper saying its own nouns.
        modifier = listModifier
            .fillMaxSize()
            .swipeBetweenPeriods(
                onPrevious = viewModel::showPreviousDay,
                onNext = viewModel::showNextDay,
            ),
        contentPadding = contentPadding,
    ) {
        if (state.reminders.isEmpty && !state.isLoading) {
            item {
                EmptyState(
                    title = stringResource(R.string.reminders_empty_title),
                    // A quiet day and a quiet today are different answers: one is
                    // "nothing is scheduled for the day you have stepped to", the
                    // other is the page's own resting state.
                    body = stringResource(
                        if (state.isToday) R.string.reminders_empty_body
                        else R.string.reminders_empty_body_day
                    ),
                    // This tab's own glyph. A day with nothing wanting doing is
                    // the page's resting state rather than a fault, and the
                    // mark is what says so before the words are read.
                    icon = Icons.Outlined.NotificationsNone,
                )
            }
        }

        itemsIndexed(
            items = state.reminders.rows,
            // Keyed, which it was not: a swipeable row needs a stable identity
            // or the box that is being dragged is reused under the finger for
            // whatever row lands in that slot next.
            key = { _, row -> row.key },
        ) { index, row ->
            // On its own paper, one sheet for the whole list — painted a row at
            // a time, since a lazy list cannot put a box around items it has not
            // composed, and rounded only at the two ends. See [listPanel].
            //
            // The paper is handed to [SwipeToDelete] rather than painted here,
            // for the reason the timeline hands it over: a swipe opens a gap and
            // the red has to be what shows through it. The band still goes on
            // the content inside, so it bands the sheet rather than the page.
            // Only what may actually be removed is swipeable. A written-down
            // row always may; a projection only when the rule behind it is one
            // the user wrote — a loan's instalment, a policy's premium and a
            // goal's contribution are counted by the arrangement they belong
            // to and would go on being drawn after the gesture. Same rule the
            // timeline draws, and the same reason.
            val removable = row is Reminder.Recorded ||
                (row as? Reminder.Due)?.projected?.canSkip == true
            val body = @Composable {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowStripe(index))
                    .padding(horizontal = LIST_PANEL_ROW_INSET)
            ) {
                when (row) {
                    // The ordinary entry row, drawn exactly as it is on Home:
                    // three lines, with the date on the third. It carries no
                    // "already recorded" note — the user is being reminded of
                    // the payment, not audited on whether the app has written
                    // it down yet, and every row here is one of the two.
                    is Reminder.Recorded -> EntryRow(
                        entry = row.entry,
                        // An instalment opens the debt, the same as the
                        // projected one below it does. The two are the same
                        // payment on either side of the morning it falls.
                        onClick = { openEntry(row.entry, onOpenEntry, onOpenLoan) },
                    )
                    is Reminder.Due -> ProjectedEntryRow(
                        projected = row.projected,
                        onOpen = { onOpenProjection(row.projected.seriesId) },
                        showDate = true,
                    )
                }
                if (index < state.reminders.rows.lastIndex) Hairline()
            }
            }
            val panel = Modifier
                .fillMaxWidth()
                .listPanel(
                    first = index == 0,
                    last = index == state.reminders.rows.lastIndex,
                    paint = !removable,
                )
            if (removable) {
                SwipeToDelete(
                    rowKey = row.key,
                    onSwiped = {
                        when (row) {
                            is Reminder.Recorded -> confirming = row.entry.id
                            is Reminder.Due -> skipping = row.projected
                        }
                    },
                    background = WalletTheme.colors.listSurface,
                    modifier = panel,
                    content = body,
                )
            } else {
                Column(modifier = panel) { body() }
            }
        }
    }
    }

    // The same three the timeline puts up, word for word: one movement swiped
    // from either page is one question, and two dialogs saying it differently
    // would read as two different acts.
    skipping?.let { projected ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.projection_delete_title),
            body = stringResource(R.string.projection_delete_body),
            confirmLabel = stringResource(R.string.projection_delete_action),
            onConfirm = {
                skipping = null
                viewModel.skip(projected.seriesId, projected.date, onDone = onDeleted)
            },
            onDismiss = { skipping = null },
        )
    }

    confirming?.let { id ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.entry_delete_title),
            body = stringResource(R.string.entry_delete_body),
            onConfirm = {
                confirming = null
                viewModel.delete(id, onDone = onDeleted)
            },
            onDismiss = { confirming = null },
        )
    }

    if (blocked) LaterPaymentFirstDialog(onDismiss = viewModel::dismissBlocked)
}
