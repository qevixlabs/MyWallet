package com.mywallet.data.repo

import androidx.compose.ui.graphics.Color
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.entity.AccountEntity
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.data.db.entity.Direction
import com.mywallet.data.db.entity.RecurrenceInterval
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.Goal
import com.mywallet.domain.Insurance
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A holding fed by a rule: an insurance policy, or a savings goal.
 *
 * Two rows come out of either. The **holding** is an account, because what has
 * been paid into it is still the user's money — it holds every payment so far,
 * and that is what the Accounts list shows and what net worth counts. The
 * **payments** are an ordinary repeating rule pointed at it, so the schedule
 * needs no machinery of its own: dates that have arrived are already real rows
 * in the timeline, dates still to come are projected from the same rule, and
 * editing the holding corrects both at once.
 *
 * Each payment is a **transfer** rather than spending. The money leaves the bank
 * and lands in the policy or the goal; it has not gone anywhere, and a month
 * with one in it must not read as an expensive one. That is also why the
 * balance needs no special case — it is the ordinary sum of the rows that name
 * it, which is exactly "what has been put in so far".
 *
 * The one difference between the two is which figure is given and which is
 * worked out. A policy is told what each premium costs and what it will pay
 * out — both facts off a document, neither derivable from the other. A goal is
 * told only what it is for, and the app divides that into contributions. Below
 * this line they are the same arrangement, which is why they share a save.
 *
 * Nothing is written when either one ends. That is a forecast
 * ([MaturityRepository]), for the reason a deposit's payout is: the app records
 * the arrangement and leaves the moving of the money to the user, rather than
 * crediting an account they may since have corrected by hand.
 */
@Singleton
class PlanRepository @Inject constructor(
    private val wallet: WalletRepository,
    private val recurrence: RecurrenceRepository,
    private val transfers: TransferRecorder,
    private val accountDao: AccountDao,
    private val goalTerms: GoalTermKeeper,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /**
     * A policy: what it pays out and what each premium costs are both given.
     *
     * @param payFromAccountId the account the premiums leave. Required — a
     *   premium is a standing instruction to a bank, and one with nowhere to
     *   take the money from is not a schedule.
     * @param maturesIntoAccountId where the payout is forecast to land, which is
     *   optional: nothing is ever written on that day, so a forecast naming only
     *   the policy the money leaves still says the day and the figure.
     */
    @Suppress("LongParameterList")
    suspend fun savePolicy(
        id: String?,
        name: String,
        currencyCode: String,
        color: Color,
        showInDisplayCurrency: Boolean,
        maturityAmount: Money,
        premium: Money,
        startedOn: LocalDate,
        termMonths: Int,
        everyMonths: Int,
        payFromAccountId: String,
        maturesIntoAccountId: String?,
        /**
         * Whether this plan counts its months in whichever calendar is set.
         * Null keeps whatever the plan already answered, which is what a re-save
         * that changed the payout account means; new plans say yes.
         */
        optedIntoSelectedCalendar: Boolean? = null,
    ): SaveResult {
        val terms = Insurance.Terms(
            premium = premium,
            maturityAmount = maturityAmount,
            startedOn = startedOn,
            termMonths = termMonths,
            everyMonths = everyMonths,
        )
        return savePlan(
            id = id,
            kind = AccountKind.INSURANCE,
            name = name,
            currencyCode = currencyCode,
            color = color,
            showInDisplayCurrency = showInDisplayCurrency,
            aimedAt = maturityAmount,
            perPayment = premium,
            startedOn = startedOn,
            termMonths = termMonths,
            everyMonths = everyMonths,
            lastPaymentOn = terms.lastPaymentOn,
            payFromAccountId = payFromAccountId,
            maturesIntoAccountId = maturesIntoAccountId,
            optedIntoSelectedCalendar = optedIntoSelectedCalendar,
        )
    }

    /**
     * A goal: only the figure to reach is given, and what it costs each time is
     * divided out of it.
     *
     * The contribution is worked out here rather than at every read, and stored
     * beside the target, because the rule that moves the money is written from
     * it: a figure re-divided later could disagree with the payments already
     * made against the old one.
     */
    @Suppress("LongParameterList")
    suspend fun saveGoal(
        id: String?,
        name: String,
        currencyCode: String,
        color: Color,
        showInDisplayCurrency: Boolean,
        target: Money,
        startedOn: LocalDate,
        termMonths: Int,
        everyMonths: Int,
        payFromAccountId: String,
        maturesIntoAccountId: String?,
        /**
         * Whether this plan counts its months in whichever calendar is set.
         * Null keeps whatever the plan already answered, which is what a re-save
         * that changed the payout account means; new plans say yes.
         */
        optedIntoSelectedCalendar: Boolean? = null,
    ): SaveResult {
        val terms = Goal.Terms(
            target = target,
            startedOn = startedOn,
            termMonths = termMonths,
            everyMonths = everyMonths,
        )
        return savePlan(
            id = id,
            kind = AccountKind.GOAL,
            name = name,
            currencyCode = currencyCode,
            color = color,
            showInDisplayCurrency = showInDisplayCurrency,
            aimedAt = target,
            perPayment = terms.perPayment,
            startedOn = startedOn,
            termMonths = termMonths,
            everyMonths = everyMonths,
            lastPaymentOn = terms.lastPaymentOn,
            payFromAccountId = payFromAccountId,
            maturesIntoAccountId = maturesIntoAccountId,
            optedIntoSelectedCalendar = optedIntoSelectedCalendar,
        )
    }

    /**
     * Money into or out of a goal outside its plan.
     *
     * An ordinary transfer, exactly like the contributions the rule makes: a
     * deposit leaves the account and lands in the goal, a withdrawal does the
     * reverse. Both are movements between two of the user's own holdings, so
     * neither is spending and neither is income.
     *
     * What it changes besides the balance is the **length**. The saving each
     * time stays what the user decided they can manage, so a deposit means
     * fewer contributions are left and the goal arrives sooner — which is why
     * anyone puts money in early. The term is rewritten from what is left to
     * save, and the rule's last day moves with it so no contribution falls after
     * the goal has been reached.
     *
     * @param accountId the account the money moves through, which is a fact
     *   about this movement and is never written back to the goal. A goal
     *   usually fed from the bank can still be topped up once in cash.
     */
    suspend fun moveGoalMoney(
        goalId: String,
        amount: Money,
        deposit: Boolean,
        accountId: String,
        on: LocalDate,
    ): SaveResult {
        if (!amount.isPositive) return SaveResult.AmountRequired
        val goal = accountDao.findById(goalId) ?: return SaveResult.AccountRequired
        if (goal.kind != AccountKind.GOAL) return SaveResult.AccountRequired
        val terms = goal.goalTerms() ?: return SaveResult.AccountRequired

        val recorded = transfers.record(
            transferId = null,
            fromAccountId = if (deposit) accountId else goalId,
            toAccountId = if (deposit) goalId else accountId,
            amount = amount,
            date = on,
            // Named for the goal, like every other row it is involved in: the
            // timeline already draws both ends, so the name is what says which
            // goal this is.
            note = goal.name,
        )
        if (recorded !is SaveResult.Success) return recorded
        goalTerms.reterm(goalId)
        return recorded
    }

    /**
     * Writes the holding, then the rule that feeds it, then ties the two
     * together.
     *
     * In that order because neither can be written first on its own: the rule
     * has to name the account the money lands in, and the account has to name
     * the rule to find it again when the holding is edited.
     */
    /**
     * Puts every holding that follows the calendar back in step with the one now
     * set — a policy, a goal, a fixed deposit.
     *
     * The same job [LoanRepository.recalendarSchedules] does for the debts, and
     * needed for the same reason: the opt-in is what the user answered, but what
     * the dates are generated from is the joined answer, stored on the row so
     * that every reader of a premium date or a maturity can see it without
     * reaching for the setting. Change the setting and that stored answer is
     * stale until something restates it.
     *
     * Only holdings that opted in are touched, so for almost everybody this is a
     * walk that changes nothing.
     */
    suspend fun recalendarPlans() {
        // Asked once, not once per holding: every opted-in holding gets the same
        // answer, because the opt-in is the half that varies and it is already
        // true for all of them by the time the loop acts.
        val steps = CalendarSystem.forInterest(
            optedIn = true, setting = settings.settings.first().calendarSystem,
        ) == CalendarSystem.BIKRAM_SAMBAT
        val now = clock.nowMillis()
        for (account in accountDao.allActive()) {
            if (!account.interestInBs) continue
            // Only the kinds whose *dates* are counted in months. A savings
            // account may have opted in too, but what that moves is its interest
            // periods — worked out by `postDueInterest` from the opt-in
            // directly — and writing a plan's column onto it would be storing an
            // answer to a question it was never asked.
            if (account.kind !in COUNTS_MONTHS) continue
            if (account.planRecurInBs != steps) {
                accountDao.upsert(account.copy(planRecurInBs = steps, updatedAt = now))
            }
            // And the rule that makes its payments, which generates from the
            // joined answer too.
            account.premiumSeriesId?.let { recurrence.setRecurInBs(it, steps) }
        }
    }

    private companion object {
        /**
         * The holdings whose own length or rhythm is counted in whole months —
         * a policy's premiums, a goal's contributions, a deposit's term.
         */
        val COUNTS_MONTHS = setOf(
            AccountKind.INSURANCE, AccountKind.GOAL, AccountKind.FIXED_DEPOSIT,
        )
    }

    @Suppress("LongParameterList")
    private suspend fun savePlan(
        id: String?,
        kind: AccountKind,
        name: String,
        currencyCode: String,
        color: Color,
        showInDisplayCurrency: Boolean,
        aimedAt: Money,
        perPayment: Money,
        startedOn: LocalDate,
        termMonths: Int,
        everyMonths: Int,
        lastPaymentOn: LocalDate?,
        payFromAccountId: String,
        maturesIntoAccountId: String?,
        /**
         * Whether this plan counts its months in whichever calendar is set.
         * Null keeps whatever the plan already answered, which is what a re-save
         * that changed the payout account means; new plans say yes.
         */
        optedIntoSelectedCalendar: Boolean? = null,
    ): SaveResult {
        // Which calendar this plan counts its months in — the one the user is
        // reading when they set it up, exactly as a rule they write by hand
        // takes. It is written onto the account *and* passed to the rule below
        // in the same call, because everything that draws a premium date reads
        // the account and everything that generates one reads the rule: two
        // copies of one answer, written together so they cannot drift.
        //
        // An existing plan keeps its own, since re-saving it to change the
        // payout account is not a request to move twenty years of premiums.
        // The plan's own opt-in — on by default, because a plan is the user's own
        // arrangement counted in the months they read, not a bank's schedule
        // pinned to English ones. What it comes to is that and the setting
        // together, which is what goes on the account and on the rule.
        val usesSelectedCalendar = optedIntoSelectedCalendar
            ?: accountDao.findById(id.orEmpty())?.interestInBs
            ?: true
        val recurInBs = CalendarSystem.forInterest(
            usesSelectedCalendar, settings.settings.first().calendarSystem,
        ) == CalendarSystem.BIKRAM_SAMBAT
        val saved = wallet.saveAccount(
            id = id,
            name = name,
            kind = kind,
            currencyCode = currencyCode,
            institution = null,
            // Nothing is put into either except its own payments, and every one
            // of them is a row. An opening balance here would be a payment
            // nobody made.
            openingBalance = Money.ZERO,
            color = color,
            showInDisplayCurrency = showInDisplayCurrency,
            depositStartedOn = startedOn,
            depositTermMonths = termMonths,
            maturesIntoAccountId = maturesIntoAccountId,
            maturityAmount = aimedAt,
            perPayment = perPayment,
            premiumEveryMonths = everyMonths,
            planRecurInBs = recurInBs,
            interestInBs = usesSelectedCalendar,
        )
        if (saved !is SaveResult.Success) return saved

        val existing = accountDao.findById(saved.id)?.premiumSeriesId
        val seriesId = recurrence.saveSeries(
            id = existing,
            amount = perPayment,
            currencyCode = currencyCode,
            // Out of the account, into the holding. The rule carries both ends,
            // which is what makes each occurrence one movement rather than two
            // unexplained ones.
            direction = Direction.OUT,
            accountId = payFromAccountId,
            transferToAccountId = saved.id,
            // The gap in months exactly as it was agreed — the named intervals
            // cannot say "every two months", and rounding one to the nearest
            // chip would move every payment after the first.
            interval = RecurrenceInterval.MONTHLY,
            intervalMonths = everyMonths,
            startOn = startedOn,
            // The rule stops at the last payment, not at the end of the term:
            // the day the money comes back is a day nothing goes in.
            endOn = lastPaymentOn,
            // Named for the holding, so the row says what the money is for
            // wherever it appears.
            note = name.trim().takeIf { it.isNotEmpty() },
            usesSelectedCalendar = usesSelectedCalendar,
        )
        accountDao.setPremiumSeries(saved.id, seriesId, clock.nowMillis())
        return saved
    }
}

/**
 * The stored row's goal terms — see [com.mywallet.domain.Account.goalTerms],
 * which answers the same question for the domain half of the app.
 */
internal fun AccountEntity.goalTerms(): Goal.Terms? {
    if (kind != AccountKind.GOAL) return null
    val started = depositStartedOn?.let { LocalDate.ofEpochDay(it) } ?: return null
    val months = depositTermMonths?.takeIf { it > 0 } ?: return null
    val every = premiumEveryMonths?.takeIf { it > 0 } ?: return null
    return Goal.Terms(
        target = Money(maturityAmountMinor ?: 0L),
        startedOn = started,
        termMonths = months,
        everyMonths = every,
        inBikramSambat = planRecurInBs,
    )
}
