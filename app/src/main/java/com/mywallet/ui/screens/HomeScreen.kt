package com.mywallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.domain.HoldingBreakdown
import com.mywallet.domain.MoneyEntry
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.LocalMoneyFormatter
import com.mywallet.ui.components.EmptyState
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.LabelDot
import com.mywallet.ui.components.MOVEMENT_MARK_GAP
import com.mywallet.ui.components.MoneyRoute
import com.mywallet.ui.components.MovementMark
import com.mywallet.ui.components.MoneyText
import com.mywallet.ui.components.MonthSelector
import com.mywallet.ui.components.MonthCurveSection
import com.mywallet.ui.components.PinnedPeriodHeader
import com.mywallet.ui.components.RouteText
import com.mywallet.ui.components.SectionHeader
import com.mywallet.ui.components.ShareBar
import com.mywallet.ui.components.WalletCard
import com.mywallet.ui.components.listPanel
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.components.swipeBetweenPeriods
import com.mywallet.ui.entryTitle
import com.mywallet.ui.holdingDisplayName
import com.mywallet.ui.loanMovementLabel
import com.mywallet.ui.loanRowLabel
import com.mywallet.ui.personWithCurrency
import com.mywallet.ui.overdraftRoute
import com.mywallet.ui.theme.RowAmountStyle
import com.mywallet.ui.theme.RowTitleStyle
import com.mywallet.ui.theme.DayTotalStyle
import com.mywallet.ui.theme.MoneyHeadlineStyle
import com.mywallet.ui.theme.MoneyRowStyle
import com.mywallet.ui.theme.MoneySmallStyle
import com.mywallet.ui.theme.WalletTheme
import com.mywallet.ui.transferRoute

/**
 * Home answers one question: *am I all right this month?*
 *
 * It leads with a sentence and a single number rather than a dashboard of
 * tiles, because someone who does not think in budgets cannot rank six figures
 * by importance — but they can read a sentence.
 */
@Composable
fun HomeScreen(
    onAddEntry: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenLoan: (String) -> Unit,
    /** Opens the holding a breakdown slice was spent through. */
    onOpenAccount: (String) -> Unit,
    onSeeAllMovements: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val money = LocalMoneyFormatter.current

    // The month sits above the list rather than in it: a page with a fortnight
    // of movements on it scrolled away the one control saying which month they
    // belong to. See [PinnedPeriodHeader].
    PinnedPeriodHeader(
        modifier = modifier.fillMaxWidth(),
        header = {
            MonthSelector(
                label = state.monthLabel,
                secondary = state.monthSecondary,
                canGoForward = state.canGoForward,
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
                onBackToNow = viewModel::showCurrentMonth,
                showBackToNow = !state.isCurrentMonth,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            )
        },
    ) { listModifier ->
    LazyColumn(
        // Dragging the page sideways steps the month, exactly as the arrows
        // above do. On the list rather than around it, so anything inside that
        // wants a horizontal drag is offered the touch first.
        modifier = listModifier
            .fillMaxWidth()
            .swipeBetweenPeriods(
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
            ),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.isEmpty && !state.isLoading) {
            item {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    // This tab's own glyph. The invitation underneath is to
                    // record a movement, and the button below says so.
                    icon = Icons.Outlined.Home,
                    action = {
                        TextButton(onClick = onAddEntry) {
                            Text(stringResource(R.string.cd_add_money))
                        }
                    },
                )
            }
            return@LazyColumn
        }

        item {
            HeadlineCard(
                state = state,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        item {
            SectionHeader(
                title = stringResource(R.string.home_where_it_went),
                modifier = Modifier.padding(horizontal = 20.dp),
                // What the section is: the month's spending split up and drawn
                // as bars. Deliberately not a pie, which is what the card under
                // it is deliberately not either.
                icon = Icons.Outlined.BarChart,
            )
        }

        if (state.breakdown.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.home_breakdown_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            item {
                WalletCard(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    state.breakdown.forEach { row ->
                        // Two lines wherever a slice is of foreign money, in the
                        // order every other figure in the app takes them: what
                        // the money actually was on top, what it comes to in the
                        // display currency underneath. A dollar account's
                        // spending drawn as rupees alone stated a valuation as
                        // though it were the transaction — and the reader could
                        // not find the figure they know in the total above.
                        //
                        // The bar and the ranking are untouched: those are sums
                        // across slices, and only the converted figure can be
                        // summed.
                        val foreign = row.ownTotal?.let { own ->
                            row.ownCurrency
                                ?.takeIf { !it.equals(money.currencyCode, true) }
                                ?.let { code -> money.forCurrency(code).formatCompact(own) }
                        }
                        BreakdownRow(
                            row = row,
                            formatted = foreign ?: money.formatCompact(row.total),
                            converted = foreign?.let { money.formatCompact(row.total) },
                            // Every slice names a holding the user can open, so
                            // every slice opens it. "Where it went" answers the
                            // question down to a bank and then stopped, leaving
                            // the reader to find that same bank again on another
                            // tab to see what the figure was made of.
                            //
                            // Which page depends on what the slice is. A debt's
                            // slice carries the loan's own id in [accountId] —
                            // an instalment names no account, so those rows are
                            // grouped by the debt they belong to — and
                            // [loanKind] being set is what says so. The one
                            // slice that opens nothing is the one with no
                            // holding behind it at all.
                            onClick = row.accountId?.let { id ->
                                if (row.loanKind != null) {
                                    { onOpenLoan(id) }
                                } else {
                                    { onOpenAccount(id) }
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.home_recent),
                modifier = Modifier.padding(horizontal = 20.dp),
                icon = Icons.Outlined.History,
                action = {
                    TextButton(onClick = onSeeAllMovements) {
                        Text(stringResource(R.string.home_see_all))
                    }
                },
            )
        }

        // One item, not one per row.
        //
        // The page's 20dp arrangement is spacing between *sections* — a card, a
        // heading, a card — and it fell between every movement too, so each row
        // sat 44dp from the next and five of them filled a screen. Drawn
        // together they are a list, at the same rhythm the timeline draws the
        // same rows in, and a dozen of them is a screenful and a bit rather than
        // three screens of air. Nothing is lost by giving up the laziness: the
        // count is capped, and it is capped low.
        item {
            // On its own paper, not on the page: the rows and the background
            // behind them were the same colour, so a handful of movements had
            // no edge and the foot of the page read as empty. See [listPanel].
            Column(modifier = Modifier.listPanel()) {
                // Banded, because the colour that used to sit beside each row
                // belonged to its label and labels are gone: without it a
                // handful of movements ran together into a wall of text. The
                // band goes on before the padding, so it reaches both edges of
                // the paper.
                state.recent.forEachIndexed { index, entry ->
                    EntryRow(
                        entry = entry,
                        onClick = { openEntry(entry, onOpenEntry) },
                        // Headed by which way the money ran, exactly as a
                        // Reminders row is — same mark, same size, same gap.
                        // One arrow means one thing on every list.
                        showMark = true,
                        modifier = Modifier
                            .background(rowStripe(index))
                            .padding(horizontal = LIST_PANEL_ROW_INSET),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

/**
 * The hero. Eyebrow, one big number, then the plain-language verdict — in that
 * order, because the number is what the eye lands on and the sentence is what
 * tells you whether to worry about it.
 */
@Composable
private fun HeadlineCard(state: HomeUiState, modifier: Modifier = Modifier) {
    val money = LocalMoneyFormatter.current
    val walletColors = WalletTheme.colors

    WalletCard(modifier = modifier) {
        // The eyebrow and the "came in" line below carry the app's own in/out
        // arrows, the same pair the card face on Accounts uses. This card's two
        // figures are told apart by colour alone otherwise, which is the one
        // signal a reader may not have — the same argument that already puts a
        // sign in front of every amount in the app.
        //
        // Drawn here rather than through [SectionHeader]: this is a card's own
        // eyebrow, not a section of the page, and routing it through the shared
        // heading would give it the heading's gap and rule as well.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CallMade,
                contentDescription = null,
                tint = walletColors.moneyOut,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.home_went_out).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        MoneyText(
            // Signed, because money out is not a quantity — it is a direction.
            // The bare figure read the same as a balance, which is the one number
            // it must never be mistaken for. Zero stays unsigned: "−रू 0" is not
            // a thing anyone has spent.
            formatted = money.formatCompact(state.summary.moneyOut)
                .let { if (state.summary.moneyOut.isZero) it else "−$it" },
            style = MoneyHeadlineStyle,
            // The same red every money-out figure in the app is drawn in. It was
            // plain ink here while the line under it — what came in — was green,
            // so the card coloured the smaller of its two figures and left the
            // headline looking like a balance.
            color = walletColors.moneyOut,
            autoShrink = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CallReceived,
                contentDescription = null,
                tint = walletColors.moneyIn,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.home_came_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            MoneyText(
                formatted = money.formatCompact(state.summary.moneyIn),
                style = MoneyRowStyle,
                color = walletColors.moneyIn,
            )
        }

        Spacer(Modifier.height(18.dp))
        Hairline()
        Spacer(Modifier.height(18.dp))

        MonthCurveSection(
            dailyOut = state.dailyOut,
            dailyIn = state.dailyIn,
            todayIndex = state.todayIndex,
            startLabel = state.monthStartLabel,
            endLabel = state.monthEndLabel,
            caption = stripCaption(state),
            contentDescription = stringResource(R.string.home_strip_cd, state.monthLabel),
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = verdict(state),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The line under the bars — only ever the empty case now.
 *
 * It used to count the days spent on out of the days elapsed, which is a
 * sentence restating what the bars directly above it had just drawn. With
 * nothing to draw there are no bars to read, and then the words are the only
 * thing on the page saying why the strip is blank.
 */
@Composable
private fun stripCaption(state: HomeUiState): String? =
    if (state.daysWithSpending == 0) stringResource(R.string.home_strip_none) else null

@Composable
private fun verdict(state: HomeUiState): String {
    val money = LocalMoneyFormatter.current
    val net = state.summary.net
    return if (net.minor >= 0L) {
        stringResource(R.string.home_saved_this_month, money.formatCompact(net))
    } else {
        stringResource(R.string.home_overspent_this_month, money.formatCompact(net.absolute))
    }
}

@Composable
private fun BreakdownRow(
    row: HoldingBreakdown,
    formatted: String,
    /**
     * What the figure above comes to in the display currency, on a slice of
     * foreign money. Never one line without the other: the top alone leaves a
     * slice that cannot be found inside the month's total, and the bottom alone
     * states a valuation as though it were the spending.
     */
    converted: String? = null,
    /** Where this slice came from, or null for the slice that names no holding. */
    onClick: (() -> Unit)? = null,
) {
    val color = row.color ?: MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            // Outside the padding, so the whole row is the target rather than
            // the words in the middle of it.
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelDot(color = color)
            Spacer(Modifier.width(10.dp))
            Text(
                // A debt says which arrangement it is, because a bank's name
                // alone would put its loan and its savings on the page as two
                // rows reading the same word. An instalment names no account by
                // design — it left a balance the app was not watching — so
                // without this the largest thing most people pay each month sat
                // in a slice called "No account".
                // A holding is named the way every other list names it. A debt
                // is the exception and says which arrangement it is instead: an
                // instalment names no account by design — it left a balance the
                // app was not watching — so without that the largest thing most
                // people pay each month sat in a slice called "No account".
                text = loanRowLabel(row.accountName, row.loanKind, accountLabel = null)
                    ?: holdingDisplayName(
                        row.accountInstitution,
                        row.accountName,
                        row.accountKind,
                        row.accountCurrency,
                        row.accountSiblings,
                    )
                    ?: stringResource(R.string.home_no_account),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(formatted = formatted, style = MoneyRowStyle)
                converted?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ShareBar(share = row.share, color = color)
    }
}

/**
 * What tapping a movement opens.
 *
 * **Every row opens its entry, the debt's own payments included.** A row on any
 * of these lists is one movement, and what a reader taps it for is that
 * movement: what it was for, how much, and which day. A holding is opened by
 * tapping the holding — on the Accounts page, or from the account named on the
 * row's own second line — and a list of payments that led there instead answered
 * a question nobody had arrived with.
 *
 * It used to send anything belonging to a debt to the debt's own screen, on the
 * reasoning that the money form has no field for "this is a lump sum against
 * that loan" and that a re-save would write back a plain payment. Both halves of
 * that stopped being true: the form states what it cannot ask — a loan's
 * instalment says its schedule lives with the loan, a drawdown says which
 * overdraft it came from, a purchase says which card is carrying it — and a save
 * keeps `loan_id` and `loan_part` and walks the debt from the old figure to the
 * new one. See [WalletRepository.saveEntry].
 *
 * [onOpenLoan] is kept for the one thing that is not an entry: a *projection*,
 * which is a rule speaking rather than a row, and whose only editable form is
 * the arrangement behind it.
 */
fun openEntry(entry: MoneyEntry, onOpenEntry: (String) -> Unit) {
    onOpenEntry(entry.id)
}

/**
 * One entry in a list. Money out is plain ink and money in is coloured *and*
 * signed, so the two never rely on colour alone to be told apart.
 */
@Composable
fun EntryRow(
    entry: MoneyEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = true,
    /**
     * Give the holding's currency a line of its own rather than a bracket after
     * its name. Paired with [showMark]: it is the same page's arrangement, where
     * a row leads with a mark and has the height for a third line.
     */
    showCurrency: Boolean = false,
    /**
     * Head the row with which way the money ran — see [MovementMark].
     *
     * Off wherever the rows are grouped under a date, which is every list but
     * one: there the margin is the day's, and two things in it would be a mark
     * beside a heading rather than at the head of a row. On where the page is a
     * single day and the rows have that margin free.
     */
    showMark: Boolean = false,
) {
    val money = LocalMoneyFormatter.current
    val dates = LocalDateDisplay.current
    val walletColors = WalletTheme.colors
    val isIn = entry.direction == com.mywallet.data.db.entity.Direction.IN

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMark) {
            MovementMark(isIn = isIn, isTransfer = entry.isTransfer)
            Spacer(Modifier.width(MOVEMENT_MARK_GAP))
        }
        Column(modifier = Modifier.weight(1f)) {
            // What the title said, so the line underneath does not say it again.
            // The title itself is [entryTitle], shared with every other list.
            val note = entry.note?.takeIf { it.isNotBlank() }
            // What a transfer is *for* is the two accounts, not the word
            // "Transfer": every transfer says that, so it distinguishes nothing.
            // A named one — "Salary transfer" — keeps its name on top and gets
            // "Wise → Nabil Bank" underneath.
            val route = entry.transferRoute()
            // An instalment is drawn exactly like its projection — "EMI" on
            // top, the account and the loan underneath. The day it comes due
            // it turns from a projection into this row, and a row that
            // suddenly read "Nabil Bank · Not labelled" looked like a
            // different payment. A note the user actually wrote still leads;
            // the note that merely repeats the loan's name does not.
            val ownNote = entry.ownNote
            // Where the money came from or went. On a projection this has always
            // been said; on the row the projection turns into, it was not — so
            // the same payment named its account while it was still due and
            // stopped naming it the moment it happened.
            val account = holdingDisplayName(
                entry.accountInstitution,
                entry.accountName,
                entry.accountKind,
                // Withheld from the name where the row gives the currency a
                // line of its own below — see [showCurrency]. Passing it here
                // as well would print "(NPR)" twice on one row.
                entry.accountCurrency.takeUnless { showCurrency },
                entry.accountSiblings,
            )
            // A drawdown is a movement, and its row names both ends the way a
            // transfer's does.
            val drawRoute = entry.overdraftRoute()

            // What this did to the debt, in the same words the loan's own
            // statement uses — a row and the ledger it appears in must not call
            // the same payment two different things — with whatever the user
            // wrote about it on the end. Shared with the account statement for
            // the same reason; see [loanMovementLabel].
            // Named the same way every other list names it — the statement of
            // the account it passed through included. See [entryTitle].
            RouteText(
                text = entryTitle(entry),
                style = RowTitleStyle,
            )
            // What the row has to say besides its title, and never the date:
            // where the money went, which bank it passed through, what the
            // payment did to a debt. All of it competed with the date for one
            // line and lost — the bank's name was the first thing trimmed, and
            // it is the thing that tells two rows of the same amount apart.
            // Money with a person is the one holding whose row carries its
            // currency inline — see [personWithCurrency].
            val isPersonDebt = entry.loanKind == LoanKind.PERSONAL
            val details = when {
                // The card it was bought on, and nothing else. The title is
                // already what the purchase was — "Groceries" — so the line
                // under it answers the only question left: which card is now
                // carrying it. Named like a holding, because to the person
                // paying with it that is exactly what it is.
                entry.isCardSpend -> listOfNotNull(
                    entry.loanName?.let {
                        if (showCurrency) it else "$it (${entry.currencyCode})"
                    },
                )
                entry.isOverdraftDraw -> listOfNotNull(
                    drawRoute.takeIf { ownNote != null },
                )
                entry.isLoanIncrease || entry.isLoanSettlement -> listOfNotNull(
                    // **Which debt, named the way a holding is named.** What the
                    // payment did is the title on every one of these rows now —
                    // see [entryTitle] — so this line answers the question the
                    // title cannot: which arrangement it moved. The bare name is
                    // not enough where a bank holds three things under one word,
                    // so it takes its kind and its currency, exactly as the
                    // account on any other row does. A debt with a person needs
                    // no kind: "Dad" is the whole of what it is called.
                    //
                    // The currency only where nothing else on the line carries
                    // it — an account is named with its own, and "Sita (NPR) ·
                    // Cash (NPR)" is one fact said twice on a line four words
                    // long. Withheld too where the row gives the currency a line
                    // of its own, which is [showCurrency]'s whole purpose.
                    loanRowLabel(entry.loanName, entry.loanKind, account)?.let {
                        when {
                            // Money with a person reads "Sita - NPR" wherever it
                            // is drawn — see [personWithCurrency].
                            isPersonDebt -> personWithCurrency(it, entry.currencyCode)
                            showCurrency || account != null -> it
                            else -> "$it (${entry.currencyCode})"
                        }
                    },
                    account,
                )
                entry.isLoanPayment -> listOfNotNull(
                    stringResource(R.string.loan_movement_instalment).takeIf { ownNote != null },
                    account,
                    loanRowLabel(entry.loanName, entry.loanKind, account)?.let {
                        if (isPersonDebt) personWithCurrency(it, entry.currencyCode) else it
                    },
                )
                else -> listOfNotNull(
                    // Named or not, both ends are worth saying once.
                    route.takeIf { note != null },
                    // Where it came from or landed. A transfer's route already
                    // names both accounts, so saying one of them again would put
                    // the same word twice on one line.
                    account.takeIf { !entry.isTransfer },
                )
            }.distinct().joinToString(" · ")
            if (details.isNotEmpty()) {
                RouteText(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The currency the holding above is denominated in, on a line of
            // its own.
            //
            // It used to sit in brackets after the name — "Nabil Bank Card
            // (NPR)" — which is right on a page where the line has room, and
            // wrong on one whose rows lead with a mark and whose second line is
            // already the longest thing on them. Underneath, it is the same
            // fact read in the same order, and the name is left whole.
            // Not where the line above already ends in it: a person's row
            // carries its currency inline, and a second copy underneath is the
            // same three letters twice.
            if (showCurrency && !isPersonDebt) {
                val code = entry.accountCurrency ?: entry.currencyCode
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // The date on a line of its own, third.
            //
            // It used to be the tail of the line above, which meant a row with
            // anything to say — a bank, a route, a debt — was trimmed before it
            // got there, and the date disappeared on exactly the rows that had
            // the most to explain. It is the one part of the subtext whose
            // length is known, so it is the one that can be given its own line.
            if (showDate) {
                val dateLine = listOfNotNull(
                    dates.dayAndMonth(entry.occurredOn),
                    dates.secondaryShort(entry.occurredOn),
                ).joinToString(" · ")
                Text(
                    text = dateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // The converted figure leads.
            //
            // This previously rendered the raw entered amount with the display
            // currency's symbol, so a $100 salary read as "रू 100" — wrong
            // number and wrong currency at once. The amount shown must always
            // be the one that matches the totals above it.
            // Which figure leads follows the account's own setting. A $10 charge
            // from a dollar account did not become rupees — nothing converted it
            // — so leading with a rupee figure states a valuation as if it were
            // the transaction. The exception is the paying half of a transfer
            // that crossed currencies: there the conversion actually happened, so
            // both figures are facts and the row shows the whole movement.
            val original = remember(entry.currencyCode) {
                money.forCurrency(entry.currencyCode)
            }
            val foreign = entry.isForeign(money.currencyCode)
            val partner = entry.transferPartnerCurrency?.let { code ->
                entry.transferPartnerAmount?.let { amount ->
                    money.forCurrency(code).formatCompact(amount)
                }
            }
            // A transfer is neither earned nor spent, so it carries no sign and
            // no in/out colour: "$ 100 → रू 15,000" is the whole movement, and a
            // minus in front of it would claim the money had left the user's
            // world. The direction it went is on the line underneath.
            val sign = when {
                entry.isTransfer -> ""
                isIn -> "+"
                else -> "−"
            }
            val amountColor = when {
                entry.isTransfer -> MaterialTheme.colorScheme.onSurface
                isIn -> walletColors.moneyIn
                else -> walletColors.moneyOut
            }
            if (entry.convertedOnTransfer && partner != null) {
                MoneyRoute(
                    lead = sign + original.formatCompact(entry.amount),
                    partner = partner,
                    style = RowAmountStyle,
                    color = amountColor,
                )
            } else {
                MoneyText(
                    formatted = if (foreign && !entry.showInDisplayCurrency) {
                        sign + original.formatCompact(entry.amount)
                    } else {
                        sign + money.formatCompact(entry.baseAmount)
                    },
                    style = RowAmountStyle,
                    color = amountColor,
                )
            }
            if (foreign && !entry.convertedOnTransfer) {
                Text(
                    text = if (entry.showInDisplayCurrency) {
                        original.formatCompact(entry.amount)
                    } else {
                        money.formatCompact(entry.baseAmount)
                    },
                    style = MoneySmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What a day came to, drawn at the right-hand end of its own heading.
 *
 * Set in [DayTotalStyle], which is the day label's own style with tabular
 * figures put back — the heading is one line read across, and two type sizes on
 * it made the pair of figures the loudest thing on a page of days.
 */
@Composable
fun DayTotalText(moneyIn: Money, moneyOut: Money) {
    val money = LocalMoneyFormatter.current
    val walletColors = WalletTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!moneyIn.isZero) {
            Text(
                text = "+" + money.formatCompact(moneyIn),
                style = DayTotalStyle,
                color = walletColors.moneyIn,
            )
            if (!moneyOut.isZero) Spacer(Modifier.width(10.dp))
        }
        if (!moneyOut.isZero) {
            Text(
                text = "−" + money.formatCompact(moneyOut),
                style = DayTotalStyle,
                // Coloured like the rows it is the total of. Grey beside a green
                // "+" read as the day's spending being the less important of the
                // two figures, which is the wrong way round on a money app.
                color = walletColors.moneyOut,
            )
        }
    }
}

