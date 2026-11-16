package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * An anonymous count of how many phones use Franxit each day. Once a day, when the app is opened, it
 * sends "one more phone today" with the app version, and nothing else: no number that identifies
 * the phone, no name, no account, never products, prices, searches or keys. The maker's overview
 * just adds them up. The phone only remembers the date of its last count (to count once a day).
 * Can be switched off in ⋮ → Usage statistics.
 */
object Usage {

    /** Where the counts go: the maker's own small Google Sheets script. Empty: nothing is sent. */
    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbyQjbFdhUHwOfemUyckpmQuvwP-sKAa4ilCEfAoE_m5TMEE-ub9OD0uTi5jauLNdTuq/exec"

    private const val PREFS = "usage"

    /** Whether this build counts at all (the endpoint is filled in). */
    val configured: Boolean get() = ENDPOINT.isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On unless switched off; someone who said no to the earlier question stays on no. */
    fun enabled(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean("share", prefs.getBoolean("consent", true))
    }

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean("share", on) }

    /** Counted once a day: not again on the same date. */
    internal fun shouldReport(lastDay: String?, today: String): Boolean = lastDay != today

    internal fun payload(version: String): String = JSONObject().put("version", version).toString()

    /** What the earlier version sent for a deletion: only the old number. */
    internal fun deletePayload(id: String): String = JSONObject().put("id", id).put("delete", true).toString()

    /**
     * Counts this phone for today, once a day (call off the main thread). Does nothing when switched
     * off or not configured; a failed send is tried again at the next opening.
     */
    fun reportToday(context: Context) {
        if (!configured) return
        removeOldNumber(context)
        if (!enabled(context)) return
        val prefs = prefs(context)
        val today = LocalDate.now().toString()
        synchronized(this) {
            if (!shouldReport(prefs.getString("last_day", null), today)) return
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            if (send(payload(version))) prefs.edit { putString("last_day", today) }
        }
    }

    /** Versions 1.0.11 to 1.0.13 made a number for this phone: deleted here and at the maker's. */
    private fun removeOldNumber(context: Context) {
        val prefs = prefs(context)
        val id = prefs.getString("id", null) ?: return
        if (prefs.getBoolean("consent", false)) send(deletePayload(id))
        prefs.edit { remove("id"); remove("opens"); remove("last_open"); remove("bol_clicks") }
    }

    private fun send(body: String): Boolean = runCatching {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..399
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
