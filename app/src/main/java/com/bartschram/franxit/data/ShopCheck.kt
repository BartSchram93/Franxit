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
    data class Seen(val price: Double?, val inStock: Boolean?, val otherColour: Boolean = false)

    /** A page that says the product can't be bought now, without a price. */
    val SOLD_OUT = Seen(null, false)

    /**
     * What [page] says about the product called [name]; null when the page is clearly of another product (a link
     * that went elsewhere), so it changes nothing.
     */
    fun seen(page: Scraper.Result, name: String): Seen? {
        if (Matching.otherColour(page.name, name)) return Seen(null, null, otherColour = true)
        if (page.offers.isEmpty() || Matching.isOtherProduct(page.name, name)) return null
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
        // The shop's page is of another colour: its price isn't this product's, the shop is left out.
        if (seen.otherColour) return null
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

    /** A link that is a shop's product page, not a search that leads to one. */
    fun isProductPage(link: String?): Boolean =
        link != null && (link.startsWith("https://") || link.startsWith("http://")) &&
            listOf("google.", "duckduckgo.", "bing.").none { it in link.substringBefore('?').substringAfter("//").substringBefore('/') }
}
