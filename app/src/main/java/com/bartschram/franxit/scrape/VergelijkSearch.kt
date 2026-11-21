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
        if (query.isBlank()) return emptyList()
        val hits = once(query)
        if (fits(hits, query)) return hits
        // Nothing that looks like it: "Dreame-Aqua 10 Ultrarol Blanc" (from a price tag) only finds bed covers in
        // "blanc", "Aqua 10 Ultrarol Blanc" finds the robot. At most a few looser searches, only when needed.
        for (looser in looser(query)) {
            val more = once(looser)
            if (fits(more, looser)) return withoutDuplicates(more + hits).take(40)
        }
        return hits
    }

    private fun once(query: String): List<Hit> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()
        // Suggestions are few but precise; full results add more (often accessories) below them.
        val suggestions = runCatching {
            parseSuggestions(Scraper.download("$BASE/api/v1/search/suggestions/products?searchText=$q", "application/json"))
        }.getOrDefault(emptyList())
        val results = runCatching {
            parseResults(Scraper.download("$BASE/api/v1/search/results/enhanced/combined?searchText=$q&page=1", "application/json"))
        }.getOrDefault(emptyList())
        return withoutDuplicates(suggestions + results).take(40)
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun isCode(word: String) = word.length >= 4 && word.any { it.isLetter() } && word.any { it.isDigit() }

    /**
     * Whether a search found something like [query]: one of the first hits shares its model code ("RO3134"), or
     * at least two of its words and at least half of them ("Galaxy S24" for "Samsung Galaxy S24"); a name of one
     * word, that word. Not one shared colour ("Blanc"), nor two chance words of a long name (gloves "Aqua … 10"
     * for "Dreame Aqua 10 Ultrarol Blanc").
     */
    internal fun fits(hits: List<Hit>, query: String): Boolean {
        val wanted = words(query).toSet()
        if (wanted.isEmpty()) return hits.isNotEmpty()
        return hits.take(5).any { hit ->
            val shared = words(hit.title).filter { it in wanted }.toSet()
            shared.any(::isCode) || (shared.size >= minOf(2, wanted.size) && shared.size * 2 >= wanted.size)
        }
    }

    /** A model code with a variant after its number: "RO3134EA", "HD9650/90", "KM-2000B". */
    private val VARIANT = Regex("""^(\p{L}{1,5}-?\d{3,})(?:/\w+|\p{L}{1,3})$""")

    /**
     * Looser searches for a name that found nothing like it, best first, at most three: its model codes without
     * the variant ("Rowenta RO3134EA" as "Rowenta RO3134", shops often leave it out), the brand with the words up
     * to the first number ("Dreame Aqua 10"), the name without its brand ("Aqua 10 Ultrarol Blanc"), the code alone.
     */
    internal fun looser(query: String): List<String> {
        // "DREAME-AQUA" as read on a tag: a dash between two words of letters parts them.
        val words = query.trim().replace(Regex("""(?<=\p{L})[-–](?=\p{L}{3})"""), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() && it != "-" && it != "–" }
        val codes = words.mapNotNull { word -> VARIANT.find(word)?.groupValues?.get(1)?.let { word to it } }.toMap()
        val found = mutableListOf<String>()
        if (codes.isNotEmpty()) found += words.joinToString(" ") { codes[it] ?: it }
        val number = words.indexOfFirst { word -> word.any { it.isDigit() } }
        if (number in 2 until words.size - 1) found += words.take(number + 1).joinToString(" ")
        if (words.size >= 3) found += words.drop(1).joinToString(" ")
        found += codes.values
        return found.distinct().filter { it.isNotBlank() && it != query.trim() }.take(3)
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
