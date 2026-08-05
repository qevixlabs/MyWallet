package com.mywallet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import com.mywallet.data.settings.ThemeChoice
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Palette notes — deliberately not the default fintech teal-and-mint:
 *
 *  - The page is cool paper, not cream, so the app reads as calm rather than
 *    precious.
 *  - Money moves in two directions and the two are coloured: red out, green in.
 *    Money out was plain ink for a long while, on the argument that most rows
 *    are money out and colouring them all red makes ordinary life look like a
 *    series of alarms — and what that produced was a column where one figure in
 *    ten was coloured and the rest were the same ink as the words beside them.
 *    A reader scanning for what a month cost had nothing to scan. The red is
 *    therefore a *considered* red rather than the alarm one: [WalletColors.moneyOut]
 *    sits well clear of `error`, which stays for the genuinely alarming — an
 *    account below zero, spending past income, a field that will not save.
 *  - A transfer is neither, and stays uncoloured: the money has not left the
 *    user's world, and a red figure would say it had.
 *  - Direction is never signalled by colour alone; every amount carries a
 *    + or - sign for anyone who cannot separate the hues.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5CE6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF00174A),
    secondary = Color(0xFF12715C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EEE2),
    onSecondaryContainer = Color(0xFF00251C),
    error = Color(0xFFB3341F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF410000),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF15181D),
    surface = Color(0xFFF4F6F9),
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF5C6672),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFF8A939F),
    outlineVariant = Color(0xFFE1E6EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7A9BFF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1B3E9E),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFF4FCBAB),
    onSecondary = Color(0xFF003830),
    secondaryContainer = Color(0xFF005145),
    onSecondaryContainer = Color(0xFFC9EEE2),
    error = Color(0xFFFF8A73),
    onError = Color(0xFF5F1500),
    errorContainer = Color(0xFF862008),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF0E1216),
    onBackground = Color(0xFFE9EDF2),
    surface = Color(0xFF0E1216),
    onSurface = Color(0xFFE9EDF2),
    surfaceVariant = Color(0xFF232A32),
    onSurfaceVariant = Color(0xFF94A0AD),
    surfaceContainer = Color(0xFF171C22),
    surfaceContainerHigh = Color(0xFF1D242B),
    surfaceContainerHighest = Color(0xFF232A32),
    surfaceContainerLow = Color(0xFF131920),
    surfaceContainerLowest = Color(0xFF0A0E12),
    outline = Color(0xFF6B7683),
    outlineVariant = Color(0xFF2C343D),
)

/**
 * Colours Material does not have a slot for. Kept in one place so a screen can
 * never invent its own "money green".
 */
data class WalletColors(
    val moneyIn: Color,
    /**
     * What a row cost. Red, and deliberately not
     * [androidx.compose.material3.ColorScheme.error]: this is on every second
     * row of every list in the app, and the shade that means "this will not
     * save" cannot also mean "you bought lunch". Dark enough on paper and light
     * enough on ink to be read at `bodyMedium`, which is the size it is drawn at.
     */
    val moneyOut: Color,
    /**
     * What is owed. Deliberately not [androidx.compose.material3.ColorScheme.error]:
     * a loan being repaid on schedule is ordinary life, and five rows of alarm
     * red made the accounts page read as five emergencies. This is an ember —
     * warm enough to say "this is money going the wrong way", calm enough to
     * live next to. True red stays for the genuinely alarming: an account
     * below zero, spending past income, a field that will not save.
     */
    val debt: Color,
    val hairline: Color,
    val stripTrack: Color,
    val stripToday: Color,
    /**
     * The faint band that tells one row from the next — see
     * [com.mywallet.ui.components.rowStripe], which is the only thing that reads
     * it.
     *
     * It is a colour per scheme rather than one alpha over the page's own ink,
     * which is what it was: four percent of white on a near-black page is a
     * clear step, and four percent of near-black on cool paper is nothing at
     * all. The value looked scheme-independent and was in fact only ever tuned
     * against the dark one, so half the phones had no banding.
     *
     * **A breath, not a stripe.** It was set nearly twice this, which on the
     * paper a list now sits on read as two different colours of row rather than
     * as one list — a checkerboard, which is a pattern the eye follows instead
     * of reading past. What the band has to do is stop three lines of text
     * running into the three below them, and the smallest step that does it is
     * the right one: anything more claims the second row is a different kind of
     * thing from the first.
     */
    val rowBand: Color,
    /**
     * The paper a list of movements is laid on — Home's recent list, Reminders,
     * each day of the timeline. See [com.mywallet.ui.components.listPanel].
     *
     * Those three pages drew their rows straight onto the page, so a screenful
     * of movements had no edge anywhere: the list and the background it sat on
     * were the same colour, and the whole page read pale. The Accounts tab
     * answers this by putting a bank's holdings on a white card, which works —
     * but repeating it here would make every page in the app one shape, and on
     * Home the recent list would then be a third white card under two others
     * with nothing saying it is a different kind of thing.
     *
     * So this is deliberately *not* [androidx.compose.material3.ColorScheme.surfaceContainer]:
     * where a card is raised off the page towards white, this is a sheet set
     * into it — a shade deeper and cooler in the light scheme, a shade lighter
     * and bluer in the dark one. A card is a thing on the page; a list is a
     * tray cut into it.
     */
    val listSurface: Color,
    /**
     * The stops of the one payment-card face the app draws — see
     * [com.mywallet.ui.components.BankCardFace].
     *
     * A list of stops rather than a single colour because the whole point of
     * the thing is the diagonal: a flat rectangle of primary blue reads as a
     * banner, and it is the sweep from indigo through the app's own blue into
     * its own green that reads as a card.
     *
     * **Dark in both schemes, and deliberately.** Every other surface here
     * follows the phone; this one cannot, because a card face is a physical
     * object the page is holding rather than a region of the page, and a white
     * one would be a fourth shade of paper stacked on three others. What
     * changes between the schemes is only how *hot* it is allowed to be: on
     * cool paper it can carry the full primary, and on a near-black page the
     * same stops glare, so the dark scheme's are pulled down a step.
     *
     * Because the face is dark either way, everything drawn on it takes its ink
     * from [OnDarkPanel] rather than from the scheme in force.
     */
    val cardFace: List<Color>,
)

private val LightWalletColors = WalletColors(
    moneyIn = Color(0xFF12715C),
    moneyOut = Color(0xFFC0392B),
    debt = Color(0xFF9C4A2E),
    hairline = Color(0xFFE1E6EC),
    stripTrack = Color(0xFFE1E6EC),
    stripToday = Color(0xFF2B5CE6),
    // The band is a *cool* ink rather than the page's near-black, for the reason
    // the tray below is: a neutral wash over cool paper greys it, and a row that
    // has gone grey beside one that has not reads as two colours of row rather
    // than as one list. Mixed from the same dark blue the tray leans towards, so
    // the banded row is the tray a shade deeper and nothing else.
    //
    // **Measured against the tray, not against the page.** At 4% it stepped
    // eight points per channel off a tray that was itself only eleven off the
    // background — the band was very nearly as strong as the sheet it was drawn
    // on, so the odd rows read as a third surface rather than as the same one
    // shaded. Roughly five points is what separates two rows of one list.
    rowBand = Color(0xFF2A3444).copy(alpha = 0.025f),
    // A tray cut into the page, and cut into *this* page. It was #E7EDF7, whose
    // blue sits sixteen points above its red where the page's sits five above
    // its own — so the sheet read as a pale blue panel laid on cool grey paper
    // rather than as the same paper a shade deeper, and the mismatch was the
    // first thing the eye found on every list in the app. Only the hue was
    // brought back into the page's own family at first, the luminance step being
    // left where it was; the step itself then turned out to be the louder half
    // of the problem, so the tray now sits about seven points under the page
    // rather than eleven. It has to be found rather than noticed: what the sheet
    // is for is giving a screenful of movements an edge, and anything past the
    // faintest step that does that is a panel laid on the page.
    listSurface = Color(0xFFECEFF4),
    cardFace = listOf(Color(0xFF1C2A63), Color(0xFF2B5CE6), Color(0xFF157F6B)),
)

private val DarkWalletColors = WalletColors(
    moneyIn = Color(0xFF4FCBAB),
    moneyOut = Color(0xFFFF8F78),
    debt = Color(0xFFE59B7C),
    hairline = Color(0xFF2C343D),
    stripTrack = Color(0xFF2C343D),
    stripToday = Color(0xFF7A9BFF),
    rowBand = Color(0xFFE9EDF2).copy(alpha = 0.02f),
    listSurface = Color(0xFF161E29),
    // The same three stops a step down. At the light scheme's values a card
    // face on a near-black page is a lit panel rather than an object lying on
    // it, and the headline printed across it has to fight the blue underneath.
    cardFace = listOf(Color(0xFF16204A), Color(0xFF1F47B4), Color(0xFF0F5F52)),
)

val LocalWalletColors = staticCompositionLocalOf { LightWalletColors }

/** Shorthand: `WalletTheme.colors.moneyIn`. */
object WalletTheme {
    val colors: WalletColors
        @Composable get() = LocalWalletColors.current
}

@Composable
fun MyWalletTheme(
    /**
     * Which of the two schemes to draw, or null to follow the phone.
     *
     * A setting rather than only the system's, because plenty of people run
     * their phone one way and want this app the other — and a money app is read
     * both in bright sun and in bed.
     */
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    // Dynamic colour is deliberately not used: label colours are user-chosen and
    // must stay recognisable, and a wallpaper-derived scheme would fight them.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val walletColors = if (darkTheme) DarkWalletColors else LightWalletColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalWalletColors provides walletColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WalletTypography,
            content = content,
        )
    }
}

/**
 * The light scheme, forced, for the few things the opening draws *over* the app.
 *
 * A spotlight works by not dimming one thing, and in the dark scheme that is a
 * dark row left dark: the page around it goes to 62% black, the row stays where
 * it was, and there is nothing to see. The lesson is unreadable on the theme
 * half the phones are in.
 *
 * So the cards and the rows they are taught on are drawn light whatever the app
 * is set to — white paper laid on a dimmed page, which is what a spotlight is
 * meant to look like. It is deliberately **only** the tutorial: the welcome
 * questions and the lock offer are the user's own screens, arriving on a phone
 * they have set the way they like it, and forcing those to white would be the
 * app overruling a preference rather than lighting something up for a moment.
 */
@Composable
fun TutorialLight(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalWalletColors provides LightWalletColors,
        // The colour scheme alone is not enough. Text with nothing said about
        // its colour reads [LocalContentColor], which a scheme does not set —
        // only a Surface does — so a card and a row drawn light inside the dark
        // app kept the dark app's white ink, and every title on them vanished
        // into the paper. Everything that states its own colour was fine, which
        // is what made it look like one stray label rather than the rule.
        LocalContentColor provides LightColors.onSurface,
    ) {
        MaterialTheme(
            colorScheme = LightColors,
            typography = WalletTypography,
            content = content,
        )
    }
}

/**
 * The dark scheme, forced, for what is drawn on a dark panel the page is holding.
 *
 * The mirror of [TutorialLight] and the same argument in reverse. A payment
 * card's face is dark in both schemes — see [WalletColors.cardFace] — so the
 * scheme in force says nothing useful about what will read on it: in the light
 * app, `onSurface` is near-black ink and `moneyIn` is a deep forest green, and
 * both vanish into indigo. What is needed is the ink already designed to be read
 * against something dark, which is the dark scheme's, whatever the phone is set
 * to.
 *
 * [LocalContentColor] goes with it for the reason [TutorialLight] sets it: text
 * that says nothing about its own colour reads that rather than the scheme, and
 * only a `Surface` sets it — so a label drawn on the card face would otherwise
 * keep the light app's near-black.
 *
 * This is deliberately not "the dark theme for a subtree". It is for panels the
 * app draws dark on purpose; anything that follows the user's own choice must go
 * on following it.
 */
@Composable
fun OnDarkPanel(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalWalletColors provides DarkWalletColors,
        LocalContentColor provides DarkColors.onSurface,
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = WalletTypography,
            content = content,
        )
    }
}
