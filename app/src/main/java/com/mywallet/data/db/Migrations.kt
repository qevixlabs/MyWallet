package com.mywallet.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history.
 *
 * Migrations are written by hand and never destructive: this database is the
 * user's only copy of their financial history, so a missing migration must fail
 * loudly in development rather than wipe data on someone's phone.
 */
object Migrations {

    /**
     * v1 → v2: accounts and per-entry currency.
     *
     * Existing rows were all in the user's single display currency, so they are
     * backfilled with a rate of 1.0 against that currency. That keeps every
     * historical total byte-identical to what the user saw before upgrading.
     *
     * The backfill writes 'NPR' because that is the shipped default and no
     * released build could store anything else. Rate 1.0 with matching currency
     * and base codes is self-consistent regardless, so the figures stay right
     * even if the label is not the currency a given user was actually using.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `account` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `opening_balance_minor` INTEGER NOT NULL DEFAULT 0,
                    `color_argb` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL DEFAULT 0,
                    `is_archived` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_deletedAt` ON `account` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_sort_order` ON `account` (`sort_order`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `fx_rate` (
                    `base_code` TEXT NOT NULL,
                    `quote_code` TEXT NOT NULL,
                    `rate` REAL NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`base_code`, `quote_code`)
                )
                """.trimIndent()
            )

            // SQLite cannot add a column with a foreign key, so money_entry is
            // rebuilt. Column order and constraints must match what Room
            // generates for v2 exactly, or validation fails at open time.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `money_entry_new` (
                    `id` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `currency_code` TEXT NOT NULL DEFAULT 'NPR',
                    `base_amount_minor` INTEGER NOT NULL DEFAULT 0,
                    `rate_to_base` REAL NOT NULL DEFAULT 1.0,
                    `base_currency_code` TEXT NOT NULL DEFAULT 'NPR',
                    `direction` TEXT NOT NULL,
                    `occurred_on` INTEGER NOT NULL,
                    `label_id` TEXT,
                    `account_id` TEXT,
                    `is_adjustment` INTEGER NOT NULL DEFAULT 0,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`label_id`) REFERENCES `label`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO `money_entry_new` (
                    `id`, `amount_minor`, `currency_code`, `base_amount_minor`,
                    `rate_to_base`, `base_currency_code`, `direction`, `occurred_on`,
                    `label_id`, `account_id`, `is_adjustment`, `note`,
                    `createdAt`, `updatedAt`, `deletedAt`
                )
                SELECT
                    `id`, `amount_minor`, 'NPR', `amount_minor`,
                    1.0, 'NPR', `direction`, `occurred_on`,
                    `label_id`, NULL, 0, `note`,
                    `createdAt`, `updatedAt`, `deletedAt`
                FROM `money_entry`
                """.trimIndent()
            )

            db.execSQL("DROP TABLE `money_entry`")
            db.execSQL("ALTER TABLE `money_entry_new` RENAME TO `money_entry`")

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_occurred_on` ON `money_entry` (`occurred_on`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_label_id` ON `money_entry` (`label_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_account_id` ON `money_entry` (`account_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_deletedAt` ON `money_entry` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_direction_occurred_on` ON `money_entry` (`direction`, `occurred_on`)")
        }
    }

    /**
     * v2 → v3: repeating series, and expected-vs-confirmed entries.
     *
     * Existing entries are all CONFIRMED — they were entered by hand, which is
     * the definition of confirmed. New columns are added rather than rebuilding
     * the table, since neither carries a foreign key.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `recurring_series` (
                    `id` TEXT NOT NULL,
                    `amount_minor` INTEGER NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `interval` TEXT NOT NULL,
                    `start_on` INTEGER NOT NULL,
                    `end_on` INTEGER,
                    `materialised_through` INTEGER,
                    `label_id` TEXT,
                    `account_id` TEXT,
                    `note` TEXT,
                    `is_paused` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`label_id`) REFERENCES `label`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_deletedAt` ON `recurring_series` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_label_id` ON `recurring_series` (`label_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_account_id` ON `recurring_series` (`account_id`)")

            db.execSQL("ALTER TABLE `money_entry` ADD COLUMN `series_id` TEXT")
            db.execSQL("ALTER TABLE `money_entry` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'CONFIRMED'")
        }
    }

    /** v3 → v4: loans, and an optional institution to group accounts by. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `institution` TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `loan` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `lender` TEXT,
                    `principal_minor` INTEGER NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `annual_rate` REAL,
                    `term_months` INTEGER,
                    `emi_minor` INTEGER,
                    `emi_starts_on` INTEGER,
                    `pay_from_account_id` TEXT,
                    `series_id` TEXT,
                    `started_on` INTEGER NOT NULL,
                    `is_closed` INTEGER NOT NULL DEFAULT 0,
                    `note` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`pay_from_account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_deletedAt` ON `loan` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_pay_from_account_id` ON `loan` (`pay_from_account_id`)")
        }
    }

    /**
     * v4 → v5: named bank account types, and payments tagged as principal or
     * interest.
     *
     * `BANK` became `SAVINGS` and `CURRENT`, which is the choice a bank actually
     * offers. Existing bank accounts become savings: it is the common case, and
     * the two behave identically in every calculation, so a wrong guess costs
     * the user one tap to correct and nothing else.
     *
     * The new entry columns take no foreign key, so plain `ADD COLUMN` is
     * enough — no table rebuild, and none of the risk that comes with one.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE `account` SET `kind` = 'SAVINGS' WHERE `kind` = 'BANK'")
            db.execSQL("ALTER TABLE `money_entry` ADD COLUMN `loan_id` TEXT")
            db.execSQL("ALTER TABLE `money_entry` ADD COLUMN `loan_part` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_loan_id` ON `money_entry` (`loan_id`)")
        }
    }

    /**
     * v5 → v6: money lent out, and transfers between accounts.
     *
     * Every existing loan is money the user borrowed — the app could not record
     * anything else until now — so the new direction column defaults to that and
     * no row needs rewriting.
     *
     * Transfers need two things: an id shared by the pair of entries that make up
     * one movement, and, on a repeating rule, the account the money goes to plus
     * a flag saying its occurrences are not income or spending.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `loan_direction` TEXT NOT NULL DEFAULT 'BORROWED'"
            )
            db.execSQL("ALTER TABLE `money_entry` ADD COLUMN `transfer_id` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_transfer_id` ON `money_entry` (`transfer_id`)")
            db.execSQL("ALTER TABLE `recurring_series` ADD COLUMN `transfer_to_account_id` TEXT")
            db.execSQL(
                "ALTER TABLE `recurring_series` ADD COLUMN `is_adjustment` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v6 → v7: how a loan's instalments are made up.
     *
     * Every loan entered so far was quoted as a level instalment, which is the
     * only thing the app could compute, so the default describes them correctly
     * and no row needs rewriting.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `instalment_style` TEXT NOT NULL DEFAULT 'LEVEL_EMI'"
            )
        }
    }

    /**
     * v7 → v8: which currency an account's money is shown in.
     *
     * Off for everything that already exists, which keeps every screen showing
     * exactly what it showed before the upgrade — an account in the display
     * currency is unaffected either way.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `account` ADD COLUMN `show_in_display_currency` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /** v8 → v9: the day a loan with no instalments has to be settled by. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `due_on` INTEGER")
        }
    }

    /**
     * v9 → v10: how often a loan is paid, and which currency it is read in.
     *
     * Every loan entered so far was quoted monthly, which is all the app could
     * schedule, so 1 describes them exactly and no schedule shifts underneath a
     * user on upgrade. The display flag starts off for the same reason it does
     * on accounts: the screens must look identical the moment after the upgrade.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `payment_every_months` INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `show_in_display_currency` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v10 → v11: an overdraft's approved limit.
     *
     * Null for a term loan, which has no ceiling to record. An overdraft entered
     * before the column existed had only one figure, and it is backfilled as the
     * limit while the drawn balance is left exactly where it was — so the
     * facility reads as fully drawn until the user raises the limit.
     *
     * That is the conservative reading of an ambiguous old field, and the only
     * safe one: assuming the opposite would set the balance to zero and quietly
     * erase a debt the user really has.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `credit_limit_minor` INTEGER")
            db.execSQL(
                "UPDATE `loan` SET `credit_limit_minor` = `principal_minor` " +
                    "WHERE `kind` = 'OVERDRAFT'"
            )
        }
    }

    /**
     * v11 → v12: the day a loan's money actually arrived.
     *
     * Left null on every existing loan, deliberately. The column's whole purpose
     * is to date the start of interest, and from it the app works out whether
     * the bank's first recovery falls short of a full period — a broken period,
     * charged in days. Backfilling it with `started_on` would be a guess, and a
     * wrong guess would invent a charge on a loan the user has already
     * reconciled. Null says "not recorded", and nothing is inferred from it.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `disbursed_on` INTEGER")
        }
    }

    /**
     * v12 → v13: interest a lump sum left behind, waiting for the next instalment.
     *
     * Zero everywhere on upgrade, which is the truth for every loan already on
     * file: they were re-based under the old arithmetic, where the days between
     * the last instalment and the payment were never counted at all. Inventing a
     * figure for them now would move a balance the user has already reconciled
     * against their statement, and there is nothing on file to compute it from —
     * the balance it would have been charged on no longer exists.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `carried_interest_minor` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v13 → v14: what a rate has been, rather than only what it is now.
     *
     * A bank rate belongs to a period. A floating loan reviewed quarterly is
     * charged at four rates a year and today's balance is the result of all of
     * them, so `rate_change` records each move with the day it took effect while
     * the holding's own column goes on meaning the rate it opened at. Nothing
     * changes for a holding with no rows: one rate, from the start, as before.
     *
     * Savings accounts gain a rate at all, and a watermark for how far their
     * interest has been worked out. Both null on upgrade — an account the user
     * has not told the app about earns nothing, which is exactly how every
     * account behaved yesterday.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `annual_rate` REAL")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `interest_posted_through` INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `rate_change` (" +
                    "`id` TEXT NOT NULL, " +
                    "`account_id` TEXT, " +
                    "`loan_id` TEXT, " +
                    "`annual_rate` REAL NOT NULL, " +
                    "`effective_from` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_rate_change_account_id` " +
                    "ON `rate_change` (`account_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_rate_change_loan_id` " +
                    "ON `rate_change` (`loan_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_rate_change_deletedAt` " +
                    "ON `rate_change` (`deletedAt`)"
            )
        }
    }

    /**
     * v14 → v15: lets go of accounts that back-dated instalments should never
     * have held.
     *
     * From v0.32.0 an occurrence dated before its rule was written names no
     * account, because it left a bank the app was not watching and whose balance
     * the user has since corrected by hand. That fixed what gets written from
     * now on and did nothing for what was already there — so a loan entered a
     * week earlier had left an account tens of thousands down for payments made
     * long before the app knew the loan existed.
     *
     * This is the same rule applied once to history. Only rows a *rule*
     * generated are touched: anything typed by hand is the user's own record of
     * money they watched leave, whatever its date. The amounts, the dates and
     * the link to the loan all stay exactly as they are — the schedule still
     * counts them, the statement still lists them — they simply stop moving a
     * balance they never really moved. `updatedAt` moves with them so a restore
     * from an older backup cannot quietly put the accounts back.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE money_entry
                SET account_id = NULL, updatedAt = CAST(strftime('%s','now') AS INTEGER) * 1000
                WHERE series_id IS NOT NULL
                  AND account_id IS NOT NULL
                  AND deletedAt IS NULL
                  AND occurred_on < (
                      SELECT s.createdAt / 86400000
                      FROM recurring_series s
                      WHERE s.id = money_entry.series_id
                  )
                """.trimIndent()
            )
        }
    }

    /**
     * v15 → v16: the broken-period charge lets go of its account too.
     *
     * v15 detached the back-dated *instalments* — everything a repeating rule
     * generated. It missed the one charge that is written directly rather than
     * generated: the interest for the days between a loan being disbursed and
     * the bank's first recovery. On a seven-year loan entered a year late that
     * single row is रू 10,984.93, and against a savings account holding
     * रू 10,000 it was the whole of the reported "balance is gone, and I have no
     * trace".
     *
     * Matched by the id the charge is written under, which is derived from the
     * loan's and belongs to nothing else — precise enough that no payment the
     * user made by hand can be caught by it.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE money_entry
                SET account_id = NULL, updatedAt = CAST(strftime('%s','now') AS INTEGER) * 1000
                WHERE id LIKE '%-broken-period'
                  AND account_id IS NOT NULL
                  AND deletedAt IS NULL
                  AND occurred_on < (
                      SELECT l.createdAt / 86400000
                      FROM loan l
                      WHERE l.id = money_entry.loan_id
                  )
                """.trimIndent()
            )
        }
    }

    /**
     * v16 → v17: money put away for a fixed term.
     *
     * Four columns, all null on upgrade, all null on every kind but a fixed
     * deposit — so nothing that exists today changes shape or behaviour. The day
     * the money went in is not among them: it is `matures_on` less
     * `deposit_term_months`, and storing it as well would give two columns the
     * chance to disagree about one fact.
     *
     * `AccountKind` gains a value rather than the table gaining a flag, because
     * a deposit differs from an account in what it *does* — it cannot be spent
     * from, its balance moves by a rule instead of by entries, and on one known
     * day the whole of it leaves. A boolean would have every one of those read
     * as an exception rather than as the thing itself.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `matures_on` INTEGER")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `deposit_term_months` INTEGER")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `interest_interval` TEXT")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `matures_into_account_id` TEXT")
        }
    }

    /**
     * v17 → v18: a deposit is described from the day it was made.
     *
     * v17 asked for the day it comes free and worked the start back from it,
     * which is the wrong way round: the fact the user has is the day they handed
     * the money over, and maturity falls out of that plus the agreed length.
     * `matures_on` therefore becomes `deposit_started_on`, converted rather than
     * reinterpreted — every existing row has its stored maturity walked back by
     * its own term, so the deposit it describes is unchanged.
     *
     * `interest_interval` goes with it. A deposit here now earns simple interest
     * over the whole term, which is what the bank's own calculator computes, and
     * a column recording how often something compounds is meaningless when
     * nothing does. Dropping it is deliberate: left behind it would be a stored
     * answer to a question the app no longer asks, and the next person to read
     * it would reasonably assume it still meant something.
     *
     * A rebuild rather than two ALTERs, because SQLite before 3.25 — which is
     * every device at this app's minSdk — cannot rename or drop a column.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `account_new` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`currency_code` TEXT NOT NULL, `institution` TEXT, " +
                    "`opening_balance_minor` INTEGER NOT NULL, `annual_rate` REAL, " +
                    "`interest_posted_through` INTEGER, `deposit_started_on` INTEGER, " +
                    "`deposit_term_months` INTEGER, `matures_into_account_id` TEXT, " +
                    "`color_argb` INTEGER NOT NULL, " +
                    "`show_in_display_currency` INTEGER NOT NULL DEFAULT 0, " +
                    "`sort_order` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, PRIMARY KEY(`id`))"
            )
            // Epoch days out to a date, back the agreed number of months, and
            // into epoch days again. Month arithmetic is the only way to undo
            // month arithmetic: a year is not a fixed number of days, and
            // subtracting 365 would move a deposit made on the 29th of Falgun.
            db.execSQL(
                """
                INSERT INTO account_new
                SELECT id, name, kind, currency_code, institution,
                       opening_balance_minor, annual_rate, interest_posted_through,
                       CASE
                         WHEN matures_on IS NOT NULL AND deposit_term_months IS NOT NULL
                         THEN CAST(
                                julianday(
                                  date(matures_on * 86400, 'unixepoch',
                                       '-' || deposit_term_months || ' months')
                                ) - 2440587.5 AS INTEGER)
                         ELSE NULL
                       END,
                       deposit_term_months, matures_into_account_id, color_argb,
                       show_in_display_currency, sort_order, is_archived,
                       createdAt, updatedAt, deletedAt
                FROM account
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `account`")
            db.execSQL("ALTER TABLE `account_new` RENAME TO `account`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_deletedAt` ON `account` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_sort_order` ON `account` (`sort_order`)")
        }
    }

    /**
     * v18 → v19: the account money between people actually passed through.
     *
     * One nullable column, so every loan on file keeps saying what it says now:
     * null means the app was never told, which is the honest answer for a debt
     * recorded before it asked. Nothing is back-filled from
     * `pay_from_account_id` — that is the account repayments go *through*, and
     * on money between people it is very often not the one the money arrived in.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `disbursed_account_id` TEXT")
        }
    }

    /**
     * v19 → v20: how many months a rule steps, when no named interval says it.
     *
     * One nullable column, and null is what every rule already on file means:
     * keep stepping by the interval you were written with. Nothing is back-filled
     * from a loan's `payment_every_months` — the rules that exist were all
     * generated at one of the four named gaps, so the two already agree, and
     * writing a number over them could only introduce a disagreement.
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recurring_series` ADD COLUMN `interval_months` INTEGER")
        }
    }

    /**
     * v20 → v21: an insurance policy — what it pays out, and what it costs.
     *
     * Four nullable columns, all null on every row already on file, which is
     * what every one of them is: nothing but a policy has a premium. The three
     * columns a policy shares with a fixed deposit — the day it started, how
     * long it runs, and where the money lands at the end — are the ones that
     * already exist, because they are the same three facts.
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `maturity_amount_minor` INTEGER")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `premium_minor` INTEGER")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `premium_every_months` INTEGER")
            db.execSQL("ALTER TABLE `account` ADD COLUMN `premium_series_id` TEXT")
        }
    }

    /**
     * Which calendar a repeating rule counts its months in.
     *
     * Defaults to 0 — Gregorian — so every rule already on a phone keeps the
     * dates it has. An upgrade that moved a standing payment would be the app
     * rewriting a schedule the user never touched.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `recurring_series` ADD COLUMN `recur_in_bs` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * Which calendar a policy's or a goal's schedule counts in — the account's
     * copy of the rule's own flag, so the dates it draws and the dates the rule
     * produces cannot disagree. Defaults to Gregorian, like the rule.
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `account` ADD COLUMN `plan_recur_in_bs` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * How often the bank credits this account's interest, in Nepali months.
     *
     * Null on every row already on file, and deliberately so: the answer those
     * accounts have been credited on lives in the old global setting, which no
     * migration can read. [com.mywallet.data.repo.InterestRepository] takes it
     * out of the preferences on the next launch and writes it here, once. A
     * default of 3 in this column would have silently overwritten a phone on
     * half-yearly and swept every posting it had ever made.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `account` ADD COLUMN `interest_payout_months` INTEGER")
        }
    }

    /**
     * What was actually borrowed, which `principal_minor` stops meaning the
     * moment a lump sum re-bases the debt in place.
     *
     * Null on every loan already on file, and deliberately so. A debt that has
     * been paid down no longer knows what it opened at — the instalments in
     * between cleared principal and interest together — and defaulting the column
     * to today's balance would write that shrunken figure down as the sum the
     * borrower agreed to. Null keeps them exactly as they read today: the
     * walk-back where the balance is a running total, and the current principal
     * otherwise.
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `advanced_minor` INTEGER")
        }
    }

    /**
     * Which calendar a debt's instalments are counted in.
     *
     * A bank loan's schedule was Gregorian for everybody, on the reasoning that
     * a bank debits on the 20th of the English month — true of an English
     * statement and simply wrong for a borrower whose whole diary is in Bikram
     * Sambat. It is the rule's own answer now, copied onto the loan so every
     * reader of its dates can see it.
     *
     * **Zero on every loan already on file, and that is the whole point of the
     * default.** These schedules were written in Gregorian months and the
     * borrower's standing order pays them on those days; flipping them to the
     * calendar that happens to be set would move every remaining due date of
     * every existing debt, which is the app rewriting a schedule nobody touched.
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `loan` ADD COLUMN `recur_in_bs` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * Whether a savings account counts its interest periods in Nepali months.
     *
     * Zero on every account already on file, which is the answer they have been
     * credited on: a period is a fixed slice of the year cut at the 1st of a
     * month, and flipping which calendar's months those are moves every payout
     * day the account has ever had. The question is the bank's and is now asked
     * on the account itself — see the column.
     */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `account` ADD COLUMN `interest_in_bs` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * The opt-in half of "which calendar does this count in", on the rules and
     * on the plans.
     *
     * Both tables already stored the *effective* answer — what the dates are
     * generated from — and nothing recorded whether the user had asked to
     * follow the calendar or had been pinned to one. Backfilled from that
     * effective answer, which is the only reading that leaves every rule doing
     * exactly what it was doing the day before the upgrade: one written in
     * Gregorian stays Gregorian for good, and one written in Bikram Sambat goes
     * on following the calendar it was written under.
     *
     * The plans are turned **on** only, never off: a savings account may already
     * carry an opt-in of its own in `interest_in_bs`, and rewriting the column
     * wholesale would throw that away.
     */
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `recurring_series` " +
                    "ADD COLUMN `uses_selected_calendar` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("UPDATE `recurring_series` SET `uses_selected_calendar` = `recur_in_bs`")
            db.execSQL("UPDATE `account` SET `interest_in_bs` = 1 WHERE `plan_recur_in_bs` = 1")
        }
    }

    /**
     * Labels are removed from the app, so their table and both columns pointing
     * at it go with them.
     *
     * One-way by nature: what an entry was filed under is not recoverable
     * afterwards, and that is what taking the feature out means. Both tables are
     * **rebuilt** rather than altered, because SQLite gained `DROP COLUMN` in
     * 3.35 and the oldest phone this ships to has 3.19: create, copy, drop,
     * rename, and put every index back by hand. `label` is dropped last, once
     * nothing references it.
     */
    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `money_entry_new` (" +
                    "`id` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                    "`currency_code` TEXT NOT NULL DEFAULT 'NPR', " +
                    "`base_amount_minor` INTEGER NOT NULL DEFAULT 0, " +
                    "`rate_to_base` REAL NOT NULL DEFAULT 1.0, " +
                    "`base_currency_code` TEXT NOT NULL DEFAULT 'NPR', " +
                    "`direction` TEXT NOT NULL, `occurred_on` INTEGER NOT NULL, " +
                    "`account_id` TEXT, `is_adjustment` INTEGER NOT NULL DEFAULT 0, " +
                    "`series_id` TEXT, `loan_id` TEXT, `loan_part` TEXT, `transfer_id` TEXT, " +
                    "`status` TEXT NOT NULL DEFAULT 'CONFIRMED', `note` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL )"
            )
            db.execSQL(
                "INSERT INTO `money_entry_new` SELECT `id`, `amount_minor`, `currency_code`, " +
                    "`base_amount_minor`, `rate_to_base`, `base_currency_code`, `direction`, " +
                    "`occurred_on`, `account_id`, `is_adjustment`, `series_id`, `loan_id`, " +
                    "`loan_part`, `transfer_id`, `status`, `note`, `createdAt`, `updatedAt`, " +
                    "`deletedAt` FROM `money_entry`"
            )
            db.execSQL("DROP TABLE `money_entry`")
            db.execSQL("ALTER TABLE `money_entry_new` RENAME TO `money_entry`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_occurred_on` ON `money_entry` (`occurred_on`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_account_id` ON `money_entry` (`account_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_loan_id` ON `money_entry` (`loan_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_transfer_id` ON `money_entry` (`transfer_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_deletedAt` ON `money_entry` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_money_entry_direction_occurred_on` ON `money_entry` (`direction`, `occurred_on`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_series_new` (" +
                    "`id` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, " +
                    "`currency_code` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                    "`interval` TEXT NOT NULL, `interval_months` INTEGER, " +
                    "`start_on` INTEGER NOT NULL, `end_on` INTEGER, " +
                    "`materialised_through` INTEGER, `account_id` TEXT, " +
                    "`transfer_to_account_id` TEXT, " +
                    "`is_adjustment` INTEGER NOT NULL DEFAULT 0, " +
                    "`recur_in_bs` INTEGER NOT NULL DEFAULT 0, " +
                    "`uses_selected_calendar` INTEGER NOT NULL DEFAULT 0, `note` TEXT, " +
                    "`is_paused` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL )"
            )
            db.execSQL(
                "INSERT INTO `recurring_series_new` SELECT `id`, `amount_minor`, `currency_code`, " +
                    "`direction`, `interval`, `interval_months`, `start_on`, `end_on`, " +
                    "`materialised_through`, `account_id`, `transfer_to_account_id`, " +
                    "`is_adjustment`, `recur_in_bs`, `uses_selected_calendar`, `note`, " +
                    "`is_paused`, `createdAt`, `updatedAt`, `deletedAt` FROM `recurring_series`"
            )
            db.execSQL("DROP TABLE `recurring_series`")
            db.execSQL("ALTER TABLE `recurring_series_new` RENAME TO `recurring_series`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_deletedAt` ON `recurring_series` (`deletedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_series_account_id` ON `recurring_series` (`account_id`)")

            db.execSQL("DROP TABLE IF EXISTS `label`")
        }
    }

    /**
     * A colour of its own for a card.
     *
     * A debt had none: every list drew it in the colour its *figure* was already
     * printed in — red owing, green owed to you — which was right while a debt
     * was only ever a balance to look at. A credit card is not: it sits in the
     * money form beside the bank accounts, it is spent from, and the rows it
     * produces are read down the same lists theirs are. A holding you pay with
     * is found by its colour, and this is the column that lets one be found.
     *
     * Nullable and added to every debt rather than only to cards, because the
     * column costs nothing on the ones that never use it and a second migration
     * to widen it later would cost a version.
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `color_argb` INTEGER")
        }
    }

    /**
     * The day a card or an overdraft was approved.
     *
     * The facility's expiry was the day it was entered in the app plus its
     * agreed length, which is the right answer only for somebody recording a
     * card the week they were given it. Asked outright now, and null on every
     * card already on file: a facility whose approval day the app was never told
     * has no expiry it can honestly work out, and one guessed from the creation
     * date would retire a card its owner is still spending on.
     */
    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `loan` ADD COLUMN `opened_on` INTEGER")
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
        MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
        MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
        MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
        MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30,
        MIGRATION_30_31,
    )
}
