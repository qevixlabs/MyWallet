package com.mywallet.ui.screens

import android.net.Uri
import androidx.annotation.StringRes
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mywallet.BuildConfig
import com.mywallet.ads.AdConsent
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.mywallet.ui.canLock
import com.mywallet.ui.lockHost
import com.mywallet.ui.askToUnlock
import com.mywallet.R
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.data.backup.BackupManager
import com.mywallet.data.backup.BackupTooNew
import com.mywallet.data.backup.BackupScheduler
import com.mywallet.data.notify.ReminderScheduler
import com.mywallet.data.repo.DataEraser
import com.mywallet.data.repo.InterestRepository
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.PlanRepository
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.update.AvailableUpdate
import com.mywallet.data.update.UpdateChecker
import com.mywallet.data.settings.AppSettings
import com.mywallet.data.settings.BackupFrequency
import com.mywallet.data.fx.ExchangeRateRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.SettingsStore
import com.mywallet.data.settings.ThemeChoice
import com.mywallet.ui.LocalDateDisplay
import com.mywallet.ui.components.ChoicePicker
import com.mywallet.ui.components.ChoiceRow
import com.mywallet.ui.components.Explain
import com.mywallet.ui.components.ExplainedRow
import com.mywallet.ui.components.SectionHeader
import com.mywallet.ui.components.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** A one-off message to show in a snackbar after a backup action. */
data class SettingsMessage(val text: String, val isError: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val wallet: WalletRepository,
    private val exchangeRates: ExchangeRateRepository,
    private val backupManager: BackupManager,
    private val backupScheduler: BackupScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val updateChecker: UpdateChecker,
    private val eraser: DataEraser,
    private val recurrence: RecurrenceRepository,
    private val interest: InterestRepository,
    private val loans: LoanRepository,
    private val plans: PlanRepository,
) : ViewModel() {

    private val _update = MutableStateFlow<AvailableUpdate?>(null)
    val update: StateFlow<AvailableUpdate?> = _update.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    init {
        // Checked quietly on open. A failure is silent: no network is a normal
        // state for this app, not something to interrupt the user about.
        viewModelScope.launch {
            updateChecker.check().onSuccess { _update.value = it }
        }
    }

    fun checkForUpdate() = viewModelScope.launch {
        _checkingUpdate.value = true
        updateChecker.check().fold(
            onSuccess = { found ->
                _update.value = found
                if (found == null) {
                    _message.value =
                        SettingsMessage(context.getString(R.string.settings_up_to_date))
                }
            },
            onFailure = {
                _message.value = SettingsMessage(
                    context.getString(R.string.settings_update_check_failed), isError = true,
                )
            },
        )
        _checkingUpdate.value = false
    }

    fun installUpdate() = viewModelScope.launch {
        val available = _update.value ?: return@launch
        updateChecker.downloadAndInstall(available).onFailure {
            _message.value = SettingsMessage(
                context.getString(R.string.settings_update_failed), isError = true,
            )
        }
    }

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Changes the currency everything is totalled in, and re-values everything
     * that was already recorded.
     *
     * In that order: rates for the new currency are fetched first, then every
     * stored conversion is worked out again, and only then is the setting
     * written. Setting it first would draw the whole app in the new symbol for
     * however long the rest took — a page of rupee figures with a dollar sign in
     * front of them, which is not a slow answer but a wrong one.
     */
    fun setCurrency(code: String) = viewModelScope.launch {
        // Best effort: on a train with no signal the cache is what there is, and
        // whatever cannot be converted keeps the figure it had.
        exchangeRates.refresh(code)
        exchangeRates.warmCache(code)
        wallet.restateBaseCurrency(code)
        settingsStore.setCurrency(code)
    }

    /**
     * Changes which calendar dates are read in — and, with it, the months a
     * savings account's interest periods are cut into.
     *
     * The second half is why this is not a one-liner. A period runs from the 1st
     * of a month to the day before the next opens, so the setting moves the
     * payout days themselves: quarters closing on 1 Baisakh, 1 Shrawan, 1 Kartik
     * become quarters closing on 1 January, 1 April, 1 July. Left to the next
     * launch, the account would go on showing credits on days the bank now has
     * no period ending on until the app was killed — the same argument that has
     * a rate change post immediately.
     *
     * The whole history is worked out again rather than the old credits being
     * kept beside the new ones: a Gregorian quarter and a Nepali one overlap
     * rather than abut, so keeping both would pay the same days twice.
     * [InterestRepository.postDueInterest] is idempotent and rewrites each
     * posting in place, so switching back returns exactly what was there before.
     */
    fun setCalendar(system: CalendarSystem) = viewModelScope.launch {
        settingsStore.setCalendarSystem(system)
        // Only holdings that opted in are affected, and for them the effective
        // calendar has just moved. Left to the next launch, the account would
        // show credits on days it no longer pays on and the timeline would draw
        // a due date the debt's own table disagrees with — the same argument
        // that has a rate change post immediately.
        //
        // **The debts first, the interest second**, which is the order the
        // launch runs them in and for the same reason: re-calendaring a rule
        // rewrites the instalment rows it has generated, and those rows are
        // movements the interest is computed from. Reposted first, the interest
        // would be worked out on balances the loan is about to restate, and the
        // account would carry a figure derived from days its own statement no
        // longer shows.
        loans.recalendarSchedules()
        plans.recalendarPlans()
        interest.postDueInterest()
    }

    fun setTheme(choice: ThemeChoice) = viewModelScope.launch { settingsStore.setTheme(choice) }

    /**
     * Turns the lock on, if the phone has one to ask with.
     *
     * The check belongs here rather than at the prompt: a switch that turned on
     * and then never asked for anything would be a lock the user believes in and
     * does not have.
     */
    fun setScreenLock(on: Boolean, available: Boolean) = viewModelScope.launch {
        if (on && !available) {
            _message.value = SettingsMessage(
                context.getString(R.string.settings_lock_unavailable), isError = true,
            )
            return@launch
        }
        settingsStore.setScreenLock(on)
    }

    /**
     * How many days early to be told. Nothing to reschedule — the job still runs
     * once a day at the same hour; this only changes what it counts.
     */
    fun setReminderLead(days: Int) = viewModelScope.launch {
        settingsStore.setReminderLeadDays(days)
    }

    /**
     * Turns the daily notification on, if Android is letting this app post one.
     *
     * The check belongs here for the reason the lock's does: a switch that turned
     * on and then never notified anybody would be a reminder the user believes in
     * and does not have. Whether the *permission* has been granted is the
     * screen's business — only it can ask — so it passes the answer in.
     */
    fun setNotifyReminders(on: Boolean, allowed: Boolean) = viewModelScope.launch {
        if (on && !allowed) {
            _message.value = SettingsMessage(
                context.getString(R.string.settings_notify_unavailable), isError = true,
            )
            return@launch
        }
        settingsStore.setNotifyReminders(on)
        val current = settingsStore.settings.first()
        reminderScheduler.apply(on, current.notifyAtMinutes, realign = true)
    }

    /** The hour it arrives. Re-aligned, or the change would take a day to land. */
    fun setNotifyAt(minutes: Int) = viewModelScope.launch {
        settingsStore.setNotifyAt(minutes)
        val current = settingsStore.settings.first()
        reminderScheduler.apply(current.notifyReminders, current.notifyAtMinutes, realign = true)
    }

    fun setLanguage(tag: String?) = viewModelScope.launch {
        settingsStore.setLanguageTag(tag)
        // AppCompat applies this immediately and persists it across restarts;
        // on API 33+ it hands off to the system's per-app language setting.
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    fun setBackupFrequency(frequency: BackupFrequency) = viewModelScope.launch {
        settingsStore.setBackupFrequency(frequency)
        val current = settingsStore.settings.first()
        backupScheduler.apply(frequency, current.backupFolderUri != null)
    }

    fun setBackupFolder(uri: String) = viewModelScope.launch {
        settingsStore.setBackupFolder(uri)
        val current = settingsStore.settings.first()
        backupScheduler.apply(current.backupFrequency, true)
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        backupManager.exportTo(uri).fold(
            onSuccess = {
                _message.value = SettingsMessage(context.getString(R.string.settings_backup_saved))
            },
            onFailure = {
                _message.value = SettingsMessage(
                    context.getString(R.string.settings_backup_failed), isError = true,
                )
            },
        )
    }

    fun restoreFrom(uri: Uri) = viewModelScope.launch {
        backupManager.restoreFrom(uri).fold(
            onSuccess = { report ->
                // A restored rule brings its own watermark with it, so the
                // occurrences it owes are written straight away rather than on
                // the next launch — otherwise the timeline shows a restored
                // salary as a projection while the row for last week is missing.
                recurrence.materialiseDue()
                interest.postDueInterest()
                // Both counts, because they answer different questions. The
                // entries are the movements; the second number is the accounts,
                // debts and rules — which a restore did not bring back at all
                // until now, and which is the part a user reinstalling on a new
                // phone is actually anxious about.
                val entries = report.entriesAdded + report.entriesUpdated
                _message.value = SettingsMessage(
                    listOfNotNull(
                        context.resources.getQuantityString(
                            R.plurals.settings_restored_entries, entries, entries,
                        ),
                        report.holdingsRestored
                            .takeIf { it > 0 }
                            ?.let {
                                context.resources.getQuantityString(
                                    R.plurals.settings_restored_holdings, it, it,
                                )
                            },
                    ).joinToString(" · ")
                )
            },
            onFailure = {
                _message.value = SettingsMessage(
                    context.getString(
                        if (it is BackupTooNew) {
                            R.string.settings_restore_too_new
                        } else {
                            R.string.settings_restore_failed
                        }
                    ),
                    isError = true,
                )
            },
        )
    }

    /**
     * Throws away every figure. Confirmed in the UI first — there is no undo, and
     * unlike a swipe there is nothing to bring back.
     */
    fun eraseEverything() = viewModelScope.launch {
        eraser.eraseEverything()
        // The language is not in the store the eraser clears — AppCompat keeps
        // its own copy and hands it to the platform — so it is put back to the
        // phone's own here. Left behind, a cleared app would go on reading in a
        // language whose setting no longer exists.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        _message.value = SettingsMessage(context.getString(R.string.settings_erased))
    }

    fun suggestedFileName(): String = backupManager.suggestedFileName()
}

@Composable
fun SettingsScreen(
    onMessage: (SettingsMessage) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val checking by viewModel.checkingUpdate.collectAsStateWithLifecycle()
    val dates = LocalDateDisplay.current

    message?.let {
        onMessage(it)
        viewModel.clearMessage()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportTo) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restoreFrom) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setBackupFolder(it.toString()) } }

    var confirmErase by remember { mutableStateOf(false) }

    // Asked when the screen opens rather than relied on from whenever an ad was
    // last fetched: the flag lives for one process and the answer behind it does
    // not, so somebody who reaches Settings before anything has asked for an ad
    // would otherwise be shown nothing on a phone that is owed the row.
    val context = LocalContext.current
    val adChoicesOffered by AdConsent.privacyOptionsRequired.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AdConsent.refresh(context) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp),
            )
        }

        // The order down this page is how often a setting is touched, near
        // enough: how the app looks and reads first, then what the figures are
        // in, then the two that send something, then the things set up once —
        // and the lock, which is asked for once and then lived with, beside them.
        // Anything that destroys something is last, on its own.
        item {
            SettingsGroup(title = R.string.settings_theme) {
                ChoiceRow(
                    options = listOf(
                        ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
                    ),
                    selected = settings.theme,
                    onSelect = viewModel::setTheme,
                )
                Explain(R.string.settings_theme_explain)
            }
        }

        item {
            SettingsGroup(title = R.string.settings_language) {
                // A picker, as the currency is. The answers here are proper
                // names rather than a scale — "System", "English", "नेपाली" —
                // and a row of chips spent the width of the card stating two
                // languages the reader is not in to change the one they are.
                ExplainedRow(R.string.settings_language_explain) {
                    ChoicePicker(
                        options = listOf(
                            null to stringResource(R.string.settings_language_system),
                            "en" to "English",
                            "ne" to "नेपाली",
                        ),
                        selected = settings.languageTag,
                        onSelect = viewModel::setLanguage,
                    )
                }
            }
        }

        item {
            SettingsGroup(title = R.string.settings_calendar) {
                // Chips, where the language beside it is a picker: there are two
                // answers, they are three words between them, and both fit on
                // one line — which is the whole test. A picker would hide one of
                // two answers behind a tap to save nothing.
                ChoiceRow(
                    options = listOf(
                        CalendarSystem.GREGORIAN to
                            stringResource(R.string.settings_calendar_gregorian),
                        CalendarSystem.BIKRAM_SAMBAT to
                            stringResource(R.string.settings_calendar_bikram),
                    ),
                    selected = settings.calendarSystem,
                    onSelect = viewModel::setCalendar,
                )
                Explain(R.string.settings_calendar_explain)
            }
        }

        item {
            SettingsGroup(title = R.string.settings_currency) {
                // A dropdown, for the reason the reminder lead time is one:
                // seventeen chips wrap into four lines of near-identical
                // two-word answers, which is a paragraph to read for a choice
                // that is one word. The current answer is on the row and the
                // rest sit behind it — but drawn as a box, because the row
                // alone read as a printed fact rather than as something that
                // could be tapped.
                ExplainedRow(R.string.settings_currency_explain) {
                    ChoicePicker(
                        options = CurrencyOption.ALL.map { it.code to it.pickerLabel },
                        selected = settings.currencyCode,
                        onSelect = viewModel::setCurrency,
                    )
                }
            }
        }

        // Whether the phone says what is due out loud, and how early. Two
        // settings and deliberately two cards: one is about sending something to
        // a person, the other about timing — and the switch comes first, since
        // the lead time is what the notification counts.
        item {
            val context = LocalContext.current
            // Asked for at the moment the switch is turned on, and never before:
            // a permission prompt on a screen the user opened to change the
            // theme is a prompt they refuse out of hand.
            val permission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> viewModel.setNotifyReminders(granted, granted) }
            var pickingTime by remember { mutableStateOf(false) }

            SettingsGroup(title = R.string.settings_notifications) {
                val setNotify: (Boolean) -> Unit = { on ->
                    when {
                        // Off needs nothing from Android: an app may
                        // always stop notifying.
                        !on -> viewModel.setNotifyReminders(false, true)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !notificationsAllowed(context) ->
                            permission.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS
                            )
                        else -> viewModel.setNotifyReminders(
                            on = true,
                            allowed = notificationsAllowed(context),
                        )
                    }
                }
                // The whole row is the target, not just the switch: the words
                // beside it say what the tap would do, and a thumb aiming at
                // them was landing on nothing.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = settings.notifyReminders,
                        role = Role.Switch,
                        onValueChange = setNotify,
                    ),
                ) {
                    // The row says what tapping it does, not what the setting is
                    // called: the switch beside it already shows which way it
                    // stands, so a fixed label made the reader work out what "on"
                    // meant for it.
                    Text(
                        text = stringResource(
                            if (settings.notifyReminders) {
                                R.string.settings_notify_disable
                            } else {
                                R.string.settings_notify_enable
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.notifyReminders,
                        // Null, so the row's own toggleable owns the gesture and
                        // the switch cannot answer the same tap twice.
                        onCheckedChange = null,
                    )
                }
                Explain(R.string.settings_notify_explain)
                // The hour, and only once there is something to send at it.
                if (settings.notifyReminders) {
                    ActionRow(
                        text = stringResource(
                            R.string.settings_notify_at,
                            timeLabel(settings.notifyAtMinutes),
                        )
                    ) { pickingTime = true }
                }
            }

            if (pickingTime) {
                TimePickerDialog(
                    initialMinutes = settings.notifyAtMinutes,
                    onPick = {
                        viewModel.setNotifyAt(it)
                        pickingTime = false
                    },
                    onDismiss = { pickingTime = false },
                )
            }
        }

        item {
            SettingsGroup(title = R.string.settings_reminders) {
                // A picker rather than a row of chips: this is the one choice in
                // Settings with seven answers, and seven chips wrapped onto three
                // lines of near-identical phrases — "2 days before", "3 days
                // before" — which is a paragraph to read for a decision that is
                // one number. The rest of the page stays chips, where there are
                // two or three answers and all of them fit on a line.
                ExplainedRow(R.string.settings_reminders_explain) {
                    ChoicePicker(
                        options = (0..SettingsStore.MAX_REMINDER_LEAD).map { days ->
                            days to when (days) {
                                0 -> stringResource(R.string.settings_reminders_same_day)
                                1 -> stringResource(R.string.settings_reminders_one_day)
                                else -> stringResource(R.string.settings_reminders_days, days)
                            }
                        },
                        selected = settings.reminderLeadDays,
                        onSelect = viewModel::setReminderLead,
                    )
                }
            }
        }


        item {
            val context = LocalContext.current
            SettingsGroup(title = R.string.settings_lock) {
                val host = lockHost()
                val setLock: (Boolean) -> Unit = { on ->
                    when {
                        // Off needs nothing: the app is open, which
                        // means whoever is holding it already got past
                        // the lock they are turning off.
                        !on -> viewModel.setScreenLock(false, true)
                        // Refused on a phone with no lock of its own: an
                        // app that shut itself behind a credential that
                        // does not exist could not be opened again.
                        !canLock(context) || host == null ->
                            viewModel.setScreenLock(true, canLock(context))
                        // And otherwise asked for, before it is saved.
                        // Turning the lock on is the one setting that can
                        // shut the user out of their own money, so the
                        // phone says yes first and the switch follows.
                        //
                        // In its own words: what is being checked here
                        // is that this phone's lock can be got past, not
                        // that the holder may see their money — which
                        // they plainly may, since they are looking at it.
                        else -> askToUnlock(
                            activity = host,
                            onSuccess = { viewModel.setScreenLock(true, true) },
                            subtitleRes = R.string.lock_subtitle_confirm,
                        )
                    }
                }
                // Same rule as the notification switch above: the words are
                // part of the target, not a caption beside it.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = settings.screenLock,
                        role = Role.Switch,
                        onValueChange = setLock,
                    ),
                ) {
                    // Same rule as the notification switch above: the row says
                    // what the tap would do.
                    Text(
                        text = stringResource(
                            if (settings.screenLock) {
                                R.string.settings_lock_disable
                            } else {
                                R.string.settings_lock_enable
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.screenLock,
                        onCheckedChange = null,
                    )
                }
                Explain(R.string.settings_lock_explain)
            }
        }

        item {
            SettingsGroup(title = R.string.settings_backup) {
                Text(
                    text = settings.lastBackupAt?.let { millis ->
                        stringResource(
                            R.string.settings_backup_last,
                            dates.full(
                                Instant.ofEpochMilli(millis)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            ),
                        )
                    } ?: stringResource(R.string.settings_backup_never),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Explain(R.string.settings_backup_explain)

                // The rhythm first, then the two things done by hand.
                //
                // Backing up is a thing to *arrange* once and a thing to *do*
                // rarely, and the group used to lead with the two rare ones: a
                // reader who came here to make sure their figures were safe had
                // to read past "Back up now" and "Restore from a backup" to find
                // the setting that means they never have to tap either again.
                // The rarest of the three — a restore, which is a phone being
                // rebuilt — is last.
                Spacer(Modifier.height(18.dp))
                SectionHeader(title = stringResource(R.string.settings_backup_auto))
                Spacer(Modifier.height(10.dp))
                ChoicePicker(
                    options = listOf(
                        BackupFrequency.OFF to stringResource(R.string.backup_off),
                        BackupFrequency.DAILY to stringResource(R.string.backup_daily),
                        BackupFrequency.WEEKLY to stringResource(R.string.backup_weekly),
                        BackupFrequency.MONTHLY to stringResource(R.string.backup_monthly),
                    ),
                    selected = settings.backupFrequency,
                    onSelect = viewModel::setBackupFrequency,
                )
                if (settings.backupFrequency != BackupFrequency.OFF) {
                    Spacer(Modifier.height(12.dp))
                    ActionRow(
                        text = settings.backupFolderUri?.let { folderName(it) }
                            ?: stringResource(R.string.settings_pick_folder),
                    ) {
                        folderLauncher.launch(null)
                    }
                }

                Spacer(Modifier.height(18.dp))
                ActionRow(stringResource(R.string.settings_backup_now)) {
                    exportLauncher.launch(viewModel.suggestedFileName())
                }
                ActionRow(stringResource(R.string.settings_backup_restore)) {
                    restoreLauncher.launch(arrayOf("application/json", "*/*"))
                }
            }
        }

        // Only where an answer was asked for, which is only the EEA, the UK and
        // Switzerland — [AdConsent.privacyOptionsRequired] is what decides, not
        // anything guessed here. Everywhere else this section does not exist,
        // and a row that opened nothing would be worse than no row.
        if (adChoicesOffered) {
            item {
                SettingsGroup(title = R.string.settings_ads) {
                    Text(
                        text = stringResource(R.string.settings_ads_explain),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionRow(stringResource(R.string.settings_ads_privacy)) {
                        AdConsent.showPrivacyOptions(context)
                    }
                }
            }
        }

        item {
            SettingsGroup(title = R.string.settings_about) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // The version, and — only where the app can act on it — what is
                // newer. A copy installed from Play is updated by Play, so an
                // offer to check would be a row that can only ever answer "up
                // to date": the app is forbidden from doing anything else with
                // the answer. See the `distribution` flavours in build.gradle.kts.
                val available = if (BuildConfig.SELF_UPDATES) update else null
                if (!BuildConfig.SELF_UPDATES) {
                    Unit
                } else if (available != null) {
                    Text(
                        text = stringResource(R.string.update_available, available.versionName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    available.notes?.let { notes ->
                        Text(
                            text = notes.lineSequence().take(4).joinToString("\n"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    ActionRow(stringResource(R.string.update_install), viewModel::installUpdate)
                } else {
                    ActionRow(
                        text = if (checking) {
                            stringResource(R.string.update_checking)
                        } else {
                            stringResource(R.string.update_check)
                        },
                        onClick = viewModel::checkForUpdate,
                    )
                }
            }
        }

        // Last, and on its own. Nothing else in Settings destroys anything, and
        // this must not sit next to a row someone taps by habit.
        item {
            SettingsGroup(title = R.string.settings_start_over) {
                Text(
                    text = stringResource(R.string.settings_erase_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DangerRow(stringResource(R.string.settings_erase)) { confirmErase = true }
            }
        }

    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(stringResource(R.string.settings_erase_confirm_title)) },
            text = { Text(stringResource(R.string.settings_erase_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmErase = false
                        viewModel.eraseEverything()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_erase),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Same shape as [ActionRow] but in the colour that means "think twice". */
@Composable
private fun DangerRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Whether Android will let this app post anything at all.
 *
 * Two different things can be false here — the runtime permission on Android 13
 * and later, and the app's notifications being switched off in the phone's own
 * settings — and both mean the same to the user: nothing will arrive. Asked
 * fresh each time rather than remembered, because either can change while the
 * app sits open on this screen.
 */
private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** "9:00 AM", in whatever form the phone writes a time. */
@Composable
private fun timeLabel(minutes: Int): String {
    val time = LocalTime.of(minutes / 60, minutes % 60)
    val formatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
    return time.format(formatter)
}

/**
 * The hour the daily reminder arrives.
 *
 * A clock face rather than two number fields: the answer is a time of day, the
 * platform has one control for exactly that, and it is the control the user has
 * already used in every alarm app on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notify_pick_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Shows the folder's own name rather than a content:// URI the user cannot read. */
private fun folderName(uri: String): String =
    Uri.decode(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
