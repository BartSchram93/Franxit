package com.bartschram.franxit.data

/**
 * Which Google Shopping prices count as alternatives, compared with vergelijk.be's cheapest price:
 * far above it is no alternative (U-buy at € 620 for a € 361 tablet, 172%), under half of it is another
 * item or too good to be true (€ 37,99 for € 219 earbuds). Up to 150% counts: shops like bol.com are often
 * somewhat dearer and still worth showing (€ 437,52 against a cheapest of € 349,00 is 125%).
 */
internal object ExtraShopPrices {
    const val LOWEST = 0.5
    const val HIGHEST = 1.5

    /** Reliable shops (bol.com, Coolblue…) may be dearer, but a price far below is an accessory, not the product (a € 23 cover). */
    const val RELIABLE_LOWEST = 0.5
    const val RELIABLE_HIGHEST = 2.0

    /** True when [price] is a plausible alternative; always true when there is no cheapest price to compare with. */
    fun plausible(price: Double, cheapest: Double?, reliable: Boolean = false): Boolean = when {
        cheapest == null -> true
        reliable -> price in cheapest * RELIABLE_LOWEST..cheapest * RELIABLE_HIGHEST
        else -> price in cheapest * LOWEST..cheapest * HIGHEST
    }
}
