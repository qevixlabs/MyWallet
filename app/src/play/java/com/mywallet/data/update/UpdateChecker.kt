package com.mywallet.data.update

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Play build's updater, which does not update anything.
 *
 * Same name and same two methods as the GitHub build's in `src/github`, so
 * Settings — which is in `main` and knows about neither — compiles against
 * whichever one this variant carries. Only one is ever compiled.
 *
 * **It is a stub because Play forbids the real thing**: "An app distributed via
 * Google Play may not modify, replace, or update itself using any method other
 * than Google Play's update mechanism" (Device and Network Abuse policy), and
 * an app may not download executable code from anywhere but Play. A copy
 * installed from Play is updated by Play, which is also why Settings withholds
 * the whole *About* offer here — see `BuildConfig.SELF_UPDATES`. Nothing calls
 * these; they exist so that one screen can be written once.
 *
 * Deliberately a stub rather than a version of the real one with the network
 * call removed: what must not ship is the *code that downloads and installs an
 * APK*, and the only way to be sure of that is for it to be in a source set this
 * variant never compiles. There is no permission and no FileProvider here
 * either — both are declared in the GitHub flavour's own manifest.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    /** Always "nothing newer": this build is updated by the Play Store. */
    suspend fun check(): Result<AvailableUpdate?> = Result.success(null)

    /** Unreachable — [check] never yields anything to install. */
    suspend fun downloadAndInstall(update: AvailableUpdate): Result<Unit> =
        Result.failure(UnsupportedOperationException("Updates come from Google Play"))
}
