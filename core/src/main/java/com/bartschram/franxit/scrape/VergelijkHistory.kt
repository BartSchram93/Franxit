package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.SitePoint
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/**
 * vergelijk.be's own daily price history (about a year), the data behind the
 * "Prijsontwikkeling" chart on its product pages.
 */
object VergelijkHistory {

    private val BRUSSELS = ZoneId.of("Europe/Brussels")

    /** The history API url for a vergelijk.be product page, or null for other sites. */
    fun historyUrl(productUrl: String): String? {
        val uri = runCatching { URI(productUrl) }.getOrNull() ?: return null
        if (uri.host?.endsWith("vergelijk.be") != true) return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://www.vergelijk.be/api/v1/product/history" +
            "?category=${enc(parts[0])}&brand=${enc(parts[1])}&product=${enc(parts[2])}"
    }

    /** Null when the site has no history for this url. */
    fun fetch(productUrl: String): List<SitePoint>? {
        val url = historyUrl(productUrl) ?: return null
        return parse(Scraper.download(url, "application/json"))
    }

    fun parse(json: String): List<SitePoint> {
        val series = JSONObject(json).optJSONArray("priceHistoryData") ?: return emptyList()
        val all = (0 until series.length()).mapNotNull { series.optJSONObject(it) }
        // "min" is the cheapest price per day, which is what the site's chart shows.
        val chosen = all.firstOrNull { it.optString("productHistoryMetricLabel") == "min" } ?: all.firstOrNull()
        val items = chosen?.optJSONArray("productHistoryItems") ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { items.optJSONObject(it) }
            .mapNotNull { item ->
                val price = item.optDouble("price", Double.NaN)
                if (price.isNaN() || price <= 0 || !item.has("date")) return@mapNotNull null
                SitePoint(Instant.ofEpochMilli(item.getLong("date")).atZone(BRUSSELS).toLocalDate(), price)
            }
            .associateBy { it.day }  // one point per day, last wins
            .values
            .sortedBy { it.day }
    }
}
