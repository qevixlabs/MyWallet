package com.mywallet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.inputPrefix
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.domain.Account
import com.mywallet.domain.Shortlist
import com.mywallet.ui.LocalAppSettings
import com.mywallet.ui.LocalMoneyFormatter
import com.mywallet.ui.components.ConfirmDeleteDialog
import com.mywallet.ui.components.DatePickerBox
import com.mywallet.ui.components.DefaultCalendarSwitch
import com.mywallet.ui.components.LabelDot
import com.mywallet.ui.components.LaterPaymentFirstDialog
import com.mywallet.ui.components.Reveal
import com.mywallet.ui.components.SectionHeader
import com.mywallet.ui.components.ShortlistChips
import com.mywallet.ui.components.editableFieldColors
import com.mywallet.ui.components.pickableChipColors
import com.mywallet.ui.components.rememberAmountGrouping
import com.mywallet.ui.holdingDisplayName
import com.mywallet.ui.theme.TitleStyle
import com.mywallet.ui.theme.WalletTheme

/**
 * Adding money is the one thing users do every day, so it is a single screen
 * with no wizard: direction, amount, holding, date, note — all visible at once,
 * and Save is reachable without scrolling on a normal phone.
 */
/** The mark on a direction segment, sized to sit beside its word. */
private val SEGMENT_ICON = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The bin asks first, which it did not. It removes one row on an ordinary
    // entry and a whole repeating arrangement on a rule — see
    // [AddEntryViewModel.delete] — and neither comes back from a snackbar here.
    var confirming by remember { mutableStateOf(false) }
    val money = LocalMoneyFormatter.current
    val amountGrouping = rememberAmountGrouping()
    val settings = LocalAppSettings.current
    // How many holdings each bank name covers, so a chip says which product it
    // is only where that tells two of them apart — the same rule every row in
    // the app follows. See [holdingDisplayName].
    val siblings: Map<String, Int> = remember(state.accounts) {
        state.accounts.groupingBy { bankKey(it) }.eachCount()
    }

    LaunchedEffect(state.isSaved, state.isDeleted) {
        if (state.isSaved || state.isDeleted) onDone()
    }

    if (state.deleteBlocked) {
        LaterPaymentFirstDialog(onDismiss = viewModel::dismissDeleteBlocked)
    }

    if (confirming) {
        ConfirmDeleteDialog(
            // What actually goes, said before it does. A repeating payment
            // reached from any of its dates takes all of them with it, which is
            // a great deal more than the row the user tapped.
            title = stringResource(
                if (state.hasSeries) {
                    R.string.entry_delete_repeat_title
                } else {
                    R.string.entry_delete_title
                }
            ),
            body = stringResource(
                if (state.hasSeries) {
                    R.string.entry_delete_repeat_body
                } else {
                    R.string.entry_delete_body
                }
            ),
            onConfirm = {
                confirming = false
                viewModel.delete()
            },
            onDismiss = { confirming = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (state.isEditing) R.string.add_title_edit else R.string.add_title_new
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
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = { confirming = true }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // imePadding before verticalScroll, deliberately: it shrinks the
                // scrolling viewport when the keyboard appears, so the field you
                // are typing in can scroll above it. Applied after the scroll
                // modifier it would only pad the content and leave the focused
                // field stranded underneath.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.isTransfer && state.direction == Direction.OUT,
                    onClick = {
                        viewModel.setTransferMode(false)
                        viewModel.setDirection(Direction.OUT)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    // The app's own out/in/across marks, which is what the menu
                    // that opened this form offered these three under. They take
                    // the slot Material fills with a tick when a segment is
                    // selected — a tick says only "this one", which the fill
                    // already says, where the arrow says which of the three it
                    // is whether or not it is the one chosen.
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.CallMade,
                            contentDescription = null,
                            modifier = Modifier.size(SEGMENT_ICON),
                        )
                    },
                ) { Text(stringResource(R.string.add_money_out)) }
                SegmentedButton(
                    selected = !state.isTransfer && state.direction == Direction.IN,
                    onClick = {
                        viewModel.setTransferMode(false)
                        viewModel.setDirection(Direction.IN)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.CallReceived,
                            contentDescription = null,
                            modifier = Modifier.size(SEGMENT_ICON),
                        )
                    },
                ) { Text(stringResource(R.string.add_money_in)) }
                SegmentedButton(
                    selected = state.isTransfer,
                    // Needs somewhere to move the money to. Offering the mode with
                    // one account would lead to a form that can never be saved.
                    enabled = state.canTransfer,
                    onClick = { viewModel.setTransferMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(SEGMENT_ICON),
                        )
                    },
                ) { Text(stringResource(R.string.add_direction_transfer)) }
            }
            if (!state.canTransfer) {
                Text(
                    text = stringResource(R.string.transfer_needs_accounts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // First, and no longer optional. It is what the row will be called:
            // with labels gone, nothing else on a movement tells one of a bank's
            // payments from another, and a list of "Money out · Nabil Bank" says
            // nothing the account page did not. Asked before the amount because
            // that is the order the user thinks in — what this is, then what it
            // cost.
            //
            // Withheld on the movements the app named itself — see
            // [AddEntryUiState.showsNote]. A drawdown's row already reads "Taken
            // from Dad", so an empty box demanding what it is for is asking
            // after a question already answered. It comes back the moment there
            // is something in it: a purchase on a card is called after what was
            // bought, and that is the user's own word to correct.
            if (state.showsNote) {
            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.add_note)) },
                placeholder = { Text(stringResource(R.string.add_note_hint)) },
                singleLine = true,
                isError = state.error == EntryError.NOTE,
                supportingText = if (state.error == EntryError.NOTE) {
                    { Text(stringResource(R.string.error_note_required)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            }

            OutlinedTextField(
                colors = editableFieldColors(),
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text(stringResource(R.string.add_amount)) },
                // Symbol follows the entry's own currency: typing a USD amount
                // under a "रू" prefix is exactly the confusion this is here to
                // prevent.
                prefix = { Text(CurrencyOption.byCode(state.currencyCode).inputPrefix) },
                visualTransformation = amountGrouping,
                singleLine = true,
                // Over the card's limit is a fault of the *amount*, not of the
                // card: the answer is a smaller figure, and saying so under the
                // box being typed in is saying it where the user is looking.
                isError = state.error == EntryError.AMOUNT ||
                    state.error == EntryError.OVER_LIMIT,
                supportingText = when (state.error) {
                    EntryError.AMOUNT -> {
                        { Text(stringResource(R.string.error_amount_required)) }
                    }
                    EntryError.OVER_LIMIT -> {
                        { Text(stringResource(R.string.entry_over_limit)) }
                    }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            // What the typed figure comes to in the currency the totals are in.
            // The currency itself is no longer asked: the account this money
            // leaves or lands in is denominated in one, and that is the answer —
            // a second question could only contradict the first.
            state.convertedPreview?.let { preview ->
                Text(
                    text = stringResource(R.string.add_converted, preview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The paying end, asked in two rows: the accounts money simply sits
            // in, and under them the holdings it does not — the debts and the
            // goals. One question and one answer across both, which is why
            // tapping in either row clears the other.
            if (state.showsAccountRow) {
                Column {
                    SectionHeader(
                        // Money in does not come *from* one of your own
                        // accounts, it lands in one. Asking "paid from" of an
                        // arrival was a question about the wrong end of it.
                        title = stringResource(
                            when {
                                state.isTransfer -> R.string.transfer_from
                                state.direction == Direction.IN -> R.string.add_account_in
                                else -> R.string.add_account
                            }
                        ),
                        // Which way the money runs through the account named
                        // under it. The same arrow the segment above is picked
                        // by, so the heading agrees with the mode.
                        icon = if (!state.isTransfer && state.direction == Direction.IN) {
                            Icons.AutoMirrored.Outlined.CallReceived
                        } else {
                            Icons.AutoMirrored.Outlined.CallMade
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    ShortlistChips(items = state.accountChips, shortlist = Shortlist.HOLDINGS) {
                        AccountChip(
                            account = it,
                            siblings = siblings[bankKey(it)] ?: 1,
                            selected = it.id == state.selectedAccountId,
                            onClick = { viewModel.selectAccount(it.id) },
                        )
                    }
                    // The cards, in the same row and after the accounts: a card
                    // is how the money leaves, exactly as a bank account is, and
                    // asking the user to record a purchase on the debt's own
                    // screen and then again as spending was two entries for one
                    // thing. Each says what it has left to draw, because that is
                    // the figure that decides whether this purchase is possible.
                    if (state.offersCards) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            state.cards.forEach { card ->
                                CardChip(
                                    card = card,
                                    available = card.available?.let { money.formatCompact(it) },
                                    selected = card.id == state.selectedCardId,
                                    onClick = { viewModel.selectCard(card.id) },
                                )
                            }
                        }
                    }
                }
            }

            // An existing drawdown says where the money came from instead of
            // asking again: re-answering could only double what the overdraft
            // thinks it owes.
            state.existingDrawdownName?.let { name ->
                Text(
                    text = stringResource(R.string.entry_drawdown_note, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // And a purchase already made on a card says which card, for the
            // same reason: the facility is carrying this figure in what it says
            // has been drawn, so where it was spent is a fact about the row
            // rather than a question this form can reopen. What it was for, how
            // much and when are all still the user's to correct.
            state.existingCardName?.let { name ->
                Text(
                    text = stringResource(R.string.entry_card_spend_note, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isTransfer) {
                Reveal {
                Column {
                    SectionHeader(
                        title = stringResource(R.string.transfer_to),
                        icon = Icons.AutoMirrored.Outlined.CallReceived,
                    )
                    Spacer(Modifier.height(10.dp))
                    // The source is left out: money cannot be moved to where it
                    // already is, and offering it invites an error message
                    // instead of preventing one.
                    val destinations = state.accountChips.filter { it.id != state.selectedAccountId }
                    ShortlistChips(items = destinations, shortlist = Shortlist.HOLDINGS) { account ->
                        AccountChip(
                            account = account,
                            siblings = siblings[bankKey(account)] ?: 1,
                            selected = account.id == state.toAccountId,
                            onClick = { viewModel.selectToAccount(account.id) },
                        )
                    }
                    state.transferPreview?.let { preview ->
                        Text(
                            text = stringResource(R.string.transfer_arrives, preview),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (state.error == EntryError.TRANSFER_ACCOUNTS) {
                        Text(
                            text = stringResource(R.string.transfer_same_account),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // Said where the two accounts are, because that is what the
                    // app cannot value — not the amount, which is fine.
                    if (state.error == EntryError.NO_RATE) {
                        Text(
                            text = stringResource(R.string.transfer_no_rate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                }
            }


            Column {
                SectionHeader(
                    title = stringResource(R.string.add_when),
                    icon = Icons.Outlined.CalendarMonth,
                )
                Spacer(Modifier.height(10.dp))
                // One obvious control, already answered with today. The old
                // Today/Yesterday chips beside a bare text date read as the
                // only two choices, and the date that actually opened the
                // calendar did not look tappable at all.
                DatePickerBox(
                    date = state.date,
                    placeholder = "",
                    onPick = viewModel::setDate,
                )
            }

            // A movement against a debt is a one-off act, not a standing
            // arrangement: an overdraft that refilled itself on a schedule would
            // report a debt the user never took, and a repayment that repeated
            // would pay one down every month whether or not it was paid. An
            // instalment's rhythm belongs to its loan's schedule, not to any one
            // payment, so a loan entry says so instead of offering controls that
            // would rewrite the schedule.
            if (state.isLoanInstalment) {
                Text(
                    text = stringResource(R.string.entry_loan_instalment_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.canRepeat) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.repeats, onCheckedChange = viewModel::setRepeats)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (state.isTransfer) R.string.transfer_repeat else R.string.repeat_this
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable { viewModel.setRepeats(!state.repeats) },
                    )
                }
                if (state.repeats) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecurrenceInterval.entries.forEach { interval ->
                            FilterChip(
                                colors = pickableChipColors(),
                                selected = state.interval == interval,
                                onClick = { viewModel.setInterval(interval) },
                                label = { Text(stringResource(interval.labelRes())) },
                            )
                        }
                    }
                    // Which calendar this rule's months are counted in.
                    //
                    // Asked with the switch every bank holding is asked with,
                    // and carrying the *same* sentence it always carried as its
                    // own subtext rather than a second one of its own: the line
                    // already said which calendar the rule uses, which is
                    // exactly what a reader wants to know after answering, and
                    // two explanations stacked under one control is one of them
                    // going unread.
                    //
                    // Withheld where there is nothing to ask — English being
                    // read, or a rule already running — and the sentence is
                    // then drawn on its own, because it is a fact about the
                    // rule whether or not there is a choice to make about it.
                    Spacer(Modifier.height(8.dp))
                    if (state.offersRepeatCalendar) {
                        DefaultCalendarSwitch(
                            checked = state.usesSelectedCalendar,
                            effectiveCalendarName = stringResource(
                                state.effectiveCalendarNameRes
                            ),
                            // The switch's own line, and shorter than the
                            // one drawn where there is no switch: the control
                            // above has already asked the question, so this only
                            // has to say what the answer means for what gets
                            // written down.
                            explain = R.string.repeat_calendar_explain,
                            onChange = viewModel::setUsesSelectedCalendar,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.repeat_explain,
                                stringResource(state.effectiveCalendarNameRes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.action_save))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}


/**
 * One account in a row of them, named the way every list in the app names one.
 *
 * Written once because the same chip is drawn at four places on this form, and
 * four copies of it drifting apart is how one question starts reading as two —
 * and it goes through [holdingDisplayName] for the same reason at one remove: a
 * chip that said "Demo Bank · NPR" while every row it produced said "Demo
 * Bank(Savings) - NPR" is the same holding wearing two names on two screens.
 */
@Composable
private fun AccountChip(
    account: Account,
    siblings: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        colors = pickableChipColors(),
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                holdingDisplayName(
                    account.institution,
                    account.name,
                    account.kind,
                    account.currencyCode,
                    siblings,
                ).orEmpty()
            )
        },
        leadingIcon = { LabelDot(color = account.color, size = 8.dp) },
    )
}

/** Interval names live with the UI, not the enum — the enum is storage. */
@androidx.annotation.StringRes
private fun RecurrenceInterval.labelRes(): Int = when (this) {
    RecurrenceInterval.WEEKLY -> R.string.repeat_weekly
    RecurrenceInterval.FORTNIGHTLY -> R.string.repeat_fortnightly
    RecurrenceInterval.MONTHLY -> R.string.repeat_monthly
    RecurrenceInterval.QUARTERLY -> R.string.repeat_quarterly
    RecurrenceInterval.HALF_YEARLY -> R.string.repeat_half_yearly
    RecurrenceInterval.YEARLY -> R.string.repeat_yearly
}

/**
 * One card in the row of sources: what it is called, and what it has left.
 *
 * The headroom is on the chip rather than only in the refusal, because it is the
 * figure that decides whether the purchase being typed is possible at all — and
 * a save refused after the fact tells the user something the form knew before
 * they started.
 */
@Composable
private fun CardChip(
    card: com.mywallet.domain.Loan,
    available: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        colors = pickableChipColors(),
        selected = selected,
        onClick = onClick,
        // A card is the one debt offered in this row, and it sits among the
        // accounts — so it is found the way they are found. It carries a colour
        // of its own for exactly that reason (`loan.color_argb`); where none was
        // chosen it takes the ember every debt's figure is printed in.
        leadingIcon = {
            LabelDot(color = card.color ?: WalletTheme.colors.debt, size = 8.dp)
        },
        label = {
            Text(
                available
                    ?.let { stringResource(R.string.entry_card_available, card.name, it) }
                    ?: card.name
            )
        },
    )
}

/**
 * The bank a holding is filed under, lowercased — the key the accounts list
 * groups by, and the one that decides whether a name needs its kind beside it.
 */
private fun bankKey(account: Account): String =
    (account.institution ?: account.name).lowercase()
