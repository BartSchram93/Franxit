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
        offers.filter { it.inStock != false }.minWithOrNull(order(price))
            ?: offers.minWithOrNull(order(price))

    /**
     * Shops by [price], and at the same price by name: the Buy button names the shop at the top of the list
     * (it said Vandenborre.be with Bol.com, Coolblue.be and Vandenborre.be all at € 99,00, and Bol.com first).
     */
    fun order(price: (ShopOffer) -> Double): Comparator<ShopOffer> =
        compareBy<ShopOffer>(price).thenBy { Shops.name(it.seller).lowercase() }

    /**
     * The shops to show: every shop vergelijk.be (or the page you added) gives stays, and the [extra] sources
     * (Google Shopping, Shoparize) add the shops that aren't there yet. For a shop both list, the extra source's
     * price counts, not the cheaper one: a comparison page can still show an old, lower price that the shop no
     * longer has (€ 26,95 for what bol.com sells at € 38,99). One offer per shop. Sold-out shops are left out
     * unless every shop is sold out.
     */
    fun merge(own: List<ShopOffer>, extra: List<ShopOffer>): List<ShopOffer> = pick(listOf(extra, own))

    /** An offer more than this many times off the other sources' middle price is another product. */
    private const val OUTLIER = 3.0

    /**
     * [offers] without the ones far off what [others] (other sources' offers for the product) ask: a price
     * more than 3 times their middle price, or under a third of it, is another pack or a mistake. Only with
     * at least two other prices to go by; else all stay.
     */
    fun withoutOutliers(offers: List<ShopOffer>, others: List<ShopOffer>): List<ShopOffer> {
        if (others.size < 2) return offers
        val sorted = others.map { it.price }.sorted()
        val middle = sorted[sorted.size / 2]
        return offers.filter { it.price in middle / OUTLIER..middle * OUTLIER }
    }

    /** Two prices this close are the same price. */
    private const val SAME_PRICE = 0.01

    /**
     * One offer per shop from several sources, [sources] in order of trust (Google Shopping, Shoparize,
     * vergelijk.be). For a shop more than one source lists: a price two sources agree on wins, so one source
     * that runs behind is outvoted (Google still at € 26,95 where vergelijk.be and Shoparize both say € 38,99);
     * when they all differ, the first source's price. A shop is the same shop under its different spellings
     * ("Bol Plaza", "bol.com"). Sold-out shops are left out unless every shop is sold out.
     */
    fun pick(sources: List<List<ShopOffer>>): List<ShopOffer> {
        val all = sources.flatMapIndexed { rank, list -> list.map { it to rank } }
        val sellable = all.filter { it.first.inStock != false }.ifEmpty { all }
        return sellable.groupBy { Shops.name(it.first.seller).lowercase() }.values
            .map { same ->
                // Each source's offer for this shop (its cheapest), most trusted source first.
                val perSource = same.groupBy { it.second }.toSortedMap().values.map { list -> list.minBy { it.first.price } }
                val agreed = perSource.firstOrNull { a ->
                    perSource.count { b -> kotlin.math.abs(b.first.price - a.first.price) <= SAME_PRICE } >= 2
                }
                (agreed ?: perSource.first()).first
            }
            .sortedBy { it.price }
    }
}
