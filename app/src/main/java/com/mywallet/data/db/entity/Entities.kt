package com.mywallet.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Which way the money moved. Stored as a string so a database dump stays
 * readable and a future direction can be added without renumbering.
 */
enum class Direction { IN, OUT }

/**
 * Where money physically sits. Only affects wording and icons, not maths —
 * except for [FIXED_DEPOSIT], which is the one kind that changes both.
 *
 * Savings and current are both bank accounts and every calculation treats them
 * the same; they are separate values because that is the choice a bank actually
 * offers, and a single "Bank" told the user nothing about which of theirs it
 * was.
 *
 * [FIXED_DEPOSIT] is money the user has put away and agreed not to touch. It is
 * still theirs — it counts towards what they hold — but it cannot be spent from
 * or transferred into, its balance grows by a rule rather than by entries, and
 * on one known day the whole of it moves somewhere else. Every one of those is a
 * departure from what an account does, which is why it is a kind and not a flag.
 *
 * [INSURANCE] is a policy paid for in instalments: a premium leaves an account
 * every so often for an agreed length, and on the day the term runs out the
 * insurer hands over the sum assured. It is an account rather than a debt
 * because the money paid in is still the user's — what it holds is every premium
 * paid so far — and it is its own kind for the same reasons a deposit is:
 * nothing may be spent from it, and on one known day the whole of it moves.
 *
 * [GOAL] is money put aside on purpose: a figure to reach and a rhythm that
 * reaches it. It runs on exactly the machinery [INSURANCE] does — a rule that
 * moves money into it, and one day on which it comes back out — and differs in
 * which figure is given and which is worked out: a policy is told what each
 * premium costs, a goal is told what it is for and asked to divide.
 */
enum class AccountKind { SAVINGS, CURRENT, FIXED_DEPOSIT, INSURANCE, GOAL, WALLET, CASH }

/**
 * Who the loan is with.
 *
 * BANK loans have a rate, a term and an EMI. PERSONAL ones — money from family
 * or a friend — may have none of those, which is exactly why they get their own
 * kind rather than being forced into a schedule they do not have.
 *
 * OVERDRAFT is the third shape a bank offers: a limit you draw against, with
 * interest on what is drawn and no instalment schedule at all. It runs on the
 * same machinery as any other loan — a balance, a rate, and repayments taken off
 * it — but it is a separate kind because the row has to say which of the bank's
 * products it is, and because an overdraft with a fixed EMI would be a lie.
 */
enum class LoanKind { BANK, PERSONAL, OVERDRAFT }

/**
 * Which way the debt runs.
 *
 * [LENT] is the mirror of a loan: money someone owes *you*. It is an asset, its
 * repayments arrive rather than leave, and it must never be added to what the
 * user owes — the two appear in different places and on opposite sides of net
 * worth.
 */
enum class LoanDirection { BORROWED, LENT }

/**
 * How each instalment on a loan is made up.
 *
 * Nepali banks offer the first two by name, and an informal loan is usually one
 * of the others:
 *
 *  - [LEVEL_EMI] — the same amount every month, split between interest and
 *    principal, with the split shifting as the balance falls.
 *  - [PRINCIPAL_ONLY] — a fixed slice of the principal each month with that
 *    month's interest added on top, so the payment starts high and falls. Costs
 *    less interest overall than a level instalment on the same terms.
 *  - [INTEREST_ONLY] — the interest is serviced and the balance waits, to be
 *    settled at the end. A moratorium, or the informal arrangement where you pay
 *    someone their interest monthly and the principal when you can.
 *
 * This is a property of the loan, not of a payment. It used to be neither: the
 * app offered one-off "principal only" and "interest only" payments, which asked
 * the user to re-describe their loan every single month.
 */
enum class InstalmentStyle { LEVEL_EMI, PRINCIPAL_ONLY, INTEREST_ONLY }

/**
 * Which half of a debt a payment went to.
 *
 * Set only on payments that are deliberately all one or all the other: a lump
 * sum off the principal, or interest serviced on its own during a moratorium.
 * Ordinary instalments carry null, because they are a mix and the amortisation
 * schedule already knows the split.
 *
 * These rows are a record, not an input: no balance is ever derived from them.
 * A principal payment is already folded into the loan's own principal figure, so
 * subtracting these as well would count the same rupee twice.
 */
enum class LoanPart { PRINCIPAL, INTEREST }

/** How often a repeating entry comes round. */
enum class RecurrenceInterval { WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, HALF_YEARLY, YEARLY }

/**
 * Whose row this is — the rule's, or the user's.
 *
 * [EXPECTED] rows were written by a repeating series when their date arrived.
 * They count towards every total, balance and debt from that moment, exactly
 * like any other row: a schedule that has run has run, and a standing
 * instruction the bank executes on the 10th does not wait for anyone's
 * approval. There used to be a Confirm button on each of them, which meant a
 * loan entered with two years of history showed the wrong balance until the
 * user had tapped it twenty-four times — the app asking to be told what it
 * already knew.
 *
 * What the flag still decides is ownership. An EXPECTED row is the rule's own
 * words, so editing the rule may rewrite or discard it; [CONFIRMED] marks a row
 * the user typed or has since corrected, which the rule must leave alone. Saving
 * an edit is what moves a row from the first to the second.
 */
enum class EntryStatus { CONFIRMED, EXPECTED }

/**
 * Fields every table carries so that backup files can be merged rather than
 * blindly overwritten:
 *
 *  - the id is a UUID, not an autoincrement, so two devices never collide;
 *  - [updatedAt] lets a restore keep whichever copy of a row is newer;
 *  - [deletedAt] is a tombstone, so "deleted on phone A" survives a restore
 *    from phone B instead of the row quietly coming back to life.
 */
interface SyncFields {
    val createdAt: Long
    val updatedAt: Long
    val deletedAt: Long?
}

/**
 * Somewhere money lives: a bank account, a wallet like Wise, or plain cash.
 *
 * Each account has its own currency, because that is the real constraint — a
 * Wise balance is in USD whatever the user's display currency happens to be.
 */
@Entity(
    tableName = "account",
    indices = [Index(value = ["deletedAt"]), Index(value = ["sort_order"])],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: AccountKind,
    /** ISO 4217 code this account is denominated in. */
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    /**
     * Bank or provider this account sits under, for grouping — "Global IME"
     * covering both a savings and a current account. Free text and optional:
     * cash belongs to no institution.
     */
    @ColumnInfo(name = "institution") val institution: String? = null,
    /**
     * Balance before any recorded entry, in this account's own currency.
     * Also where a cash true-up lands: spending cash that was never recorded as
     * income is normal, and forcing a fake income entry to explain it would be
     * worse than adjusting the opening figure.
     */
    @ColumnInfo(name = "opening_balance_minor") val openingBalanceMinor: Long = 0L,
    /**
     * What the bank pays on this account, when it pays anything.
     *
     * Null on cash, a wallet, and any account whose rate the user has not
     * bothered to record — which must go on behaving exactly as it does now.
     * The rate is only the *current* one; what it has been is in `rate_change`.
     */
    @ColumnInfo(name = "annual_rate") val annualRate: Double? = null,
    /**
     * How far interest has been worked out and paid into this account, as an
     * epoch day. The same watermark idea as a repeating series': it makes
     * posting safe to run on every launch, and it is what stops a quarter from
     * being paid twice.
     */
    @ColumnInfo(name = "interest_posted_through") val interestPostedThrough: Long? = null,
    /**
     * Whether this account's interest periods are counted in Nepali months.
     *
     * An **opt-in**, not the effective answer: what the periods are actually cut
     * into is this *and* the calendar setting being Bikram Sambat, worked out
     * where the interest is. Reading dates in Nepali is one thing; a bank
     * closing its quarters on 1 Baisakh is another, and most Nepali banks
     * following the English quarter is exactly why the app must not infer one
     * from the other. Off unless the account's owner says otherwise, so somebody
     * who simply prefers a Nepali patro keeps the quarters their passbook shows.
     */
    @ColumnInfo(name = "interest_in_bs", defaultValue = "0")
    val interestInBs: Boolean = false,
    /**
     * How many months this account's interest period runs for.
     *
     * Whose months is not stored: it is the calendar setting, read when the
     * interest is worked out, because the whole history is recomputed on every
     * run and so a switch simply re-cuts the year. See [SavingsInterest].
     *
     * The bank's arrangement and not a preference, which is why it sits on the
     * account rather than in Settings: quarterly is what most Nepali banks do,
     * but monthly, half-yearly and yearly all exist, and somebody with accounts
     * at two banks has two answers. It was one global setting until the accounts
     * disagreed with each other; the figure a passbook shows follows whichever
     * arrangement *that* account is on.
     *
     * Null on every holding the question does not apply to — cash, a wallet, a
     * deposit, a policy, a goal — and on every account that predates the column,
     * where it reads as [com.mywallet.domain.SavingsInterest.DEFAULT_EVERY_MONTHS].
     * Nullable rather than defaulted for exactly that: a phone upgrading from the
     * global setting has to be able to tell "never answered" from "answered
     * three", or the answer it was actually being credited on is lost.
     */
    @ColumnInfo(name = "interest_payout_months") val interestPayoutMonths: Int? = null,
    /**
     * The day this arrangement started, as an epoch day — the day the money
     * went into a fixed deposit, or the day an insurance policy was taken out.
     * Null on every kind that has no term.
     *
     * The day it comes free is not stored beside it: that is this date plus
     * [depositTermMonths], which is the same arrangement said once instead of
     * twice. Two columns could disagree about it, and this is the one the user
     * actually knows — they remember handing the money over.
     *
     * Named for the deposit it was added for. It means the same thing on a
     * policy, which is why it is shared rather than copied: two columns holding
     * one fact are two columns that can disagree.
     */
    @ColumnInfo(name = "deposit_started_on") val depositStartedOn: Long? = null,
    /** How long it was agreed for, in months — a deposit's term or a policy's. */
    @ColumnInfo(name = "deposit_term_months") val depositTermMonths: Int? = null,
    /**
     * Where the money lands when the deposit matures, or the insurer pays out.
     *
     * Deliberately not a foreign key with `SET NULL`: a deposit whose
     * destination account was deleted has to keep saying where it was meant to
     * go, or a maturity worth several lakh silently stops being projected
     * anywhere. The reader resolves it and copes with a missing account.
     */
    @ColumnInfo(name = "matures_into_account_id") val maturesIntoAccountId: String? = null,
    /**
     * The figure the whole arrangement is aimed at: what a policy pays out on
     * the day its term runs out, or what a goal is trying to reach.
     *
     * On a policy it is typed by the user and never derived from the premiums —
     * an endowment hands back more than was paid into it, and dividing this by
     * the number of payments would quote a premium that disagrees with the one
     * on the policy document. On a goal it is the other way round and this is
     * the figure the user gives: nothing is put aside that they did not put
     * there, so what it costs each time is the app's to divide out.
     */
    @ColumnInfo(name = "maturity_amount_minor") val maturityAmountMinor: Long? = null,
    /**
     * What leaves the account each time: a premium, or a goal's contribution.
     * Named for the policy it was added for, and stored on a goal as well —
     * rather than re-divided on every read — so the rule that moves the money
     * and the figure the card quotes cannot come out different.
     */
    @ColumnInfo(name = "premium_minor") val premiumMinor: Long? = null,
    /** Months between those payments: 1 for monthly, 12 for yearly, and so on. */
    @ColumnInfo(name = "premium_every_months") val premiumEveryMonths: Int? = null,
    /**
     * The repeating rule that makes them.
     *
     * The rule is where the schedule actually lives — one occurrence per
     * premium, materialised as it falls due and projected before it does — and
     * this is how the policy finds it again to rewrite it. Not a foreign key for
     * the same reason a loan's is not: series are soft-deleted, and a restored
     * backup has to be able to find its way back.
     */
    @ColumnInfo(name = "premium_series_id") val premiumSeriesId: String? = null,
    /**
     * Whether this plan's schedule counts Nepali months.
     *
     * The same answer as its rule's `recur_in_bs`, kept here as well because
     * every reader of a policy's or a goal's dates is a pure function of the
     * account row — the maturity forecast, the card's table, the payment count —
     * and none of them can reach the rule. Written with the rule, in one call,
     * so the two cannot drift.
     */
    @ColumnInfo(name = "plan_recur_in_bs", defaultValue = "0")
    val planRecurInBs: Boolean = false,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    /**
     * True to show this account's money converted into the display currency
     * rather than as it is.
     *
     * A per-account choice because the honest answer differs per account. Someone
     * who thinks of their Wise balance in dollars wants dollars; someone who only
     * cares what it is worth at home wants rupees. Both are true, and the app
     * cannot guess which one is in the user's head.
     */
    @ColumnInfo(name = "show_in_display_currency", defaultValue = "0")
    val showInDisplayCurrency: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : SyncFields

/**
 * One movement of money — the atom the whole app is built from.
 *
 * Salary, a bill, a subscription charge and a goal contribution are all just
 * entries with different flags against different holdings; that is why the
 * feature list collapses down to this single table.
 */
@Entity(
    tableName = "money_entry",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["occurred_on"]),
        Index(value = ["account_id"]),
        Index(value = ["loan_id"]),
        Index(value = ["transfer_id"]),
        Index(value = ["deletedAt"]),
        Index(value = ["direction", "occurred_on"]),
    ],
)
data class MoneyEntryEntity(
    @PrimaryKey val id: String,
    /**
     * Always positive, in [currencyCode] — the amount as the user typed it.
     * [direction] carries the sign so sums cannot go wrong.
     */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** Currency actually paid or received in, e.g. USD for a Netflix charge. */
    @ColumnInfo(name = "currency_code", defaultValue = "NPR") val currencyCode: String,
    /**
     * The same amount converted to [baseCurrencyCode] using [rateToBase], both
     * captured when the entry was saved.
     *
     * Storing the converted figure rather than reconverting on every read is the
     * whole point: a $10 charge from March keeps March's value, so last month's
     * totals do not quietly change every time the rate moves.
     */
    @ColumnInfo(name = "base_amount_minor", defaultValue = "0") val baseAmountMinor: Long,
    /** Units of base currency per 1 unit of [currencyCode], at save time. */
    @ColumnInfo(name = "rate_to_base", defaultValue = "1.0") val rateToBase: Double,
    /**
     * Which currency [baseAmountMinor] is expressed in. Recorded because the
     * user can change their display currency later, and a stored conversion is
     * only reusable while it still points at the currency being displayed.
     */
    @ColumnInfo(name = "base_currency_code", defaultValue = "NPR") val baseCurrencyCode: String,
    val direction: Direction,
    /**
     * Epoch day, not a timestamp. People remember "I spent this on Tuesday",
     * not the minute — and day granularity means no timezone can shift an entry
     * into the wrong month.
     */
    @ColumnInfo(name = "occurred_on") val occurredOn: Long,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    /**
     * A balance correction rather than real income or spending. Counts towards
     * the account balance but is excluded from "where it went", so a cash
     * top-up never masquerades as earnings.
     */
    @ColumnInfo(name = "is_adjustment", defaultValue = "0") val isAdjustment: Boolean = false,
    /** The repeating series that generated this row, if any. */
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    /**
     * The loan this payment went towards, when it went towards one.
     *
     * Deliberately not a foreign key: loans are soft-deleted, so a real
     * `ON DELETE SET NULL` would never fire, and a deleted loan's payments must
     * keep pointing at it for a restored backup to make sense again.
     */
    @ColumnInfo(name = "loan_id") val loanId: String? = null,
    /** Set only when the payment was all principal or all interest. */
    @ColumnInfo(name = "loan_part") val loanPart: LoanPart? = null,
    /**
     * Ties the two halves of a transfer together — the row leaving one account
     * and the row arriving in another.
     *
     * A transfer is two entries rather than one because each account's balance is
     * computed from its own rows, and because the two amounts differ whenever the
     * accounts are in different currencies. The shared id is what lets them be
     * edited and deleted as the single movement the user thinks they made.
     */
    @ColumnInfo(name = "transfer_id") val transferId: String? = null,
    @ColumnInfo(name = "status", defaultValue = "CONFIRMED") val status: EntryStatus = EntryStatus.CONFIRMED,
    val note: String? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : SyncFields

/**
 * Money spent straight from a card, told by its shape and nothing else.
 *
 * Money out, against a facility, naming no account and no rule, and not an
 * adjustment: nothing else in the app writes that. A new `loan_part` value would
 * have been plainer, but an older build restoring the backup could not parse one
 * — and this row has to be readable by every version that can open the file.
 *
 * Mirrored in SQL by `LoanDao.balanceChanges`, which needs the same verdict to
 * give the row the sign the direction alone would get wrong. The two are one
 * rule and have to move together.
 */
val MoneyEntryEntity.isCardSpend: Boolean
    get() = loanId != null &&
        direction == Direction.OUT &&
        loanPart == null &&
        accountId == null &&
        seriesId == null &&
        !isAdjustment


/**
 * A repeating money event: salary on the 1st, EMI on the 5th, Netflix monthly.
 *
 * The series is the *rule*, not the occurrences. Dates that have arrived are
 * materialised into real [MoneyEntryEntity] rows so they can be confirmed and
 * edited individually; dates still in the future are computed on demand and
 * never stored. That way editing the rule instantly corrects every future
 * projection, and there is no drawer of stale rows to clean up.
 */
@Entity(
    tableName = "recurring_series",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["deletedAt"]), Index(value = ["account_id"])],
)
data class RecurringSeriesEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    val direction: Direction,
    val interval: RecurrenceInterval,
    /**
     * Months between occurrences, when the gap is not one the [interval] can say.
     *
     * Null on everything the four named monthly intervals already describe, which
     * is every rule the user writes by hand. A loan is the exception: its gap is
     * whatever was agreed — every two months, every five, or a single payment at
     * the end of the term — and before this column such a loan fell back to
     * monthly and billed the borrower several times too often. When it is set it
     * wins over [interval], which then only records the family the gap belongs to.
     */
    @ColumnInfo(name = "interval_months") val intervalMonths: Int? = null,
    /** First occurrence, as an epoch day. Later dates step from here. */
    @ColumnInfo(name = "start_on") val startOn: Long,
    /** Last day the series may produce an occurrence. Null means open-ended. */
    @ColumnInfo(name = "end_on") val endOn: Long? = null,
    /**
     * How far occurrences have been turned into real rows. Stops a second run
     * on the same day from creating the same expected entry twice.
     */
    @ColumnInfo(name = "materialised_through") val materialisedThrough: Long? = null,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    /**
     * Set when this rule moves money between two accounts rather than in or out
     * of the wallet. [accountId] is the source; each occurrence becomes a pair of
     * entries, converted at the rate on the day it happens.
     */
    @ColumnInfo(name = "transfer_to_account_id") val transferToAccountId: String? = null,
    /**
     * True when occurrences move a balance without being income or spending —
     * transfers, and repayments of money the user lent out. Carried on the rule
     * because the occurrences are generated long after it was written.
     */
    @ColumnInfo(name = "is_adjustment", defaultValue = "0") val isAdjustment: Boolean = false,
    /**
     * Whether a "month" of this rule is a Nepali month or an English one — the
     * **effective** answer, and the one the dates are actually generated from.
     *
     * Written from [usesSelectedCalendar] and the calendar setting together, and
     * rewritten whenever either moves. Carried on the rule rather than read from
     * the setting at generation time so that nothing about when the money moves
     * depends on which screen is being looked at.
     *
     * Defaults off, which is what every rule written before this existed was
     * doing: an upgrade must not move a single payment.
     */
    @ColumnInfo(name = "recur_in_bs", defaultValue = "0") val recurInBs: Boolean = false,
    /**
     * Whether this rule follows whichever calendar the app is set to — the
     * **opt-in**, which is what the user actually answers.
     *
     * On for a rule the user writes by hand: somebody who sets up a subscription
     * on 1 Baisakh while reading a Nepali patro means the 1st of Baisakh, and if
     * they later switch the app to the English calendar they mean the months
     * they are now reading. Off is how a schedule is pinned to English
     * whatever is on screen, which is what a bank's instalment needs.
     *
     * Backfilled from [recurInBs] on upgrade, so every rule already on file
     * keeps doing exactly what it was doing: one written in Gregorian is pinned
     * there for good, and one written in Bikram Sambat goes on following the
     * calendar it was written under.
     */
    @ColumnInfo(name = "uses_selected_calendar", defaultValue = "0")
    val usesSelectedCalendar: Boolean = false,
    val note: String? = null,
    @ColumnInfo(name = "is_paused", defaultValue = "0") val isPaused: Boolean = false,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : SyncFields

/**
 * A day the bank moved a rate, on a savings account or on a loan.
 *
 * Kept as its own row rather than overwriting the holding's rate, because a rate
 * is a property of a period and not of an account. A floating loan reviewed
 * quarterly is charged at four different rates a year, and the balance owed
 * today is the result of every one of them; rewriting the single stored figure
 * would recompute seven years of history at whatever the bank charges this
 * month. The same applies to a savings account, where each quarter's interest
 * was earned at the rate in force then.
 *
 * Exactly one of [accountId] and [loanId] is set. Two nullable columns rather
 * than a shared "holding" one, so the foreign keys can do their job and a
 * deleted account cannot leave its rates behind.
 */
@Entity(
    tableName = "rate_change",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["loan_id"]),
        Index(value = ["deletedAt"]),
    ],
)
data class RateChangeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    @ColumnInfo(name = "loan_id") val loanId: String? = null,
    @ColumnInfo(name = "annual_rate") val annualRate: Double,
    /** The day the new rate started applying, which is rarely the day it was typed in. */
    @ColumnInfo(name = "effective_from") val effectiveFrom: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : SyncFields

/**
 * Money owed: a bank loan with an EMI, or an informal one with nothing fixed.
 *
 * A loan is not an account. You do not spend from it — it is a debt that
 * shrinks as you repay, and its balance follows an amortisation schedule rather
 * than a running total of entries. The EMI itself is an ordinary repeating
 * series pointed at whichever account the bank debits.
 */
@Entity(
    tableName = "loan",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["pay_from_account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["deletedAt"]), Index(value = ["pay_from_account_id"])],
)
data class LoanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: LoanKind,
    /** Bank or person the money is owed to — or who owes it, on a loan you gave. */
    val lender: String? = null,
    /** Whether the user borrowed this money or lent it out. */
    @ColumnInfo(name = "loan_direction", defaultValue = "'BORROWED'")
    val loanDirection: LoanDirection = LoanDirection.BORROWED,
    /** How each instalment is made up. */
    @ColumnInfo(name = "instalment_style", defaultValue = "'LEVEL_EMI'")
    val instalmentStyle: InstalmentStyle = InstalmentStyle.LEVEL_EMI,
    /**
     * What is owed on the loan before any repayment — and on an overdraft, what
     * has actually been *drawn*, which starts at zero and moves with every
     * withdrawal and repayment. [creditLimitMinor] is the ceiling it may reach.
     */
    @ColumnInfo(name = "principal_minor") val principalMinor: Long,
    /**
     * The approved limit on an overdraft, and null on everything else.
     *
     * Separate from [principalMinor] because an overdraft is two numbers, not
     * one: the bank has approved a ceiling, and the user owes only what they have
     * taken. Conflating them would open every overdraft already owing its full
     * limit on the day it was created.
     */
    @ColumnInfo(name = "credit_limit_minor") val creditLimitMinor: Long? = null,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    /** Annual percentage. Null or zero means an interest-free loan. */
    @ColumnInfo(name = "annual_rate") val annualRate: Double? = null,
    /** Null for an open-ended informal loan with no agreed end. */
    @ColumnInfo(name = "term_months") val termMonths: Int? = null,
    /**
     * Months between instalments: 1 monthly, 3 quarterly, 12 yearly.
     *
     * Not cosmetic. Interest accrues over the gap, so a quarterly loan charges
     * three months of it each time and costs more in total than the same loan
     * paid monthly. Everything derived from the schedule — the instalment, the
     * outstanding balance, the projection dates — is computed per *payment*
     * rather than per month because of this column.
     */
    @ColumnInfo(name = "payment_every_months", defaultValue = "1")
    val paymentEveryMonths: Int = 1,
    /**
     * The instalment. Computed from the other three when they are all present,
     * but stored so a bank's own figure always wins over ours — their rounding
     * is the one that appears on the statement.
     */
    @ColumnInfo(name = "emi_minor") val emiMinor: Long? = null,
    /**
     * The bank's first recovery date. Null when there is no schedule.
     *
     * Not necessarily the first *full* instalment. A bank recovers on a fixed
     * day of the month, and money handed over on the 3rd meets that day before a
     * whole month has passed — so the payment on it covers only the days since
     * disbursement. See [disbursedOn] and `LoanMath.brokenPeriodInterest`.
     */
    @ColumnInfo(name = "emi_starts_on") val emiStartsOn: Long? = null,
    /**
     * The day the money actually arrived, which is when interest starts running.
     *
     * A separate fact from every other date here, and the only one the borrower
     * has no say in. Null on loans entered before the column existed, and null
     * means the app cannot say: no broken period is invented for them, because
     * guessing a disbursement date would invent an interest charge with it.
     */
    @ColumnInfo(name = "disbursed_on") val disbursedOn: Long? = null,
    /**
     * The day the bank approved the facility, on a card or an overdraft.
     *
     * Deliberately not [disbursedOn], which is the day money changed hands and
     * is what interest is metered from: nothing is drawn on a card the day it is
     * approved, and reading an approval date as a disbursement would start the
     * meter running on a balance of nothing years before the first purchase.
     *
     * With [termMonths] it is the whole of when the facility expires, which is
     * the only thing it decides — an expired card is no longer offered as
     * somewhere money can be spent from. The day it was *created in the app*
     * stood in for this before the column existed, which is right only for
     * somebody entering a card the week they were given it.
     *
     * Null everywhere else, and null on every card already on file: a facility
     * whose approval day the app was never told has no expiry it can honestly
     * work out, and one guessed from the creation date could retire a card the
     * user is still using.
     */
    @ColumnInfo(name = "opened_on") val openedOn: Long? = null,
    /**
     * Interest that had already run when a lump sum re-based this loan, waiting
     * to be collected by the next instalment.
     *
     * A payment lands between instalments far more often than on one, and the
     * days behind it were charged on the larger balance. The payment comes off
     * the principal in full — that is what the borrower paid — so those days are
     * held here rather than added to the debt, where compounding them would
     * quietly overstate it. Zero on a loan that has never been re-based, which
     * is most of them.
     */
    @ColumnInfo(name = "carried_interest_minor", defaultValue = "0")
    val carriedInterestMinor: Long = 0L,
    /**
     * Whether this debt's instalments are counted in Nepali months.
     *
     * An **opt-in**, not the effective answer: what the schedule is actually
     * stepped in is this *and* the calendar setting being Bikram Sambat, worked
     * out by [com.mywallet.core.date.CalendarSystem.forInterest]. A bank debits
     * on a fixed day of the English month whatever patro its borrower reads, and
     * inferring the schedule from the display setting would move every remaining
     * due date the moment somebody switched it. Off unless the borrower says
     * their bank really does count in Nepali months.
     *
     * Getting the effective answer wrong is not cosmetic. `LoanMath` charges
     * interest for the days between payments, so a schedule stepped in the other
     * calendar puts every due date days from where the timeline has it and the
     * two disagree about the same instalment. That is why the loan carries the
     * flag at all rather than only the rule: every reader of its dates — the
     * schedule table, the broken period, the day the rule ends — is a pure
     * function of the loan row and none of them can reach the series.
     *
     * **False on every loan already on file**, which the migration's default of
     * 0 is there for: an upgrade that moved a standing payment would be the app
     * rewriting a schedule nobody touched.
     */
    @ColumnInfo(name = "recur_in_bs", defaultValue = "0")
    val recurInBs: Boolean = false,
    /**
     * The day it has to be settled by, for a loan with no instalments.
     *
     * The usual shape of money between people: nothing monthly, just "before
     * Dashain". Optional, because plenty of them have no date at all — and where
     * there is one it is worth projecting, since a lump sum falling due is
     * exactly what the user needs warning about.
     */
    @ColumnInfo(name = "due_on") val dueOn: Long? = null,
    /** Account the instalment is taken from. */
    @ColumnInfo(name = "pay_from_account_id") val payFromAccountId: String? = null,
    /**
     * The account the money passed through on the day the debt was made: where a
     * borrowing landed, or the one a lending left from.
     *
     * A second account, and deliberately not [payFromAccountId]: money between
     * people moves twice in opposite directions, and the account it arrived in is
     * very often not the one it goes back from. Null is the normal answer on a
     * bank loan — plenty are disbursed straight to a seller — and on any debt the
     * user has already reconciled by hand.
     *
     * Kept after the entry it wrote, because it is also the account more of the
     * same arrangement moves through: another रू 2,000 lent to the same person
     * leaves the account the first रू 8,000 did.
     */
    @ColumnInfo(name = "disbursed_account_id") val disbursedAccountId: String? = null,
    /**
     * The sum ever advanced on this arrangement — what the borrower means by
     * "the loan amount", and the one figure about a debt that never changes.
     *
     * [principalMinor] stops meaning that the moment a lump sum lands: the
     * re-base rewrites it in place, so रू 1,00,000 with रू 5,000 paid off stores
     * रू 95,000 and the sum that was actually taken is gone. On a debt whose
     * balance is a running total it can be walked back out of the dated
     * movements (`LoanMath.totalAdvanced`), but on one that follows a schedule it
     * cannot: the instalments in between cleared principal *and* interest
     * together, and nothing on file says how the payment split.
     *
     * So it is written once, on the way in, and moved only by more being
     * borrowed on the same arrangement — never by anything that pays it down.
     *
     * Null on every loan that predates the column, and nothing is inferred from a
     * null: a debt already re-based has no honest answer left, and seeding it
     * with today's balance would state a figure the borrower never agreed to.
     * Null on an overdraft too, which opens at nothing by definition — the
     * approved ceiling is [creditLimitMinor].
     */
    @ColumnInfo(name = "advanced_minor") val advancedMinor: Long? = null,
    /** The repeating series paying this loan, so the two stay linked. */
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "started_on") val startedOn: Long,
    /**
     * True to show this loan converted into the display currency rather than as
     * it stands. The same per-holding choice an account gets, and for the same
     * reason: a debt in dollars is a dollar debt to the person who took it out.
     */
    @ColumnInfo(name = "show_in_display_currency", defaultValue = "0")
    val showInDisplayCurrency: Boolean = false,
    @ColumnInfo(name = "is_closed", defaultValue = "0") val isClosed: Boolean = false,
    /**
     * The colour this debt is drawn in, where the user chose one.
     *
     * Only a card asks: it is the one debt that sits among the accounts on the
     * money form and produces rows read down the same lists theirs are. Null
     * everywhere else, and a null debt keeps what it always had — the colour of
     * its own figure, red owing and green owed to you.
     */
    @ColumnInfo(name = "color_argb") val colorArgb: Int? = null,
    val note: String? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : SyncFields

/**
 * A cached exchange rate.
 *
 * Kept in the database rather than fetched per screen so the app converts
 * correctly with no network — a money app that shows nothing on a train is
 * useless. Rates are refreshed opportunistically, never blockingly.
 */
@Entity(tableName = "fx_rate", primaryKeys = ["base_code", "quote_code"])
data class FxRateEntity(
    @ColumnInfo(name = "base_code") val baseCode: String,
    @ColumnInfo(name = "quote_code") val quoteCode: String,
    /** Units of quote per 1 unit of base. */
    val rate: Double,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
