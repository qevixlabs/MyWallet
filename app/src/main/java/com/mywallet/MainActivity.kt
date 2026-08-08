package com.mywallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mywallet.ads.AdConfig
import com.mywallet.ads.InterstitialAds
import com.mywallet.data.backup.BackupScheduler
import com.mywallet.data.notify.ReminderScheduler
import com.mywallet.data.repo.InterestRepository
import com.mywallet.data.repo.LoanRepository
import com.mywallet.data.repo.PlanRepository
import com.mywallet.data.repo.RecurrenceRepository
import com.mywallet.data.repo.WalletRepository
import com.mywallet.data.settings.AppSettings
import com.mywallet.data.settings.SettingsStore
import com.mywallet.ui.LockScreen
import com.mywallet.ui.WalletApp
import com.mywallet.ui.askToUnlock
import com.mywallet.ui.canLock
import com.mywallet.ui.lockHost
import com.mywallet.ui.nav.Routes
import com.mywallet.ui.nav.TopLevelDestination
import com.mywallet.ui.screens.MonthSelection
import com.mywallet.ui.setup.LockOfferSheet
import com.mywallet.ui.setup.SetupScreen
import com.mywallet.ui.theme.MyWalletTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.mywallet.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.drop

/**
 * Holds the settings the whole UI tree reads, and does the things that must
 * happen once per launch: put every opted-in debt's schedule back in step with
 * the calendar, credit the interest each period has earned, materialise
 * whatever the rules owe, and re-register the two background jobs.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val wallet: WalletRepository,
    private val backupScheduler: BackupScheduler,
    private val reminderScheduler: ReminderScheduler,
    private val loans: LoanRepository,
    private val plans: PlanRepository,
    private val recurrence: RecurrenceRepository,
    private val interest: InterestRepository,
    private val months: MonthSelection,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * Whether there is anywhere to move money *to*, which is what decides
     * whether the button's menu offers a transfer at all.
     *
     * The form has always greyed its Transfer tab out on a phone with one
     * account and said why underneath. The menu in front of it has no room for
     * that sentence, so it drops the option instead: an option that opens a
     * page to explain that it does nothing is worse than an option that is not
     * there. Counted the way the form counts its ends — the places money sits,
     * so a deposit, a policy and a goal are not among them.
     */
    val canTransfer: StateFlow<Boolean> = wallet.observeAccounts()
        .map { accounts ->
            accounts.count { !it.isFixedDeposit && !it.isInsurance && !it.isGoal } > 1
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Ticks once every time the user's own figures move.
     *
     * The lock offer waits for a gap, and "a gap" has to mean more than standing
     * on a tab: somebody who has just written down a payment is mid-thought, and
     * a sheet arriving on the back of the save is the app interrupting the very
     * thing it waited for them to finish. Walking into a form and out again
     * already restarts that wait, but a movement recorded *without* leaving the
     * page — a row swiped away, a payment made on a debt's own card — did not,
     * and the offer could land a second later.
     *
     * So the wait is keyed on this as well, and every recorded figure starts the
     * count again from the beginning.
     *
     * The balance is what it watches because that is what "recorded" means here:
     * money added, removed or corrected all move it. The first emission is
     * dropped — that one is the app reading the database on launch, not the user
     * doing anything.
     */
    val dataRevision: StateFlow<Int> = wallet.observeConfirmedBalance()
        .drop(1)
        .runningFold(0) { ticks, _ -> ticks + 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Steps the shared month by one, for the lesson that teaches stepping it.
     *
     * [MonthSelection] is the one month both tabs read, so a step made against
     * the tutorial card is the month the reader is left on — which is the whole
     * point of letting that gesture through. Here rather than in the activity
     * because the selection is the view model's to hold; the activity only knows
     * that a swipe happened.
     */
    fun stepPeriod(delta: Int) = months.show(months.offset.value + delta)

    companion object {
        /**
         * Set true to replay the whole opening on **every** launch — the three
         * questions, the account hint, both swipe lessons and the lock offer —
         * whatever this phone holds and whatever `setup_done` says.
         *
         * One switch, for looking at the opening as a new user sees it without
         * wiping a database to see it once. Deliberately not a build-type check:
         * it is read on a real phone with real figures behind it, which a debug
         * build is not.
         *
         * False is the shipping value and does **not** mean the opening is off.
         * A phone with nothing on it still gets all of it, and so does one whose
         * figures have just been thrown away by Start over — see [usedBefore],
         * which is asked again the moment `setup_done` is gone. This only
         * decides whether somebody who has already answered is asked again.
         */
        const val REHEARSE_FIRST_RUN = false
    }

    /**
     * Whether this phone had anything on it when the app opened, or null while
     * that is still being read.
     *
     * Asked **before** anything this launch writes — the rules it materialises,
     * the interest it credits — which is the only moment it can be asked
     * honestly: a moment later a phone the app has filled in for looks exactly
     * like one the user has been keeping figures on for a year.
     */
    private val usedBefore = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the opening questions are still owed, or null while that is not
     * yet known.
     *
     * Null is the point of it. [settings] starts at its defaults and the stored
     * values arrive a moment later, so reading `setupDone` straight off it would
     * show the setup screen for a frame on every launch of a phone that has
     * already answered — which is the one thing a once-only screen must not do.
     *
     * Two answers make it false, and the second is what an upgrade needs: the
     * questions have been answered, or this phone has figures on it already. An
     * app being opened for the hundredth time is not being started for the first
     * time, whatever a preference written by a later version says.
     */
    val setupPending: StateFlow<Boolean?> =
        combine(settingsStore.settings, usedBefore) { current, used ->
            when {
                used == null -> null
                // Temporary: see [REHEARSE_FIRST_RUN]. Both answers below say
                // "this phone has been here before", which is exactly what has
                // to be ignored while the opening is being looked at.
                REHEARSE_FIRST_RUN -> true
                current.setupDone -> false
                used -> false
                else -> true
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the app may say the two things it says to a new user — where an
     * account comes from, and that it can be locked.
     *
     * The same answer [setupPending] rests on, held on its own because the
     * questions are done with long before the other two have been.
     */
    val firstRun: StateFlow<Boolean> = usedBefore
        .map { REHEARSE_FIRST_RUN || it == false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Turns the lock on once the phone itself has said who this is. */
    fun enableLock() = viewModelScope.launch { settingsStore.setScreenLock(true) }

    /**
     * Whether this phone holds anything of the user's own — one holding, or one
     * movement — which is what the ad is conditioned on.
     *
     * An app that has been opened and not yet used is somebody deciding whether
     * to keep it. Advertising at them costs the decision and earns a dismissal,
     * so nothing is shown until they have put something in: an account, or a
     * payment. Asked of the database at the moment it matters rather than held
     * as a flag, because it becomes true the instant they add their first
     * account and no launch stands between the two.
     */
    suspend fun hasFiguresOfTheirOwn(): Boolean =
        wallet.hasAnyHolding() || wallet.hasAnyEntry()

    /**
     * The row the swipe lesson is taught on, or null when there is none.
     *
     * A real entry rather than something drawn to look like one: what is being
     * taught is the gesture that deletes a payment, and a row that only pretends
     * to be one would teach it against a list the app has to special-case.
     * Filed against whichever holding money can move through, dated today so it
     * lands in the month the timeline opens on.
     *
     * Null when there is nowhere to file it — somebody who skipped past the
     * account hint has no holding yet, and the lesson is simply not given.
     */
    private val _practiceEntryId = MutableStateFlow<String?>(null)
    val practiceEntryId: StateFlow<String?> = _practiceEntryId

    /**
     * True once the practice row has gone — which is the lesson being passed.
     *
     * Watched rather than reported by whichever list the swipe happened on: the
     * row can also be deleted from an account's statement or opened and removed
     * from the form, and all three are the user having made the gesture.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val practiceSwiped: StateFlow<Boolean> = _practiceEntryId
        .flatMapLatest { id ->
            if (id == null) flowOf(false) else wallet.observeEntryExists(id).map { !it }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The holding the accounts lesson is taught on, and whether it has gone.
     *
     * The same pair as the practice entry and for the same reasons: a real row,
     * because the gesture works on real rows, and its absence is what says the
     * lesson has been passed — a holding can also be removed from its own editor.
     */
    private val _demoAccountId = MutableStateFlow<String?>(null)
    val demoAccountId: StateFlow<String?> = _demoAccountId

    @OptIn(ExperimentalCoroutinesApi::class)
    val demoAccountSwiped: StateFlow<Boolean> = _demoAccountId
        .flatMapLatest { id ->
            if (id == null) flowOf(false) else wallet.observeAccountExists(id).map { !it }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Opens the demo holding. Nothing happens twice, and nothing happens at all
     * on a page the user has already filled.
     *
     * The id staying null is what withholds the lesson — see the two
     * `show…SwipeHint` flags, which are both conditioned on it.
     */
    fun addDemoAccount(name: String) {
        if (_demoAccountId.value != null) return
        viewModelScope.launch {
            if (wallet.hasAnyHolding()) return@launch
            _demoAccountId.value = wallet.addDemoAccount(name)
        }
    }

    /** Ends that lesson and takes the holding back, swiped away or skipped. */
    fun endAccountDemo() {
        val id = _demoAccountId.value ?: return
        _demoAccountId.value = null
        viewModelScope.launch { wallet.removeDemoAccount(id) }
    }

    /**
     * Writes the row the lesson is taught on. Nothing happens twice, and nothing
     * happens at all once the timeline has movements of the user's own.
     *
     * The sideways-drag card is not withheld with it: that one is about the
     * page rather than about a row, and it reads the same whether the month is
     * full or empty.
     */
    fun addPracticeEntry(note: String) {
        if (_practiceEntryId.value != null) return
        viewModelScope.launch {
            if (wallet.hasAnyEntry()) return@launch
            // Back to the month the row is in, first.
            //
            // The practice entry is dated today, and the lesson *before* this
            // one is about dragging the page sideways to change the month — so
            // a reader who does as they were told is looking at some other month
            // by the time this one is owed. The row was then written somewhere
            // they could not see, and the spotlight lit an empty page.
            //
            // Safe on the ordinary path, where the offset is already zero: the
            // month is a shared singleton and setting it to the value it holds
            // emits nothing.
            months.show(0)
            _practiceEntryId.value = wallet.addPracticeEntry(note)
        }
    }

    /**
     * The lesson is over, and the row goes with it however it ended.
     *
     * Swiped away, the entry is already gone and this only forgets it; skipped,
     * the app takes back what it put there. Either way nothing it invented is
     * left in the user's own figures, which is the whole licence for writing a
     * row nobody asked for.
     */
    fun endPractice() {
        val id = _practiceEntryId.value ?: return
        _practiceEntryId.value = null
        viewModelScope.launch { wallet.removePracticeEntry(id) }
    }

    init {
        // Start over throws the figures *and* the preferences away, so
        // `setup_done` going missing is the app being handed back empty. Asked
        // again at that moment, so the questions, the hints and the lock offer
        // all come back in the session the user erased in rather than waiting
        // for the next launch.
        //
        // Safe to ask on every such emission: the other way to have no
        // `setup_done` is an upgrade from a version that never wrote one, and
        // there the answer is the same "yes, this phone has been used" it
        // already was.
        viewModelScope.launch {
            settingsStore.settings.collect { current ->
                if (!current.setupDone && usedBefore.value == true) {
                    usedBefore.value = wallet.hasHistory()
                }
            }
        }
        // **On [io], and this is the sweep that most needed it.** Everything
        // below is the catching-up a launch owes — occurrences whose day has
        // arrived, debts put back in step with the calendar, interest for the
        // quarters that closed — and it ran on `viewModelScope`, which is the
        // main thread, at the exact moment the first screen was trying to draw.
        // Measured on a real phone it showed as 14% janky frames with a 99th
        // percentile of 200ms and the GPU idle at 4–7ms: work on the thread that
        // draws, not drawing that was too slow. None of it touches a view; the
        // two things it writes are `StateFlow` values, which are safe from any
        // thread.
        viewModelScope.launch(io) {
            // Asked before anything else writes: a phone with figures on it is
            // not shown the opening questions however `setup_done` stands.
            usedBefore.value = wallet.hasHistory()
            // Then what has happened while the app was closed, written down
            // before any screen reads it: occurrences whose day has arrived
            // become real rows, and a savings quarter that has closed is
            // credited. This belongs to the launch and not to a tab — it used to
            // be done by the Timeline and Reminders view models, so an app opened
            // on Home showed a month with today's instalment missing from it and
            // a balance that had not felt it, until the user happened to visit
            // one of the other two tabs.
            // Occurrences written before a user's own rule was allowed to name
            // its account get it back, then the dates that have arrived become
            // rows, then the interest those rows earned. In that order: the
            // repair and the new rows are both movements the interest is
            // computed from.
            recurrence.adoptOrphanedOccurrences()
            // And a debt that counts in Nepali months is put back in step with
            // the calendar now set, before its occurrences are written. The
            // effective answer is the debt's opt-in *and* the setting, so a
            // backup restored onto a phone reading the other calendar arrives
            // with rules stepping in months nobody is looking at. Cheap: it
            // skips every debt that never opted in, which is nearly all of them.
            loans.recalendarSchedules()
            plans.recalendarPlans()
            // And the rules the user writes by hand, which had neither sweep and
            // go stale exactly the same way — see
            // [RecurrenceRepository.recalendarRules]. Last of the three, so
            // anything the two above have already restated is found equal here.
            recurrence.recalendarRules()
            // And every debt on file says on the page that it arrived. The row
            // used to be written only where the money was said to land in an
            // account, so the month a loan was taken out listed everything it has
            // cost since and nothing about the loan. Cheap and written once: it
            // skips any debt that already has the row, tombstoned or not.
            loans.writeMissingDisbursements()
            // And a lump sum the user promised for a day that has now arrived is
            // folded into the debt, the same way an occurrence whose day has come
            // becomes a row. Before `materialiseDue`, because a re-basing moves
            // where the schedule starts and what it asks for.
            loans.applyDuePayments()
            recurrence.materialiseDue()
            // Before the interest is worked out, never after: an account that
            // has been credited half-yearly has to be holding that answer before
            // anything recomputes its periods, or the run sweeps two years of
            // postings onto quarters the bank never paid.
            interest.adoptStoredPayoutInterval()
            interest.postDueInterest()
        }
        // Scheduling work is disk and IPC, and it is owed to nothing on screen.
        viewModelScope.launch(io) {
            // WorkManager keeps its own schedule, but re-applying on launch means
            // a reinstall or a cleared job store cannot silently stop backups.
            val current = settingsStore.settings.first()
            backupScheduler.apply(current.backupFrequency, current.backupFolderUri != null)
            // Re-registered rather than re-aligned: a reinstall or a cleared job
            // store must not silently stop the daily reminder, and an app opened
            // shortly before the chosen hour must not push that day's run back.
            reminderScheduler.apply(current.notifyReminders, current.notifyAtMinutes)
        }
    }
}

/**
 * Extends [AppCompatActivity] rather than ComponentActivity for exactly one
 * reason: `AppCompatDelegate.setApplicationLocales` looks up a registered
 * AppCompat delegate to reach the platform LocaleManager. With a plain
 * ComponentActivity there is no delegate, the call silently does nothing, and
 * the in-app language picker appears to work while changing nothing.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * The tab a notification asked for, held until the UI has taken it.
     *
     * State rather than a read of `intent`, for two reasons. The tap may arrive
     * while the app is already running, which reaches [onNewIntent] and never
     * touches the intent composition first saw; and with the screen lock on,
     * nothing can navigate until the user has got past it, so the request has to
     * survive being unanswered for a while.
     */
    private var pendingDestination by mutableStateOf<String?>(null)

    /**
     * The day that request is about, held the same way and for the same reasons.
     *
     * Separate from the tab because it is consumed later: the tab is navigated to
     * first, and the day can only be applied once the page it belongs to has
     * composed. Cleared by that page rather than by the navigation, or it would
     * be thrown away a frame before anything could read it.
     */
    private var pendingReminderDay by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingDestination = intent?.destination()
        pendingReminderDay = intent?.reminderDay()
        setContent {
            val appViewModel: AppViewModel = viewModel()
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            val canTransfer by appViewModel.canTransfer.collectAsStateWithLifecycle()
            // Only the lock offer reads this: it restarts its wait every time
            // the user records something. See [AppViewModel.dataRevision].
            val dataRevision by appViewModel.dataRevision.collectAsStateWithLifecycle()
            val setupPending by appViewModel.setupPending.collectAsStateWithLifecycle()
            // Whether this phone has ever been used. The two suggestions below
            // are for somebody who has not seen the app before: a user with
            // accounts already has set them up, and being shown where the button
            // is is the app talking over them.
            val firstRun by appViewModel.firstRun.collectAsStateWithLifecycle()
            // Answered on this run, whatever the store says. The stored flag is
            // what stops the questions on the next launch; this is what takes
            // them off the screen on this one, without waiting for a round trip
            // through storage that would leave the button looking like it missed.
            var setupAnswered by rememberSaveable { mutableStateOf(false) }
            // Whether the lock is still to be suggested on this run, and whether
            // the sheet is up. Two flags and not one: the first is set the moment
            // setup is answered and the second only once the user has had the app
            // in front of them for a while.
            var lockOfferOwed by rememberSaveable { mutableStateOf(false) }
            var offeringLock by rememberSaveable { mutableStateOf(false) }
            // Where setup leaves the user, and the one thing said when they get
            // there. Accounts, because the app cannot say anything about money
            // until there is somewhere for it to sit, and the button that opens
            // one is the only thing on that page nobody can guess.
            var openAfterSetup by rememberSaveable { mutableStateOf<String?>(null) }
            var accountHintOwed by rememberSaveable { mutableStateOf(false) }
            // The two lessons the Timeline owes, in order: what the sideways
            // drag does, and then the same gesture made on a row. Owed from the
            // moment setup is answered and spent when the user first walks onto
            // that tab — a gesture is explained on the page it works on.
            var timelineHintOwed by rememberSaveable { mutableStateOf(false) }
            var swipeHintOwed by rememberSaveable { mutableStateOf(false) }
            // And the same lesson on the accounts page, taught first: the
            // button hint is read, a holding appears, and the swipe that takes
            // it away is the next thing shown.
            var accountSwipeOwed by rememberSaveable { mutableStateOf(false) }
            // The two rows those lessons are taught on, still to be written.
            //
            // A card being read is not the moment to write one. Both used to be
            // written by the card's own Got it, which meant the second lesson
            // was on screen in the same frame the first left it — and the tap
            // that dismissed the card was as often a tap aimed at the button
            // *behind* it, the user reaching to add their own first account. The
            // app answered by demonstrating a delete instead. So the row waits
            // for the user to come to rest on the page it belongs to, and the
            // two writers decline outright once that page has the user's own
            // figures on it: somebody who has just added an account is not shown
            // a demo one.
            var accountDemoOwed by rememberSaveable { mutableStateOf(false) }
            var practiceOwed by rememberSaveable { mutableStateOf(false) }
            // Which tab the user is resting on, or null while they are part way
            // through something. Not saveable: it is where they are this second,
            // and the shell says so as soon as it draws.
            var idleRoute by remember { mutableStateOf<String?>(null) }
            val idleOnTab = idleRoute != null
            // The next moment an ad may be considered, or null while none is
            // owed. Two things raise one and nothing else does: the user backing
            // out of a screen onto a tab, and the app coming back to the front.
            //
            // A cue rather than a flag, carrying an id that only ever climbs, so
            // two of the same kind in a row are two separate considerations —
            // and each is considered exactly once, which is the whole of "at
            // most one ad per event".
            var adCue by remember { mutableStateOf<AdCue?>(null) }
            // The practice row: whether there is one, and whether it has been
            // swiped away. The note is read here because it is words on a screen
            // and belongs to whichever language the app is being read in.
            val practiceEntryId by appViewModel.practiceEntryId.collectAsStateWithLifecycle()
            val practiceGone by appViewModel.practiceSwiped.collectAsStateWithLifecycle()
            val practiceNote = stringResource(R.string.practice_entry_note)
            val demoAccountId by appViewModel.demoAccountId.collectAsStateWithLifecycle()
            val demoAccountGone by
                appViewModel.demoAccountSwiped.collectAsStateWithLifecycle()
            val demoAccountName = stringResource(R.string.demo_account_name)

            // Locked until proved otherwise, and locked again every time the app
            // leaves the screen. Not on every recomposition — on the lifecycle,
            // so switching to the calculator and back asks again while turning
            // the phone sideways does not.
            var unlocked by rememberSaveable { mutableStateOf(false) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            // Whether the app has been to the front before. The launch itself
            // reaches ON_RESUME like anything else, and a launch is not a
            // return: somebody who has just opened the app is being shown the
            // app, not advertised at.
            var resumedBefore by remember { mutableStateOf(false) }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    // An ad the app itself put up covers the activity, which
                    // fires exactly this event — and re-arming there would mean
                    // dismissing that ad and being asked for a fingerprint. The
                    // app has not left the user's hands, so the lock does not
                    // close behind it. Every other way to reach ON_STOP still
                    // does, which is the whole point of the lock.
                    if (event == Lifecycle.Event.ON_STOP && !InterstitialAds.showing) {
                        unlocked = false
                    }
                    if (event == Lifecycle.Event.ON_RESUME) {
                        if (resumedBefore) {
                            adCue = AdCue.next(adCue, AdConfig.AFTER_RESUME_MS)
                        } else {
                            resumedBefore = true
                        }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            MyWalletTheme(theme = settings.theme) {
                when {
                    settings.screenLock && !unlocked -> LockScreen(onUnlocked = { unlocked = true })
                    // Nothing at all while the stored answer is on its way. Null
                    // means it has not arrived, and guessing either way flashes
                    // the wrong screen: the setup questions at somebody who
                    // answered them weeks ago, or the app in the wrong currency
                    // at somebody who has not answered them yet.
                    setupPending == null -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {}
                    // The app *and* the questions over it: the panel is a layer
                    // on the thing being set up, not a page in front of it, so
                    // what it is talking about has to be drawn behind it. The
                    // NavHost underneath is the same one the user is about to
                    // be using, and the scrim eats every touch aimed at it.
                    setupPending == true && !setupAnswered -> Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        WalletApp(
                            settings = settings,
                            canTransfer = canTransfer,
                            // The notification's request is deliberately not
                            // acted on yet: it would switch a tab nobody can see
                            // and clear itself doing it. It waits for the panel.
                            openAt = null,
                        )
                        SetupScreen(onDone = {
                            setupAnswered = true
                            openAfterSetup = Routes.ACCOUNTS
                            accountHintOwed = firstRun
                            timelineHintOwed = firstRun
                            // The lock is not asked for on the way in — it is
                            // suggested a while after the app has opened, and
                            // only to somebody who has just been through setup
                            // and has none. A phone with no lock of its own is
                            // never asked: the only honest answer there is "your
                            // phone cannot", which is not a suggestion.
                            lockOfferOwed = firstRun && !settings.screenLock &&
                                canLock(this@MainActivity)
                        })
                    }
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        WalletApp(
                            settings = settings,
                            canTransfer = canTransfer,
                            // A notification's request wins: it is the user
                            // having tapped something, where the other is where
                            // the app would have put them anyway.
                            openAt = pendingDestination ?: openAfterSetup,
                            onOpened = {
                                pendingDestination = null
                                openAfterSetup = null
                            },
                            openReminderOn = pendingReminderDay,
                            onReminderDayOpened = { pendingReminderDay = null },
                            showAccountHint = accountHintOwed,
                            onAccountHintDone = {
                                accountHintOwed = false
                                // Owed, not written. The card is often dismissed
                                // by a tap meant for the button behind it, and
                                // opening a demo holding in that same frame
                                // answers "let me add an account" with "here,
                                // watch me delete one". It waits below.
                                accountDemoOwed = true
                            },
                            showAccountSwipeHint = accountSwipeOwed && demoAccountId != null,
                            demoAccountId = demoAccountId,
                            onAccountSwipeHintSkipped = {
                                accountSwipeOwed = false
                                appViewModel.endAccountDemo()
                            },
                            onIdleOn = { idleRoute = it },
                            // Backing out of a screen is the one thing in the
                            // app that raises an ad. See [AdCue].
                            onLeftScreen = {
                                adCue = AdCue.next(adCue, AdConfig.AFTER_BACK_MS)
                            },
                            showTimelineHint = timelineHintOwed,
                            // The lesson's own gesture, doing what the lesson
                            // says it does. Through [MonthSelection] because it
                            // is the one month both tabs read, so the step the
                            // reader makes here is the month they land on.
                            onStepPeriod = appViewModel::stepPeriod,
                            onTimelineHintDone = {
                                timelineHintOwed = false
                                // Owed rather than written, for the reason the
                                // demo holding above is. Nothing is shown until
                                // the row exists — a phone with nowhere to file
                                // it gets no lesson rather than a sentence about
                                // a row that is not there.
                                practiceOwed = true
                            },
                            showSwipeHint = swipeHintOwed && practiceEntryId != null,
                            practiceEntryId = practiceEntryId,
                            onSwipeHintSkipped = {
                                swipeHintOwed = false
                                appViewModel.endPractice()
                            },
                        )
                        // The row each lesson is taught on, written into a gap
                        // rather than into the frame the card before it closed.
                        //
                        // Keyed on where the user is, so walking into the add
                        // menu or into a form cancels the wait and coming back
                        // out starts a fresh one: the lesson arrives in a quiet
                        // moment on the page it is about, or it does not arrive.
                        // Somebody who spent that moment adding an account of
                        // their own comes back to a page that has one, and the
                        // writer declines — see `addDemoAccount`, which asks the
                        // database rather than trusting this.
                        LaunchedEffect(accountDemoOwed, idleRoute) {
                            if (!accountDemoOwed) return@LaunchedEffect
                            if (idleRoute != Routes.ACCOUNTS) return@LaunchedEffect
                            delay(LESSON_AFTER_MS)
                            accountDemoOwed = false
                            appViewModel.addDemoAccount(demoAccountName)
                        }
                        LaunchedEffect(practiceOwed, idleRoute) {
                            if (!practiceOwed) return@LaunchedEffect
                            if (idleRoute != Routes.TIMELINE) return@LaunchedEffect
                            delay(LESSON_AFTER_MS)
                            practiceOwed = false
                            appViewModel.addPracticeEntry(practiceNote)
                        }
                        // Written, so the hint may be shown; gone, so the lesson
                        // is over. Watched rather than reported by the timeline,
                        // because the row can be swiped away from an account's
                        // statement or opened and deleted from the form, and all
                        // three are the user having done the thing.
                        LaunchedEffect(demoAccountId) {
                            if (demoAccountId == null) return@LaunchedEffect
                            accountSwipeOwed = true
                        }
                        LaunchedEffect(demoAccountGone) {
                            if (demoAccountGone) {
                                accountSwipeOwed = false
                                appViewModel.endAccountDemo()
                            }
                        }
                        LaunchedEffect(practiceEntryId) {
                            if (practiceEntryId == null) return@LaunchedEffect
                            swipeHintOwed = true
                        }
                        LaunchedEffect(practiceGone) {
                            if (practiceGone) {
                                swipeHintOwed = false
                                appViewModel.endPractice()
                            }
                        }
                        // Long enough that the app has been used rather than
                        // merely opened: the suggestion lands on a page with the
                        // user's own money on it, which is the argument for it.
                        // Keyed on the flag, so answering it cannot start the
                        // wait again — and cancelled with the composition, so a
                        // launch that never reaches this branch never counts.
                        // A few seconds after the user comes to rest, and only
                        // then. Keyed on where they are as well as on what is
                        // owed, so walking into a form cancels the wait and
                        // coming back out of it starts a fresh one — the
                        // suggestion arrives in a gap or it does not arrive.
                        // …and on anything the user has just recorded, so a
                        // payment written down restarts the wait rather than
                        // being followed straight out of the form by a sheet.
                        // See [AppViewModel.dataRevision].
                        LaunchedEffect(
                            lockOfferOwed, accountHintOwed, idleOnTab, settings.screenLock,
                            dataRevision,
                        ) {
                            if (!lockOfferOwed || accountHintOwed) return@LaunchedEffect
                            // Asked again here and not only when it was owed: the
                            // user may have turned it on in Settings in the minutes
                            // since, and a sheet offering what is already on reads
                            // as the app not knowing its own state.
                            if (settings.screenLock) return@LaunchedEffect
                            if (!idleOnTab) return@LaunchedEffect
                            delay(LOCK_OFFER_AFTER_MS)
                            offeringLock = true
                        }
                        // Never during the opening. `firstRun` is this phone
                        // having had nothing on it when the app launched, which
                        // is exactly the session the setup questions, both
                        // swipe lessons and the lock offer all run in — so
                        // withholding on that one flag withholds the ad from
                        // every one of them, with no second list to keep in
                        // step. Somebody meeting the app for the first time
                        // meets it unadvertised, and the ads start from their
                        // next launch.
                        //
                        // And nothing is even *fetched* until the phone holds
                        // something of the user's own: an app opened, looked at
                        // and not yet used makes no ad request at all.
                        LaunchedEffect(firstRun) {
                            if (!AdConfig.ENABLED || firstRun) return@LaunchedEffect
                            if (!appViewModel.hasFiguresOfTheirOwn()) return@LaunchedEffect
                            InterstitialAds.preload(this@MainActivity)
                        }
                        // The one ad, considered once per cue and never
                        // otherwise. Everything it has to be true of is asked
                        // here, at the moment it would appear, rather than
                        // remembered from when the cue was raised — the pause
                        // below is long enough for any of it to have changed.
                        LaunchedEffect(adCue) {
                            val cue = adCue ?: return@LaunchedEffect
                            // Asked here as well as inside [InterstitialAds], so
                            // a switched-off build does not so much as touch the
                            // database on the way to doing nothing.
                            if (!AdConfig.ENABLED || firstRun) return@LaunchedEffect
                            delay(cue.afterMs)
                            // Asked again rather than assumed: the pause is long
                            // enough that the app may have been put away inside
                            // it, and an ad shown at a stopped activity is
                            // dropped by the SDK — with the gap between ads
                            // spent on one nobody saw.
                            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                return@LaunchedEffect
                            }
                            // And the user must still be simply standing on a
                            // tab. A cue raised by the app coming back to the
                            // front can land on somebody who reopened it inside
                            // a half-typed payment, and covering that form is
                            // exactly what this must never do.
                            if (idleRoute == null) return@LaunchedEffect
                            if (!appViewModel.hasFiguresOfTheirOwn()) return@LaunchedEffect
                            // Nothing loaded: this cue is spent either way. The
                            // fetch is for the *next* one — an ad that arrives a
                            // second late is an ad on a page nobody asked to
                            // leave.
                            if (!InterstitialAds.show(this@MainActivity)) {
                                InterstitialAds.preload(this@MainActivity)
                            }
                        }
                        if (offeringLock) {
                            val host = lockHost()
                            // Any tap dismisses it and nothing is written by
                            // that: the switch is still in Settings, and this is
                            // the app suggesting something rather than asking.
                            val close = {
                                offeringLock = false
                                lockOfferOwed = false
                            }
                            LockOfferSheet(
                                onDismiss = close,
                                onEnable = {
                                    close()
                                    // Asked before it is saved, exactly as the
                                    // switch in Settings does it: the lock is the
                                    // one setting that can shut the user out of
                                    // their own money, so the phone says yes
                                    // first and the setting follows.
                                    host?.let { activity ->
                                        askToUnlock(
                                            activity = activity,
                                            onSuccess = {
                                                // Unlocked as well: they have
                                                // this second proved who they
                                                // are, and the lock they just
                                                // turned on must not ask for the
                                                // same fingerprint a frame later.
                                                unlocked = true
                                                appViewModel.enableLock()
                                            },
                                            subtitleRes = R.string.lock_subtitle_confirm,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A tap on the notification while the app is already open.
     *
     * `setIntent` as well, so anything that reads the activity's intent later
     * sees the one that actually brought the user here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDestination = intent.destination()
        pendingReminderDay = intent.reminderDay()
    }

    /**
     * Which tab this intent is asking for, or null when it is an ordinary launch.
     *
     * Checked against the tabs that exist rather than trusted: an extra is
     * something any app on the phone can send, and a route taken on faith would
     * be a way to drive this one's navigation from outside.
     */
    private fun Intent.destination(): String? =
        getStringExtra(EXTRA_DESTINATION)
            ?.takeIf { route -> TopLevelDestination.entries.any { it.route == route } }

    /**
     * Which day that notification was about, or null when it named none.
     *
     * Only ever read alongside a destination, and the epoch day is checked for
     * being a day at all: like the route above, an extra is something any app on
     * the phone can send, and a bare `getLongExtra` default would take a missing
     * one for 1 January 1970. The page it reaches clamps it to a day it can
     * actually show, so nothing here has to know what that range is.
     */
    private fun Intent.reminderDay(): LocalDate? =
        getLongExtra(EXTRA_DAY, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE && it >= 0L }
            ?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }

    companion object {
        /** Names the tab a notification wants opened. See [destination]. */
        const val EXTRA_DESTINATION = "com.mywallet.extra.DESTINATION"

        /**
         * The day it is about, as an epoch day. See [reminderDay] — and
         * `ReminderNotifier`, which is the only thing that sends it.
         */
        const val EXTRA_DAY = "com.mywallet.extra.DAY"

        /**
         * How long a quiet page is left alone before the lock is suggested.
         *
         * A breath rather than a wait: the account hint has just been read and
         * the page behind it is the one being talked about, so a long pause
         * would put the question somewhere else entirely. The counting only runs
         * while the user is idle on a tab — see `onIdleOnTab`.
         */
        /**
         * How long the user is left alone before the lock is offered.
         *
         * Three seconds, which is what this was, is not a gap — it is the pause
         * between finishing one thing and starting the next, so the sheet
         * arrived while the reader was still looking at the page they had just
         * reached. Seven is long enough to be a lull rather than an interruption
         * and short enough that the offer still belongs to the session it is
         * about. It is counted from the last thing the user *did*, not merely
         * from arriving on a tab — see [AppViewModel.dataRevision].
         */
        private const val LOCK_OFFER_AFTER_MS = 7_000L

        /**
         * How long a page is left alone before the row a lesson is taught on is
         * written onto it.
         *
         * Long enough to be a gap and not a frame: the card before it is often
         * dismissed by a tap aimed at the button behind it, and the whole point
         * of the pause is that such a tap gets to land. Short enough that the
         * lesson still reads as following on from the card, rather than as
         * something that arrived out of nowhere later.
         */
        private const val LESSON_AFTER_MS = 2_000L

    }
}

/**
 * One moment at which an ad may be considered, and how long to hold off first.
 *
 * Two things raise one — the user backing out of a screen onto a tab, and the
 * app coming back to the front — and it exists so that each of them is
 * considered *once*. The id is what does that: it only ever climbs, so two cues
 * of the same kind one after another are two different values and the effect
 * watching them runs again, while a recomposition for any other reason leaves it
 * alone. A plain boolean could say neither of those things.
 */
private data class AdCue(val id: Long, val afterMs: Long) {
    companion object {
        fun next(previous: AdCue?, afterMs: Long): AdCue =
            AdCue(id = (previous?.id ?: 0L) + 1L, afterMs = afterMs)
    }
}
