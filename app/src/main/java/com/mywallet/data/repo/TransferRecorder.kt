package com.mywallet.data.repo

import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.dao.MoneyEntryDao
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.EntryStatus
import com.mywallet.data.db.entity.MoneyEntryEntity
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moving money between two of the user's own accounts.
 *
 * Written as two entries, not one. Each account's balance is computed from its
 * own rows, so one row cannot move both; and when the accounts hold different
 * currencies the two amounts are genuinely different numbers — $100 leaves Wise
 * and रू 13,600 arrives at the bank. A shared [MoneyEntryEntity.transferId] ties
 * them back together so the pair can be edited or deleted as the one movement
 * the user thinks they made.
 *
 * Both legs are adjustments. A transfer is not income and not spending: the money
 * was already the user's and still is. Counting it would double every salary that
 * passes through a second account on its way to being spent.
 *
 * The conversion uses the rate on the day the transfer happens, not the day the
 * rule was written — a standing order to move $100 next month moves whatever
 * $100 is worth next month.
 */
@Singleton
class TransferRecorder @Inject constructor(
    private val entryDao: MoneyEntryDao,
    private val accountDao: AccountDao,
    private val exchangeRates: ExchangeRateRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /**
     * Writes or rewrites one transfer.
     *
     * @param transferId null to create a new pair; an existing id to update the
     *   pair that already carries it.
     * @param amount in the *source* account's currency, which is what the user
     *   typed — they know what is leaving, not what will land.
     * @param debitsSource false to write the leaving half without naming the
     *   account it left. Only a rule generating an occurrence from before the
     *   day it was written asks for this: that money left an account the app was
     *   not watching, whose balance the user has since told it directly, and
     *   taking it out again would subtract the same payment twice. The row still
     *   exists, because the movement really happened — see [creditsDestination]
     *   for the other half of the same question.
     * @param creditsDestination false for the same reason on the arriving side.
     *   True even for a back-dated occurrence when the destination is a holding
     *   the app opened itself and is the only record of — an insurance policy,
     *   whose balance is nothing but the premiums paid into it and which the
     *   user has no way to correct by hand. Crediting it is not double-counting;
     *   it is the only counting there is.
     */
    @Suppress("LongParameterList")
    suspend fun record(
        transferId: String?,
        fromAccountId: String,
        toAccountId: String,
        amount: Money,
        date: LocalDate,
        note: String?,
        status: EntryStatus = EntryStatus.CONFIRMED,
        seriesId: String? = null,
        debitsSource: Boolean = true,
        creditsDestination: Boolean = true,
    ): SaveResult {
        if (amount.minor <= 0L) return SaveResult.AmountRequired
        if (fromAccountId == toAccountId) return SaveResult.AccountRequired
        val from = accountDao.findById(fromAccountId) ?: return SaveResult.AccountRequired
        val to = accountDao.findById(toAccountId) ?: return SaveResult.AccountRequired

        val baseCode = settings.settings.first().currencyCode
        val fromUnits = CurrencyOption.byCode(from.currencyCode).minorUnits
        val toUnits = CurrencyOption.byCode(to.currencyCode).minorUnits
        val baseUnits = CurrencyOption.byCode(baseCode).minorUnits

        // What actually lands in the other account.
        val arriving = if (from.currencyCode.equals(to.currencyCode, ignoreCase = true)) {
            amount
        } else {
            exchangeRates.convert(
                amountMinor = amount.minor,
                from = from.currencyCode,
                fromMinorUnits = fromUnits,
                base = to.currencyCode,
                baseMinorUnits = toUnits,
            ).amount
        }

        val leavingInBase = exchangeRates.convert(
            amountMinor = amount.minor,
            from = from.currencyCode,
            fromMinorUnits = fromUnits,
            base = baseCode,
            baseMinorUnits = baseUnits,
        )
        val arrivingInBase = exchangeRates.convert(
            amountMinor = arriving.minor,
            from = to.currencyCode,
            fromMinorUnits = toUnits,
            base = baseCode,
            baseMinorUnits = baseUnits,
        )

        val now = clock.nowMillis()
        val id = transferId ?: UUID.randomUUID().toString()
        val existing = entryDao.entriesForTransfer(id)
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }

        // Matched by direction rather than by position: an edit that swaps the
        // two accounts must rewrite the same two rows, not create a third.
        val out = existing.firstOrNull { it.direction == Direction.OUT }
        val into = existing.firstOrNull { it.direction == Direction.IN }

        entryDao.upsert(
            leg(
                existing = out,
                direction = Direction.OUT,
                accountId = fromAccountId.takeIf { debitsSource },
                amount = amount,
                currencyCode = from.currencyCode,
                baseMinor = leavingInBase.amount.minor,
                rate = leavingInBase.rate,
                baseCode = baseCode,
                date = date,
                note = trimmedNote,
                status = status,
                transferId = id,
                seriesId = seriesId,
                now = now,
            )
        )
        entryDao.upsert(
            leg(
                existing = into,
                direction = Direction.IN,
                accountId = toAccountId.takeIf { creditsDestination },
                amount = arriving,
                currencyCode = to.currencyCode,
                baseMinor = arrivingInBase.amount.minor,
                rate = arrivingInBase.rate,
                baseCode = baseCode,
                date = date,
                note = trimmedNote,
                status = status,
                transferId = id,
                seriesId = seriesId,
                now = now,
            )
        )
        return SaveResult.Success(id)
    }

    @Suppress("LongParameterList")
    private fun leg(
        existing: MoneyEntryEntity?,
        direction: Direction,
        accountId: String?,
        amount: Money,
        currencyCode: String,
        baseMinor: Long,
        rate: Double,
        baseCode: String,
        date: LocalDate,
        note: String?,
        status: EntryStatus,
        transferId: String,
        seriesId: String?,
        now: Long,
    ): MoneyEntryEntity = MoneyEntryEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        amountMinor = amount.minor,
        currencyCode = currencyCode.uppercase(),
        baseAmountMinor = baseMinor,
        rateToBase = rate,
        baseCurrencyCode = baseCode,
        direction = direction,
        occurredOn = date.toEpochDay(),
        // A transfer has no label: it is not a kind of spending, so there is
        // nothing to file it under.
        accountId = accountId,
        isAdjustment = true,
        seriesId = seriesId,
        status = status,
        transferId = transferId,
        note = note,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
    )
}
