package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Test

class NuxtOffersTest {

    // Trimmed copy of vergelijk.be's devalue table: Amazon at 386,10, Bol. at 499,99 with an
    // 11% deal bringing it to 445,82. Objects point at other entries by index.
    private val page = """
        <script type="application/ld+json">{"@type":"Product","name":"TCL NXTPAPER 14","offers":{"@type":"AggregateOffer",
          "offers":[{"@type":"Offer","price":386.1,"seller":{"name":"Amazon"}},{"@type":"Offer","price":499.99,"seller":{"name":"Bol."}}]}}</script>
        <script type="application/json" data-nuxt-data="nuxt-app" data-ssr="true" id="__NUXT_DATA__">[
          ["ShallowReactive",1],
          {"items":2},
          [3,4],
          {"shop":5,"offer":7},
          {"shop":10,"offer":12},
          {"brand":6},
          {"name":9},
          {"totalPrice":8,"productCondition":20},
          386.1,
          "Amazon",
          {"brand":11},
          {"name":14},
          {"totalPrice":13,"deal":15,"productCondition":20},
          499.99,
          "Bol.",
          ["ShallowReactive",16],
          {"@type":17,"discountedPrice":18},
          "DealSummary",
          445.82,
          null,
          "NEW"
        ]</script>
    """.trimIndent()

    @Test
    fun readsDiscountedShopPrices() {
        val offers = NuxtOffers.parse(page)
        assertEquals(listOf(Scraper.Offer("Amazon", 386.1), Scraper.Offer("Bol.", 445.82)), offers)
    }

    @Test
    fun scraperPrefersAppStateOverListPrices() {
        val result = Scraper.parse(page)
        assertEquals("TCL NXTPAPER 14", result.name)
        assertEquals(445.82, result.offers.first { it.seller == "Bol." }.price, 0.001)
        assertEquals("Amazon", result.cheapest.seller)
    }
}
