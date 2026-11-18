package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.Shops
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the schema.org Product data (JSON-LD) that comparison sites such as vergelijk.be embed
 * in their product pages, and returns every offer on it.
 */
object Scraper {

    /** [inStock] is null when the page doesn't say. [link] goes straight to the shop, when known. */
    data class Offer(val seller: String, val price: Double, val inStock: Boolean? = null, val link: String? = null)

    data class Result(val name: String, val offers: List<Offer>, val image: String? = null) {
        /** Cheapest offer you can actually buy, falling back to the cheapest one listed. */
        val cheapest: Offer
            get() = offers.filter { it.inStock != false }.minByOrNull { it.price } ?: offers.minBy { it.price }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0 Mobile Safari/537.36"

    private val LD_JSON = Regex(
        """<script[^>]+application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** What a check says when a product page is gone: the site removed it, or sends you elsewhere. */
    fun goneMessage(url: String) = if ("vergelijk.be" in URL(url).host) "No longer listed" else "No longer on " + shop(url)

    fun fetch(url: String): Result {
        val (html, finalUrl) = try {
            downloadPage(url, "text/html,application/xhtml+xml")
        } catch (e: IOException) {
            throw if (e.message == "HTTP 404" || e.message == "HTTP 410") IOException(goneMessage(url)) else e
        }
        // A removed product redirects to a category or the home page, full of other products'
        // prices (Pokémon cards for a piano bench): that page is no answer for this product.
        // Only for vergelijk.be, whose product urls stay put; a shop may redirect to its own new url.
        if ("vergelijk.be" in URL(url).host && movedElsewhere(url, finalUrl)) throw IOException(goneMessage(url))
        return try {
            parse(html)
        } catch (e: IOException) {
            // A check page instead of the product ("Just a moment…"): say the shop keeps apps out,
            // rather than that the page has no prices.
            if (isBrowserCheck(html)) throw IOException("${shop(url)} only allows browsers")
            throw e
        }
    }

    private fun shop(url: String) = Shops.name(URL(url).host)

    private val CHALLENGE = listOf(
        "just a moment", "checking your browser", "enable javascript", "verify you are human", "captcha",
        "cf-chl", "challenge-platform", "access denied", "are you a robot", "bot protection",
    )

    /** A bot check, not a product page: one of the usual challenge texts, or a tiny page without a title. */
    internal fun isBrowserCheck(html: String): Boolean {
        val lower = html.lowercase()
        if (CHALLENGE.any { it in lower }) return true
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return html.length < 5_000 && title.isEmpty()
    }

    /** Whether a redirect ended on another page than the product's (the query aside). */
    internal fun movedElsewhere(asked: String, got: String): Boolean {
        fun page(u: String) = runCatching { URL(u) }.getOrNull()?.let { it.host.removePrefix("www.") + it.path.trimEnd('/') }
        val a = page(asked) ?: return false
        val b = page(got) ?: return false
        return a != b
    }

    internal fun download(url: String, accept: String, headers: Map<String, String> = emptyMap()): String =
        downloadPage(url, accept, headers).first

    /** The page and the url it ended on, after redirects. */
    private fun downloadPage(url: String, accept: String, headers: Map<String, String> = emptyMap()): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            // 403/406/429: the usual answers of shops that keep apps out.
            if (code == 403 || code == 406 || code == 429) throw IOException("${shop(url)} only allows browsers")
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() } to conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    fun parse(html: String): Result {
        val all = LD_JSON.findAll(html)
            .flatMap { m ->
                runCatching { nodes(JSONTokener(m.groupValues[1].trim()).nextValue()) }
                    .getOrDefault(emptyList())
            }
            .toList()
        val products = all.filter { isType(it, "Product") }
        val fromSchema = products.firstNotNullOfOrNull { product ->
            offers(product.opt("offers")).takeIf { it.isNotEmpty() }
                ?.let { Result(product.optString("name").trim(), it, image(product.opt("image"), all)) }
        }
        // vergelijk.be: its app state has the prices after discounts, which schema.org leaves out.
        val shopOffers = NuxtOffers.parse(html)
        // Without a product on the page, those offers aren't of one product either.
        if (shopOffers.isNotEmpty() && products.isNotEmpty()) {
            val product = products.firstOrNull()
            val name = fromSchema?.name ?: product?.optString("name")?.trim().orEmpty()
            return Result(name, shopOffers, fromSchema?.image ?: product?.let { image(it.opt("image"), all) })
        }
        return fromSchema ?: throw IOException("No prices found on page")
    }

    /** A url from schema.org's image: a string, a list, an ImageObject, or a reference by "@id". */
    private fun image(value: Any?, all: List<JSONObject>): String? {
        val url = when (value) {
            is String -> value
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { image(value.opt(it), all) }
            is JSONObject -> value.optString("url").ifBlank { value.optString("contentUrl") }.ifBlank {
                val ref = value.optString("@id")
                all.firstOrNull { it !== value && it.optString("@id") == ref && (it.has("url") || it.has("contentUrl")) }
                    ?.let { image(it, all) }
                    .orEmpty()
            }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** Every JSON object in the tree, parents before children. */
    private fun nodes(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).flatMap { nodes(value.opt(it)) }
        is JSONObject -> listOf(value) + value.keys().asSequence().toList().flatMap { nodes(value.opt(it)) }
        else -> emptyList()
    }

    private fun isType(obj: JSONObject, type: String): Boolean = when (val t = obj.opt("@type")) {
        is String -> t == type
        is JSONArray -> (0 until t.length()).any { t.optString(it) == type }
        else -> false
    }

    private fun offers(value: Any?): List<Offer> = when (value) {
        is JSONArray -> (0 until value.length()).flatMap { offers(value.opt(it)) }
        is JSONObject -> {
            val nested = offers(value.opt("offers"))
            when {
                nested.isNotEmpty() -> nested
                isType(value, "AggregateOffer") ->
                    listOfNotNull(price(value.opt("lowPrice"))?.let { Offer("", it) })
                else -> listOfNotNull(price(value.opt("price"))?.let { Offer(seller(value), it, inStock(value)) })
            }
        }
        else -> emptyList()
    }

    private fun price(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.replace(",", ".").toDoubleOrNull()
        else -> null
    }?.takeIf { it > 0 }

    private fun inStock(offer: JSONObject): Boolean? {
        val availability = offer.optString("availability").substringAfterLast('/')
        return when {
            availability.isEmpty() -> null
            availability in setOf("InStock", "LimitedAvailability", "OnlineOnly", "InStoreOnly", "PreOrder", "BackOrder") -> true
            else -> false
        }
    }

    private fun seller(offer: JSONObject): String = when (val s = offer.opt("seller")) {
        is JSONObject -> s.optString("name")
        is String -> s
        else -> ""
    }.trim()
}
