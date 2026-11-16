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

    /** The search behind a Google Shopping product url; null for any other url. */
    fun queryOf(url: String): String? = url.takeIf { it.startsWith(SEARCH) }?.let { URLDecoder.decode(it.removePrefix(SEARCH), "UTF-8") }

    /** [token]: Google's page for the best match, which lists more shops (see [addStores]). */
    data class Found(val result: Scraper.Result, val links: Map<String, String>, val token: String? = null, val term: String? = null)

    /**
     * The shops selling this product: a search, then Google's page for the best match, which
     * lists more shops, each with its own link (two searches; the second is skipped when it fails).
     */
    fun fetch(query: String, key: String, trusted: (String) -> String?, terms: List<String> = listOf(query)): Found {
        // Google's results depend a lot on the wording: "TCL Nxtpaper 14" gets only screen protectors,
        // "TCL Nxtpaper 14 kopen" gets the tablet. When a wording finds nothing, the next is tried
        // (at most three, as each is a search); other errors are no use retrying.
        val wordings = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_WORDINGS)
        var nothing: IOException? = null
        for (term in wordings.ifEmpty { listOf(query) }) {
            try {
                return search(term, query, key, trusted).copy(term = term)
            } catch (e: IOException) {
                if (e.message != NOT_FOUND) throw e
                nothing = e
            }
        }
        throw nothing ?: IOException(NOT_FOUND)
    }

    private const val MAX_WORDINGS = 3

    /** Searches for [term]; the results must be [query] (they may be found under a fuller wording). */
    private fun search(term: String, query: String, key: String, trusted: (String) -> String?): Found {
        val q = URLEncoder.encode(term, "UTF-8")
        val searched = parse(serp("engine=google_shopping&q=$q&gl=be&hl=nl&google_domain=google.be", key), query, trusted)
        val found = searched.token?.let { token ->
            val t = URLEncoder.encode(token, "UTF-8")
            runCatching { addStores(searched, serp("engine=google_immersive_product&page_token=$t&more_stores=true", key), query, trusted) }.getOrNull()
        } ?: searched
        if (found.result.offers.isEmpty()) throw IOException(NOT_FOUND)
        return found
    }

    /** One SerpApi request, with its errors in plain words. */
    private fun serp(params: String, key: String): String {
        val k = URLEncoder.encode(key, "UTF-8")
        try {
            return Scraper.download("https://serpapi.com/search.json?$params&api_key=$k", "application/json")
        } catch (e: IOException) {
            throw when (e.message) {
                "HTTP 401" -> IOException(BAD_KEY)
                "HTTP 429" -> IOException(NO_SEARCHES_LEFT)
                else -> e
            }
        }
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
    fun storeLink(token: String, shop: String, key: String, sameShop: (String, String) -> Boolean): String? {
        val t = URLEncoder.encode(token, "UTF-8")
        return storeLinkIn(serp("engine=google_immersive_product&page_token=$t&more_stores=true", key), shop, sameShop)
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
    fun parse(json: String, query: String, trusted: (String) -> String? = { it }): Found {
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
            val link = direct ?: token?.let { TOKEN + it } ?: urlFor("$query $shop")
            val site = direct?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.")
            Item(title, shop, price, link, o.optString("thumbnail").takeIf { it.startsWith("http") }, site, token)
        }.filter { Matching.isSameProduct(it.title, query) && !isForeignShop(it.shop) && !isMarketplace(it.shop) }
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
                }
            }
            .let { withoutOutliers(it) { item -> item.price } }

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
    internal fun addStores(found: Found, json: String, query: String, trusted: (String) -> String?): Found {
        val stores = JSONObject(json).optJSONObject("product_results")?.optJSONArray("stores").objects()
        val extra = stores.mapNotNull { s ->
            val name = s.optString("name").trim()
            val price = (s.opt("extracted_price") as? Number)?.toDouble() ?: return@mapNotNull null
            val link = storeUrl(s.optString("link")) ?: return@mapNotNull null
            val title = s.optString("title")
            if (name.isBlank() || price <= 0 || isForeignShop(name) || isMarketplace(name)) return@mapNotNull null
            if (SECOND_HAND.containsMatchIn("$name $title ${s.optJSONArray("details_and_offers")}")) return@mapNotNull null
            val site = storeSite(s)
            // Trusted by the lists, or by what Google's shoppers say about the shop (stars and number
            // of reviews): a fake shop rarely has plenty of both.
            val shown = site?.takeIf { trusted(it) != null }?.let(Shops::name) ?: trusted(name)
                ?: name.takeIf { wellReviewed(s) } ?: return@mapNotNull null
            Scraper.Offer(shown, price, link = link)
        }
        // The page's own links first: on the same price, its direct link wins.
        val offers = withoutOutliers(extra + found.result.offers) { it.price }
            .groupBy { it.seller.lowercase() }.values.map { group -> group.minBy { it.price } }.sortedBy { it.price }
        val links = offers.mapNotNull { o -> (o.link ?: found.links[o.seller])?.let { o.seller to it } }.toMap()
        return Found(found.result.copy(offers = offers), links, found.token)
    }

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

    private val SECOND_HAND = Regex(
        """\b(used|refurbished|renewed|reconditioned|second.?hand|tweedehands|2e hands|gebruikt|occasion|refurb|seconde main|d'occasion|reconditionn[ée])\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Prices far from the middle of the results are odd ones out, not alternatives: over 1.5× (import
     * prices), or under half (another item, or too good to be true: € 37,99 for € 219 earbuds).
     */
    private fun <T> withoutOutliers(items: List<T>, price: (T) -> Double): List<T> {
        if (items.size < 3) return items
        val sorted = items.map(price).sorted()
        val median = sorted[sorted.size / 2]
        return items.filter { price(it) in median * 0.5..median * 1.5 }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
