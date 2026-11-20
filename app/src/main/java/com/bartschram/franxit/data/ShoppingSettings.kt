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

    /** Google Shopping goes through Franxit's own search service; always on. */
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context? = null): Boolean = GoogleShopping.PROXY.isNotBlank()

    /** The wording of the search that worked last time for this product: tried first next time. */
    fun extraTerm(context: Context, productId: Long): String? = prefs(context).getString("extra_term_$productId", null)

    fun setExtraTerm(context: Context, productId: Long, term: String) =
        prefs(context).edit { putString("extra_term_$productId", term) }

    /** Bumped when the way results are found or filtered changes, so older saved results aren't reused. */
    private const val EXTRA_VERSION = 5

    /** Google Shopping offers for a product, saved so a later refresh doesn't search again: used for [days] days (more for "nothing found": no searches wasted). */
    fun saveExtraOffers(context: Context, productId: Long, day: String, offers: List<ShopOffer>, days: Int = 1) =
        saveOffers(context, "extra_offers_$productId", EXTRA_VERSION, day, offers, days)

    fun extraOffers(context: Context, productId: Long, day: String): List<ShopOffer>? =
        loadOffers(context, "extra_offers_$productId", EXTRA_VERSION, day)

    /** Google Shopping's offers from the last search, however long ago: shown while a fresh search runs, so prices don't jump. */
    fun lastExtraOffers(context: Context, productId: Long): List<ShopOffer>? =
        loadOffers(context, "extra_offers_$productId", EXTRA_VERSION, null)

    /** Bumped when the way Shoparize's results are found or filtered changes. */
    private const val SHOPARIZE_VERSION = 3

    /** Shoparize's offers for a product, kept like Google Shopping's: asked once a day, "nothing found" for [days] days. */
    fun saveShoparizeOffers(context: Context, productId: Long, day: String, offers: List<ShopOffer>, days: Int = 1) =
        saveOffers(context, "shoparize_offers_$productId", SHOPARIZE_VERSION, day, offers, days)

    fun shoparizeOffers(context: Context, productId: Long, day: String): List<ShopOffer>? =
        loadOffers(context, "shoparize_offers_$productId", SHOPARIZE_VERSION, day)

    private fun saveOffers(context: Context, key: String, version: Int, day: String, offers: List<ShopOffer>, days: Int) {
        val json = JSONObject().put("v", version).put("day", day)
            .put("until", java.time.LocalDate.parse(day).plusDays(days - 1L).toString()).put(
            "offers",
            org.json.JSONArray(offers.map { JSONObject().put("seller", it.seller).put("price", it.price).also { o -> it.inStock?.let { s -> o.put("inStock", s) } } }),
        )
        prefs(context).edit { putString(key, json.toString()) }
    }

    /** The shops' own product pages Shoparize's links named, by shop: for the Buy button. Replaces the earlier ones. */
    fun saveShoparizeLinks(context: Context, productId: Long, links: Map<String, String>) {
        val json = JSONObject()
        links.forEach { (shop, link) -> json.put(TrustedShops.key(shop), link) }
        prefs(context).edit { putString("shoparize_links_$productId", json.toString()) }
    }

    fun shoparizeLink(context: Context, productId: Long, shop: String): String? =
        prefs(context).getString("shoparize_links_$productId", null)?.let { JSONObject(it).optString(TrustedShops.key(shop)) }
            ?.takeIf { it.startsWith("https://") }

    /** The shops Shoparize listed at the last search, however long ago: for the Buy button, which shows what was last found. */
    fun lastShoparizeOffers(context: Context, productId: Long): List<ShopOffer>? =
        loadOffers(context, "shoparize_offers_$productId", SHOPARIZE_VERSION, null)

    /** [day] null: whatever was saved, whenever. */
    private fun loadOffers(context: Context, key: String, version: Int, day: String?): List<ShopOffer>? {
        val json = prefs(context).getString(key, null)?.let(::JSONObject) ?: return null
        if (json.optInt("v", 0) != version) return null
        if (day != null && (day > json.optString("until", "") || day < json.optString("day"))) return null
        val list = json.getJSONArray("offers")
        return (0 until list.length()).map { list.getJSONObject(it).let { o -> ShopOffer(o.getString("seller"), o.getDouble("price"), if (o.has("inStock")) o.getBoolean("inStock") else null) } }
    }

    /** Keeps a shop's page found earlier when the new search only has a token to look it up again. */
    /** What the last Google Shopping search for this product found, in words for the product page. */
    fun extraStatus(context: Context, productId: Long): String? = prefs(context).getString("extra_status_$productId", null)

    fun setExtraStatus(context: Context, productId: Long, status: String) =
        prefs(context).edit { putString("extra_status_$productId", status) }

    fun clearExtraStatus(context: Context, productId: Long) =
        prefs(context).edit { remove("extra_status_$productId") }

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
