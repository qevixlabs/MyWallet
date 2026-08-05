package com.mywallet.data.notify

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.ReminderRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.domain.ReminderSchedule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Once a day, at the hour the user picked: what falls due today.
 *
 * Nothing is posted on a day with nothing on it. A daily notification that
 * arrives whether or not it has anything to say is one the user learns to
 * dismiss without reading, which is worse than no notification at all — and by
 * then it is the one carrying their rent.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsStore,
    private val reminders: ReminderRepository,
    private val recurrence: RecurrenceRepository,
    private val notifier: ReminderNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val current = settings.settings.first()
        if (!current.notifyReminders) return Result.success()

        // What has fallen due since the app was last open has to be written down
        // before it can be counted — the same call every screen makes on open.
        // Without it a phone that has not been unlocked for three days would be
        // told nothing is happening.
        recurrence.materialiseDue()

        // The lead time is this, and only this. It is how many days early to be
        // told, and a notification that only ever counted today would warn the
        // user about the rent on the morning it is taken — which is the thing
        // the setting exists to stop. The tab deliberately does not read it: a
        // page that can be stepped drew every payment on two consecutive days,
        // and *what is coming* is a different question from *what happens on
        // this day*. See `ReminderRepository.lastDay`.
        val due = reminders.due(current.reminderLeadDays)
        if (due.isEmpty) return Result.success()

        notifier.notifyDue(
            reminders = due,
            currencyCode = current.currencyCode,
            // With no warning asked for, everything on the list falls today and
            // the notification can say so. With a lead time it cannot: half of
            // what it is counting has not arrived yet.
            sameDayOnly = current.reminderLeadDays == 0,
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "daily-reminder"
    }
}

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Applies the setting to WorkManager.
     *
     * [realign] is the difference between the user changing the time and the app
     * merely starting up. On a change the schedule has to be torn down and built
     * again, or the next run stays at the old hour; on a launch it must not be,
     * or a phone opened every morning at ten to nine would push the nine o'clock
     * run back a day at a time and the notification would never arrive.
     */
    fun apply(on: Boolean, atMinutes: Int, realign: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        if (!on) {
            workManager.cancelUniqueWork(ReminderWorker.WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(
                ReminderSchedule.minutesUntil(LocalDateTime.now(), atMinutes),
                TimeUnit.MINUTES,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            ReminderWorker.WORK_NAME,
            if (realign) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            },
            request,
        )
    }
}
