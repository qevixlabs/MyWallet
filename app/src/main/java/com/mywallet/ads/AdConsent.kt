package com.mywallet.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The permission that has to be in hand before a single ad may be requested.
 *
 * Google Play ships this app to every country it is not withheld from, and in
 * the EEA, the UK and Switzerland an ad served without a consent record is a
 * policy breach rather than an oversight — the account, not the build, is what
 * answers for it. The Mobile Ads SDK does not ask on anybody's behalf and does
 * not carry the code that could: `user-messaging-platform` is a separate
 * dependency, and this file is the whole of what it is here for.
 *
 * What it does *not* do is show a dialog to everybody. [ConsentInformation]
 * decides that from where the phone is: outside the regions above there is no
 * form to load, [gather] finds it can request ads on the first call and hands
 * straight back. Somebody in Nepal — which is very nearly everybody who will
 * open this app — sees nothing at all, and the developer's own phone therefore
 * cannot check this by running it. Use `ConsentDebugSettings` with a hashed
 * device id and `DEBUG_GEOGRAPHY_EEA` when the form itself needs looking at;
 * see developers.google.com/admob/android/privacy.
 *
 * The one rule here is ordering. Consent precedes
 * [com.google.android.gms.ads.MobileAds.initialize], which is why
 * [InterstitialAds.preload] routes through this rather than starting the SDK
 * itself.
 */
object AdConsent {

    private const val TAG = "AdConsent"

    /**
     * Whether the request below has been made this process.
     *
     * The trigger for an ad recurs, so [gather] is called again and again — and
     * `requestConsentInfoUpdate` is a network call that would be repeated with
     * every one of them. Once is enough: the answer is cached by the SDK across
     * launches, not merely across calls.
     */
    private val asked = AtomicBoolean(false)

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /**
     * Whether this phone is owed a way back into the consent it already gave.
     *
     * True only where [ConsentInformation] says a privacy option is required —
     * the same regions that are shown a form in the first place. Settings hides
     * the row on every other phone rather than offering a choice that would open
     * nothing, which is why this is a flow and not a constant.
     *
     * It is also the app keeping a promise it has already made in writing: the
     * privacy policy tells users in the EEA and the UK that they "can change
     * that answer later", and before this there was no way to. Google asks for
     * the same thing from the other side — the publish dialog for a consent
     * message says not to forget the revocation link.
     */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /**
     * Re-reads the cached requirement status.
     *
     * Called on the way through [gather], and again when Settings opens: the
     * flag above lives for one process, while the answer behind it is cached by
     * the SDK across launches. Somebody who opens Settings before anything has
     * asked for an ad would otherwise be shown no row on a phone that is owed
     * one.
     */
    fun refresh(context: Context) {
        _privacyOptionsRequired.value =
            UserMessagingPlatform.getConsentInformation(context)
                .privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    /**
     * Reopens the form so an answer already given can be changed.
     *
     * Takes a [Context] rather than an [Activity] because the caller is a
     * composable and the context it sees may be a wrapper; the activity is found
     * below. Does nothing if there is none — there is no form without a window
     * to put it in, and a crash on the Settings screen would be a poor trade for
     * a case that should not arise.
     */
    fun showPrivacyOptions(context: Context) {
        val activity = context.findActivity() ?: return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) {
                Log.w(TAG, "Privacy options: ${error.message}")
            }
            // The answer may have just changed, and with it whether the row that
            // opened this belongs on the screen at all.
            refresh(activity)
        }
    }

    private fun Context.findActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /**
     * Asks if it must, then reports whether ads may be requested.
     *
     * [onReady] may be called immediately, or after the user has dismissed a
     * form, or twice, or never — a phone whose owner refused is the last of
     * those, and no ad is fetched on it for the life of that refusal. Callers
     * are expected to be safe to enter more than once for this reason;
     * [InterstitialAds.startSdk] guards itself accordingly.
     */
    fun gather(activity: Activity, onReady: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        if (!asked.compareAndSet(false, true)) {
            // Already asked this process. The cached answer is still the
            // answer, and a second trigger arriving mid-form must not open a
            // second one over it.
            if (info.canRequestAds()) onReady()
            return
        }
        refresh(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                // Shows a form only where one is required and not yet answered.
                // Everywhere else this returns without drawing anything.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) {
                        Log.w(TAG, "Consent form: ${error.message}")
                    }
                    // Only now is the requirement status settled — before the
                    // update returned, the SDK had nothing to base it on.
                    refresh(activity)
                    if (info.canRequestAds()) onReady()
                }
            },
            { error ->
                // A phone with no network reaches here, and so does one Google
                // could not place. Neither is a reason to serve an ad it has no
                // consent for: the cached answer below is what decides, and on
                // a phone that has never had one it says no.
                Log.w(TAG, "Consent unavailable: ${error.message}")
                if (info.canRequestAds()) onReady()
            },
        )
        // Asked here as well, and not only from the callbacks above: somebody
        // who answered the form on an earlier launch has a cached consent that
        // is good right now, and making them wait on a network round trip would
        // cost the first ad of every launch for no gain.
        if (info.canRequestAds()) onReady()
    }
}
