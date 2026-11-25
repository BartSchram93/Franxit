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
    /** [title]: what the shop calls the product, where the source gives it (vergelijk.be's offers). */
    data class Offer(val seller: String, val price: Double, val inStock: Boolean? = null, val link: String? = null, val title: String? = null)

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
    fun explain(e: Exception, url: String): String {
        val shop = shop(url)
        val message = e.message.orEmpty()
        return when {
            e is SocketTimeoutException -> "$shop didn't answer in time"
            e is UnknownHostException -> "No internet connection, or $shop can't be reached"
            e is SSLException -> "Couldn't connect securely to $shop"
            // "Software caused connection abort": the connection dropped (another network, the app in the background).
            e is java.net.SocketException && e !is java.net.ConnectException -> "Connection to $shop lost"
            e is java.net.ConnectException -> "No internet connection, or $shop can't be reached"
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
        // Amazon's pages have no product data: their price and stock are read from the places Amazon puts them.
        if (AmazonPage.isAmazon(url)) {
            AmazonPage.parse(html)?.let { return it }
            if (AmazonPage.isSoldOut(html)) throw IOException("Not available at ${shop(url)} right now")
        }
        return try {
            withVat(parse(html), html)
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
     * A shop whose product data holds its prices without VAT (Back in use: 354.55 in the data, "€429,01 incl. BTW"
     * on the page): the price with 21% VAT, when that one shows on the page and the data's price doesn't.
     */
    fun withVat(result: Result, html: String): Result {
        val shown = html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ").replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
        // The price as pages write it, followed by the words that say VAT is in it.
        fun withVatOnPage(price: Double): Boolean {
            val cents = String.format(java.util.Locale.ROOT, "%.2f", price)
            val whole = cents.substringBefore('.').toLong()
            val written = listOf(cents, cents.replace('.', ','), "%d.%03d,%s".format(java.util.Locale.ROOT, whole / 1000, whole % 1000, cents.substringAfter('.')))
                .filter { !it.startsWith("0.") }
            return written.any { Regex("""(?<![\d.,])${Regex.escape(it)}\s*(incl|inclusief|tvac|ttc|btw inbegrepen)""", RegexOption.IGNORE_CASE).containsMatchIn(shown) }
        }
        // One price shown with VAT where the data has it without: the shop's data is without VAT, all its versions'
        // prices too (the page shows only the chosen one's).
        val withoutVat = result.offers.any { offer ->
            !withVatOnPage(offer.price) && withVatOnPage(Math.round(offer.price * 121.0) / 100.0)
        }
        if (!withoutVat) return result
        return result.copy(offers = result.offers.map { it.copy(price = Math.round(it.price * 121.0) / 100.0) })
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

    fun isUnavailable(html: String): Boolean = UNAVAILABLE.containsMatchIn(html)

    private fun shop(url: String) = Shops.name(URL(url).host)

    private val CHALLENGE = listOf(
        "just a moment", "checking your browser", "enable javascript", "verify you are human", "captcha",
        "cf-chl", "challenge-platform", "access denied", "are you a robot", "bot protection",
    )

    /**
     * A bot check, not a product page: a challenge text in its title, or in a small page (a check page is a few
     * kilobytes), or a tiny page without a title. Not the same words somewhere in a full shop page: Cloudflare puts
     * its "challenge-platform" script in ordinary pages, and a contact form its "captcha"; music-vanderheyden.be's
     * product page counted as a check page, even in the browser, and the product never got a name or price.
     */
    fun isBrowserCheck(html: String): Boolean {
        val lower = html.lowercase()
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (CHALLENGE.any { it in title.lowercase() }) return true
        if (html.length < CHECK_PAGE_SIZE && CHALLENGE.any { it in lower }) return true
        return html.length < 5_000 && title.isEmpty()
    }

    /** A page this size or larger is a real page, whatever words it holds. */
    private const val CHECK_PAGE_SIZE = 60_000

    /** Whether a redirect ended on another page than the product's (the query aside). */
    fun movedElsewhere(asked: String, got: String): Boolean {
        fun page(u: String) = runCatching { URL(u) }.getOrNull()?.let { it.host.removePrefix("www.") + it.path.trimEnd('/') }
        val a = page(asked) ?: return false
        val b = page(got) ?: return false
        return a != b
    }

    /** A page that sends you on with a meta refresh or a script ("location.href = '…'"): where it sends you. */
    fun forwardIn(html: String): String? {
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

    fun download(url: String, accept: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): String =
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
                // Franxit's own search service: its answer as it is ("HTTP 429: Too many searches today"), not a shop's.
                if (code !in 200..299 && current.startsWith(GoogleShopping.PROXY)) {
                    val said = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().trim().take(80)
                    throw IOException("HTTP $code" + if (said.isNotEmpty()) ": $said" else "")
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
    fun keepCookies(jar: MutableMap<String, MutableMap<String, String>>, host: String, setCookies: List<String>) {
        for (line in setCookies) {
            val pair = line.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            if (name.isEmpty()) continue
            jar.getOrPut(siteOf(host)) { mutableMapOf() }[name] = pair.substringAfter('=')
        }
    }

    /** The Cookie header for [host]: only its own site's cookies ("www.shop.be" and "shop.be" share them). */
    fun cookieHeader(jar: Map<String, Map<String, String>>, host: String): String? =
        jar[siteOf(host)]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    private fun siteOf(host: String) = host.lowercase().removePrefix("www.")

    fun parse(html: String): Result {
        val all = LD_JSON.findAll(html)
            .flatMap { m ->
                runCatching { nodes(JSONTokener(m.groupValues[1].trim()).nextValue()) }
                    .getOrDefault(emptyList())
            }
            .toList()
        val products = pageFirst(all.filter { isType(it, "Product") }, html)
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
        return fromSchema ?: fromMeta(html) ?: throw IOException("No prices found on page")
    }

    /** `<meta property="product:price:amount" content="…">` or og:price:amount, and microdata's itemprop="price". */
    private val META_PRICE = Regex("""<meta[^>]+(?:property|name)=["'](?:product|og):price:amount["'][^>]*content=["']([0-9][0-9.,]*)["']|<meta[^>]+content=["']([0-9][0-9.,]*)["'][^>]*(?:property|name)=["'](?:product|og):price:amount["']""", RegexOption.IGNORE_CASE)
    private val ITEMPROP_PRICE = Regex("""itemprop=["']price["'][^>]*content=["']([0-9][0-9.,]*)["']|content=["']([0-9][0-9.,]*)["'][^>]*itemprop=["']price["']""", RegexOption.IGNORE_CASE)
    /** Odoo's shops (music-vanderheyden.be): `<span itemprop="price" style="display:none;">799.0</span>`, the price as text. */
    private val ITEMPROP_PRICE_TEXT = Regex("""itemprop=["']price["'][^>]*>\s*([0-9][0-9.,]*)\s*<""", RegexOption.IGNORE_CASE)
    private val META_CURRENCY = Regex("""(?:product|og):price:currency["'][^>]*content=["']([A-Za-z]{3})|itemprop=["']priceCurrency["'][^>]*content=["']([A-Za-z]{3})|itemprop=["']priceCurrency["'][^>]*>\s*([A-Za-z]{3})\s*<""", RegexOption.IGNORE_CASE)
    private val META_STOCK = Regex("""(?:product:availability["'][^>]*content=["']([^"']+)|itemprop=["']availability["'][^>]*(?:href|content)=["']([^"']+))""", RegexOption.IGNORE_CASE)
    private val OG_TITLE = Regex("""<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE = Regex("""<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * A page without schema.org's product data but with the price in its meta tags or microdata (shops that fill
     * those for Facebook or older search engines): music-vanderheyden.be's page, once reached, had no price the app
     * could read. Null without such a price.
     */
    fun fromMeta(html: String): Result? {
        val raw = (META_PRICE.find(html) ?: ITEMPROP_PRICE.find(html) ?: ITEMPROP_PRICE_TEXT.find(html))?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: return null
        val currency = META_CURRENCY.find(html)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.uppercase()
        if (currency != null && currency != "EUR") return null
        // "1.299,00", "1,299.00", "1299.00", "1299,00", Odoo's "799.0": the last separator before one or two digits
        // is the decimal one ("1.299" is a thousand).
        val price = Regex("""^(.*?)[.,](\d{1,2})$""").find(raw)?.let { m -> (m.groupValues[1].replace(Regex("[.,]"), "") + "." + m.groupValues[2]).toDoubleOrNull() }
            ?: raw.replace(Regex("[.,]"), "").toDoubleOrNull()
        if (price == null || price <= 0) return null
        val stock = META_STOCK.find(html)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.lowercase()
        val inStock = when {
            stock == null -> null
            listOf("outofstock", "out of stock", "soldout", "discontinued", "oos").any { it in stock.replace("_", " ").replace("-", " ") || it in stock } -> false
            else -> true
        }
        val title = OG_TITLE.find(html)?.groupValues?.get(1)
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)
        val name = title.orEmpty().replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()
        return Result(name, listOf(Offer("", price, inStock)), OG_IMAGE.find(html)?.groupValues?.get(1))
    }

    /** schema.org's brand: a name, a Brand or Organization with a name, or a list of those. */
    fun brand(value: Any?): String? = when (value) {
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
    private val CANONICAL = Regex("""<link[^>]+rel=["']canonical["'][^>]*href=["']([^"']+)|<link[^>]+href=["']([^"']+)["'][^>]*rel=["']canonical["']|<meta[^>]+property=["']og:url["'][^>]*content=["']([^"']+)""", RegexOption.IGNORE_CASE)
    private val PAGE_TITLE = Regex("""<title[^>]*>(.*?)</title>|<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)|<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * The page's own product first, among the variants its data lists: bol.com's page of the black Sennheiser
     * HD 599 SE lists the brown HD 599 too, first. The page's own one is the product at the page's address (its
     * last number, "/9300000002888093/"), else the one its title names; otherwise the order stays.
     */
    private fun pageFirst(products: List<JSONObject>, html: String): List<JSONObject> {
        if (products.size < 2) return products
        val address = CANONICAL.findAll(html).flatMap { it.groupValues.drop(1) }.filter { it.isNotEmpty() }.toList()
        val ids = address.mapNotNull { Regex("""(\d{6,})(?!.*\d{6,})""").find(it.substringBefore('?'))?.groupValues?.get(1) }.toSet()
        fun urls(p: JSONObject): List<String> = listOfNotNull(p.optString("url"), p.optString("@id"), p.optString("sku"), p.optString("productID")) +
            (p.optJSONObject("offers")?.optString("url")?.let(::listOf) ?: emptyList())
        products.firstOrNull { p -> urls(p).any { u -> u.isNotEmpty() && (address.any { a -> a.substringBefore('?').trimEnd('/') == u.substringBefore('?').trimEnd('/') } || ids.any { it in u }) } }
            ?.let { own -> return listOf(own) + (products - own) }
        val titles = PAGE_TITLE.findAll(html).flatMap { it.groupValues.drop(1) }.filter { it.isNotBlank() }
            .map { it.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim().lowercase() }.toList()
        products.firstOrNull { p -> p.optString("name").trim().lowercase().let { n -> n.isNotEmpty() && titles.any { n in it } } }
            ?.let { own -> return listOf(own) + (products - own) }
        return products
    }

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
