package com.mywallet.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mywallet.BuildConfig
import com.mywallet.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub Releases for a newer build and installs it.
 *
 * **This is the GitHub distribution's copy, and it exists nowhere else.** The
 * Play build compiles the stub in `src/play` instead, because an app on Play may
 * not update itself: "An app distributed via Google Play may not modify,
 * replace, or update itself using any method other than Google Play's update
 * mechanism" (Device and Network Abuse). The permission and the FileProvider
 * this needs are in this flavour's own manifest for the same reason — a
 * permission declared in `main` would ship in both.
 *
 * Deliberately anonymous: the releases are public, so no token is embedded in
 * the APK. An APK is a zip — anything secret inside it is not secret.
 *
 * Safety does not rest on this code. Android only accepts an update signed with
 * the same key as the installed app, so a substituted or tampered APK fails to
 * install rather than silently replacing the user's money app. The install
 * itself always goes through the system installer, which asks the user.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the newest release when it is newer than what is installed, or
     * null when up to date.
     *
     * Read from a small file published beside the APK, **not** from the GitHub
     * API. The API allows an anonymous caller sixty requests an hour per IP
     * address, shared with everything else on the same network — and the app has
     * to be anonymous, because a token inside an APK is not a secret. A few
     * releases cut from home in one afternoon were enough to exhaust it, and
     * every phone on that connection then got HTTP 403 instead of an update.
     *
     * `releases/latest/download/…` is an ordinary redirect on github.com and is
     * not metered that way. It also always points at the newest release, so
     * nothing here has to sort tags or page through a list.
     *
     * The API is still tried if that file is missing — a release cut by hand
     * would not have one — but it is the fallback now rather than the path.
     */
    suspend fun check(): Result<AvailableUpdate?> = withContext(io) {
        runCatching {
            val newest = runCatching { fromLatestJson() }.getOrNull() ?: fromReleasesApi()
            newest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /** The published manifest: one request, no quota, always the newest release. */
    private fun fromLatestJson(): AvailableUpdate? {
        val body = httpGet(LATEST_JSON_URL)
        val manifest = json.parseToJsonElement(body).jsonObject
        val code = manifest["versionCode"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        return AvailableUpdate(
            versionName = manifest["versionName"]?.jsonPrimitive?.content ?: code.toString(),
            versionCode = code,
            downloadUrl = LATEST_APK_URL,
            sizeBytes = 0L,
            notes = manifest["notes"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The old path, kept for a release published without a manifest.
     *
     * Versions are compared by the numeric versionCode parsed from the tag, not
     * by string ordering — "v0.10.0" must beat "v0.9.0".
     */
    private fun fromReleasesApi(): AvailableUpdate? {
        val body = httpGet(RELEASES_URL)
        return json.parseToJsonElement(body).jsonArray
            .asSequence()
            .map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.content != "true" }
            .filter { it["prerelease"]?.jsonPrimitive?.content != "true" }
            .mapNotNull { release ->
                val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val code = versionCodeFrom(tag) ?: return@mapNotNull null
                val asset = release["assets"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                    ?: return@mapNotNull null
                AvailableUpdate(
                    versionName = tag.removePrefix("v"),
                    versionCode = code,
                    downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null,
                    sizeBytes = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    notes = release["body"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                )
            }
            .maxByOrNull { it.versionCode }
    }

    /** Downloads the APK into cache and hands it to the system installer. */
    suspend fun downloadAndInstall(update: AvailableUpdate): Result<Unit> = withContext(io) {
        runCatching {
            val target = File(context.cacheDir, "updates").apply { mkdirs() }
                .resolve("mywallet-${update.versionCode}.apk")

            // Written to a temp file and renamed, so an interrupted download can
            // never leave a truncated APK that the installer would reject.
            val partial = File(target.parentFile, target.name + ".part")
            URL(update.downloadUrl).openStream().use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (partial.length() <= 0L) throw IOException("Downloaded file was empty")
            partial.renameTo(target)

            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", target,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Tags are `v<versionName>+<versionCode>` when the release step writes them,
     * falling back to deriving a comparable number from a plain semver tag.
     */
    private fun versionCodeFrom(tag: String): Long? {
        tag.substringAfter('+', "").toLongOrNull()?.let { return it }
        val parts = tag.removePrefix("v").substringBefore('+').split('.')
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10_000L + minor * 100L + patch
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MyWallet/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // Said in words, because "HTTP 403" sent the user looking for a
                // broken link when nothing was broken: GitHub's anonymous limit
                // is per IP address and resets on the hour.
                throw IOException(
                    when (code) {
                        403, 429 -> "GitHub is rate limiting this network. Try again in an hour."
                        404 -> "No release found to update to."
                        else -> "HTTP $code from $url"
                    }
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val LATEST = "https://github.com/qevixlabs/MyWallet/releases/latest/download"
        const val LATEST_JSON_URL = "$LATEST/latest.json"
        const val LATEST_APK_URL = "$LATEST/MyWallet.apk"
        const val RELEASES_URL =
            "https://api.github.com/repos/qevixlabs/MyWallet/releases?per_page=10"
    }
}
