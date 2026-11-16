package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Anonymous usage counts, so the maker knows how many people use the app and how often. All that is
 * sent: a random number made on this phone (no name, no account, nothing that says who you are),
 * the app version and how many times the app was opened. Never products, prices, searches or keys.
 * The app says so on first start, and it can be switched off in ⋮ → Usage statistics.
 */
object Usage {

    /** Where the counts go: the maker's own small Google Sheets script. Empty: nothing is sent. */
    private const val ENDPOINT = ""

    private const val PREFS = "usage"
    /** Coming back to the app within this time isn't a new use. */
    private const val SAME_USE_MS = 30 * 60 * 1000L

    /** Whether this build counts at all (the endpoint is filled in). */
    val configured: Boolean get() = ENDPOINT.isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean("enabled", on) }

    /** Whether the one-time explanation was shown. */
    fun noticeSeen(context: Context): Boolean = prefs(context).getBoolean("notice_seen", false)

    fun setNoticeSeen(context: Context) = prefs(context).edit { putBoolean("notice_seen", true) }

    /** A new use: not again within 30 minutes of the last one. */
    internal fun isNewUse(lastOpenAt: Long, now: Long): Boolean = now - lastOpenAt >= SAME_USE_MS

    internal fun payload(id: String, version: String, opens: Int): String =
        JSONObject().put("id", id).put("version", version).put("opens", opens).toString()

    /**
     * Counts one opening of the app and tells the maker the new total (off the main thread).
     * Does nothing when switched off or not configured; a failed send is simply sent with the next one.
     */
    fun opened(context: Context) {
        if (!configured || !enabled(context)) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!isNewUse(prefs.getLong("last_open", 0L), now)) return
            val opens = prefs.getInt("opens", 0) + 1
            val id = prefs.getString("id", null) ?: UUID.randomUUID().toString()
            prefs.edit { putLong("last_open", now); putInt("opens", opens); putString("id", id) }
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            send(payload(id, version, opens))
        }
    }

    private fun send(body: String) {
        runCatching {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
