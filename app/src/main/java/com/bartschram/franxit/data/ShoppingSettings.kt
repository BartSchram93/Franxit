package com.bartschram.franxit.data

import android.content.Context
import androidx.core.content.edit
import com.bartschram.franxit.scrape.GoogleShopping
import org.json.JSONObject

/**
 * Your SerpApi key for Google Shopping, how many searches the app used this month, and each
 * product's shop pages from its last search. Kept on the phone only.
 */
object ShoppingSettings {

    private const val PREFS = "google_shopping"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(context: Context): String? = prefs(context).getString("key", null)?.takeIf { it.isNotBlank() }

    fun setKey(context: Context, key: String?) = prefs(context).edit {
        if (key.isNullOrBlank()) remove("key") else putString("key", key.trim())
    }

    /** Products that also search Google Shopping, next to vergelijk.be. */
    fun extraIds(context: Context): Set<Long> =
        prefs(context).getStringSet("extra", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun isExtra(context: Context, productId: Long): Boolean =
        prefs(context).getStringSet("extra", emptySet())!!.contains(productId.toString())

    fun setExtra(context: Context, productId: Long, on: Boolean) {
        val now = prefs(context).getStringSet("extra", emptySet())!!.toMutableSet()
        if (on) now += productId.toString() else now -= productId.toString()
        // Switching it on or off starts afresh: a new search, a new result.
        prefs(context).edit { putStringSet("extra", now); remove("extra_offers_$productId"); remove("extra_status_$productId") }
    }

    /** Today's Google Shopping offers for a product, so a later refresh doesn't search again. */
    fun saveExtraOffers(context: Context, productId: Long, day: String, offers: List<ShopOffer>) {
        val json = JSONObject().put("day", day).put(
            "offers",
            org.json.JSONArray(offers.map { JSONObject().put("seller", it.seller).put("price", it.price) }),
        )
        prefs(context).edit { putString("extra_offers_$productId", json.toString()) }
    }

    fun extraOffers(context: Context, productId: Long, day: String): List<ShopOffer>? {
        val json = prefs(context).getString("extra_offers_$productId", null)?.let(::JSONObject) ?: return null
        if (json.optString("day") != day) return null
        val list = json.getJSONArray("offers")
        return (0 until list.length()).map { list.getJSONObject(it).let { o -> ShopOffer(o.getString("seller"), o.getDouble("price")) } }
    }

    /** Keeps a shop's page found earlier when the new search only has a token to look it up again. */
    /** What the last Google Shopping search for this product found, in words for the product page. */
    fun extraStatus(context: Context, productId: Long): String? = prefs(context).getString("extra_status_$productId", null)

    fun setExtraStatus(context: Context, productId: Long, status: String) =
        prefs(context).edit { putString("extra_status_$productId", status) }

    fun saveLinks(context: Context, productId: Long, links: Map<String, String>) {
        val old = prefs(context).getString("links_$productId", null)?.let(::JSONObject)
        val merged = links.mapValues { (shop, link) ->
            val known = old?.optString(shop).orEmpty()
            if (link.startsWith(GoogleShopping.TOKEN) && known.startsWith("http")) known else link
        }
        prefs(context).edit { putString("links_$productId", JSONObject(merged).toString()) }
    }

    fun saveLink(context: Context, productId: Long, shop: String, link: String) {
        val all = prefs(context).getString("links_$productId", null)?.let(::JSONObject) ?: JSONObject()
        prefs(context).edit { putString("links_$productId", all.put(shop, link).toString()) }
    }

    fun link(context: Context, productId: Long, shop: String): String? =
        prefs(context).getString("links_$productId", null)?.let { JSONObject(it).optString(shop).takeIf(String::isNotBlank) }
}
