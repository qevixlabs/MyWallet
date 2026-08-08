package com.mywallet.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.FxRateEntity
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.RateChangeEntity
import com.mywallet.data.db.entity.RecurringSeriesEntity
import kotlinx.coroutines.flow.Flow

/**
 * An entry plus the few columns of its holding that the UI actually draws.
 *
 * Pulled in one join rather than a Room `@Relation`, which would fire a second
 * query per row — noticeable once the timeline has a few thousand entries.
 */
data class MoneyEntryRow(
    @Embedded val entry: MoneyEntryEntity,
    /** Whether the account this belongs to prefers the display currency. */
    @ColumnInfo(name = "a_display") val accountPrefersDisplay: Boolean?,
    @ColumnInfo(name = "a_name") val accountName: String?,
    /** The bank it sits under, so a row can say "Nabil Bank Savings". */
    @ColumnInfo(name = "a_inst") val accountInstitution: String?,
    /**
     * Which of the bank's products it is, and what it is denominated in — the
     * other two thirds of what a holding is called. See `holdingDisplayName`.
     */
    @ColumnInfo(name = "a_kind") val accountKind: AccountKind?,
    @ColumnInfo(name = "a_currency") val accountCurrency: String?,
    /**
     * How many live holdings sit under the same bank name — which decides
     * whether saying the kind distinguishes anything. See `holdingDisplayName`.
     */
    @ColumnInfo(name = "a_siblings") val accountSiblings: Int?,
    /** The other half of a transfer, so a converted one can show both figures. */
    @ColumnInfo(name = "t_amount") val transferPartnerMinor: Long?,
    @ColumnInfo(name = "t_currency") val transferPartnerCurrency: String?,
    /** Where the other half of a transfer sits, so a row can name both ends. */
    @ColumnInfo(name = "t_account") val transferPartnerAccountName: String?,
)

/** A repeating rule that pays a loan, with just enough of the loan to name it. */
data class LoanSeriesRow(
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "kind") val kind: LoanKind,
    /**
     * The day the current principal figure was set, which is where counting the
     * schedule's periods starts — and therefore where counting the ones that
     * went unpaid starts. See `Arrears`.
     */
    @ColumnInfo(name = "started_on") val startedOn: Long,
)

/**
 * One confirmed row that moved a loan, with the account it went through.
 *
 * Both ways a loan is paid are gathered by the query that fills this: the
 * instalments its own repeating rule generated, and the rows that name the loan
 * directly — lump sums, interest serviced alone, money drawn or added. A list
 * built from either link on its own would be missing half of what the user did.
 */
data class LoanEntryRow(
    @Embedded val entry: MoneyEntryEntity,
    @ColumnInfo(name = "a_name") val accountName: String?,
)

/** A day a facility's balance moved, signed: drawn positive, repaid negative. */
data class BalanceChangeRow(
    @ColumnInfo(name = "occurred_on") val occurredOn: Long,
    @ColumnInfo(name = "delta") val deltaMinor: Long,
)

/**
 * A currency the user holds something in: how many holdings are in it, and
 * when they last took it up.
 *
 * [uses] leads and [lastUsedAt] only breaks its ties, which is the rule
 * [HoldingUseRow] follows and the reason this row carries a count at all. It was
 * ordered on recency alone, so one account opened in dirhams to hold a single
 * transfer led the row in front of the currency five of the user's holdings are
 * actually denominated in — the newest answer rather than the likeliest one.
 */
data class CurrencyUseRow(
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "lastUsedAt") val lastUsedAt: Long,
)

/**
 * How much use one holding has had, which is what puts it near the
 * front of a row of chips.
 *
 * Counted from the movements themselves rather than kept as a tally of what has
 * been tapped, for the reason [CurrencyUseRow] is: a tally records what the user
 * once looked at, and an account they picked by mistake and have never used
 * again would go on being offered first for ever. The entries are the record of
 * what they actually do, and a movement deleted stops counting the moment it is.
 *
 * [lastOn] breaks the ties, so two accounts used the same number of times are
 * ordered by which was used more recently. Deliberately only the tie-break: a
 * salary account touched once a month must not fall behind a cash tin because
 * the cash tin was touched yesterday.
 */
data class HoldingUseRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "lastOn") val lastOn: Long,
)

/** One slice of the "where it went" breakdown, which is one holding. */
data class HoldingTotal(
    @ColumnInfo(name = "accountId") val accountId: String?,
    @ColumnInfo(name = "accountName") val accountName: String?,
    /**
     * The bank it sits under, which of its products it is, and what it holds —
     * so a slice is named the way every other list names the same holding. See
     * `holdingDisplayName`.
     */
    @ColumnInfo(name = "accountInstitution") val accountInstitution: String?,
    @ColumnInfo(name = "accountKind") val accountKind: AccountKind?,
    @ColumnInfo(name = "accountCurrency") val accountCurrency: String?,
    @ColumnInfo(name = "accountSiblings") val accountSiblings: Int?,
    @ColumnInfo(name = "colorArgb") val colorArgb: Int?,
    /**
     * Set when the slice is a debt rather than an account, so the row can say
     * which of a bank's arrangements it is — "Nabil Bank Loan" beside that same
     * bank's savings, rather than two rows reading the same name.
     */
    @ColumnInfo(name = "loanKind") val loanKind: LoanKind?,
    @ColumnInfo(name = "totalMinor") val totalMinor: Long,
    /**
     * The same total in the currency the money was actually in, and how many
     * currencies went into it. Anything above one and the sum means nothing —
     * see the query.
     */
    @ColumnInfo(name = "ownTotalMinor") val ownTotalMinor: Long,
    @ColumnInfo(name = "currencyCount") val currencyCount: Int,
    @ColumnInfo(name = "ownCurrency") val ownCurrency: String?,
    @ColumnInfo(name = "entryCount") val entryCount: Int,
)

/** In and out totals for a period, from a single grouped query. */
data class DirectionTotal(
    @ColumnInfo(name = "direction") val direction: Direction,
    @ColumnInfo(name = "totalMinor") val totalMinor: Long,
)

private const val ENTRY_WITH_ACCOUNT = """
    SELECT e.*,
           a.show_in_display_currency AS a_display,
           a.name AS a_name,
           a.institution AS a_inst,
           a.kind AS a_kind,
           a.currency_code AS a_currency,
           (SELECT COUNT(*) FROM account a2
             WHERE a2.deletedAt IS NULL AND a2.is_archived = 0
               AND lower(COALESCE(a2.institution, a2.name))
                 = lower(COALESCE(a.institution, a.name))) AS a_siblings,
           (SELECT o.amount_minor FROM money_entry o
             WHERE o.transfer_id = e.transfer_id AND o.id <> e.id AND o.deletedAt IS NULL
             LIMIT 1) AS t_amount,
           (SELECT o.currency_code FROM money_entry o
             WHERE o.transfer_id = e.transfer_id AND o.id <> e.id AND o.deletedAt IS NULL
             LIMIT 1) AS t_currency,
           (SELECT oa.name FROM money_entry o
              JOIN account oa ON oa.id = o.account_id
             WHERE o.transfer_id = e.transfer_id AND o.id <> e.id AND o.deletedAt IS NULL
             LIMIT 1) AS t_account
    FROM money_entry e
    LEFT JOIN account a ON a.id = e.account_id
"""

@Dao
interface MoneyEntryDao {

    @Query(
        ENTRY_WITH_ACCOUNT + """
        WHERE e.deletedAt IS NULL
          AND e.occurred_on >= :startDay AND e.occurred_on < :endDayExclusive
        ORDER BY e.occurred_on DESC, e.createdAt DESC
        """
    )
    fun observeBetween(startDay: Long, endDayExclusive: Long): Flow<List<MoneyEntryRow>>

    @Query("SELECT * FROM money_entry WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): MoneyEntryEntity?

    /**
     * Removes a row outright, tombstone and all.
     *
     * Only for a marker the app wrote to stand for something that never
     * happened — a skipped occurrence, which exists solely to stop a date being
     * generated again. Undoing that skip has to leave *nothing* behind, because
     * a tombstone is what would go on blocking it. Nothing the user recorded is
     * ever removed this way: a real row is tombstoned so another phone learns it
     * has gone.
     */
    @Query("DELETE FROM money_entry WHERE id = :id")
    suspend fun hardDelete(id: String)

    /**
     * Whether this row is still there, watched rather than asked once.
     *
     * The swipe lesson ends when its own row goes, and it may go from the
     * timeline, from an account's statement or from the entry form — so what
     * finishes it is the row's absence rather than any one screen reporting a
     * delete.
     */
    @Query("SELECT COUNT(*) FROM money_entry WHERE id = :id AND deletedAt IS NULL")
    fun observeExists(id: String): Flow<Int>

    // The same question of a holding, for the same reason: the accounts lesson
    // ends when the row it is taught on goes.

    /**
     * The row with this id whether or not it has been deleted.
     *
     * For the backup merge and nothing else. Every other read hides tombstones,
     * which is right everywhere except here: a merge that cannot see the
     * tombstone treats a row deleted on *this* phone as one it has never met,
     * and quietly brings it back from a file written before the delete.
     */
    @Query("SELECT * FROM money_entry WHERE id = :id")
    suspend fun findAnyById(id: String): MoneyEntryEntity?

    /**
     * Everything that has touched one account up to [asOfDay], oldest first.
     *
     * Oldest first because a running balance can only be built forwards; the
     * screen turns it round to show the newest at the top.
     *
     * Cut at today, exactly as [AccountDao.observeBalances] is. The statement is
     * the working behind that balance, so the two have to stop on the same day:
     * with the future in it, the figure at the top of the card counted a salary
     * banked for the 3rd that no other screen in the app had felt, and the
     * account read one thing on its own page and another everywhere else. What
     * is still to come belongs on the Timeline, where every row says so.
     */
    @Query(
        ENTRY_WITH_ACCOUNT + """
        WHERE e.deletedAt IS NULL AND e.account_id = :accountId
          AND e.occurred_on <= :asOfDay
        ORDER BY e.occurred_on ASC, e.createdAt ASC
        """
    )
    suspend fun forAccount(accountId: String, asOfDay: Long): List<MoneyEntryRow>

    @Query(
        """
        SELECT direction AS direction, SUM(base_amount_minor) AS totalMinor
        FROM money_entry
        WHERE deletedAt IS NULL AND is_adjustment = 0
          AND occurred_on >= :startDay AND occurred_on < :endDayExclusive
        GROUP BY direction
        """
    )
    fun observeTotals(startDay: Long, endDayExclusive: Long): Flow<List<DirectionTotal>>

    @Query(
        """
        SELECT COALESCE(e.account_id, l.id) AS accountId,
               COALESCE(a.name, l.lender, l.name) AS accountName,
               a.institution AS accountInstitution,
               a.kind AS accountKind,
               a.currency_code AS accountCurrency,
               (SELECT COUNT(*) FROM account a2
             WHERE a2.deletedAt IS NULL AND a2.is_archived = 0
               AND lower(COALESCE(a2.institution, a2.name))
                 = lower(COALESCE(a.institution, a.name))) AS accountSiblings,
               -- A card carries its own colour now, so a slice that is one is
               -- drawn in it. Every other debt has none and falls through to
               -- null, which is what the row's own figure colour is for.
               COALESCE(a.color_argb, l.color_argb) AS colorArgb,
               CASE WHEN e.account_id IS NULL THEN l.kind ELSE NULL END AS loanKind,
               SUM(e.base_amount_minor) AS totalMinor,
               -- What the money the slice is made of actually was, before any
               -- conversion. A dollar account's spending is dollars, and a
               -- slice that could only say what those dollars come to in
               -- rupees was stating a valuation as though it were the
               -- transaction. Only usable while every row in the slice agrees
               -- about its currency, which [currencyCount] is here to say:
               -- summing two currencies is always a bug, so a mixed slice
               -- keeps the converted figure alone.
               SUM(e.amount_minor) AS ownTotalMinor,
               COUNT(DISTINCT e.currency_code) AS currencyCount,
               MIN(e.currency_code) AS ownCurrency,
               COUNT(*) AS entryCount
        FROM money_entry e
        LEFT JOIN account a ON a.id = e.account_id
        LEFT JOIN loan l ON l.deletedAt IS NULL AND (
                 l.id = e.loan_id
              OR (e.series_id IS NOT NULL AND l.series_id = e.series_id)
          )
        WHERE e.deletedAt IS NULL AND e.is_adjustment = 0
          AND e.direction = :direction
          AND e.occurred_on >= :startDay AND e.occurred_on < :endDayExclusive
        GROUP BY COALESCE(e.account_id, l.id)
        ORDER BY totalMinor DESC
        """
    )
    fun observeTotalsByAccount(
        direction: Direction,
        startDay: Long,
        endDayExclusive: Long,
    ): Flow<List<HoldingTotal>>

    @Upsert
    suspend fun upsert(entry: MoneyEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<MoneyEntryEntity>)

    /**
     * Every row whose stored conversion points at a currency that is no longer
     * the one totals are read in.
     *
     * A stored base figure is only reusable while it still names the display
     * currency — see [MoneyEntryEntity.baseCurrencyCode]. When the user changes
     * that currency every one of these has to be worked out again, or the app
     * goes on showing rupees with a dollar sign in front of them.
     *
     * Tombstones are included deliberately: a deleted row that comes back
     * through Undo, or through a restore, must not come back stale.
     */
    @Query("SELECT * FROM money_entry WHERE base_currency_code <> :code")
    suspend fun withOtherBaseCurrency(code: String): List<MoneyEntryEntity>

    /** Both halves of one transfer, newest write order irrelevant. */
    @Query("SELECT * FROM money_entry WHERE transfer_id = :transferId AND deletedAt IS NULL")
    suspend fun entriesForTransfer(transferId: String): List<MoneyEntryEntity>

    /** Tombstones or restores a whole transfer, so a pair cannot be left half deleted. */
    @Query("UPDATE money_entry SET deletedAt = :now, updatedAt = :now WHERE transfer_id = :transferId")
    suspend fun softDeleteTransfer(transferId: String, now: Long)

    @Query("UPDATE money_entry SET deletedAt = NULL, updatedAt = :now WHERE transfer_id = :transferId")
    suspend fun restoreTransfer(transferId: String, now: Long)

    /**
     * The entry a repeating rule was created from — its earliest surviving
     * occurrence.
     *
     * Deleting *that* row means the user is done with the whole arrangement, so
     * the rule stops with it. Deleting any later occurrence removes only that one.
     * This used to be inferred by comparing the entry's date with the rule's start
     * date, which stopped matching the moment either was edited, and the deleted
     * thing carried on appearing in the projection.
     */
    @Query(
        """
        SELECT id FROM money_entry
        WHERE series_id = :seriesId AND deletedAt IS NULL
        ORDER BY occurred_on ASC, createdAt ASC
        LIMIT 1
        """
    )
    suspend fun anchorEntryForSeries(seriesId: String): String?

    /**
     * Every surviving occurrence of one rule, oldest first.
     *
     * For the one delete that means the whole arrangement rather than one date
     * of it — see `AddEntryViewModel.delete`. Oldest first because each row is
     * then removed through the ordinary door, and the ordinary door refuses a
     * lump sum with a later one still on file; going forwards never meets that.
     */
    @Query(
        """
        SELECT id FROM money_entry
        WHERE series_id = :seriesId AND deletedAt IS NULL
        ORDER BY occurred_on ASC, createdAt ASC
        """
    )
    suspend fun entriesForSeries(seriesId: String): List<String>

    @Query("UPDATE money_entry SET series_id = :seriesId, updatedAt = :now WHERE id = :id")
    suspend fun setSeries(id: String, seriesId: String?, now: Long)

    /**
     * Every interest posting on one account, newest first.
     *
     * Found by the shape of its id, which is the only mark a posting carries now
     * that labels are gone. That id is derived — the account and the day it was
     * credited — precisely so recomputing a period rewrites the same row, so it
     * is a fact about the posting rather than an accident of one. Matched with
     * `substr` rather than `LIKE`, which would read `_` in an id as a wildcard.
     * The holding editor recognises one the same way.
     */
    @Query(
        """
        SELECT * FROM money_entry
        WHERE account_id = :accountId AND deletedAt IS NULL
          AND substr(id, 1, length(:idPrefix)) = :idPrefix
        ORDER BY occurred_on DESC
        """
    )
    suspend fun postingsWithIdPrefix(accountId: String, idPrefix: String): List<MoneyEntryEntity>

    /**
     * Tombstones everything that ever touched one account, both halves of a
     * transfer included.
     *
     * Deleting an account is the user saying it should never have been there,
     * and a movement left behind is money that left an account that no longer
     * exists: it still counts towards a month's spending, still sits in the
     * timeline, and can only be removed one swipe at a time. The partner half of
     * a transfer goes with it because half a transfer is not a thing — the money
     * would arrive somewhere having left nowhere.
     */
    @Query(
        """
        UPDATE money_entry SET deletedAt = :now, updatedAt = :now
        WHERE deletedAt IS NULL AND (
            account_id = :accountId
            OR transfer_id IN (
                SELECT transfer_id FROM money_entry
                WHERE account_id = :accountId AND transfer_id IS NOT NULL
            )
        )
        """
    )
    suspend fun softDeleteForAccount(accountId: String, now: Long)

    /**
     * Gives back the account to occurrences of a user's own repeating rule that
     * were generated without one.
     *
     * The rows already in the timeline were written while every back-dated
     * occurrence lost its account — a rule meant for a loan's replayed schedule,
     * applied to rules the user wrote by hand. There is no other way for them to
     * acquire it: an occurrence is generated rather than typed, so nobody is
     * ever asked which account it came from, and the result is a rule whose
     * first payment debits the bank and whose next fifteen do not.
     *
     * Deliberately narrow. Only **EXPECTED** rows, which are still the rule's own
     * words — a confirmed one is the user's, and if they saved it naming no
     * account that is their answer. Only rules that are **nobody else's
     * schedule**: a loan's instalments and a policy's premiums keep the cutoff,
     * which is what it was built for. And only rules that are **not transfers**,
     * because a back-dated transfer is missing its arriving row altogether and
     * half a transfer put back is money leaving for nowhere.
     *
     * Idempotent: after one pass there are no such rows left.
     */
    @Query(
        """
        UPDATE money_entry
        SET account_id = (
                SELECT account_id FROM recurring_series s WHERE s.id = money_entry.series_id
            ),
            updatedAt = :now
        WHERE deletedAt IS NULL
          AND account_id IS NULL
          AND status = 'EXPECTED'
          AND series_id IN (
              SELECT id FROM recurring_series
              WHERE deletedAt IS NULL
                AND account_id IS NOT NULL
                AND transfer_to_account_id IS NULL
                AND id NOT IN (
                    SELECT series_id FROM loan
                    WHERE series_id IS NOT NULL AND deletedAt IS NULL
                )
          )
        """
    )
    suspend fun adoptOrphanedOccurrences(now: Long): Int

    @Query("UPDATE money_entry SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE money_entry SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** How much has ever touched one holding. See [AccountDao.activeNamed]. */
    @Query("SELECT COUNT(*) FROM money_entry WHERE account_id = :accountId AND deletedAt IS NULL")
    suspend fun countForAccount(accountId: String): Int

    /** Whether anything at all has been recorded. See `WalletRepository.hasHistory`. */
    @Query("SELECT COUNT(*) FROM money_entry WHERE deletedAt IS NULL")
    suspend fun count(): Int

    /**
     * Fires whenever anything in `money_entry` changes. The number is never read.
     *
     * For the pages that are **built once and then read** — an account's
     * statement, a debt's ledger. Each is a list whose every balance was worked
     * out from the row above it, so it cannot be patched in place; it is
     * rebuilt. They used to rebuild only when the page itself removed a row,
     * which left every edit made anywhere else invisible: tapping a row opens
     * the entry form, and correcting an amount there came back to a statement
     * still showing the old one, right down to the running balance. It took
     * leaving the page and returning to see what had actually been saved.
     *
     * **The count is not the point and must not be made distinct.** Room re-runs
     * this on any write to the table and emits whatever it gets, which is what
     * catches an *amount* being corrected — the row count does not move for
     * that, and a `distinctUntilChanged` here would swallow exactly the case
     * this exists for.
     */
    @Query("SELECT COUNT(*) FROM money_entry")
    fun observeRevision(): Flow<Int>

    @Query("SELECT * FROM money_entry")
    suspend fun dumpAll(): List<MoneyEntryEntity>
}

/** An account plus its computed balance, in the account's own currency. */
data class AccountBalance(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "balance_minor") val balanceMinor: Long,
)

@Dao
interface AccountDao {

    @Query(
        """
        SELECT * FROM account
        WHERE deletedAt IS NULL AND is_archived = 0
        ORDER BY sort_order ASC, name COLLATE NOCASE ASC
        """
    )
    fun observeActive(): Flow<List<AccountEntity>>

    /** Every holding still on file, for a sweep that has to visit all of them. */
    @Query("SELECT * FROM account WHERE deletedAt IS NULL")
    suspend fun allActive(): List<AccountEntity>

    /** Whether this holding is still there. See `MoneyEntryDao.observeExists`. */
    @Query("SELECT COUNT(*) FROM account WHERE id = :id AND deletedAt IS NULL")
    fun observeExists(id: String): Flow<Int>

    @Query("SELECT * FROM account WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): AccountEntity?

    /**
     * The row with this id whether or not it has been deleted.
     *
     * For the backup merge and nothing else. Every other read hides tombstones,
     * which is right everywhere except here: a merge that cannot see the
     * tombstone treats a row deleted on *this* phone as one it has never met,
     * and quietly brings it back from a file written before the delete.
     */
    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findAnyById(id: String): AccountEntity?

    @Query("SELECT COUNT(*) FROM account WHERE deletedAt IS NULL")
    suspend fun count(): Int

    /**
     * Live holdings of the same kind going by the same name.
     *
     * For the backup merge, which has one job to do with it: the first run seeds
     * a Cash account, so restoring a file onto a reinstalled app used to leave
     * two — the seeded empty one and the one from the file, holding the money.
     * A same-named, untouched holding folds for exactly this reason.
     */
    @Query(
        """
        SELECT * FROM account
        WHERE deletedAt IS NULL AND kind = :kind AND LOWER(name) = LOWER(:name)
        """
    )
    suspend fun activeNamed(name: String, kind: AccountKind): List<AccountEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM account")
    suspend fun nextSortOrder(): Int

    /**
     * Every account, most-used first. See [HoldingUseRow].
     *
     * Falls back to `sort_order` where nothing has moved through either, which
     * is the order the accounts list itself is in — so a phone with no history
     * offers them in the order the user created them rather than in whatever
     * order SQLite happened to return.
     */
    @Query(
        """
        SELECT a.id AS id,
               COUNT(e.id) AS uses,
               COALESCE(MAX(e.occurred_on), 0) AS lastOn
        FROM account a
        LEFT JOIN money_entry e ON e.account_id = a.id AND e.deletedAt IS NULL
        WHERE a.deletedAt IS NULL
        GROUP BY a.id
        ORDER BY uses DESC, lastOn DESC, a.sort_order ASC
        """
    )
    suspend fun byUse(): List<HoldingUseRow>

    /**
     * Every currency the user actually holds money in, **most held first** —
     * which is what the form offers before it offers all seventeen.
     *
     * The same rule the accounts are ranked by ([HoldingUseRow]):
     * how many holdings are denominated in it leads, and how recently one was
     * taken up only breaks the ties. Recency alone put whichever currency was
     * touched last in front of the one most of the user's money is in.
     *
     * Debts count and are unioned in: money borrowed in dirhams is a currency
     * this user uses, and leaving loans out would go on guessing at somebody
     * whose only foreign holding is one. Read from the holdings rather than
     * kept as a list of what has been tapped, so it is the currencies they have
     * and not the ones they once looked at — a currency picked by mistake and
     * deleted stops being suggested, which a tally of taps could never say.
     */
    @Query(
        """
        SELECT code, COUNT(*) AS uses, MAX(createdAt) AS lastUsedAt FROM (
            SELECT currency_code AS code, createdAt FROM account WHERE deletedAt IS NULL
            UNION ALL
            SELECT currency_code AS code, createdAt FROM loan WHERE deletedAt IS NULL
        )
        GROUP BY code
        ORDER BY uses DESC, lastUsedAt DESC
        """
    )
    suspend fun currenciesInUse(): List<CurrencyUseRow>

    /**
     * Balance per account, in that account's own currency.
     *
     * Uses `amount_minor` — the amount as entered — not the base-converted
     * figure, because an account denominated in USD holds dollars regardless of
     * what the user chooses to view totals in. Adjustments are included: they
     * exist precisely to move a balance.
     *
     * A scheduled payment counts from the day it falls due, without waiting to
     * be confirmed by hand. Cut at [asOfDay], which is today: money dated for
     * next week has not left the account yet.
     */
    @Query(
        """
        SELECT a.id AS account_id,
               a.opening_balance_minor + COALESCE(SUM(
                   CASE WHEN e.direction = 'IN' THEN e.amount_minor ELSE -e.amount_minor END
               ), 0) AS balance_minor
        FROM account a
        LEFT JOIN money_entry e
               ON e.account_id = a.id
              AND e.deletedAt IS NULL
              AND e.occurred_on <= :asOfDay
        WHERE a.deletedAt IS NULL
        GROUP BY a.id
        """
    )
    fun observeBalances(asOfDay: Long): Flow<List<AccountBalance>>

    /**
     * Accounts the bank pays something on, which is what interest is worked out
     * for.
     *
     * Either they opened at a rate, or one has been recorded since. Both halves
     * are needed: an account given its first rate today has nothing in its own
     * column — that column means the rate it *opened* at, and this account
     * opened at none.
     *
     * Fixed deposits are excluded even though every one of them has a rate.
     * What this list feeds is the savings quarter — a fixed slice of the year
     * paid on 1 Baisakh, Shrawan, Kartik and Magh, credited as a real entry. A
     * deposit earns on its *own* periods, running from the day it was made, and
     * its interest is part of its balance rather than a payment into it. Left in
     * here it would be paid twice: once by the bank's calendar and once by its
     * own terms.
     */
    @Query(
        """
        SELECT * FROM account
        WHERE deletedAt IS NULL AND kind <> 'FIXED_DEPOSIT' AND (
            (annual_rate IS NOT NULL AND annual_rate > 0)
            OR id IN (
                SELECT account_id FROM rate_change
                WHERE account_id IS NOT NULL AND deletedAt IS NULL
            )
        )
        """
    )
    suspend fun earningInterest(): List<AccountEntity>

    /**
     * Every movement on an account up to [until], for working out what balance
     * it held on each day. The opening balance is not here — it is on the
     * account itself, and belongs to the day before the first of these.
     */
    @Query(
        """
        SELECT occurred_on,
               CASE WHEN direction = 'IN' THEN amount_minor ELSE -amount_minor END AS delta
        FROM money_entry
        WHERE account_id = :accountId AND deletedAt IS NULL AND occurred_on <= :until
        ORDER BY occurred_on ASC
        """
    )
    suspend fun movements(accountId: String, until: Long): List<BalanceChangeRow>

    /**
     * The same, less the interest this account has already been credited.
     *
     * There is one caller and one reason: interest is worked out from the
     * balance the account held, and the interest already credited is the app's
     * own previous answer. Reading it back would build each run on top of the
     * last, so the postings come out and are put back one period at a time as
     * they are recomputed. See [com.mywallet.data.repo.InterestRepository].
     *
     * They are told apart by their derived id, for the reason
     * [postingsWithIdPrefix] gives.
     */
    @Query(
        """
        SELECT occurred_on,
               CASE WHEN direction = 'IN' THEN amount_minor ELSE -amount_minor END AS delta
        FROM money_entry
        WHERE account_id = :accountId AND deletedAt IS NULL AND occurred_on <= :until
          AND substr(id, 1, length(:idPrefix)) <> :idPrefix
        ORDER BY occurred_on ASC
        """
    )
    suspend fun movementsExcept(
        accountId: String,
        until: Long,
        idPrefix: String,
    ): List<BalanceChangeRow>

    @Query(
        "UPDATE account SET interest_posted_through = :day, updatedAt = :now WHERE id = :id"
    )
    suspend fun setInterestPostedThrough(id: String, day: Long, now: Long)

    /**
     * Gives every account that has never been asked the interval it has in fact
     * been credited on — the old global setting, adopted once on upgrade.
     *
     * Only the two kinds that are credited period by period, because they are
     * the only ones the answer ever meant anything to: a figure left on a cash
     * tin would be inert, and it would still have bumped that row's `updatedAt`
     * past every backup copy of it for nothing. Only where the column is still
     * null, so an account already answered for keeps its own answer. Deleted
     * rows are included on purpose: a tombstoned account restored from a backup
     * would otherwise come back on an interval nobody ever chose.
     */
    @Query(
        "UPDATE account SET interest_payout_months = :months, updatedAt = :now " +
            "WHERE interest_payout_months IS NULL AND kind IN ('SAVINGS', 'CURRENT')"
    )
    suspend fun adoptPayoutMonths(months: Int, now: Long): Int

    /**
     * Ties a policy to the rule that pays its premiums.
     *
     * Written after both exist rather than with the rest of the row: the rule
     * has to name the policy it pays into, so the policy is saved first and the
     * link closed afterwards.
     */
    @Query("UPDATE account SET premium_series_id = :seriesId, updatedAt = :now WHERE id = :id")
    suspend fun setPremiumSeries(id: String, seriesId: String?, now: Long)

    /**
     * The arrangement a repeating rule pays into, if it pays into one.
     *
     * Asked by the money form before it offers to edit an occurrence: a policy's
     * premium, a deposit's instalment and a goal's contribution are each one
     * date of a schedule the arrangement owns, and the controls that would
     * rewrite that schedule — where the money goes, and whether it repeats at
     * all — are not this form's to offer. The same question
     * [LoanRepository.findLoanBySeries] answers for a debt.
     */
    @Query("SELECT * FROM account WHERE premium_series_id = :seriesId AND deletedAt IS NULL LIMIT 1")
    suspend fun findByPremiumSeries(seriesId: String): AccountEntity?

    @Query("UPDATE account SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /**
     * Unpoints any deposit or policy that was told to pay into this account.
     *
     * The column is deliberately not a foreign key — a maturity has to keep
     * saying where it was meant to go — so nothing clears it on its own, and a
     * forecast would go on crediting an account that is gone.
     */
    @Query(
        """
        UPDATE account SET matures_into_account_id = NULL, updatedAt = :now
        WHERE matures_into_account_id = :accountId
        """
    )
    suspend fun detachMaturityTarget(accountId: String, now: Long)

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account")
    suspend fun dumpAll(): List<AccountEntity>
}

@Dao
interface RateChangeDao {

    /** Every rate a holding has been on, oldest first. */
    @Query(
        """
        SELECT * FROM rate_change
        WHERE deletedAt IS NULL AND (account_id = :accountId OR loan_id = :loanId)
        ORDER BY effective_from ASC
        """
    )
    suspend fun forHolding(accountId: String?, loanId: String?): List<RateChangeEntity>

    /** Fires whenever any rate moves, so balances built on one can be redrawn. */
    @Query("SELECT COUNT(*) FROM rate_change WHERE deletedAt IS NULL")
    fun observeRevision(): Flow<Int>

    @Query("SELECT * FROM rate_change WHERE deletedAt IS NULL")
    suspend fun all(): List<RateChangeEntity>

    /** Whether or not it has been deleted — the one read that sees tombstones, for the backup merge. */
    @Query("SELECT * FROM rate_change WHERE id = :id")
    suspend fun findAnyById(id: String): RateChangeEntity?

    @Upsert
    suspend fun upsert(change: RateChangeEntity)

    @Query("UPDATE rate_change SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /**
     * Every rate an account was ever on, tombstoned with the account.
     *
     * The table's foreign key cascades on a real delete, and nothing here is a
     * real delete — so without this the rates outlive the holding they belong
     * to and a restored backup brings them back attached to nothing.
     */
    @Query(
        """
        UPDATE rate_change SET deletedAt = :now, updatedAt = :now
        WHERE account_id = :accountId AND deletedAt IS NULL
        """
    )
    suspend fun softDeleteForAccount(accountId: String, now: Long)

    @Query("SELECT * FROM rate_change")
    suspend fun dumpAll(): List<RateChangeEntity>
}

@Dao
interface FxRateDao {

    @Query("SELECT * FROM fx_rate WHERE base_code = :base")
    suspend fun ratesFor(base: String): List<FxRateEntity>

    @Query("SELECT * FROM fx_rate WHERE base_code = :base AND quote_code = :quote")
    suspend fun rate(base: String, quote: String): FxRateEntity?

    @Query("SELECT MAX(fetched_at) FROM fx_rate WHERE base_code = :base")
    suspend fun lastFetchedAt(base: String): Long?

    @Upsert
    suspend fun upsertAll(rates: List<FxRateEntity>)

    @Query("DELETE FROM fx_rate WHERE base_code = :base")
    suspend fun clear(base: String)
}

@Dao
interface RecurringSeriesDao {

    @Query("SELECT * FROM recurring_series WHERE deletedAt IS NULL ORDER BY start_on ASC")
    fun observeAll(): Flow<List<RecurringSeriesEntity>>

    @Query("SELECT * FROM recurring_series WHERE deletedAt IS NULL AND is_paused = 0")
    suspend fun activeSeries(): List<RecurringSeriesEntity>

    @Query("SELECT * FROM recurring_series WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): RecurringSeriesEntity?

    /** Whether or not it has been deleted — the one read that sees tombstones, for the backup merge. */
    @Query("SELECT * FROM recurring_series WHERE id = :id")
    suspend fun findAnyById(id: String): RecurringSeriesEntity?

    @Upsert
    suspend fun upsert(series: RecurringSeriesEntity)

    @Query("UPDATE recurring_series SET materialised_through = :day, updatedAt = :now WHERE id = :id")
    suspend fun setMaterialisedThrough(id: String, day: Long, now: Long)

    @Query("UPDATE recurring_series SET is_paused = :paused, updatedAt = :now WHERE id = :id")
    suspend fun setPaused(id: String, paused: Boolean, now: Long)

    @Query("UPDATE recurring_series SET end_on = :day, updatedAt = :now WHERE id = :id")
    suspend fun setEndOn(id: String, day: Long?, now: Long)

    @Query("UPDATE recurring_series SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /**
     * Stops every rule that moves money through one account, at either end.
     *
     * Without it the account is gone and its standing instructions are not: the
     * next launch materialises another occurrence, and the payment reappears
     * for a holding that no longer exists.
     */
    @Query(
        """
        UPDATE recurring_series SET deletedAt = :now, updatedAt = :now
        WHERE deletedAt IS NULL
          AND (account_id = :accountId OR transfer_to_account_id = :accountId)
        """
    )
    suspend fun softDeleteForAccount(accountId: String, now: Long)

    /**
     * Removes expected rows for a series from [fromDay] on, when the rule changes.
     *
     * A real delete, not a tombstone — the only place in the app that does not
     * leave one. These rows were generated from the rule and are about to be
     * generated again from its new version; a tombstone exists to stop something
     * the *user* deleted from coming back, and would here block the replacement
     * from ever appearing.
     */
    @Query(
        """
        DELETE FROM money_entry
        WHERE series_id = :seriesId AND status = 'EXPECTED' AND occurred_on >= :fromDay
        """
    )
    suspend fun discardExpectedFrom(seriesId: String, fromDay: Long)

    @Query("SELECT * FROM recurring_series")
    suspend fun dumpAll(): List<RecurringSeriesEntity>

    /**
     * Whether this occurrence has already been written, tombstones included.
     *
     * Counting deleted rows is the point: one the user threw away must not be
     * generated again on the next launch. This makes materialising idempotent
     * against the data itself rather than trusting the watermark alone.
     */
    @Query(
        """
        SELECT COUNT(*) FROM money_entry
        WHERE series_id = :seriesId AND occurred_on = :day
        """
    )
    suspend fun occurrenceCount(seriesId: String, day: Long): Int

    /**
     * Which days between [from] and [to] this series already has a row on,
     * tombstones included.
     *
     * The same question [occurrenceCount] answers, asked of a whole window at
     * once — a projection walks a month of dates and asking per day would be a
     * query per row. Counting deleted rows is again the point: a date the user
     * has dropped must not be drawn as still coming.
     */
    @Query(
        """
        SELECT occurred_on FROM money_entry
        WHERE series_id = :seriesId AND occurred_on BETWEEN :from AND :to
        """
    )
    suspend fun occurrenceDays(seriesId: String, from: Long, to: Long): List<Long>

    /**
     * The same days, but only where a row **survives**.
     *
     * The opposite half of [occurrenceDays], and the difference between them is
     * the whole of what an arrears calculation reads: a scheduled day whose only
     * row has been deleted is a payment that was owed and never made. That one
     * counts tombstones so a date the user dropped is never generated again;
     * this one ignores them so the same date can be seen to be unpaid.
     *
     * [direction] is the rule's own, and it is what keeps the count honest on a
     * rule that pays a policy or a goal: a transfer is **two** rows sharing one
     * date, and counting both would say every premium had been paid twice — so
     * every one of them would read as settled and none could ever be owed. The
     * money leaving is the half that matches the rule.
     */
    @Query(
        """
        SELECT occurred_on FROM money_entry
        WHERE series_id = :seriesId AND deletedAt IS NULL
          AND direction = :direction
          AND occurred_on BETWEEN :from AND :to
        """
    )
    suspend fun paidOccurrenceDays(
        seriesId: String,
        from: Long,
        to: Long,
        direction: Direction,
    ): List<Long>
}

@Dao
interface LoanDao {

    @Query("SELECT * FROM loan WHERE deletedAt IS NULL ORDER BY is_closed ASC, started_on DESC")
    fun observeAll(): Flow<List<LoanEntity>>

    /**
     * Fires whenever anything in `money_entry` changes. The number itself is
     * never read.
     *
     * What is owed is worked out from the payments recorded against the loan,
     * but [observeAll] watches only the `loan` table, so Room had no reason to
     * re-run it when a payment arrived. Every list of debts went on showing the
     * figure it was built with: an instalment falling due moved the account
     * balance beside it and left the loan untouched, and the two sat there
     * disagreeing until something happened to the loan row itself.
     */
    @Query("SELECT COUNT(*) FROM money_entry")
    fun observeEntryRevision(): Flow<Int>

    @Query("SELECT COUNT(*) FROM loan WHERE deletedAt IS NULL")
    suspend fun count(): Int

    @Query("SELECT * FROM loan WHERE id = :id AND deletedAt IS NULL")
    suspend fun findById(id: String): LoanEntity?

    /** Every debt still on file, for a sweep that has to visit all of them. */
    @Query("SELECT * FROM loan WHERE deletedAt IS NULL")
    suspend fun activeLoans(): List<LoanEntity>

    /**
     * The row with this id whether or not it has been deleted.
     *
     * For the backup merge and nothing else. Every other read hides tombstones,
     * which is right everywhere except here: a merge that cannot see the
     * tombstone treats a row deleted on *this* phone as one it has never met,
     * and quietly brings it back from a file written before the delete.
     */
    @Query("SELECT * FROM loan WHERE id = :id")
    suspend fun findAnyById(id: String): LoanEntity?

    /** The loan a repeating rule pays, so a projected instalment can be opened. */
    @Query("SELECT * FROM loan WHERE series_id = :seriesId AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySeries(seriesId: String): LoanEntity?

    /**
     * Every rule that pays a loan, and the loan it pays, in one query.
     *
     * The timeline needs to name which of a month's projected payments are
     * instalments and what they settle, and asking per row would be one query
     * per drawn line.
     */
    @Query(
        """
        SELECT series_id, name, kind, started_on FROM loan
        WHERE series_id IS NOT NULL AND deletedAt IS NULL
        """
    )
    suspend fun loanSeries(): List<LoanSeriesRow>

    /**
     * Instalments paid so far, counting only those on or after [sinceDay] — the
     * day the loan's current principal figure was set.
     *
     * The lower cutoff is what makes a re-based loan add up. After a lump sum
     * the loan is rewritten as a fresh one for the reduced balance, and the
     * instalments paid against the *old* balance are already accounted for
     * inside it; counting them again would show the debt clearing far sooner
     * than it does.
     *
     * [untilDay] is the same question asked of an earlier moment: what a lump
     * sum paid three weeks ago actually met is the balance as it stood *then*,
     * with only the instalments up to that day counted against it.
     */
    @Query(
        """
        SELECT COUNT(*) FROM money_entry
        WHERE series_id = :seriesId AND deletedAt IS NULL
          AND occurred_on >= :sinceDay AND occurred_on <= :untilDay
        """
    )
    suspend fun paymentsSince(
        seriesId: String,
        sinceDay: Long,
        untilDay: Long = Long.MAX_VALUE,
    ): Int

    /**
     * Total repaid against a loan with no schedule, in the loan's own currency.
     * Same cutoffs, for the same reasons.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0) FROM money_entry
        WHERE series_id = :seriesId AND deletedAt IS NULL
          AND occurred_on >= :sinceDay AND occurred_on <= :untilDay
        """
    )
    suspend fun repaidSince(
        seriesId: String,
        sinceDay: Long,
        untilDay: Long = Long.MAX_VALUE,
    ): Long

    /**
     * Principal paid outside the instalments — lump sums and principal-only
     * payments — over the whole life of the loan.
     *
     * For display only. Every rupee of it is already inside the loan's principal
     * figure, so subtracting this from the balance would count it twice.
     */
    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0) FROM money_entry
        WHERE loan_id = :loanId AND loan_part = 'PRINCIPAL' AND deletedAt IS NULL
          AND occurred_on <= :today
        """
    )
    suspend fun principalPaidOutright(loanId: String, today: Long): Long

    /**
     * How many lump sums off this loan were paid after the one on [day].
     *
     * Asked before a lump sum is deleted, on a debt that amortises. Each one
     * re-bases the loan on the balance it met, so the balances after it were
     * computed from the reduced figure; putting the money back on the *current*
     * balance would be adding it at the wrong date, and the interest the later
     * instalments charged on it would be quietly forgiven. The one being deleted
     * therefore has to be the last, and the user is told to take the later one
     * first rather than handed a debt that is a few thousand rupees out.
     *
     * The tie on [createdAt] matters: two lump sums recorded on one day are
     * ordered by when they were typed, exactly as the ledger orders them.
     */
    @Query(
        """
        SELECT COUNT(*) FROM money_entry
        WHERE loan_id = :loanId AND loan_part = 'PRINCIPAL' AND deletedAt IS NULL
          AND id <> :exceptId
          AND (occurred_on > :day OR (occurred_on = :day AND createdAt > :createdAt))
        """
    )
    suspend fun principalPaymentsAfter(
        loanId: String,
        day: Long,
        createdAt: Long,
        exceptId: String,
    ): Int

    /** Interest serviced on its own, which never touched the balance. Display only. */
    @Query(
        """
        SELECT COALESCE(SUM(amount_minor), 0) FROM money_entry
        WHERE loan_id = :loanId AND loan_part = 'INTEREST' AND deletedAt IS NULL
        """
    )
    suspend fun interestPaidOutright(loanId: String): Long

    /**
     * Everything that has actually happened to one loan, oldest first.
     *
     * [seriesId] is the loan's own rule and may be null — a bare IOU has none —
     * in which case that half of the condition matches nothing, since SQL never
     * treats NULL as equal to anything.
     */
    @Query(
        """
        SELECT e.*, a.name AS a_name
        FROM money_entry e
        LEFT JOIN account a ON a.id = e.account_id
        WHERE e.deletedAt IS NULL
          AND (e.loan_id = :loanId OR e.series_id = :seriesId)
        ORDER BY e.occurred_on ASC, e.createdAt ASC
        """
    )
    suspend fun movements(loanId: String, seriesId: String?): List<LoanEntryRow>

    /**
     * How many of them there are, for deciding whether a statement exists to
     * open at all. The same condition as [movements], counted rather than read:
     * offering "see every payment" on a debt nothing has happened to yet leads
     * to an empty page.
     */
    @Query(
        """
        SELECT COUNT(*) FROM money_entry
        WHERE deletedAt IS NULL AND (loan_id = :loanId OR series_id = :seriesId)
        """
    )
    suspend fun movementCount(loanId: String, seriesId: String?): Int

    /**
     * What the debt itself arrived as, on the day the money changed hands.
     *
     * Looked up by the derived id rather than by shape, for the reason
     * everything else that has to recognise that row does: it is identical to
     * more being borrowed on the same arrangement. Null when the user never said
     * where the money went, which is most debts — and then the balance the debt
     * opened at is the whole of what it opened at.
     */
    @Query("SELECT amount_minor FROM money_entry WHERE id = :id AND deletedAt IS NULL")
    suspend fun disbursedAmount(id: String): Long?

    /**
     * The movements that rewrote `principal_minor` **in place**, after [day].
     *
     * These are the ones an amortisation schedule cannot account for, and the
     * reason a debt with one cannot simply be asked what it stood at last March:
     * a lump sum re-bases the loan and money borrowed on the same arrangement
     * adds to it, so the figure on file describes today and nothing else.
     * Everything else against a debt is either counted from the rule's own dates
     * (an instalment) or moved no balance at all (interest serviced on its own).
     *
     * The **opening is excluded**, by the derived id every other reader tells it
     * by: the debt arriving is the money landing in an account, and on a loan
     * with a schedule the principal was already the sum borrowed before that row
     * was ever written.
     *
     * Signed as [balanceChanges] signs its rows, for a *borrowed* balance: a lump
     * sum comes off, a top-up goes on. The caller flips it for money lent.
     */
    @Query(
        """
        SELECT occurred_on,
               CASE WHEN direction = 'IN' THEN amount_minor ELSE -amount_minor END AS delta
        FROM money_entry
        WHERE loan_id = :loanId AND deletedAt IS NULL
          AND occurred_on > :day
          AND id <> :openingId
          AND (loan_part IS NULL OR loan_part = 'PRINCIPAL')
        ORDER BY occurred_on ASC
        """
    )
    suspend fun basisChangesAfter(
        loanId: String,
        day: Long,
        openingId: String,
    ): List<BalanceChangeRow>

    /**
     * Lump sums against one debt whose day has arrived, oldest first.
     *
     * For `LoanRepository.applyDuePayments`, which folds in the ones the debt has
     * not been re-based on yet. Which of these those are is decided there, from
     * the date against `started_on`; this only has to stop at today, since a
     * payment promised for next week is not owed yet.
     *
     * The amount is returned unsigned — a lump sum is always money coming off,
     * whichever way the debt runs, and the caller subtracts it.
     */
    @Query(
        """
        SELECT occurred_on, amount_minor AS delta FROM money_entry
        WHERE loan_id = :loanId AND loan_part = 'PRINCIPAL' AND deletedAt IS NULL
          AND occurred_on <= :today
        ORDER BY occurred_on ASC, createdAt ASC
        """
    )
    suspend fun duePrincipalPayments(loanId: String, today: Long): List<BalanceChangeRow>

    /** The mirror: lump sums promised for a day that has not arrived. */
    @Query(
        """
        SELECT occurred_on, amount_minor AS delta FROM money_entry
        WHERE loan_id = :loanId AND loan_part = 'PRINCIPAL' AND deletedAt IS NULL
          AND occurred_on > :today
        ORDER BY occurred_on ASC
        """
    )
    suspend fun pendingPrincipalPayments(loanId: String, today: Long): List<BalanceChangeRow>

    /**
     * Every day a loan's balance moved, for metering interest on one that has no
     * schedule to read a figure off — an overdraft, or a debt between people
     * with a rate but no end date.
     *
     * Both links are followed, as in [movements]: a debt due in one go is paid
     * through its own rule, and a meter blind to that payment would go on
     * charging interest on money that had already gone back.
     *
     * Interest serviced on its own is the one thing excluded. It never moved the
     * balance, which is the entire point of servicing it.
     *
     * Signed by direction, so the deltas describe a *borrowed* balance: money
     * arriving raises it, money leaving brings it down. Lending runs the other
     * way and the caller flips it — see `LoanRepository`.
     */
    @Query(
        """
        SELECT occurred_on,
               CASE
                 WHEN direction = 'IN' THEN amount_minor
                 -- Money spent straight from a card. It is the one thing that
                 -- goes *out* and still puts the balance up, so it cannot take
                 -- the sign the direction would give it. Told by its shape and
                 -- nothing else — see `LoanLedger.kindOf`; `is_adjustment = 0`
                 -- is what keeps money lent on an existing arrangement, which
                 -- is also OUT with no account, out of this branch.
                 WHEN loan_part IS NULL AND account_id IS NULL
                   AND series_id IS NULL AND is_adjustment = 0 THEN amount_minor
                 ELSE -amount_minor
               END AS delta
        FROM money_entry
        WHERE (loan_id = :loanId OR series_id = :seriesId)
          AND deletedAt IS NULL
          AND (loan_part IS NULL OR loan_part = 'PRINCIPAL')
        ORDER BY occurred_on ASC
        """
    )
    suspend fun balanceChanges(loanId: String, seriesId: String?): List<BalanceChangeRow>

    /**
     * Tombstones everything a loan put in the timeline, following both links the
     * way [movements] does: the instalments its own rule generated, and the rows
     * that name the loan directly.
     *
     * A loan being deleted is the user saying it should never have been there,
     * and the app wrote most of these rows itself from its schedule. Left
     * behind, they are payments to a debt that cannot be opened, still moving an
     * account balance for a loan the user has thrown away — and the only way to
     * be rid of them was to find each one in the timeline and swipe it.
     *
     * Tombstoned rather than erased, like every other delete here, so a backup
     * taken on another device does not bring them all back.
     */
    @Query(
        """
        UPDATE money_entry SET deletedAt = :now, updatedAt = :now
        WHERE (loan_id = :loanId OR series_id = :seriesId) AND deletedAt IS NULL
        """
    )
    suspend fun softDeleteEntries(loanId: String, seriesId: String?, now: Long)

    @Upsert
    suspend fun upsert(loan: LoanEntity)

    @Query("UPDATE loan SET is_closed = :closed, updatedAt = :now WHERE id = :id")
    suspend fun setClosed(id: String, closed: Boolean, now: Long)

    /**
     * Forgets an account a debt was paid from or disbursed into.
     *
     * The debt itself survives — it is owed to a bank, not to an account — but a
     * form offering an account that has been deleted would be offering to pay
     * from nowhere.
     */
    @Query(
        """
        UPDATE loan SET
            pay_from_account_id =
                CASE WHEN pay_from_account_id = :accountId THEN NULL
                     ELSE pay_from_account_id END,
            disbursed_account_id =
                CASE WHEN disbursed_account_id = :accountId THEN NULL
                     ELSE disbursed_account_id END,
            updatedAt = :now
        WHERE pay_from_account_id = :accountId OR disbursed_account_id = :accountId
        """
    )
    suspend fun detachAccount(accountId: String, now: Long)

    @Query("UPDATE loan SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("SELECT * FROM loan")
    suspend fun dumpAll(): List<LoanEntity>
}
