package com.bartschram.franxit.data

object Offers {

    /**
     * The cheapest offer you can buy: one known to be in stock first, then one whose stock is unknown
     * (Google Shopping's shops don't say), and only then one that is out of stock. Null when there are none.
     */
    fun cheapest(offers: List<ShopOffer>): ShopOffer? = best(offers) { it.price }

    /** Like [cheapest], by any price measure (e.g. what you pay after your discounts). */
    fun best(offers: List<ShopOffer>, price: (ShopOffer) -> Double): ShopOffer? =
        offers.filter { it.inStock == true }.minByOrNull(price)
            ?: offers.filter { it.inStock == null }.minByOrNull(price)
            ?: offers.minByOrNull(price)
}
