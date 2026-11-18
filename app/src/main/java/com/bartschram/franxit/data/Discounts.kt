package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToLong

/**
 * Personal discounts per shop, e.g. 5% at bol.com through work. They only decide where to buy
 * (the Buy button, the order of Shops today, a "for you" line on the Overview): the price history,
 * "Lowest" and price alerts keep using the shops' own prices.
 */
object Discounts {

    private const val PREFS = "discounts"

    /** Shop name to percentage off. */
    fun load(context: Context): Map<String, Double> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (shop, value) -> (value as? Float)?.let { shop to it.toDouble() } }
            .toMap()

    /** Sets [shop]'s discount, or removes it when [percent] is null. */
    fun set(context: Context, shop: String, percent: Double?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (percent == null) remove(shop) else putFloat(shop, percent.toFloat())
        }
    }

    /**
     * [shop]'s discount: one set for that exact seller, or else one set for the same shop under
     * another of its names, so a bol.com discount also counts for "Bol Plaza", its partners.
     */
    fun percentFor(shop: String, discounts: Map<String, Double>): Double? {
        discounts.entries.firstOrNull { it.key.equals(shop.trim(), ignoreCase = true) }?.let { return it.value }
        val site = Shops.name(shop)
        return discounts.entries.firstOrNull { Shops.name(it.key) == site }?.value
    }

    /** What [offer] costs you, to the cent. */
    fun yourPrice(offer: ShopOffer, discounts: Map<String, Double>): Double {
        val percent = percentFor(offer.seller, discounts) ?: return offer.price
        return (offer.price * (100 - percent)).roundToLong() / 100.0
    }

    /** The offer that's cheapest for you, preferring ones in stock. Null when there are none. */
    fun bestForYou(offers: List<ShopOffer>, discounts: Map<String, Double>): ShopOffer? =
        Offers.best(offers) { yourPrice(it, discounts) }

    /** "5" or "2,5", for "−5%". */
    fun label(percent: Double): String =
        if (percent % 1.0 == 0.0) percent.toLong().toString() else percent.toString().replace('.', ',')
}
