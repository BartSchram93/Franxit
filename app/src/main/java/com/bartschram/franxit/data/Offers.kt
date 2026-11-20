package com.bartschram.franxit.data

object Offers {

    /**
     * The cheapest offer you can buy: any shop not known to be out of stock, the cheapest of them
     * (a shop whose stock isn't known counts as having it); only when every shop is out of stock,
     * the cheapest of those. Null when there are none.
     */
    fun cheapest(offers: List<ShopOffer>): ShopOffer? = best(offers) { it.price }

    /** Like [cheapest], by any price measure (e.g. what you pay after your discounts). */
    fun best(offers: List<ShopOffer>, price: (ShopOffer) -> Double): ShopOffer? =
        offers.filter { it.inStock != false }.minByOrNull(price)
            ?: offers.minByOrNull(price)

    /**
     * [secondary] without the shops [primary] already has: where two sources list the same shop, the first
     * source's offer counts, not the cheaper one (Google Shopping before Shoparize). A shop is the same shop
     * under its different spellings ("MediaMarkt.be", "Mediamarkt").
     */
    fun withoutShopsOf(secondary: List<ShopOffer>, primary: List<ShopOffer>): List<ShopOffer> {
        val taken = primary.map { TrustedShops.key(it.seller) }.toSet()
        return secondary.filter { TrustedShops.key(it.seller) !in taken }
    }

    /**
     * The shops to show: every shop vergelijk.be (or the page you added) gives stays; Google Shopping
     * only adds shops that aren't there yet, or lowers the price of one that is. One offer per shop,
     * the cheaper. Sold-out shops are left out unless every shop is sold out.
     */
    fun merge(own: List<ShopOffer>, extra: List<ShopOffer>): List<ShopOffer> =
        (own + extra).let { all -> all.filter { it.inStock != false }.ifEmpty { all } }
            .groupBy { Shops.name(it.seller).lowercase() }.values
            .map { same -> same.minBy { it.price } }
            .sortedBy { it.price }
}
