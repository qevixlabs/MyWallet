package com.mywallet.data.repo

import com.mywallet.core.money.Money
import com.mywallet.data.db.dao.AccountDao
import com.mywallet.data.db.entity.AccountKind
import com.mywallet.domain.Goal
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a goal's length in step with what is actually in it.
 *
 * The saving each time is what the user committed to, so the length is the thing
 * that gives: money put aside early means fewer contributions are left and the
 * goal arrives sooner, which is why anyone puts money in early. Money taken back
 * out pushes it away again.
 *
 * A class of its own, small as it is, because both sides of the app move a
 * goal's balance and neither can call the other. [PlanRepository] owns the goal
 * card and depends on [WalletRepository]; [WalletRepository] owns the ordinary
 * movements — an entry saved against a goal from the entry form, and the delete
 * that takes one back — so it cannot depend on [PlanRepository] in return. Two
 * copies of this arithmetic drifting apart would leave a goal whose bar
 * disagreed with the day printed beside it.
 *
 * Every caller reaches it holding an account id that is usually not a goal at
 * all, so it is deliberately quiet about that: an account of another kind, or a
 * goal without a full set of terms, is left exactly as it was.
 */
@Singleton
class GoalTermKeeper @Inject constructor(
    private val accountDao: AccountDao,
    private val recurrence: RecurrenceRepository,
    private val clock: Clock,
) {

    /** Works [goalId]'s length out again from the balance it now holds. */
    suspend fun reterm(goalId: String) {
        val goal = accountDao.findById(goalId) ?: return
        if (goal.kind != AccountKind.GOAL) return
        val terms = goal.goalTerms() ?: return

        val today = clock.today()
        val balances = accountDao.observeBalances(today.toEpochDay()).first()
        val saved = balances.firstOrNull { it.accountId == goalId }
            ?.let { Money(it.balanceMinor) } ?: Money.ZERO
        val months = Goal.termAfter(
            saved = saved,
            target = terms.target,
            perPayment = terms.perPayment,
            everyMonths = terms.everyMonths,
            // Whatever the plan has already asked for, which is not up for
            // rewriting: those contributions have been made.
            paymentsDone = terms.plan.paymentDates().count { !it.isAfter(today) },
        )
        // Nothing to write where the length has not moved, which is the ordinary
        // case: every entry saved and every one deleted asks this, and almost
        // none of them are against a goal at all.
        if (months == goal.depositTermMonths) return

        val now = clock.nowMillis()
        accountDao.upsert(goal.copy(depositTermMonths = months, updatedAt = now))
        // The rule stops at the last contribution the new length holds. Only its
        // end moves: the amount and the rhythm are the user's answers, and money
        // arriving is not a reason to restate either.
        goal.premiumSeriesId?.let { seriesId ->
            recurrence.setEndOn(seriesId, terms.copy(termMonths = months).lastPaymentOn)
        }
    }
}
