package com.mywallet.data.update

/**
 * A newer release than the one installed.
 *
 * Lives in `main` rather than beside the checker that produces it, because
 * Settings — which is in `main` — names the type. There are two checkers, one
 * per distribution: the GitHub build's fetches releases and hands them to the
 * system installer, and the Play build's answers "nothing" and can do nothing
 * else. See `UpdateChecker` in `src/github` and `src/play`.
 */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notes: String?,
)
