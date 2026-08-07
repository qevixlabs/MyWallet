package com.mywallet.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.Direction
import com.mywallet.domain.ProjectedDay
import com.mywallet.domain.ProjectedEntry
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.LocalMoneyFormatter
import com.mywallet.ui.components.ConfirmDeleteDialog
import com.mywallet.ui.components.DashedRule
import com.mywallet.ui.components.DayLabel
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.GroupHeader
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.LabelDot
import com.mywallet.ui.components.LaterPaymentFirstDialog
import com.mywallet.ui.components.MoneyRoute
import com.mywallet.ui.components.MoneyText
import com.mywallet.ui.components.MonthSelector
import com.mywallet.ui.components.Perforation
import com.mywallet.ui.components.PinnedPeriodHeader
import com.mywallet.ui.components.ROUTE_ARROW
import com.mywallet.ui.components.RouteText
import com.mywallet.ui.components.RowSeparator
import com.mywallet.ui.components.SPOTLIGHT_CORNER
import com.mywallet.ui.components.SwipeToDelete
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.swipeBetweenPeriods
import com.mywallet.ui.convertedAfter
import com.mywallet.ui.emiShown
import com.mywallet.ui.formatter
import com.mywallet.ui.holdingDisplayName
import com.mywallet.ui.kindLabelRes
import com.mywallet.ui.labelRes
import com.mywallet.ui.loanRowLabel
import com.mywallet.ui.outstandingShown
import com.mywallet.ui.shownAfter
import com.mywallet.ui.theme.MoneyRowStyle
import com.mywallet.ui.theme.MoneySmallStyle
import com.mywallet.ui.theme.TutorialLight
import com.mywallet.ui.theme.WalletTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What separates one day's paper from the next. Small: the days are one log read
 * straight down, and a card's worth of air between them would read as a page of
 * unrelated blocks rather than as a month.
 */
private val DAY_GAP = 12.dp

/**
 * One month of money: what happened, what is still to come, and where it leaves
 * the accounts.
 *
 * Day headers carry that day's totals, so scrolling answers "what did that
 * Saturday cost me?" without any tapping. Scheduled payments sit above the real
 * ones, drawn quieter, because they are a plan rather than a record.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    onOpenEntry: (String) -> Unit,
    onOpenLoan: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * The one row a lesson is being taught on, or null. Its bounds go back the
     * same way the stepper's do, so the shell can light it and point at it.
     */
    highlightEntryId: String? = null,
    onHighlightBounds: (Rect) -> Unit = {},
    /**
     * The bounds of the day heading over the lit row.
     *
     * Reported separately because the two are separate items of a lazy list and
     * nothing can measure both at once; the shell unions them, and only while
     * they are still touching — a sticky heading that has pinned to the top of
     * the page is no longer above its own rows, and a hole stretched between
     * them would cut half the screen out of the dim.
     */
    onHighlightHeaderBounds: (Rect) -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val blocked by viewModel.blockedByLaterPayment.collectAsStateWithLifecycle()
    val money = LocalMoneyFormatter.current
    val scope = rememberCoroutineScope()

    // Undo is offered only where there is something to bring back: a refused
    // delete has to say why instead, and a snackbar reading "Deleted · Undo"
    // over a row still sitting there would be the app contradicting itself.
    //
    // Asked about first, wherever the row is not the one a lesson is being
    // taught on. A swipe is easy to make by accident on a list of somebody's
    // payments, and the snackbar behind it is gone in four seconds — so the
    // question comes first and the Undo stays as the second net. The practice
    // row is the exception: the app has just asked for that gesture, and a
    // dialog there is the lesson interrupting itself.
    var confirming by remember { mutableStateOf<String?>(null) }
    val onDelete: (String) -> Unit = { id -> viewModel.delete(id, onDone = onDeleted) }
    val onSwipe: (String) -> Unit = { id ->
        // Nor is it reported. "Deleted" is the app confirming something the user
        // meant to do to their own figures; the practice row is the app's own,
        // written to be swiped and taken back either way, and a snackbar over
        // the lesson is the app answering a question it asked itself. It would
        // also carry an Undo for a row that is about to be removed regardless.
        if (id == highlightEntryId) viewModel.delete(id) else confirming = id
    }
    // The same gesture on a date that has not arrived. Asked about in its own
    // words: what goes is one occurrence and not the rule, which is the whole of
    // what separates this from stopping the repeat.
    var skipping by remember { mutableStateOf<ProjectedEntry?>(null) }
    var stoppingSeries by remember { mutableStateOf<String?>(null) }
    // A projection is computed from a rule, so opening one opens whatever wrote
    // it: the loan, or the entry the rule was created from.
    val onOpenProjection: (String) -> Unit = { seriesId ->
        scope.launch {
            when (val target = viewModel.resolveProjection(seriesId)) {
                is ProjectionTarget.Entry -> onOpenEntry(target.id)
                is ProjectionTarget.LoanEditor -> onOpenLoan(target.id)
                // Nothing left to edit, so the offer is to stop it. Tapping and
                // having nothing happen is what made these impossible to remove.
                is ProjectionTarget.Rule -> stoppingSeries = target.seriesId
            }
        }
    }

    // The month above the list rather than in it, so a page of movements cannot
    // scroll away the one thing saying which month they are. The day headings
    // below keep their own stickiness and pin flush under whatever is left of
    // it — see [PinnedPeriodHeader], which shrinks the space it takes rather
    // than drawing over the list.
    PinnedPeriodHeader(
        modifier = modifier.fillMaxWidth(),
        header = {
            MonthSelector(
                label = state.monthLabel,
                secondary = state.monthSecondary,
                canGoForward = state.canGoForward,
                showBackToNow = !state.isCurrentMonth,
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
                onBackToNow = viewModel::showCurrentMonth,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            )
        },
    ) { listModifier ->
    LazyColumn(
        // Dragging the page sideways steps the month. The rows keep their own
        // swipe — a horizontal drag that starts on one is a delete, and the
        // month does not move under it — because this sits outside them and
        // Compose offers the touch to the child first. The filter chips scroll
        // sideways for the same reason.
        modifier = listModifier
            .fillMaxWidth()
            .swipeBetweenPeriods(
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
            ),
        contentPadding = contentPadding,
    ) {
        item {
            // Scrolls horizontally: longer translations do not fit on a narrow
            // phone, and a wrapped chip reading "Money / in" is worse than one the
            // user has to nudge sideways.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                FilterChip(
                    selected = filter == TimelineFilter.ALL,
                    onClick = { viewModel.setFilter(TimelineFilter.ALL) },
                    label = { Text(stringResource(R.string.timeline_filter_all)) },
                )
                FilterChip(
                    selected = filter == TimelineFilter.OUT,
                    onClick = { viewModel.setFilter(TimelineFilter.OUT) },
                    label = { Text(stringResource(R.string.timeline_filter_out)) },
                )
                FilterChip(
                    selected = filter == TimelineFilter.IN,
                    onClick = { viewModel.setFilter(TimelineFilter.IN) },
                    label = { Text(stringResource(R.string.timeline_filter_in)) },
                )
            }
        }

        if (state.isEmpty) {
            item {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.timeline_empty),
                    // This tab's own glyph, so an empty month is recognisably
                    // this page rather than a blank one.
                    icon = Icons.Outlined.Receipt,
                )
            }
        }

        // The days that have happened first, oldest at the top, and the days
        // still to come underneath them — so the whole log reads forwards, the
        // way the thing it describes happens.
        //
        // It ran the other way for a long while, newest first, which is right
        // for every *other* list of movements in the app and wrong for this
        // one. Those are records: the reader wants the last thing that
        // happened, and the top of the page is where it belongs. This is a
        // plan, and a plan is read in the order it will arrive — the whole
        // reason the tab is called Timeline and not History.
        //
        // The running balance is what settles it. Each future day states where
        // the account stands once that day has run, so the column is a
        // cumulative one and only makes sense read in the direction it
        // accumulates. Newest first it went -रू 950, -रू 3,450, -रू 2,750 down
        // the page — three correct figures in an order that reads as an error.
        state.days.forEach { day ->
            item(key = "g-${day.date.toEpochDay()}") { Spacer(Modifier.height(DAY_GAP)) }
            stickyHeader(key = "h-${day.date.toEpochDay()}") {
                // Lit along with the row beneath it while the lesson is on this
                // day. What a swipe takes away is a payment, and a payment on
                // this page is a row *under a date* — lighting the row alone
                // left the date it belongs to dimmed a few pixels above it,
                // which reads as the spotlight having missed.
                val litDay = highlightEntryId != null &&
                    day.entries.any { it.id == highlightEntryId }
                TutorialIf(litDay) {
                // The head of the day's own paper — see [listPanel]. Opaque
                // whatever else is true, because the rows travel behind it on
                // their way out; it used to be the page's own colour, which was
                // also every row's, so the log had no edge anywhere on it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .listPanel(first = true, last = false)
                        .then(
                            if (litDay) {
                                Modifier
                                    // Only the top two corners: the row below
                                    // supplies the bottom pair, and between them
                                    // the block is one rounded rect the shape of
                                    // the hole. See [SPOTLIGHT_CORNER].
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = SPOTLIGHT_CORNER,
                                            topEnd = SPOTLIGHT_CORNER,
                                        )
                                    )
                                    .onGloballyPositioned {
                                        onHighlightHeaderBounds(it.boundsInWindow())
                                    }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The band every other list of movements bands its
                            // rows with — see [rowStripe]. On this page the rows
                            // are already grouped by day, so what it separates
                            // is the *heading* from the payments under it: the
                            // day and its totals are the one line on the page
                            // that is about a day rather than about a movement.
                            // Before the padding, so it reaches both edges of
                            // the paper the day is drawn on.
                            .background(WalletTheme.colors.rowBand)
                            .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DayHeading(date = day.date, modifier = Modifier.weight(1f))
                        DayTotalText(moneyIn = day.moneyIn, moneyOut = day.moneyOut)
                    }
                    DashedRule(modifier = Modifier.padding(start = LIST_PANEL_ROW_INSET))
                }
                }
            }

            // **No band on this page.** Every other list of movements alternates
            // its rows — Home's recent list, Reminders, an account's statement, a
            // debt's — and the timeline is the one that does not need it: it is
            // already cut into days by a sticky header with a rule under it, so
            // the banding was a second grouping laid over a page that had one,
            // and the two did not line up. A day of three payments read as a
            // stripe pattern rather than as a day.
            itemsIndexed(day.entries, key = { _, it -> it.id }) { index, entry ->
                // A day that has arrived is drawn like any other: the payment
                // has happened and every figure in the app already counts it.
                // It used to carry "Expected · Confirm", which asked the user to
                // vouch for a standing instruction their bank had carried out —
                // and until they did, the balance beside it was wrong.
                val lit = entry.id == highlightEntryId
                // Light while it is being taught on, whatever the app is set
                // to: a spotlight stops dimming one thing, and in the dark
                // scheme that leaves a dark row on a dark page. See
                // [TutorialLight].
                TutorialIf(lit) {
                    SwipeToDelete(
                        rowKey = entry.id,
                        onSwiped = { onSwipe(entry.id) },
                        // The row paints the day's paper itself rather than
                        // taking it from the item around it: a swipe opens a gap
                        // between the two, and the red has to be what shows
                        // through it. Inside [TutorialLight] this reads the
                        // light scheme's paper, which is what lights the row a
                        // lesson is being taught on.
                        background = WalletTheme.colors.listSurface,
                        // Measured only while it is the row being talked about,
                        // so the ordinary list carries no reporting at all. The
                        // paper's own inset is what holds it off the sides now —
                        // it used to run edge to edge, so the hole cut for it
                        // reached both screen edges and its rounded corners were
                        // sliced off square.
                        modifier = Modifier
                            .listPanel(
                                first = false,
                                last = index == day.entries.lastIndex,
                                // Painted by the row, above.
                                paint = false,
                            )
                            .then(
                                if (lit) {
                                    Modifier
                                        // Clipped to the shape of the hole it is
                                        // about to show through. A row is a
                                        // square rectangle of colour, so its four
                                        // corners sat outside the rounded hole
                                        // and were dimmed with the rest of the
                                        // page — a light row on a dark one
                                        // wearing a grey halo. See
                                        // [SPOTLIGHT_CORNER].
                                        //
                                        // Only the bottom two: the day's heading
                                        // is lit with this row and sits directly
                                        // on top of it, so it is what rounds the
                                        // top of the block. Rounding it here as
                                        // well cut two notches of dimmed page
                                        // out between the two.
                                        .clip(
                                            RoundedCornerShape(
                                                bottomStart = SPOTLIGHT_CORNER,
                                                bottomEnd = SPOTLIGHT_CORNER,
                                            )
                                        )
                                        .onGloballyPositioned {
                                            onHighlightBounds(it.boundsInWindow())
                                        }
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LIST_PANEL_ROW_INSET)
                        ) {
                            EntryRow(
                                entry = entry,
                                onClick = { openEntry(entry, onOpenEntry, onOpenLoan) },
                                showDate = false,
                            )
                            // A break inside the day, not the end of it — see
                            // [RowSeparator]. Drawn inside the swipeable row
                            // rather than between rows, so it travels with the
                            // payment it belongs to instead of hanging over the
                            // red a swipe opens.
                            if (index < day.entries.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }

        // And then the days still to come, at the foot of the log rather than
        // the head of it. No heading over them: the running balance on each
        // one already says these have not happened yet.
        if (state.hasSchedule) {
            // A day still to come is headed exactly as a day that has happened:
            // its own sticky row, so it pins under the month and travels off
            // with the payments beneath it. It used to be drawn *inside* the
            // block of rows it heads, which meant the stickiness stopped at the
            // first projection — on a month made entirely of them, which is
            // every future month, nothing pinned at all and the reader scrolled
            // a list of payments with no day against them.
            state.projectedDays.forEach { day ->
                item(key = "ug-${day.date.toEpochDay()}") {
                    // The same gap a day that has happened opens with. The tear
                    // used to be drawn here, between one day and the next, and
                    // it said the wrong thing at that scale: a month of daily
                    // payments was a column of scissors, and the mark that is
                    // supposed to say "here is where the page is cut" was on
                    // every join in it. It is spent once now, on the one join
                    // that really is a cut — see the note above the holdings.
                    Spacer(Modifier.height(DAY_GAP))
                }
                stickyHeader(key = "uh-${day.date.toEpochDay()}") {
                    ProjectedDayHeader(
                        date = day.date,
                        balanceText = stringResource(
                            R.string.projected_balance,
                            money.formatCompact(day.balanceAfter),
                        ),
                        negative = day.balanceAfter.minor < 0,
                    )
                }
                // Unbanded, like every row on this page — see the note on
                // [state.days] above.
                itemsIndexed(
                    day.entries,
                    key = { _, it -> "u-${it.seriesId}-${it.date.toEpochDay()}" },
                ) { index, projected ->
                    ProjectedRow(
                        projected = projected,
                        onOpen = onOpenProjection,
                        onSkip = { skipping = it },
                        last = index == day.entries.lastIndex,
                    )
                }
            }
        }

        // Where the money lands afterwards, and what is left owing. Both used to
        // live on a separate tab, which meant the answer to "can I afford this
        // month?" was somewhere else entirely.
        //
        // The page is two halves — what happened day by day, then where it
        // leaves each holding — and each block of the second half is a card, the
        // way a bank's holdings are on the Accounts page. That is what says where
        // one block ends and the next begins; the rule that used to be drawn
        // under the log, and the hairline trailing the last row of each block,
        // both said it a second time and are gone.
        //
        // **The tear is spent here, once.** It used to sit between every pair of
        // days, where a mark meaning "the page is cut here" was on a join that is
        // not a cut at all — the days are one receipt read down. This *is* the
        // cut: the log ends and what the month leaves behind begins. Withheld
        // when there is nothing below it, since a tear at the foot of the page is
        // a sheet torn off nothing.
        val hasOutlook = state.accounts.isNotEmpty() || state.loans.isNotEmpty() ||
            state.personalLoans.isNotEmpty() || state.lentOut.isNotEmpty()
        if (hasOutlook) item { Perforation() }

        if (state.accounts.isNotEmpty()) {
            item {
                // Every kind of holding money sits in is in this one block —
                // banks, wallets, cash, a deposit, a policy, a goal — so the
                // mark is the one for a place money is kept rather than any one
                // of their own.
                TimelineGroup(
                    title = stringResource(R.string.next_after_all),
                    icon = Icons.Outlined.AccountBalanceWallet,
                ) {
                    state.accounts.forEachIndexed { index, row ->
                        if (index > 0) Hairline(inset = 16.dp)
                        key(row.accountId) {
                            // Read in whichever currency the account asked to be
                            // read in. An account holding dollars that never
                            // opted into conversion has to say dollars here:
                            // showing a rupee figure would state a valuation as
                            // though it were the balance.
                            val own = remember(row.currencyCode) {
                                money.forCurrency(row.currencyCode)
                            }
                            val base = row.takeIf { it.showInDisplayCurrency }
                                ?.let { p -> p.now?.let { now -> p.after?.let { now to it } } }
                            val now = base?.first ?: row.nowOwn
                            val after = base?.second ?: row.afterOwn
                            val shown = if (base != null) money else own
                            BalanceRow(
                                // What the user called this one, or failing that
                                // the bank. Joining the two columns said "Nabil
                                // Bank · Nabil Bank" on every holding that was
                                // never given a name of its own — which is most
                                // of them, since the name field is optional.
                                title = row.ownName ?: row.institution ?: row.name,
                                // Everything the title did not say: the bank,
                                // when a name took its place, then which of that
                                // bank's holdings this is, then where it stands
                                // today.
                                subtitle = listOfNotNull(
                                    row.institution.takeIf { row.ownName != null },
                                    stringResource(row.kind.labelRes()),
                                    // Where it stands today, but only when this
                                    // month's schedule actually moves it — the
                                    // same rule the debts below follow. On a
                                    // month that has been and gone nothing is
                                    // left to run, and the line said "now
                                    // रू 1,000" beside a figure reading रू 1,000.
                                    if (after != now) {
                                        stringResource(
                                            R.string.next_from_now,
                                            shown.formatCompact(now),
                                        )
                                    } else {
                                        null
                                    },
                                ).joinToString(" · "),
                                amount = shown.formatCompact(after),
                                // What that comes to in the currency the totals
                                // above are in. Only when it is genuinely a
                                // second figure: an account already read in the
                                // display currency would say it twice.
                                converted = row.after
                                    ?.takeIf {
                                        base == null && !row.currencyCode.equals(
                                            money.currencyCode, ignoreCase = true,
                                        )
                                    }
                                    ?.let { money.formatCompact(it) },
                                negative = after.minor < 0,
                                // The dot the Accounts page knows this holding
                                // by. Colour means one thing in this app — a
                                // holding — and it is drawn wherever holdings
                                // are, so a bank found by its green on one tab
                                // is found by the same green on this one.
                                dot = row.color,
                                // The debts below have always opened; an account
                                // beside them that did nothing when tapped read
                                // as a bug rather than as a deliberate
                                // difference — and "where will my money be?" is
                                // asked precisely when the answer is about to be
                                // corrected.
                                onClick = { onOpenAccount(row.accountId) },
                            )
                        }
                    }
                }
            }
        }

        // Three sections, not two. A bank's loan and money borrowed from a
        // person are both debts and were listed together, but the user thinks
        // of them as different obligations — one has a schedule the bank runs,
        // the other is a promise between two people — and the Accounts page has
        // always kept them apart. Now this one does too.
        loanSection(
            titleRes = R.string.next_loans_left,
            rows = state.loans,
            money = money,
            onOpenLoan = onOpenLoan,
            icon = Icons.Outlined.AccountBalance,
        )
        loanSection(
            titleRes = R.string.accounts_owe,
            rows = state.personalLoans,
            money = money,
            onOpenLoan = onOpenLoan,
            icon = Icons.Outlined.Person,
        )
        loanSection(
            titleRes = R.string.accounts_owed_to_you,
            rows = state.lentOut,
            money = money,
            lent = true,
            onOpenLoan = onOpenLoan,
            // The same mark as the block above it: both are money with a
            // person, and the heading's own words are what say which way it is
            // running.
            icon = Icons.Outlined.Person,
        )

        item { Spacer(Modifier.height(32.dp)) }
    }
    }

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
                onDelete(id)
            },
            onDismiss = { confirming = null },
        )
    }

    if (blocked) LaterPaymentFirstDialog(onDismiss = viewModel::dismissBlocked)

    stoppingSeries?.let { seriesId ->
        AlertDialog(
            onDismissRequest = { stoppingSeries = null },
            title = { Text(stringResource(R.string.series_orphan_title)) },
            text = { Text(stringResource(R.string.series_orphan_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopSeries(seriesId)
                        stoppingSeries = null
                    }
                ) { Text(stringResource(R.string.series_orphan_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { stoppingSeries = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * One block of debts as this month leaves them.
 *
 * Written once and called three times — a bank's loans, money owed to a person,
 * money owed by one. The three differ in a heading and in which way the figure
 * is coloured; everything else about the row is the same fact about the same
 * kind of thing, and three copies of it would be three chances to drift apart.
 */
private fun LazyListScope.loanSection(
    @StringRes titleRes: Int,
    rows: List<LoanOutlook>,
    money: MoneyFormatter,
    onOpenLoan: (String) -> Unit,
    /**
     * The mark for this block — the same one the Accounts tab heads the same
     * debts with. A bank's debt takes the bank's mark and money with a person
     * takes the person's, which is the whole of the difference between the
     * three blocks this is called for.
     */
    icon: ImageVector,
    lent: Boolean = false,
) {
    if (rows.isEmpty()) return
    // One list item for the whole block, not a heading followed by rows: a card
    // cannot be built out of separate list items, and a month touches a handful
    // of holdings, so nothing is lost by drawing them together.
    item {
        TimelineGroup(title = stringResource(titleRes), icon = icon) {
            rows.forEachIndexed { index, outlook ->
                if (index > 0) Hairline(inset = 16.dp)
                val loan = outlook.loan
                val shown = loan.formatter(money)
                BalanceRow(
                    title = loan.name,
                    subtitle = listOfNotNull(
                        stringResource(loan.kindLabelRes()),
                        // What it stands at today, but only when this month's
                        // payments actually move it — otherwise the row says the
                        // same number twice.
                        if (outlook.changes) {
                            stringResource(
                                R.string.next_from_now,
                                shown.formatCompact(loan.outstandingShown),
                            )
                        } else {
                            null
                        },
                        if (lent) {
                            // The lender only when it says something the name
                            // does not: one field names a loan now, and loans
                            // entered before that still carry a separate lender
                            // worth showing.
                            loan.lender?.takeIf { it != loan.name }
                        } else {
                            loan.emiShown?.let {
                                stringResource(
                                    // One payment is not something paid each time.
                                    if (loan.paysAtEnd) {
                                        R.string.loan_emi_at_end_short
                                    } else {
                                        R.string.loan_emi_short
                                    },
                                    shown.formatCompact(it),
                                )
                            }
                        },
                    ).joinToString(" · "),
                    amount = shown.formatCompact(outlook.shownAfter),
                    converted = outlook.convertedAfter(money)?.let { money.formatCompact(it) },
                    debt = !lent,
                    incoming = lent,
                    onClick = { onOpenLoan(loan.id) },
                )
            }
        }
    }
}

/**
 * A heading and the rows underneath it as one card — the shape a bank's holdings
 * take on the Accounts page.
 *
 * Loose rows separated by hairlines read as a continuation of the day-by-day log
 * above them, which is a list of movements rather than a list of holdings. The
 * card is what says these are a block of their own, and it says it without a rule
 * drawn across the page.
 */
@Composable
private fun TimelineGroup(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        GroupHeader(
            title = title,
            icon = icon,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Clipped before the background, so a row's ripple stops at the
                // rounded corner instead of painting over it.
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            content = content,
        )
    }
}

/**
 * A name, a quiet second line, and one figure on the right — with [converted]
 * underneath it when the figure is not in the currency the user reads totals in.
 *
 * The same shape an entry row uses: the currency the money is actually in on
 * top, what it is worth underneath. A holding that showed only its own currency
 * left the user to reconcile it against a total they could not see it inside.
 */
@Composable
private fun BalanceRow(
    title: String,
    subtitle: String?,
    amount: String,
    converted: String? = null,
    negative: Boolean = false,
    incoming: Boolean = false,
    debt: Boolean = false,
    /**
     * The holding's own colour, or null for a debt — which has no colour column
     * and takes the one its figure is already printed in, exactly as the
     * Accounts page does it. Every row in these blocks starts with a dot for
     * that reason: one shape for all of them, and the block then reads as the
     * same list of holdings the other tab shows.
     */
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val amountColor = when {
        incoming -> WalletTheme.colors.moneyIn
        // Owing is ordinary; only a balance below zero is an alarm.
        debt -> WalletTheme.colors.debt
        negative -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Inset from the card's edge rather than from the page's, so the
            // rows sit inside the block the way a bank's holdings do.
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelDot(color = dot ?: amountColor, size = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = amount,
                style = MoneySmallStyle,
                color = amountColor,
            )
            converted?.let {
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
 * Which day a block of rows falls on, written the one way this page writes it.
 *
 * The date's own figure leads, big enough to scroll to — and in whichever
 * calendar is being read, so a Nepali page is counted in Nepali days and a
 * Gregorian one in Gregorian days. The words beside it are what the figure alone
 * cannot say: the month it belongs to, the weekday where that is worth a line —
 * see [weekday] — and, on a Nepali page, the English date the same way a printed
 * patro puts it in the corner of a cell.
 *
 * The month is there because the figure needs it. A day number is only a date
 * once something says which month it counts in, and on this page that is not
 * always the month in the stepper above: a Bikram Sambat month straddles two
 * Gregorian ones, and the days still to come run past the end of the month
 * being read.
 *
 * Written once and used by both headings on this page — a day that has happened
 * and a day still to come. They are the same list read in two directions and
 * cannot be lettered two ways.
 */
@Composable
private fun DayHeading(
    date: LocalDate,
    modifier: Modifier = Modifier,
    /**
     * Whether the day it fell on is worth a line of its own.
     *
     * Yes on a day that has happened, where it is the one thing about the date
     * a reader looking back actually uses — "what did that Saturday cost me?".
     * No on a day still to come, which never carried one: a heading there is a
     * date to arrive at rather than a day to remember, and the line would be a
     * second row of words on a heading that had one, making every future day a
     * notch taller than it is today.
     */
    weekday: Boolean = true,
) {
    val dates = LocalDateDisplay.current
    val month = dates.monthName(date)
    val gregorian = dates.secondaryShort(date)
    DayLabel(
        day = dates.dayNumber(date),
        primary = if (weekday) dates.weekdayName(date) else month,
        secondary = listOfNotNull(month.takeIf { weekday }, gregorian).joinToString(" · "),
        modifier = modifier,
    )
}

/**
 * The heading over a future day: which day, and the balance once it has run.
 *
 * Sticky, exactly as the heading over a day that has already happened is. It
 * used to be drawn inside the block of rows it heads, which meant nothing pinned
 * on a month made entirely of projections — every future month — and the reader
 * scrolled a list of payments with no day against them.
 */
@Composable
private fun ProjectedDayHeader(
    date: LocalDate,
    balanceText: String,
    negative: Boolean,
) {
    // The head of its day's paper, exactly as a day behind us has — see
    // [listPanel]. Opaque either way: the rows pass behind it on their way out.
    Column(modifier = Modifier.listPanel(first = true, last = false)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Banded like the heading over a day that has happened, and for the
            // same reason: the two are one kind of row and cannot be drawn two
            // ways on one page.
            modifier = Modifier
                .fillMaxWidth()
                .background(WalletTheme.colors.rowBand)
                .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 10.dp),
        ) {
            // The same day heading the days behind us wear, down to the figure
            // in the margin. A day still to come is a day: set in `titleSmall`
            // it was the one heading on the page in a different voice, and an
            // EMI's date read louder than the date of the rent that has already
            // gone out. Without the weekday, which it has never carried — see
            // [DayHeading].
            DayHeading(date = date, weekday = false, modifier = Modifier.weight(1f))
            Text(
                text = balanceText,
                style = MoneySmallStyle,
                color = if (negative) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DashedRule(modifier = Modifier.padding(start = LIST_PANEL_ROW_INSET))
    }
}

/**
 * One payment still to come.
 *
 * Laid out exactly like a real entry — same dot, same type sizes, same signed
 * and coloured figure. These used to be drawn smaller and greyer, which read as
 * less important rather than as not-yet-happened; the heading above and the
 * running balance already say it is a plan.
 *
 * And it swipes away like one that has arrived: what the gesture does is drop
 * that one date out of the rule — see [ProjectedEntry.canSkip] for the schedules
 * that are the app's to run and not the user's to skip a month of.
 */
@Composable
private fun ProjectedRow(
    projected: ProjectedEntry,
    onOpen: (String) -> Unit,
    onSkip: (ProjectedEntry) -> Unit,
    /** The last row of its day, and so where the day's paper is cut round. */
    last: Boolean,
) {
    val body = @Composable {
        Column(modifier = Modifier.padding(horizontal = LIST_PANEL_ROW_INSET)) {
            ProjectedEntryRow(
                projected = projected,
                onOpen = { onOpen(projected.seriesId) },
            )
            if (!last) RowSeparator()
        }
    }
    if (projected.canSkip) {
        SwipeToDelete(
            rowKey = "${projected.seriesId}-${projected.date.toEpochDay()}",
            onSwiped = { onSkip(projected) },
            // The row paints the day's paper, so the red is what a swipe
            // uncovers rather than the page showing through the gap.
            background = WalletTheme.colors.listSurface,
            modifier = Modifier.listPanel(first = false, last = last, paint = false),
            content = body,
        )
    } else {
        Column(modifier = Modifier.listPanel(first = false, last = last)) { body() }
    }
}

/**
 * One payment that has not happened yet.
 *
 * Shared with the Reminders tab rather than written twice: the same occurrence
 * appears on both pages, and two copies of this — the title, the two ends of a
 * transfer, which currency leads — would be two chances for one payment to be
 * described two ways.
 */
@Composable
fun ProjectedEntryRow(
    projected: ProjectedEntry,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Draw the day it falls on as a third line, the way a real entry does on
     * Home. Off on the timeline, where the day heading above has said it.
     */
    showDate: Boolean = false,
) {
    val money = LocalMoneyFormatter.current
    val dates = LocalDateDisplay.current
    val walletColors = WalletTheme.colors
    val route = projected.transferRoute()
    val accountLabel = projected.accountLabel()
    val loanLabel = projected.loanLabel(accountLabel)
    // An instalment's note defaults to the loan's own name, which the
    // subtext underneath now carries. Leading with it said the bank's
    // name twice and never said the row was an instalment; a note the
    // user actually wrote still leads, because it says something the
    // loan's name does not.
    val ownNote = projected.note?.takeIf {
        !projected.isLoanPayment || it != projected.loanName
    }
    val title = if (projected.isLoanPayment) {
        ownNote ?: stringResource(R.string.loan_movement_instalment)
    } else {
        ownNote ?: projected.title ?: route
            ?: stringResource(R.string.transfer_row)
    }
    val subtitle = listOfNotNull(
        // A bank's name alone does not say which of its products this
        // payment is for. An instalment says so — unless the title
        // already did.
        if (projected.isLoanPayment && ownNote != null) {
            stringResource(R.string.loan_movement_instalment)
        } else {
            null
        },
        projected.title?.takeIf { ownNote != null },
        route.takeIf { it != title },
        accountLabel.takeIf { route == null },
        // Where it lands: the debt this settles. Named only when the
        // account it leaves has not already named it.
        loanLabel,
    ).distinct().joinToString(" · ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // A quarter's interest and a deposit coming free are both
            // the bank's schedule, not a rule the user wrote, so there
            // is nothing behind either to open — and tapping one used to
            // offer to stop a rule that has never existed.
            .then(
                if (projected.hasRuleBehindIt) {
                    Modifier.clickable(onClick = onOpen)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RouteText(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                RouteText(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showDate) {
                Text(
                    text = listOfNotNull(
                        dates.dayAndMonth(projected.date),
                        dates.secondaryShort(projected.date),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Unsigned and uncoloured for a transfer, exactly as a real one
        // is drawn: the money is not leaving the user's world, and the
        // route underneath already says which way it goes.
        val isTransfer = route != null
        // And in whichever currency the account asked to be read in,
        // exactly as a real one is drawn. A projection is the same
        // payment as the row it will become, so a dollar instalment
        // that has already been paid cannot read as dollars while the
        // one still due reads as rupees — the same money would appear
        // to change currency by stepping a month forward.
        val original = remember(projected.currencyCode) {
            money.forCurrency(projected.currencyCode)
        }
        val foreign = !projected.currencyCode.equals(
            money.currencyCode, ignoreCase = true,
        )
        val leadsOwn = foreign && !projected.showInDisplayCurrency
        // A transfer that crosses currencies draws the whole movement on
        // one line — "$ 900 → रू 1,38,587" — the same shape the real row
        // takes once the date arrives. It used to stack the two figures
        // instead, so one standing instruction was drawn one way in the
        // month it was carried out and another way in every month after.
        val partner = projected.transferPartnerCurrency?.let { code ->
            projected.transferPartnerAmount?.let { amount ->
                money.forCurrency(code).formatCompact(amount)
            }
        }
        // Held as the figure itself rather than as a flag beside it, so the two
        // cannot disagree: there is no such thing as a crossed transfer with no
        // partner figure to draw.
        val crossedPartner = partner?.takeIf { projected.convertedOnTransfer }
        val crossed = crossedPartner != null
        val lead = if (leadsOwn || crossed) {
            original.formatCompact(projected.amount)
        } else {
            money.formatCompact(projected.baseAmount)
        }
        val sign = when {
            isTransfer -> ""
            projected.direction == Direction.IN -> "+"
            else -> "−"
        }
        val amountColor = when {
            isTransfer -> MaterialTheme.colorScheme.onSurface
            projected.direction == Direction.IN -> walletColors.moneyIn
            else -> walletColors.moneyOut
        }
        Column(horizontalAlignment = Alignment.End) {
            if (crossedPartner != null) {
                MoneyRoute(
                    lead = sign + lead,
                    partner = crossedPartner,
                    style = MoneyRowStyle,
                    color = amountColor,
                )
            } else {
                MoneyText(
                    formatted = sign + lead,
                    style = MoneyRowStyle,
                    color = amountColor,
                )
            }
            // The other figure underneath, so a foreign payment still
            // says what it is worth in the currency the totals are in.
            // Not when the line above already carries both.
            if (foreign && !crossed) {
                Text(
                    text = if (leadsOwn) {
                        money.formatCompact(projected.baseAmount)
                    } else {
                        original.formatCompact(projected.amount)
                    },
                    style = MoneySmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The two ends of a projected transfer, drawn the same way as a real one — the
 * end that is known where only one is. See [MoneyEntry.transferRoute].
 */
private fun ProjectedEntry.transferRoute(): String? {
    val from = transferFromName
    val to = transferToName
    return when {
        from != null && to != null -> "$from $ROUTE_ARROW $to"
        to != null -> "$ROUTE_ARROW $to"
        from != null -> "$from $ROUTE_ARROW"
        else -> null
    }
}

/** The account this leaves, phrased exactly as the real row's is. */
@Composable
private fun ProjectedEntry.accountLabel(): String? =
    holdingDisplayName(
        accountInstitution, accountName, accountKind, accountCurrency, accountSiblings,
    )

/** The debt an instalment settles \u2014 see [loanRowLabel], which real rows share. */
@Composable
private fun ProjectedEntry.loanLabel(accountLabel: String?): String? =
    loanRowLabel(loanName, loanKind, accountLabel)

/**
 * The light scheme, but only for the row a lesson is being taught on.
 *
 * See the accounts list, which needs exactly the same thing for the same
 * reason — a spotlight is only a spotlight when what it leaves undimmed is
 * lighter than what surrounds it.
 */
@Composable
private fun TutorialIf(on: Boolean, content: @Composable () -> Unit) {
    if (on) TutorialLight(content) else content()
}
