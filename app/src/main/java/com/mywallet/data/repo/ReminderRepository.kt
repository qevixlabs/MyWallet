package com.mywallet.data.repo

import com.mywallet.core.money.Money
import com.mywallet.data.db.entity.Direction
import com.mywallet.domain.MoneyEntry
import com.mywallet.domain.ProjectedEntry
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One thing happening on a day the user is being reminded about.
 *
 * Two shapes, because a day that has arrived and a day that has not are
 * genuinely different: an occurrence whose date is today was materialised into a
 * real row the moment the app opened, and every balance in the app already
 * counts it. Saying "due" over it would ask the user to do something that has
 * been done.
 */
sealed interface Reminder {
    val date: LocalDate

    /**
     * What identifies this row across a redraw.
     *
     * A written-down row has an id of its own; a projection has none, being
     * computed rather than stored, so it is named by the rule and the day it
     * falls on — which is exactly the pair the timeline keys its own projected
     * rows by, and the pair a skip is recorded against. Needed because the rows
     * can be swiped: a lazy list without stable keys reuses the box being
     * dragged for whatever lands in that slot next, so the finger finishes a
     * gesture on a different payment from the one it started on.
     */
    val key: String

    /** Written down already — today's occurrences, and anything dated ahead. */
    data class Recorded(val entry: MoneyEntry) : Reminder {
        override val date: LocalDate get() = entry.occurredOn
        override val key: String get() = entry.id
    }

    /** Still to come: computed from a rule, the bank's schedule, or a term ending. */
    data class Due(val projected: ProjectedEntry) : Reminder {
        override val date: LocalDate get() = projected.date
        override val key: String
            get() = "${projected.seriesId}-${projected.date.toEpochDay()}"
    }
}

/**
 * What the user is being reminded about, soonest first.
 *
 * One flat list rather than a day-by-day plan. Everything on it is being asked
 * for *now* — a payment falls on the list the moment it is within the lead time,
 * and the row's own date says which day it lands on, exactly as it does on Home.
 * Grouped under "Today", "Tomorrow" and a date it read as a forecast of the week
 * ahead, which is the Timeline's job and not this one's.
 *
 * [moneyOut] and [moneyIn] are what the notification says, and only that — the
 * page itself carries no total. Adjustments are left out of both, exactly as they
 * are everywhere else: a transfer between the user's own accounts is neither
 * earned nor spent, and counting one would make a day with a single transfer on
 * it read as an expensive day.
 */
data class Reminders(
    val rows: List<Reminder> = emptyList(),
    val moneyIn: Money = Money.ZERO,
    val moneyOut: Money = Money.ZERO,
) {
    val count: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * What the user needs reminding of, gathered from every place money is
 * scheduled.
 *
 * The timeline answers "what does this month look like" and buries today in it.
 * This answers the question actually asked in the morning — what wants doing —
 * and it is deliberately built from the same four sources, so a row here and the
 * row for the same payment on the timeline cannot disagree:
 *
 *  - occurrences a rule has already materialised, which is what *today* is made
 *    of: the app writes them the moment it opens, so nothing today is ever a
 *    projection;
 *  - occurrences still to come, from the rules the user wrote;
 *  - a savings quarter's interest, which is the bank's rule rather than theirs;
 *  - a deposit maturing, a policy paying out, a goal coming due.
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val wallet: WalletRepository,
    private val recurrence: RecurrenceRepository,
    private val interest: InterestRepository,
    private val maturities: MaturityRepository,
    private val clock: Clock,
) {

    /**
     * The furthest day the *notification* reaches, given [leadDays] of warning.
     *
     * Zero lead is today and nothing else — the morning it happens. Two days'
     * lead reaches the day after tomorrow, so a payment is announced on the
     * morning two days before it is taken.
     *
     * **The lead belongs to the notification and to nothing else.** It used to
     * govern the tab as well, on the reasoning that one number should mean one
     * thing on both — but the tab can be *stepped*, and a window that moved with
     * the day being looked at drew every payment on two consecutive pages: a
     * reader walking forward day by day met the same rent twice and read it as
     * the app having recorded it twice. What the page answers is "what happens
     * on this day", which is one day's worth; what the notification answers is
     * "what is coming", which is what a warning is for.
     */
    fun lastDay(leadDays: Int, from: LocalDate = clock.today()): LocalDate =
        from.plusDays(leadDays.coerceAtLeast(0).toLong())

    /**
     * Everything being reminded of, one call — for the daily notification, which
     * has no flow to collect and only needs the answer once.
     */
    suspend fun due(leadDays: Int): Reminders {
        val today = clock.today()
        val last = lastDay(leadDays)
        val entries = wallet.observeEntries(today, last.plusDays(1)).first()
        return build(today, last, entries)
    }

    /**
     * One day, for the tab — from a list of entries the caller is already
     * observing.
     *
     * Split from [due] so the screen can stay live: its entries come from a Room
     * flow and redraw themselves, while the projections — which are computed
     * rather than stored — are worked out again each time that flow emits.
     *
     * Exactly [day], never a window: see [lastDay] for why the lead time stops
     * at the notification.
     */
    suspend fun onDay(day: LocalDate, entries: List<MoneyEntry>): Reminders {
        // Never behind today: a day that has been and gone is not something that
        // wants doing, and the projections below are only computed forwards
        // anyway — a past day would list its real rows and silently drop every
        // scheduled one, which is a day described as half of itself.
        val first = maxOf(day, clock.today())
        return build(first, first, entries)
    }

    private suspend fun build(
        first: LocalDate,
        last: LocalDate,
        entries: List<MoneyEntry>,
    ): Reminders {
        // A transfer is one movement, drawn once — the paying half, which names
        // both ends. A balance correction is not a movement at all. Both rules
        // are the timeline's, and a reminder that broke either would be the same
        // money described two different ways on two screens.
        val recorded = entries
            .filter { it.occurredOn in first..last }
            .filterNot { it.isTransferArrival || it.isBalanceCorrection }
            .map(Reminder::Recorded)

        val projected = (
            recurrence.projectForward(last) +
                interest.projectDue(last) +
                maturities.maturingBetween(last)
            )
            .filter { it.date in first..last }
            .filterNot { it.isTransferArrival }
            .map(Reminder::Due)

        // Soonest first. One day at a time this settles nothing on the tab, but
        // the notification counts a window and its named row has to be the
        // nearest thing due rather than whichever the query happened to return.
        val rows = (recorded + projected).sortedBy { it.date }

        return Reminders(
            rows = rows,
            moneyIn = rows.sumIn(),
            moneyOut = rows.sumOut(),
        )
    }

    private fun List<Reminder>.sumIn(): Money = total(Direction.IN)

    private fun List<Reminder>.sumOut(): Money = total(Direction.OUT)

    /**
     * What the rows going one way come to, in the display currency.
     *
     * Adjustments are skipped on both sides — a transfer between the user's own
     * accounts is neither earned nor spent — which is the same rule the month's
     * totals follow, and the reason a day of nothing but transfers reads as zero
     * rather than as an expensive one.
     */
    private fun List<Reminder>.total(direction: Direction): Money = Money(
        sumOf { row ->
            when (row) {
                is Reminder.Recorded -> row.entry
                    .takeIf { it.counts && it.direction == direction }
                    ?.baseAmount?.minor ?: 0L
                is Reminder.Due -> row.projected
                    .takeIf { !it.isAdjustment && it.direction == direction }
                    ?.baseAmount?.minor ?: 0L
            }
        }
    )
}
