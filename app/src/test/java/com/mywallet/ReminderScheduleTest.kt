package com.mywallet

import com.mywallet.domain.ReminderSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * When the daily reminder next falls.
 *
 * A background job gets one number from the app — how long to wait — and every
 * way of getting it wrong is silent: too small and the notification arrives the
 * instant the setting is saved, too large and it skips a day, negative and
 * WorkManager fires it immediately.
 */
class ReminderScheduleTest {

    private val nineAm = 9 * 60

    @Test
    fun `later today is the same day`() {
        val minutes = ReminderSchedule.minutesUntil(
            LocalDateTime.parse("2026-07-30T07:30"), nineAm,
        )
        assertEquals(90L, minutes)
    }

    @Test
    fun `a time already gone today is tomorrow's`() {
        val minutes = ReminderSchedule.minutesUntil(
            LocalDateTime.parse("2026-07-30T10:00"), nineAm,
        )
        assertEquals(23L * 60, minutes)
    }

    /**
     * The moment itself counts as gone. The run for it has just happened, and a
     * delay of zero would fire a second one on the spot.
     */
    @Test
    fun `the exact minute is tomorrow's`() {
        val minutes = ReminderSchedule.minutesUntil(
            LocalDateTime.parse("2026-07-30T09:00"), nineAm,
        )
        assertEquals(24L * 60, minutes)
    }

    /** Never zero, however close the clock is to it. */
    @Test
    fun `a minute away is never no time at all`() {
        val minutes = ReminderSchedule.minutesUntil(
            LocalDateTime.parse("2026-07-30T08:59:30"), nineAm,
        )
        assertTrue("expected at least a minute, was $minutes", minutes >= 1L)
    }

    @Test
    fun `midnight is a time like any other`() {
        assertEquals(
            60L,
            ReminderSchedule.minutesUntil(LocalDateTime.parse("2026-07-30T23:00"), 0),
        )
    }
}
