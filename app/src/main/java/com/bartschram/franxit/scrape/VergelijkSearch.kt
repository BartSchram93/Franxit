package com.bartschram.franxit.scrape

import org.json.JSONObject
import java.net.URLEncoder

/** Product search on vergelijk.be, using the same endpoints as the site's search box. */
object VergelijkSearch {

    data class Hit(val title: String, val url: String, val price: Double?)

    private const val BASE = "https://www.vergelijk.be"

    fun search(query: String): List<Hit> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        // Suggestions are few but precise; full results add more (often accessories) below them.
        val suggestions = runCatching {
            parseSuggestions(Scraper.download("$BASE/api/v1/search/suggestions/products?searchText=$q", "application/json"))
        }.getOrDefault(emptyList())
        val results = runCatching {
            parseResults(Scraper.download("$BASE/api/v1/search/results/enhanced/combined?searchText=$q&page=1", "application/json"))
        }.getOrDefault(emptyList())
        return (suggestions + results).distinctBy { productId(it.url) ?: it.url }.take(40)
    }

    fun parseSuggestions(json: String): List<Hit> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("suggestionType", "PRODUCT_SUGGESTION") == "PRODUCT_SUGGESTION" }
            .mapNotNull { item ->
                val uri = item.optString("uri").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                Hit(item.optString("title").trim(), BASE + uri.trimEnd('/') + "/", price(item.opt("cheapestPrice")))
            }
    }

    fun parseResults(json: String): List<Hit> {
        val items = JSONObject(json).optJSONObject("searchResult")?.optJSONObject("result")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val summary = items.optJSONObject(i)?.optJSONObject("productOfferSummary")?.optJSONObject("productSummary")
                ?: return@mapNotNull null
            val path = summary.optJSONObject("path") ?: return@mapNotNull null
            val parts = listOf("categoryUrl", "brandUrl", "productUrl").map { path.optString(it) }
            if (parts.any { it.isBlank() }) return@mapNotNull null
            Hit(summary.optString("name").trim(), "$BASE/${parts.joinToString("/")}/", price(summary.opt("cheapestPriceIncludingDeals")))
        }
    }

    private fun price(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it > 0 }

    /** vergelijk.be product urls end in the product id: …_784877634/ */
    private fun productId(url: String): String? = Regex("""_(\d{6,})/?$""").find(url.substringBefore('?'))?.groupValues?.get(1)
}
