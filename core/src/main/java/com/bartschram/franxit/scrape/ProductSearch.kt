package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.ShopCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

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
    const val LIMIT = "You've reached your daily limit of 20 searches. You can search again tomorrow."
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
            // ... or by its first folder on a site that isn't Belgian ("asus.com/us/…").
            !host.endsWith(".be") && path.trim('/').substringBefore('/').lowercase() in OTHER_COUNTRIES -> null
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
        // The name before the shop's part, however the page separates them ("|", "｜", "·").
        var t = title.split(Regex("""\s+[|｜·]\s+""")).first().trim()
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

    /**
     * The product's photo on its page [html] at [url], for a found page without one (the search doesn't always give
     * Coolblue's or Amazon's): Amazon's main picture, else the page's product data or its preview picture. Null when
     * the page has none, or only the shop's logo.
     */
    fun photoIn(html: String, url: String): String? {
        // Amazon's largest picture first, then the one it shows.
        val amazon = listOf(""""hiRes"\s*:\s*"(https://[^"]+)"""", """data-old-hires="(https://[^"]+)"""", """id="landingImage"[^>]*src="(https://[^"]+)"""")
            .firstNotNullOfOrNull { Regex(it).find(html)?.groupValues?.get(1) }
        val page = amazon ?: runCatching { Scraper.fromPage(html, url).image }.getOrNull()
            ?: Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*content=["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        val photo = page?.replace("&amp;", "&")?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
        return photo.takeIf { it.startsWith("https://") && !LOGO.containsMatchIn(photo) }
    }

    /** A shop's own picture rather than a product's ("/media/seo/default/Molecule.jpg", "…/logo.png"). */
    private val LOGO = Regex("""logo|/seo/default/|favicon|placeholder""", RegexOption.IGNORE_CASE)

    /**
     * Each product once: the same product at another shop says nothing new (tracking it finds all its shops). The
     * same is the same words, whatever the brand's place, "console" or how a size is written ("SONY PlayStation 5 Pro
     * 2 TB", "PlayStation 5 Pro", "Sony PlayStation 5 Pro 2TB"); another colour, size, edition or bundle stays.
     * The first, as the search ranked them, is kept.
     */
    fun oneOfEach(products: List<Product>): List<Product> {
        val kept = mutableListOf<Product>()
        for (p in products) {
            if (kept.none { same(it.title, p.title) }) kept += p
        }
        return kept
    }

    private fun same(a: String, b: String): Boolean {
        if (Matching.otherColour(a, b) || Matching.otherSpecs(a, b) || Matching.otherTier(a, b) || Matching.otherTier(b, a) ||
            Matching.otherEdition(a, b) || Matching.otherEdition(b, a) || Matching.otherModelCode(a, b) || Matching.otherModelCode(b, a)
        ) return false
        return words(a) == words(b)
    }

    /** A name's words that tell products apart: not its brand's, "console" or a size (compared above). */
    private fun words(title: String): Set<String> {
        val t = title.lowercase()
            .replace(Regex("""\bps(\d)\b"""), "playstation $1")
            .replace(Regex("""(\d+)\s*(gb|tb)\b"""), "")
        return t.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() && it !in NOT_TELLING }.toSet()
    }

    private val NOT_TELLING = setOf("sony", "apple", "samsung", "nintendo", "microsoft", "console", "consola", "spelconsole", "spelcomputer", "the", "de", "het")

    /**
     * Only results that name what you asked for: a model code in your words ("f100", "dg-335", "wh-ch520") must be in
     * the result's name or address. The search can find a near look-alike ("ASUS TUF F15" for "HJC F100"): no
     * result is better than another product.
     */
    fun relevant(products: List<Product>, query: String): List<Product> {
        val codes = query.lowercase().split(Regex("""\s+""")).map { it.trim(',', '.', '"', '\'') }
            .filter { w -> w.length >= 3 && w.any(Char::isDigit) && w.any(Char::isLetter) }
            .map { it.filter(Char::isLetterOrDigit) }
        if (codes.isEmpty()) return products
        return products.filter { p ->
            val text = (p.title + " " + p.url).lowercase().filter(Char::isLetterOrDigit)
            codes.all { it in text }
        }
    }

    /** Products for [query], product pages only, best first as the service ranks them. */
    fun search(query: String): List<Product> {
        val q = clean(query) ?: return emptyList()
        return relevant(productPages(parse(post("/api/product-search", Request(q).toJson())).products), q)
    }

    private fun post(path: String, body: String): String = request(path, body)

    private fun request(path: String, body: String?): String {
        val conn = java.net.URL(GoogleShopping.PROXY + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            CoreConfig.appToken.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Franxit-Token", it) }
            // The app's anonymous id: the service counts each user's searches a day with it.
            CoreConfig.installId.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Franxit-Install", it) }
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
