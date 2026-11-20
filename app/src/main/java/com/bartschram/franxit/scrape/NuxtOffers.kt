package com.bartschram.franxit.scrape

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Reads the shop offers from vergelijk.be's embedded app state (`__NUXT_DATA__`).
 *
 * The page's schema.org data lists list prices only (e.g. Bol. at 499,99), while the price you
 * actually pay can be lower through a deal (445,82). The app state has both.
 *
 * The state is a flat "devalue" table: objects hold indexes into the table instead of values,
 * and some values are wrapped as ["ShallowReactive", index].
 */
object NuxtOffers {

    private val NUXT_DATA = Regex(
        """<script[^>]+id="__NUXT_DATA__"[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val WRAPPERS = setOf("ShallowReactive", "Reactive", "ShallowRef", "Ref")

    fun parse(html: String): List<Scraper.Offer> {
        val raw = NUXT_DATA.find(html)?.groupValues?.get(1) ?: return emptyList()
        val table = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        fun deref(obj: JSONObject?, key: String): Any? {
            val index = obj?.opt(key) as? Int ?: return null
            var value = table.opt(index)
            while (value is JSONArray && value.length() == 2 && value.optString(0) in WRAPPERS) {
                value = table.opt(value.optInt(1))
            }
            return value
        }

        val offers = linkedSetOf<Scraper.Offer>()
        for (i in 0 until table.length()) {
            val entry = table.optJSONObject(i) ?: continue
            if (!entry.has("shop") || !entry.has("offer")) continue
            val offer = deref(entry, "offer") as? JSONObject ?: continue
            val condition = deref(offer, "productCondition") as? String
            if (condition != null && condition != "NEW") continue
            val seller = (deref(deref(entry, "shop") as? JSONObject, "brand") as? JSONObject)
                ?.let { deref(it, "name") as? String }
                ?: continue
            // The shop's own price for the product, after its deal: what the shop's page shows. vergelijk.be's
            // total adds the shipping, which Google Shopping and Shoparize leave out: with it, a shop looked
            // € 5,95 dearer (Tandenborstel.com at € 10,90 for € 4,95 plus shipping). The total only when the
            // page doesn't give the product price.
            val deal = deref(offer, "deal") as? JSONObject
            val base = (deref(offer, "basePrice") as? Number)?.toDouble()
            val baseDiscounted = (deref(deal, "baseDiscountedPrice") as? Number)?.toDouble()
            val total = (deref(offer, "totalPrice") as? Number)?.toDouble()
            val discounted = (deref(deal, "discountedPrice") as? Number)?.toDouble()
            val price = listOfNotNull(base, baseDiscounted).filter { it > 0 }.minOrNull()
                ?: listOfNotNull(total, discounted).filter { it > 0 }.minOrNull()
                ?: continue
            // The "Bekijk product" button opens this; vergelijk.be then redirects to the shop.
            val link = (deref(offer, "clickoutHash") as? String)?.let {
                "https://www.vergelijk.be/clickout/register?hash=" + URLEncoder.encode(it, "UTF-8")
            }
            offers += Scraper.Offer(seller.trim(), price, link = link)
        }
        // The same offer appears more than once in the state (e.g. as the "winner").
        return offers.distinctBy { it.seller to it.price }
    }
}
