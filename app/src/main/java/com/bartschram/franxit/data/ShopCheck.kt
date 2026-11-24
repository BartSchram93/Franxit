package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Matching
import com.bartschram.franxit.scrape.Scraper

/**
 * What a shop's own product page says, against what Google Shopping or Shoparize said about it: Google listed the
 * Switch 2 Zelda Edition at Coolblue for € 549, Coolblue's page said € 539 and sold out. Without anything Android,
 * so the cases are tested; Repository reads the pages.
 */
internal object ShopCheck {

    /**
     * A shop page's [price] (null when it shows none) and whether it has the product [inStock] (null: doesn't say).
     * [otherColour]: the page is of the same model in another colour (Galaxus's pink Selphy CP1500 for the black one),
     * so its listed price is no price of this product.
     */
    data class Seen(val price: Double?, val inStock: Boolean?, val otherColour: Boolean = false, val otherProduct: Boolean = false)

    /** The shop's link is no page of this product (a search, a list, another product): the shop is left out. */
    val ELSEWHERE = Seen(null, null, otherProduct = true)

    /** A page that says the product can't be bought now, without a price. */
    val SOLD_OUT = Seen(null, false)

    /**
     * What [page] says about the product called [name]; null when the page is clearly of another product (a link
     * that went elsewhere), so it changes nothing.
     */
    fun seen(page: Scraper.Result, name: String): Seen? {
        if (Matching.otherColour(page.name, name)) return Seen(null, null, otherColour = true)
        if (Matching.isOtherProduct(page.name, name) || Matching.addsBundleOrAccessory(page.name, name) || Matching.otherEdition(page.name, name)) return ELSEWHERE
        if (page.offers.isEmpty()) return null
        val inStock = when {
            page.offers.all { it.inStock == false } -> false
            page.offers.any { it.inStock == true } -> true
            else -> null
        }
        val price = (page.offers.filter { it.inStock != false }.ifEmpty { page.offers }).minOf { it.price }
        return Seen(price, inStock)
    }

    /**
     * [offer] as its shop's page shows it: sold out when the page says so, and the page's price when it is close
     * to the listed one (between half and one and a half times: farther off, the page is likely of another
     * version, and the listed price stays). Null when the page is of another colour: the offer isn't this product.
     */
    fun apply(offer: ShopOffer, seen: Seen?): ShopOffer? {
        if (seen == null) return offer
        // The shop's page is of another colour or another product: its price isn't this product's, the shop is left out.
        if (seen.otherColour || seen.otherProduct) return null
        var checked = offer
        seen.inStock?.let { checked = checked.copy(inStock = it) }
        seen.price?.takeIf { it > 0 && it / offer.price in 0.5..1.5 }?.let { checked = checked.copy(price = it) }
        return checked
    }

    /**
     * The shop's own address in a link through Google ("https://www.google.com/url?q=https://www.coolblue.be/…"), as
     * Google Shopping gives many: the shop's page is read, not Google's. Other links as they are.
     */
    fun direct(link: String?): String? {
        if (link == null) return null
        val host = link.substringAfter("//").substringBefore('/').lowercase()
        if (!host.contains("google.")) return link
        val query = link.substringAfter('?', "")
        val target = query.split('&').firstNotNullOfOrNull { part ->
            val (key, value) = part.substringBefore('=') to part.substringAfter('=', "")
            if (key in setOf("q", "url", "adurl")) runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull() else null
        }
        return target?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.takeIf { !it.substringAfter("//").substringBefore('/').contains("google.") } ?: link
    }

    /** Paths and parameters of a shop's search or list pages ("fr.fnac.be/se77634/…", "/zoeken?q=…"). */
    private val LISTING_PATH = Regex("""/(search|zoeken|zoek|recherche|rechercher|catalogsearch|s|se\d+|ia\d+|c\d+|categor(y|ie|ies)|collections?)(/|$)""", RegexOption.IGNORE_CASE)
    private val LISTING_QUERY = Regex("""[?&](q|k|query|search|searchtext|keyword|keywords|text|Search)=""")

    /**
     * Whether the shop's [link] is clearly no page of the product called [name]: a search or list page (Fnac's
     * "se77634" for a series of books), or a page its own address names as another product ("La-Legende-des-
     * Quatres" for a keyboard bench). A shop with such a link is left out: its price may be of anything.
     */
    fun isElsewhere(link: String, name: String): Boolean {
        val path = runCatching { java.net.URI(link).path }.getOrNull().orEmpty()
        if (LISTING_PATH.containsMatchIn(path) || LISTING_QUERY.containsMatchIn(link)) return true
        return Matching.titleFromLink(link)?.let { Matching.isOtherProduct(it, name) || Matching.addsBundleOrAccessory(it, name) || Matching.otherEdition(it, name) } == true
    }

    /**
     * Whether [link] is on the site of the shop called [shop] when that name is a site ("Amazon.com.be"): amazon.com's
     * page (the US store, not sold there) says nothing about Amazon.com.be. Shops without a known site: any link.
     */
    fun isShopsLink(link: String, shop: String): Boolean {
        val site = com.bartschram.franxit.scrape.GoogleShopping.siteOf(shop) ?: return true
        val host = link.substringAfter("//").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        return host == site || host.endsWith(".$site")
    }

    /**
     * How a shop's price was checked, shown under it: at the shop itself, or why not. Stored by shop (TrustedShops.key).
     */
    object Status {
        const val CHECKED = "checked"
        const val BLOCKED = "blocked"
        const val UNREADABLE = "unreadable"
        const val NOT_FOUND = "not_found"
        const val SEARCH_BLOCKED = "search_blocked"
    }

    /** The status after reading a shop's page: read ([seen] not null), or why it couldn't be ([error]). */
    fun status(seen: Seen?, error: String?): String = when {
        seen != null -> Status.CHECKED
        error?.contains("only allows browsers") == true -> Status.BLOCKED
        else -> Status.UNREADABLE
    }

    /**
     * Whether [url], where a comparison site's link for [shop] ended, is that shop's product page: on its site (by its
     * site, or its name: "kamera-express.be" for "Kamera Express"), for Belgium, not a search or a list.
     */
    fun isLandingOf(url: String, shop: String): Boolean {
        if (!isProductPage(url) || com.bartschram.franxit.scrape.DuckDuckGo.isOtherCountry(url)) return false
        val host = url.substringAfter("//").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        val onSite = if (com.bartschram.franxit.scrape.GoogleShopping.siteOf(shop) != null) isShopsLink(url, shop)
            else com.bartschram.franxit.scrape.DuckDuckGo.isShopsSite(host, shop)
        if (!onSite) return false
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        return path.trim('/').isNotEmpty() && !LISTING_PATH.containsMatchIn(path)
    }

    /** A link that is a shop's product page, not a search that leads to one. */
    fun isProductPage(link: String?): Boolean =
        link != null && (link.startsWith("https://") || link.startsWith("http://")) &&
            listOf("google.", "duckduckgo.", "bing.").none { it in link.substringBefore('?').substringAfter("//").substringBefore('/') }
}
