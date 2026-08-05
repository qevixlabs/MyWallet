package com.mywallet.ads

/**
 * Every number the one interstitial obeys, and the switch that turns it off.
 *
 * Kept apart from [InterstitialAds] so the rules can be read — and changed —
 * without reading the SDK plumbing underneath them. Nothing here is a
 * preference: these are the app's own manners, not something a user is asked
 * about.
 */
object AdConfig {

    /**
     * The master switch, and it is **on**.
     *
     * It was false through the run in which the placement was written, on this
     * phone and on any other: an ad arriving in the middle of a walk through a
     * form is a lost minute for whoever is doing the walking, and every
     * screenshot taken with one on it has to be taken again. Turn it back to
     * false for any session like that — nothing else changes with it, every rule
     * below is enforced either way, and with it false no request is ever made,
     * so the SDK is not even started up.
     *
     * **What it does not switch off is the risk of tapping your own ads.** The
     * ids are real now (see [AdIds]), so an ad served to a phone this repo is
     * being developed on is a real impression, and a tap on it is invalid
     * traffic — which is what AdMob suspends accounts for. A device used for
     * development belongs on the test-device list before it is used to try this
     * out; see developers.google.com/admob/android/test-ads.
     */
    const val ENABLED = true

    /**
     * The phones that are served a "Test Ad" placeholder instead of the real
     * thing, whatever [ENABLED] says.
     *
     * This is not a debugging convenience, it is the account's safety: the ids
     * in [AdIds] are real, so an ad shown on a phone this app is *developed* on
     * is a real impression and a tap on it is invalid traffic — which is what
     * AdMob suspends publishers for. The developer's own phone runs the shipped
     * build, installed from the same release the updater serves, so there is no
     * build type to hide this behind and nothing to remove before shipping: the
     * id stays in, for good, and costs everybody else nothing.
     *
     * An entry is the uppercase MD5 of that phone's advertising id, and the SDK
     * prints the exact line to add the first time an unrecognised device asks
     * for an ad:
     *
     *     I/Ads: Use RequestConfiguration.Builder().setTestDeviceIds(
     *              Arrays.asList("DEC2EE07…")) to get test ads on this device.
     *
     * Emulators need no entry — Google registers them as test devices already.
     */
    val TEST_DEVICES = listOf(
        // Niraj's OPPO CPH2493, which the release build is tried on.
        "DEC2EE07F35F787B1BC5B2B4E4C5172D",
    )

    /**
     * The shortest gap between two of these, whatever the user does in between.
     *
     * The triggers recur — somebody moving in and out of their own accounts
     * would meet an ad, dismiss it, come back out and meet another. That is the
     * behaviour AdMob polices and the behaviour that gets an app deleted.
     */
    const val MIN_GAP_MS = 45_000L

    /**
     * How long after coming back to the app one may be considered.
     *
     * Never in the frame the app returns in: whoever has just reopened it is
     * looking for a figure, and a full-screen ad over the page they are opening
     * reads as the app having been replaced rather than as an ad. The pause is
     * what makes it something that arrives *while* they are here.
     */
    const val AFTER_RESUME_MS = 2_000L

    /**
     * How long after backing out of a screen one may be considered.
     *
     * A breath rather than a wait. Coming back out of a form is the moment the
     * user has finished something, which is the honest place for this — but the
     * page they came back to has to have drawn first, or the ad covers a screen
     * they never saw.
     */
    const val AFTER_BACK_MS = 400L
}
