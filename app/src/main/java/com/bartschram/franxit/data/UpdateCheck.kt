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
    const val DOWNLOAD_PAGE = "https://bartschram93.github.io/franxit-download/"
    private const val PREFS = "update_check"

    /** The build number in a release tag: "v1.0.12" is 12. Null for anything else. */
    internal fun buildOf(version: String?): Int? =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").find(version.orEmpty().trim())?.groupValues?.get(3)?.toIntOrNull()

    /** The latest release's build number, from GitHub's answer. */
    internal fun latestBuild(json: String): Int? = buildOf(runCatching { JSONObject(json).optString("tag_name") }.getOrNull())

    internal fun isNewer(latest: Int?, installedVersion: String?): Boolean {
        val installed = buildOf(installedVersion) ?: return false
        return latest != null && latest > installed
    }

    /** Asks GitHub at most once a day; remembers the answer so the banner shows without waiting. */
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("checked", null) == today) return
        val latest = runCatching { latestBuild(Scraper.download(LATEST, "application/vnd.github+json")) }.getOrNull() ?: return
        prefs.edit { putString("checked", today); putInt("latest", latest) }
    }

    /** Whether the last answer from GitHub is newer than the installed version. */
    fun available(context: Context): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val latest = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("latest", 0).takeIf { it > 0 }
        return isNewer(latest, installed)
    }
}
