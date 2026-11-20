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
     * The shops to show: every shop vergelijk.be (or the page you added) gives stays; Google Shopping
     * adds the shops that aren't there yet. For a shop both give, Google's price counts: it is read today
     * from the shop's own feed, while vergelijk.be's can be old or be for another pack. One offer per shop.
     * Sold-out shops are left out unless every shop is sold out.
     */
    fun merge(own: List<ShopOffer>, extra: List<ShopOffer>): List<ShopOffer> {
        val ownTagged = own.map { false to it }
        val extraTagged = extra.map { true to it }
        return (ownTagged + extraTagged).let { all -> all.filter { it.second.inStock != false }.ifEmpty { all } }
            .groupBy { Shops.name(it.second.seller).lowercase() }.values
            .map { same ->
                val google = same.filter { it.first }.map { it.second }
                if (google.isNotEmpty()) google.minBy { it.price } else same.map { it.second }.minBy { it.price }
            }
            .sortedBy { it.price }
    }
}
