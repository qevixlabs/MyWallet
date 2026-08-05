package com.mywallet.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mywallet.MainActivity
import com.mywallet.R
import com.mywallet.core.money.CurrencyOption
import com.mywallet.core.money.MoneyFormatter
import com.mywallet.data.repo.Reminder
import com.mywallet.data.repo.Reminders
import com.mywallet.data.settings.SettingsStore
import com.mywallet.ui.nav.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the phone what falls due today.
 *
 * Deliberately short and deliberately silent about detail: how many things, what
 * they come to, and a tap that opens the app. A notification listing the
 * payments themselves would put someone's rent and their loan on a lock screen
 * anybody can read over their shoulder — and this app's whole premise is that
 * the figures live on the phone and nowhere else.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * Posts the day's reminder, or does nothing if the phone is not accepting
     * notifications.
     *
     * Checked rather than assumed: the permission can be refused when the switch
     * is turned on and revoked at any time afterwards, and posting into a void
     * would leave the setting reading as though it were working.
     */
    fun notifyDue(
        reminders: Reminders,
        currencyCode: String,
        /**
         * True when nothing on the list falls later than today, so it can be
         * said in those words. With a lead time it cannot: some of what is being
         * counted is a few days out, and calling that "due today" would have the
         * user looking for money that is not going anywhere yet.
         */
        sameDayOnly: Boolean = true,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannel()

        val money = MoneyFormatter(CurrencyOption.byCode(currencyCode), grouping = settings.grouping)
        val body = listOfNotNull(
            reminders.moneyOut
                .takeIf { !it.isZero }
                ?.let { context.getString(R.string.reminders_to_pay, money.formatCompact(it)) },
            reminders.moneyIn
                .takeIf { !it.isZero }
                ?.let { context.getString(R.string.reminders_coming_in, money.formatCompact(it)) },
        ).joinToString(" · ")

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                // Where the tap lands. A notification about what falls due today
                // opening the summary of the month is an answer to a question
                // nobody asked: the list it is counting is on Reminders, and the
                // user then had to find the tab themselves.
                .putExtra(MainActivity.EXTRA_DESTINATION, Routes.REMINDERS)
                // And which day, because the tab answers for one day and this
                // may be counting a later one. Asked to be told a day early, the
                // user gets a note about tomorrow's rent and would land on
                // today's page, where it is not — the tap has to open the day
                // the notification is about. The soonest of them: the rows are
                // sorted, and what is nearest is what the reader is being warned
                // about first. Today at a lead of zero, which is every user who
                // has not asked to be warned early.
                .putExtra(
                    MainActivity.EXTRA_DAY,
                    reminders.rows.firstOrNull()?.date?.toEpochDay() ?: -1L,
                )
                // SINGLE_TOP alongside CLEAR_TOP so a running app is handed the
                // intent through onNewIntent instead of being destroyed and
                // rebuilt — without it the extra arrives, but at the cost of
                // throwing away every screen the user had open.
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            // Immutable, because nothing is being handed to the system to fill
            // in — and mutable pending intents are a permission leak.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(
                // One thing due is named; several are counted.
                //
                // "1 thing due today" is the least useful sentence the app can
                // say — the reader has to open it to find out whether it is the
                // rent or a magazine, which is exactly what a reminder exists to
                // save them. Where there *is* only one, its own words are both
                // shorter and the whole answer.
                //
                // It stops at one deliberately. Two or three named on a lock
                // screen is a list of what the user owes, readable by whoever
                // picks the phone up, and it grows past the one line a
                // notification gets anyway; the count is the honest summary
                // there. See the note above on what this deliberately withholds.
                reminders.rows.singleOrNull()?.let { shortName(it) }
                    ?: context.resources.getQuantityString(
                        if (sameDayOnly) {
                            R.plurals.notify_due_count
                        } else {
                            R.plurals.notify_coming_count
                        },
                        reminders.count,
                        reminders.count,
                    )
            )
            .setContentText(body.takeIf { it.isNotEmpty() })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // A fixed id, so today's note replaces yesterday's rather than stacking
        // a week of them in the shade.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check above and here. There
            // is nothing to tell the user — the app is not running.
        }
    }

    /**
     * What one reminder is called, in as few words as it has.
     *
     * The user's own note first, which is what every list in the app leads with
     * and the only part they wrote themselves; then the debt an instalment
     * belongs to, or the name the app's own schedule gave the row. Null when
     * there is nothing but a direction to report, and the caller falls back to
     * the count — "Money out" as a notification title says less than "1 thing
     * due today" does.
     *
     * Deliberately not `entryTitle`, which is the same idea for a row on a
     * screen: that one is a Composable and reaches for `stringResource`, and it
     * resolves branches that only matter beside an amount and a date. This is
     * the short form, and short is the whole requirement here.
     */
    private fun shortName(row: Reminder): String? = when (row) {
        is Reminder.Recorded -> row.entry.note?.takeIf { it.isNotBlank() }
            ?: row.entry.loanName?.takeIf { it.isNotBlank() }
        is Reminder.Due -> row.projected.note?.takeIf { it.isNotBlank() }
            ?: row.projected.title?.takeIf { it.isNotBlank() }
            ?: row.projected.loanName?.takeIf { it.isNotBlank() }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_reminders),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notify_channel_reminders_explain)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "reminders"
        const val NOTIFICATION_ID = 1001
    }
}
