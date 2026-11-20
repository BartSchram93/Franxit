package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.GoogleShopping
import com.bartschram.franxit.scrape.Scraper

/**
 * How a product's shops come together from its sources, without anything Android, so it can be tested with
 * real cases (Dash, Interprox, Pampers). Repository reads the sources and stores the result; the choices are here.
 */
internal object ShopPrices {

    /**
     * The shops to show, one offer each.
     * - [own]: the product's own page (vergelijk.be, or a shop page you added); for a Google Shopping product
     *   ([googleProduct]) its own shops are Google's.
     * - [google], [shoparize]: the extra sources. Only prices plausible next to [own]'s cheapest count
     *   (see ExtraShopPrices): a € 23 case is no € 349 tablet.
     * - An offer of [own] far from what the other sources ask is another pack (Amazon at € 54,29 where the others
     *   ask € 5 to € 8 for 6 ragers) and is left out.
     * - Then per shop: a price two sources agree on wins, else Google Shopping, then Shoparize, then [own]
     *   (see Offers.pick). Sold-out shops aren't shown, unless every shop is sold out.
     */
    fun combine(own: List<ShopOffer>, google: List<ShopOffer>, shoparize: List<ShopOffer>, googleProduct: Boolean): List<ShopOffer> {
        val ownCheapest = own.minOfOrNull { it.price }
        val plausible = { offer: ShopOffer -> ExtraShopPrices.plausible(offer.price, ownCheapest, GoogleShopping.isReliableShop(offer.seller, null)) }
        val first = google.filter(plausible)
        val second = shoparize.filter(plausible)
        val mine = Offers.withoutOutliers(own, first + second)
        return Offers.pick(if (googleProduct) listOf(mine, second) else listOf(first, second, mine))
    }

    /** Shoparize's shops as the app's offers: marketplaces left out, named as the app names shops, stock not said. */
    fun fromShoparize(found: List<Scraper.Offer>): List<ShopOffer> =
        found.filterNot { GoogleShopping.isMarketplace(it.seller) }.map { ShopOffer(Shops.name(it.seller), it.price, null) }
}
