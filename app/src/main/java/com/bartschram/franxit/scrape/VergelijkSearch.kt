package com.bartschram.franxit.scrape

import org.json.JSONObject
import java.net.URLEncoder

/** Product search on vergelijk.be, using the same endpoints as the site's search box. */
object VergelijkSearch {

    /** [image]: a photo of the product, when the search gives one. */
    data class Hit(val title: String, val url: String, val price: Double?, val image: String? = null)

    /**
     * A photo address from vergelijk.be's search, which leaves the size open: "//pc.vergelijk.be/{{size}}/1.webp".
     * The size its product pages use. Null when there is none.
     */
    internal fun photo(template: String?): String? =
        template?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.replace("{{size}}", "500x500")
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.startsWith("https://") }

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
        val hits = withoutDuplicates(suggestions + results).take(40)
        if (hits.isNotEmpty()) return hits
        // Nothing for "Rowenta RO3134EA": shops often leave the model code's variant out ("RO3134").
        for (looser in looser(query)) {
            val more = runCatching {
                parseResults(Scraper.download("$BASE/api/v1/search/results/enhanced/combined?searchText=${URLEncoder.encode(looser, "UTF-8")}&page=1", "application/json"))
            }.getOrDefault(emptyList())
            if (more.isNotEmpty()) return withoutDuplicates(more).take(40)
        }
        return emptyList()
    }

    /** A model code with a variant after its number: "RO3134EA", "HD9650/90", "KM-2000B". */
    private val VARIANT = Regex("""^(\p{L}{1,5}-?\d{3,})(?:/\w+|\p{L}{1,3})$""")

    /**
     * Looser searches for a name that found nothing, best first: its model codes without the variant
     * ("Rowenta RO3134EA" as "Rowenta RO3134"), then that code alone. Empty when the name has no such code.
     */
    internal fun looser(query: String): List<String> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val codes = words.mapNotNull { word -> VARIANT.find(word)?.groupValues?.get(1)?.let { word to it } }.toMap()
        if (codes.isEmpty()) return emptyList()
        val shorter = words.joinToString(" ") { codes[it] ?: it }
        return (listOf(shorter) + codes.values).distinct().filter { it.isNotBlank() && it != query.trim() }
    }

    /**
     * The same product once: by its id, and by its title when only the writing differs
     * (capitals, dashes, spaces). The first (the more precise suggestion) stays. Titles that differ
     * in any word, colour or size are different products and all stay.
     */
    internal fun withoutDuplicates(hits: List<Hit>): List<Hit> =
        hits.distinctBy { productId(it.url) ?: it.url }
            .distinctBy { hit -> hit.title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { hit.url } }

    fun parseSuggestions(json: String): List<Hit> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("suggestionType", "PRODUCT_SUGGESTION") == "PRODUCT_SUGGESTION" }
            .mapNotNull { item ->
                val uri = item.optString("uri").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                Hit(item.optString("title").trim(), BASE + uri.trimEnd('/') + "/", price(item.opt("cheapestPrice")), photo(item.optString("imageUrl")))
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
            Hit(
                summary.optString("name").trim(), "$BASE/${parts.joinToString("/")}/", price(summary.opt("cheapestPriceIncludingDeals")),
                photo(summary.optJSONArray("imageBasenames")?.optString(0)),
            )
        }
    }

    private fun price(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it > 0 }

    /** vergelijk.be product urls end in the product id: …_784877634/ */
    private fun productId(url: String): String? = Regex("""_(\d{6,})/?$""").find(url.substringBefore('?'))?.groupValues?.get(1)
}
