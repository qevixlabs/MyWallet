package com.mywallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mywallet.R
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import com.mywallet.domain.Loan
import com.mywallet.domain.MoneyEntry
import com.mywallet.ui.components.ROUTE_ARROW

/**
 * What to call a holding's type on a row.
 *
 * One bank holds several things under one name — a savings account, a term loan,
 * an overdraft — so every list that shows the name shows this underneath it.
 * Shared rather than written out per screen, because three screens quietly
 * disagreeing about what an overdraft is called is exactly how a list stops
 * reading as one list.
 */
fun AccountKind.labelRes(): Int = when (this) {
    AccountKind.SAVINGS -> R.string.accounts_kind_savings
    AccountKind.CURRENT -> R.string.accounts_kind_current
    AccountKind.FIXED_DEPOSIT -> R.string.accounts_kind_fd
    AccountKind.INSURANCE -> R.string.accounts_kind_insurance
    AccountKind.GOAL -> R.string.accounts_kind_goal
    AccountKind.WALLET -> R.string.accounts_kind_wallet
    AccountKind.CASH -> R.string.accounts_kind_cash
}


/**
 * The same for a debt.
 *
 * Money with a person is named for the person, not for which way it runs: the
 * section it sits in already says whether it is owed or owing, and "I lent"
 * under someone's name would say it a second time.
 */
fun LoanKind.labelRes(): Int = when (this) {
    LoanKind.BANK -> R.string.accounts_kind_loan
    LoanKind.OVERDRAFT -> R.string.accounts_kind_overdraft
    LoanKind.PERSONAL -> R.string.accounts_kind_person
}

fun Loan.kindLabelRes(): Int = kind.labelRes()

/**
 * What to call one holding among several at the same bank.
 *
 * The bank's name is already the heading above it, so what is left is either
 * the name the user gave this one — "Dollar Account" — or, when they gave none,
 * simply what kind it is.
 *
 * Never both. A user who names an account has said what to call it, and
 * "Dollar Account Savings" answers a question they already answered: it reads
 * as the app correcting them. What kind it is goes in the line underneath,
 * where the currency and the rest of the row's facts are.
 */
@Composable
fun holdingLabel(ownName: String?, kindRes: Int, currencyCode: String? = null): String {
    val named = ownName?.trim()?.takeIf { it.isNotEmpty() }
    if (named != null) return named
    val kind = stringResource(kindRes)
    // In brackets, because it is a note about the holding rather than part of
    // what it is called — the same way `holdingDisplayName` writes it, so one
    // currency does not appear two ways on two screens. Passed only where two
    // of a bank's holdings would otherwise wear the same word; see
    // [HoldingTab.currencyCode], which is null in every other case.
    return if (currencyCode == null) kind else "$kind ($currencyCode)"
}

/**
 * "Demo Bank Savings (NPR)" — which holding a movement passed through, said the
 * same way everywhere a holding is named.
 *
 * Three parts, and each earns its place:
 *
 *  - **What to call it.** The name the user gave *this* holding where there is
 *    one, and the bank's where there is not. Never both: somebody who names an
 *    account has said what to call it, and "Dollar Account - Global IME Bank"
 *    spends the width of the row saying it twice. Wallets, cash tins and money
 *    with a person have only ever had the one name, so nothing changes for them.
 *  - **Which of the bank's products, and only where that tells two things
 *    apart.** One bank name can cover a savings account, a deposit and a current
 *    account, and then the row has to say which. Where it covers exactly one it
 *    says nothing: "Demo Bank Savings (USD)" on somebody's only account at Demo
 *    Bank is a word that distinguishes it from nothing. Never on a wallet, a
 *    cash tin or a person either — each is the only one of itself — and never
 *    beside a name the user chose, which has already answered the question.
 *  - **What it is denominated in.** The one fact that decides whether two
 *    figures on a page may be added up, and the one a reader of a mixed-currency
 *    list has to hunt for otherwise. In brackets, because it is a note about the
 *    holding rather than part of what it is called.
 *
 * Where the money came from or landed is half of what a row means, and it used
 * to be on the projection of a payment but not on the payment itself: the same
 * bill said "Bills · Nabil Bank Savings" while it was still due and only "Bills"
 * once it had been paid.
 *
 * @param siblings how many live holdings sit under the same bank name. Null
 *   reads as "unknown", which is treated as one — a name is never qualified on
 *   a guess.
 */
@Composable
fun holdingDisplayName(
    institution: String?,
    name: String?,
    kind: AccountKind?,
    currencyCode: String?,
    siblings: Int? = null,
): String? {
    val bank = institution?.trim()?.takeIf { it.isNotEmpty() }
    val own = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(bank, ignoreCase = true) }
    val called = own ?: bank ?: return null
    val product = kind
        ?.takeIf { it.isBankProduct && own == null && (siblings ?: 1) > 1 }
        ?.let { " " + stringResource(it.labelRes()) }
    val currency = currencyCode?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" }
    return called + product.orEmpty() + currency.orEmpty()
}

/**
 * Whether this kind is one of several things a bank holds under one name.
 *
 * A policy and a goal are at no bank — their name is the arrangement's own — and
 * a wallet and a cash tin are one thing each, so none of them is told apart by
 * naming its kind on every row.
 */
val AccountKind.isBankProduct: Boolean
    get() = this == AccountKind.SAVINGS ||
        this == AccountKind.CURRENT ||
        this == AccountKind.FIXED_DEPOSIT

/**
 * The debt a payment settles, named so it says something [accountLabel] does
 * not.
 *
 * A term loan at the bank the money leaves is just "Loan": the account beside
 * it already carries the name, and "Nabil Bank Savings · Nabil Bank Loan" names
 * one bank twice while distinguishing nothing. A loan at *another* bank keeps
 * its name, because that is the whole difference between the two rows. Money
 * with a person is always the person's name — "A person" identifies nobody.
 *
 * Shared between projected instalments and the real rows they become, so the
 * day a payment comes due does not change what its row calls it.
 */
@Composable
fun loanRowLabel(loanName: String?, loanKind: LoanKind?, accountLabel: String?): String? {
    val name = loanName ?: return null
    val kind = loanKind ?: return null
    if (kind == LoanKind.PERSONAL) return name
    val kindLabel = stringResource(kind.labelRes())
    if (accountLabel?.contains(name, ignoreCase = true) == true) return kindLabel
    // A loan the user called "Test loan" does not become "Test loan Loan". The
    // kind is only worth adding when the name has not already said it.
    if (name.contains(kindLabel, ignoreCase = true)) return name
    return "$name $kindLabel"
}

/**
 * "Wise → Nabil Bank", or null when this is not a transfer at all.
 *
 * Both halves of a transfer produce the same string, so the pair reads as one
 * movement however the list happens to order them.
 *
 * One end may be unknown, and the arrow then says which. A premium or a
 * contribution dated before the app was told about the policy or goal names no
 * account on the paying side — that money left a balance the user has since
 * corrected by hand — so the row reads "→ Nepal Life Endowment". It used to fall
 * back to the bare word "Transfer", which is the one thing a row must never say:
 * every transfer says that, so it distinguishes nothing, and a premium the user
 * went looking for was indistinguishable from anything else that had moved.
 */
fun MoneyEntry.transferRoute(): String? {
    if (!isTransfer) return null
    val from = transferFromName
    val to = transferToName
    return when {
        from != null && to != null -> "$from $ROUTE_ARROW $to"
        to != null -> "$ROUTE_ARROW $to"
        from != null -> "$from $ROUTE_ARROW"
        else -> null
    }
}

/**
 * Both ends of a drawdown: the facility the money came out of, then the account
 * it landed in — unless that account is at the same bank, where repeating the
 * name would distinguish nothing.
 */
@Composable
fun MoneyEntry.overdraftRoute(): String? {
    if (!isOverdraftDraw) return null
    val from = loanRowLabel(loanName, loanKind, null)
    val to = accountName?.takeIf { !it.equals(loanName, ignoreCase = true) }
    return listOfNotNull(from, to).joinToString(" $ROUTE_ARROW ").takeIf { it.isNotEmpty() }
}

/**
 * A line of the app's own words with whatever the user wrote about this movement
 * after it — "Borrowed more - car repair".
 *
 * The app's word leads because it is the one that is always there and always
 * means the same thing; the note is what tells one of a run of them from the
 * next. Joined by a hyphen rather than the interpunct the rest of a subtext uses,
 * so the two read as one phrase and its qualifier rather than as two more facts
 * in the list beside the account and the debt.
 */
@Composable
fun String.withNote(note: String?): String {
    val own = note?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    return stringResource(R.string.loan_movement_with_note, this, own)
}

/**
 * What a movement did to the debt it belongs to, in the same words the debt's own
 * statement uses — with the user's note on it.
 *
 * Shared rather than written out per screen for the reason [entryTitle] is: a
 * payment named one thing on the timeline and another on the statement of the
 * account it passed through reads as two different payments. Which way round it
 * is comes from the *direction* and only from there — money arriving is either
 * borrowing more or being repaid, money leaving is either lending more or paying
 * — since the loan's own direction would say the same thing twice.
 *
 * Null where there is nothing to say: an instalment (its own rule already names
 * it), a drawdown (drawn as a route, both ends named), and anything that is not
 * about a debt at all.
 */
@Composable
fun loanMovementLabel(entry: MoneyEntry): String? {
    val isIn = entry.direction == Direction.IN
    val action = when {
        entry.isOverdraftDraw -> null
        // Before the increase below, which it also is: the debt arriving is the
        // one addition that is not the debt growing.
        entry.isLoanOpening -> stringResource(
            if (isIn) R.string.loan_movement_borrowed else R.string.loan_movement_lent
        )
        entry.isLoanIncrease -> stringResource(
            if (isIn) R.string.loan_movement_borrowed_more else R.string.loan_movement_lent_more
        )
        // Called what the button that recorded it is called, on every debt and
        // in both directions. It read "Off the balance" and "Off what they owe":
        // words that named the act in terms nobody had seen on the screen they
        // made it on, where the card, its title and its button all say Payment.
        entry.loanPart == LoanPart.PRINCIPAL -> stringResource(R.string.prepay_title)
        entry.loanPart == LoanPart.INTEREST -> stringResource(
            if (isIn) R.string.loan_movement_interest_lent else R.string.loan_movement_interest
        )
        else -> null
    } ?: return null
    return action.withNote(entry.ownNote)
}

/**
 * What a movement calls itself at the head of a row.
 *
 * What the user wrote leads, then what the row *is*: an instalment, the two ends
 * of a transfer, the person a debt is with. A row with none of those and no note
 * says "Money out" or "Money in", which is the plainest true thing left to say
 * about it — never a complaint that the user omitted something.
 *
 * Shared with [com.mywallet.ui.screens.EntryRow] rather than written twice,
 * because a movement named one thing on the timeline and another on the
 * statement of the account it passed through reads as two different payments.
 */
@Composable
fun entryTitle(entry: MoneyEntry): String {
    val note = entry.note?.takeIf { it.isNotBlank() }
    // A note that merely repeats the debt's own name says nothing the row would
    // not say anyway, so it does not count as the user having named this one.
    val ownNote = entry.ownNote
    return when {
        entry.isOverdraftDraw -> ownNote ?: entry.overdraftRoute().orEmpty()
        // The person, and the person whatever else was said. "Lent more" is true
        // of every one of these rows and so distinguishes none of them — but a
        // note the user typed into the payment card does not replace the name
        // either: whose debt this is, is the one thing every row of the kind has
        // to say, and a title reading "car repair" leaves the reader to work out
        // which of three arrangements it moved. The note goes under it instead,
        // on the end of what the payment did — see [loanMovementLabel].
        entry.isLoanIncrease || entry.isLoanSettlement ->
            entry.loanName ?: ownNote.orEmpty()
        entry.isLoanPayment -> ownNote ?: stringResource(R.string.loan_movement_instalment)
        // A row attached to nothing at all — no note, no transfer, no debt — is
        // a "correct this balance". No list draws one except the statement of
        // the account it corrected, where it has to say what it is rather than
        // falling through to the bare "Money in" every other row could say.
        entry.isBalanceCorrection && note == null ->
            stringResource(R.string.statement_correction)
        // The app's own credit. Nobody was asked what it was for, so a row that
        // fell through to "Money in" would say nothing about the one movement on
        // the page the user did not make.
        entry.isInterestPosting -> stringResource(R.string.entry_interest)
        else -> note
            ?: entry.transferRoute()
            ?: stringResource(
                when {
                    entry.isTransfer -> R.string.transfer_row
                    entry.direction == Direction.IN -> R.string.add_money_in
                    else -> R.string.add_money_out
                }
            )
    }
}
