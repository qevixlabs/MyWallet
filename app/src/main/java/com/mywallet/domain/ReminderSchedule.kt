package com.mywallet.domain

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When the next daily reminder falls.
 *
 * Pure, and in `domain` rather than beside the scheduler, because it is the one
 * part of a background job a unit test can reach — and it is the part that is
 * easy to get wrong: a delay of zero fires the moment the setting is saved,
 * which reads as the app shouting the instant it is told to wait until morning.
 */
object ReminderSchedule {

    /**
     * Minutes from [now] until [atMinutes] past midnight next comes round.
     *
     * Never zero and never negative. A time already gone today is tomorrow's,
     * and the exact moment counts as gone: the run for it has just happened.
     */
    fun minutesUntil(now: LocalDateTime, atMinutes: Int): Long {
        val target = LocalTime.of(atMinutes / 60, atMinutes % 60)
        var next = now.toLocalDate().atTime(target)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMinutes().coerceAtLeast(1)
    }
}
