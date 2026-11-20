package com.bartschram.franxit.scrape

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Shoparize (shoparize.com/be), a price comparison site: the shops it lists for a product, as extra
 * prices next to vergelijk.be's. Only read: its buttons are tracking links, so they are never followed
 * and the Buy button doesn't use them.
 *
 * A search page lists products as a schema.org ItemList; a product page lists its shops in a table whose
 * rows each carry a `data-tracking` block (the shop's site and its price as a number).
 */
object Shoparize {

    private const val BASE = "https://www.shoparize.com"
    private const val PRODUCT_PAGES = "$BASE/be/p/"
    private val HEADERS = mapOf("Accept-Language" to "nl-BE,nl;q=0.9,en;q=0.8")

    private val LD_JSON = Regex(
        """<script[^>]+application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val OFFER_ROW = Regex("""<a\b([^>]*\bdata-tracking="([^"]+)"[^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""\bhref="([^"]+)"""")
    private val REFURBISHED = Regex("""refurbished|gebruikt|tweedehands|second.?hand|occasion""", RegexOption.IGNORE_CASE)

    fun searchUrl(query: String) = "$BASE/be/q?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    /** The products a search page lists, in Shoparize's order. Only product pages count. */
    fun parseSearch(html: String): List<VergelijkSearch.Hit> =
        LD_JSON.findAll(html).flatMap { block ->
            val json = runCatching { JSONTokener(block.groupValues[1].trim()).nextValue() }.getOrNull()
            val lists = when (json) {
                is JSONObject -> listOf(json)
                is JSONArray -> (0 until json.length()).mapNotNull { json.optJSONObject(it) }
                else -> emptyList()
            }
            lists.filter { it.optString("@type") == "ItemList" }.flatMap { list ->
                val items = list.optJSONArray("itemListElement") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i) ?: return@mapNotNull null
                    val url = item.optString("url")
                    val title = item.optString("name").trim()
                    if (url.startsWith(PRODUCT_PAGES) && title.isNotEmpty()) VergelijkSearch.Hit(title, url, null) else null
                }
            }
        }.toList()

    /**
     * The shops of a product page with their prices. Refurbished offers are left out: the app follows
     * the new product. Whether a shop has it in stock isn't said (null), like for Google Shopping's shops.
     */
    fun parseOffers(html: String): List<Scraper.Offer> =
        OFFER_ROW.findAll(html).mapNotNull { row ->
            val data = runCatching { JSONObject(unescape(row.groupValues[2])) }.getOrNull() ?: return@mapNotNull null
            if (data.optString("element") != "pricesTable") return@mapNotNull null
            val shop = data.optString("shopid").trim().ifEmpty { return@mapNotNull null }
            val price = data.optDouble("price", 0.0).takeIf { it > 0 } ?: return@mapNotNull null
            if (REFURBISHED.containsMatchIn(row.groupValues[3])) return@mapNotNull null
            val href = HREF.find(row.groupValues[1])?.groupValues?.get(1)?.let(::unescape)
            Scraper.Offer(shop, price, link = href?.let { cleanLink(it, shop) })
        }.toList()

    /** At most this many searches for one product: its name as given, then shorter and tidied wordings. */
    private const val MAX_SEARCHES = 5

    /**
     * [wanted] as Shoparize's titles write it: "5 kg" as "5kg", and a lone letter joined to the word before
     * it ("Pro X" as "ProX", "2 x 5kg" as "2x 5kg").
     */
    internal fun tidy(wanted: String): String =
        wanted.trim().replace(Regex("""(\d)\s+(kg|g|mg|l|cl|ml|gb|tb|mb|cm|mm|w|v|mah|hz)\b""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(Regex("""(?<=\S)\s+(\p{L})(?=\s|$)"""), "$1")
            .replace(Regex("\\s+"), " ")

    /**
     * The wordings to search for, best first. Shoparize's search wants every word to be in a title, so a long
     * name (with a colour, a size, "incl. …") finds nothing: the name as given, then tidied, then its first
     * words only. A shorter search finds more products; [best] still decides which one is the product.
     */
    internal fun queries(wanted: String): List<String> {
        val given = wanted.trim().replace(Regex("\\s+"), " ")
        val tidy = tidy(wanted)
        val words = tidy.split(' ').filter { it.isNotEmpty() }
        val shorter = listOf(5, 4, 3).filter { it < words.size }.map { words.take(it).joinToString(" ") }
        return (listOf(given, tidy) + shorter).filter { it.isNotBlank() }.distinct().take(MAX_SEARCHES)
    }

    /**
     * Shoparize's shops for the product called [wanted]: searched (with shorter wordings when the first finds
     * nothing that fits), the one product that is clearly it (the same matcher as for Google Shopping's
     * results), then its shops. Empty when none is clear-cut. Throws when the site can't be reached; the
     * caller treats that as "ask again later".
     */
    fun offersFor(
        wanted: String,
        download: (String) -> String = { Scraper.download(it, "text/html,application/xhtml+xml", HEADERS) },
    ): List<Scraper.Offer> {
        val tidy = tidy(wanted)
        for (query in queries(wanted)) {
            val hits = parseSearch(download(searchUrl(query)))
            // The tidied name first, then the name as it is (tidying must not make a good match worse).
            val hit = best(hits, tidy) ?: best(hits, wanted.trim()) ?: continue
            return parseOffers(download(hit.url))
        }
        return emptyList()
    }

    /**
     * The listed product that is [wanted], the best fit if several are; null when none fits. Not lenient:
     * a bundle ("… + Fortnite") or an accessory is another product with another price, and one of those
     * must not pass for the product itself.
     */
    internal fun best(hits: List<VergelijkSearch.Hit>, wanted: String): VergelijkSearch.Hit? =
        hits.filter { multipliers(it.title) == multipliers(wanted) && Matching.isSameProduct(it.title, wanted, lenient = false) }
            .maxByOrNull { Matching.score(it.title, wanted) }

    private val MULTIPLIER = Regex("""(?<![\w.,])(\d{1,3})\s?[x×](?!\w)""", RegexOption.IGNORE_CASE)

    /**
     * The multipacks a text names: "2x Dash …" and "… 2 X 5kg" are both a 2. A multipack is another product
     * with another price than the single pack, so the wanted name and the title must name the same ones.
     */
    internal fun multipliers(text: String): Set<Int> =
        MULTIPLIER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /** Query parameters that only track where a visit came from. */
    private val TRACKING = setOf("tt", "tduid", "awc", "gclid", "fbclid", "sclid", "cid", "aff", "affid", "affiliate", "ref")

    /** Path words of a tracking or redirect page: such a page is no product page. */
    private val REDIRECT = setOf("click", "clicks", "redirect", "track", "tracking", "go", "out", "aff", "affiliate", "partner")

    /**
     * The shop's own product page, when Shoparize's tracking link ([trackingHref]) names it in full on the
     * shop's own site ([shopDomain]): "…&url=…%26r%3Dhttps%3A%2F%2Fwww.pazzox.be%2Fnl%2F…%2Fprox-caps-180%2F".
     * Without tracking parameters (utm_…, tt, awc…). Null when the link doesn't say, as with an affiliate
     * network's own address or a path that is only a relative deep link: then the Buy button uses a search.
     * The link is only read, never followed.
     */
    internal fun cleanLink(trackingHref: String, shopDomain: String): String? {
        val domain = shopDomain.trim().lowercase().removePrefix("www.")
        if (domain.isEmpty()) return null
        var text = trackingHref.substringAfter("url=", "").substringBefore("&")
        if (text.isEmpty()) return null
        // Encoded once or twice over: decoded until it stops changing.
        repeat(3) { text = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text) }
        // The last address on the shop's own site is the product page; the first may be its tracking page.
        val address = Regex("""https?://(?:www\.)?${Regex.escape(domain)}/[^\s&"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.value }.lastOrNull() ?: return null
        val base = address.substringBefore("?")
        val path = runCatching { URI(base).path }.getOrNull().orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        if (path.size < 2 || path.any { it.lowercase() in REDIRECT }) return null
        val kept = address.substringAfter("?", "").split('&')
            .filter { it.isNotEmpty() && !it.lowercase().startsWith("utm_") && it.substringBefore("=").lowercase() !in TRACKING }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    /** Big shops with a search page of their own: the Buy button goes there. */
    private val OWN_SEARCH = listOf("bol", "coolblue", "fnac", "mediamarkt", "krefel")
    private val DOMAIN = Regex("""[a-z0-9-]+(\.[a-z0-9-]+)+""")

    /**
     * Where the Buy button goes for a shop that only Shoparize lists. Never Shoparize itself: its links are
     * tracking links. A big shop's own search page for the product, else a Google search limited to that
     * shop's site ("name site:pazzox.be"), whose first result is the shop's page for the product.
     */
    fun shopSearchUrl(query: String, shop: String): String {
        val s = shop.trim().lowercase()
        if (OWN_SEARCH.any { it in s }) return GoogleShopping.fallbackUrl(query, shop)
        val domain = s.removePrefix("www.")
        if (!DOMAIN.matches(domain)) return GoogleShopping.fallbackUrl(query, shop)
        return "https://www.google.be/search?q=" + URLEncoder.encode("${query.trim()} site:$domain", "UTF-8")
    }

    private fun unescape(s: String) =
        s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
