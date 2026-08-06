package com.mywallet.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This app's own AdMob ids, registered against `com.qevixlabs.mywallet`.
 *
 * They replaced Google's published test ids, which served a "Test Ad"
 * placeholder on any package and needed no account — useful while the placement
 * was being judged on a phone, and useless for anything else.
 *
 * **An ad unit is bound to the package it was created for.** That is now the
 * hard half of the lock on the `applicationId`: moving the package again would
 * orphan these two strings as well as strand the database. See ARCHITECTURE.md, where
 * the two earlier moves and the reason there will not be a third are written
 * down.
 *
 * Two things travel with them. [APP_ID] must equal the `APPLICATION_ID`
 * meta-data in the manifest — the SDK reads that at process start and refuses
 * every request if the two disagree — and the Play Data Safety form has to say
 * that an advertising identifier is collected, since the SDK merges in
 * `com.google.android.gms.permission.AD_ID`. The manifest carries the same note
 * beside its own copy.
 *
 * A freshly registered unit is also allowed to answer **"Account not approved
 * yet"** (`Ad failed to load : 3`), which is not a fault here and not something
 * a code change can fix: nothing fills until the app is linked to a store
 * listing and passes AdMob's app-readiness review. See
 * support.google.com/admob/answer/9905175.
 */
object AdIds {
    /** Mirrors the `APPLICATION_ID` meta-data in the manifest. Kept for reference. */
    const val APP_ID = "ca-app-pub-4483991085473763~2654037317"
    const val INTERSTITIAL = "ca-app-pub-4483991085473763/5190223826"
}

/**
 * The one full-screen ad the app shows, and every rule about when it may appear.
 *
 * Process-scoped rather than remembered in a composition, because the two facts
 * it holds are facts about the *user* and not about a screen: an ad already
 * fetched should not be fetched again when the activity is recreated, and "one
 * of these was shown a moment ago" has to survive a rotation — otherwise turning
 * the phone sideways is a way to be shown a second one.
 *
 * Nothing here initialises the SDK on launch. [preload] is the first thing that
 * touches it, and the caller only reaches [preload] once it has decided ads are
 * allowed at all — so a phone being set up for the first time makes no ad
 * request, and neither does one still being shown the opening lessons. Nor does
 * [preload] start the SDK itself any more: [AdConsent] stands in front of it,
 * and on a phone that has refused consent the SDK is never started at all.
 *
 * [AdConfig.ENABLED] is checked in both public entry points rather than at the
 * call sites: a switch that has to be remembered at four different places is a
 * switch that will one day be forgotten at one of them.
 */
object InterstitialAds {

    private const val TAG = "InterstitialAds"

    /** Set once, guarded because the SDK must not be initialised twice. */
    private val started = AtomicBoolean(false)

    /**
     * True once the SDK has finished starting up.
     *
     * Loading before this is the mistake that made the whole thing look broken:
     * [MobileAds.initialize] returns immediately and does its real work in the
     * background — on a phone that has never served an ad it fetches and dexes
     * a Play Services module, which took eleven seconds on a cold emulator. An
     * `InterstitialAd.load` fired in the same breath has nothing to load
     * through, so the first request of a launch was always wasted. The fetch is
     * made from the initialisation callback instead, and any [preload] arriving
     * in the meantime simply returns — that callback will do it.
     */
    @Volatile
    private var ready = false

    private var ad: InterstitialAd? = null
    private var loading = false
    private var lastShownAt = 0L

    /**
     * True while one of these is actually on the screen.
     *
     * Read by [com.mywallet.MainActivity], which re-arms the screen lock on
     * `ON_STOP`: an ad covering the app fires exactly that event, and without
     * this the user would dismiss an ad the app itself chose to show and be
     * asked for their fingerprint. The app has not left their hands, so the lock
     * has no business closing behind it.
     */
    @Volatile
    var showing = false
        private set

    /**
     * Fetches one if there is nothing waiting, gathering consent and starting
     * the SDK on the way if this is the first time.
     *
     * Cheap to call repeatedly — an ad already held, or a request already in
     * flight, is left alone.
     *
     * Takes an [Activity] rather than a [Context] because [AdConsent] may have
     * to put a form on the screen, and there is nowhere to put one without it.
     * Every caller had one to hand already.
     */
    fun preload(activity: Activity) {
        if (!AdConfig.ENABLED) return
        val app = activity.applicationContext
        if (!ready) {
            // Consent first and always, even on the launches where it turns out
            // there was nothing to ask: [AdConsent.gather] is what decides that,
            // not this. It calls back at once where no form is owed, so the
            // usual phone loses nothing by going the long way round.
            AdConsent.gather(activity) { startSdk(app) }
            return
        }
        fetch(app)
    }

    /**
     * Starts the SDK, once, after [AdConsent] has said ads may be requested.
     *
     * Guarded rather than trusted to be called once: consent can report itself
     * ready twice — from the cache and again from the form — and initialising
     * the SDK twice is not allowed.
     */
    private fun startSdk(app: Context) {
        if (!started.compareAndSet(false, true)) return
        // Before the SDK starts, never after: the configuration decides what
        // the *first* request is answered with, and a phone told about it
        // afterwards has already had one real ad served to it. See
        // [AdConfig.TEST_DEVICES] for why this ships rather than being
        // stripped out.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTestDeviceIds(AdConfig.TEST_DEVICES)
                .build()
        )
        MobileAds.initialize(app) {
            ready = true
            // Only now is there anything to load through — see [ready].
            fetch(app)
        }
    }

    private fun fetch(app: Context) {
        if (ad != null || loading) return
        loading = true
        InterstitialAd.load(
            app,
            AdIds.INTERSTITIAL,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    loading = false
                    ad = loaded
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    ad = null
                    // Nothing is retried here on purpose, and nothing may be:
                    // a failed fetch waits for the next eligible trigger — the
                    // user backing out of a screen, or coming back to the app —
                    // which is a rhythm the user sets rather than a backoff loop
                    // of our own that can run away on a phone with no fill.
                    Log.w(TAG, "No ad: ${error.message}")
                }
            },
        )
    }

    /**
     * Shows the ad if there is one and enough time has passed since the last.
     *
     * Returns whether it went up, so the caller can tell "shown" from "nothing
     * happened" without reaching into the state above.
     */
    fun show(activity: Activity): Boolean {
        if (!AdConfig.ENABLED) return false
        val ready = ad ?: return false
        if (showing) return false
        if (lastShownAt != 0L &&
            SystemClock.elapsedRealtime() - lastShownAt < AdConfig.MIN_GAP_MS
        ) {
            return false
        }
        ad = null
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                showing = true
                // Counted from the moment it appears rather than from the moment
                // it is dismissed: an ad left up for two minutes is not a licence
                // to show the next one immediately afterwards.
                lastShownAt = SystemClock.elapsedRealtime()
            }

            override fun onAdDismissedFullScreenContent() {
                showing = false
                // The next one is *fetched* now, so it is ready by the time a
                // trigger comes round — which is not the same as showing one:
                // nothing appears until the user backs out of a screen or comes
                // back to the app, and not for another [AdConfig.MIN_GAP_MS]
                // even then.
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                Log.w(TAG, "Could not show: ${error.message}")
                // Fetched, not re-shown. An ad that could not go up has missed
                // its moment and waits for the next one.
                preload(activity)
            }
        }
        ready.show(activity)
        return true
    }
}
