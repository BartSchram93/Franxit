package com.bartschram.franxit.scrape

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What product a photo shows, when it is no price tag: Google Cloud Vision's web detection, asked through the app's
 * search service (the Cloudflare Worker, which holds the key). Only when you choose so: the photo leaves the phone.
 */
object ProductPhoto {

    /** Shown when the day's photos are used up: the service keeps within Vision's free use. */
    const val TOO_MANY = "Too many photos recognised today. Try again tomorrow, or type the name."

    /** The product's name for [jpeg]; null when Vision saw nothing useful. Throws when the service can't be reached. */
    fun recognize(jpeg: ByteArray): String? {
        val conn = URL(GoogleShopping.PROXY + "/recognize").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "image/jpeg")
            com.bartschram.franxit.BuildConfig.APP_TOKEN.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Franxit-Token", it) }
            conn.outputStream.use { it.write(jpeg) }
            val code = conn.responseCode
            if (code == 429) throw IOException(TOO_MANY)
            if (code !in 200..299) throw IOException("HTTP $code")
            return name(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The name to search for, from the service's answer ({"guess", "entities", "titles"}): the title of a page with
     * the same photo when it holds Vision's best guess ("Dreame Aqua 10 Ultra Roller | Coolblue" for "dreame aqua 10
     * ultra roller"), else that guess, else the thing it names most surely. Null when nothing says enough.
     */
    internal fun name(json: String): String? {
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val guess = data.optString("guess").trim()
        val guessWords = words(guess)
        val titles = data.optJSONArray("titles")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
        if (guessWords.isNotEmpty()) {
            titles.map(::firstPart)
                .firstOrNull { part -> words(part).containsAll(guessWords) && part.split(Regex("\\s+")).size <= 10 }
                ?.let { return it }
        }
        if (guessWords.size >= 2) return tidy(guess)
        val entities = data.optJSONArray("entities")
        val best = entities?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }
            ?.filter { it.optDouble("score", 0.0) >= 0.5 }
            ?.map { it.optString("name").trim() }
            ?.firstOrNull { it.count(Char::isLetter) >= 3 }
        return best ?: guess.takeIf { it.count(Char::isLetter) >= 3 }?.let(::tidy)
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /** A page title without the shop's name after it: "Dreame Aqua 10 Ultra Roller | Coolblue" as its first part. */
    private fun firstPart(title: String) = title.split(Regex("""\s+[|–—]\s+|\s+-\s+""")).first().trim()

    /** Vision's lowercase guess as typed: "dreame aqua 10 ultra roller" as "Dreame Aqua 10 Ultra Roller", "ro3134ea" as "RO3134EA". */
    private fun tidy(guess: String) = guess.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
        when {
            word.any { it.isDigit() } && word.any { it.isLetter() } -> word.uppercase()
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }
}
