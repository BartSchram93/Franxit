package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Scraper
import com.bartschram.franxit.scrape.Shoparize
import java.net.URI

/**
 * Which page the Buy button opens, without anything Android, so the cases that went wrong are tested
 * ("Buy at Gladi.nl" opened bol.com: vergelijk.be's cheapest instead of the button's shop).
 * Repository reads the pages and saved links; the choices are here.
 */
internal object BuyLink {

    /** The shop the button names: the one asked for, else the cheapest in the app. */
    fun shop(seller: String?, offers: List<ShopOffer>): String? = seller ?: Offers.cheapest(offers)?.seller

    /** A shop's own page you added (fitshop.be): that page, when the button's shop is that page's shop (or none is named). */
    fun ownPage(productUrl: String, shop: String?): String? {
        val host = runCatching { URI(productUrl).host.removePrefix("www.") }.getOrDefault("")
        return productUrl.takeIf { shop == null || TrustedShops.key(shop) == TrustedShops.key(host) }
    }

    /**
     * From the product's vergelijk.be [page]: the link of [shop]'s offer, the button's shop and not vergelijk.be's
     * cheapest. When vergelijk.be doesn't list that shop, or has no link for it: [shoparize] (the shop's page or
     * search through Shoparize). Without a shop named: the page's cheapest.
     */
    fun fromPage(page: Scraper.Result, shop: String?, shoparize: (String) -> String?): String? {
        if (shop == null) return page.cheapest.link
        val listed = page.offers.filter { TrustedShops.same(it.seller, shop) }
        return listed.minByOrNull { it.price }?.link ?: shoparize(shop)
    }

    /**
     * What a shop's site is searched with: Shoparize's title for the product when it says more than the app's name
     * ("999 Games Patchwork" for "Patchwork", which on its own finds poufs in patchwork fabric), else that name.
     */
    fun searchName(name: String, shoparizeTitle: String?): String {
        val title = shoparizeTitle?.trim().orEmpty()
        if (title.isEmpty() || title.length > 80) return name
        val words = { s: String -> s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet() }
        // Only a title that is the same product in a few more words (a brand, a publisher): all of the name's words
        // in it, at most two more, and no accessory or other edition. "EtuiTab - Szkło Hartowane - TCL NXTPAPER 14",
        // a screen protector, made the Buy button search Amazon for that.
        val extra = words(title) - words(name)
        // Nor a colour the name doesn't have ("Canon Selphy CP1500 Pink" searched Fnac for the pink one).
        val same = words(title).containsAll(words(name)) && extra.size in 1..2 &&
            com.bartschram.franxit.scrape.Matching.colourWords(extra.joinToString(" ")).isEmpty() &&
            !com.bartschram.franxit.scrape.Matching.addsBundleOrAccessory(title, name) &&
            !com.bartschram.franxit.scrape.Matching.otherEdition(title, name)
        return if (same) title else name
    }

    /**
     * The page [found] for the button's [shop], else a search for the product limited to that shop's site: never
     * another shop's page. "Buy at Bol.com" opened Multibazar, the page the product was added from, when no page
     * of bol.com's was known. Null only when no shop is named.
     */
    fun orSearch(found: String?, shop: String?, productName: String): String? =
        found ?: shop?.let { Shoparize.shopSearchUrl(productName, it) }

    /**
     * For a shop Shoparize [listed] for this product: its own product page ([cleanLink], from Shoparize's link without
     * tracking), else the shop's own search for [productName]. Shoparize's own links are never opened. Null for a shop
     * Shoparize didn't list.
     */
    fun fromShoparize(listed: List<ShopOffer>, shop: String, cleanLink: String?, productName: String): String? {
        if (listed.none { TrustedShops.same(it.seller, shop) }) return null
        return cleanLink ?: Shoparize.shopSearchUrl(productName, shop)
    }
}
