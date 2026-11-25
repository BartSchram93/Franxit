package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScraperTest {

    // Same shape as the JSON-LD graph on vergelijk.be product pages.
    private val page = """
        <html><head>
        <script type="application/ld+json" data-hid="schema-org-graph">{"@context":"https://schema.org","@graph":[
          {"@type":"WebSite","name":"VERGELIJK.BE"},
          {"@type":"Product","name":"TCL NXTPAPER 14","offers":{"@type":"AggregateOffer","lowPrice":386.1,
            "highPrice":499.99,"priceCurrency":"EUR","offerCount":3,"offers":[
              {"@type":"Offer","price":389.0,"seller":{"@type":"Organization","name":"Amazon"}},
              {"@type":"Offer","price":"386.10","seller":{"@type":"Organization","name":"Amazon Marketplace"}},
              {"@type":"Offer","price":499.99,"seller":{"@type":"Organization","name":"Bol."}}]}}
        ]}</script>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun picksCheapestOffer() {
        val result = Scraper.parse(page)
        assertEquals("TCL NXTPAPER 14", result.name)
        assertEquals(3, result.offers.size)
        assertEquals(386.10, result.cheapest.price, 0.001)
        assertEquals("Amazon Marketplace", result.cheapest.seller)
    }

    @Test
    fun fallsBackToLowPrice() {
        val html = """<script type="application/ld+json">{"@type":"Product","name":"X",
            "offers":{"@type":"AggregateOffer","lowPrice":"12,50"}}</script>"""
        assertEquals(12.5, Scraper.parse(html).cheapest.price, 0.001)
    }

    @Test
    fun prefersInStockOffers() {
        val html = """<script type="application/ld+json">{"@type":"Product","name":"X","offers":[
            {"@type":"Offer","price":10,"availability":"https://schema.org/OutOfStock","seller":{"name":"A"}},
            {"@type":"Offer","price":12,"availability":"https://schema.org/InStock","seller":{"name":"B"}}]}</script>"""
        assertEquals("B", Scraper.parse(html).cheapest.seller)
    }

    // Coolblue: one Offer without a seller, plus review snippets that repeat the Product without offers.
    @Test
    fun readsShopOwnOffer() {
        val html = """
            <script type="application/ld+json">{"@type":"Product","name":"Wisberg WBTTVR55DNFW",
              "offers":{"@type":"Offer","priceCurrency":"EUR","price":399,"availability":"https://schema.org/OutOfStock"}}</script>
            <script type="application/ld+json">{"@type":"Review","itemReviewed":{"@type":"Product","name":"Wisberg WBTTVR55DNFW"}}</script>
        """
        val result = Scraper.parse(html)
        assertEquals("Wisberg WBTTVR55DNFW", result.name)
        assertEquals(399.0, result.cheapest.price, 0.001)
        assertEquals("", result.cheapest.seller)
    }

    @Test
    fun findsImageByReference() {
        val html = """<script type="application/ld+json">{"@graph":[
            {"@id":"https://x/#/schema/image/1","@type":"ImageObject","url":"https://pc.vergelijk.be/500x500/1.webp"},
            {"@type":"Product","name":"X","image":{"@id":"https://x/#/schema/image/1"},"offers":{"@type":"Offer","price":10}}]}</script>"""
        assertEquals("https://pc.vergelijk.be/500x500/1.webp", Scraper.parse(html).image)
        val list = """<script type="application/ld+json">{"@type":"Product","name":"X","image":["//img/a.jpg","//img/b.jpg"],
            "offers":{"@type":"Offer","price":10}}</script>"""
        assertEquals("https://img/a.jpg", Scraper.parse(list).image)
    }

    @Test(expected = IOException::class)
    fun failsWithoutProduct() {
        Scraper.parse("<html>nothing here</html>")
    }

    @Test
    fun aRedirectToAnotherPageIsNoticed() {
        val product = "https://www.vergelijk.be/stoelen/hercules/kb200b_123/?catId=1"
        assertEquals(false, Scraper.movedElsewhere(product, "https://www.vergelijk.be/stoelen/hercules/kb200b_123"))
        assertEquals(true, Scraper.movedElsewhere(product, "https://www.vergelijk.be/stoelen/"))
    }

    @Test
    fun theBrandAShopPageNames() {
        val page = """<script type="application/ld+json">{"@type":"Product","name":"Patchwork","brand":{"@type":"Brand","name":"999 Games"},
            "offers":{"@type":"Offer","price":"16.95","priceCurrency":"EUR"}}</script>"""
        assertEquals("999 Games", Scraper.parse(page).brand)
        assertEquals("Asmodee", Scraper.brand("Asmodee"))
        assertEquals(null, Scraper.brand("Unbranded"))
    }

    @Test
    fun aPriceInAnotherCurrencyIsNoPriceInEuros() {
        val pounds = """<script type="application/ld+json">{"@type":"Product","name":"Zelda Edition",
            "offers":{"@type":"Offer","price":"434.99","priceCurrency":"GBP"}}</script>"""
        assertTrue(runCatching { Scraper.parse(pounds) }.isFailure)
        val euros = pounds.replace("GBP", "EUR").replace("434.99", "519.99")
        assertEquals(519.99, Scraper.parse(euros).offers.single().price, 0.001)
    }

    @Test
    fun aPriceWithoutVatInTheDataIsReadWithIt() {
        // Back in use: the data says 354.55 (without VAT), the page "€429,01 incl. BTW".
        val data = """<script type="application/ld+json">{"@type":"Product","name":"Playstation 5 Slim Digital Edition | 825GB","offers":[{"@type":"Offer","price":"354.55","priceCurrency":"EUR"}]}</script>"""
        val page = "<html><head>$data</head><body><span>€599,00</span> <span>€429,01</span> incl. BTW</body></html>"
        assertEquals(429.01, Scraper.fromPage(page, "https://www.backinuse.be/products/playstation-5-slim-digital-edition-825gb").offers.single().price, 0.001)
        // Its other version (Premium, 371.07 in the data) with VAT too, though the page shows only the chosen one.
        val two = page.replace(""""price":"354.55","priceCurrency":"EUR"}]""", """"price":"354.55","priceCurrency":"EUR"},{"@type":"Offer","price":"371.07","priceCurrency":"EUR"}]""")
        assertEquals(listOf(429.01, 448.99), Scraper.fromPage(two, "https://www.backinuse.be/products/x").offers.map { it.price })
        // The data's price on the page too, hidden or in a list of versions: still the price with VAT that's shown so.
        val hidden = "<html><head>$data</head><body><span style=\"display:none\">€-354,55</span><option>Uitstekend - €354,55</option><p>€429,01<sup>incl. BTW</sup></p></body></html>"
        assertEquals(429.01, Scraper.fromPage(hidden, "https://www.backinuse.be/products/x").offers.single().price, 0.001)
        // A page showing the data's own price: as it is.
        val plain = "<html><head>$data</head><body><span>€354,55</span> incl. btw</body></html>"
        assertEquals(354.55, Scraper.fromPage(plain, "https://www.shop.be/p/1").offers.single().price, 0.001)
        // A page showing neither: as it is (no guess).
        assertEquals(354.55, Scraper.fromPage("<html><head>$data</head><body></body></html>", "https://www.shop.be/p/1").offers.single().price, 0.001)
    }
}
