package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.Shops
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Reads the schema.org Product data (JSON-LD) that comparison sites such as vergelijk.be embed
 * in their product pages, and returns every offer on it.
 */
object Scraper {

    /** [inStock] is null when the page doesn't say. [link] goes straight to the shop, when known. */
    data class Offer(val seller: String, val price: Double, val inStock: Boolean? = null, val link: String? = null)

    /** [brand]: the maker the page names ("999 Games"), when it does. */
    data class Result(val name: String, val offers: List<Offer>, val image: String? = null, val brand: String? = null) {
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

    /**
     * What a check says when a page couldn't be read, so you see why ("Vtechnl.com didn't answer in time") and not
     * Java's words ("Too many follow-up requests: 21"). The app's own messages pass unchanged.
     */
    internal fun explain(e: Exception, url: String): String {
        val shop = shop(url)
        val message = e.message.orEmpty()
        return when {
            e is SocketTimeoutException -> "$shop didn't answer in time"
            e is UnknownHostException -> "No internet connection, or $shop can't be reached"
            e is SSLException -> "Couldn't connect securely to $shop"
            // Sent round in circles: a shop that only lets browsers in (it wants cookies or scripts the app lacks).
            e is ProtocolException || message.startsWith("Too many") -> "$shop only allows browsers"
            Regex("""HTTP 5\d\d""").matches(message) -> "$shop had a problem, tried again at the next check"
            Regex("""HTTP \d+""").matches(message) -> "$shop refused the page ($message)"
            message.isBlank() -> "$shop couldn't be read"
            else -> message
        }
    }

    /** [readTimeoutMs]: how long to wait for the page; shorter when many shops' pages are read for one check. */
    fun fetch(url: String, readTimeoutMs: Int = 30_000): Result {
        val (html, finalUrl) = try {
            downloadPage(url, "text/html,application/xhtml+xml", readTimeoutMs = readTimeoutMs)
        } catch (e: IOException) {
            throw if (e.message == "HTTP 404" || e.message == "HTTP 410") IOException(goneMessage(url)) else IOException(explain(e, url), e)
        }
        // A removed product redirects to a category or the home page, full of other products'
        // prices (Pokémon cards for a piano bench): that page is no answer for this product.
        // Only for vergelijk.be, whose product urls stay put; a shop may redirect to its own new url.
        if ("vergelijk.be" in URL(url).host && movedElsewhere(url, finalUrl)) throw IOException(goneMessage(url))
        return fromPage(html, url)
    }

    /** The product on the page [html] of [url] (read here, or in a browser): its offers, or why there are none. */
    fun fromPage(html: String, url: String): Result {
        return try {
            parse(html)
        } catch (e: IOException) {
            // A check page instead of the product ("Just a moment…"): say the shop keeps apps out,
            // rather than that the page has no prices.
            if (isBrowserCheck(html)) throw IOException("${shop(url)} only allows browsers")
            // Sold out: shops often leave the price off then. Said so, not that the app can't read the page.
            if (isUnavailable(html)) throw IOException("Not available at ${shop(url)} right now")
            throw IOException("${shop(url)}'s page shows no price the app can read")
        }
    }

    /**
     * A page whose product data says it can't be bought now (schema.org's "OutOfStock", "SoldOut", "Discontinued").
     * Only the data, not words on the page: a page also names other versions ("Currently unavailable" for the pink
     * Selphy on Amazon's page of the black one), and those words said nothing of this product.
     */
    private val UNAVAILABLE = Regex(
        """"availability"\s*:\s*"(?:https?://schema\.org/)?(?:OutOfStock|SoldOut|Discontinued)"|itemprop=["']availability["'][^>]*(?:OutOfStock|SoldOut|Discontinued)""",
        RegexOption.IGNORE_CASE,
    )

    internal fun isUnavailable(html: String): Boolean = UNAVAILABLE.containsMatchIn(html)

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

    /** A page that sends you on with a meta refresh or a script ("location.href = '…'"): where it sends you. */
    internal fun forwardIn(html: String): String? {
        val meta = Regex("""<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(html)
        val script = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""").find(html)
        return (meta ?: script)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
    }

    /**
     * Where a link ends up: its redirects followed, and a page that forwards you on (a meta refresh, a script) once
     * more. For a comparison site's "go to the shop" link, the shop's page you land on.
     */
    fun finalUrl(url: String): String {
        var (html, current) = downloadPage(url, "text/html,application/xhtml+xml")
        repeat(2) {
            val next = forwardIn(html)?.takeIf { it != current } ?: return current
            val page = downloadPage(next, "text/html,application/xhtml+xml")
            html = page.first
            current = page.second
        }
        return current
    }

    internal fun download(url: String, accept: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): String =
        downloadPage(url, accept, headers, readTimeoutMs).first

    private const val MAX_REDIRECTS = 10

    /**
     * The page and the url it ended on, after redirects. Followed here, with the cookies each site sets on the way,
     * like a browser: a shop that sets a cookie and sends you back to the same page (vtechnl.com) otherwise
     * sends the app round in circles ("Too many follow-up requests"). Cookies live for this one download only.
     */
    private fun downloadPage(url: String, accept: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): Pair<String, String> {
        val cookies = mutableMapOf<String, MutableMap<String, String>>()
        var current = url
        repeat(MAX_REDIRECTS) {
            val address = URL(current)
            val conn = address.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                cookieHeader(cookies, address.host)?.let { conn.setRequestProperty("Cookie", it) }
                val code = conn.responseCode
                keepCookies(cookies, address.host, conn.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten())
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(address, location).toString()
                    return@repeat
                }
                // 403/406/429: the usual answers of shops that keep apps out.
                if (code == 403 || code == 406 || code == 429) throw IOException("${shop(url)} only allows browsers")
                if (code !in 200..299) throw IOException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() } to current
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("${shop(url)} only allows browsers")
    }

    /** The cookies a site set ("name=value; Path=/; …"), by host: only the name and value are kept. */
    internal fun keepCookies(jar: MutableMap<String, MutableMap<String, String>>, host: String, setCookies: List<String>) {
        for (line in setCookies) {
            val pair = line.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            if (name.isEmpty()) continue
            jar.getOrPut(siteOf(host)) { mutableMapOf() }[name] = pair.substringAfter('=')
        }
    }

    /** The Cookie header for [host]: only its own site's cookies ("www.shop.be" and "shop.be" share them). */
    internal fun cookieHeader(jar: Map<String, Map<String, String>>, host: String): String? =
        jar[siteOf(host)]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    private fun siteOf(host: String) = host.lowercase().removePrefix("www.")

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
                ?.let { Result(product.optString("name").trim(), it, image(product.opt("image"), all), brand(product.opt("brand"))) }
        }
        // vergelijk.be: its app state has the prices after discounts, which schema.org leaves out.
        val shopOffers = NuxtOffers.parse(html)
        // Without a product on the page, those offers aren't of one product either.
        if (shopOffers.isNotEmpty() && products.isNotEmpty()) {
            val product = products.firstOrNull()
            val name = fromSchema?.name ?: product?.optString("name")?.trim().orEmpty()
            return Result(name, shopOffers, fromSchema?.image ?: product?.let { image(it.opt("image"), all) }, product?.let { brand(it.opt("brand")) })
        }
        return fromSchema ?: throw IOException("No prices found on page")
    }

    /** schema.org's brand: a name, a Brand or Organization with a name, or a list of those. */
    internal fun brand(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.optString("name")
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { brand(value.opt(it)) }
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("null", "unbranded", "geen merk", "no brand", "generic") }

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

    /**
     * The currency an offer names ("GBP"), from itself or its price specification; null when it names none (then
     * euros, as on every Belgian shop).
     */
    private fun currency(offer: JSONObject): String? =
        offer.optString("priceCurrency").ifBlank { offer.optJSONObject("priceSpecification")?.optString("priceCurrency").orEmpty() }
            .trim().uppercase().takeIf { it.isNotEmpty() }

    private fun offers(value: Any?): List<Offer> = when (value) {
        is JSONArray -> (0 until value.length()).flatMap { offers(value.opt(it)) }
        is JSONObject -> {
            val nested = offers(value.opt("offers"))
            when {
                nested.isNotEmpty() -> nested
                // A price in pounds or another currency (Nintendo's UK store, £ 434,99) is no price in euros.
                currency(value).let { it != null && it != "EUR" } -> emptyList()
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
