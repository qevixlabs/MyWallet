package com.mywallet.data.repo

import android.content.Context
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.FixedDeposit
import com.mywallet.domain.Goal
import com.mywallet.domain.Insurance
import com.mywallet.domain.ProjectedEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The day a holding with a term hands its money back.
 *
 * Three arrangements end this way and they end identically. A **fixed deposit**
 * sits still for its whole life: nothing may be paid into it and nothing spent
 * from it, so it produces no entries and needs no rule, and on exactly one day
 * the whole of it — deposit and interest together — moves into the account the
 * user chose. An **insurance policy** is paid for in premiums, each of which is
 * a movement of its own, and then on one day the insurer hands over the sum
 * assured. A **goal** is the same shape again, and hands back exactly what was
 * put into it. Different arrangements, one shape: a figure, a date, and
 * somewhere it goes.
 *
 * That day is drawn as a forecast and nothing more. Nothing is written to the
 * timeline when it arrives: the app records the arrangement and leaves the
 * moving of the money to the user, exactly as it records a loan without
 * pretending to have watched the money land. An automatic payout is the one
 * thing that can make the money disappear — credited to an account whose balance
 * the user has since corrected by hand, or skipped as back-dated and credited
 * nowhere at all — and a forecast cannot.
 *
 * Drawn as a movement between two of the user's own holdings — the same two-line
 * shape a transfer takes — because that is exactly what it is.
 */
@Singleton
class MaturityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val exchangeRates: ExchangeRateRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /**
     * Every deposit, policy and goal coming to its day between tomorrow and
     * [horizon], as the pair of rows that movement is.
     *
     * Two halves sharing a day, like a transfer: one leaving the holding so its
     * forward balance falls to nothing, one arriving so the destination's rises.
     * Only the leaving half is drawn — it names both ends — and the arriving one
     * is there because the balances need it.
     *
     * A maturity that has already passed is not projected. The money is not
     * "about to arrive"; it arrived, and the holding has been showing its
     * matured value ever since. Recording it as a real entry would be the app
     * moving money into an account it never watched arrive, which is the same
     * mistake as debiting a back-dated instalment.
     */
    suspend fun maturingBetween(horizon: LocalDate): List<ProjectedEntry> {
        val today = clock.today()
        if (!today.isBefore(horizon)) return emptyList()
        val baseCode = settings.settings.first().currencyCode
        val accounts = accountDao.observeActive().first()
        val byId = accounts.associateBy { it.id }
        // What each holding is worth today, for the one arrangement whose
        // payout is not a figure agreed in advance — see [payout].
        val balances = accountDao.observeBalances(today.toEpochDay()).first()
            .associate { it.accountId to Money(it.balanceMinor) }
        val projected = mutableListOf<ProjectedEntry>()

        for (account in accounts) {
            val payout = account.payout(
                today = today,
                balance = balances[account.id] ?: Money.ZERO,
            ) ?: continue
            if (payout.on.isBefore(today) || payout.on.isAfter(horizon)) continue
            val value = payout.value
            if (!value.isPositive) continue

            val into = account.maturesIntoAccountId?.let { byId[it] }
            val inBase = convert(value, account.currencyCode, baseCode)
            // What will land where it is going, in that account's own currency.
            // A deposit in dollars maturing into a rupee account arrives as
            // rupees, and the row has to carry both figures — the same pair a
            // cross-currency transfer draws.
            val arrivingCode = into?.currencyCode ?: baseCode
            val arriving = if (arrivingCode.equals(account.currencyCode, ignoreCase = true)) {
                value
            } else {
                convert(value, account.currencyCode, arrivingCode)
            }
            val title = context.getString(payout.titleRes)

            projected += ProjectedEntry(
                // Derived from the account rather than a rule's id, because
                // there is no rule. Nothing opens it — see [isDepositMaturity].
                seriesId = "fd-${account.id}",
                date = payout.on,
                amount = value,
                currencyCode = account.currencyCode,
                baseAmount = inBase,
                direction = Direction.OUT,
                accountId = account.id,
                accountName = account.name,
                accountInstitution = account.institution,
                showInDisplayCurrency = account.showInDisplayCurrency,
                note = title,
                // Money of the user's moving between two places of theirs. Not
                // spending, and the month it falls in must not read as an
                // expensive one.
                isAdjustment = true,
                title = null,
                transferFromName = account.name,
                transferToName = into?.name,
                transferPartnerAmount = arriving,
                transferPartnerCurrency = arrivingCode,
                isDepositMaturity = true,
            )

            // The arriving half. Absent when the account it was meant for has
            // been deleted: the money still comes free — the row above says so
            // — but there is nowhere for the app to say it went.
            if (into != null) {
                projected += ProjectedEntry(
                    seriesId = "fd-${account.id}",
                    date = payout.on,
                    amount = arriving,
                    currencyCode = into.currencyCode,
                    baseAmount = inBase,
                    direction = Direction.IN,
                    accountId = into.id,
                    accountName = into.name,
                    accountInstitution = into.institution,
                    showInDisplayCurrency = into.showInDisplayCurrency,
                    note = title,
                    isAdjustment = true,
                    title = null,
                    transferFromName = account.name,
                    transferToName = into.name,
                    transferPartnerAmount = value,
                    transferPartnerCurrency = account.currencyCode,
                    isTransferArrival = true,
                    isDepositMaturity = true,
                )
            }
        }
        return projected
    }

    /** Converted at today's rate, and left as it is when there is no rate at all. */
    private suspend fun convert(amount: Money, from: String, to: String): Money {
        if (from.equals(to, ignoreCase = true)) return amount
        return exchangeRates.convert(
            amountMinor = amount.minor,
            from = from,
            fromMinorUnits = CurrencyOption.byCode(from).minorUnits,
            base = to,
            baseMinorUnits = CurrencyOption.byCode(to).minorUnits,
        ).amount
    }
}

/** What a holding hands back, and the day it does. */
private data class Payout(val value: Money, val on: LocalDate, val titleRes: Int)

/**
 * The one payment this holding is going to make, or null when it makes none.
 *
 * A deposit hands back what it holds plus the interest its own terms earn; a
 * policy hands back the figure the insurer printed on it, which is not derived
 * from the premiums and never could be. Null on every other kind, and on one so
 * half-described that no day or figure can be worked out — which the form
 * prevents but a restored backup might not.
 */
private fun AccountEntity.payout(today: LocalDate, balance: Money): Payout? = when (kind) {
    AccountKind.FIXED_DEPOSIT -> depositTerms()?.let {
        Payout(FixedDeposit.maturityValue(it), it.maturesOn, R.string.fd_row_matures)
    }
    AccountKind.INSURANCE -> policyTerms()?.let {
        Payout(it.maturityAmount, it.maturesOn, R.string.insurance_row_matures)
    }
    // A goal hands back what is in it and not a rupee more. Nobody tops it up,
    // so the honest figure is what it holds today plus the contributions still
    // to come — which equals the target where the plan has been kept to, and
    // says less where it has not. Promising the target regardless would have the
    // forecast credit an account with money nobody put aside.
    AccountKind.GOAL -> goalTerms()?.let {
        val toCome = Money(it.perPayment.minor * it.plan.paymentsAfter(today))
        Payout(balance + toCome, it.targetOn, R.string.goal_row_reached)
    }
    else -> null
}

/**
 * The stored row's terms, or null when it is not a deposit or is only half
 * described. Mirrors [com.mywallet.domain.Account.depositTerms], for the
 * repository half that works in entities rather than domain models.
 */
private fun AccountEntity.depositTerms(): FixedDeposit.Terms? {
    if (kind != AccountKind.FIXED_DEPOSIT) return null
    val started = depositStartedOn?.let { LocalDate.ofEpochDay(it) } ?: return null
    val months = depositTermMonths?.takeIf { it > 0 } ?: return null
    return FixedDeposit.Terms(
        principal = Money(openingBalanceMinor),
        annualRate = annualRate ?: 0.0,
        startedOn = started,
        termMonths = months,
    )
}

/** The same for a policy — see [com.mywallet.domain.Account.policyTerms]. */
private fun AccountEntity.policyTerms(): Insurance.Terms? {
    if (kind != AccountKind.INSURANCE) return null
    val started = depositStartedOn?.let { LocalDate.ofEpochDay(it) } ?: return null
    val months = depositTermMonths?.takeIf { it > 0 } ?: return null
    val every = premiumEveryMonths?.takeIf { it > 0 } ?: return null
    return Insurance.Terms(
        premium = Money(premiumMinor ?: 0L),
        maturityAmount = Money(maturityAmountMinor ?: 0L),
        startedOn = started,
        termMonths = months,
        everyMonths = every,
        inBikramSambat = planRecurInBs,
    )
}
