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
     * For a shop Shoparize [listed] for this product: its own product page ([cleanLink], from Shoparize's link without
     * tracking), else the shop's own search for [productName]. Shoparize's own links are never opened. Null for a shop
     * Shoparize didn't list.
     */
    fun fromShoparize(listed: List<ShopOffer>, shop: String, cleanLink: String?, productName: String): String? {
        if (listed.none { TrustedShops.same(it.seller, shop) }) return null
        return cleanLink ?: Shoparize.shopSearchUrl(productName, shop)
    }
}
