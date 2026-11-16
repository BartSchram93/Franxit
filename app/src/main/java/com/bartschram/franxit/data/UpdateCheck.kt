package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import com.bartschram.franxit.scrape.Scraper
import org.json.JSONObject
import java.time.LocalDate

/**
 * Says when a newer Franxit is out, since the app isn't in a store: once a day it asks GitHub
 * (the public download page's latest release) which version that is. Nothing is sent but that
 * request, and nothing is installed by itself: the banner opens the download page.
 */
object UpdateCheck {

    private const val LATEST = "https://api.github.com/repos/BartSchram93/franxit-download/releases/latest"
    const val DOWNLOAD_PAGE = "https://franxit.be/"
    const val PRIVACY_PAGE = DOWNLOAD_PAGE + "privacy.html"
    private const val PREFS = "update_check"

    /** The build number in a release tag: "v1.0.12" is 12. Null for anything else. */
    internal fun buildOf(version: String?): Int? =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").find(version.orEmpty().trim())?.groupValues?.get(3)?.toIntOrNull()

    /** A release: its build number and the first line of its text, the short "what's new". */
    internal class Release(val build: Int, val notes: String?)

    internal fun parseRelease(json: String): Release? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val build = buildOf(release.optString("tag_name")) ?: return null
        val notes = release.optString("body").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(140)
        return Release(build, notes)
    }

    /** The latest release's build number, from GitHub's answer. */
    internal fun latestBuild(json: String): Int? = parseRelease(json)?.build

    internal fun isNewer(latest: Int?, installedVersion: String?): Boolean {
        val installed = buildOf(installedVersion) ?: return false
        return latest != null && latest > installed
    }

    /** Asks GitHub at most once a day; remembers the answer so the banner shows without waiting. */
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("checked", null) == today) return
        val release = runCatching { parseRelease(Scraper.download(LATEST, "application/vnd.github+json")) }.getOrNull() ?: return
        prefs.edit { putString("checked", today); putInt("latest", release.build); putString("notes", release.notes.orEmpty()) }
    }

    /** What's new in the latest version, in a short sentence; null when it says nothing. */
    fun notes(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("notes", null)?.takeIf { it.isNotBlank() }

    /** A newer version is out and the person wasn't told yet (by the banner or a notification). */
    fun shouldNotify(context: Context): Boolean =
        available(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { it.getInt("latest", 0) > it.getInt("told", 0) }

    /** Remembers that the person knows about this version: it's not announced again. */
    fun markTold(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putInt("told", prefs.getInt("latest", 0)) }
    }

    /** Whether the last answer from GitHub is newer than the installed version. */
    fun available(context: Context): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val latest = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("latest", 0).takeIf { it > 0 }
        return isNewer(latest, installed)
    }
}
