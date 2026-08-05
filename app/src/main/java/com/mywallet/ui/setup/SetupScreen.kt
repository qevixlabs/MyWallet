package com.mywallet.ui.setup

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mywallet.R
import com.mywallet.core.date.CalendarSystem
import com.mywallet.core.money.CurrencyOption
import com.mywallet.data.settings.AppSettings
import com.mywallet.data.settings.SettingsStore
import com.mywallet.ui.components.ChoicePicker
import com.mywallet.ui.components.ChoiceRow
import com.mywallet.ui.components.Explain
import com.mywallet.ui.components.ExplainedRow
import com.mywallet.ui.components.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * How far the app behind the panel is dimmed.
 *
 * Enough that the panel is plainly the thing being read, and not so much that
 * the app disappears: what is behind it is half the point — the questions are
 * about something that is already there.
 */
private const val SCRIM_ALPHA = 0.6f


/**
 * The three questions asked before the app is opened for the first time.
 *
 * Deliberately the same three settings the Settings tab holds, written to the
 * same store as they are tapped rather than gathered up and saved at the end:
 * the language takes effect immediately, the calendar and the currency are what
 * every page behind this one is about to be drawn in, and a "Save" that could
 * fail would leave the app open in a state nobody chose.
 *
 * There is no currency restatement here, unlike Settings: nothing has been
 * recorded yet, so there is nothing to re-value. That is the whole reason this
 * is asked on the way in — answering it later means every figure already on file
 * has to be converted.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setLanguage(tag: String?) = viewModelScope.launch {
        settingsStore.setLanguageTag(tag)
        // AppCompat applies this immediately and persists it across restarts;
        // on API 33+ it hands off to the system's per-app language setting.
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    fun setCalendar(system: CalendarSystem) =
        viewModelScope.launch { settingsStore.setCalendarSystem(system) }

    fun setCurrency(code: String) = viewModelScope.launch { settingsStore.setCurrency(code) }

    fun enableLock() = viewModelScope.launch { settingsStore.setScreenLock(true) }

    /** Written last, so a phone killed part way through asks again. */
    fun finish() = viewModelScope.launch { settingsStore.setSetupDone() }
}

/**
 * The three questions, put over the app rather than in front of it.
 *
 * Deliberately **not a page of its own**. It is drawn as one panel on a dimmed
 * app — the tabs, the month, the empty Home behind it — because what it is
 * saying is "here is how this will read", and a full screen with no app behind
 * it says instead "answer this before you may see anything". The user is being
 * helped into something that is already there, and the layer says so.
 *
 * Three questions and a way past them, and nothing else. The lock is offered
 * too, but not here and not now — it comes up from the bottom of the app a
 * while after this panel has gone (see [LockOfferSheet]), because somebody who
 * has not seen the app yet has no reason to want it guarded.
 *
 * The scrim eats every touch that is not on the panel. It is not a way out —
 * these three have to be answered — but the page underneath must not be
 * tappable through a layer that is covering it.
 */
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Written down and then handed back to the shell, in that order and never
    // one without the other: the flag is what stops these questions coming back
    // on the next launch, and [onDone] is what draws the app behind them.
    val done: () -> Unit = {
        viewModel.finish()
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                // Swallowed, not answered: a tap outside is not a way past three
                // questions the whole app is drawn from, and a page that reacted
                // to one through the dim would read as still in use.
                .pointerInput(Unit) { detectTapGestures {} }
        )

        Surface(
            shape = RoundedCornerShape(28.dp),
            // The colour every card in the app is, and no tonal elevation on top
            // of it: the panel is lifted by its shadow, and a tint as well made
            // it a different kind of surface from the cards it is holding.
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 12.dp,
            // The screen less the system bars and a hairline of margin — about
            // nineteen twentieths of the phone. What is left is deliberately not
            // nothing: the app showing along every edge is what says this is a
            // layer over something rather than a page instead of it.
            modifier = Modifier
                .align(Alignment.Center)
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // The questions scroll and the button does not. A fixed panel
                // whose whole contents scrolled would put the one thing that
                // ends it below the fold on a short phone; pinned at the foot it
                // is where the thumb is and where it stays.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    // Set loosely: the panel is most of the screen, and three
                    // questions packed at the top of it leave a hole above the
                    // button rather than a page.
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.setup_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // In the order they matter to what is drawn behind: the words
                    // first, then the dates those words are written in, then the
                    // money. Unboxed, because the panel is already the card.
                    SettingsGroup(
                        title = R.string.settings_language,
                        boxed = false,
                    ) {
                        // The same box Settings draws, in the same place with the
                        // same line beside it: a language chosen here and the
                        // same language changed a week later must not look like
                        // two different decisions.
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

                    SettingsGroup(
                        title = R.string.settings_calendar,
                        boxed = false,
                    ) {
                        // Chips here as in Settings, and for the same reason:
                        // two answers that fit on a line are read at a glance,
                        // and this is the one question on the panel whose answer
                        // the reader may not have a word for yet.
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

                    SettingsGroup(
                        title = R.string.settings_currency,
                        boxed = false,
                    ) {
                        // The same box Settings draws, with its explanation
                        // beside it in the same place: a currency chosen here
                        // and the same currency changed a week later must not
                        // look like two different decisions.
                        ExplainedRow(R.string.settings_currency_explain) {
                            ChoicePicker(
                                options = CurrencyOption.ALL.map { it.code to it.pickerLabel },
                                selected = settings.currencyCode,
                                onSelect = viewModel::setCurrency,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(onClick = done, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_continue))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.setup_changeable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
