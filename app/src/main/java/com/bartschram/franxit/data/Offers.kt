package com.bartschram.franxit.data

object Offers {

    /** The cheapest offer you can buy, preferring ones in stock. Null when there are none. */
    fun cheapest(offers: List<ShopOffer>): ShopOffer? =
        offers.filter { it.inStock != false }.minByOrNull { it.price } ?: offers.minByOrNull { it.price }
}
