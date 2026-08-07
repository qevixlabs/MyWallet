package com.mywallet.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.mywallet.R

/**
 * Routes are plain strings rather than type-safe serialised objects: there are
 * a handful of them, they take at most one optional id, and the string form keeps
 * deep links trivial to add later.
 */
object Routes {
    const val HOME = "home"
    const val REMINDERS = "reminders"
    const val TIMELINE = "timeline"
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"

    /**
     * The one editor for anywhere money sits. It takes two optional ids because
     * an account and a loan live in different tables; at most one is ever set,
     * and neither means "add something new".
     */
    const val HOLDING_BASE = "holding"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_LOAN_ID = "loanId"

    /**
     * What kind of holding is being added, chosen before the form opens.
     *
     * Empty when opening something that already exists, which knows its own
     * kind — the argument is the answer to a question only a new one is asked.
     */
    const val ARG_GROUP = "group"

    fun holding(
        accountId: String? = null,
        loanId: String? = null,
        group: String? = null,
    ): String = "$HOLDING_BASE?$ARG_ACCOUNT_ID=${accountId.orEmpty()}" +
        "&$ARG_LOAN_ID=${loanId.orEmpty()}&$ARG_GROUP=${group.orEmpty()}"

    const val HOLDING_PATTERN =
        "$HOLDING_BASE?$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}&$ARG_LOAN_ID={$ARG_LOAN_ID}" +
            "&$ARG_GROUP={$ARG_GROUP}"

    /**
     * Everything that has happened to one loan. Reached from the loan itself
     * and takes the same id, so the statement and the terms are two views of
     * one thing rather than two places to go looking.
     */
    const val LEDGER_BASE = "ledger"

    fun loanLedger(loanId: String): String = "$LEDGER_BASE?$ARG_LOAN_ID=$loanId"

    const val LEDGER_PATTERN = "$LEDGER_BASE?$ARG_LOAN_ID={$ARG_LOAN_ID}"

    /**
     * What a debt still has to pay. Reached from the card that quotes its
     * instalment and takes the same id, so the terms, the statement and the
     * schedule are three views of one thing.
     *
     * A page rather than a panel inside the editor for the reasons the account
     * statement is one: a seven-year loan is eighty-four rows, and the editor is
     * a single scrolling column where none of them are lazy.
     */
    const val SCHEDULE_BASE = "schedule"

    fun loanSchedule(loanId: String): String = "$SCHEDULE_BASE?$ARG_LOAN_ID=$loanId"

    const val SCHEDULE_PATTERN = "$SCHEDULE_BASE?$ARG_LOAN_ID={$ARG_LOAN_ID}"

    /**
     * Everything that has touched one account — the same page a debt's payments
     * get, reached the same way and taking the same shape.
     *
     * It was drawn inside the holding's editor, expanded from a toggle, which
     * made a form that is already several screens long several screens longer:
     * the list is not lazy there (the editor is one long `Column`), it pushed
     * the colour picker and Save off the bottom, and the one column a reader
     * opens it for — the running balance — was read in a viewport a third of a
     * page tall.
     */
    const val STATEMENT_BASE = "statement"

    fun accountStatement(accountId: String): String =
        "$STATEMENT_BASE?$ARG_ACCOUNT_ID=$accountId"

    const val STATEMENT_PATTERN = "$STATEMENT_BASE?$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}"

    const val ADD_ENTRY_BASE = "entry"
    const val ARG_ENTRY_ID = "entryId"
    const val ARG_DIRECTION = "direction"

    /**
     * Which of the form's three tabs to open on.
     *
     * A separate argument from the direction because a transfer is not one: it
     * is both directions at once, and the form keeps its own segmented button
     * for changing its mind afterwards.
     */
    const val ARG_TRANSFER = "transfer"

    /**
     * Open the tapped occurrence itself, not the rule it came from.
     *
     * A repeating row opens its rule everywhere the page is about the plan —
     * Home, the Timeline, Reminders — because the rule is what those rows are.
     * A statement is the record, and a correction made there is about one date:
     * July's charge that came out wrong is fixed on July's row, and the rule
     * goes on saying what every other month does.
     */
    const val ARG_OCCURRENCE = "occurrence"

    /** @param entryId null to add, an id to edit an existing entry. */
    fun addEntry(
        entryId: String? = null,
        direction: String = "OUT",
        transfer: Boolean = false,
        occurrence: Boolean = false,
    ): String =
        "$ADD_ENTRY_BASE?$ARG_ENTRY_ID=${entryId.orEmpty()}" +
            "&$ARG_DIRECTION=$direction&$ARG_TRANSFER=$transfer" +
            "&$ARG_OCCURRENCE=$occurrence"

    const val ADD_ENTRY_PATTERN =
        "$ADD_ENTRY_BASE?$ARG_ENTRY_ID={$ARG_ENTRY_ID}" +
            "&$ARG_DIRECTION={$ARG_DIRECTION}&$ARG_TRANSFER={$ARG_TRANSFER}" +
            "&$ARG_OCCURRENCE={$ARG_OCCURRENCE}"
}

/**
 * The bottom bar: the places money is looked at every day.
 *
 * Accounts sits in the bar rather than behind a menu — it is the answer to
 * "where is my money?", asked as often as "what happened?". Labels moved to
 * Settings: they are set up once and touched rarely, and the slot they occupied
 * cost the drawer that pushed every heading down a row.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    // Between the record and the plan, which is exactly what it is: what is
    // happening now. The Timeline holds a month and buries today in it.
    REMINDERS(Routes.REMINDERS, R.string.nav_reminders, Icons.Outlined.NotificationsNone),
    TIMELINE(Routes.TIMELINE, R.string.nav_timeline, Icons.Outlined.Receipt),
    ACCOUNTS(Routes.ACCOUNTS, R.string.nav_accounts, Icons.Outlined.AccountBalanceWallet),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
}
