package com.mywallet.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.mywallet.data.db.MyWalletDatabase
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.LoanDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.dao.RateChangeDao
import com.mywallet.data.db.dao.RecurringSeriesDao
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.InstalmentStyle
import com.mywallet.data.db.entity.LoanDirection
import com.mywallet.data.db.entity.LoanEntity
import com.mywallet.data.db.entity.LoanKind
import com.mywallet.data.db.entity.LoanPart
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.db.entity.RateChangeEntity
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.db.entity.RecurringSeriesEntity
import com.mywallet.data.repo.Clock
import com.mywallet.data.settings.SettingsStore
import com.mywallet.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-disk backup format.
 *
 * Deliberately JSON rather than a copy of the SQLite file: a `.db` is opaque,
 * version-locked to the app's schema, and useless to the user. JSON can be
 * opened, read, and salvaged by hand years later — which is the whole point of
 * a backup for someone's financial history.
 */
@Serializable
data class BackupFile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersion: String,
    val exportedAt: Long,
    val entries: List<BackupEntry>,
    /** Added in format 2. Absent in older files, which is why it defaults. */
    val accounts: List<BackupAccount> = emptyList(),
    /**
     * The debts, the repeating rules and every rate move — added later, and
     * therefore optional like everything else here.
     *
     * They were missing for a long time, and their absence was the worst thing
     * about this format: a restored phone came back with every entry and every
     * account and no loans at all, so the payments were there, the debts they had
     * paid were not, and nothing on the accounts page could be reconciled. A rule
     * was the same story from the other end — the occurrences it had already
     * written came back as ordinary rows and nothing went on producing the next
     * one, so the salary simply stopped.
     */
    val loans: List<BackupLoan> = emptyList(),
    val rules: List<BackupRule> = emptyList(),
    val rateChanges: List<BackupRateChange> = emptyList(),
) {
    companion object {
        /**
         * 1 → 2 added accounts and per-entry currency. Version 1 files still
         * restore: the new entry fields default to a 1:1 NPR conversion, which
         * is exactly what those entries were.
         *
         * Deliberately still 2 after the loans, rules, rate changes and the rest
         * of a holding's terms were added. Everything new is an optional field
         * with a default, and older builds read this file with
         * `ignoreUnknownKeys`, so they lose the new parts rather than refusing the
         * whole file. Bumping it would make every older install reject a backup
         * it can still read most of — which is the opposite of what a backup is
         * for. It goes up only when a file genuinely cannot be read by them.
         */
        const val CURRENT_FORMAT_VERSION = 2
    }
}

@Serializable
data class BackupAccount(
    val id: String,
    val name: String,
    val kind: String,
    val currencyCode: String,
    /**
     * Bank the account sits under. Added later than the rest of this class and
     * therefore optional: a file written before it existed simply has no
     * grouping to restore, which is what those accounts were.
     */
    val institution: String? = null,
    val openingBalanceMinor: Long = 0,
    /**
     * What the bank pays on it. Optional for the same reason [institution] is:
     * a file written before rates existed simply has none to restore, and an
     * account with no rate earns nothing — which is what those accounts did.
     */
    val annualRate: Double? = null,
    val colorArgb: Int,
    /**
     * Everything that makes a holding more than a name and a balance: how long a
     * deposit is locked away for, what a policy pays out, what a goal is aimed
     * at, and which rule pays into either.
     *
     * All optional, because a savings account has none of them — and because
     * files written before they existed have none either. Their absence used to
     * be silent and expensive: a restored fixed deposit came back as a holding
     * with no term and no maturity date, a policy with no premium and nothing to
     * pay out, and a goal with a progress bar measured against nothing.
     */
    val showInDisplayCurrency: Boolean = true,
    val interestPostedThrough: Long? = null,
    /**
     * How often the bank credits this account's interest. Optional, and null on
     * a file written while that was one setting for the whole app — those
     * accounts restore onto the default, which is what the setting's own default
     * was.
     */
    val interestPayoutMonths: Int? = null,
    /**
     * Whether the bank cuts this account's interest periods into Nepali months.
     *
     * Defaults to false, so a file from an older build restores the periods it
     * was written with — the English calendar, which is what every account on such a
     * phone was credited on.
     */
    val interestInBs: Boolean = false,
    val depositStartedOn: Long? = null,
    val depositTermMonths: Int? = null,
    val maturesIntoAccountId: String? = null,
    val maturityAmountMinor: Long? = null,
    val premiumMinor: Long? = null,
    val premiumEveryMonths: Int? = null,
    val premiumSeriesId: String? = null,
    /**
     * Which calendar this plan's schedule counts in. Optional with a default, so
     * a file from an older build restores as Gregorian — which is what that
     * build was doing.
     */
    val planRecurInBs: Boolean = false,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * One debt, whichever way round it runs.
 *
 * Every column, including the ones that look derivable. `principal_minor` stops
 * meaning "the sum borrowed" the moment a lump sum re-bases the loan, `started_on`
 * is the day that happened, and `carried_interest_minor` is the interest those
 * days had already run — none of the three can be worked out again from anything
 * else in the file, so a backup that dropped them would restore a debt with the
 * right name and the wrong balance.
 */
@Serializable
data class BackupLoan(
    val id: String,
    val name: String,
    val kind: String,
    val lender: String? = null,
    val loanDirection: String,
    val instalmentStyle: String,
    val principalMinor: Long,
    val creditLimitMinor: Long? = null,
    val currencyCode: String = "NPR",
    val annualRate: Double? = null,
    val termMonths: Int? = null,
    val paymentEveryMonths: Int = 1,
    val emiMinor: Long? = null,
    val emiStartsOn: Long? = null,
    val disbursedOn: Long? = null,
    /**
     * The day a facility was approved. Optional, and null on every file written
     * before it existed — which is the same "the app was never told" a card from
     * before the column carries, and leaves it with no expiry rather than one
     * guessed from a date that means something else.
     */
    val openedOn: Long? = null,
    val carriedInterestMinor: Long = 0,
    /**
     * Which calendar this debt's instalments count months in.
     *
     * Defaults to false, so a file written by an older build restores the
     * schedule it was written with — Gregorian, which is what every loan on such
     * a phone was on.
     */
    val recurInBs: Boolean = false,
    val dueOn: Long? = null,
    val payFromAccountId: String? = null,
    val disbursedAccountId: String? = null,
    /**
     * The sum ever advanced. Optional, and null on every file written before it
     * existed — which is the same "no honest answer" a loan from before the
     * column carries, so an older backup restores exactly as it reads today.
     */
    val advancedMinor: Long? = null,
    val seriesId: String? = null,
    val startedOn: Long,
    val showInDisplayCurrency: Boolean = true,
    val isClosed: Boolean = false,
    /**
     * The colour a card is drawn in. Optional with a null default, like every
     * field added since the format was first written: an older build reads the
     * file without it and loses only the colour.
     */
    val colorArgb: Int? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * One repeating rule — a salary, an instalment, a premium.
 *
 * `materialisedThrough` is carried like any other column and not reset. It is the
 * boundary between the occurrences that already exist as real rows and the ones
 * still to be computed, and a restore that put it back at nothing would generate
 * a second copy of every payment the rule has ever made.
 */
@Serializable
data class BackupRule(
    val id: String,
    val amountMinor: Long,
    val currencyCode: String = "NPR",
    val direction: String,
    val interval: String,
    val intervalMonths: Int? = null,
    val startOn: Long,
    val endOn: Long? = null,
    val materialisedThrough: Long? = null,
    val accountId: String? = null,
    val transferToAccountId: String? = null,
    val isAdjustment: Boolean = false,
    /**
     * Which calendar this rule counts months in. Optional with a default, so a
     * file written by an older build still restores — and restores as Gregorian,
     * which is what that build was doing.
     */
    val recurInBs: Boolean = false,
    /**
     * Whether this rule follows whichever calendar is set — the opt-in.
     *
     * Defaults false, so a file from an older build restores the rule pinned to
     * the calendar it was generating in rather than starting to follow the
     * setting on the phone it lands on.
     */
    val usesSelectedCalendar: Boolean = false,
    val note: String? = null,
    val isPaused: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * One move of a rate, with the day it took effect.
 *
 * Not derivable from anything: a holding's own `annual_rate` column is the rate it
 * *opened* at, and every figure the bank has charged since lives here. Without
 * these rows a restored loan is recomputed at the rate it was taken out at, and
 * a savings account is credited at a figure the bank stopped paying years ago.
 */
@Serializable
data class BackupRateChange(
    val id: String,
    val accountId: String? = null,
    val loanId: String? = null,
    val annualRate: Double,
    val effectiveFrom: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class BackupEntry(
    val id: String,
    val amountMinor: Long,
    val currencyCode: String = "NPR",
    val baseAmountMinor: Long? = null,
    val rateToBase: Double = 1.0,
    val baseCurrencyCode: String = "NPR",
    val accountId: String? = null,
    val isAdjustment: Boolean = false,
    val direction: String,
    val occurredOn: Long,
    /** The loan this payment went towards, and which half of it. Both optional. */
    val loanId: String? = null,
    val loanPart: String? = null,
    /** Ties the two rows of a transfer together. */
    val transferId: String? = null,
    /**
     * The rule that wrote it, and whether it is still the rule's own words.
     *
     * Both optional and both added late. Without the rule's id a restored
     * instalment is an ordinary payment: the loan cannot count it, so the debt
     * comes back reading a year behind itself, and the rule believes the
     * occurrence was never written and produces it again.
     */
    val seriesId: String? = null,
    val status: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * A file this build cannot read at all.
 *
 * A type rather than a message, because the message the user sees has to be in
 * their own language and nothing down here can know what that is. Every other
 * failure in this class is a device or a permission problem the user can do
 * nothing specific about, and the screen says so in one sentence; this one has an
 * answer — update the app — so it has to be told apart.
 */
class BackupTooNew : IOException()

/** What a restore did, so the UI can tell the user something specific. */
data class RestoreReport(
    val entriesAdded: Int,
    val entriesUpdated: Int,
    val accountsAdded: Int = 0,
    /** Holdings, debts and rules the file was newer about than this phone. */
    val holdingsRestored: Int = 0,
) {
    val total: Int
        get() = entriesAdded + entriesUpdated + accountsAdded + holdingsRestored
}

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entryDao: MoneyEntryDao,
    private val accountDao: AccountDao,
    private val loanDao: LoanDao,
    private val seriesDao: RecurringSeriesDao,
    private val rateChangeDao: RateChangeDao,
    // For the transaction the merge runs in. The DAOs do the work; this is only
    // what makes a failed restore leave nothing behind.
    private val database: MyWalletDatabase,
    private val settings: SettingsStore,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true // so an older app can still read a newer file
        encodeDefaults = true
    }

    /** Writes the whole database to [target], overwriting whatever is there. */
    suspend fun exportTo(target: Uri): Result<Unit> = withContext(io) {
        runCatching {
            val payload = json.encodeToString(buildBackup())
            // "wt" truncates. Without it, writing a smaller backup over a bigger
            // one leaves trailing bytes and produces invalid JSON.
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(payload.toByteArray()) }
                ?: throw IOException("Could not open $target for writing")
            settings.setLastBackupAt(clock.nowMillis())
        }
    }

    /**
     * Writes a timestamped backup into the folder the user picked, then trims
     * old files so the folder does not grow without limit.
     */
    suspend fun writeAutomaticBackup(folderUri: Uri): Result<Unit> = withContext(io) {
        runCatching {
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw IOException("Backup folder is no longer reachable")
            if (!folder.canWrite()) throw IOException("No permission to write to the backup folder")

            val name = "mywallet-${timestampForFilename()}.json"
            val file = folder.createFile("application/json", name)
                ?: throw IOException("Could not create $name")

            val payload = json.encodeToString(buildBackup())
            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.use { it.write(payload.toByteArray()) }
                ?: throw IOException("Could not open the new backup for writing")

            pruneOldBackups(folder)
            settings.setLastBackupAt(clock.nowMillis())
        }
    }

    /**
     * Merges a backup into the current database rather than replacing it.
     *
     * A row is only overwritten when the backup's copy is strictly newer, so
     * restoring an old file cannot silently undo recent work. Tombstones are
     * honoured — on both sides — so something deleted on either phone stays
     * deleted.
     *
     * Two things about the order are load-bearing, and both were learned the hard
     * way when a restore of a file this app had just written failed outright:
     *
     *  - **Accounts go in before anything that points at them.** The references
     *    are real foreign keys, so a rule carrying an account id that is not in
     *    the table yet is refused — and one refused row used to abandon the
     *    whole restore.
     *  - **Every reference is resolved before it is used.** A hand-trimmed file
     *    can name an account it does not contain, and that is dropped to null
     *    rather than allowed to fail the insert: an entry that has lost which
     *    holding it moved through is a small loss, an entry that never arrived
     *    is not.
     *
     * All of it in one transaction. A merge that stops half way through leaves a
     * database with some of the file in it and no way to tell which part, and the
     * user has just been told it did not work.
     */
    suspend fun restoreFrom(source: Uri): Result<RestoreReport> = withContext(io) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use { it.readBytes().decodeToString() }
                ?: throw IOException("Could not open the backup file")

            val backup = json.decodeFromString<BackupFile>(text)
            if (backup.formatVersion > BackupFile.CURRENT_FORMAT_VERSION) throw BackupTooNew()

            database.withTransaction { merge(backup) }
        }
    }

    private suspend fun merge(backup: BackupFile): RestoreReport {
        var accountsAdded = 0
        var entriesAdded = 0
        var entriesUpdated = 0
        var holdingsRestored = 0

        // Accounts first, because the entries and the rules point at them. By the same
        // newest-wins rule as everything else: this used to be "add if missing",
        // so a holding renamed, recoloured or given its terms on another phone
        // never arrived — the id was already here, and the newer row was dropped
        // on the floor.
        for (incoming in backup.accounts) {
            val existing = accountDao.findAnyById(incoming.id)
            when {
                existing == null -> {
                    foldSeededHolding(incoming)
                    accountDao.upsert(incoming.toEntity())
                    accountsAdded++
                }
                incoming.updatedAt > existing.updatedAt -> {
                    accountDao.upsert(incoming.toEntity())
                    holdingsRestored++
                }
            }
        }

        /** An account that exists here, or null — see the note on order above. */
        suspend fun accountOrNull(accountId: String?): String? =
            accountId?.takeIf { accountDao.findAnyById(it) != null }

        // The rules, before the debts that name them and the entries they wrote.
        for (incoming in backup.rules) {
            val existing = seriesDao.findAnyById(incoming.id)
            if (existing != null && incoming.updatedAt <= existing.updatedAt) continue
            seriesDao.upsert(
                incoming.toEntity().copy(
                    accountId = accountOrNull(incoming.accountId),
                    transferToAccountId = accountOrNull(incoming.transferToAccountId),
                )
            )
            holdingsRestored++
        }

        for (incoming in backup.loans) {
            val existing = loanDao.findAnyById(incoming.id)
            if (existing != null && incoming.updatedAt <= existing.updatedAt) continue
            loanDao.upsert(
                incoming.toEntity().copy(
                    payFromAccountId = accountOrNull(incoming.payFromAccountId),
                    disbursedAccountId = accountOrNull(incoming.disbursedAccountId),
                )
            )
            holdingsRestored++
        }

        for (incoming in backup.rateChanges) {
            // A rate belongs to a holding and cascades with it, so one whose
            // holding is not in the file has nothing to describe.
            val holdingKnown = incoming.accountId?.let { accountDao.findAnyById(it) != null }
                ?: incoming.loanId?.let { loanDao.findAnyById(it) != null }
                ?: false
            if (!holdingKnown) continue
            val existing = rateChangeDao.findAnyById(incoming.id)
            if (existing != null && incoming.updatedAt <= existing.updatedAt) continue
            rateChangeDao.upsert(incoming.toEntity())
            holdingsRestored++
        }

        for (incoming in backup.entries) {
            val existing = entryDao.findAnyById(incoming.id)
            if (existing != null && incoming.updatedAt <= existing.updatedAt) continue
            entryDao.upsert(
                incoming.toEntity().copy(
                    accountId = accountOrNull(incoming.accountId),
                )
            )
            if (existing == null) entriesAdded++ else entriesUpdated++
        }

        return RestoreReport(
            entriesAdded = entriesAdded,
            entriesUpdated = entriesUpdated,
            accountsAdded = accountsAdded,
            holdingsRestored = holdingsRestored,
        )
    }

    /**
     * Puts aside a holding this phone made for itself that [incoming] replaces.
     *
     * The first run seeds one Cash account so there is somewhere to record the
     * user's first coffee. Restore a backup onto a reinstalled app and the file
     * brings its own Cash — same name, different id — so the accounts page ended
     * up with two, one holding every rupee of history and one holding nothing.
     * A fold by name, for a holding the user made and never used.
     *
     * Narrow on purpose. It matches only a holding of the same kind and name that
     * **nothing has ever touched** — no movement names it and it opens at zero —
     * which is the seeded account and almost nothing else. Two accounts a user
     * deliberately gave the same name are left alone, because at least one of them
     * has money in it.
     */
    private suspend fun foldSeededHolding(incoming: BackupAccount) {
        val kind = runCatching { AccountKind.valueOf(incoming.kind) }.getOrNull() ?: return
        val untouched = accountDao.activeNamed(incoming.name, kind).firstOrNull { local ->
            local.openingBalanceMinor == 0L && entryDao.countForAccount(local.id) == 0
        } ?: return
        accountDao.softDelete(untouched.id, clock.nowMillis())
    }

    /** Suggested filename for the "save a copy" picker. */
    fun suggestedFileName(): String = "mywallet-${timestampForFilename()}.json"

    private suspend fun buildBackup(): BackupFile = BackupFile(
        appVersion = com.mywallet.BuildConfig.VERSION_NAME,
        exportedAt = clock.nowMillis(),
        entries = entryDao.dumpAll().map { it.toBackup() },
        accounts = accountDao.dumpAll().map { it.toBackup() },
        loans = loanDao.dumpAll().map { it.toBackup() },
        rules = seriesDao.dumpAll().map { it.toBackup() },
        rateChanges = rateChangeDao.dumpAll().map { it.toBackup() },
    )

    /** Keeps the newest [KEEP_BACKUPS] automatic files and deletes the rest. */
    private fun pruneOldBackups(folder: DocumentFile) {
        val ours = folder.listFiles()
            .filter { it.isFile && it.name?.startsWith("mywallet-") == true }
            .sortedByDescending { it.lastModified() }
        ours.drop(KEEP_BACKUPS).forEach { runCatching { it.delete() } }
    }

    private fun timestampForFilename(): String =
        Instant.ofEpochMilli(clock.nowMillis())
            .atZone(ZoneId.systemDefault())
            .format(FILENAME_FORMAT)

    private companion object {
        const val KEEP_BACKUPS = 10
        val FILENAME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}

private fun AccountEntity.toBackup() = BackupAccount(
    id = id,
    name = name,
    kind = kind.name,
    currencyCode = currencyCode,
    institution = institution,
    openingBalanceMinor = openingBalanceMinor,
    annualRate = annualRate,
    colorArgb = colorArgb,
    showInDisplayCurrency = showInDisplayCurrency,
    interestPostedThrough = interestPostedThrough,
    interestPayoutMonths = interestPayoutMonths,
    interestInBs = interestInBs,
    depositStartedOn = depositStartedOn,
    depositTermMonths = depositTermMonths,
    maturesIntoAccountId = maturesIntoAccountId,
    maturityAmountMinor = maturityAmountMinor,
    premiumMinor = premiumMinor,
    premiumEveryMonths = premiumEveryMonths,
    premiumSeriesId = premiumSeriesId,
    planRecurInBs = planRecurInBs,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun BackupAccount.toEntity() = AccountEntity(
    id = id,
    name = name,
    // A file written before savings and current existed says "BANK"; those
    // accounts were savings in all but name.
    kind = runCatching { AccountKind.valueOf(kind) }.getOrDefault(AccountKind.SAVINGS),
    currencyCode = currencyCode,
    institution = institution,
    openingBalanceMinor = openingBalanceMinor,
    annualRate = annualRate,
    colorArgb = colorArgb,
    showInDisplayCurrency = showInDisplayCurrency,
    interestPostedThrough = interestPostedThrough,
    interestPayoutMonths = interestPayoutMonths,
    interestInBs = interestInBs,
    depositStartedOn = depositStartedOn,
    depositTermMonths = depositTermMonths,
    maturesIntoAccountId = maturesIntoAccountId,
    maturityAmountMinor = maturityAmountMinor,
    premiumMinor = premiumMinor,
    premiumEveryMonths = premiumEveryMonths,
    premiumSeriesId = premiumSeriesId,
    planRecurInBs = planRecurInBs,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun MoneyEntryEntity.toBackup() = BackupEntry(
    id = id,
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    baseAmountMinor = baseAmountMinor,
    rateToBase = rateToBase,
    baseCurrencyCode = baseCurrencyCode,
    accountId = accountId,
    isAdjustment = isAdjustment,
    direction = direction.name,
    occurredOn = occurredOn,
    loanId = loanId,
    loanPart = loanPart?.name,
    transferId = transferId,
    seriesId = seriesId,
    status = status.name,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun BackupEntry.toEntity() = MoneyEntryEntity(
    id = id,
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    // Format-1 files carry no converted figure; those entries were already in
    // the display currency, so the amount is its own conversion.
    baseAmountMinor = baseAmountMinor ?: amountMinor,
    rateToBase = rateToBase,
    baseCurrencyCode = baseCurrencyCode,
    accountId = accountId,
    isAdjustment = isAdjustment,
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.OUT),
    occurredOn = occurredOn,
    loanId = loanId,
    loanPart = loanPart?.let { runCatching { LoanPart.valueOf(it) }.getOrNull() },
    transferId = transferId,
    seriesId = seriesId,
    // A file written before the column existed says nothing, and every row in it
    // was one the user had confirmed by the standards of the build that wrote it.
    status = status
        ?.let { runCatching { EntryStatus.valueOf(it) }.getOrNull() }
        ?: EntryStatus.CONFIRMED,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun LoanEntity.toBackup() = BackupLoan(
    id = id,
    name = name,
    kind = kind.name,
    lender = lender,
    loanDirection = loanDirection.name,
    instalmentStyle = instalmentStyle.name,
    principalMinor = principalMinor,
    creditLimitMinor = creditLimitMinor,
    currencyCode = currencyCode,
    annualRate = annualRate,
    termMonths = termMonths,
    paymentEveryMonths = paymentEveryMonths,
    emiMinor = emiMinor,
    emiStartsOn = emiStartsOn,
    disbursedOn = disbursedOn,
    openedOn = openedOn,
    carriedInterestMinor = carriedInterestMinor,
    recurInBs = recurInBs,
    dueOn = dueOn,
    payFromAccountId = payFromAccountId,
    disbursedAccountId = disbursedAccountId,
    advancedMinor = advancedMinor,
    seriesId = seriesId,
    startedOn = startedOn,
    showInDisplayCurrency = showInDisplayCurrency,
    isClosed = isClosed,
    colorArgb = colorArgb,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun BackupLoan.toEntity() = LoanEntity(
    id = id,
    name = name,
    // An unreadable kind falls back to the bank's ordinary loan rather than
    // dropping the debt: one restored under the wrong heading can be corrected,
    // one that never arrived cannot.
    kind = runCatching { LoanKind.valueOf(kind) }.getOrDefault(LoanKind.BANK),
    lender = lender,
    loanDirection = runCatching { LoanDirection.valueOf(loanDirection) }
        .getOrDefault(LoanDirection.BORROWED),
    instalmentStyle = runCatching { InstalmentStyle.valueOf(instalmentStyle) }
        .getOrDefault(InstalmentStyle.LEVEL_EMI),
    principalMinor = principalMinor,
    creditLimitMinor = creditLimitMinor,
    currencyCode = currencyCode,
    annualRate = annualRate,
    termMonths = termMonths,
    paymentEveryMonths = paymentEveryMonths,
    emiMinor = emiMinor,
    emiStartsOn = emiStartsOn,
    disbursedOn = disbursedOn,
    openedOn = openedOn,
    carriedInterestMinor = carriedInterestMinor,
    recurInBs = recurInBs,
    dueOn = dueOn,
    payFromAccountId = payFromAccountId,
    disbursedAccountId = disbursedAccountId,
    advancedMinor = advancedMinor,
    seriesId = seriesId,
    startedOn = startedOn,
    showInDisplayCurrency = showInDisplayCurrency,
    isClosed = isClosed,
    colorArgb = colorArgb,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun RecurringSeriesEntity.toBackup() = BackupRule(
    id = id,
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    direction = direction.name,
    interval = interval.name,
    intervalMonths = intervalMonths,
    startOn = startOn,
    endOn = endOn,
    materialisedThrough = materialisedThrough,
    accountId = accountId,
    transferToAccountId = transferToAccountId,
    isAdjustment = isAdjustment,
    recurInBs = recurInBs,
    usesSelectedCalendar = usesSelectedCalendar,
    note = note,
    isPaused = isPaused,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun BackupRule.toEntity() = RecurringSeriesEntity(
    id = id,
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.OUT),
    interval = runCatching { RecurrenceInterval.valueOf(interval) }
        .getOrDefault(RecurrenceInterval.MONTHLY),
    intervalMonths = intervalMonths,
    startOn = startOn,
    endOn = endOn,
    materialisedThrough = materialisedThrough,
    accountId = accountId,
    transferToAccountId = transferToAccountId,
    isAdjustment = isAdjustment,
    recurInBs = recurInBs,
    usesSelectedCalendar = usesSelectedCalendar,
    note = note,
    isPaused = isPaused,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun RateChangeEntity.toBackup() = BackupRateChange(
    id = id,
    accountId = accountId,
    loanId = loanId,
    annualRate = annualRate,
    effectiveFrom = effectiveFrom,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun BackupRateChange.toEntity() = RateChangeEntity(
    id = id,
    accountId = accountId,
    loanId = loanId,
    annualRate = annualRate,
    effectiveFrom = effectiveFrom,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
