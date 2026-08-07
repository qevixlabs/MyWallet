package com.mywallet.ui.screens

import androidx.annotation.StringRes
import androidx.compose.ui.draw.clip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.inputPrefix
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.domain.AccountWithBalance
import com.mywallet.domain.HoldingProgress
import com.mywallet.domain.Loan
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.LocalMoneyFormatter
import com.mywallet.ui.convertedOutstanding
import com.mywallet.ui.emiShown
import com.mywallet.ui.holdingLabel
import com.mywallet.ui.kindLabelRes
import com.mywallet.ui.labelRes
import com.mywallet.ui.formatter
import com.mywallet.ui.rateShown
import com.mywallet.ui.settleShown
import com.mywallet.ui.principalShown
import com.mywallet.ui.components.BankCardFace
import com.mywallet.ui.components.CardFaceLine
import com.mywallet.ui.components.CardFaceMuted
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LabelDot
import com.mywallet.ui.components.MoneyText
import com.mywallet.ui.components.SwipeToDelete
import com.mywallet.ui.theme.TutorialLight
import com.mywallet.ui.components.rememberAmountGrouping
import com.mywallet.ui.theme.TitleStyle
import com.mywallet.ui.theme.MoneyHeadlineStyle
import com.mywallet.ui.theme.MoneyRowStyle
import com.mywallet.ui.theme.MoneySmallStyle
import com.mywallet.ui.theme.WalletTheme
import java.time.LocalDate

/**
 * Where the money physically is.
 *
 * Each account shows its balance in its own currency — a Wise account holds
 * dollars — with the display-currency equivalent underneath, and one total at
 * the top. Anything that cannot be converted says so rather than being quietly
 * left out of the total.
 */
@Composable
fun AccountsScreen(
    onOpenAccount: (String) -> Unit,
    onOpenLoan: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * The one holding a lesson is being taught on, or null — see `WalletApp`.
     * Its bounds go back so the shell can light it and point at it, and it is
     * drawn in the light scheme so the spotlight reads in the dark one.
     */
    highlightAccountId: String? = null,
    onHighlightBounds: (Rect) -> Unit = {},
    /**
     * Called once a holding is gone, so the shell can say so.
     *
     * A holding leaves no Undo behind it — there is nothing to bring back — but
     * a page that simply loses a row says nothing about whether the tap landed
     * on the holding the dialog named. The snackbar is the receipt.
     */
    onDeleted: () -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val baseFormatter = LocalMoneyFormatter.current
    // What a swipe has proposed removing. Held here rather than acted on
    // immediately: an entry deleted by mistake comes back from a snackbar, but
    // an account takes every movement that ever touched it with it, and there is
    // nothing left to bring back — so this one asks first.
    var deleting by remember { mutableStateOf<Deletion?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        // Its own heading, like every tab: the shell draws no top bar.
        //
        // And only a heading. Adding an account is the floating button in the
        // corner, where every other page puts the thing it adds — a second plus
        // in the heading meant two buttons a thumb-width apart doing different
        // things, and no way to tell which was which but tapping one.
        item {
            Text(
                text = stringResource(R.string.accounts_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        item {
            // The one card face in the app. What the user holds is the single
            // figure on this page nobody needs a label to want, and on the same
            // white rectangle as everything under it the page opened on four
            // identical cards with only the type size saying which mattered.
            // See [BankCardFace] for what is and is not drawn on it.
            BankCardFace(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = stringResource(R.string.accounts_total).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    // The card's own quiet ink, not the scheme's — see
                    // [CardFaceMuted]. A cool grey meant for a near-black page
                    // has little contrast left over indigo and none at all over
                    // the green corner.
                    color = CardFaceMuted,
                )
                Spacer(Modifier.height(6.dp))
                // The headline is what you actually hold. Debt is real but it
                // is not this number, and leading with net worth made a
                // healthy balance read as a large negative.
                MoneyText(
                    formatted = baseFormatter.formatCompact(state.total),
                    style = MoneyHeadlineStyle,
                    autoShrink = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The headline is a valuation — what everything would come to
                // if it were converted today. What is actually held in
                // another currency is said underneath in that currency, so
                // the card states the fact as well as the arithmetic.
                if (state.foreign.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.accounts_also_holding,
                            state.foreign.joinToString(" · ") { holding ->
                                baseFormatter.forCurrency(holding.currencyCode)
                                    .formatCompact(holding.amount)
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CardFaceMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // Underneath, and signed: an amount owed written without a
                // minus in front reads as money you have.
                //
                // Each of the three carries the mark of the direction it runs
                // in — money leaving, money coming back, and the two netted
                // off. They are the app's own in/out arrows, the pair the
                // holding editor and the add menu already use, so an arrow
                // means the same thing here as it does two taps away.
                if (!state.owed.isZero) {
                    CardFaceLine(
                        icon = Icons.AutoMirrored.Outlined.CallMade,
                        color = WalletTheme.colors.debt,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.accounts_owed_line,
                                baseFormatter.formatCompact(state.owed),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WalletTheme.colors.debt,
                        )
                    }
                }
                if (!state.lent.isZero) {
                    CardFaceLine(
                        icon = Icons.AutoMirrored.Outlined.CallReceived,
                        color = WalletTheme.colors.moneyIn,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.accounts_lent_line,
                                baseFormatter.formatCompact(state.lent),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WalletTheme.colors.moneyIn,
                        )
                    }
                }
                if (!state.owed.isZero || !state.lent.isZero) {
                    CardFaceLine(
                        icon = Icons.Outlined.Balance,
                        color = CardFaceMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.accounts_net_worth,
                                baseFormatter.formatCompact(
                                    state.total + state.lent - state.owed,
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = CardFaceMuted,
                        )
                    }
                }
                if (state.hasUnconvertible) {
                    CardFaceLine(
                        icon = Icons.Outlined.ErrorOutline,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.accounts_rate_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // One heading per bank, and no figure beside it: a bank's holdings run
        // in both directions, so a single number over them could only be a
        // subtraction nobody asked for. Each row underneath says what it *is* —
        // "Savings", "Loan" — because the name is already the heading and
        // repeating it four times distinguished nothing.
        //
        // The whole group is one item, not a heading followed by rows: the card
        // is what makes them read as *this bank's*, and a card cannot be built
        // out of separate list items. A bank holds a handful of things, so
        // nothing is lost by drawing them together.
        state.groups.forEach { group ->
            item(key = "grp-${group.title ?: group.titleRes?.toString() ?: "other"}") {
                Column(
                    modifier = Modifier.padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 20.dp,
                    )
                ) {
                    GroupTitle(
                        // The bank's own name, the app's word for a section it
                        // named itself — Goals — or "Other" for the holdings
                        // that belong to nobody.
                        title = group.title
                            ?: stringResource(group.titleRes ?: R.string.accounts_other),
                        icon = group.icon(),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Clipped before the background, so a row's ripple
                            // stops at the rounded corner instead of painting
                            // over it.
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        val underBank = group.title != null
                        // **No rule between the rows of a section the app named.**
                        // A policy, a goal or a wallet carries a bar of its own
                        // under it — see [HoldingProgressBar] — and a hairline
                        // under that is a second line across the card a few
                        // points below the first. The rows are one kind of thing
                        // listed together and are already separated by the air
                        // around them; a bank's card keeps its rules, where the
                        // rows are savings, a deposit and a loan and the reader
                        // is looking for where one ends.
                        val ruled = underBank
                        group.accounts.forEachIndexed { index, row ->
                            if (index > 0 && ruled) Hairline(inset = 16.dp)
                            AccountRow(
                                row = row,
                                baseFormatter = baseFormatter,
                                today = state.today,
                                // The demo holding goes without being asked
                                // about. Every real one asks, because an account
                                // takes every movement that ever touched it and
                                // there is nothing to bring back — but this one
                                // the app opened itself a moment ago, holds
                                // nothing, and exists to be swiped away. A
                                // dialog there is the lesson interrupting the
                                // gesture it just asked for. Only for this row:
                                // nothing about the question every other holding
                                // asks has changed.
                                //
                                // Nor is the delete reported. "Deleted" is the
                                // app confirming something the user meant to do
                                // to their own figures; this holding is the
                                // app's own, put there to be swiped and taken
                                // back either way, and a snackbar over the
                                // lesson is the app answering a question it
                                // asked itself.
                                onDelete = {
                                    if (row.account.id == highlightAccountId) {
                                        viewModel.deleteAccount(row.account.id)
                                    } else {
                                        deleting = Deletion.of(row)
                                    }
                                },
                                highlighted = row.account.id == highlightAccountId,
                                onHighlightBounds = onHighlightBounds,
                                // Under a bank's own name the kind is the whole
                                // of what this row is; a wallet or a cash tin
                                // has no heading above it, so it still has to
                                // say what it is called.
                                underBank = underBank,
                                // Under a heading the app wrote — Insurance,
                                // Goals — the row keeps its own name but drops
                                // the kind: the heading has just said it, and a
                                // policy called "Insurance · NPR" under the word
                                // Insurance says one thing twice.
                                saysKind = group.titleRes == null,
                                onEdit = { onOpenAccount(row.account.id) },
                                onAdjust = { viewModel.startAdjust(row) },
                            )
                        }
                        group.loans.forEachIndexed { index, loan ->
                            if (ruled && (index > 0 || group.accounts.isNotEmpty())) {
                                Hairline(inset = 16.dp)
                            }
                            LoanRow(
                                loan = loan,
                                baseFormatter = baseFormatter,
                                underBank = underBank,
                                onDelete = { deleting = Deletion.of(loan) },
                                onClick = { onOpenLoan(loan.id) },
                            )
                        }
                    }
                }
            }
        }

        // What is left is money with a person, which has no bank to sit under.
        if (state.personalLoans.isNotEmpty()) {
            item {
                DebtGroup(
                    title = stringResource(R.string.accounts_owe),
                    loans = state.personalLoans,
                    baseFormatter = baseFormatter,
                    onDelete = { deleting = Deletion.of(it) },
                    onOpenLoan = onOpenLoan,
                )
            }
        }

        // Money lent out gets its own section. It is an asset, and putting it
        // anywhere near the debts would invite adding the two together.
        if (state.lentOut.isNotEmpty()) {
            item {
                DebtGroup(
                    title = stringResource(R.string.accounts_owed_to_you),
                    loans = state.lentOut,
                    baseFormatter = baseFormatter,
                    onDelete = { deleting = Deletion.of(it) },
                    onOpenLoan = onOpenLoan,
                )
            }
        }
    }

    state.adjusting?.let { adjust ->
        AdjustBalanceDialog(
            state = adjust,
            onTarget = viewModel::setAdjustTarget,
            onConfirm = viewModel::confirmAdjust,
            onDismiss = viewModel::dismissAdjust,
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            // Named the way the row itself is named — what the user called
            // this one, or what kind it is, and the bank underneath it. The
            // bare `name` column says "Nabil Bank" on every unnamed holding
            // there, so a dialog headed with it asked the user to delete one of
            // four things without saying which.
            title = {
                val label = listOfNotNull(
                    target.ownName ?: stringResource(target.kindRes),
                    target.institution?.takeIf { !it.equals(target.ownName, ignoreCase = true) },
                ).joinToString(" · ")
                Text(stringResource(R.string.accounts_delete_title, label))
            },
            text = {
                Text(
                    stringResource(
                        if (target.isLoan) {
                            R.string.accounts_delete_body_debt
                        } else {
                            R.string.accounts_delete_body
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (target.isLoan) {
                            viewModel.deleteLoan(target.id)
                        } else {
                            viewModel.deleteAccount(target.id)
                        }
                        deleting = null
                        onDeleted()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * A holding a swipe has proposed removing, named so the dialog can say which.
 *
 * One type for both halves of the page: an account and a debt are removed by
 * different repositories but the question asked about them is the same, and two
 * pieces of dialog state would be two ways for the page to be showing two.
 */
private data class Deletion(
    val id: String,
    /** What the user called this one, or null where they called it nothing. */
    val ownName: String?,
    /** The bank it sits under, said second where it says something new. */
    val institution: String?,
    @StringRes val kindRes: Int,
    val isLoan: Boolean,
) {
    companion object {
        fun of(row: AccountWithBalance) = Deletion(
            id = row.account.id,
            ownName = row.account.ownName,
            institution = row.account.institution,
            kindRes = row.account.kind.labelRes(),
            isLoan = false,
        )

        fun of(loan: Loan) = Deletion(
            id = loan.id,
            ownName = loan.ownName ?: loan.name,
            // A debt between people is named for the person, and the lender
            // column on an old one holds that same name — saying it twice.
            institution = loan.lender?.takeIf { loan.kind != LoanKind.PERSONAL },
            kindRes = loan.kindLabelRes(),
            isLoan = true,
        )
    }
}

/**
 * A section's heading with the mark of what it is a section of.
 *
 * The page is a column of near-identical cards under near-identical headings,
 * and the one thing that differs between them — a bank, the wallets, the goals,
 * money with a person — was a word set in the same type as every other word on
 * the page. The glyph is what lets a reader find the block they came for
 * without reading the headings above it.
 *
 * It is a mark and not a picture: [TitleStyle]'s own size, the quiet ink the
 * second line of every row is set in, and no content description — the heading
 * beside it says the same thing in words, and a screen reader announcing both
 * would say it twice.
 */
@Composable
private fun GroupTitle(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = TitleStyle)
    }
}

/**
 * Which mark heads this block.
 *
 * Read from the heading the group already carries rather than from what is
 * inside it: a bank's card holds a savings account, a deposit and a loan at
 * once, so no holding in it can speak for the block, and the sections the app
 * names itself hold exactly one kind each. The glyphs are the ones the add menu
 * offers those same kinds under, so the mark a user picked a holding by is the
 * mark they later find it under.
 */
@Composable
private fun AccountGroup.icon(): ImageVector = when (titleRes) {
    R.string.accounts_wallets -> Icons.Outlined.AccountBalanceWallet
    R.string.accounts_kind_insurance -> Icons.Outlined.Shield
    R.string.accounts_goals -> Icons.Outlined.Savings
    // A name means a bank. What is left is the nameless block at the foot of
    // the page, which is the cash — money that really is nowhere in particular.
    else -> if (title != null) Icons.Outlined.AccountBalance else Icons.Outlined.Payments
}

/**
 * Money with people, drawn the way a bank's holdings are.
 *
 * A person is not a bank and has no card of holdings, but the rows are the same
 * rows: leaving them loose on the page while every bank's sat in a card made the
 * page read as two different lists.
 */
@Composable
private fun DebtGroup(
    title: String,
    loans: List<Loan>,
    baseFormatter: MoneyFormatter,
    onDelete: (Loan) -> Unit,
    onOpenLoan: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        GroupTitle(
            title = title,
            // Both of these sections are money with a person, whichever way it
            // is running — the heading's own words say which, and two glyphs
            // for one kind of thing would claim they were two.
            icon = Icons.Outlined.Person,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            loans.forEachIndexed { index, loan ->
                // Unruled, as every section the app names itself is: money with
                // one person and money with the next are two of the same thing
                // rather than two parts of one arrangement, and each already
                // carries a bar under it. See the note in the bank groups above.
                LoanRow(
                    loan = loan,
                    baseFormatter = baseFormatter,
                    underBank = false,
                    onDelete = { onDelete(loan) },
                    onClick = { onOpenLoan(loan.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(
    row: AccountWithBalance,
    baseFormatter: MoneyFormatter,
    underBank: Boolean,
    /** For the one bar drawn over time rather than money — see [HoldingProgress]. */
    today: LocalDate,
    onEdit: () -> Unit,
    onAdjust: () -> Unit,
    onDelete: () -> Unit,
    saysKind: Boolean = true,
    /** True while this is the row a lesson is being taught on. */
    highlighted: Boolean = false,
    onHighlightBounds: (Rect) -> Unit = {},
) {
    val ownFormatter = remember(row.account.currencyCode) {
        baseFormatter.forCurrency(row.account.currencyCode)
    }
    // Correcting a balance is a long press on the row it corrects.
    //
    // It was a pencil beside the figure, which is one more thing to draw on
    // every row and a target a thumb hits by accident on the way to opening the
    // account. Not offered on a deposit, a policy or a goal: what each of those
    // holds is worked out from its own terms or put there by its own rule, so a
    // correction would be money arriving from nowhere in a holding nothing may
    // be paid into.
    val correctable = !row.account.isFixedDeposit && !row.account.isInsurance &&
        !row.account.isGoal
    val adjustLabel = stringResource(R.string.accounts_adjust)
    // Drawn light while it is being taught on, whatever the app is set to: what
    // a spotlight does is stop dimming one thing, and in the dark scheme that is
    // a dark row left dark with nothing to see. See [TutorialLight].
    TutorialIf(highlighted) {
    SwipeToDelete(
        rowKey = row.account.id,
        onSwiped = onDelete,
        background = MaterialTheme.colorScheme.surfaceContainer,
        modifier = if (highlighted) {
            Modifier.onGloballyPositioned { onHighlightBounds(it.boundsInWindow()) }
        } else {
            Modifier
        },
    ) {
    // A column rather than a row, for the kinds that have something to draw
    // under themselves. The bar spans the whole width — it is the row's own
    // measure of itself, not a note beside its name — and a holding with no end
    // to reach sees an ordinary row with nothing below it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onEdit,
                onLongClick = if (correctable) onAdjust else null,
                onLongClickLabel = adjustLabel,
            )
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelDot(color = row.account.color, size = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            val kind = stringResource(row.account.kind.labelRes())
            // Under a bank heading, what the user called this one — or, when
            // they called it nothing, what kind it is: "Dollar Account", or
            // "Fixed deposit". The bank above says the rest. On its own it
            // leads with its name, because there is no heading to lean on.
            Text(
                text = if (underBank) {
                    holdingLabel(
                        ownName = row.account.ownName,
                        kindRes = row.account.kind.labelRes(),
                    )
                } else {
                    row.account.name
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            // A deposit's row says when it comes free, and a policy's when it
            // pays out, because that is the one thing about either the user
            // cannot act on and most wants to know.
            //
            // With the year, unlike a day header: these are years out — a
            // twenty-year policy matures in 2046 — and "२९ साउन" alone happens
            // every year, which reads as a date this one.
            val dates = LocalDateDisplay.current
            val freeOn = row.account.maturesOn
            Text(
                // What kind it is, whenever the line above has not said it —
                // which is every named holding, since the name took the title's
                // place. "Savings" belongs somewhere on the row: two named
                // holdings at one bank are otherwise told apart only by their
                // figures.
                text = listOfNotNull(
                    kind.takeIf { if (underBank) row.account.ownName != null else saysKind },
                    row.account.currencyCode,
                    freeOn?.let { dates.full(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // Which figure leads is the account's own choice: dollars for someone
        // who thinks in dollars, rupees for someone who only cares what it is
        // worth at home. The other one goes underneath, and only when it says
        // something new.
        val foreign =
            !row.account.currencyCode.equals(baseFormatter.currencyCode, ignoreCase = true)
        val leadInBase = row.account.showInDisplayCurrency && row.balanceInBase != null
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                formatted = if (leadInBase) {
                    baseFormatter.formatCompact(row.balanceInBase!!)
                } else {
                    ownFormatter.formatCompact(row.balance)
                },
                style = MoneyRowStyle,
            )
            if (foreign) {
                val second = if (leadInBase) {
                    ownFormatter.formatCompact(row.balance)
                } else {
                    row.balanceInBase?.let { baseFormatter.formatCompact(it) }
                }
                second?.let {
                    Text(
                        text = it,
                        style = MoneySmallStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // What the figure above is on its way to, said the way a loan says
            // what it was borrowed against: the balance alone is a number, and
            // the pair is a distance.
            row.account.goalTerms?.let { goal ->
                Text(
                    text = stringResource(
                        R.string.loan_of_principal,
                        ownFormatter.formatCompact(goal.target),
                    ),
                    style = MoneySmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
        // How far along this arrangement is, drawn rather than said — a goal
        // filling up, a policy being paid up, a deposit running down its term.
        // Two figures and a date are a sentence to be read; a bar is the one
        // thing on this screen that can be understood without reading, which is
        // the whole point of setting a goal rather than simply having savings,
        // and just as true of everything else with an end to reach. Anything
        // that is simply somewhere money sits has none, since there is nothing
        // it is on its way to.
        HoldingProgressBar(
            progress = HoldingProgress.of(row.account, row.balance, today),
            color = row.account.color,
        )
    }
    }
    }
}

/**
 * How far along one holding is, under the row it belongs to.
 *
 * One composable for every kind that has an end to reach, because they are one
 * idea: what the bar *measures* is the arrangement's own question — see
 * [HoldingProgress] — and what it looks like must not be. Drawn full width under
 * the row rather than beside the figures, since it is the row's own measure of
 * itself and not a note about its name.
 *
 * **In the holding's own colour**, which is how it is known everywhere else in
 * the app — the dot at the head of this row, the bar on Home's breakdown, the
 * block on the Timeline. A debt has no colour column unless it is a card, so it
 * falls back to the ink its figure is already printed in; passing null draws
 * nothing at all, which is what a holding with no honest measure gets.
 *
 * It is also what separates one row from the next in the sections the app names
 * itself, which have no hairline between their rows: a bar under a row is a
 * clearer end to it than a rule drawn across the card, and the two together were
 * two lines a few points apart.
 */
@Composable
private fun HoldingProgressBar(progress: Float?, color: Color) {
    if (progress == null) return
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { progress },
        color = color,
        // The row's own surface behind it, so the empty part of the bar reads as
        // track rather than as a second figure.
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
        // Ends where the figures do, which is the row's own edge: the pencil
        // that used to sit past them is gone.
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    )
}

/**
 * The light scheme, but only when a lesson is being taught on this row.
 *
 * A wrapper rather than a branch at every call site: the row is a long
 * composable and duplicating it under two themes would be two rows to keep in
 * step. See [TutorialLight].
 */
@Composable
private fun TutorialIf(on: Boolean, content: @Composable () -> Unit) {
    if (on) TutorialLight(content) else content()
}

@Composable
private fun AdjustBalanceDialog(
    state: AdjustState,
    onTarget: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val amountGrouping = rememberAmountGrouping()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(state.accountName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.accounts_adjust_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.targetText,
                    onValueChange = onTarget,
                    label = { Text(stringResource(R.string.accounts_adjust_hint)) },
                    prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                    visualTransformation = amountGrouping,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/**
 * One debt. Shows what is still owed, with the instalment and rate underneath
 * so the row answers "how much and how often" without opening anything.
 */
@Composable
private fun LoanRow(
    loan: Loan,
    baseFormatter: MoneyFormatter,
    underBank: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    // Its own currency unless the loan asked to be read in the display one.
    val formatter = loan.formatter(baseFormatter)
    // A **card** carries a colour of its own, because it is paid with and is
    // found in a list the way an account is; every other debt has none and takes
    // the colour the figure on the right is already printed in. The dot at the
    // head of the row and the bar under it both wear it, so a debt is known by
    // one colour wherever it is drawn.
    val mark = loan.color ?: if (loan.isLent) {
        WalletTheme.colors.moneyIn
    } else {
        WalletTheme.colors.debt
    }
    SwipeToDelete(
        rowKey = loan.id,
        onSwiped = onDelete,
        background = MaterialTheme.colorScheme.surfaceContainer,
    ) {
    // A column, for the bar underneath — see [HoldingProgressBar]. An account's
    // row has had one since goals arrived, and a debt is the thing on this page
    // with the plainest end to reach.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same dot an account's row starts with, so a bank's holdings line
        // up down one edge whichever way the money runs.
        LabelDot(color = mark, size = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            val kind = stringResource(loan.kindLabelRes())
            // The bank's name is the heading this row sits under, so what is
            // left to say is which of its debts this is — the name the user
            // gave it, or failing that its kind.
            Text(
                text = if (underBank) {
                    holdingLabel(ownName = loan.ownName, kindRes = loan.kindLabelRes())
                } else {
                    loan.name
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            val dates = LocalDateDisplay.current
            val detail = listOfNotNull(
                // What the bank calls it, first: one name can cover a term loan
                // and an overdraft at the same bank. Said here whenever the line
                // above has not — which includes a named debt, whose own name
                // now takes the title on its own.
                kind.takeIf { !underBank || loan.ownName != null },
                // The lender only when it says something the name does not: it
                // holds the bank on a debt the user has named, which is the
                // heading right above this row.
                loan.lender?.takeIf { !underBank && it != loan.name },
                loan.rateShown?.let { stringResource(R.string.loan_rate_short, it) },
                // Never on money between people: it goes back in one payment,
                // so an instalment here would quote a schedule nobody agreed
                // to. Loans entered before that was true still carry a stored
                // figure, and this is what stops it being printed.
                loan.emiShown?.takeIf { loan.kind != LoanKind.PERSONAL }?.let {
                    stringResource(
                        // "Each time" of a debt paid once describes a schedule
                        // that does not exist. One payment says when instead.
                        if (loan.paysAtEnd) {
                            R.string.loan_emi_at_end_short
                        } else {
                            R.string.loan_emi_short
                        },
                        formatter.formatCompact(it),
                    )
                },
                // Worth the space: a date is the only thing a loan with no
                // instalments has to say about when. A day to settle by where
                // one was agreed, and otherwise the day the money changed
                // hands — which on money between people is the fact both sides
                // remember, and what the interest is counted from.
                loan.dueOn?.let {
                    stringResource(R.string.loan_due_short, dates.dayAndMonth(it))
                } ?: loan.disbursedOn
                    ?.takeIf { loan.kind == LoanKind.PERSONAL }
                    ?.let { stringResource(R.string.loan_since_short, dates.dayAndMonth(it)) },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // What it would take to be done with it today, not the balance on
            // its own: on a debt that has been sitting for months the interest
            // is the whole reason there is a rate on file, and a row that
            // leaves it out states a figure the user cannot settle for.
            MoneyText(
                formatted = formatter.formatCompact(loan.settleShown),
                style = MoneyRowStyle,
                // Red is for what you owe. What is owed to you is money coming
                // back, and colouring it as a debt would read as the opposite.
                color = if (loan.isLent) {
                    WalletTheme.colors.moneyIn
                } else {
                    WalletTheme.colors.debt
                },
            )
            // What is inside that figure. Where interest has run, saying how
            // much of it is interest matters more than what was borrowed —
            // "of रू 1,00,000" beside a figure that has grown past it reads as
            // an error rather than as a debt costing money.
            val interest = loan.accruedInterest?.takeIf { it.isPositive }
            // And nothing at all when neither says anything: a debt nothing has
            // been paid off yet owes exactly what was borrowed, and
            // "रू 10,000 · of रू 10,000" is one figure printed twice under
            // itself. The line comes back the moment the two differ, which is
            // the moment it is worth reading.
            val measure = when {
                interest != null -> stringResource(
                    R.string.loan_incl_interest,
                    formatter.formatCompact(interest),
                )
                loan.principalShown != loan.settleShown -> stringResource(
                    R.string.loan_of_principal,
                    formatter.formatCompact(loan.principalShown),
                )
                else -> null
            }
            measure?.let {
                Text(
                    text = it,
                    style = MoneySmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // And what that is worth in the currency the totals above are in.
            // A dollar debt read in dollars said nothing about the rupee figure
            // it was being subtracted from at the top of the page.
            loan.convertedOutstanding(baseFormatter)?.let {
                Text(
                    text = baseFormatter.formatCompact(it),
                    style = MoneySmallStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
        // How much of it is behind them — or, on a card, how much of the ceiling
        // is gone. The two run opposite ways on purpose; see [HoldingProgress].
        HoldingProgressBar(progress = HoldingProgress.of(loan), color = mark)
    }
    }
}
