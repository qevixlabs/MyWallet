package com.mywallet.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.inputPrefix
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.domain.Account
import com.mywallet.domain.BankHolding
import com.mywallet.domain.HoldingChoice
import com.mywallet.domain.HoldingGroup
import com.mywallet.domain.HoldingPalette
import com.mywallet.domain.PersonHolding
import com.mywallet.domain.payLabel
import com.mywallet.domain.payableHoldings
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.components.CARD_INSET
import com.mywallet.ui.components.CardFooterAction
import com.mywallet.ui.components.DateField
import com.mywallet.ui.components.DatePickerBox
import com.mywallet.ui.components.DefaultCalendarSwitch
import com.mywallet.ui.components.FOOTER_GAP
import com.mywallet.ui.components.Hairline
import com.mywallet.ui.components.LIST_PANEL_ROW_INSET
import com.mywallet.ui.components.LabelDot
import com.mywallet.ui.components.MoneyText
import com.mywallet.ui.components.Reveal
import com.mywallet.ui.components.RouteText
import com.mywallet.ui.components.SectionHeader
import com.mywallet.ui.components.TermUnitChips
import com.mywallet.ui.components.WalletCard
import com.mywallet.ui.components.cardBleed
import com.mywallet.ui.components.cardWithFooter
import com.mywallet.ui.components.editableFieldColors
import com.mywallet.ui.components.pickableChipColors
import com.mywallet.ui.components.rememberAmountGrouping
import com.mywallet.ui.components.rowStripe
import com.mywallet.ui.components.termShown
import com.mywallet.ui.entryTitle
import com.mywallet.ui.holdingLabel
import com.mywallet.ui.labelRes
import com.mywallet.ui.loanMovementLabel
import com.mywallet.ui.theme.MoneyHeadlineStyle
import com.mywallet.ui.theme.TitleStyle
import com.mywallet.ui.theme.WalletTheme
import java.time.LocalDate
import com.mywallet.ui.components.TopSnackbar

/**
 * Adding anywhere money sits — including the places it is owed from.
 *
 * One form, one question at the top: a bank holds a savings account, a term loan
 * or an overdraft — several of them under one name, which is why the name and
 * the kind are asked together and shown together. Money can also be in a wallet,
 * in cash, or between the user and a person. Loans used to live behind their own
 * button, which asked the user to know in advance that a loan is not an account.
 *
 * The loan half stays deliberately forgiving: a bank loan and money borrowed
 * from a cousin are the same debt with different certainty, so rate, term and
 * instalment are all optional. Fill in what you know; the app works out the rest
 * and says nothing it cannot support.
 */
/**
 * What the form is adding, for its own title.
 *
 * Six answers open six different forms, and until the title said which, the
 * only thing on the page naming the answer was the chip row — which is absent
 * for the four kinds that have no sub-choice.
 */
@StringRes
private fun addTitleRes(group: HoldingGroup): Int = when (group) {
    HoldingGroup.BANK -> R.string.accounts_add_bank
    HoldingGroup.WALLET -> R.string.accounts_add_wallet
    HoldingGroup.CASH -> R.string.accounts_add_cash
    HoldingGroup.PERSON -> R.string.accounts_add_person
    HoldingGroup.INSURANCE -> R.string.accounts_add_insurance
    HoldingGroup.GOAL -> R.string.accounts_add_goal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null for an account: only a debt has a statement to open. */
    onOpenLedger: (() -> Unit)? = null,
    /**
     * Opens another holding at the same bank — one of the tabs. Exactly one id
     * is given, because an account and a loan are opened by different routes.
     */
    onOpenHolding: (accountId: String?, loanId: String?) -> Unit = { _, _ -> },
    /**
     * Everything that has touched this account, on a page of its own — the same
     * offer a debt makes with its payments. Pushed rather than swapped: unlike a
     * bank's tabs this is a step *into* something, and the back arrow has to come
     * back to the holding whose statement it was read from.
     */
    onOpenStatement: (String) -> Unit = {},
    /**
     * What the debt still has to pay, on a page of its own. Null for an account,
     * and withheld inside the card on a debt with no schedule to show.
     *
     * A page rather than a panel that opened in place, for the reason the
     * statement became one: a seven-year loan is eighty-four rows in a form that
     * is one long unlazy column, and expanding it pushed the colour picker and
     * Save a screen and a half down.
     */
    onOpenSchedule: (() -> Unit)? = null,
    viewModel: HoldingEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dates = LocalDateDisplay.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.isSaved) { if (state.isSaved) onDone() }

    // What the account holds, re-read whenever this screen comes back to the
    // front. Its statement is a page of its own now and a movement can be swiped
    // away there — every balance in that column is worked out from the one above
    // it, so a row leaving restates the figure on the card here. Without this the
    // reader deletes a payment, presses back, and reads a balance that still
    // counts it. Only the balance: the form may have half-typed answers in it,
    // and reloading the whole thing would throw them away on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStatement() }

    // Every card here acts in place and leaves the page rearranged under the
    // thumb. This is the only thing that says what happened, so it is shown and
    // cleared immediately — a message left in the state would come back the next
    // time the screen was recomposed from a stale value.
    val message = state.message
    // Resolved in the composition rather than inside the effect: the words
    // belong to the language the screen is being read in, and a Context pulled
    // out of the tree to format them would miss a locale change.
    val messageText = message?.let { stringResource(it.text, *it.args.toTypedArray()) }
    LaunchedEffect(message) {
        if (messageText == null) return@LaunchedEffect
        // Cleared *after* it has been seen. Clearing first would change the key
        // this effect is running on and cancel the snackbar in the same frame it
        // appeared.
        snackbar.showSnackbar(message = messageText, duration = SnackbarDuration.Short)
        viewModel.clearMessage()
    }

    // The alert goes over the page from the top; see [TopSnackbar].
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(
                                when {
                                    // While creating, the title says which of the
                                    // six forms this is. The sheet asked the
                                    // question; a page headed "Add account" over
                                    // fields asking for a policy's premium left it
                                    // unanswered, and there is nothing else on the
                                    // screen that says what is being added.
                                    !state.isEditing -> addTitleRes(state.choice.group)
                                    // One title for every holding already on file.
                                    // A debt at a bank is reached from the same list
                                    // and edited on the same form as the savings
                                    // account beside it — and on a bank's tabs the
                                    // heading changed from "Edit account" to "Edit
                                    // loan" as the user moved along the row, which
                                    // read as having landed somewhere else.
                                    else -> R.string.accounts_edit
                                }
                            ),
                            style = TitleStyle,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDone) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // Nothing at all until it is known what this holding is. The form's
            // default state is the *create* form, and drawing it for the frame or
            // two a load takes flashed empty boxes and a row of kind chips over the
            // holding the user had just tapped.
            if (state.isLoading) return@Scaffold
            // **Save sits at the foot of the page on a short form and at the foot of
            // the content on a long one.** A cash tin asks for a name, a balance and
            // a colour, and Save then landed wherever those happened to end — a
            // third of the way down, with the rest of the phone empty beneath it,
            // which reads as a form still loading rather than one already answered.
            // The page's own foot is where a thumb goes looking for it.
            //
            // The scrolling column is given a floor of one viewport and lays its two
            // halves out `SpaceBetween`: with room to spare the slack falls between
            // the questions and the button, and with none the column simply grows
            // and Save follows the last question down, exactly as it always did.
            // Weight cannot do this — inside a scroll the height is unbounded, and
            // there is no remaining space for a weighted child to take.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // imePadding out here, before the scroll and before the viewport
                    // is read: it shrinks the scrolling viewport when the keyboard
                    // appears, so the field being typed in can scroll above it, and
                    // the floor below shrinks with it rather than forcing a page's
                    // worth of empty scroll under the keyboard. Applied after the
                    // scroll modifier it would only pad the content and leave the
                    // focused field stranded underneath.
                    .imePadding(),
            ) {
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
            Column(
                modifier = Modifier.heightIn(min = viewport),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column {
                    // The bank's own name where there is one, because the row of
                    // tabs underneath is that bank's holdings and "What is it?"
                    // over four answers is a question the tabs have already asked.
                    //
                    // Drawn as a title with a rule under it rather than as another
                    // small grey caption: it is what the whole screen is about, and
                    // at caption size it read as one more section heading — the
                    // same weight as the list it was reached from, or lighter.
                    if (state.showsBankTabs) {
                        Text(
                            text = state.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Hairline()
                        Spacer(Modifier.height(14.dp))
                    } else if (!state.isEditing && state.showsKindQuestion) {
                        // Only where there is something left to *answer*. A new
                        // wallet, cash tin, policy or goal is one thing and nothing
                        // else, and the heading over an empty space asked a question
                        // with no options under it. On a holding already on file the
                        // answer is settled and carries its own label, in the box
                        // below — the eyebrow above it said the same words twice.
                        SectionHeader(title = stringResource(R.string.accounts_kind))
                        Spacer(Modifier.height(10.dp))
                    }
                    // What this is gets answered once, when it is created.
                    //
                    // Afterwards it is a statement of fact rather than a question:
                    // savings and current are not the same product, a term loan and
                    // an overdraft are not the same debt, and flipping borrowed to
                    // lent would reverse the meaning of every payment recorded
                    // against it. Getting it wrong means deleting it and entering it
                    // again, which is the honest amount of work.
                    if (state.showsBankTabs) {
                        // One bank, several holdings, one screen. Each tab is the
                        // whole of another form — its own figures, its own Save —
                        // so this is navigation rather than a choice being made:
                        // nothing about the holding open right now changes by
                        // tapping one, and nothing is carried across.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.bankTabs.forEach { tab ->
                                FilterChip(
                                    colors = pickableChipColors(),
                                    selected = tab.isCurrent,
                                    onClick = {
                                        // The one already open leads nowhere.
                                        if (!tab.isCurrent) {
                                            onOpenHolding(tab.accountId, tab.loanId)
                                        }
                                    },
                                    label = {
                                        Text(
                                            // A wallet's tab says what it holds and
                                            // nothing else — see [HoldingTab.literal].
                                            tab.literal ?: holdingLabel(
                                                tab.ownName, tab.labelRes, tab.currencyCode,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    } else if (state.isEditing) {
                        // The day it changed hands rides alongside, because on money
                        // between people the two answer one question together: what
                        // this is, and when it started. It is a settled fact by then
                        // — a box of its own further down was a field nobody could
                        // type in, taking a whole line to say a date.
                        Row(
                            // Top-aligned, so a value that wraps to two lines does
                            // not drag its neighbour's label off the line it shares.
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SettledBox(
                                label = stringResource(R.string.accounts_kind),
                                // A policy and a goal say their own name here, so
                                // nothing below has to repeat it: "Goal · Bike" is
                                // what this holding *is*, and a name field under it
                                // asked a question already answered on the line
                                // above.
                                value = listOfNotNull(
                                    state.choice.describe(),
                                    state.name.takeIf { state.hidesNameField && it.isNotBlank() },
                                ).joinToString(" · "),
                                modifier = Modifier.weight(1f),
                            )
                            // What the policy pays out, which is the other half of
                            // what it is. Settled here rather than left as a box
                            // further down, where it was the one live field on a
                            // form of settled facts.
                            if (state.isInsurance) {
                                SettledBox(
                                    label = stringResource(R.string.insurance_maturity_amount),
                                    value = state.maturityDisplay.orEmpty(),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (state.paysInOneGo) {
                                SettledBox(
                                    label = stringResource(
                                        if (state.isLent) {
                                            R.string.loan_lent_on
                                        } else {
                                            R.string.loan_borrowed_on
                                        }
                                    ),
                                    value = state.disbursedOn?.let { dates.full(it) }.orEmpty(),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        // No row of kinds. It was answered before this screen
                        // opened — six kinds open six different forms, and asking
                        // inside the form meant the first thing anyone did on "Add
                        // account" was decide which page they wanted.
                        //
                        // What is left is the sub-choice, where there is one: a bank
                        // holds four different things and money with a person runs
                        // two ways. A wallet, a cash tin, a policy and a goal are
                        // each one thing, and their section is absent rather than
                        // empty.
                        if (state.choice.group == HoldingGroup.BANK) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                state.bankHoldings.forEach { holding ->
                                    FilterChip(
                                        colors = pickableChipColors(),
                                        selected = state.choice.bank == holding,
                                        onClick = { viewModel.setBankHolding(holding) },
                                        label = { Text(stringResource(holding.labelRes())) },
                                    )
                                }
                            }
                        }
                        // Money between people runs both ways. Which way decides
                        // whether this is a debt or something owed to the user, so it
                        // is asked here rather than inferred from anything later.
                        if (state.choice.group == HoldingGroup.PERSON) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                state.personHoldings.forEach { holding ->
                                    FilterChip(
                                        colors = pickableChipColors(),
                                        selected = state.choice.person == holding,
                                        onClick = { viewModel.setPersonHolding(holding) },
                                        label = { Text(stringResource(holding.labelRes())) },
                                    )
                                }
                            }
                        }
                    }
                }

                // One name field, whatever this is. It used to be two — a name and a
                // bank, or a name and a lender — which on every screen but cash meant
                // asking the same question twice and getting the same answer.
                //
                // A policy's stays a box for good: it is a label its owner chose,
                // like the name on a bank holding, and renaming it rewrites nothing.
                if (state.hidesNameField) {
                    Unit
                } else if (state.nameSettled) {
                    // The two facts that identify the loan, on one line: who it is
                    // with, and how much was borrowed. The amount sat in a full-width
                    // box below, which invited typing into a figure that cannot be
                    // changed — what is owed falls by paying, and "Pay off a lump
                    // sum" is where that happens.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Not under the tabs: the heading above them is this name,
                        // and a field repeating it would be the same words twice on
                        // consecutive lines.
                        if (!state.showsBankTabs) {
                            SettledBox(
                                label = stringResource(state.choice.nameLabelRes()),
                                value = state.name,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (state.amountPairsWithName) {
                            // Named for what it is on this kind of debt — the loan
                            // amount, the approved limit, what was lent at the
                            // start. See [HoldingEditorState.amountLabelRes]: the
                            // chips that used to say it are gone by now, and beside
                            // a name the bare word "Amount" is the one thing on the
                            // line that does not say what it is.
                            SettledPair(
                                label = stringResource(state.amountLabelRes),
                                value = state.principalDisplay.orEmpty(),
                                boxed = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // A wallet or a cash tin has one other fact worth pairing
                        // with its name, and it is what it started with. A deposit
                        // says the same: nothing is ever paid into or out of one, so
                        // what it started with is also the whole of what is in
                        // there, and one word for both beats a second one that only
                        // this holding uses.
                        if (state.pairsOpeningWithName) {
                            SettledPair(
                                label = stringResource(R.string.accounts_opening),
                                value = state.openingDisplay.orEmpty(),
                                boxed = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // How long it runs for, beside what it holds — the two facts
                        // a deposit or a term loan is, in the space the name used to
                        // take. Drawn here rather than further down so the pair is
                        // read as one line; the fields it replaces know to stay
                        // quiet, or it would be stated twice on one screen.
                        if (state.showsBankTabs) {
                            state.termSettled()?.let { term ->
                                SettledPair(
                                    label = stringResource(R.string.loan_term),
                                    value = term,
                                    boxed = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    // Full width while creating. A field being typed into wants the
                    // room — the name and the figure are only paired once they are
                    // settled, where they are two short facts being read rather than
                    // two boxes being filled.
                    //
                    // On a bank it also suggests the banks already on file; see
                    // [BankNameField]. Everything else is a name nothing else can
                    // guess, so it is the plain box.
                    if (state.showsBankShortcuts) {
                        BankNameField(state = state, viewModel = viewModel)
                    } else {
                        NameField(state = state, viewModel = viewModel)
                    }
                }

                // And what the user calls this one, which is the only way to tell
                // two deposits at one bank apart. Optional on purpose and editable
                // for good: it is a label they chose, not a fact about the money, so
                // nothing is rewritten by changing it — unlike the amount or the
                // term above, which describe what was agreed.
                if (state.offersHoldingName) {
                    OutlinedTextField(
                        colors = editableFieldColors(),
                        value = state.holdingName,
                        onValueChange = viewModel::setHoldingName,
                        label = { Text(stringResource(R.string.accounts_holding_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Asked once, while creating. Every figure below already carries its
                // symbol, and switching currency on a holding with history would
                // relabel amounts that were never in the new one.
                if (!state.isEditing) {
                Column {
                    SectionHeader(title = stringResource(R.string.accounts_currency), divider = true)
                    Spacer(Modifier.height(10.dp))
                    // A few, and the rest behind a chip that asks for them. All
                    // seventeen wrapped into four lines of three-letter codes, so
                    // choosing the currency somebody was always going to choose
                    // meant reading the other sixteen first. Which four is the
                    // user's own answer — see [CurrencyOption.shortlist].
                    //
                    // Saved rather than remembered, because the keyboard opening
                    // under a name field is a configuration change on some phones
                    // and a list that folded itself back up there would lose the
                    // currency the user had just gone looking for.
                    var showAll by rememberSaveable { mutableStateOf(false) }
                    val shown = if (showAll) {
                        // The shortlist keeps its place and the rest follow it:
                        // expanding a list must not move what the eye is already on.
                        state.currencyChoices + (CurrencyOption.ALL - state.currencyChoices.toSet())
                    } else {
                        state.currencyChoices
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        shown.forEach { option ->
                            FilterChip(
                                colors = pickableChipColors(),
                                selected = option.code == state.currencyCode,
                                onClick = { viewModel.setCurrency(option.code) },
                                label = { Text(option.code) },
                            )
                        }
                        // Deliberately not a chip. Every other pill in this row is a
                        // currency, and one shaped like them but reading "More" was
                        // a seventeenth currency to read past — the opposite of what
                        // it is there to save. So it is what the rest of the app
                        // makes an action out of: the words in the colour that means
                        // "this does something", with no border of its own.
                        //
                        // Only until it has been answered. Once every currency is on
                        // screen there is nothing left to ask for, and a way to fold
                        // them away again is a way back to a shorter list nobody
                        // wants once they have gone past it.
                        if (!showAll && shown.size < CurrencyOption.ALL.size) {
                            Text(
                                text = stringResource(R.string.accounts_currency_more),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    // Centred against the chips rather than sitting
                                    // on the line they start at: text is shorter
                                    // than a pill, and at the top of the row it
                                    // reads as a caption over them.
                                    .align(Alignment.CenterVertically)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showAll = true }
                                    // Takes it to the chips' own height, so what the
                                    // thumb aims at is the size of what it is beside.
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                    // Why this one cannot be saved, under the row that has to
                    // change. A provider holds one wallet per currency — see
                    // [HoldingEditorState.currencyError] — and the answer is either
                    // a different currency or the wallet already on file, both of
                    // which are up here rather than at the Save button.
                    state.currencyError?.let {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                }

                if (state.isLoan) {
                    LoanFields(
                        state = state,
                        viewModel = viewModel,
                        onOpenLedger = onOpenLedger,
                        onOpenSchedule = onOpenSchedule,
                    )
                } else {
                    AccountFields(
                        state = state,
                        viewModel = viewModel,
                        onOpenStatement = onOpenStatement,
                    )
                }

                // Offered for everything that holds money, debts included, and only
                // when the two currencies actually differ.
                if (state.offersDisplayCurrency) {
                    DisplayCurrencyChoice(state = state, viewModel = viewModel)
                }

            }
                // The other half of the `SpaceBetween` above: what the page ends
                // with, rather than one more answer in the column of them. It keeps
                // the 18dp the questions are spaced by, so on a form long enough to
                // scroll nothing about the rhythm changes.
                Column {
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) { Text(stringResource(R.string.action_save)) }

                    // No delete here, on a debt any more than on an account. A
                    // holding is removed by swiping its row on the Accounts page,
                    // which asks before it acts and is the one gesture that removes
                    // anything anywhere in the app. A second way in, on the screen
                    // where the debt is being *edited*, put "delete everything about
                    // this" a thumb's width under Save.

                    Spacer(Modifier.height(24.dp))
                }
            }
            }
            }
        }
        TopSnackbar(snackbar, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * When a rate the bank moved started applying, and what it has been before.
 *
 * The date only appears once the user has actually changed the figure, because
 * that is the only moment the answer matters. Everything before that day was
 * earned — or charged — at the old rate, and the app has no way to know which
 * day unless it asks.
 */
@Composable
private fun RateChangeFields(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    if (state.asksWhenRateChanged) {
        DateField(
            // Three different questions wear this one field. A holding that
            // never had a rate is being given its first; one whose figure has
            // been emptied or zeroed is having it taken away, and the date is
            // then the day the charging stops.
            label = stringResource(
                when {
                    state.cancelsInterest -> R.string.rate_stops_from
                    state.hadRate -> R.string.rate_changed_on
                    else -> R.string.rate_applied_from
                }
            ),
            date = state.rateChangedOn,
            placeholder = stringResource(R.string.loan_pick_date),
            onPick = viewModel::setRateChangedOn,
            // A rate charges the days it is in force over, and there are no such
            // days before the money moved. Null on a bank account, which has no
            // one day it began on — only the movements it has since had.
            minDate = state.movedOn,
        )
        Text(
            // No bank is involved in money between people: a rate that moved
            // there is the two of them agreeing something different, and saying
            // "the bank moved this rate" names a party to the arrangement who
            // does not exist.
            text = stringResource(
                when {
                    // Dated back to the day it started, the rate it replaces is
                    // overwritten rather than followed, so nothing was charged.
                    state.cancelsAllInterest -> R.string.rate_stops_all_explain
                    state.cancelsInterest -> R.string.rate_stops_explain
                    !state.hadRate -> R.string.rate_applied_explain
                    state.paysInOneGo -> R.string.rate_change_explain_person
                    else -> R.string.rate_change_explain
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Under the rate on everything now. It used to sit beside one on a savings
    // account, where the interval has taken its place — and a list of dated
    // figures reads better across the page than down half of it anyway.
    if (state.rateHistory.isNotEmpty()) {
        RateHistoryColumn(state = state)
    }
}

/** What the rate has been, newest first. Only worth drawing once it has moved. */
@Composable
private fun RateHistoryColumn(state: HoldingEditorState, modifier: Modifier = Modifier) {
    val dates = LocalDateDisplay.current
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.rate_history), divider = true)
        Spacer(Modifier.height(6.dp))
        state.rateHistory.forEach { row ->
            Text(
                text = stringResource(
                    R.string.rate_history_row,
                    stringResource(R.string.loan_rate_short, row.annualRate),
                    // With the year: a rate history is read against a statement,
                    // and "१ माघ" happens every year.
                    dates.full(row.from),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Somewhere money sits: a balance and a colour. */
@Composable
private fun AccountFields(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
    onOpenStatement: (String) -> Unit,
) {
    val amountGrouping = rememberAmountGrouping()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Where the account began, and only ever answered at the beginning.
        // Every entry since has been counted from it, so editing it now would
        // move today's balance by a figure from before any of them — which is
        // what "Correct this balance" is for, and it says so where it happens.
        // Once the account exists this is drawn beside the name above, so it
        // never appears twice.
        //
        // A policy and a goal have none: nothing is put into either but its own
        // payments, and each of those is a row. A figure typed here would be a
        // payment nobody made.
        if (!state.isEditing && !state.hasPlan) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.openingText,
                onValueChange = viewModel::setOpening,
                // "Starting balance", a deposit included. It is not opened and
                // then added to — what goes in is what is in there until the day
                // it comes free — so the figure is both what it started with and
                // the whole of what it holds, and the word every other holding
                // uses says it perfectly well.
                label = { Text(stringResource(R.string.accounts_opening)) },
                prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                visualTransformation = amountGrouping,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Only where a bank actually pays something. Cash and a wallet earn
        // nothing, and a rate box on either invites a figure the app would then
        // have to pretend to credit.
        if (state.earnsInterest) {
            // A deposit's rate is the one rate in the app that genuinely cannot
            // move: being fixed for the term is the whole of what the user
            // agreed to. Every other account keeps its field, because banks
            // reprice savings quarterly.
            //
            // A running one draws its rate below, beside the day it started —
            // the two facts that decide what it pays, on one line.
            //
            // And an account already on file that has no rate draws none of it:
            // an empty box beside an interval reading "3 months" and two lines
            // explaining an arithmetic the account does none of is four things
            // to read past, all about a bank arrangement the user never said
            // they had. The offer below is the way back to it — see
            // [HoldingEditorState.offersInterest], which a debt with no terms
            // has always had.
            if ((state.isFixedDeposit && state.isEditing) || !state.showsRate) {
                Unit
            } else {
            // The rate on the left and how often it is paid on the right: what
            // this account earns is those two facts together, and one without
            // the other says nothing about what lands in it. Stacked, they read
            // as two unrelated settings — which is what they were while the
            // interval was a row in Settings belonging to every bank at once.
            var payoutEditing by remember { mutableStateOf(false) }
            val payoutFocus = remember { FocusRequester() }
            // A deposit has no payout interval — what it earns arrives in one
            // piece when the term runs out — so what pairs with its rate is the
            // term itself. They are the two facts a deposit *is*, and the same
            // pair the form states side by side once it exists; asked one under
            // the other while it was being written down, they read as two
            // unrelated questions with the rate stranded on a line of its own.
            val pairsTermWithRate = state.isFixedDeposit && !state.isEditing
            var termEditing by remember { mutableStateOf(false) }
            val termFocus = remember { FocusRequester() }
            // Height from the rate box, so a settled payout interval beside it
            // draws its rule the full height of that box — see [SettledBox].
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    colors = editableFieldColors(),
                    value = state.rateText,
                    onValueChange = viewModel::setRate,
                    label = {
                        Text(
                            stringResource(
                                if (state.isFixedDeposit) R.string.fd_rate
                                else R.string.account_rate
                            )
                        )
                    },
                    suffix = { Text("%") },
                    singleLine = true,
                    isError = state.depositError == R.string.fd_error_rate,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                if (state.offersPayoutInterval) {
                    val payoutUnit = stringResource(
                        if (state.payoutInYears) R.string.loan_term_years
                        else R.string.loan_term_months
                    )
                    // Settled once a period has actually been credited: every
                    // figure on file was worked out from this, and moving it
                    // then rewrites them rather than describing anything new.
                    // A box rather than plain text, because a length is
                    // something the eye hunts for — the same rule a settled
                    // date follows.
                    if (state.payoutSettled) {
                        SettledBox(
                            label = stringResource(R.string.account_payout_every),
                            value = termShown(state.payoutText, payoutUnit, editing = false),
                            modifier = Modifier.weight(1f).besideField(),
                            matchesFieldBeside = true,
                        )
                    } else {
                        OutlinedTextField(
                            colors = editableFieldColors(),
                            value = termShown(state.payoutText, payoutUnit, payoutEditing),
                            onValueChange = viewModel::setPayoutInterval,
                            label = { Text(stringResource(R.string.account_payout_every)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(payoutFocus)
                                .onFocusChanged { payoutEditing = it.isFocused },
                        )
                    }
                } else if (pairsTermWithRate) {
                    // The same length field every other one in the app is: the
                    // unit reads back inside the box, and its chips appear only
                    // while the figure is being typed.
                    OutlinedTextField(
                        colors = editableFieldColors(),
                        value = termShown(
                            state.termText,
                            stringResource(
                                if (state.termInYears) {
                                    R.string.loan_term_years
                                } else {
                                    R.string.loan_term_months
                                }
                            ),
                            termEditing,
                        ),
                        onValueChange = viewModel::setTerm,
                        label = { Text(stringResource(R.string.fd_how_long)) },
                        singleLine = true,
                        isError = state.depositError == R.string.fd_error_term,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(termFocus)
                            .onFocusChanged { termEditing = it.isFocused },
                    )
                }
            }
            if (pairsTermWithRate && termEditing) {
                TermUnitChips(
                    inYears = state.termInYears,
                    onPick = viewModel::setTermInYears,
                    onKeepFocus = { termFocus.requestFocus() },
                )
            }
            // Under the row rather than inside it: the chips are a row of their
            // own and belong to the box above them, exactly as every other
            // length in the app draws them.
            if (state.offersPayoutInterval && !state.payoutSettled && payoutEditing) {
                TermUnitChips(
                    inYears = state.payoutInYears,
                    onPick = viewModel::setPayoutInYears,
                    onKeepFocus = { payoutFocus.requestFocus() },
                )
            }
            // Directly under the interval, because the two are one question
            // about the same bank: how often it pays, and whose months it counts
            // to get there. On a deposit the box beside the rate is the *term*
            // rather than a payout interval, so the line says which months that
            // term is counted in — and this is the only place a deposit asks it.
            // The question used to be put a second time further down, inside
            // [DepositFields], where the same switch stood between the maturity
            // card and the colour picker: one control, asked twice on one form,
            // with two different sentences under it.
            if (state.offersInterestCalendar) {
                Spacer(Modifier.height(4.dp))
                DefaultCalendarSwitch(
                    checked = state.usesSelectedCalendar,
                    effectiveCalendarName = stringResource(state.effectiveCalendarNameRes),
                    explain = if (state.isFixedDeposit) {
                        R.string.holding_calendar_explain_deposit
                    } else {
                        R.string.holding_calendar_explain_interest
                    },
                    onChange = viewModel::setInterestInBs,
                )
            }
            }
            // A deposit says its own thing about interest, right under the
            // interval chips — this line is about a rate that moves, which a
            // deposit's does not.
            if (!state.isFixedDeposit && state.showsRate) {
                Text(
                    // Names no interval of its own: the box beside the rate is
                    // holding that answer, and a sentence restating it could
                    // only fall out of step with the field an inch above. It
                    // points at that box instead — two short lines, because
                    // what the reader wants here is what they earn and when
                    // they get it, and the paragraph this replaced explained
                    // the app's arithmetic to somebody who had asked neither.
                    // Which calendar the periods are counted in is part of this
                    // sentence wherever the switch above is absent — which is
                    // every holding already on file. It is the half of the
                    // answer a reader can act on, and with nothing left to tap
                    // there is nowhere else for it to be said.
                    text = when {
                        state.payoutSettled -> stringResource(R.string.account_payout_settled)
                        state.offersInterestCalendar ->
                            stringResource(R.string.account_rate_explain)
                        else -> stringResource(
                            R.string.account_rate_explain_calendar,
                            stringResource(state.effectiveCalendarNameRes),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // An account whose interest has been credited says so in the
                // line above, which leaves the calendar unsaid — so it is said
                // here, on its own. Never twice: the line above carries it
                // whenever it is free to.
                if (state.payoutSettled && !state.offersInterestCalendar) {
                    Text(
                        text = stringResource(
                            R.string.holding_calendar_explain_interest,
                            stringResource(state.effectiveCalendarNameRes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RateChangeFields(state, viewModel)
            }
            // The way back to all of it, on an account that has none — the same
            // offer, in the same words and drawn the same way, that a debt with
            // no terms has always had. A bank that starts paying on an account
            // opened as somewhere for money to sit is exactly the case this is
            // for, and the alternative was deleting the account and entering it
            // again, which takes every movement with it.
            if (state.offersInterest) {
                TextButton(
                    onClick = viewModel::revealTerms,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.loan_add_interest)) }
            }
        }

        // Everything a fixed deposit is: how long, when it comes free, how often
        // the interest joins it, and where it goes afterwards.
        if (state.isFixedDeposit) DepositFields(state = state, viewModel = viewModel)

        // And everything a policy is: what it pays out, what it costs, when
        // each premium falls and where the two ends of it are.
        if (state.isInsurance) InsuranceFields(state = state, viewModel = viewModel)

        // A goal is the same shape with the division the other way round: what
        // it is for, and what that costs each time.
        if (state.isGoal) {
            GoalFields(
                state = state,
                viewModel = viewModel,
                // Only once it is on file: a page is opened by id, and a goal
                // being created has none.
                onOpenStatement = state.accountId?.let { id -> { onOpenStatement(id) } },
            )
        }

        // Shown for every account, not just one earning interest: the question
        // "why is this figure what it is?" is asked of cash and wallets too.
        // A deposit is the exception — nothing has ever touched it, so its
        // statement would be one empty list; what it has is its own breakdown.
        // A policy has the opposite reason to keep it: what it holds is every
        // premium paid into it, and this is the list of them.
        if (state.isEditing && !state.isFixedDeposit && !state.isGoal) {
            // Only once the holding is on file: the id is what the statement is
            // read by, and a card offering a page for a holding that does not
            // exist yet would open an empty one.
            state.accountId?.let { id ->
                StatementCard(state, onOpenStatement = { onOpenStatement(id) })
            }
        }

        // Where the whole of it goes when the day comes, on the three holdings
        // that have such a day. Drawn here rather than inside each of their
        // field blocks, because "last thing before the colour" is a fact about
        // the *form* and not about any one of them: inside, a policy's landed
        // above its own statement card and the row was not last after all. See
        // [LandsInSection], which the three still draw themselves while the
        // holding is being written down, each where it belongs among the
        // answers being given.
        if (state.isEditing && (state.isFixedDeposit || state.isInsurance || state.isGoal)) {
            LandsInSection(state = state, viewModel = viewModel)
        }

        Column {
            // No rule above it — see the note on the debt form's own picker.
            SectionHeader(title = stringResource(R.string.accounts_colour), divider = false)
            Spacer(Modifier.height(10.dp))
            ColourPicker(selected = state.color, onPick = viewModel::setColor)
        }
    }
}

/**
 * Where the whole of it goes when the day comes: a deposit coming free, a
 * policy paying out, a goal being reached.
 *
 * One composable for the three because it is one question, asked the same way
 * and optional for the same reason — nothing is ever written on that day, so a
 * forecast naming only the holding the money leaves still says the day and the
 * figure. "Not recorded" is a chip of its own rather than a hidden second tap
 * on the selected one. Other deposits and policies are not offered, since money
 * cannot come free into somewhere it still cannot be spent from.
 *
 * *Where* it is drawn is the caller's to say, and it differs. While the holding
 * is being written down it sits among the answers being given. Once the holding
 * exists it is the last thing on the form still worth changing — everything
 * above it is settled and everything below it is a card of figures — so it goes
 * at the end, against the colour picker, rather than sitting between two blocks
 * that are only there to be read.
 */
@Composable
private fun LandsInSection(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    Column {
        SectionHeader(title = stringResource(R.string.fd_lands_in), divider = true)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                colors = pickableChipColors(),
                selected = state.depositIntoAccountId == null,
                onClick = { viewModel.setDepositInto(null) },
                label = { Text(stringResource(R.string.loan_account_none)) },
            )
            state.accounts.payableHoldings(keep = state.depositIntoAccountId)
                .forEach { account ->
                    FilterChip(
                        colors = pickableChipColors(),
                        selected = account.id == state.depositIntoAccountId,
                        onClick = { viewModel.setDepositInto(account.id) },
                        label = { Text(account.payLabel) },
                        leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                    )
                }
        }
    }
}

/**
 * Money put away for a term, asked in the words people use for it.
 *
 * Nothing here says tenor, principal, maturity instruction or auto-renewal. The
 * user knows they put money in, that they cannot touch it for a while, that it
 * earns something, and that one day it comes back — so those are the four
 * questions, in that order.
 *
 * The day the money went in is not among them. It is the maturity date less the
 * length, which are both asked, and a third box for it could only disagree with
 * the two above it; it is stated underneath instead so it can still be checked.
 */
@Composable
private fun DepositFields(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // How long, and when the money went in. A running deposit's are settled:
        // its whole nature is that the terms were fixed on the day it was made,
        // and editing either would recompute interest already earned.
        if (state.isEditing) {
            // The length is stated beside the amount at the top of the form,
            // where the two facts a deposit *is* read as one line. Here only
            // where that pair is not drawn.
            if (!state.showsBankTabs) {
                SettledBox(
                    label = stringResource(R.string.fd_how_long),
                    value = listOf(
                        state.termText,
                        stringResource(
                            if (state.termInYears) R.string.loan_term_years
                            else R.string.loan_term_months
                        ),
                    ).joinToString(" "),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // What it pays, beside the day it started paying it. Both are fixed
            // for the term — that is what a deposit *is* — so they are one pair
            // of settled facts rather than two full-width rows of nothing to do.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettledBox(
                    label = stringResource(R.string.fd_rate),
                    value = state.rateText + " %",
                    modifier = Modifier.weight(1f),
                )
                SettledDate(
                    label = stringResource(R.string.fd_started_on),
                    date = state.depositStartedOn,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // "How long?" is drawn beside the rate above, where the two facts a
            // deposit *is* read as one line — see `pairsTermWithRate`. What is
            // left here is the day the money went in.
            Column {
                SectionHeader(title = stringResource(R.string.fd_started_on), divider = true)
                Spacer(Modifier.height(10.dp))
                DatePickerBox(
                    date = state.depositStartedOn,
                    placeholder = "",
                    onPick = viewModel::setDepositStartedOn,
                )
            }
        }

        // Where it goes when it is free. It was required for a while, on the
        // reasoning that a deposit maturing into nowhere is several lakh
        // vanishing on a known date. It is not: nothing is ever written when
        // the day comes, so the forecast simply names the deposit the money
        // leaves and stays quiet about where it goes. What the rule actually
        // did was refuse to save a deposit made before the account it will come
        // back into exists. See [LandsInSection] for why it moves below the
        // card once the deposit is on file.
        if (!state.isEditing) LandsInSection(state = state, viewModel = viewModel)

        state.depositError?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Which months the term is counted in is asked once, under the length
        // itself — see [AccountFields]. It was asked here as well, which put the
        // same switch on the form twice: once beside the box it governs and once
        // between the maturity card and the colour picker, where nothing above
        // it said what it was about.
        DepositSummaryCard(state = state)
    }
}

/**
 * A goal: one figure to reach, and the rhythm that reaches it.
 *
 * The mirror of the policy form below. There the user gives what each payment
 * costs and the app counts the dates; here they give what they want to have and
 * the app divides it up — which is the whole difference between recording an
 * arrangement someone else set and making one of your own.
 *
 * Everything about the plan is settled once the goal exists, the target
 * included. It is the one figure every other one is computed from: changing it
 * would restate what the contributions already made were meant to be, and a
 * progress bar measured against a target that moved is not progress. What stays
 * live is the name, the colour, and where the money lands at the end.
 */
@Composable
private fun GoalFields(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
    onOpenStatement: (() -> Unit)?,
) {
    val amountGrouping = rememberAmountGrouping()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (state.isEditing) {
            // What the goal is for and the day it started, then how often the
            // money goes in and for how long: the two facts it was set up from,
            // then the two that describe its rhythm.
            //
            // What it costs each time is not among them. It leads the card
            // below — it is the figure the user actually acts on — and stating
            // it twice on one screen made the second one look like a different
            // question.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettledBox(
                    label = stringResource(R.string.goal_amount),
                    value = state.maturityDisplay.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                SettledDate(
                    label = stringResource(R.string.goal_started_on),
                    date = state.depositStartedOn,
                    modifier = Modifier.weight(1f),
                )
            }
            // How long it runs, then how often it is paid: the length is the
            // arrangement and the rhythm is how it is met, which is the order
            // they are agreed in and the order every other form asks them.
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettledBox(
                    label = stringResource(R.string.fd_how_long),
                    value = state.termSettled().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                SettledBox(
                    label = stringResource(R.string.loan_pay_every),
                    value = state.payEverySettled(),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // The one figure asked for. Everything else on this form is either
            // a date or a rhythm; this is the thing being saved towards.
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.maturityText,
                onValueChange = viewModel::setMaturityAmount,
                label = { Text(stringResource(R.string.goal_amount)) },
                prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                visualTransformation = amountGrouping,
                singleLine = true,
                isError = state.depositError == R.string.goal_error_target,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            // How long there is to save it, with the unit inside the box and its
            // chips only while it is being typed — the rule every length in this
            // form follows.
            // How long it runs and how often money goes in, on one line: they
            // are one plan, and the length alone says nothing about what it
            // costs to keep. Not offered "at the end": saving it all on the last
            // day is not a savings plan, it is a deadline.
            var termEditing by remember { mutableStateOf(false) }
            val termFocus = remember { FocusRequester() }
            var payEveryEditing by remember { mutableStateOf(false) }
            val payEveryFocus = remember { FocusRequester() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = termShown(
                    state.termText,
                    stringResource(
                        if (state.termInYears) R.string.loan_term_years
                        else R.string.loan_term_months
                    ),
                    termEditing,
                ),
                onValueChange = viewModel::setTerm,
                label = { Text(stringResource(R.string.fd_how_long)) },
                singleLine = true,
                isError = state.depositError == R.string.goal_error_term,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(termFocus)
                    .onFocusChanged { termEditing = it.isFocused },
            )
            OutlinedTextField(
                colors = editableFieldColors(),
                value = termShown(
                    state.payEveryText,
                    stringResource(state.payEveryUnit.labelRes()),
                    payEveryEditing,
                ),
                onValueChange = viewModel::setPayEvery,
                label = { Text(stringResource(R.string.loan_pay_every)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(payEveryFocus)
                    .onFocusChanged { payEveryEditing = it.isFocused },
            )
            }
            // Under the row rather than inside it, where two chips would wrap
            // onto two lines in a half-width column.
            if (termEditing) {
                TermUnitChips(
                    inYears = state.termInYears,
                    onPick = viewModel::setTermInYears,
                    onKeepFocus = { termFocus.requestFocus() },
                )
            }
            if (payEveryEditing) {
                TermUnitChips(
                    inYears = state.payEveryUnit == PayEvery.YEARS,
                    onPick = {
                        viewModel.setPayEveryUnit(if (it) PayEvery.YEARS else PayEvery.MONTHS)
                    },
                    onKeepFocus = { payEveryFocus.requestFocus() },
                )
            }

            // What it costs each time, in the slot where it used to be asked
            // for. It was a box of its own — the other side of the rhythm
            // above, each setting the other, so somebody who knew they could
            // spare रू 5,000 a time could type that instead of a rhythm. What
            // that cost was the same figure twice on one screen: a box saying
            // रू 8,334 with a card three inches below saying रू 8,334 under the
            // very same words, and nothing on the form to say which of them was
            // the answer. The card is the one that can also say how many
            // payments there are and the day they reach the target, so it is
            // the one that stays — here, directly under the two boxes it is
            // divided out of, rather than at the foot of the form.
            GoalPlanCard(state = state)

            // The day the first contribution goes in. The day the goal is due is
            // this plus the length, and it is not asked for: it is stated on the
            // card, where it reads as what the plan produces rather than as a
            // third fact to keep in step with the other two.
            Column {
                SectionHeader(title = stringResource(R.string.goal_started_on), divider = true)
                Spacer(Modifier.height(10.dp))
                DatePickerBox(
                    date = state.depositStartedOn,
                    placeholder = "",
                    onPick = viewModel::setDepositStartedOn,
                )
            }
        }

        // Where the money is put aside from. Required for the reason a policy's
        // is: money cannot leave an account nobody named.
        if (!state.isEditing) {
            Column {
                SectionHeader(title = stringResource(R.string.loan_account_borrowed))
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.accounts.payableHoldings(keep = state.payFromAccountId)
                        .forEach { account ->
                            FilterChip(
                                colors = pickableChipColors(),
                                selected = account.id == state.payFromAccountId,
                                onClick = { viewModel.setPayFrom(account.id) },
                                label = { Text(account.payLabel) },
                                leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                            )
                        }
                }
            }
        }

        // And where it goes when the day comes — see [LandsInSection], which
        // also says why it moves below the cards once the goal is on file.
        if (!state.isEditing) LandsInSection(state = state, viewModel = viewModel)

        state.depositError?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Which months this arrangement's own length and rhythm are counted in.
        // A deposit, a policy and a goal are the user's own arrangements rather
        // than a bank's schedule, so this is on unless they say otherwise — see
        // [HoldingEditorState.usesSelectedCalendar]. Above the split, so it is
        // there whether the goal is being set up or read back.
        if (state.offersInterestCalendar) {
            Spacer(Modifier.height(4.dp))
            DefaultCalendarSwitch(
                checked = state.usesSelectedCalendar,
                effectiveCalendarName = stringResource(state.effectiveCalendarNameRes),
                explain = R.string.holding_calendar_explain_goal,
                onChange = viewModel::setInterestInBs,
            )
        }

        if (state.isEditing) {
            GoalProgressCard(state = state, onOpenStatement = onOpenStatement)

            // The same pair of offers a debt gets, and for the same reasons —
            // see [HoldingActionsCard]. Money in and money out, in that order:
            // putting more aside is what a goal is for, and taking it back out
            // is the exception, which is why only the first is a filled button.
            var openSheet by remember { mutableStateOf<GoalAction?>(null) }
            LaunchedEffect(state.message) {
                if (state.message != null) openSheet = null
            }
            val depositLabel = stringResource(R.string.goal_deposit)
            val withdrawLabel = stringResource(R.string.goal_withdraw)
            HoldingActionsCard(
                listOf(
                    // Into the goal and out of it, which is what the two arrows
                    // say — read from the goal's own side, since this is the
                    // goal's page.
                    HoldingActionButton(
                        depositLabel,
                        Icons.AutoMirrored.Outlined.CallReceived,
                    ) { openSheet = GoalAction.DEPOSIT },
                    HoldingActionButton(
                        withdrawLabel,
                        Icons.AutoMirrored.Outlined.CallMade,
                    ) { openSheet = GoalAction.WITHDRAW },
                )
            )
            when (openSheet) {
                GoalAction.DEPOSIT -> HoldingActionSheet(
                    title = depositLabel,
                    onDismiss = { openSheet = null },
                ) { GoalMoveCard(state = state, viewModel = viewModel, deposit = true) }
                GoalAction.WITHDRAW -> HoldingActionSheet(
                    title = withdrawLabel,
                    onDismiss = { openSheet = null },
                ) { GoalMoveCard(state = state, viewModel = viewModel, deposit = false) }
                null -> Unit
            }
        }
    }
}

/**
 * Money into or out of a goal, outside its plan.
 *
 * A deposit is the whole reason someone watches a savings goal: a windfall
 * arrives and it can go straight in, and the day the goal is reached moves
 * closer. A withdrawal is the same machinery admitting the other thing that
 * happens — the money was needed for something else — and pushes that day away
 * again. Neither touches what the user said they can put aside each time: that
 * is the one figure they committed to, so the length is what gives.
 *
 * The preview says both halves of that: what the goal would hold, and when it
 * would then be due. Without the second one a deposit reads as a number moving,
 * rather than as the point of making it.
 */
@Composable
private fun GoalMoveCard(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
    deposit: Boolean,
) {
    val amountGrouping = rememberAmountGrouping()
    val dates = LocalDateDisplay.current
    val amount = if (deposit) state.goalDepositText else state.goalWithdrawText
    val after = if (deposit) state.goalDepositAfter else state.goalWithdrawAfter
    val readyOn = if (deposit) state.goalDepositReadyOn else state.goalWithdrawReadyOn
    val accountId = if (deposit) state.goalDepositAccountId else state.goalWithdrawAccountId

    // A sheet's body — see [LumpSumCard].
    Column {
        OutlinedTextField(
            colors = editableFieldColors(),
            value = amount,
            onValueChange = {
                if (deposit) viewModel.setGoalDeposit(it) else viewModel.setGoalWithdraw(it)
            },
            label = { Text(stringResource(R.string.prepay_amount)) },
            prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
            visualTransformation = amountGrouping,
            singleLine = true,
            isError = !deposit && state.goalWithdrawTooMuch,
            supportingText = if (!deposit && state.goalWithdrawTooMuch) {
                { Text(stringResource(R.string.goal_withdraw_too_much)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        DateField(
            label = stringResource(R.string.goal_moved_on),
            date = state.goalMoveDate,
            placeholder = stringResource(R.string.loan_pick_date),
            onPick = viewModel::setGoalMoveDate,
            // Nothing was put aside before there was a goal to put it in.
            minDate = state.movedOn,
        )
        // The same gap the rule inside it leaves underneath, so the line sits
        // with equal air on both sides.
        Spacer(Modifier.height(FOOTER_GAP))
        MovementAccount(
            label = stringResource(
                if (deposit) R.string.goal_deposit_from else R.string.goal_withdraw_into
            ),
            accounts = state.accounts.payableHoldings(keep = accountId),
            selectedId = accountId,
            onPick = {
                if (deposit) {
                    viewModel.setGoalDepositAccount(it)
                } else {
                    viewModel.setGoalWithdrawAccount(it)
                }
            },
        )
        if (after != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.goal_after_move, after),
                style = MaterialTheme.typography.bodyMedium,
            )
            readyOn?.let {
                Text(
                    text = stringResource(
                        R.string.goal_then_ready,
                        listOfNotNull(dates.full(it), dates.secondary(it)).joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (deposit) viewModel.applyGoalDeposit() else viewModel.applyGoalWithdraw()
                },
                enabled = accountId != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (deposit) R.string.goal_deposit_apply else R.string.goal_withdraw_apply
                    )
                )
            }
        }
    }
}

/**
 * What the plan costs, before there is any progress to show.
 *
 * Leads with the figure the user has to find each time, because that is the one
 * thing that decides whether the goal is realistic — a target is easy to want
 * and the contribution is what it actually asks for.
 */
@Composable
private fun GoalPlanCard(state: HoldingEditorState) {
    val perPayment = state.premiumDisplay ?: return
    val target = state.maturityDisplay ?: return
    val readyBy = state.policyMaturesOn ?: return
    val dates = LocalDateDisplay.current
    var showingSchedule by remember { mutableStateOf(false) }
    // The card gives up its bottom padding when the toggle is there, so the
    // words sit the same distance from the card's edge as a footer's do. The
    // table below supplies its own when it is open.
    val hasToggle = state.premiumDates.isNotEmpty()

    WalletCard(
        contentPadding = if (hasToggle) cardWithFooter(20.dp) else PaddingValues(20.dp),
    ) {
        CardEyebrow(
            text = stringResource(R.string.goal_put_aside),
            icon = Icons.Outlined.Savings,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = perPayment, style = MaterialTheme.typography.headlineSmall)
        state.premiumTotal?.let { total ->
            Text(
                text = pluralStringResource(
                    R.plurals.insurance_payments_total,
                    state.premiumCount,
                    state.premiumCount,
                    total,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // The target and the day it is due, which is the whole point and the one
        // date the form never asked for.
        Text(
            text = stringResource(
                R.string.goal_ready_by,
                target,
                listOfNotNull(dates.full(readyBy), dates.secondary(readyBy))
                    .joinToString(" · "),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = WalletTheme.colors.moneyIn,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (state.premiumDates.isNotEmpty()) {
            ScheduleToggle(
                expanded = showingSchedule,
                onToggle = { showingSchedule = !showingSchedule },
            )
            if (showingSchedule) {
                Reveal { PremiumTable(dates = state.premiumDates, amount = perPayment) }
            }
        }
    }
}

/**
 * How far along a goal is, once it is running.
 *
 * A bar and two figures, in that order of prominence: the point of a goal is
 * the distance left, and a bar says that at a glance where "रू 25,000 of
 * रू 1,00,000" has to be read and divided. Both are here because only the
 * figures can be checked against the statement below.
 */
@Composable
private fun GoalProgressCard(state: HoldingEditorState, onOpenStatement: (() -> Unit)?) {
    val saved = state.goalSaved ?: return
    val target = state.maturityDisplay ?: return
    val readyBy = state.policyMaturesOn
    val dates = LocalDateDisplay.current

    // The way into the transactions lives here rather than in a card of its own.
    // That card led with the balance again, under a second heading, an inch
    // below the one this card opens with — the same figure twice, inviting the
    // reader to look for the difference. What it had that this did not was the
    // way to the rows, so that is what moved. See [CardFooterAction].
    WalletCard(
        contentPadding = if (onOpenStatement != null) {
            cardWithFooter(20.dp)
        } else {
            PaddingValues(20.dp)
        },
    ) {
        CardEyebrow(
            text = stringResource(R.string.goal_saved_title),
            icon = Icons.Outlined.Savings,
        )
        Spacer(Modifier.height(4.dp))
        MoneyText(
            formatted = saved,
            style = MoneyHeadlineStyle,
            autoShrink = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { state.goalSavedFraction },
            color = state.color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                R.string.goal_saved_of,
                saved,
                target,
                (state.goalSavedFraction * 100).toInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // What is left, or that there is nothing left. Said in words rather than
        // shown as an empty remainder: "रू 0 still to go" is a sentence nobody
        // wants to read at the end of saving for something.
        Text(
            text = if (state.goalReached) {
                stringResource(R.string.goal_reached)
            } else {
                stringResource(R.string.goal_left, state.goalLeft.orEmpty())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.goalReached) {
                WalletTheme.colors.moneyIn
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
        readyBy?.let {
            Text(
                text = stringResource(
                    R.string.goal_ready_by,
                    target,
                    listOfNotNull(dates.full(it), dates.secondary(it)).joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        onOpenStatement?.let {
            CardFooterAction(text = stringResource(R.string.statement_every), onClick = it)
        }
    }
}

/**
 * A policy: two figures off the document, and the schedule between them.
 *
 * Everything above the card is asked once, while creating, and stated
 * afterwards. A policy's terms are not something its holder can edit — the
 * premium, how often it falls, the day it started and how long it runs are all
 * the insurer's, and changing one here would rewrite premiums already paid
 * rather than describe anything. What stays live is what the policy will pay
 * out, which no schedule was ever computed from, and where the money goes when
 * it does.
 *
 * "How long?" and the maturity date are one answer with two faces, so both are
 * offered and each sets the other: a policy is agreed for twenty years and
 * matures in 2091, and which of those a person has to hand depends on which
 * document they are looking at.
 */
@Composable
private fun InsuranceFields(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    val amountGrouping = rememberAmountGrouping()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // What the whole thing is for, first: the figure the policy exists to
        // produce. Still a box on a running policy — an insurer's bonus moves
        // it, and nothing has ever been computed from it, so correcting it
        // rewrites nothing.
        // Only while it is being written down. On a running policy it is stated
        // at the top of the form beside what the policy is, where it reads as
        // one of the two facts off the certificate rather than as the single
        // live box on a page of settled ones.
        if (!state.isEditing) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.maturityText,
                onValueChange = viewModel::setMaturityAmount,
                label = { Text(stringResource(R.string.insurance_maturity_amount)) },
                prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                visualTransformation = amountGrouping,
                singleLine = true,
                // Both boxes are marked when the two figures cannot both be
                // right: which of them is the wrong one is the user's to say,
                // and reddening only the premium would name the other as
                // settled when it is as likely to be the typo.
                isError = state.depositError == R.string.insurance_error_maturity ||
                    state.policyPremiumTooBig,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.isEditing) {
            // The four settled facts, in the order the arrangement happened:
            // what one premium costs and the day the first was paid, then how
            // often they fall and for how long. The pairs that read together
            // are the two figures the policy started from and the two that
            // describe its schedule — not, as they were, the cost beside its
            // frequency and the length beside its start.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettledBox(
                    label = stringResource(R.string.insurance_premium),
                    value = state.premiumDisplay.orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                SettledDate(
                    label = stringResource(R.string.insurance_started_on),
                    date = state.depositStartedOn,
                    modifier = Modifier.weight(1f),
                )
            }
            // How long it runs, then how often it is paid: the length is the
            // arrangement and the rhythm is how it is met, which is the order
            // they are agreed in and the order every other form asks them.
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettledBox(
                    label = stringResource(R.string.fd_how_long),
                    value = state.termSettled().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                SettledBox(
                    label = stringResource(R.string.loan_pay_every),
                    value = state.payEverySettled(),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.premiumText,
                onValueChange = viewModel::setPremium,
                label = { Text(stringResource(R.string.insurance_premium)) },
                prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                visualTransformation = amountGrouping,
                singleLine = true,
                isError = state.depositError == R.string.insurance_error_premium ||
                    state.policyPremiumTooBig,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            // How long it runs and how often a premium falls, on one line: they
            // are one arrangement, and one without the other says nothing about
            // what it costs. Each keeps the rule every length in the app follows
            // — the unit reads back inside the box, and its chips appear only
            // while the figure is being typed. "At the end" is not offered: a
            // policy paid for in one go at the end of its term is not a policy
            // anybody sells.
            var termEditing by remember { mutableStateOf(false) }
            val termFocus = remember { FocusRequester() }
            var payEveryEditing by remember { mutableStateOf(false) }
            val payEveryFocus = remember { FocusRequester() }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = termShown(
                    state.termText,
                    stringResource(
                        if (state.termInYears) R.string.loan_term_years
                        else R.string.loan_term_months
                    ),
                    termEditing,
                ),
                onValueChange = viewModel::setTerm,
                label = { Text(stringResource(R.string.fd_how_long)) },
                singleLine = true,
                isError = state.depositError == R.string.insurance_error_term,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(termFocus)
                    .onFocusChanged { termEditing = it.isFocused },
            )
            OutlinedTextField(
                colors = editableFieldColors(),
                value = termShown(
                    state.payEveryText,
                    stringResource(state.payEveryUnit.labelRes()),
                    payEveryEditing,
                ),
                onValueChange = viewModel::setPayEvery,
                label = { Text(stringResource(R.string.loan_pay_every)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(payEveryFocus)
                    .onFocusChanged { payEveryEditing = it.isFocused },
            )
            }
            // Under the row rather than inside it, where two chips would wrap
            // onto two lines in a half-width column.
            if (termEditing) {
                TermUnitChips(
                    inYears = state.termInYears,
                    onPick = viewModel::setTermInYears,
                    onKeepFocus = { termFocus.requestFocus() },
                )
            }
            if (payEveryEditing) {
                TermUnitChips(
                    inYears = state.payEveryUnit == PayEvery.YEARS,
                    onPick = {
                        viewModel.setPayEveryUnit(if (it) PayEvery.YEARS else PayEvery.MONTHS)
                    },
                    onKeepFocus = { payEveryFocus.requestFocus() },
                )
            }

            // The two dates, in the order they happen. The second is the same
            // fact as the length above said the other way round, so answering
            // either one moves the other rather than contradicting it.
            Column {
                SectionHeader(title = stringResource(R.string.insurance_started_on), divider = true)
                Spacer(Modifier.height(10.dp))
                DatePickerBox(
                    date = state.depositStartedOn,
                    placeholder = "",
                    onPick = viewModel::setDepositStartedOn,
                )
            }
            // Drawn exactly as the date above it, and not as the labelled field
            // a loan's dates use: the two are one question asked from both ends,
            // and two date boxes of different shapes side by side read as two
            // different kinds of answer.
            //
            // It is never empty: with no length answered it shows the earliest
            // day the policy could mature, which is one turn of the rhythm in
            // the box above. And it is floored there, since a payout falling
            // before the first period is over is a term no premium fits into.
            Column {
                SectionHeader(title = stringResource(R.string.insurance_matures_on), divider = true)
                Spacer(Modifier.height(10.dp))
                DatePickerBox(
                    date = state.policyMaturesOn,
                    placeholder = "",
                    onPick = viewModel::setPolicyMaturesOn,
                    minDate = state.policyEarliestMaturity,
                )
            }
        }

        // Where the premiums come from. Required, unlike the payout's
        // destination: a premium is a standing instruction to a bank, and one
        // with nowhere to take the money from is not a schedule.
        if (!state.isEditing) {
            Column {
                SectionHeader(title = stringResource(R.string.loan_account_borrowed))
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.accounts.payableHoldings(keep = state.payFromAccountId)
                        .forEach { account ->
                            FilterChip(
                                colors = pickableChipColors(),
                                selected = account.id == state.payFromAccountId,
                                onClick = { viewModel.setPayFrom(account.id) },
                                label = { Text(account.payLabel) },
                                leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                            )
                        }
                }
            }
        }

        // And where the payout lands. It stays answerable for good — a policy is
        // often taken out years before the account it will be paid into — which
        // is exactly why it goes last once the policy exists: everything else on
        // the form by then is settled text. See [LandsInSection].
        if (!state.isEditing) LandsInSection(state = state, viewModel = viewModel)

        state.depositError?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Which months this arrangement's own length and rhythm are counted in.
        // A deposit, a policy and a goal are the user's own arrangements rather
        // than a bank's schedule, so this is on unless they say otherwise —
        // see [HoldingEditorState.usesSelectedCalendar].
        if (state.offersInterestCalendar) {
            Spacer(Modifier.height(4.dp))
            DefaultCalendarSwitch(
                checked = state.usesSelectedCalendar,
                effectiveCalendarName = stringResource(state.effectiveCalendarNameRes),
                explain = R.string.holding_calendar_explain_premium,
                onChange = viewModel::setInterestInBs,
            )
        }

        PremiumCard(state = state)
    }
}

/**
 * What the policy costs, and every date it costs it on.
 *
 * The same card a loan's instalment gets, for the same reason: the figure that
 * actually leaves the account is the one thing a schedule is worth quoting, and
 * a policy's is the only figure on this form the user did not type. What is
 * added underneath is what it comes to over the whole term, because that is the
 * question a policy raises and a loan's does not — a premium is small and there
 * are a great many of them.
 */
@Composable
private fun PremiumCard(state: HoldingEditorState) {
    val dates = LocalDateDisplay.current
    val premium = state.premiumDisplay ?: return
    val matures = state.policyMaturesOn ?: return
    var showingSchedule by remember { mutableStateOf(false) }
    val hasToggle = !state.isEditing && state.premiumDates.isNotEmpty()

    WalletCard(
        contentPadding = if (hasToggle) cardWithFooter(20.dp) else PaddingValues(20.dp),
    ) {
        CardEyebrow(
            text = stringResource(R.string.loan_emi_computed),
            icon = Icons.Outlined.Shield,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = premium,
            style = MaterialTheme.typography.headlineSmall,
            color = if (state.policyPremiumTooBig) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            },
        )
        // And the card stops there. Everything below is worked out from the two
        // figures — how many premiums, what they come to, what the policy hands
        // back over them — and every one of those answers would be about an
        // arrangement nobody has: रू 5,000 a month towards a payout of रू 500
        // is a figure in the wrong box, not a term plan. Said here rather than
        // only under the fields because this is where the reader is looking for
        // what the numbers they typed come to.
        if (state.policyPremiumTooBig) {
            Text(
                text = stringResource(R.string.insurance_error_premium_too_big),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@WalletCard
        }
        state.premiumTotal?.let { total ->
            Text(
                text = pluralStringResource(
                    R.plurals.insurance_payments_total,
                    state.premiumCount,
                    state.premiumCount,
                    total,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // What it all leads to, on the line where a loan says what it costs.
        state.maturityDisplay?.let { payout ->
            Text(
                text = stringResource(
                    R.string.insurance_pays_out,
                    payout,
                    listOfNotNull(dates.full(matures), dates.secondary(matures))
                        .joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WalletTheme.colors.moneyIn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // And what that comes to against what goes in — the one question a
        // policy is bought on, and the only figure on the card the user cannot
        // read off their document. It has to be able to say "less", not only
        // "more": a term plan pays out nothing, and a card that stated a gain or
        // stayed silent would be describing every policy as an investment.
        state.policyGain?.let { gain ->
            Text(
                text = stringResource(
                    if (state.policyShortfall) {
                        R.string.insurance_shortfall
                    } else {
                        R.string.insurance_gain
                    },
                    gain,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.policyShortfall) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    WalletTheme.colors.moneyIn
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // Only while the policy is being written down, where the dates it
        // implies are the way to check the terms above before committing to
        // them. Once it exists the card underneath lists the premiums actually
        // paid, and a second list of the same dates — one of them a plan, one
        // of them a record — asks the reader to work out which is which.
        if (!state.isEditing && state.premiumDates.isNotEmpty()) {
            ScheduleToggle(
                expanded = showingSchedule,
                onToggle = { showingSchedule = !showingSchedule },
            )
            if (showingSchedule) {
                Reveal { PremiumTable(dates = state.premiumDates, amount = premium) }
            }
        }
    }
}

/**
 * Every premium, as a table of dates.
 *
 * Two columns and not four: unlike a loan's instalment nothing about a premium
 * changes from one to the next, so a payment column repeating the same figure
 * two hundred times would be a shape with nothing in it. What the table is for
 * is the dates — which day of which month, and how many of them there are.
 */
@Composable
private fun PremiumTable(dates: List<LocalDate>, amount: String) {
    val display = LocalDateDisplay.current
    // The card holding this gave its bottom padding to the toggle above, so the
    // table puts it back — otherwise the last date sits on the card's edge.
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell(stringResource(R.string.loan_schedule_col_date), DATE_WEIGHT, false)
            HeaderCell(stringResource(R.string.loan_schedule_col_payment), FIGURE_WEIGHT, true)
        }
        Spacer(Modifier.height(6.dp))
        Hairline()
        dates.forEachIndexed { index, on ->
            Row(
                // Banded exactly as the schedule table above it is: it is the
                // same table of dates in the same white card, and one banded
                // beside one not would read as two different kinds of list.
                modifier = Modifier
                    .fillMaxWidth()
                    .cardBleed()
                    .background(rowStripe(index))
                    .padding(horizontal = CARD_INSET, vertical = 7.dp),
            ) {
                FigureCell(
                    text = display.full(on),
                    weight = DATE_WEIGHT,
                    end = false,
                    emphasis = true,
                )
                FigureCell(amount, FIGURE_WEIGHT, end = true, emphasis = index == 0)
            }
        }
    }
}

/**
 * What the deposit comes to, and when.
 *
 * The figure the user is actually asking for leads — what they will get back —
 * with the day underneath it and the interest inside it on the line below.
 * What they put in is not repeated here: it is the starting balance, which the
 * form has already asked for and states beside the name.
 */
@Composable
private fun DepositSummaryCard(state: HoldingEditorState) {
    val matures = state.depositMaturesOn ?: return
    val atEnd = state.depositMaturityValue ?: return
    val dates = LocalDateDisplay.current

    WalletCard {
        // The one card here that opened straight onto its figure where its six
        // siblings all name themselves first — so the largest number on the
        // screen sat under nothing saying what it was.
        CardEyebrow(
            // The words the timeline already heads this same forecast
            // with, rather than a new string saying the same thing.
            text = stringResource(R.string.fd_row_matures),
            icon = Icons.Outlined.EventAvailable,
        )
        Spacer(Modifier.height(4.dp))
        MoneyText(
            formatted = atEnd,
            style = MoneyHeadlineStyle,
            autoShrink = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.fd_on_date,
                listOfNotNull(dates.full(matures), dates.secondary(matures))
                    .joinToString(" · "),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.depositTotalInterest?.let { earned ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.fd_holds_earned) + " " + earned,
                style = MaterialTheme.typography.bodyMedium,
                color = WalletTheme.colors.moneyIn,
            )
        }
    }
}

/**
 * What goes back in the end on money between people.
 *
 * The figure they are actually asking for leads — the whole sum to hand over —
 * with the interest inside it underneath. It stands where a term loan's
 * instalment card stands, and answers the same question that card answers for a
 * schedule: what will this cost me.
 *
 * Absent entirely when no rate and no length were agreed, which is most money
 * between people. Then the amount borrowed is the whole story and a card
 * repeating it would be one.
 */
@Composable
private fun OneGoTotalCard(state: HoldingEditorState) {
    val total = state.totalToRepay ?: return
    val interest = state.interestToRepay ?: return
    val dates = LocalDateDisplay.current

    WalletCard {
        CardEyebrow(
            text = stringResource(
                if (state.isLent) R.string.loan_one_go_back else R.string.loan_one_go_total
            ),
            icon = Icons.Outlined.Payments,
        )
        Spacer(Modifier.height(4.dp))
        MoneyText(
            formatted = total,
            style = MoneyHeadlineStyle,
            autoShrink = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.loan_one_go_interest, interest),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // When it is wanted back. Not a field of its own: it is the day the
        // money moved plus the length already agreed above, and a third box
        // could only agree with those two or contradict them.
        state.oneGoDueOn?.let { due ->
            Text(
                text = stringResource(
                    R.string.loan_one_go_by,
                    listOfNotNull(dates.full(due), dates.secondary(due)).joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The account the money itself passed through on the day the debt was made,
 * which is [HoldingEditorState.disbursedOn] — the date asked directly above.
 *
 * Two facts, not one, and the form used to ask only the second: a debt moves
 * twice in opposite directions, and the account the money landed in is very
 * often not the one it goes back from. Without this the user recorded the same
 * movement twice — once as the debt, once by hand into the account — or simply
 * never saw the money arrive at all.
 *
 * **Optional, with "not recorded" as a chip rather than a hidden second tap.**
 * Blank is the right answer surprisingly often: a debt written down months after
 * it moved sits against a balance the user has already corrected, and crediting
 * it again would double it. That is a choice worth making visible, so it is
 * offered as an option instead of leaving the user to guess that tapping the
 * selected chip clears it.
 *
 * Asked only while creating. Once the debt exists that movement has happened —
 * the row it wrote is dated and sitting in an account — and re-pointing a field
 * would not move it. From then on the question belongs to each new payment, and
 * is asked inside the card that is about to make one.
 */
@Composable
private fun DisbursedAccountSection(
    state: HoldingEditorState,
    accounts: List<Account>,
    viewModel: HoldingEditorViewModel,
) {
    Column {
        SectionHeader(
            title = stringResource(
                if (state.isLent) {
                    R.string.loan_account_paid_out
                } else {
                    R.string.loan_account_received
                }
            ),
            divider = true,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                colors = pickableChipColors(),
                selected = state.disbursedAccountId == null,
                onClick = { viewModel.setDisbursedAccount(null) },
                label = { Text(stringResource(R.string.loan_account_none)) },
            )
            accounts.forEach { account ->
                FilterChip(
                    colors = pickableChipColors(),
                    selected = account.id == state.disbursedAccountId,
                    onClick = { viewModel.setDisbursedAccount(account.id) },
                    label = { Text(account.payLabel) },
                    leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                )
            }
        }
        // What choosing one actually does, in the words of what will appear in
        // that account: this writes a real dated movement, and a field that
        // quietly moves a balance is the one kind that has to say so.
        Text(
            text = stringResource(
                if (state.isLent) {
                    R.string.loan_account_paid_out_explain
                } else {
                    R.string.loan_account_received_explain
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Which currency this holding is read in.
 *
 * Only ever shown when it holds something other than the display currency —
 * there is nothing to convert otherwise, and storing a preference in that case
 * would surprise the user if they later changed their display currency.
 */
@Composable
private fun DisplayCurrencyChoice(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.showInDisplayCurrency,
                onCheckedChange = viewModel::setShowInDisplayCurrency,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                // A loan is not an account, and calling it one here is exactly
                // the confusion the rest of this form works to avoid.
                text = stringResource(
                    if (state.isLoan) {
                        R.string.loan_show_converted
                    } else {
                        R.string.accounts_show_converted
                    },
                    state.baseCurrencyCode,
                ),
                style = MaterialTheme.typography.bodyLarge,
                // The words are half the target. A checkbox whose label does
                // nothing is a 24dp box on a 6-inch screen.
                modifier = Modifier.clickable {
                    viewModel.setShowInDisplayCurrency(!state.showInDisplayCurrency)
                },
            )
        }
        Text(
            // An account row carries the converted figure underneath; a loan row
            // has no room for it, so the two say different things.
            text = stringResource(
                if (state.isLoan) {
                    R.string.loan_show_converted_explain
                } else {
                    R.string.accounts_show_converted_explain
                },
                state.currencyCode,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** A debt: what was borrowed, on what terms, and what has been paid off it. */
@Composable
private fun LoanFields(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
    onOpenLedger: (() -> Unit)?,
    onOpenSchedule: (() -> Unit)?,
) {
    val amountGrouping = rememberAmountGrouping()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // On an existing loan the amount is a record, not an input: what is
        // owed falls by paying, so changing the debt goes through "Pay off a
        // lump sum", which records the money actually leaving. Retyping the
        // figure here would move the balance with no payment behind it. An
        // overdraft stays editable — its box is the approved ceiling, and
        // editing the ceiling never touches what has been drawn.
        val principalLocked = state.amountPairsWithName
        // Drawn beside the bank's name above instead, where it reads as part of
        // what this loan *is* rather than as something to fill in.
        if (!principalLocked) {
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.principalText,
            onValueChange = viewModel::setPrincipal,
            readOnly = principalLocked,
            // The bare word only where the chips above genuinely answer it,
            // which is money between people: "Amount lent" under a heading
            // already reading "I lent" said it twice. A bank's loan and a card's
            // ceiling name themselves — see
            // [HoldingEditorState.amountLabelRes].
            label = { Text(stringResource(state.amountLabelRes)) },
            prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
            visualTransformation = amountGrouping,
            singleLine = true,
            isError = state.amountError,
            supportingText = if (state.amountError) {
                { Text(stringResource(R.string.error_amount_required)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        }

        // Everything from here to the date below describes *terms*. Plenty of
        // money has none — "I lent Sita 8,000" is a note of who owes what, not a
        // loan with a rate — and reopening it should not present four empty
        // boxes implying the user forgot to fill them in.
        if (state.showsTerms) {
        Reveal {
        // Whether the length is being typed into, which is the only moment the
        // unit beside it is a live question.
        var termEditing by remember { mutableStateOf(false) }
        val termFocus = remember { FocusRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // The row takes its height from the rate box, so whichever settled
        // answer sits beside it draws a rule the same height rather than one
        // measured to its own two short lines. See [SettledBox].
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.rateText,
                onValueChange = viewModel::setRate,
                label = { Text(stringResource(R.string.loan_rate)) },
                suffix = { Text("%") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            // How long, and how often, are one answer given once. A running loan
            // whose term or frequency moved would have every payment already made
            // recomputed against a schedule it was never on — and neither is
            // something a borrower can change by editing a form. A lump sum is
            // how the term actually shortens, and it says so lower down.
            //
            // Money between people is the exception, and stays a box. There is
            // no schedule there for a length to rewrite: it sets the interest and
            // the day it is wanted back, both of which two people move between
            // themselves all the time. It also has to stay editable because it
            // is so often left blank at first — and a settled box then read as
            // the bare word "Months" with nothing in front of it.
            //
            // A facility is the other exception, for the same reason: its length
            // is a shelf life rather than a schedule — nothing but the expiry
            // date is counted from it — and a bank renewing a card is exactly
            // what this box is for. Settled, the only way to record a renewal
            // would be deleting the card and every purchase made on it.
            if (state.isEditing && !state.paysInOneGo && !state.isOverdraft) {
                // "7 Years", in the box it was typed into. It used to read
                // "7 Years · Monthly" as one line of plain text, which answered
                // neither question — how long is the loan, and how often is it
                // paid — and looked like a caption rather than a field.
                //
                // Withheld under a bank's tabs, where it is already stated
                // beside the amount: the rate then takes the whole line, which
                // is right for the one field on this row that can still change.
                if (state.showsBankTabs) {
                    // The length is up beside the amount, so what belongs here
                    // is the other half of the same question: at that rate, how
                    // often. Both settled, both about the schedule the bank
                    // wrote, and neither any use on its own.
                    if (state.hasInstalments) {
                        SettledBox(
                            label = stringResource(R.string.loan_pay_every),
                            value = state.payEverySettled(),
                            modifier = Modifier.weight(1f).besideField(),
                            matchesFieldBeside = true,
                        )
                    }
                } else {
                    SettledBox(
                        label = stringResource(R.string.loan_term),
                        value = listOf(
                            state.termText,
                            stringResource(
                                if (state.termInYears) {
                                    R.string.loan_term_years
                                } else {
                                    R.string.loan_term_months
                                }
                            ),
                        ).joinToString(" "),
                        modifier = Modifier.weight(1f).besideField(),
                        matchesFieldBeside = true,
                    )
                }
            } else {
                OutlinedTextField(
                    colors = editableFieldColors(),
                    // Reads back as "1 months" until it is typed into again: the
                    // unit is part of the answer, and the two chips that set it
                    // are only worth the room while it is being given.
                    value = termShown(
                        state.termText,
                        stringResource(
                            if (state.termInYears) {
                                R.string.loan_term_years
                            } else {
                                R.string.loan_term_months
                            }
                        ),
                        termEditing,
                    ),
                    onValueChange = viewModel::setTerm,
                    label = { Text(stringResource(R.string.loan_term)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(termFocus)
                        .onFocusChanged { termEditing = it.isFocused },
                )
            }
        }
        // Directly under the box they measure, and only while it is being
        // answered. Full width rather than inside the half-width column the box
        // sits in, where two chips would wrap onto two lines.
        if ((!state.isEditing || state.paysInOneGo || state.isOverdraft) && termEditing) {
            TermUnitChips(
                inYears = state.termInYears,
                onPick = viewModel::setTermInYears,
                onKeepFocus = { termFocus.requestFocus() },
            )
        }
        // The day the bank approved the facility, directly under the length it
        // is measured with: the two are one answer — when it started and how
        // long it runs — and the day it expires falls out of them.
        //
        // It used to be the day the card was *entered in the app*, which is the
        // right answer only for somebody recording one the week they were given
        // it. Nothing else is counted from it: interest on a card is metered
        // from the purchases, and the expiry decides one thing, which is whether
        // the card is still offered as somewhere money can be spent from.
        if (state.isOverdraft) {
            Column {
                SectionHeader(title = stringResource(R.string.loan_opened_on), divider = true)
                Spacer(Modifier.height(10.dp))
                DatePickerBox(
                    date = state.openedOn,
                    placeholder = stringResource(R.string.loan_no_date),
                    onPick = viewModel::setOpenedOn,
                )
                // What the two boxes come to, said once. A date the user can
                // check beats a rule they have to apply — and it is the only
                // thing either box decides, so leaving it unsaid would make the
                // pair look like paperwork.
                state.facilityExpiresOn?.let { expires ->
                    val expired = state.facilityHasExpired
                    Text(
                        text = stringResource(
                            if (expired) R.string.loan_expired_on else R.string.loan_expires_on,
                            LocalDateDisplay.current.full(expires),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        // Under the length, which is the last thing said about the shape of the
        // schedule before its dates start being counted.
        if (state.offersInterestCalendar) {
            Spacer(Modifier.height(10.dp))
            DefaultCalendarSwitch(
                checked = state.usesSelectedCalendar,
                effectiveCalendarName = stringResource(state.effectiveCalendarNameRes),
                explain = R.string.holding_calendar_explain_emi,
                onChange = viewModel::setInterestInBs,
            )
        }
        // A floating loan: the instalment stays put and the split moves.
        RateChangeFields(state, viewModel)

        // How often an instalment falls. Not a scheduling detail: interest
        // accrues over the gap, so paying quarterly costs more than paying
        // monthly on the same terms, and the figures below move when this does.
        //
        // An overdraft has no instalments, so everything from here to the
        // repayment box below is hidden for one: nothing is due on any date
        // until money is actually drawn. Money between people has none either —
        // it goes back in one payment on one day — so it takes the same
        // shortcut, and is offered a total and a date instead further down.
        if (state.hasInstalments) {
        // A figure and a unit, exactly as the length above it is given. The four
        // named frequencies could not say "every two months" — a real
        // arrangement, and one a borrower cannot round to the nearest offered
        // chip without the app charging them interest for months they do not
        // have. The third answer is not a unit at all: it is one payment when
        // the term runs out, which is what a gap as long as the loan means.
        if (state.isEditing) {
            // Beside the rate under a bank's tabs, and only here otherwise.
            if (!state.showsBankTabs) {
                SettledBox(
                    label = stringResource(R.string.loan_pay_every),
                    value = state.payEverySettled(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Under the rhythm wherever that box was drawn: on a debt already
            // on file the switch is gone, and this is the only thing left
            // saying which months the schedule steps in.
            Text(
                text = stringResource(
                    R.string.holding_calendar_explain_emi,
                    stringResource(state.effectiveCalendarNameRes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var payEveryEditing by remember { mutableStateOf(false) }
            val payEveryFocus = remember { FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // One box for all three answers, exactly as the length above it
                // has one. "At the end" has no figure to type, and it used to
                // become settled text — which cost the chips their anchor: they
                // had to be drawn permanently, because a row that appeared only
                // while a box had focus would have vanished with the box the
                // moment "at the end" was chosen, taking the way back to a
                // schedule with it. Read-only rather than absent, the field
                // still takes a tap, so the chips can come and go with focus
                // like every other unit in this form.
                OutlinedTextField(
                    colors = editableFieldColors(),
                    value = if (state.paysAtEnd) {
                        stringResource(R.string.loan_pay_at_end)
                    } else {
                        termShown(
                            state.payEveryText,
                            stringResource(state.payEveryUnit.labelRes()),
                            payEveryEditing,
                        )
                    },
                    onValueChange = viewModel::setPayEvery,
                    readOnly = state.paysAtEnd,
                    label = { Text(stringResource(R.string.loan_pay_every)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(payEveryFocus)
                        .onFocusChanged { payEveryEditing = it.isFocused },
                )
                // Only while the box is being answered, and the tap goes back
                // into it so switching months to years costs one tap rather
                // than three. The requester is always attached now — the field
                // is drawn whichever answer is in force — so there is no case
                // where asking for focus has nothing to ask.
                if (payEveryEditing) {
                    PayEveryChips(
                        selected = state.payEveryUnit,
                        onPick = { picked ->
                            viewModel.setPayEveryUnit(picked)
                            payEveryFocus.requestFocus()
                        },
                    )
                }
                if (state.paysAtEnd) {
                    Text(
                        text = stringResource(R.string.loan_pay_at_end_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The computed instalment, shown live. Editable, because the bank's own
        // rounding is the figure that appears on the statement and theirs must
        // win over ours.
        state.quotedEmi?.let { quoted ->
            var showingSchedule by remember { mutableStateOf(false) }
            // The way to the schedule, at the foot of the card that quotes the
            // instalment — the same offer, drawn by the same footer, as a debt's
            // payments and a holding's transactions. Only once the debt exists:
            // a page is opened by id, and a loan being created has none, so
            // there the table stays where it was, under a toggle, as the live
            // preview of what is being typed.
            val opensSchedule = onOpenSchedule
                ?.takeIf { state.isEditing && state.scheduleFromHere.isNotEmpty() }
            WalletCard(
                contentPadding = if (
                    opensSchedule != null || state.scheduleFromHere.isNotEmpty()
                ) {
                    cardWithFooter(20.dp)
                } else {
                    PaddingValues(20.dp)
                },
            ) {
                CardEyebrow(
                    text = stringResource(
                        when {
                            // One payment is not something paid "each time". It
                            // is the whole debt, and the words money between
                            // people already uses for exactly this are the right
                            // ones — it is the same arrangement with a bank at
                            // one end of it.
                            state.paysAtEnd && state.isLent -> R.string.loan_one_go_back
                            state.paysAtEnd -> R.string.loan_one_go_total
                            state.finalPayment != null && state.finalPaymentIsLower ->
                                R.string.loan_first_payment
                            else -> R.string.loan_emi_computed
                        }
                    ),
                    icon = Icons.Outlined.Payments,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = quoted, style = MaterialTheme.typography.headlineSmall)
                // What the payment achieves, not just what it costs. Early on
                // most of it is interest, and a debt that barely moves after a
                // full instalment reads as the app having lost the money.
                val principalPart = state.nextSplitPrincipal
                val interestPart = state.nextSplitInterest
                if (principalPart != null && interestPart != null) {
                    Text(
                        text = stringResource(
                            R.string.loan_emi_split,
                            principalPart,
                            interestPart,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                state.finalPayment?.let {
                    Text(
                        text = stringResource(
                            if (state.finalPaymentIsLower) {
                                R.string.loan_falls_to
                            } else {
                                R.string.loan_last_payment
                            },
                            it,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                state.derivedTermMonths?.let {
                    Text(
                        text = pluralStringResource(R.plurals.loan_clears_in, it, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                state.totalInterest?.let {
                    Text(
                        text = stringResource(R.string.loan_total_interest, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // The whole schedule. On a debt that exists it is a page of its
                // own, reached by the footer below; while one is being *created*
                // it opens in place, because there is no loan to open a page for
                // and the table is the live answer to what is being typed. It
                // used to be a dialog behind an ⓘ, which put the one thing that
                // answers "when does this actually start paying the loan down?"
                // on top of the form rather than inside it.
                if (opensSchedule == null && state.scheduleFromHere.isNotEmpty()) {
                    ScheduleToggle(
                        expanded = showingSchedule,
                        onToggle = { showingSchedule = !showingSchedule },
                        title = stringResource(R.string.loan_schedule_title),
                    )
                    if (showingSchedule) {
                        Reveal {
                            ScheduleTable(
                                rows = state.scheduleFromHere,
                                paymentsMade = state.paymentsSoFar,
                                currencySymbol =
                                    CurrencyOption.byCode(state.currencyCode).symbol,
                            )
                        }
                    }
                }
                opensSchedule?.let {
                    CardFooterAction(
                        text = stringResource(R.string.loan_schedule_open),
                        onClick = it,
                    )
                }
            }
        }

        // A card is found in a list by its colour, exactly as an account is:
        // it sits among them on the money form, it is paid with, and the rows
        // it produces are read down the same lists theirs are. Every other debt
        // keeps the colour of its own figure — red owing, green owed to you —
        // which is right for something that is only ever a balance to look at.
        if (state.isOverdraft) {
            Column {
                // No rule above it. Every other section on the form opens a new
                // question about the money; this one asks what colour to find
                // the holding by in a list, and a line drawn over it read as a
                // heavier break than the change of subject actually is.
                SectionHeader(title = stringResource(R.string.accounts_colour), divider = false)
                Spacer(Modifier.height(10.dp))
                ColourPicker(selected = state.color, onPick = viewModel::setColor)
            }
        }

        // How the loan is made up. Asked once: changing the shape of a running
        // schedule rewrites history rather than describing it.
        //
        // The bank's own instalment used to be asked for underneath, as a figure
        // to use instead of the computed one. It is gone. It was a second answer
        // to a question the three fields above had already answered, and the one
        // the app could not check — a rounding a rupee out reads as the loan
        // costing more than it does, and every figure on this form is built from
        // it. Loans already carrying a lender's figure keep it: it is read on
        // load and written back untouched.
        if (!state.isEditing) {
        Column {
            SectionHeader(title = stringResource(R.string.loan_style), divider = true)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.styles.forEach { style ->
                    FilterChip(
                        colors = pickableChipColors(),
                        selected = state.style == style,
                        onClick = { viewModel.setStyle(style) },
                        label = { Text(stringResource(style.labelRes())) },
                    )
                }
            }
            // Both shapes at once, named, rather than a line about whichever
            // chip is currently selected. The question here is which of the two
            // to pick, and an explanation of the one already chosen answers it
            // only by being tapped through — the reader has to select a shape
            // to find out what it is. Naming both is what makes the labels in
            // the sentence worth having; a line about the selection would
            // repeat the chip above it.
            Text(
                text = stringResource(R.string.loan_style_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        }

        // The two dates a schedule runs between. There used to be a third field
        // here for a loan with no schedule — a day to clear it by — and it is
        // gone: a debt settled on one date is a length and "at the end", which
        // the two answers above already give, and the day itself falls out of
        // them. Asking for it as well was a date that could only agree with them
        // or contradict them. Loans already carrying one keep it untouched.
        if (state.hasSchedule) {
            // The day the money arrived, above the day the bank starts taking
            // it back — the order they happen in. Interest runs from the first,
            // and the gap between the two is what the first payment settles.
            if (state.isEditing) {
                // Side by side, in the order they happen: the money arrives,
                // then the bank starts taking it back. Stacked, they read as two
                // unrelated facts; beside each other the gap between them — the
                // broken period explained underneath — is the obvious thing.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettledDate(
                        label = stringResource(R.string.loan_disbursed_on),
                        date = state.disbursedOn,
                        modifier = Modifier.weight(1f),
                    )
                    SettledDate(
                        label = stringResource(R.string.loan_first_due),
                        date = state.emiStartsOn,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                DateField(
                    label = stringResource(R.string.loan_disbursed_on),
                    date = state.disbursedOn,
                    placeholder = stringResource(R.string.loan_pick_date),
                    onPick = viewModel::setDisbursedOn,
                )
                DateField(
                    label = stringResource(R.string.loan_first_due),
                    date = state.emiStartsOn,
                    placeholder = stringResource(R.string.loan_pick_date),
                    onPick = viewModel::setEmiStartsOn,
                    // The bank cannot recover money it has not handed over. The
                    // field above is the one being answered while the loan is
                    // being created, so it is read first; [movedOn] is the same
                    // day on a debt already on file.
                    minDate = state.disbursedOn ?: state.movedOn,
                )
                // What the date is for, which is not obvious from its name: it
                // is not merely the first payment but the day the whole schedule
                // is anchored to, and every one after it falls the same gap
                // later. A user who picks it thinking only of the first payment
                // has quietly moved all of them.
                //
                // Withheld where there is no later one to move — a debt settled
                // at the end has exactly one payment, and telling the user how
                // the rest are counted would be describing a schedule it does
                // not have.
                if (!state.paysAtEnd) {
                    Text(
                        text = stringResource(R.string.loan_first_due_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }
        }
        }
        }
        // Money between people: one day, one amount. The day the money actually
        // changed hands — no frequency, no instalment, no first payment, because
        // nobody sets up an EMI with their sister.
        //
        // It used to ask for a day to clear it by, and that was the wrong end of
        // the arrangement: nobody fills in a date they have not agreed, nothing
        // could be measured from it, and the one date both sides do remember —
        // the day it moved — went unrecorded. Interest is counted from this one,
        // and the day it is owed back falls out of it and the agreed length.
        if (state.paysInOneGo) {
            // Only while creating. Once the debt exists this is a settled fact
            // and it reads beside what the debt *is*, at the top of the form —
            // a disabled box down here spent a whole line saying a date nobody
            // could change.
            if (!state.isEditing) {
                DateField(
                    label = stringResource(
                        if (state.isLent) R.string.loan_lent_on else R.string.loan_borrowed_on
                    ),
                    date = state.disbursedOn,
                    placeholder = stringResource(R.string.loan_pick_date),
                    onPick = viewModel::setDisbursedOn,
                )
            }
            // Only while creating. Once the debt exists the same figures lead
            // the card below, which also carries what is left of them — two
            // cards saying "you pay back रू 8,800" and "you owe रू 8,000" one
            // above the other were the same answer twice.
            if (!state.isEditing) OneGoTotalCard(state = state)
        }

        // Interest agreed after the fact, which is how it usually happens
        // between people: the money goes first and the rate is agreed in a
        // sentence weeks later. This used to offer a whole repayment plan, which
        // was paperwork for the one kind of debt that exists to avoid it —
        // what it offers now is the one thing that is actually agreed, and the
        // date it starts applying from comes with it.
        if (state.offersInterest) {
            TextButton(
                onClick = viewModel::revealTerms,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.loan_add_interest)) }
        }

        // Nothing can be paid into or out of money put away for a term, so a
        // deposit belongs at neither end of a debt.
        val spendable = state.accounts.payableHoldings(keep = state.payFromAccountId)
        if (spendable.isNotEmpty()) {
            // The two ends of a debt, in the order they happen: the day the
            // money moved, then every day after it. Both are asked of a bank
            // loan too — the first optional and answered "not recorded" until
            // the user says otherwise, because plenty are paid straight to a
            // seller and an arrival the app invented would send them hunting
            // for money that never landed.
            if (state.showsDisbursedAccount) {
                DisbursedAccountSection(
                    state = state,
                    accounts = spendable,
                    viewModel = viewModel,
                )
            }
            // Not on a debt between people that already exists: from there on
            // every movement the form can make carries its own account, asked
            // inside the card that is about to move the money. A bank's is a
            // fact about the schedule rather than about any one payment, so it
            // stays.
            if (state.showsPayFromSection) {
            Column {
                // Named for the only thing this account does from here on: pay
                // the money back, or take it in. One wording for both a
                // schedule's instalments and a facility repaid whenever the
                // user has it — the money leaves the same account either way,
                // and "Repay from" beside "Money pay from" elsewhere asked the
                // reader to notice a difference that is not there.
                SectionHeader(
                    title = stringResource(
                        if (state.isLent) {
                            R.string.loan_account_lent
                        } else {
                            R.string.loan_account_borrowed
                        }
                    ),
                    divider = true,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Between people there is often no account at all —
                    // plenty of it is cash — and naming one the money never
                    // touches is worse than naming none. A bank debits an
                    // account on a date, so its schedule is never offered it.
                    if (state.payFromOptional) {
                        FilterChip(
                            colors = pickableChipColors(),
                            selected = state.payFromAccountId == null,
                            onClick = { viewModel.setPayFrom(null) },
                            label = { Text(stringResource(R.string.loan_account_none)) },
                        )
                    }
                    spendable.forEach { account ->
                        FilterChip(
                            colors = pickableChipColors(),
                            selected = account.id == state.payFromAccountId,
                            onClick = { viewModel.setPayFrom(account.id) },
                            label = { Text(account.payLabel) },
                            leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                        )
                    }
                }
            }
            }
        }

        // Paying a loan off only makes sense for one that already exists and
        // still has something owing on it.
        val outstanding = state.outstanding
        if (state.isEditing && outstanding != null) {
            OwedCard(state = state, outstanding = outstanding, onOpenLedger = onOpenLedger)

            // Which sheet is up, or null for none. Not saveable: a half-typed
            // payment is not something to restore across a process death, and
            // coming back to a sheet nobody opened would be worse than coming
            // back to the form.
            var openSheet by remember { mutableStateOf<DebtAction?>(null) }
            // Closed by the act succeeding. `message` is set by the repository
            // call and by nothing else, so this cannot fire on a rejected
            // amount — and closing here is what puts the snackbar saying what
            // moved in front of the reader rather than behind the sheet.
            LaunchedEffect(state.message) {
                if (state.message != null) openSheet = null
            }

            // Nothing to pay back on an overdraft nothing has been taken from.
            // The button was offered on a facility owing zero, where the only
            // figure it would accept is one the bank would refuse.
            val canPay = !state.isOverdraft || state.hasDrawnBalance
            val payLabel = stringResource(
                when {
                    state.isOverdraft -> R.string.prepay_title_overdraft
                    state.isLent -> R.string.prepay_title_lent
                    else -> R.string.prepay_title
                }
            )
            val moreLabel = stringResource(
                if (state.isLent) R.string.loan_more_title_lent else R.string.loan_more_title
            )
            HoldingActionsCard(
                buildList {
                    // Paying leads. Money between people grows as often as it
                    // shrinks, but paying one off is the commoner thing to come
                    // here for, and the two must never be mistaken for each
                    // other — which is what the filled button and the outlined
                    // one say without a word.
                    if (canPay) {
                        add(
                            HoldingActionButton(payLabel, Icons.Outlined.Payments) {
                                openSheet = DebtAction.PAY
                            }
                        )
                    }
                    if (state.canAddMore) {
                        // The same mark whichever way the money runs: borrowing
                        // more and lending more are one arrangement growing, and
                        // an arrow would have to point two ways to say it.
                        add(
                            HoldingActionButton(moreLabel, Icons.Outlined.AddCircleOutline) {
                                openSheet = DebtAction.MORE
                            }
                        )
                    }
                }
            )
            when (openSheet) {
                DebtAction.PAY -> HoldingActionSheet(
                    title = payLabel,
                    onDismiss = { openSheet = null },
                ) { LumpSumCard(state = state, viewModel = viewModel) }
                DebtAction.MORE -> HoldingActionSheet(
                    title = moreLabel,
                    onDismiss = { openSheet = null },
                ) { MoreCard(state = state, viewModel = viewModel) }
                null -> Unit
            }
        }
    }
}

/**
 * One button per thing this holding can have done to it, at the foot of its
 * form.
 *
 * The forms behind these buttons used to be laid out in the page — a payment,
 * more borrowed, money into or out of a goal, each an open card with its own
 * amount box, its own date and its own row of accounts. Two of them together
 * put four fields and three buttons under a form that was already long, and
 * every one of them was asking a question the reader had not come to answer:
 * somebody opening a debt to correct its name scrolled past a live payment box
 * to reach Save.
 *
 * So what is left on the page is the offer, and the form arrives when it is
 * taken. Nothing about what those forms *do* has changed — same fields, same
 * preview, same arithmetic — only when they are on screen.
 *
 * The words are the act itself: "Payment", "Borrow more", "Deposit". A button
 * saying what will happen needs no caption over it, which is why this card has
 * no heading of its own — and each carries the mark of what it does, which is
 * what tells two of them apart from across the page before either is read.
 *
 * **Deliberately not the primary blue.** Save is the one filled blue button on
 * this form, and these sit an inch above it doing something else entirely: an
 * offer that opens a sheet, not the act of writing the form down. Two blue
 * buttons on one screen is a coin toss for the thumb, so these take the theme's
 * secondary — the same green the app already means "money" with — and the
 * outlined one takes it too, or the pair would read as two unrelated controls.
 */
@Composable
private fun HoldingActionsCard(actions: List<HoldingActionButton>) {
    if (actions.isEmpty()) return
    WalletCard {
        actions.forEachIndexed { index, action ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            // The first is the filled one: on every holding here the leading
            // action is the one money usually moves by — a payment against a
            // debt, money into a goal — and the rest are the exception it is
            // told apart from. Two filled buttons of equal weight would make
            // "Borrow more" as likely a tap as "Payment".
            if (index == 0) {
                Button(
                    onClick = action.onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { HoldingActionLabel(action) }
            } else {
                OutlinedButton(
                    onClick = action.onClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { HoldingActionLabel(action) }
            }
        }
    }
}

/**
 * The mark and the words, in that order.
 *
 * The icon is small and takes the button's own content colour, so it reads as
 * part of the label rather than as a second thing on the button — what it is
 * there for is the glance before the reading, on a card where two buttons are
 * the same size and the same shape.
 */
@Composable
private fun HoldingActionLabel(action: HoldingActionButton) {
    Icon(
        imageVector = action.icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(action.label)
}

/** One offer on [HoldingActionsCard]: what it is called, what it opens, its mark. */
private data class HoldingActionButton(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Which of a debt's two sheets is open. */
private enum class DebtAction { PAY, MORE }

/** Which of a goal's two sheets is open. */
private enum class GoalAction { DEPOSIT, WITHDRAW }

/**
 * The sheet one of those buttons opens, holding the form it used to draw inline.
 *
 * A sheet rather than a page of its own: these forms act on the holding open
 * behind them and hand back to it, so pushing a route would put a back arrow on
 * something that is not a place. It comes up over the editor, the editor is
 * still visible above it, and dismissing it changes nothing.
 *
 * The title is the same word as the button, because they are the same act named
 * twice and a sheet that renamed what the reader just tapped would read as
 * somewhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingActionSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The keyboard is the whole point of these sheets — every one
                // of them opens on an amount box — so the content is scrolled
                // and inset above it. `imePadding` before `verticalScroll`, as
                // everywhere else in this file: the other order pads the
                // scrolling content rather than the viewport, and the field
                // being typed into stays under the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = title,
                style = TitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            content()
            // Clear of the gesture bar, and of the sheet's own bottom edge: the
            // last thing in here is a button, and one sitting on the rim reads
            // as cut off.
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** How far the balance card's contents are held off its edges. */
private val STATEMENT_INSET = 20.dp

/**
 * Where the account stands, and the way into how it got there.
 *
 * The balance elsewhere in the app is a total; the statement is the working, and
 * without it an account that reads less than expected has nothing behind it to
 * check — the figure is simply wrong and there is nowhere to look. A back-dated
 * loan charge made that concrete: रू 10,984.93 left an account holding रू 10,000 and
 * the only evidence was the minus sign.
 *
 * **The rows themselves are a page of their own** ([AccountStatementScreen]),
 * reached from the footer here exactly as a debt's payments are reached from its
 * own card. They were drawn inline, behind a toggle, and three things were wrong
 * with that: this editor is one long scrolling `Column`, so the list is not lazy
 * and every row of a decade-old salary account composed the moment the toggle was
 * tapped; opening it pushed the colour picker and Save a screen and a half down a
 * form that is already long; and the running balance — the column somebody opens
 * it for — was read through a viewport a third of a page tall.
 */
@Composable
private fun StatementCard(state: HoldingEditorState, onOpenStatement: () -> Unit) {
    Surface(
        // The corner and the inset every other card in the app is cut to. This
        // one had 16 and 18 of its own, which put its heading four points in
        // from the heading of the card directly above it — close enough to read
        // as a mistake rather than as a different kind of card.
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // No bottom padding: the footer supplies its own, so the rule sits the
        // same distance from the figures above it as the words below it sit from
        // the card's edge. See [CardFooterAction].
        Column(modifier = Modifier.padding(cardWithFooter(STATEMENT_INSET))) {
            CardEyebrow(
                text = stringResource(R.string.statement_title),
                // The mark the rows behind this card wear, and the one on the
                // page the footer below opens.
                icon = Icons.Outlined.Receipt,
            )
            // The figure, unless the card above is already leading with it. A
            // goal's progress card opens with what has been put aside, which is
            // this same balance under another name, and stating it twice in two
            // headlines invited the reader to look for the difference.
            if (!state.isGoal) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.balanceNow.orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            // What the bank has added to it, said once here rather than in a card
            // of its own. The quarters themselves are rows on the page the footer
            // opens — they are entries like any other — and two cards showing the
            // same rows was the same answer twice.
            state.interestTotal?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.interest_paid_line, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.movementCount == 0) {
                // Nothing to open, so nothing is offered: a way into an empty
                // page reads as the app having lost the movements. The card
                // supplies the bottom padding the footer would have.
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.statement_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(STATEMENT_INSET))
                return@Column
            }
            CardFooterAction(
                text = stringResource(R.string.statement_every),
                onClick = onOpenStatement,
            )
        }
    }
}

/**
 * One line of a statement: what happened, when, and what the balance stood at
 * afterwards.
 *
 * Pulled out of the list so the swipeable and the fixed row are the same drawing
 * rather than two copies of it — the one row on the statement that cannot be
 * swiped is a loan's instalment, and it must not look like a different kind of
 * row for it. It lives here, beside [StatementRow], rather than on
 * [AccountStatementScreen] which is now the only thing that draws it: the row
 * and the shape it is built from belong together, and the screen the list is
 * drawn on has moved once already.
 */
@Composable
internal fun StatementRowView(
    row: StatementRow,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dates = LocalDateDisplay.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // Not every row leads anywhere: interest the app credited to this
            // very account would open the page it was tapped on. Those stay
            // untappable rather than being given a destination that goes
            // nowhere — a tap that does nothing reads as the app not working.
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            // Inset inside the row rather than by the paper, so the band behind
            // it runs to both edges of the sheet — and the same inset a debt's
            // ledger uses, which is the same list one screen away.
            .padding(horizontal = LIST_PANEL_ROW_INSET, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RouteText(
                text = entryTitle(row.entry),
                style = MaterialTheme.typography.bodyMedium,
            )
            // What a payment against a debt did to it, and what the user wrote
            // about it — "Borrowed more - car repair". The title of such a row
            // is the debt itself, so without this line an account's statement
            // listed three payments to the same person and said nothing about
            // any of them but the amount. Only where there is something to say:
            // an ordinary movement is already named by its own note.
            loanMovementLabel(row.entry)?.let {
                RouteText(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = dates.full(row.on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = (if (row.isIn) "+" else "−") + row.amount,
                style = MaterialTheme.typography.bodyMedium,
                // The app's own two directions, not this screen's guess at them:
                // money in was drawn in the *primary* blue here and money out in
                // plain ink, so the one list that exists to be read down a column
                // was the one list not using the green and the red every other
                // list uses.
                color = if (row.isIn) {
                    WalletTheme.colors.moneyIn
                } else {
                    WalletTheme.colors.moneyOut
                },
            )
            // What it left behind. The point of the whole list.
            Text(
                text = row.balanceAfter,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Something the form states rather than asks.
 *
 * The same idea as the kind chips turning into plain text once a holding
 * exists: what a loan was taken at is a fact about the past, and a box you can
 * type in says the opposite. Changing the rate or the term of a running loan
 * does not renegotiate it with the bank — it silently rewrites the arithmetic
 * behind every payment already made — so those answers are given once and shown
 * afterwards. What can still be changed still has a field.
 */
@Composable
private fun SettledField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A settled fact, drawn to match the fields it sits above.
 *
 * Prose under a small label reads best on its own — a name, an amount — but a
 * form is a column of boxes, and a bare line of text at the top of one sits
 * outside that column: its label starts at the page margin while every field
 * below starts inside a border, so the two never line up.
 *
 * **Every holding takes the box, not only a bank's.** It was conditional on the
 * tabs being drawn, which meant a wallet, a cash tin and money with a person all
 * opened on two lines of loose prose above a column of fields — and a savings
 * account at a bank with nothing else at it had the same, so the same form
 * looked like two different forms depending on what else the user happened to
 * bank there. The box is what says "this is a field, and it is settled"; the
 * question of whether there are sibling holdings has nothing to do with it.
 */
@Composable
private fun SettledPair(
    label: String,
    value: String,
    boxed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (boxed) {
        SettledBox(label = label, value = value, modifier = modifier)
    } else {
        SettledField(label = label, value = value, modifier = modifier)
    }
}

/**
 * How often it is paid, as the box reads it back: "1 Months", or "At the end"
 * where the whole debt falls on one day.
 */
@Composable
private fun HoldingEditorState.payEverySettled(): String =
    if (paysAtEnd) {
        stringResource(R.string.loan_pay_at_end)
    } else {
        listOf(payEveryText, stringResource(payEveryUnit.labelRes())).joinToString(" ")
    }

/**
 * The agreed length as one phrase — "7 Years" — or null where there is none.
 *
 * Read from the same two fields the boxes further down use, so a deposit's term
 * and a loan's are said the same way whichever form is open.
 */
@Composable
private fun HoldingEditorState.termSettled(): String? =
    termText.trim().takeIf { it.isNotEmpty() }?.let { figure ->
        val unit = stringResource(
            if (termInYears) R.string.loan_term_years else R.string.loan_term_months
        )
        "$figure $unit"
    }

/**
 * The three answers to how often it is paid: months, years, or all of it at the
 * end.
 *
 * Offered on the same rule as the length's own two — only while the box beside
 * them is being answered — which is possible because the third chip no longer
 * takes that box away: it turns it read-only instead, so it is still there to be
 * tapped and bring these back.
 */
@Composable
private fun PayEveryChips(
    selected: PayEvery,
    onPick: (PayEvery) -> Unit,
    modifier: Modifier = Modifier,
) {
    Reveal {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = modifier,
        ) {
            PayEvery.entries.forEach { option ->
                FilterChip(
                    colors = pickableChipColors(),
                    selected = selected == option,
                    onClick = { onPick(option) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
    }
}

/**
 * A settled answer: a fact this form is *stating*, not a field it is asking.
 *
 * It was a disabled `OutlinedTextField` — the value at full contrast inside a
 * greyed frame — and the trouble with that is what a text field's frame says.
 * The notched border with the label cut into it is the single strongest "type
 * here" signal the whole Material set has, so a form that had settled down to
 * one live box among nine settled ones still looked like nine boxes to fill in,
 * and the one worth touching was the hardest of the ten to find. Greying it only
 * made that worse: it read as a form that had broken rather than as one that had
 * been answered.
 *
 * So it is drawn by hand, exactly as [com.mywallet.ui.components.ChoicePicker]
 * is and for the same reason — nothing is ever typed into it, and a real text
 * field cannot stop looking like one. What is left is a rule down the left edge,
 * the label above and the value under it: the shape a quotation takes, which is
 * what a settled fact is.
 *
 * **Still not filled.** Fill means "write here" everywhere else in this app, and
 * a filled settled box is the inversion this replaced, one step further on.
 *
 * The eye can still hunt for it — that is what the rule is for, and it is the
 * whole of what the box was doing for a date or a length. The value wraps rather
 * than truncating: half a phone's width does not hold "A person · I borrowed",
 * and nothing here can be tapped to see the rest.
 */
/**
 * Half the height of a floating label, which is how far an `OutlinedTextField`
 * sits below the top of the space it is given.
 *
 * Material notches the label *into* the top border, so the field reserves half a
 * line above that border for it and its visible box starts there — not at the
 * composable's own top edge. A [SettledBox] told to fill the same row would
 * otherwise start its rule half a line high, which is the misalignment this pair
 * exists to remove rather than move. Taken from the label's own line height so
 * it follows the type scale rather than a number measured off one screenshot.
 */
private val FIELD_LABEL_OVERHANG: Dp
    @Composable get() = with(LocalDensity.current) {
        (MaterialTheme.typography.bodySmall.lineHeight.toDp()) / 2
    }

/** Lines a settled answer up with the outlined field sharing its row. */
@Composable
private fun Modifier.besideField(): Modifier =
    this.fillMaxHeight().padding(top = FIELD_LABEL_OVERHANG)

@Composable
private fun SettledBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    matchesFieldBeside: Boolean = false,
) {
    Row(
        // Measured to its own content, so the rule is exactly as tall as the
        // lines beside it however many they wrap to — *except* when it shares a
        // line with a live field, where content height is exactly the wrong
        // answer: an `OutlinedTextField` stands at its own 56dp minimum however
        // little is in it, so a rule measured to two short lines ended a third
        // of a box short of the border beside it and the settled half of the row
        // read as having floated upwards. There the caller imposes the row's
        // height instead and the rule spans the box, which is what makes the two
        // halves read as one line.
        modifier = if (matchesFieldBeside) modifier else modifier.height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                // At full contrast: a figure nobody can read is worse than one
                // nobody can edit.
                color = MaterialTheme.colorScheme.onSurface,
            )
            supporting?.let {
                Spacer(Modifier.height(2.dp))
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
 * The name of whatever is being created, as a plain box.
 *
 * Pulled out so the bank's version of it — which is this field with suggestions
 * hanging off it, see [BankNameField] — is the same drawing rather than a second
 * copy that can drift from it.
 */
@Composable
private fun NameField(
    state: HoldingEditorState,
    viewModel: HoldingEditorViewModel,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        colors = editableFieldColors(),
        value = state.name,
        onValueChange = viewModel::setName,
        label = { Text(stringResource(state.choice.nameLabelRes())) },
        placeholder = state.namePlaceholder()?.let { hint -> { Text(hint) } },
        singleLine = true,
        isError = state.nameError != null,
        supportingText = state.nameError?.let { { Text(stringResource(it)) } },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The bank's name, with the banks already on file offered under it as it is typed.
 *
 * A savings account, a term loan and an overdraft at the same bank are three rows
 * sharing one name, and typing it out three times is both tedious and how "Nabil
 * Bank" and "Nabil bank" end up as two banks in every list.
 *
 * This used to be a row of chips *above* the field, which is the answers before
 * the question: it took a line of the form whether or not the user wanted it, and
 * on a phone with several banks it wrapped to three, pushing the box it was there
 * to help off the screen. Under the field, on focus, is where a suggestion
 * belongs — it costs nothing until the field is being filled in, it narrows to
 * what has been typed, and it is simply absent once nothing matches.
 *
 * A name that has been *reached* is no longer a suggestion, so an exact match is
 * dropped from the list: a list sitting over the keyboard with one row in it
 * saying what the box already says is in the way of the next field.
 *
 * It is part of the form and deliberately **not** a menu floating over it. As a
 * popup it was silenced by the second tap on the box — which is what anybody does
 * once the keyboard has slid up under their thumb: a popup counts a touch on its
 * own anchor as a dismissal, focus never leaves, and nothing puts the list back
 * but typing another character. Neither way of hearing about that tap works
 * either, because an editable anchor reports nothing when it is tapped: the whole
 * point of the anchor type is that a tap on it asks for the keyboard rather than
 * for the list. So the suggestions were simply never seen again, on a phone where
 * a second tap is ordinary and an emulator driven one tap at a time where it is
 * not. Drawn in the column, there is no popup to dismiss and nothing to latch:
 * the list is on screen exactly while the field has focus and has something to
 * offer.
 */
@Composable
private fun BankNameField(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    var focused by remember { mutableStateOf(false) }

    val typed = state.name.trim()
    val known = state.nameSuggestions
    val matches = remember(known, typed) {
        if (typed.isEmpty()) {
            known
        } else {
            known.filter {
                it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true)
            }
        }
            // Somebody who banks with a dozen of them would otherwise push the
            // rest of the form off the screen to be told what they already know;
            // what they want is a few more letters typed, which narrows it.
            .take(MAX_BANK_SUGGESTIONS)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(state.choice.nameLabelRes())) },
            placeholder = state.namePlaceholder()?.let { hint -> { Text(hint) } },
            singleLine = true,
            isError = state.nameError != null,
            supportingText = state.nameError?.let { { Text(stringResource(it)) } },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
        if (focused && matches.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    matches.forEach { bank ->
                        Text(
                            text = bank,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Nothing closes it by hand: picking makes the
                                // typed name an exact match, the list empties,
                                // and it goes with it.
                                .clickable { viewModel.pickBank(bank) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How many banks the field offers before asking for another letter instead. */
private const val MAX_BANK_SUGGESTIONS = 5

/**
 * How many known names fit in the empty box before it is a wall rather than a
 * reminder. Fewer than the list below it offers on purpose: this one is a single
 * line that ellipsises, and three is about what survives on a narrow phone.
 */
private const val MAX_NAME_PLACEHOLDERS = 3

/** A settled date, drawn in whichever calendar the user reads. */
@Composable
private fun SettledDate(label: String, date: LocalDate?, modifier: Modifier = Modifier) {
    val dates = LocalDateDisplay.current
    SettledBox(
        label = label,
        value = date?.let { dates.full(it) } ?: stringResource(R.string.loan_no_date),
        // The other calendar underneath, as the picker showed it. Years
        // included: "१२ साउन" happens every year and a loan's is rarely this one.
        supporting = date?.let { dates.secondary(it) },
        modifier = modifier,
    )
}

/**
 * The line that opens and closes the schedule, drawn as the control it is.
 *
 * **Spaced exactly as [CardFooterAction] is**, and it owns the whole rhythm
 * rather than leaving the gap above the rule to its callers: one [FOOTER_GAP]
 * above, one below, one under the words. They are the same offer at the foot of
 * the same kind of card — a policy's premiums, a deposit's months, a loan being
 * written down — and one drawn at eight points and the other at fourteen read as
 * two different ideas on two screens a tap apart.
 */
@Composable
private fun ScheduleToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    /** What it opens — a loan's remaining instalments, or a deposit's months. */
    title: String? = null,
) {
    Spacer(Modifier.height(FOOTER_GAP))
    Hairline()
    // No second gap here. The row's own top padding is the gap — a spacer as
    // well doubled it, so the words sat twice as far below the rule as the ones
    // in a card's footer two screens away.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = FOOTER_GAP),
    ) {
        Text(
            text = title ?: stringResource(R.string.loan_schedule_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) {
                Icons.Outlined.ExpandLess
            } else {
                Icons.Outlined.ExpandMore
            },
            // The label beside it already says what this opens; announcing it
            // twice is noise to a screen reader and nothing to anyone else.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Every remaining instalment, as a table.
 *
 * The point of it is the shape rather than any single row: the interest slice
 * falling and the balance following it down is the whole reason a loan costs
 * what it does, and none of that is visible from one instalment figure. Columns
 * are what make a shape legible — three stacked sentences per payment, which is
 * what this was, is a list of facts you have to hold in your head to compare.
 *
 * Dated, because "payment 34" means nothing and "Mangsir 2085" means a great
 * deal; and with the year, because a five-year schedule passes "20 Saun" five
 * times. Numbered only where there is no date to use instead.
 *
 * The figures drop their currency symbol and state it once above the table.
 * Four columns of "रू 92,041.74" do not fit a phone in either script, and the
 * digits are what the table is for.
 */
@Composable
private fun ScheduleTable(
    rows: List<ScheduleRow>,
    /** Every payment handed over since the debt began — see [paymentsSoFar]. */
    paymentsMade: Int,
    currencySymbol: String,
) {
    val dates = LocalDateDisplay.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // See [PremiumTable]: the card gave its bottom padding to the toggle.
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)) {
        // What has already been handed over, so a loan reopened halfway through
        // says where in it the table starts. Every payment counts — the
        // instalments, the lump sums, and the charge for the broken first
        // period — and not just the instalments against the current balance,
        // which is what made this read "1" on a debt with a year behind it.
        if (paymentsMade > 0) {
            Text(
                text = stringResource(R.string.loan_schedule_made, paymentsMade),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            HeaderCell(stringResource(R.string.loan_schedule_col_date), DATE_WEIGHT, false)
            HeaderCell(stringResource(R.string.loan_schedule_col_payment), FIGURE_WEIGHT, true)
            HeaderCell(stringResource(R.string.loan_schedule_col_interest), FIGURE_WEIGHT, true)
            HeaderCell(stringResource(R.string.loan_schedule_col_left), LEFT_WEIGHT, true)
        }
        Spacer(Modifier.height(6.dp))
        Hairline()
        rows.forEachIndexed { index, row ->
            Row(
                // Banded like the same table on its own page — see [rowStripe].
                // A schedule is the case the band was made for: a screenful of
                // near-identical rows of digits where losing your place by one
                // line means reading the wrong month's balance. The band is an
                // alpha, so on this card it comes out as a whisper off the
                // card's white rather than as the tint it is over the page.
                // [cardBleed] is what carries it to the card's own edges and
                // pads the figures back under their headings.
                modifier = Modifier
                    .fillMaxWidth()
                    .cardBleed()
                    .background(rowStripe(index))
                    .padding(horizontal = CARD_INSET, vertical = 7.dp),
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
            }
        }
    }
}

// The date needs the most room in either script; what is left owing is the
// widest figure, because it is the only one that can still be six digits long.
private const val DATE_WEIGHT = 1.25f
private const val FIGURE_WEIGHT = 0.95f
private const val LEFT_WEIGHT = 1.1f

/**
 * The line of small capitals every figure card on this screen opens with, and
 * the mark of what the card is about.
 *
 * They were seven hand-written copies of one `Text` — same style, same colour,
 * same `.uppercase()` — on seven cards that are otherwise identical in shape:
 * eyebrow, one headline figure, a stack of quiet lines. In the light scheme a
 * card is white on almost-white with no border and no elevation, so the only
 * thing distinguishing one from another was the words, and the words are the
 * part you have to already be reading. The glyph is what says which card this is
 * before it is read.
 *
 * The marks are the ones those same arrangements wear on the Accounts tab — a
 * goal's piggy bank, a policy's shield — so a card and the row it was opened
 * from are recognisably about the same thing.
 */
@Composable
private fun CardEyebrow(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

/**
 * Where the debt stands: what goes back in the end, what is left of it, and what
 * the payments so far have achieved.
 *
 * One card, because on money between people it was two saying the same thing
 * twice — "you pay back in total" directly above "what you owe", with the same
 * figure in both whenever nothing had been repaid yet. The agreed total leads
 * where there is one, since that is the figure the two people shook on; what is
 * still owed is the line underneath, because it is the one that moves.
 */
@Composable
private fun OwedCard(
    state: HoldingEditorState,
    outstanding: String,
    onOpenLedger: (() -> Unit)?,
) {
    val dates = LocalDateDisplay.current
    // Only ever both when a rate and a length were agreed. A bare IOU has no
    // total to lead with, and then this card is exactly what it always was.
    val total = state.totalToRepay?.takeIf { state.paysInOneGo && state.interestToRepay != null }
    // The footer supplies the bottom padding when it is there, so the rule sits
    // the same distance from the figures above it as the words below it sit from
    // the card's edge. Nothing to open, nothing to give up.
    val footer = onOpenLedger != null && state.hasMovements

    WalletCard(contentPadding = if (footer) cardWithFooter(20.dp) else PaddingValues(20.dp)) {
        CardEyebrow(
            text = stringResource(
                when {
                    total != null && state.isLent -> R.string.loan_one_go_back
                    total != null -> R.string.loan_one_go_total
                    state.isOverdraft -> R.string.loan_drawn_title
                    state.isLent -> R.string.loan_owed_to_you_title
                    else -> R.string.loan_owed_title
                }
            ),
            // Money coming back is the app's own in-arrow; everything else on
            // this card is a debt being paid down.
            icon = if (state.isLent) {
                Icons.AutoMirrored.Outlined.CallReceived
            } else {
                Icons.Outlined.Payments
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(text = total ?: outstanding, style = MaterialTheme.typography.headlineSmall)
        if (total != null) {
            state.interestToRepay?.let {
                Text(
                    text = stringResource(R.string.loan_one_go_interest, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.oneGoDueOn?.let { due ->
                Text(
                    text = stringResource(
                        R.string.loan_one_go_by,
                        listOfNotNull(dates.full(due), dates.secondary(due)).joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // The total above is what it comes to on the day it is due; this is
            // what is left of it today, and the two are different numbers the
            // moment anything has been paid.
            Text(
                text = stringResource(
                    if (state.isLent) R.string.loan_owed_line_lent else R.string.loan_owed_line,
                    outstanding,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // The headroom, which is the number that decides whether the next
        // withdrawal is possible at all.
        state.available?.let {
            Text(
                text = stringResource(R.string.loan_available, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // What the money has cost so far — the same job the EMI split does on a
        // term loan, for the debts that have no instalment to carry it. Metered
        // day by day, so it is stated as an estimate rather than as anyone's
        // own figure.
        //
        // Never beside the agreed total, which is the same cost answered the
        // other way: a length turns the debt into simple interest over that
        // length, and the meter stops. The two only overlap for as long as it
        // takes to type a length in, before the save the figures come from.
        state.accruedInterest?.takeIf { total == null }?.let {
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
                // Interest owed is a cost; interest coming to you is not.
                color = if (state.isLent) {
                    WalletTheme.colors.moneyIn
                } else {
                    WalletTheme.colors.debt
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // The balance and the interest added up, which is the question a debt
        // with no end date is really carrying: what would it take to be done
        // with it? Said as one figure because nobody adds two up in their head
        // while looking at the person they owe.
        state.settleToday?.takeIf { total == null }?.let {
            Text(
                text = stringResource(R.string.loan_settle_today, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // How much of the debt itself has gone. The instalments paid do not
        // answer it — most of an early one is interest — and it is the first
        // thing a user wants to know when they open a loan they have been
        // paying for a while.
        state.principalCleared?.let {
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_principal_cleared_lent
                    } else {
                        R.string.loan_principal_cleared
                    },
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WalletTheme.colors.moneyIn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.principalPaidOutright?.let {
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_paid_principal_outright_lent
                    } else {
                        R.string.loan_paid_principal_outright
                    },
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        state.interestPaidOutright?.let {
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_paid_interest_outright_lent
                    } else {
                        R.string.loan_paid_interest_outright
                    },
                    it,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // The totals above say where the debt stands; this says how it got
        // there, one dated payment at a time. It sits under them because that is
        // the order the question arrives in — "how much?" first, "since when?"
        // the moment the figure is not the one the other person remembers.
        //
        // Withheld until something has actually happened. A debt between people
        // is entered long before the first repayment, and a button that opens an
        // empty page reads as the app having lost the payments.
        onOpenLedger?.takeIf { state.hasMovements }?.let { open ->
            // The same footer a holding's card carries, drawn by the same code:
            // two cards, one offer, and the spacing symmetric about the rule.
            // See [CardFooterAction].
            CardFooterAction(
                text = stringResource(R.string.loan_ledger_open),
                onClick = open,
            )
        }
    }
}

/**
 * Which account this one movement goes through, asked where it happens.
 *
 * It reads as the answer it already has — "From  Nabil" — and opens the choice
 * only when tapped, because nine times out of ten the default is right and a row
 * of chips in every card would be four lines of form for a question nobody
 * wanted to reopen.
 *
 * **It changes nothing that has already happened.** Nothing here is written back
 * to the loan: a debt usually repaid from the bank can still be paid down once
 * in cash, and saying so must not restate where the earlier payments came from.
 * The next movement starts from the loan's own account again.
 */
@Composable
private fun MovementAccount(
    label: String,
    accounts: List<Account>,
    selectedId: String?,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = accounts.firstOrNull { it.id == selectedId }

    Column {
        // A rule above it, the same one every other separation in the app uses
        // — see [CardFooterAction], whose gap this shares. What the card asks
        // above this is how much and when, which is the movement itself; which
        // account it passes through is a different question, and the last row of
        // a card runs into the one above it without a line to say so.
        Hairline()
        Spacer(Modifier.height(FOOTER_GAP))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    chosen?.let {
                        LabelDot(color = it.color, size = 8.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = chosen?.payLabel ?: stringResource(R.string.loan_account_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Icon(
                imageVector = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                // The line beside it already names the account and what it is
                // for; saying it again is noise to a screen reader.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) {
            Reveal {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilterChip(
                        colors = pickableChipColors(),
                        selected = selectedId == null,
                        onClick = { onPick(null); open = false },
                        label = { Text(stringResource(R.string.loan_account_none)) },
                    )
                    accounts.forEach { account ->
                        FilterChip(
                            colors = pickableChipColors(),
                            selected = account.id == selectedId,
                            onClick = { onPick(account.id); open = false },
                            label = { Text(account.payLabel) },
                            leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A lump sum, with both routes side by side.
 *
 * Shortening usually saves more interest; lowering the instalment frees monthly
 * cash. Neither is universally right, so the app shows the numbers and lets the
 * user decide.
 */
@Composable
private fun LumpSumCard(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    val amountGrouping = rememberAmountGrouping()
    // Neither a card nor an eyebrow: this is the body of a sheet now, whose own
    // title says which act it is and is drawn from the same string the button
    // that opened it uses.
    Column {
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.prepayText,
            onValueChange = viewModel::setPrepay,
            label = {
                Text(
                    stringResource(
                        when {
                            state.isOverdraft -> R.string.prepay_amount_overdraft
                            state.isLent -> R.string.prepay_amount_lent
                            else -> R.string.prepay_amount
                        }
                    )
                )
            },
            prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
            visualTransformation = amountGrouping,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        // What this one was about. Optional, because most payments against a
        // debt need no explaining — the debt is what they are about — but a run
        // of them down a statement is otherwise three identical rows, and the
        // one thing that tells them apart is what the two people said to each
        // other at the time. It is never the row's *title*: whose debt this is
        // stays on top and the note goes under it, on the end of what the
        // payment did. See `loanMovementLabel`.
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.prepayNote,
            onValueChange = viewModel::setPrepayNote,
            label = { Text(stringResource(R.string.loan_movement_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // When it happened, not when it was typed in. Money between people is
        // usually written down afterwards, and a payment filed under the wrong
        // day is worth nothing to either person trying to check it later.
        Spacer(Modifier.height(12.dp))
        DateField(
            // Their money arriving, not yours leaving, when the debt is one you
            // gave: the day is the same fact from opposite sides.
            label = stringResource(
                if (state.isLent) R.string.loan_paid_on_lent else R.string.loan_paid_on
            ),
            date = state.prepayDate,
            placeholder = stringResource(R.string.loan_pick_date),
            onPick = viewModel::setPrepayDate,
            // Nothing was paid back before the money changed hands, and the
            // balance a payment meets is the balance as it stood on its own day
            // — a day before the debt began has no balance to meet.
            minDate = state.movedOn,
            // No ceiling. "I am paying रू 4,30,000 off on the 30th" is a real
            // answer and the whole point of asking for a date, and the app
            // already knows what to do with a movement the user has dated
            // forward: it is written down, it is drawn in the month it falls in,
            // and nothing feels it until the day arrives — see
            // `LoanRepository.applyDuePayments`. This was capped at today
            // because the save clamped it there, which is the wrong end of the
            // problem to fix.
        )

        // And which account it moves through — a question about this payment,
        // not about the debt. Money coming back from someone you lent to arrives
        // somewhere; money paid off a debt leaves somewhere.
        // The same gap the rule inside it leaves underneath, so the line sits
        // with equal air on both sides.
        Spacer(Modifier.height(FOOTER_GAP))
        MovementAccount(
            label = stringResource(
                if (state.isLent) R.string.loan_account_lent else R.string.loan_account_borrowed
            ),
            accounts = state.accounts.payableHoldings(keep = state.prepayAccountId),
            selectedId = state.prepayAccountId,
            onPick = viewModel::setPrepayAccount,
        )

        state.prepayNewBalance?.let { balance ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.prepay_new_balance_lent
                    } else {
                        R.string.prepay_new_balance
                    },
                    balance,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            state.prepayShorterMonths?.let { months ->
                Button(
                    onClick = { viewModel.applyPrepay(keepInstalment = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(pluralStringResource(R.plurals.prepay_keep_emi, months, months))
                }
                state.prepaySavedByShortening?.let {
                    Text(
                        text = stringResource(R.string.prepay_saves, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                }
            }
            state.prepayLowerEmi?.let { emi ->
                Button(
                    onClick = { viewModel.applyPrepay(keepInstalment = false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.prepay_keep_term, emi))
                }
                state.prepaySavedByLowering?.let {
                    Text(
                        text = stringResource(R.string.prepay_saves, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // Only a level instalment offers a choice between finishing sooner
            // and paying less; the other styles have already answered it.
            if (state.prepayShorterMonths == null && state.prepayLowerEmi == null) {
                Button(
                    onClick = { viewModel.applyPrepay(keepInstalment = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.prepay_apply)) }
            }
        }
    }
}

/**
 * More money on the same arrangement.
 *
 * "I lent Sita 8,000" is rarely the end of it — another 2,000 follows, and until
 * now the only way to record that was a second loan under the same name, leaving
 * the user to add the two up themselves. What goes out is not spending and what
 * comes in is not earnings; only the debt moves, which is why this sits here
 * rather than on the money-in form.
 */
@Composable
private fun MoreCard(state: HoldingEditorState, viewModel: HoldingEditorViewModel) {
    val amountGrouping = rememberAmountGrouping()
    // A sheet's body — see [LumpSumCard].
    Column {
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.moreText,
            onValueChange = viewModel::setMore,
            label = { Text(stringResource(R.string.loan_more_amount)) },
            prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
            visualTransformation = amountGrouping,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        // What this addition was for — see the same field on [LumpSumCard].
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            colors = editableFieldColors(),
            value = state.moreNote,
            onValueChange = viewModel::setMoreNote,
            label = { Text(stringResource(R.string.loan_movement_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        DateField(
            label = stringResource(R.string.loan_more_on),
            date = state.moreDate,
            placeholder = stringResource(R.string.loan_pick_date),
            onPick = viewModel::setMoreDate,
            // More of the same arrangement cannot predate the arrangement.
            minDate = state.movedOn,
        )

        // Where it lands, or leaves from. Starts at the account the arrangement
        // already moves through rather than the one it is repaid through:
        // another रू 2,000 lent leaves the account the first रू 8,000 left.
        // The same gap the rule inside it leaves underneath, so the line sits
        // with equal air on both sides.
        Spacer(Modifier.height(FOOTER_GAP))
        MovementAccount(
            label = stringResource(
                if (state.isLent) R.string.loan_account_paid_out else R.string.loan_account_received
            ),
            accounts = state.accounts.payableHoldings(keep = state.moreAccountId),
            selectedId = state.moreAccountId,
            onPick = viewModel::setMoreAccount,
        )

        state.moreNewBalance?.let { balance ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (state.isLent) {
                        R.string.loan_more_new_balance_lent
                    } else {
                        R.string.loan_more_new_balance
                    },
                    balance,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::applyMore,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.loan_more_apply)) }
        }
    }
}

@Composable
private fun ColourPicker(selected: Color, onPick: (Color) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HoldingPalette.colors.forEach { colour ->
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(colour, CircleShape)
                    .border(
                        width = if (colour == selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    )
                    .clickable { onPick(colour) },
            )
        }
    }
}

/** "Bank · Overdraft" — what this holding is, for a row that cannot be changed. */
@Composable
private fun HoldingChoice.describe(): String = listOfNotNull(
    stringResource(group.labelRes()),
    when (group) {
        HoldingGroup.BANK -> stringResource(bank.labelRes())
        HoldingGroup.PERSON -> stringResource(person.labelRes())
        else -> null
    },
).joinToString(" · ")

/**
 * What to call the one name field.
 *
 * A bank account and a bank loan are both asking for the bank; money with a
 * person is asking for the person. Neither needs a second box afterwards
 * repeating the question in other words.
 */
private fun HoldingChoice.nameLabelRes(): Int = when (group) {
    HoldingGroup.BANK -> R.string.accounts_bank_name
    HoldingGroup.WALLET -> R.string.accounts_wallet_name
    HoldingGroup.CASH -> R.string.accounts_name
    HoldingGroup.PERSON -> R.string.loan_person_name
    HoldingGroup.INSURANCE -> R.string.insurance_policy_name
    HoldingGroup.GOAL -> R.string.goal_name
}

/**
 * What stands in the name box while it is empty, or null for nothing at all.
 *
 * It used to be an invented example per kind — "Global IME, Nabil…" over a bank,
 * "eSewa, Khalti, Wise…" over a wallet. Two things wrong with that. It printed
 * other people's brands inside the app, which is somebody else's name being used
 * to describe our form; and it answered a question the label above had already
 * asked plainly, so the reader paid a line of grey text to be told that a box
 * marked *Bank name* wants the name of a bank.
 *
 * What is worth the space is what this user has already called things. Seeing
 * "Nabil Bank" sitting in the empty box is how the second account there gets
 * spelled the same way instead of becoming a second bank — the same reason
 * [BankNameField] lists them underneath, said once more where the eye already
 * is. Only banks and wallets have such a list; a cash tin, a person, a policy or
 * a goal is one of itself, so the box is simply empty and the label carries it.
 */
private fun HoldingEditorState.namePlaceholder(): String? =
    nameSuggestions
        .take(MAX_NAME_PLACEHOLDERS)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

/**
 * The unit a gap between payments is counted in.
 *
 * The same two words the length above it uses, deliberately: they are the same
 * kind of answer, and a form that called one "Months" and the other "Monthly"
 * would be asking the reader to notice a difference that is not there.
 */
private fun PayEvery.labelRes(): Int = when (this) {
    PayEvery.MONTHS -> R.string.loan_term_months
    PayEvery.YEARS -> R.string.loan_term_years
    PayEvery.AT_END -> R.string.loan_pay_at_end
}

private fun HoldingGroup.labelRes(): Int = when (this) {
    HoldingGroup.BANK -> R.string.accounts_kind_bank
    HoldingGroup.WALLET -> R.string.accounts_kind_wallet
    HoldingGroup.CASH -> R.string.accounts_kind_cash
    HoldingGroup.PERSON -> R.string.accounts_kind_person
    HoldingGroup.INSURANCE -> R.string.accounts_kind_insurance
    HoldingGroup.GOAL -> R.string.accounts_kind_goal
}

private fun PersonHolding.labelRes(): Int = when (this) {
    PersonHolding.BORROWED -> R.string.person_borrowed
    PersonHolding.LENT -> R.string.person_lent
}

private fun BankHolding.labelRes(): Int = when (this) {
    BankHolding.SAVINGS -> R.string.accounts_kind_savings
    BankHolding.CURRENT -> R.string.accounts_kind_current
    BankHolding.FIXED_DEPOSIT -> R.string.accounts_kind_fd
    BankHolding.LOAN -> R.string.accounts_kind_loan
    BankHolding.OVERDRAFT -> R.string.accounts_kind_overdraft
}

private fun InstalmentStyle.labelRes(): Int = when (this) {
    InstalmentStyle.LEVEL_EMI -> R.string.loan_style_level
    InstalmentStyle.PRINCIPAL_ONLY -> R.string.loan_style_principal
    InstalmentStyle.INTEREST_ONLY -> R.string.loan_style_interest
}
