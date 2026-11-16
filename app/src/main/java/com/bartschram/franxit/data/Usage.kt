package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Anonymous usage counts, only for people who say yes (asked once, on first start; Franxit works the
 * same either way). What is sent: a random number made on this phone (no name, no account), the app
 * version, how many times the app was opened and how often "Buy at Bol.com" was tapped. Never
 * products, prices, searches or keys. In ⋮ → Usage statistics it can be switched off, which also
 * deletes the number at the maker, or deleted and started afresh.
 */
object Usage {

    /** Where the counts go: the maker's own small Google Sheets script. Empty: nothing is sent. */
    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbyVwPdgF_xmoCbOsggmm5ujh5f3E8x-bMjz7XBG-aFddF2nJZwplQpO8cCTleI-tsJB/exec"

    private const val PREFS = "usage"
    /** Coming back to the app within this time isn't a new use. */
    private const val SAME_USE_MS = 30 * 60 * 1000L

    /** Whether this build counts at all (the endpoint is filled in). */
    val configured: Boolean get() = ENDPOINT.isNotBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The person's answer: true = yes, false = no, null = not asked yet (then nothing is sent). */
    fun consent(context: Context): Boolean? =
        prefs(context).takeIf { it.contains("consent") }?.getBoolean("consent", false)

    /** Saves the answer. A no (or switching off) also deletes this phone's number at the maker. */
    fun setConsent(context: Context, on: Boolean) {
        if (!on) deleteMine(context)
        prefs(context).edit { putBoolean("consent", on) }
    }

    /** Deletes this phone's number at the maker and starts afresh (a new number when counting again). */
    fun deleteMine(context: Context) {
        val prefs = prefs(context)
        val id = synchronized(this) {
            prefs.getString("id", null).also {
                prefs.edit { remove("id"); remove("opens"); remove("bol_clicks"); remove("last_open") }
            }
        }
        if (configured && id != null) Thread { send(deletePayload(id)) }.start()
    }

    /** A new use: not again within 30 minutes of the last one. */
    internal fun isNewUse(lastOpenAt: Long, now: Long): Boolean = now - lastOpenAt >= SAME_USE_MS

    internal fun payload(id: String, version: String, opens: Int, bolClicks: Int): String =
        JSONObject().put("id", id).put("version", version).put("opens", opens).put("bol_clicks", bolClicks).toString()

    internal fun deletePayload(id: String): String = JSONObject().put("id", id).put("delete", true).toString()

    /** One tap on "Buy at Bol.com": only that it happened, never which product. */
    fun boughtAtBol(context: Context) {
        if (!configured || consent(context) != true) return
        val prefs = prefs(context)
        synchronized(this) { prefs.edit { putInt("bol_clicks", prefs.getInt("bol_clicks", 0) + 1) } }
    }

    /**
     * Counts one opening of the app and tells the maker the new totals (off the main thread).
     * Does nothing without a yes or when not configured; a failed send is simply sent with the next one.
     */
    fun opened(context: Context) {
        if (!configured || consent(context) != true) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!isNewUse(prefs.getLong("last_open", 0L), now)) return
            val opens = prefs.getInt("opens", 0) + 1
            val id = prefs.getString("id", null) ?: UUID.randomUUID().toString()
            prefs.edit { putLong("last_open", now); putInt("opens", opens); putString("id", id) }
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            send(payload(id, version, opens, prefs.getInt("bol_clicks", 0)))
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
