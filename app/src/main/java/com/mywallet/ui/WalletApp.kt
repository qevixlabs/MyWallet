package com.mywallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mywallet.R
import com.mywallet.data.settings.AppSettings
import com.mywallet.ui.components.AddEntryMenu
import com.mywallet.ui.components.AddHoldingMenu
import com.mywallet.ui.components.AddHoldingScrim
import com.mywallet.ui.components.CoachMark
import com.mywallet.ui.components.EntryStart
import com.mywallet.ui.components.PracticeSpotlight
import com.mywallet.ui.nav.Routes
import com.mywallet.ui.nav.TopLevelDestination
import com.mywallet.ui.screens.AccountStatementScreen
import com.mywallet.ui.screens.AccountsScreen
import com.mywallet.ui.screens.AddEntryScreen
import com.mywallet.ui.screens.HoldingEditorScreen
import com.mywallet.ui.screens.HomeScreen
import com.mywallet.ui.screens.LoanLedgerScreen
import com.mywallet.ui.screens.LoanScheduleScreen
import com.mywallet.ui.screens.ProjectionTarget
import com.mywallet.ui.screens.ReminderScreen
import com.mywallet.ui.screens.ReminderViewModel
import com.mywallet.ui.screens.SettingsScreen
import com.mywallet.ui.screens.TimelineScreen
import com.mywallet.ui.screens.TimelineViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.mywallet.ui.components.TopSnackbar

/**
 * How far apart a day's heading and its row may sit and still be one block.
 *
 * They abut exactly, so this is rounding slack rather than a tolerance — a few
 * pixels of it, and nowhere near the height of a row, so a heading that has
 * pinned to the top of a scrolled page can never be mistaken for one still
 * sitting on its own rows.
 */
private const val HEADER_JOIN_SLACK = 8f

/**
 * True when this entry is a holding's editor — the screen a bank's tabs move
 * between, which is the one place a destination navigates to itself.
 */
private val NavBackStackEntry.holdingRoute: Boolean
    get() = destination.route == Routes.HOLDING_PATTERN

/** The tabs sit beside each other, so switching cross-fades rather than slides. */
private val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(tween(200)) }
private val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(tween(150)) }

/**
 * The app shell: four destinations in a bottom bar and one button to add money.
 *
 * Add money is a floating button rather than a fifth tab because it is an
 * action, not a place — and it is the only thing the user does every day.
 * There is deliberately no top bar and no drawer: every screen leads with its
 * own heading, and the row a menu button occupied pushed each of them down for
 * the sake of one icon.
 */
@Composable
fun WalletApp(
    settings: AppSettings,
    /** Whether the add menu may offer a transfer — see [AddEntryMenu]. */
    canTransfer: Boolean = false,
    /**
     * A tab something outside the app asked for — today only the reminder
     * notification, which is a note about the list on Reminders and used to land
     * on Home. Null on an ordinary launch.
     */
    openAt: String? = null,
    /** Called once [openAt] has been acted on, so it cannot fire twice. */
    onOpened: () -> Unit = {},
    /**
     * The day that notification was about, applied once the Reminders page is on
     * screen.
     *
     * That tab answers for one day, and the note may be counting a later one: a
     * user who asked to be warned a day early is told about tomorrow's rent, and
     * landing on today's page would leave them looking at a list it is not in.
     * Taken by the page rather than by the navigation above, which happens a
     * frame earlier — see [onReminderDayOpened].
     */
    openReminderOn: LocalDate? = null,
    /** Called once [openReminderOn] has been applied, so it cannot fire twice. */
    onReminderDayOpened: () -> Unit = {},
    /**
     * True while the one thing a new install cannot guess is still to be said:
     * that the button in the corner is where an account comes from.
     *
     * The shell's business rather than the accounts page's, because the button
     * it points at belongs to the shell — and pointing at it means knowing where
     * it is, which only the composable drawing it can say.
     */
    showAccountHint: Boolean = false,
    /** Read: not shown again. */
    onAccountHintDone: () -> Unit = {},
    /**
     * The tab the user is simply *resting* on — Home, Reminders, Timeline or
     * Accounts — or null when they are part way through something.
     *
     * Three things wait on a quiet moment, and none of them may arrive over a
     * form being filled in, a holding being created or a hint still being read:
     * those are all a user in the middle of an act, and something sliding up
     * under their thumb either loses the tap or takes the page. Settings is
     * excluded too, for the opposite reason — the lock has its own switch there,
     * and a sheet offering what the row two inches down offers is the app
     * arguing with itself.
     *
     * **Which** tab, and not merely whether: the two swipe lessons each write a
     * row onto a particular page, and writing one while the user is on another
     * tab opens a holding they never asked for on a page they are not looking
     * at. The lock offer is the one that does not care which.
     *
     * Reported rather than decided here: only the shell knows where the user is,
     * and only the activity knows whether there is anything left to say.
     */
    onIdleOn: (String?) -> Unit = {},
    /**
     * The user has come back out of a screen they opened — a form, an editor, a
     * statement — and is standing on a tab again.
     *
     * The one moment the app puts an ad up. It is the honest place for it: they
     * have just finished something rather than been interrupted part way through
     * it, which is the whole difference between an ad and a lost form. Reported
     * only when the tab they landed on is a quiet one, so a lesson still being
     * read is not talked over either.
     */
    onLeftScreen: () -> Unit = {},
    /**
     * True while the Timeline still owes its lesson: that the month steps when
     * the page is dragged sideways. Said on the Timeline itself rather than
     * during setup, because a gesture explained on a page the user is not on is
     * a sentence about nothing.
     */
    showTimelineHint: Boolean = false,
    /** Read: the card goes, and the practice row is written. */
    onTimelineHintDone: () -> Unit = {},
    /**
     * Steps the shared month by one, for the lesson that is about stepping it.
     *
     * The shell owns no month of its own — [MonthSelection] is the one fact both
     * tabs read — so the step is handed in rather than reached for here.
     */
    onStepPeriod: (Int) -> Unit = {},
    /**
     * True while a practice row is on the timeline waiting to be swiped away.
     * The hint that goes with it is deliberately not a [CoachMark] — see
     * [PracticeHint].
     */
    showSwipeHint: Boolean = false,
    /** Which row the lesson is on, so the timeline can measure it for the card. */
    practiceEntryId: String? = null,
    /**
     * The accounts page's own version of the pair above: a demo holding to
     * swipe away, taught the moment the button hint has been read.
     */
    showAccountSwipeHint: Boolean = false,
    demoAccountId: String? = null,
    /** Given up on rather than done: the holding is taken back either way. */
    onAccountSwipeHintSkipped: () -> Unit = {},
    /** Given up on rather than done: the row is taken back either way. */
    onSwipeHintSkipped: () -> Unit = {},
) {
    // The theme is applied by the activity, outside this, so the lock screen in
    // front of it is drawn in the same one.
    ProvideFormatters(settings) {
        // Where the overlay below begins, in window coordinates. Declared out
        // here because the Box that reports it also reads it.
        var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
        // Everything the shell draws, and above it the one card that has to be
        // over the tabs *and* let a touch through. It cannot be a popup — see
        // [PracticeSpotlight] — so it is a sibling of the Scaffold here, and it
        // reports where it begins so the row's window coordinates can be put
        // into its own.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.boundsInWindow().topLeft }
        ) {
            val navController = rememberNavController()
            // Open while the user is choosing what to add. Both pages with a
            // button ask a question before the form now — which holding on
            // Accounts, which of the three movements on the Timeline — and one
            // flag serves both, since only one button exists at a time.
            var addingHolding by remember { mutableStateOf(false) }
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination
            val snackbarHostState = remember { SnackbarHostState() }
            // Where the floating button is, in window coordinates, for the one
            // hint that points at it. Null until it has been drawn — and null
            // again on every page that has no button, which is what stops the
            // hint pointing at where it used to be.
            var addButtonAt by remember { mutableStateOf<Rect?>(null) }
            val scope = rememberCoroutineScope()
            val deletedMessage = stringResource(R.string.deleted_snackbar)
            val undoLabel = stringResource(R.string.action_undo)

            val onTopLevelDestination = TopLevelDestination.entries.any { destination ->
                currentRoute?.hierarchy?.any { it.route == destination.route } == true
            }
            val onAccounts = currentRoute?.hierarchy?.any { it.route == Routes.ACCOUNTS } == true
            val onSettings = currentRoute?.hierarchy?.any { it.route == Routes.SETTINGS } == true
            val onHome = currentRoute?.hierarchy?.any { it.route == Routes.HOME } == true
            val onTimeline =
                currentRoute?.hierarchy?.any { it.route == Routes.TIMELINE } == true
            // The practice row, and where the overlay that lights it begins.
            // Both are in window coordinates; the second is subtracted from the
            // first, because that overlay is drawn in the app's own composition
            // rather than in a popup at the window's origin.
            var practiceAt by remember { mutableStateOf<Rect?>(null) }
    /** The day heading over the lit row, unioned with it where they touch. */
    var practiceHeaderAt by remember { mutableStateOf<Rect?>(null) }
            var demoAccountAt by remember { mutableStateOf<Rect?>(null) }
            // Reminders is a list of what is about to happen, and nothing can be
            // added to it: every row on it is written by a rule or a schedule.
            val onReminders =
                currentRoute?.hierarchy?.any { it.route == Routes.REMINDERS } == true
            // Whether there is a button in the corner for content to clear.
            // Computed once and used twice: the padding that keeps the last row
            // out from under it must not be reserved on the two pages that have
            // no button, where it read as a page that had not finished loading.
            val hasAddButton =
                onTopLevelDestination && !onSettings && !onHome && !onReminders

            // Switching tabs, written once: the bottom bar does it and so does a
            // notification asking for one, and the two must leave the same back
            // stack behind them.
            val switchTab: (String) -> Unit = { route ->
                navController.navigate(route) {
                    // Keep a single copy of each tab and preserve its scroll
                    // position.
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            // Where the user is, named for whoever is waiting for a quiet
            // moment. Keyed on the answer, so it is reported when it changes and
            // not on every recomposition.
            val idleOnTab = onTopLevelDestination && !onSettings && !addingHolding &&
                !showAccountHint && !showTimelineHint && !showSwipeHint &&
                !showAccountSwipeHint
            val idleRoute = when {
                !idleOnTab -> null
                onAccounts -> Routes.ACCOUNTS
                onTimeline -> Routes.TIMELINE
                onHome -> Routes.HOME
                onReminders -> Routes.REMINDERS
                else -> null
            }
            LaunchedEffect(idleRoute) { onIdleOn(idleRoute) }

            // And when they come back *out* of something. Anything that is not
            // a tab is a screen the user opened on purpose — a form, a holding,
            // a statement — so leaving one is a piece of work finished, which is
            // the only moment an ad may take. See [onLeftScreen].
            //
            // Held until the tab is a quiet one rather than reported the instant
            // the route changes: a lesson still on screen is something the user
            // is in the middle of, exactly as a form is.
            var wasInsideScreen by remember { mutableStateOf(false) }
            LaunchedEffect(onTopLevelDestination, idleRoute) {
                if (!onTopLevelDestination) {
                    wasInsideScreen = true
                } else if (wasInsideScreen && idleRoute != null) {
                    wasInsideScreen = false
                    onLeftScreen()
                }
            }

            // The options belong to the button on the accounts page, so leaving
            // the page takes them with it — a tab can be tapped while they are
            // open, since only the page behind them is covered.
            LaunchedEffect(onAccounts, onTimeline) {
                if (!onAccounts && !onTimeline) addingHolding = false
            }
            // Back closes the menu before it leaves the page, exactly as it would
            // have dismissed the sheet this replaced.
            BackHandler(enabled = addingHolding) { addingHolding = false }

            // The alert goes over the page from the top; see [TopSnackbar].
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (onTopLevelDestination) {
                            NavigationBar {
                                TopLevelDestination.entries.forEach { destination ->
                                    val selected = currentRoute?.hierarchy
                                        ?.any { it.route == destination.route } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { switchTab(destination.route) },
                                        icon = {
                                            Icon(
                                                destination.icon,
                                                contentDescription = null,
                                            )
                                        },
                                        label = { Text(stringResource(destination.labelRes)) },
                                    )
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        // The button adds whatever the page is a list of. On Accounts
                        // that is an account, which is why the page no longer carries
                        // a second add icon in its heading: two buttons a thumb-width
                        // apart doing different things is a coin toss, and the one in
                        // the corner is the one the thumb reaches.
                        //
                        // Settings is a list of nothing — there is no such thing as
                        // adding a setting — so it has no button at all. Nor has
                        // Home: it is a summary of the month rather than a list of
                        // anything, and the same entry is added from History, which
                        // is the list it would join.
                        if (hasAddButton) {
                            // The options grow out of the button in the same slot, so
                            // neither of them has to know where the other one is.
                            Column(horizontalAlignment = Alignment.End) {
                                // And they take no *height* in it. The Scaffold
                                // stacks the snackbar directly above this slot, so
                                // with the options measured the whole strip grew by
                                // six option cards the moment the plus was tapped —
                                // and a "Deleted" that had just appeared at the foot
                                // of the page shot up to the middle of it. Fixed at
                                // zero and allowed to overflow upwards, the slot is
                                // the button's own size whether the menu is open or
                                // not, and the options are simply drawn above it.
                                Box(
                                    contentAlignment = Alignment.BottomEnd,
                                    modifier = Modifier
                                        .height(0.dp)
                                        .wrapContentHeight(
                                            align = Alignment.Bottom,
                                            unbounded = true,
                                        ),
                                ) {
                                    if (onAccounts) {
                                        AddHoldingMenu(
                                            expanded = addingHolding,
                                            onPick = { group ->
                                                addingHolding = false
                                                navController.navigate(
                                                    Routes.holding(group = group.name)
                                                )
                                            },
                                        )
                                    }
                                    if (onTimeline) {
                                        AddEntryMenu(
                                            expanded = addingHolding,
                                            canTransfer = canTransfer,
                                            onPick = { start ->
                                                addingHolding = false
                                                navController.navigate(
                                                    Routes.addEntry(
                                                        direction = if (start == EntryStart.IN) {
                                                            "IN"
                                                        } else {
                                                            "OUT"
                                                        },
                                                        transfer = start == EntryStart.TRANSFER,
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }
                                // The plus turns into the way out of the menu it
                                // opened: a quarter turn is the whole animation, since
                                // a plus on its side is already a cross.
                                val turn by animateFloatAsState(
                                    targetValue = if (addingHolding) 45f else 0f,
                                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                                    label = "add-button-turn",
                                )
                                FloatingActionButton(
                                    modifier = Modifier.onGloballyPositioned {
                                        addButtonAt = it.boundsInWindow()
                                    },
                                    onClick = {
                                        // The question comes first on both pages that
                                        // have a button: six kinds of holding open six
                                        // different forms, and money out, money in and
                                        // a transfer are three. Either way it is asked
                                        // in front of the form rather than as its
                                        // first row, and the form opens knowing.
                                        addingHolding = !addingHolding
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = stringResource(
                                            when {
                                                addingHolding -> R.string.cd_close
                                                onAccounts -> R.string.accounts_add
                                                else -> R.string.cd_add_money
                                            }
                                        ),
                                        modifier = Modifier.rotate(turn),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val direction = LocalLayoutDirection.current
                    // Screens draw their own horizontal padding; only the system
                    // insets and bars come from the Scaffold.
                    //
                    // The status bar inset is deliberately *not* part of this:
                    // content padding inside a lazy list is scrollable space, so
                    // rows travelled up behind the clock and notification icons and
                    // showed through them. It is applied as real padding on the
                    // screen instead — see [topLevel] — which stops the list short
                    // of the status bar however far it is scrolled.
                    //
                    // The 88dp is the floating button and the gap under it, and it
                    // is reserved only where there is one. On Settings and Home it
                    // was a screenful-and-a-bit of empty space under the last card
                    // that nothing would ever occupy.
                    val contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        top = 8.dp,
                        bottom = padding.calculateBottomPadding() + if (hasAddButton) 88.dp else 16.dp,
                    )
                    val topLevel = Modifier.padding(top = padding.calculateTopPadding())

                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                        // Editors slide in over the page that opened them and slide
                        // back out on back — the motion says "a sheet on top", so
                        // where the back gesture leads is never a surprise. The
                        // tabs override this with a plain cross-fade below: they
                        // sit beside each other, and sliding would claim an order
                        // they do not have.
                        enterTransition = {
                            slideInHorizontally(
                                tween(300, easing = FastOutSlowInEasing)
                            ) { it / 3 } + fadeIn(tween(300))
                        },
                        exitTransition = { fadeOut(tween(150)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = {
                            slideOutHorizontally(
                                tween(280, easing = FastOutSlowInEasing)
                            ) { it / 3 } + fadeOut(tween(280))
                        },
                    ) {
                        composable(Routes.HOME, enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) {
                            HomeScreen(
                                onAddEntry = { navController.navigate(Routes.addEntry()) },
                                onOpenEntry = { navController.navigate(Routes.addEntry(it)) },
                                // An instalment opens the debt it counts against,
                                // wherever it is tapped — see [openEntry].
                                onOpenLoan = { navController.navigate(Routes.holding(loanId = it)) },
                                // A slice of "where it went" opens the holding it
                                // was spent through — the same page its row on the
                                // Accounts tab opens.
                                onOpenAccount = {
                                    navController.navigate(Routes.holding(accountId = it))
                                },
                                // A tab switch, not a push onto Home.
                                //
                                // "See all" is a move to another tab and has to
                                // leave the same back stack behind it as tapping
                                // that tab would. Navigating plainly stacked a
                                // second Timeline *above* Home, and the Home tab
                                // then could not be reached at all: its own
                                // navigate pops back to Home saving what it popped,
                                // and `restoreState` put the very Timeline it had
                                // just popped straight back on top. The tab looked
                                // dead; the back stack was chasing its own tail.
                                onSeeAllMovements = { switchTab(Routes.TIMELINE) },
                                modifier = topLevel,
                                contentPadding = contentPadding,
                            )
                        }
                        composable(Routes.REMINDERS, enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { entry ->
                            val reminderViewModel: ReminderViewModel = hiltViewModel(entry)
                            // The day a tapped notification was about. Applied here
                            // rather than where the tab is navigated to, because
                            // this page has to exist before it can be told anything;
                            // clearing it is this effect's job for the same reason.
                            LaunchedEffect(openReminderOn) {
                                val day = openReminderOn ?: return@LaunchedEffect
                                reminderViewModel.showDayOn(day)
                                onReminderDayOpened()
                            }
                            ReminderScreen(
                                viewModel = reminderViewModel,
                                onOpenEntry = { navController.navigate(Routes.addEntry(it)) },
                                onOpenLoan = { navController.navigate(Routes.holding(loanId = it)) },
                                // A projected row opens whatever wrote it, exactly as
                                // the timeline's does — the loan, or the entry the
                                // rule was created from.
                                onOpenProjection = { seriesId ->
                                    scope.launch {
                                        when (val target = reminderViewModel.resolve(seriesId)) {
                                            is ProjectionTarget.Entry ->
                                                navController.navigate(Routes.addEntry(target.id))
                                            is ProjectionTarget.LoanEditor ->
                                                navController.navigate(Routes.holding(loanId = target.id))
                                            // Nothing left behind it to open. The
                                            // timeline offers to stop the rule here;
                                            // this page is a list to read, so it
                                            // simply does nothing rather than
                                            // offering to delete from a glance.
                                            is ProjectionTarget.Rule -> Unit
                                        }
                                    }
                                },
                                // The same receipt the timeline's swipe leaves, from
                                // the same host: a movement removed from either page
                                // is one act, and it comes back the same way.
                                onDeleted = {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = deletedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            reminderViewModel.undoDelete()
                                        }
                                    }
                                },
                                modifier = topLevel,
                                contentPadding = contentPadding,
                            )
                        }
                        composable(Routes.TIMELINE, enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) { entry ->
                            val timelineViewModel: TimelineViewModel = hiltViewModel(entry)
                            TimelineScreen(
                                viewModel = timelineViewModel,
                                highlightEntryId = practiceEntryId.takeIf { showSwipeHint },
                                onHighlightBounds = { practiceAt = it },
                                onHighlightHeaderBounds = { practiceHeaderAt = it },
                                onOpenEntry = { navController.navigate(Routes.addEntry(it)) },
                                onOpenLoan = { navController.navigate(Routes.holding(loanId = it)) },
                                onOpenAccount = {
                                    navController.navigate(Routes.holding(accountId = it))
                                },
                                onDeleted = {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = deletedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            timelineViewModel.undoDelete()
                                        }
                                    }
                                },
                                modifier = topLevel,
                                contentPadding = contentPadding,
                            )
                        }
                        composable(Routes.ACCOUNTS, enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) {
                            AccountsScreen(
                                onOpenAccount = {
                                    navController.navigate(Routes.holding(accountId = it))
                                },
                                onOpenLoan = { navController.navigate(Routes.holding(loanId = it)) },
                                modifier = topLevel,
                                contentPadding = contentPadding,
                                highlightAccountId = demoAccountId.takeIf { showAccountSwipeHint },
                                onHighlightBounds = { demoAccountAt = it },
                                // No Undo behind it: a holding takes every movement
                                // that ever touched it, and the dialog before it has
                                // already said so. What is left to say is that it
                                // went.
                                onDeleted = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = deletedMessage,
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                },
                            )
                        }
                        composable(
                            route = Routes.HOLDING_PATTERN,
                            arguments = listOf(
                                navArgument(Routes.ARG_ACCOUNT_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument(Routes.ARG_LOAN_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument(Routes.ARG_GROUP) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                            // Moving between one bank's holdings is not arriving at a
                            // new screen — it is this screen showing a different tab
                            // — so it does not slide in over itself. The default
                            // motion drew both forms at once, one sliding across the
                            // other, which read as a page having opened on top of a
                            // nearly identical page. Anything arriving from
                            // *elsewhere* keeps the sheet-over-the-page slide.
                            enterTransition = {
                                if (initialState.holdingRoute) {
                                    EnterTransition.None
                                } else {
                                    slideInHorizontally(
                                        tween(300, easing = FastOutSlowInEasing)
                                    ) { it / 3 } + fadeIn(tween(300))
                                }
                            },
                            exitTransition = {
                                if (targetState.holdingRoute) ExitTransition.None else fadeOut(tween(150))
                            },
                            popEnterTransition = {
                                if (initialState.holdingRoute) EnterTransition.None else fadeIn(tween(200))
                            },
                            popExitTransition = {
                                if (targetState.holdingRoute) {
                                    ExitTransition.None
                                } else {
                                    slideOutHorizontally(
                                        tween(280, easing = FastOutSlowInEasing)
                                    ) { it / 3 } + fadeOut(tween(280))
                                }
                            },
                        ) { entry ->
                            // Only a loan has a statement to open, so the offer is
                            // absent rather than disabled when this is an account.
                            val loanId = entry.arguments
                                ?.getString(Routes.ARG_LOAN_ID)
                                ?.takeIf { it.isNotBlank() }
                            HoldingEditorScreen(
                                onDone = { navController.popBackStack() },
                                onOpenLedger = loanId?.let { id ->
                                    { navController.navigate(Routes.loanLedger(id)) }
                                },
                                // Another holding at the same bank, in place of this
                                // one rather than on top of it: the tabs are one
                                // screen showing one bank, so five taps between a
                                // savings account and its loan must not leave five
                                // editors to back out through.
                                onOpenHolding = { accountId, holdingLoanId ->
                                    navController.navigate(
                                        Routes.holding(
                                            accountId = accountId,
                                            loanId = holdingLoanId,
                                        )
                                    ) {
                                        popUpTo(Routes.HOLDING_PATTERN) { inclusive = true }
                                    }
                                },
                                // Everything that has touched this account, on a page
                                // of its own. Pushed, not swapped: this is a step
                                // *into* the holding, and back has to return to it.
                                onOpenStatement = {
                                    navController.navigate(Routes.accountStatement(it))
                                },
                                // What is left to pay, on a page of its own — a step
                                // into the debt, exactly as its statement is, so back
                                // returns to the terms it was opened from.
                                onOpenSchedule = loanId?.let { id ->
                                    { navController.navigate(Routes.loanSchedule(id)) }
                                },
                            )
                        }
                        composable(
                            route = Routes.SCHEDULE_PATTERN,
                            arguments = listOf(
                                navArgument(Routes.ARG_LOAN_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) {
                            LoanScheduleScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = Routes.STATEMENT_PATTERN,
                            arguments = listOf(
                                navArgument(Routes.ARG_ACCOUNT_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) {
                            AccountStatementScreen(
                                onBack = { navController.popBackStack() },
                                // A row opens the movement itself — and on this page
                                // alone, the tapped *occurrence* rather than the rule
                                // behind it. A statement is the record: a correction
                                // made here is about the one date that came out
                                // wrong, and the rule goes on saying what every
                                // other month does. Pushed on top: back returns to
                                // the list it was read in.
                                onOpenEntry = { id ->
                                    navController.navigate(
                                        Routes.addEntry(id, occurrence = true)
                                    )
                                },
                                onOpenLoan = { id ->
                                    navController.navigate(Routes.holding(loanId = id))
                                },
                            )
                        }
                        composable(
                            route = Routes.LEDGER_PATTERN,
                            arguments = listOf(
                                navArgument(Routes.ARG_LOAN_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) {
                            // Nothing to open from here: a row on a debt's
                            // statement turns over and shows its working rather
                            // than leading to an editor — see [LoanLedgerScreen].
                            LoanLedgerScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.SETTINGS, enterTransition = tabEnter, exitTransition = tabExit, popEnterTransition = tabEnter, popExitTransition = tabExit) {
                            SettingsScreen(
                                modifier = topLevel,
                                onMessage = { message ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = message.text,
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                },
                                contentPadding = contentPadding,
                            )
                        }
                        composable(
                            route = Routes.ADD_ENTRY_PATTERN,
                            arguments = listOf(
                                navArgument(Routes.ARG_ENTRY_ID) {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument(Routes.ARG_DIRECTION) {
                                    type = NavType.StringType
                                    defaultValue = "OUT"
                                },
                                navArgument(Routes.ARG_TRANSFER) {
                                    type = NavType.StringType
                                    defaultValue = "false"
                                },
                                navArgument(Routes.ARG_OCCURRENCE) {
                                    type = NavType.StringType
                                    defaultValue = "false"
                                },
                            ),
                        ) {
                            AddEntryScreen(onDone = { navController.popBackStack() })
                        }
                    }

                    // A tab asked for from outside — the reminder notification. Done
                    // here rather than by starting the NavHost somewhere else, so
                    // Home stays behind it and back leads where it always does. It
                    // waits on the graph being set, which is why it sits after the
                    // NavHost, and it clears the request so a recomposition or a
                    // rotation cannot pull the user off the tab they moved to.
                    LaunchedEffect(openAt) {
                        val route = openAt ?: return@LaunchedEffect
                        switchTab(route)
                        onOpened()
                    }

                    // Over the page and under the button, drawn last so it covers
                    // what the options are floating above. It is emitted beside the
                    // NavHost rather than around it because both fill the same body
                    // slot, and the later of the two is the one on top.
                    AddHoldingScrim(
                        expanded = addingHolding,
                        onDismiss = { addingHolding = false },
                    )

                    // Last of all, so it is over the page, the tabs and the button
                    // it is pointing at. Only on the page that button adds an
                    // account from, and only once it has been measured: a card that
                    // arrived before the tab it belongs to would point at nothing on
                    // a page it is not talking about.
                    if (showAccountHint && onAccounts && addButtonAt != null) {
                        CoachMark(
                            title = stringResource(R.string.hint_first_account_title),
                            body = stringResource(R.string.hint_first_account_body),
                            target = addButtonAt,
                            actionLabel = stringResource(R.string.hint_got_it),
                            onAction = onAccountHintDone,
                        )
                    }

                    // The same card, one page along — and the only one of the
                    // three with nothing lit under it. What it is about is the
                    // width of the page rather than any control on it, so the two
                    // arrows at the sides are what it points at and there is no
                    // spotlight to withhold it until.
                    if (showTimelineHint && onTimeline) {
                        CoachMark(
                            title = stringResource(R.string.hint_swipe_period_title),
                            body = stringResource(R.string.hint_swipe_period_body),
                            target = null,
                            actionLabel = stringResource(R.string.hint_got_it),
                            onAction = onTimelineHintDone,
                            showEdgeArrows = true,
                            // The one card whose gesture is allowed through, so
                            // the month visibly moves for the reader who makes
                            // it. Without this the lesson asked for a swipe and
                            // then swallowed it, which reads as nothing having
                            // happened at all.
                            onStepPeriod = onStepPeriod,
                        )
                    }

                }
                TopSnackbar(snackbarHostState, Modifier.align(Alignment.TopCenter))
            }

            // Outside the Scaffold, so the dim covers the tabs as the other two
            // cards do — and drawn last, so it is over everything but the row it
            // has deliberately left live.
            // The row and the date above it, as one hole.
            //
            // What a swipe takes away is a payment, and a payment on the
            // timeline is a row *under a date* — lighting the row alone left
            // its own date dimmed a few pixels above it, which read as the
            // spotlight having missed rather than as a deliberately small
            // target.
            //
            // Unioned only while the two are still touching. That heading is
            // sticky: once the list is scrolled it pins to the top of the page
            // and stops being above its own rows, and a hole stretched between
            // them would cut half the screen out of the dim. Apart, the row
            // alone is the honest answer.
            val practiceLit = practiceAt?.let { row ->
                val header = practiceHeaderAt
                if (header != null && abs(header.bottom - row.top) < HEADER_JOIN_SLACK) {
                    Rect(
                        left = min(header.left, row.left),
                        top = header.top,
                        right = max(header.right, row.right),
                        bottom = row.bottom,
                    )
                } else {
                    row
                }
            }

            if (showSwipeHint && onTimeline) {
                PracticeSpotlight(
                    title = stringResource(R.string.hint_swipe_delete_title),
                    body = stringResource(R.string.hint_swipe_delete),
                    target = practiceLit?.translate(-overlayOrigin),
                    actionLabel = stringResource(R.string.hint_skip),
                    onAction = onSwipeHintSkipped,
                )
            }

            // The same lesson on the accounts page, opening on the same clause:
            // a swipe takes a row away wherever the row is, so saying that twice
            // in two ways would make it read as two different gestures. What it
            // adds is the one gesture this page has and the timeline has not —
            // a long press on a holding, which is how a balance is corrected.
            if (showAccountSwipeHint && onAccounts) {
                PracticeSpotlight(
                    title = stringResource(R.string.hint_swipe_delete_title),
                    body = stringResource(R.string.hint_swipe_delete_holding),
                    target = demoAccountAt?.translate(-overlayOrigin),
                    actionLabel = stringResource(R.string.hint_skip),
                    onAction = onAccountSwipeHintSkipped,
                )
            }
        }
    }
}
