package com.bartschram.franxit.scrape

import com.bartschram.franxit.data.Shops
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Google Shopping through SerpApi's official API, for products vergelijk.be doesn't list. Each
 * check is one search of your SerpApi plan. A product's url is the Google Shopping search for it,
 * so it also opens in the browser.
 */
object GoogleShopping {

    private const val SEARCH = "https://www.google.be/search?tbm=shop&q="

    fun urlFor(query: String): String = SEARCH + URLEncoder.encode(query.trim(), "UTF-8")

    /** The big shops' own sites in Belgium, for [fallbackUrl]. */
    private val BIG_SHOPS = listOf(
        "bol" to "bol.com", "coolblue" to "coolblue.be", "fnac" to "fnac.be", "mediamarkt" to "mediamarkt.be",
        "krefel" to "krefel.be", "krëfel" to "krefel.be",
    )

    /**
     * Where to send you when the shop's own page isn't known: for a big shop, a Google search limited to its site
     * ("name site:bol.com"), whose first result is the shop's page for the product; else Google Shopping's search.
     * Not the shop's own search: bol.com's finds nothing for a long product name, not even the product itself.
     */
    fun fallbackUrl(query: String, shop: String): String {
        val s = shop.trim().lowercase()
        val domain = BIG_SHOPS.firstOrNull { (name, _) -> s == name || s.startsWith("$name.") || s.startsWith("$name ") }?.second
        // A shop known only by its name ("nintendo"): a plain Google search for the product at that shop, whose first
        // result is its page. Not Google Shopping's search, which lists other shops again.
        // A shop with its site as name ("Bax-shop.be"): a search on that site.
        val site = domain ?: s.removePrefix("www.").takeIf { Regex("""[a-z0-9-]+(\.[a-z0-9-]+)+""").matches(it) }
        return site?.let { siteSearchUrl(query, it) }
            ?: ("https://www.google.be/search?q=" + URLEncoder.encode("${query.trim()} ${shop.trim()}", "UTF-8"))
    }

    /** A Google search for [query] on the site [domain] only: its first result is that site's page for it. */
    fun siteSearchUrl(query: String, domain: String): String =
        "https://www.google.be/search?q=" + URLEncoder.encode("${query.trim()} site:${domain.trim().lowercase().removePrefix("www.")}", "UTF-8")

    /** The search behind a Google Shopping product url; null for any other url. */
    fun queryOf(url: String): String? = url.takeIf { it.startsWith(SEARCH) }?.let { URLDecoder.decode(it.removePrefix(SEARCH), "UTF-8") }

    /** [token]: Google's page for the best match, which lists more shops (see [addStores]). */
    data class Found(val result: Scraper.Result, val links: Map<String, String>, val token: String? = null, val term: String? = null)

    /**
     * The shops selling this product: a search, then Google's page for the best match, which
     * lists more shops, each with its own link (two searches; the second is skipped when it fails).
     */
    fun fetch(
        query: String, trusted: (String) -> String?, terms: List<String> = listOf(query), reference: Double? = null,
    ): Found {
        // Google's results depend a lot on the wording: "TCL Nxtpaper 14" gets only screen protectors,
        // "TCL Nxtpaper 14 kopen" gets the tablet. When a wording finds nothing, the next is tried
        // (at most three, as each is a search); other errors are no use retrying.
        val wordings = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_WORDINGS)
        var nothing: IOException? = null
        for (term in wordings.ifEmpty { listOf(query) }) {
            try {
                return search(term, query, trusted, reference).copy(term = term)
            } catch (e: IOException) {
                if (e.message != NOT_FOUND) throw e
                nothing = e
            }
        }
        throw nothing ?: IOException(NOT_FOUND)
    }

    private const val MAX_WORDINGS = 3

    /** Searches for [term]; the results must be [query] (they may be found under a fuller wording). */
    private fun search(term: String, query: String, trusted: (String) -> String?, reference: Double?): Found {
        val q = URLEncoder.encode(term, "UTF-8")
        val searched = parse(serp("/search?q=$q"), query, reference, trusted)
        val found = searched.token?.let { token ->
            val t = URLEncoder.encode(token, "UTF-8")
            runCatching { addStores(searched, serp("/stores?page_token=$t"), query, reference, trusted) }.getOrNull()
        } ?: searched
        if (found.result.offers.isEmpty()) throw IOException(NOT_FOUND)
        return resolveLinks(found)
    }

    private const val MAX_LOOKUPS = 3

    /**
     * Google's lookup tokens expire within minutes, so a shop's own page can't wait for the tap:
     * for the cheapest shops that still only have a token, the page is looked up now (a search each).
     * A shop whose page isn't found keeps its token.
     */
    private fun resolveLinks(found: Found): Found {
        val links = found.links.toMutableMap()
        found.result.offers.filter { links[it.seller]?.startsWith(TOKEN) == true }.take(MAX_LOOKUPS).forEach { offer ->
            val token = links.getValue(offer.seller).removePrefix(TOKEN)
            val page = runCatching {
                storeLink(token, offer.seller) { a, b -> com.bartschram.franxit.data.TrustedShops.same(a, b) }
            }.getOrNull()
            if (page != null) links[offer.seller] = page
        }
        return found.copy(links = links)
    }

    /**
     * Franxit's own search service (a Cloudflare Worker), which holds the SerpApi key and shares
     * answers between everyone: the app never has a key. A path like "/search?q=…".
     */
    const val PROXY = "https://franxit-zoeken.schram-bart.workers.dev"

    /** How long a search may take: Google Shopping through LiteScrape can take most of a minute. */
    private const val SEARCH_TIMEOUT_MS = 75_000

    /**
     * One request to the search service, with its errors in plain words. A search that takes too long is asked once
     * more: the service may have it by then (it keeps answers for a day).
     */
    private fun serp(path: String): String {
        val token = com.bartschram.franxit.BuildConfig.APP_TOKEN.takeIf { it.isNotBlank() }?.let { mapOf("X-Franxit-Token" to it) }.orEmpty()
        var last: IOException? = null
        repeat(2) {
            try {
                return Scraper.download(PROXY + path, "application/json", token, SEARCH_TIMEOUT_MS)
            } catch (e: java.net.SocketTimeoutException) {
                last = e
            } catch (e: IOException) {
                throw IOException(explain(e), e)
            }
        }
        throw IOException(explain(last ?: IOException()), last)
    }

    const val TOO_SLOW = "Google Shopping didn't answer in time, tried again at the next check"
    const val NO_CONNECTION = "No internet connection, tried again at the next check"

    /** A failed request to the search service in words ("timeout" was all a check said). */
    internal fun explain(e: IOException): String = when {
        e is java.net.SocketTimeoutException -> TOO_SLOW
        e is java.net.UnknownHostException || e is java.net.ConnectException -> NO_CONNECTION
        e.message == "HTTP 401" -> BAD_KEY
        e.message == "HTTP 429" -> NO_SEARCHES_LEFT
        e.message.orEmpty().matches(Regex("""HTTP 5\d\d""")) -> "Google Shopping had a problem, tried again at the next check"
        else -> e.message ?: "Google Shopping couldn't be reached"
    }

    const val NOT_FOUND = "Not found on Google Shopping"
    const val BAD_KEY = "Your Google Shopping key doesn't work"
    const val NO_SEARCHES_LEFT = "No Google Shopping searches left this month"

    /** A stored link that still has to be looked up: [TOKEN] + Google's product page token. */
    const val TOKEN = "serpapi-token:"

    /**
     * The shop's own page for a product, from Google's product page (SerpApi's immersive product
     * engine, one search): the store whose name or site is [shop]. Null when it isn't listed.
     */
    fun storeLink(token: String, shop: String, sameShop: (String, String) -> Boolean): String? {
        val t = URLEncoder.encode(token, "UTF-8")
        return storeLinkIn(serp("/stores?page_token=$t"), shop, sameShop)
    }

    internal fun storeLinkIn(json: String, shop: String, sameShop: (String, String) -> Boolean): String? {
        val stores = JSONObject(json).optJSONObject("product_results")?.optJSONArray("stores").objects()
        return stores.firstNotNullOfOrNull { s ->
            val link = storeUrl(s.optString("link")) ?: return@firstNotNullOfOrNull null
            link.takeIf { sameShop(s.optString("name"), shop) || storeSite(s)?.let { sameShop(it, shop) } == true }
        }
    }

    /**
     * The results that are this product (not accessories or other models), cheapest per shop.
     * [Found.links] goes from shop to its page, for the Buy button. Only [trusted] shops count, by
     * the name it gives them (see TrustedShops): Google also lists fake shops.
     */
    fun parse(json: String, query: String, trusted: (String) -> String? = { it }): Found = parse(json, query, null, trusted)

    /** As above, with [reference]: the price you know (vergelijk.be's), which the results are compared with before they are grouped. */
    fun parse(json: String, query: String, reference: Double?, trusted: (String) -> String? = { it }): Found {
        val root = JSONObject(json)
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            throw IOException(
                when {
                    "run out of searches" in it -> NO_SEARCHES_LEFT
                    "hasn't returned any results" in it -> NOT_FOUND
                    else -> "Google Shopping: $it"
                },
            )
        }
        val items = listOf("shopping_results", "inline_shopping_results")
            .flatMap { key -> root.optJSONArray(key).objects() }
        data class Item(
            val title: String, val shop: String, val price: Double, val link: String?, val image: String?, val site: String?,
            val token: String?,
        )
        val same = items.mapNotNull { o ->
            val title = o.optString("title").trim()
            val shop = o.optString("source").trim()
            val price = (o.opt("extracted_price") as? Number)?.toDouble()
                ?: o.optString("price").replace(Regex("[^0-9,.]"), "").replace(".", "").replace(",", ".").toDoubleOrNull()
            if (title.isBlank() || shop.isBlank() || price == null || price <= 0) return@mapNotNull null
            // New products only: Google marks used and refurbished offers; titles and shops say so too.
            if (o.optString("second_hand_condition").isNotBlank() || SECOND_HAND.containsMatchIn("$title $shop")) return@mapNotNull null
            // A shop selling in another currency (Gigstore: "AED 366", shown as € 88,99) isn't
            // for the Belgian market; Google only gives an alternative price for those.
            val currency = o.optJSONObject("alternative_price")?.optString("currency").orEmpty()
            if (currency.isNotBlank() && !currency.equals("EUR", ignoreCase = true)) return@mapNotNull null
            // The shop's own page when Google gives it; else a Google Shopping search for this product
            // at this shop. Google's product page ("product_link") often says it has no details.
            val direct = o.optString("link").takeIf { it.startsWith("http") && "google." !in it }
            // Without it, the token to look the shop's page up when you tap it (one search, see [storeLink]).
            val token = o.optString("immersive_product_page_token").takeIf { it.isNotBlank() }
            val link = direct ?: token?.let { TOKEN + it } ?: fallbackUrl(query, shop)
            val site = direct?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.")
            Item(title, shop, price, link, o.optString("thumbnail").takeIf { it.startsWith("http") }, site, token)
        }.filter {
            // With a price to compare with, a bundle or an accessory in the title is fine (the cheaper one counts); the price decides.
            Matching.isSameProduct(it.title, query, lenient = reference != null || isReliableShop(it.shop, it.site)) && !isForeignShop(it.shop) && !isMarketplace(it.shop) &&
                (!isOtherLanguage(it.title, it.link) || isReliableShop(it.shop, it.site))
        }
        if (same.isEmpty()) throw IOException(NOT_FOUND)
        // Google's page for the best match, whichever shop it came from: it lists the trusted ones too.
        val best = same.maxBy { Matching.score(it.title, query) }
        val token = same.filter { it.token != null }.maxByOrNull { Matching.score(it.title, query) }?.token
        val matching = same
            // A trusted shop by the site it links to ("Bax Music" is bax-shop.be), then named by that
            // site, or else by its name. Google Shopping Belgium only lists shops that ship here,
            // so a trusted shop's .nl, .fr or .de site (thomann.de/be) counts too.
            .mapNotNull { item ->
                when {
                    item.site != null && trusted(item.site) != null -> item.copy(shop = Shops.name(item.site))
                    else -> trusted(item.shop)?.let { item.copy(shop = it) }
                        ?: item.takeIf { isReliableShop(it.shop, it.site) }
                }
            }
            // Against the price you know (vergelijk.be's), before anything is grouped: a cover must not hide the tablet.
            .filter { item ->
                reference == null || com.bartschram.franxit.data.ExtraShopPrices.plausible(item.price, reference, isReliableShop(item.shop, item.site))
            }
            // Odd prices out first (too good to be true, import prices), by the middle price of the results.
            .let { withoutOutliers(it, { item -> isReliableShop(item.shop, item.site) }) { item -> item.price } }
            .let { all ->
                // The big shops may be dearer, but a price far below the other shops' is an accessory (a € 23 case
                // for a € 349 tablet), also when there are too few results for the middle price to say so.
                val others = all.filterNot { isReliableShop(it.shop, it.site) }.map { it.price }
                // Alone, the big shops' own middle price is all there is to measure against.
                val lowest = others.minOrNull() ?: all.map { it.price }.sorted().let { it.getOrNull(it.size / 2) }
                all.filter { item ->
                    lowest == null || !isReliableShop(item.shop, item.site) || item.price in lowest * 0.5..lowest * 2.0
                }
            }

        val cheapest = matching.groupBy { it.shop.lowercase() }.values.map { group -> group.minBy { it.price } }.sortedBy { it.price }
        return Found(
            Scraper.Result(best.title, cheapest.map { Scraper.Offer(it.shop, it.price, link = it.link) }, best.image),
            cheapest.mapNotNull { item -> item.link?.let { item.shop to it } }.toMap(),
            token,
        )
    }

    /**
     * Adds the shops on Google's page for the product ("stores"): each with its own price and link.
     * Google groups them under this one product, so titles aren't matched again (bol.com calls the
     * KB200B "HCKB-200B"); otherwise the same rules as the search: new, trusted shops, no odd prices.
     */
    internal fun addStores(found: Found, json: String, query: String, trusted: (String) -> String?): Found =
        addStores(found, json, query, null, trusted)

    internal fun addStores(found: Found, json: String, query: String, reference: Double?, trusted: (String) -> String?): Found {
        val stores = JSONObject(json).optJSONObject("product_results")?.optJSONArray("stores").objects()
        val extra = stores.mapNotNull { s ->
            val name = s.optString("name").trim()
            val price = (s.opt("extracted_price") as? Number)?.toDouble() ?: return@mapNotNull null
            val link = storeUrl(s.optString("link")) ?: return@mapNotNull null
            val title = s.optString("title")
            // Google groups colours and variants of one model together: the shop's own title tells them apart.
            if (title.isNotBlank() && Matching.isOtherVariant(title, query)) return@mapNotNull null
            // Google also groups related products under one: a shop's title (or, without one, its page's address)
            // that is clearly another product is left out.
            if (Matching.isOtherProduct(title.ifBlank { Matching.titleFromLink(link).orEmpty() }, query)) return@mapNotNull null
            if (name.isBlank() || price <= 0 || isForeignShop(name) || isMarketplace(name)) return@mapNotNull null
            if (SECOND_HAND.containsMatchIn("$name $title ${s.optJSONArray("details_and_offers")}")) return@mapNotNull null
            // Dutch or English pages only (bol.com's French page is left out; its Dutch one is listed too).
            val site = storeSite(s)
            val reliable = isReliableShop(name, site)
            val otherLanguage = isOtherLanguage(title, link)
            if (otherLanguage && !reliable) return@mapNotNull null
            // Trusted by the lists, or by what Google's shoppers say about the shop (stars and number
            // of reviews): a fake shop rarely has plenty of both.
            val shown = site?.takeIf { trusted(it) != null }?.let(Shops::name) ?: trusted(name)
                ?: name.takeIf { reliable || (wellReviewed(s) && site != null && sellsToBelgium(site)) } ?: return@mapNotNull null
            // Google's store details sometimes say it is sold out; otherwise the stock is unknown (null).
            val soldOut = OUT_OF_STOCK.containsMatchIn(s.optJSONArray("details_and_offers")?.toString().orEmpty())
            // Pages that look Dutch or English by their own address or title come first on the same price.
            Scraper.Offer(shown, price, inStock = if (soldOut) false else null, link = link) to isOtherLanguage(title, link, amazonOk = false)
        }
            // A reliable shop's page in another language only counts when no Dutch or English one is as cheap.
            .sortedBy { it.second }.map { it.first }
            .filter { reference == null || com.bartschram.franxit.data.ExtraShopPrices.plausible(it.price, reference, isReliableShop(it.seller, null)) }
        // The page's own links first: on the same price, its direct link wins.
        val offers = withoutOutliers(extra + found.result.offers, { isReliableShop(it.seller, null) }) { it.price }
            .groupBy { it.seller.lowercase() }.values.map { group -> group.minBy { it.price } }.sortedBy { it.price }
        val links = offers.mapNotNull { o -> (o.link ?: found.links[o.seller])?.let { o.seller to it } }.toMap()
        return Found(found.result.copy(offers = offers), links, found.token)
    }

    /**
     * A page in another language than Dutch or English: its address has a language folder
     * ("/fr/", "/de/", "/be/fr/", "/-/fr/"), or its title has words or letters of another language
     * ("Tablette", "pour", "Kopfhörer", "ç", "ß"). A brand like "Le Creuset" alone doesn't count.
     */
    internal fun isOtherLanguage(title: String, link: String?, frenchToo: Boolean = allowFrench, amazonOk: Boolean = true): Boolean {
        val path = link?.takeIf { it.startsWith("http") && "google." !in it }
            ?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val folders = path?.path.orEmpty().lowercase().split('/').filter { it.isNotEmpty() }
        // Amazon shows one page in any language: its title says nothing about the page you get.
        if (amazonOk && path?.host?.removePrefix("www.") in setOf("amazon.com.be", "amazon.nl")) return false
        if (folders.any { it in OTHER_LANGUAGE_FOLDERS && !(frenchToo && it in FRENCH_FOLDERS) }) return true
        val languages = if (frenchToo) "de|es|it|pt|pl" else "fr|de|es|it|pt|pl"
        if (Regex("""[?&](lang|language|locale)=($languages)""").containsMatchIn(path?.rawQuery?.let { "?$it" }.orEmpty().lowercase())) return true
        val text = title.lowercase()
        if (Regex(if (frenchToo) "[ßñãõ]" else "[çèêàâôûßñãõ]").containsMatchIn(text)) return true
        val words = text.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.any { it in OTHER_LANGUAGE_WORDS && !(frenchToo && it in FRENCH_WORDS) }) return true
        return !frenchToo && words.count { it in OTHER_LANGUAGE_SMALL_WORDS } >= 2
    }

    /** With the app in French, French pages are fine too; set from the app's language before searching. */
    @Volatile var allowFrench: Boolean = false

    private val FRENCH_FOLDERS = setOf("fr", "fr-be", "fr_be", "be-fr", "be_fr", "fr-fr", "fr_fr")
    private val FRENCH_WORDS = setOf(
        "pour", "avec", "tablette", "étui", "coque", "écouteurs", "écran", "génération", "noir", "blanc", "gris",
        "livraison", "neuf", "reconditionné", "clavier", "banc", "chargeur", "casque", "housse", "sans-fil", "enceinte",
    )

    private val OTHER_LANGUAGE_FOLDERS = setOf(
        "fr", "fr-be", "fr_be", "be-fr", "be_fr", "fr-fr", "fr_fr", "de", "de-de", "de_de", "de-be", "es", "es-es", "it", "it-it", "pt", "pl",
    )

    /** Words that only appear in French, German, Spanish or Italian product titles. */
    private val OTHER_LANGUAGE_WORDS = setOf(
        // French
        "pour", "avec", "tablette", "étui", "coque", "écouteurs", "écran", "génération", "noir", "blanc", "gris",
        "livraison", "neuf", "reconditionné", "clavier", "banc", "chargeur", "casque", "housse", "sans-fil", "enceinte",
        // German
        "für", "mit", "und", "kopfhörer", "schwarz", "weiß", "weiss", "kabellose", "kabellos", "ohne", "hülle", "tastatur",
        "ladegerät", "neu",
        // Spanish, Italian, Portuguese
        "con", "para", "negro", "blanco", "auriculares", "inalámbricos", "funda", "nuevo", "nero", "bianco", "cuffie",
        "nuovo", "custodia", "senza", "preto", "novo",
    )

    /** Short French words that can be part of a brand ("Le Creuset"): two of them make a French title. */
    private val OTHER_LANGUAGE_SMALL_WORDS = setOf("le", "la", "les", "des", "du", "et", "une", "sans")

    /**
     * A shop's site for the Belgian market: a .be, .nl or international address. A shop on a
     * French, German… address that only Google's reviews vouch for is left out (it rarely ships here).
     */
    internal fun sellsToBelgium(site: String): Boolean =
        site.substringAfterLast('.').lowercase() in setOf("be", "nl", "com", "eu", "net", "shop", "store", "online")

    /** Google's reviews of the shop itself: at least 4 stars from at least 50 reviews. */
    internal fun wellReviewed(store: JSONObject): Boolean =
        store.optDouble("rating", 0.0) >= MIN_STARS && store.optInt("reviews", 0) >= MIN_REVIEWS

    private const val MIN_STARS = 4.0
    private const val MIN_REVIEWS = 50

    /** A store's link; some go through Google's redirect ("/goto?url=…"), which opens the shop too. */
    private fun storeUrl(raw: String): String? = when {
        raw.startsWith("/") -> "https://www.google.com$raw"
        raw.startsWith("http") -> raw
        else -> null
    }

    /** A store's site: from its link, or from its logo's address when the link goes through Google. */
    private fun storeSite(store: JSONObject): String? {
        fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
        val link = store.optString("link")
        if (link.startsWith("http") && "google." !in link) return host(link)
        val logo = store.optString("logo")
        val url = Regex("[?&]url=([^&]+)").find(logo)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        return url?.let(::host)
    }

    /** "eBay - sobreruedas-spain": a seller named after another country. */
    private val OTHER_COUNTRY = Regex(
        """\b(spain|espa[nñ]a|germany|deutschland|ital(y|ia)|france|nederland|netherlands|holland|uk|united kingdom|usa|china|hong kong|dubai|uae|poland|polska)\b""",
        RegexOption.IGNORE_CASE,
    )

    internal fun isForeignShop(shop: String): Boolean = OTHER_COUNTRY.containsMatchIn(shop)

    /** Marketplaces and import sites: other sellers, often abroad or used, at import prices (U-buy). */
    private val MARKETPLACES = listOf("ebay", "ubuy", "u-buy", "aliexpress", "temu", "wish", "fruugo", "desertcart", "joom")

    internal fun isMarketplace(shop: String): Boolean = shop.lowercase().let { s -> MARKETPLACES.any { it in s } }

    private val OUT_OF_STOCK = Regex(
        // Only clear "sold out" wording: "not available for pickup" or "unavailable in store" is no reason to hide a shop.
        """\b(out of stock|sold out|uitverkocht|niet op voorraad|[ée]puis[ée]|rupture de stock)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SECOND_HAND = Regex(
        """\b(used|refurbished|renewed|reconditioned|second.?hand|tweedehands|2e hands|gebruikt|occasion|refurb|seconde main|d'occasion|reconditionn[ée])\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Prices far from the middle of the results are odd ones out, not alternatives: over 1.5× (import
     * prices), or under half (another item, or too good to be true: € 37,99 for € 219 earbuds).
     */
    private fun <T> withoutOutliers(items: List<T>, reliable: (T) -> Boolean, price: (T) -> Double): List<T> {
        if (items.size < 3) return items
        val sorted = items.map(price).sorted()
        val median = sorted[sorted.size / 2]
        // Reliable shops may be dearer, but not far cheaper (that is an accessory, like a cover).
        return items.filter { if (reliable(it)) price(it) in median * 0.5..median * 2.0 else price(it) in median * 0.5..median * 1.5 }
    }

    /**
     * Big, reliable shops: shown whatever the page's language, and up to twice the usual price (a dearer
     * bol.com is still worth showing), but not far below it. Matched by the shop's name or its site.
     */
    private val RELIABLE_SHOPS = listOf("bol.com", "coolblue", "fnac", "mediamarkt", "media markt", "krefel", "krëfel")

    internal fun isReliableShop(name: String, site: String?): Boolean =
        name.trim().equals("bol", ignoreCase = true) ||
            "$name ${site.orEmpty()}".lowercase().let { text -> RELIABLE_SHOPS.any { it in text } }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
