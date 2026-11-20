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

    // Tandenborstel.com: € 6,50, € 4,95 after its deal, € 10,90 with € 5,95 shipping; Plein.be € 5,20, € 13,15 with shipping.
    private val withShipping = """
        <script type="application/json" data-nuxt-data="nuxt-app" data-ssr="true" id="__NUXT_DATA__">[
          {"shop":1,"offer":4},
          {"brand":2},
          {"name":3},
          "Tandenborstel.com",
          {"basePrice":5,"totalPrice":6,"deal":7,"productCondition":10},
          6.5,
          12.45,
          {"baseDiscountedPrice":8,"discountedPrice":9},
          4.95,
          10.9,
          "NEW",
          {"shop":12,"offer":15},
          {"brand":13},
          {"name":14},
          "Plein.be",
          {"basePrice":16,"totalPrice":17,"productCondition":10},
          5.2,
          13.15
        ]</script>
    """.trimIndent()

    @Test
    fun theShopsOwnPriceCountsWithoutShipping() {
        val offers = NuxtOffers.parse(withShipping)
        assertEquals(listOf(Scraper.Offer("Tandenborstel.com", 4.95), Scraper.Offer("Plein.be", 5.2)), offers)
    }
}
