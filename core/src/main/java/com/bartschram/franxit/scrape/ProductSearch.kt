package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.ShopCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Finding products on the web for what you type: words ("PlayStation 5") or a question ("cheap wireless headphones").
 * The search runs in Franxit's search service (the Worker at [GoogleShopping.PROXY]), which asks Exa and holds its key;
 * the app only knows the service's own format, so the provider behind it can change without an app update.
 *
 * Only what the source gives is kept: a product without a price, photo or shop stays without one. The app reads a
 * price from the product's page itself ([com.bartschram.franxit.data.Repository.verifiedPrice]), never from here.
 */
object ProductSearch {

    /** What the app asks: the words, and how many results at most. */
    data class Request(val query: String, val limit: Int = MAX_RESULTS) {
        fun toJson(): String = JSONObject().put("query", query).put("limit", limit).toString()
    }

    /** One product page found. Every field but [title] and [url] may be missing. */
    data class Product(
        val title: String,
        val url: String,
        val description: String? = null,
        val imageUrl: String? = null,
        val price: Double? = null,
        val currency: String? = null,
        /** The shop, as its site ("coolblue.be"), from the page's address. */
        val retailer: String? = null,
        val availability: String? = null,
        val score: Double? = null,
    )

    data class Response(val products: List<Product>)

    /** The service's daily limit was reached (or the provider's credits): not a connection problem. */
    class LimitReached : IOException(LIMIT)

    const val MAX_RESULTS = 20
    const val MAX_QUERY = 150
    const val LIMIT = "Too many searches today. Try again tomorrow."
    const val FAILED = "Search failed. Check your connection."

    /** A query worth sending: two characters or more, not longer than the service takes. Null otherwise. */
    fun clean(query: String): String? = query.replace(Regex("\\s+"), " ").trim().takeIf { it.length in 2..MAX_QUERY }

    /** The service's answer, read leniently: a product without a title or a web address is left out. */
    fun parse(json: String): Response {
        val list = JSONObject(json).optJSONArray("products") ?: JSONArray()
        val seen = HashSet<String>()
        val products = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val url = o.text("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val title = o.text("title") ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            Product(
                title = title,
                url = url,
                description = o.text("description"),
                imageUrl = o.text("imageUrl")?.takeIf { it.startsWith("https://") },
                price = o.optDouble("price").takeIf { !it.isNaN() && it > 0 },
                currency = o.text("currency"),
                retailer = o.text("retailer"),
                availability = o.text("availability"),
                score = o.optDouble("score").takeIf { !it.isNaN() },
            )
        }
        return Response(products)
    }

    /**
     * The product pages among [products]: not a search, a category, a brand's page or the shop's help pages (the
     * same rules as for a shop's link elsewhere in the app). Only a product's page is ever opened or tracked.
     */
    fun productPages(products: List<Product>): List<Product> = products.filter { p ->
        ShopCheck.isProductPage(p.url) && !ShopCheck.isElsewhere(p.url, p.title) && hasPath(p.url)
    }

    /** A shop's home page ("https://www.coolblue.be/") is no product. */
    private fun hasPath(url: String): Boolean =
        runCatching { URI(url).path }.getOrNull().orEmpty().trim('/').isNotEmpty()

    /** Products for [query], product pages only, best first as the service ranks them. */
    fun search(query: String): List<Product> {
        val q = clean(query) ?: return emptyList()
        return productPages(parse(post("/api/product-search", Request(q).toJson())).products)
    }

    /** Search words for what you typed so far ("play" → "playstation 5"): words to search, never products. */
    fun suggest(prefix: String): List<String> {
        val q = clean(prefix) ?: return emptyList()
        return parseSuggestions(get("/api/suggest?q=" + URLEncoder.encode(q, "UTF-8")), q)
    }

    /** The suggestions in the service's answer, without the words you typed yourself. */
    fun parseSuggestions(json: String, typed: String): List<String> {
        val list = JSONObject(json).optJSONArray("suggestions") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            .filter { !it.equals(typed.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 6

    private fun get(path: String): String = request(path, null)

    private fun post(path: String, body: String): String = request(path, body)

    private fun request(path: String, body: String?): String {
        val conn = java.net.URL(GoogleShopping.PROXY + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            CoreConfig.appToken.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Franxit-Token", it) }
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 429) throw LimitReached()
            if (code != 200) throw IOException(FAILED)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: LimitReached) {
            throw e
        } catch (e: IOException) {
            throw IOException(FAILED, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}
