package com.mywallet.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.DigitGrouping
import com.mywallet.domain.SavingsInterest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** How often the app writes a backup file on its own. */
enum class BackupFrequency(val key: String) {
    OFF("off"),
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly");

    companion object {
        fun fromKey(key: String?): BackupFrequency =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * Light, dark, or whatever the phone is set to.
 *
 * [SYSTEM] is the default and the honest one for most people — a phone that
 * turns dark in the evening should take the app with it. The other two exist
 * because plenty of people run their phone one way and want this one the other,
 * and because a money app is read in bright sun and in bed.
 */
enum class ThemeChoice(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeChoice =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Everything the user has chosen, in one snapshot the UI can render from. */
data class AppSettings(
    val currencyCode: String = "NPR",
    val calendarSystem: CalendarSystem = CalendarSystem.GREGORIAN,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /**
     * Whether the app asks for the phone's own lock before it opens.
     *
     * The phone's, deliberately: a passcode of its own would be one more thing
     * to forget and one more thing to store, and the database it guards is the
     * only copy of the user's financial history. What this asks for is the
     * fingerprint or PIN they already have.
     */
    val screenLock: Boolean = false,
    /** BCP-47 tag, or null for "match my phone". */
    val languageTag: String? = null,
    val backupFrequency: BackupFrequency = BackupFrequency.OFF,
    /** SAF tree URI of the folder automatic backups are written to. */
    val backupFolderUri: String? = null,
    val lastBackupAt: Long? = null,
    /**
     * How many days *before* something falls due the user is told about it.
     *
     * Zero — the morning it happens — by default. This is a lead time and not a
     * window onto the future: the Reminders tab answers "what am I being
     * reminded about", and someone who needs a few days' warning to move money
     * says how many. The list it produces is the same either way; what changes
     * is how early a payment joins it.
     */
    val reminderLeadDays: Int = 0,
    /** Whether the phone is told, once a day, what is due. */
    val notifyReminders: Boolean = false,
    /** What time of day it is told, as minutes since midnight. */
    val notifyAtMinutes: Int = DEFAULT_NOTIFY_MINUTES,
    /**
     * Whether the opening questions — language, calendar, currency — have been
     * answered on this phone.
     *
     * They are asked once, before anything else is drawn, because every figure
     * and every date the app shows is written in whatever they answer: a page of
     * rupees read in Nepali is a different app from a page of dollars read in
     * English, and asking afterwards means the first thing the user sees is the
     * wrong one.
     */
    val setupDone: Boolean = false,
) {
    /**
     * Whether the app is being *read* in Nepali.
     *
     * The stored tag is the answer wherever there is one; "match my phone" has to
     * ask the phone, which is what [Locale.getDefault] is doing here — the app
     * sets its own locale through AppCompat, so this is the language the strings
     * are actually coming out in either way.
     *
     * It is asked for one thing: whether somebody is likely to think in Nepali
     * months. The calendar setting is the stronger signal and the one the
     * arithmetic obeys, but a reader in Nepali on Gregorian dates is still
     * somebody the question is worth putting to. See `offersInterestCalendar`.
     */
    val readsNepali: Boolean
        get() = languageTag?.startsWith("ne")
            ?: (Locale.getDefault().language == "ne")
}

/** Nine in the morning: after the phone is picked up, before the day's bills. */
const val DEFAULT_NOTIFY_MINUTES: Int = 9 * 60

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CURRENCY = stringPreferencesKey("currency_code")
        val CALENDAR = stringPreferencesKey("calendar_system")
        val THEME = stringPreferencesKey("theme_choice")
        val SCREEN_LOCK = booleanPreferencesKey("screen_lock")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val REMINDER_LEAD = intPreferencesKey("reminder_lead_days")
        val NOTIFY_REMINDERS = booleanPreferencesKey("notify_reminders")
        val NOTIFY_AT = intPreferencesKey("notify_at_minutes")
        val INTEREST_EVERY = intPreferencesKey("interest_payout_months")
        val INTEREST_SET = booleanPreferencesKey("interest_payout_months_set")
    }

    /**
     * The digit grouping in force, readable without suspending.
     *
     * Every figure the app prints has to be punctuated the same way — see
     * [DigitGrouping] — and the ~thirty places that build a formatter for one
     * row's own currency include several that cannot suspend to ask (a tap
     * handler opening a dialog, for one). So the answer is kept here as it goes
     * past: the flow below writes it on every emission, and a caller with no way
     * to await it reads the last one.
     *
     * Not a second source of truth. It is the same value the flow carries, and
     * the only thing that ever writes it is the flow itself; the worst a reader
     * can get is the answer from a moment ago, which for a display preference
     * the user has just changed is the frame before everything recomposes
     * anyway. It starts at lakhs and crores, which is what the whole app did
     * before the setting existed.
     */
    @Volatile
    var grouping: DigitGrouping = DigitGrouping.SOUTH_ASIAN
        private set

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            currencyCode = prefs[Keys.CURRENCY] ?: "NPR",
            calendarSystem = CalendarSystem.fromKey(prefs[Keys.CALENDAR])
                .also { grouping = it.grouping },
            theme = ThemeChoice.fromKey(prefs[Keys.THEME]),
            screenLock = prefs[Keys.SCREEN_LOCK] ?: false,
            languageTag = prefs[Keys.LANGUAGE],
            backupFrequency = BackupFrequency.fromKey(prefs[Keys.BACKUP_FREQUENCY]),
            backupFolderUri = prefs[Keys.BACKUP_FOLDER],
            lastBackupAt = prefs[Keys.LAST_BACKUP],
            setupDone = prefs[Keys.SETUP_DONE] ?: false,
            reminderLeadDays = (prefs[Keys.REMINDER_LEAD] ?: 0).coerceIn(0, MAX_REMINDER_LEAD),
            notifyReminders = prefs[Keys.NOTIFY_REMINDERS] ?: false,
            notifyAtMinutes = prefs[Keys.NOTIFY_AT] ?: DEFAULT_NOTIFY_MINUTES,
        )
    }

    suspend fun setCurrency(code: String) = edit { it[Keys.CURRENCY] = code }

    suspend fun setCalendarSystem(system: CalendarSystem) =
        edit { it[Keys.CALENDAR] = system.name }

    suspend fun setTheme(choice: ThemeChoice) = edit { it[Keys.THEME] = choice.key }

    suspend fun setScreenLock(on: Boolean) = edit { it[Keys.SCREEN_LOCK] = on }

    /**
     * One way only: the three questions the setup screen asks all have their own
     * row in Settings afterwards, so there is nothing for a second visit to do.
     */
    suspend fun setSetupDone() = edit { it[Keys.SETUP_DONE] = true }

    suspend fun setLanguageTag(tag: String?) = edit { prefs ->
        if (tag == null) prefs.remove(Keys.LANGUAGE) else prefs[Keys.LANGUAGE] = tag
    }

    suspend fun setBackupFrequency(frequency: BackupFrequency) =
        edit { it[Keys.BACKUP_FREQUENCY] = frequency.key }

    suspend fun setBackupFolder(uri: String?) = edit { prefs ->
        if (uri == null) prefs.remove(Keys.BACKUP_FOLDER) else prefs[Keys.BACKUP_FOLDER] = uri
    }

    suspend fun setLastBackupAt(timestamp: Long) = edit { it[Keys.LAST_BACKUP] = timestamp }

    suspend fun setReminderLeadDays(days: Int) =
        edit { it[Keys.REMINDER_LEAD] = days.coerceIn(0, MAX_REMINDER_LEAD) }

    /**
     * The interval this phone used to credit *every* account on, taken away as
     * it is read. Null once there is nothing left to take.
     *
     * How often the bank pays is now a fact about the account and lives on the
     * account row — somebody with a savings account at two banks has two answers,
     * and one setting could only be right about one of them. But a phone that
     * answered the old question has been crediting real interest on that answer,
     * and no migration can reach a preference, so the accounts adopt it on the
     * next launch instead. Taken rather than merely read so that is once and for
     * ever: an account created afterwards is the user answering for itself, and
     * a second sweep would overwrite them all with the figure they replaced.
     *
     * Clamped on the way out, exactly as it was on the way in.
     */
    suspend fun takeInterestPayoutMonths(): Int? {
        val stored = context.dataStore.data.first()[Keys.INTEREST_EVERY] ?: return null
        edit {
            it.remove(Keys.INTEREST_EVERY)
            it.remove(Keys.INTEREST_SET)
        }
        return SavingsInterest.gapOf(stored)
    }

    suspend fun setNotifyReminders(on: Boolean) = edit { it[Keys.NOTIFY_REMINDERS] = on }

    suspend fun setNotifyAt(minutes: Int) =
        edit { it[Keys.NOTIFY_AT] = minutes.coerceIn(0, 24 * 60 - 1) }

    companion object {
        /**
         * Six days' warning. Past a week ahead a payment is not something to be
         * reminded of — it is the month's plan, which is the Timeline's job.
         */
        const val MAX_REMINDER_LEAD = 6
    }

    /**
     * Throws away every answer the user has given, back to a phone that has
     * never been opened.
     *
     * Only *Start over* calls this, and only because that is what start over
     * means now: the figures and the answers about them go together, and the app
     * opens on the welcome questions the next time it is launched. Anything less
     * left a cleared app still reading in a language and a currency chosen for
     * figures that are no longer there.
     */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
