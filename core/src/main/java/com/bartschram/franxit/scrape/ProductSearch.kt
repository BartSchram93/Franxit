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
 * Only what the source gives is kept: a product without a price, photo or shop stays without one. The search screen
 * shows a found product's name and photo; its price is read when it's tracked, from the shops' pages as for any.
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

    const val MAX_RESULTS = 25
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
     * The product pages among [products], each at the shop's Belgian address: not a search, a category, a list or a
     * page about the shop (the same rules as for a shop's link elsewhere in the app, and some more for what a web
     * search finds: advice, news, blogs). Not another country's site, nor a shop's Dutch site when it has a Belgian one
     * (coolblue.nl, amazon.nl): the app is for buying in Belgium. Only a product's page is ever tracked.
     */
    fun productPages(products: List<Product>): List<Product> = products.mapNotNull { p ->
        // bol.com's Dutch store ("/nl/nl/") as its Belgian one.
        val url = DuckDuckGo.belgian(p.url)
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase().removePrefix("www.")
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        when {
            !ShopCheck.isProductPage(url) || ShopCheck.isElsewhere(url, p.title) -> null
            DuckDuckGo.isForeignSite(url) -> null
            // Another country's pages of a site ("lego.com/en-us/"); on a Belgian site those letters are a language.
            !host.endsWith(".be") && DuckDuckGo.isOtherCountry(url) -> null
            // A site for another country by its first part ("es.nbb.com"); "nl.fnac.be" and "fr.zalando.be" are Belgian.
            host.substringBefore('.') in OTHER_COUNTRIES -> null
            hasBelgianSite(host) -> null
            NOT_A_PRODUCT.containsMatchIn(path) || path.lowercase().endsWith(".pdf") -> null
            !namesOneProduct(path) -> null
            else -> p.copy(url = url, title = cleanTitle(p.title, host))
        }
    }.distinctBy { samePage(it.url) }.let(::withoutCategories).let(::withoutLogos)

    /**
     * Pages of a shop whose products have a number in their address ("…-271000937") but not this one: its categories
     * ("…/sledestofzuigers-zonder-zak" at Molecule), however many words their address has.
     */
    private fun withoutCategories(products: List<Product>): List<Product> {
        val numbered = Regex("""\d{5,}""")
        fun host(p: Product) = runCatching { URI(p.url).host }.getOrNull().orEmpty().lowercase().removePrefix("www.")
        fun path(p: Product) = runCatching { URI(p.url).path }.getOrNull().orEmpty()
        val shopsWithNumbers = products.filter { numbered.containsMatchIn(path(it)) }.map(::host).toSet()
        return products.filter { host(it) !in shopsWithNumbers || numbered.containsMatchIn(path(it)) || AMAZON_PATH.containsMatchIn(path(it)) }
    }

    /** A photo on two or more pages of the found ones is the shop's logo (Molecule's), not the product's: left out. */
    private fun withoutLogos(products: List<Product>): List<Product> {
        val shared = products.mapNotNull { it.imageUrl }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return products.map { if (it.imageUrl in shared) it.copy(imageUrl = null) else it }
    }

    /**
     * A page's title as the product's name, as vergelijk.be's were: without the shop's name and slogans that pages
     * add ("… | MediaMarkt", "… | Coolblue | Consoles", "Nedgame gameshop: … kopen", "… - Beste prijs, snelle
     * levering", "… : Amazon.com.be: Electronics"). The title itself when nothing would be left.
     */
    fun cleanTitle(title: String, host: String): String {
        val shop = host.removePrefix("www.").substringBefore('.').lowercase().replace("-", "")
        fun isShop(part: String) = part.lowercase().replace(Regex("[^a-z0-9]"), "").let { it.isNotEmpty() && (it.startsWith(shop) || shop.startsWith(it)) }
        var t = title.substringBefore(" | ").trim()
        t = t.replace(Regex("""\s*:\s*Amazon\.[a-z.]+\s*(:.*)?$""", RegexOption.IGNORE_CASE), "")
        // "Nedgame gameshop: Playstation 5 Pro …": the shop's name before a colon.
        Regex("""^([^:]{2,40}):\s+(.+)$""").find(t)?.let { m -> if (m.groupValues[1].split(' ').any(::isShop)) t = m.groupValues[2] }
        // "… - Molecule", "… - Beste prijs, snelle levering": the last part when it's the shop or a slogan.
        while (true) {
            val at = maxOf(t.lastIndexOf(" - "), t.lastIndexOf(" – "))
            if (at <= 0) break
            val last = t.substring(at + 3).trim()
            if (isShop(last) || SLOGAN.containsMatchIn(last)) t = t.substring(0, at).trim() else break
        }
        // Advertising after the name: "Sony PlayStation 5 Pro 2: Console gaming in 4K Ultra HD, met next-gen …",
        // "Fresh 'n Rebel Twins Fuse - Draadloze Oordopjes - 30 Uur Luisterplezier".
        Regex("""^(.{12,}?):\s+(.+)$""").find(t)?.let { m -> if (m.groupValues[2].split(' ').size >= 5) t = m.groupValues[1] }
        val parts = t.split(Regex("""\s+[-–]\s+"""))
        if (parts.size > 1) t = (listOf(parts.first()) + parts.drop(1).filterNot { ADVERTISING.containsMatchIn(it) }).joinToString(" - ")
        // A long name to its first comma ("Soundcore Q20i by Anker, Draadloze Bluetooth over-ear hoofdtelefoon met …").
        if (t.length > 60 && t.indexOf(", ") >= 12) t = t.substringBefore(", ")
        t = t.replace(Regex("""^(koop|kopen|buy|acheter)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(kopen|bestellen|buy|acheter)\??$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return t.ifEmpty { title.trim() }
    }

    private val ADVERTISING = Regex(
        """\buur\b|\bhours?\b|luisterplezier|plezier|gaming in|next-gen|beste|snelle|ideaal|ideal|perfect|ervaar|geniet|experience""",
        RegexOption.IGNORE_CASE,
    )
    private val SLOGAN =Regex("""prijs|levering|bezorgd|morgen in huis|gratis|kopen|bestel|garantie|price|delivery|livraison|prix""", RegexOption.IGNORE_CASE)
    private val AMAZON_PATH = Regex("""/(dp|gp/product)/[A-Z0-9]{10}(/|$)""")

    private val OTHER_COUNTRIES = setOf("es", "de", "it", "uk", "us", "gb", "at", "ch", "pl", "dk", "se", "pt")

    /** One page in two languages is one product: Coolblue's "/nl/product/954519/…" and "/en/product/954519/…". */
    private fun samePage(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return uri.host.orEmpty().lowercase().removePrefix("www.") +
            uri.path.orEmpty().replaceFirst(Regex("""^/(nl|fr|en|de)([-_](be|nl))?(?=/)""", RegexOption.IGNORE_CASE), "")
    }

    /** A Dutch site of a shop that has a Belgian one: "coolblue.nl" (coolblue.be), "amazon.nl" (amazon.com.be). */
    private fun hasBelgianSite(host: String): Boolean {
        if (!host.endsWith(".nl")) return false
        val site = GoogleShopping.siteOf(host) ?: return false
        return site != host && !DuckDuckGo.isForeignSite("https://$site", dutchToo = true)
    }

    /** Pages a web search finds at shops that sell nothing themselves: advice, news, a theme, the shop's offers. */
    private val NOT_A_PRODUCT = Regex(
        """/(l|c|advice|advies|conseils?|blog|news|nieuws|magazine|aboutus|about-us|themes?|onlineexpert[^/]*|""" +
            """images|aanbiedingen|b-stock-aanbiedingen|acties|promoties|promotions?|content|pages?|inspiratie|inspiration)(/|$)|""" +
            // A brand's or shop's overview of its models ("v15-v12-bekijk-alle-modellen", "shop-alle-modellen").
            """(alle-modellen|all-models|bekijk-alle|shop-alle|shop-all|tous-les-modeles)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether an address names one product: a product number (five digits or more, or Amazon's "/dp/B0…"), or a
     * name of three words or more ("sony-wh-ch520-zwart"). "/hoofdtelefoons/bluetooth" or "/en/playstation5" is a
     * category or a theme, whatever the search thought of it.
     */
    private fun namesOneProduct(path: String): Boolean {
        if (Regex("""\d{5,}""").containsMatchIn(path)) return true
        if (AMAZON_PATH.containsMatchIn(path)) return true
        return path.split('/').any { segment ->
            segment.substringBeforeLast('.').split('-', '_').count { it.isNotEmpty() } >= 3
        }
    }

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
            .filter { plain(it) != plain(typed) }
            .distinctBy { plain(it) }
            .take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 6

    /** Words as written alike: in small letters, without accents, Turkish "ı" as "i" (Google wrote "stofzuıger"). */
    private fun plain(text: String): String =
        java.text.Normalizer.normalize(text.trim().lowercase().replace('ı', 'i'), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

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
