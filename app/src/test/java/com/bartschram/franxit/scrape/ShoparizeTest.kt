package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoparizeTest {

    /** A row of a product page's shop table, as Shoparize writes it: a tracking link with a data-tracking block. */
    private fun row(shop: String, price: String, labels: String = "", element: String = "pricesTable") =
        """<a href="https://track.shoparize.com/site.php?pos=0&amp;cc=be&amp;url=https%3A%2F%2Fexample.com" target="_blank" rel="nofollow noopener" """ +
            """data-tracking="{&quot;shopid&quot;:&quot;$shop&quot;,&quot;type&quot;:1,&quot;element&quot;:&quot;$element&quot;,&quot;price&quot;:$price,&quot;oc&quot;:8,&quot;pos&quot;:1}" > """ +
            """<div><div><span>$shop</span></div><div><span> Gratis verzending</span><span>$labels</span></div></div>""" +
            """<div><span> € $price </span></div></a>"""

    private fun productPage(vararg rows: String) =
        """<html><body><div><span> Alle beschikbare aanbiedingen (${rows.size}) </span></div><div>${rows.joinToString("")}</div></body></html>"""

    private val sonyPage = productPage(
        row("coolblue.be", "599.99"),
        row("bol.com", "649.00"),
        row("productpine.com", "519.99", labels = "Refurbished"),
        row("mediamarkt.be", "629.5"),
    )

    private fun searchPage(vararg items: Pair<String, String>) =
        """<script type="application/ld+json">{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}</script>""" +
            """<script type="application/ld+json">{"@context":"https://schema.org","@type":"ItemList","numberOfItems":${items.size},"itemListElement":[""" +
            items.mapIndexed { i, (name, url) -> """{"@type":"ListItem","position":${i + 1},"url":"$url","name":"$name"}""" }.joinToString(",") +
            "]}</script>"

    private val page = "https://www.shoparize.com/be/p/"

    @Test
    fun theShopsAndTheirPricesAreReadFromTheTrackingBlocks() {
        val offers = Shoparize.parseOffers(sonyPage)
        assertEquals(listOf("coolblue.be", "bol.com", "mediamarkt.be"), offers.map { it.seller })
        assertEquals(listOf(599.99, 649.0, 629.5), offers.map { it.price })
    }

    @Test
    fun refurbishedOffersAreLeftOut() {
        val offers = Shoparize.parseOffers(sonyPage)
        assertTrue("productpine.com" !in offers.map { it.seller })
    }

    @Test
    fun theStockOfAShopIsNotSaid() {
        assertTrue(Shoparize.parseOffers(sonyPage).all { it.inStock == null })
    }

    @Test
    fun rowsThatAreNotInTheShopTableAreIgnored() {
        val html = productPage(row("coolblue.be", "599.99"), row("bol.com", "10.00", element = "carousel"))
        assertEquals(listOf("coolblue.be"), Shoparize.parseOffers(html).map { it.seller })
    }

    @Test
    fun aRowWithoutAPriceOrShopIsIgnored() {
        val html = productPage(row("coolblue.be", "0"), row("", "12.00"), row("bol.com", "649.00"))
        assertEquals(listOf("bol.com"), Shoparize.parseOffers(html).map { it.seller })
    }

    @Test
    fun aPageWithoutShopsGivesNoOffers() {
        assertTrue(Shoparize.parseOffers("<html><body>Niets gevonden</body></html>").isEmpty())
    }

    @Test
    fun aSearchPageListsItsProductPages() {
        val hits = Shoparize.parseSearch(
            searchPage(
                "Sony PlayStation 5 Slim Digital Edition 825GB" to "${page}sony-ps5/aaa",
                "Hoes voor PlayStation 5" to "${page}hoes/bbb",
                "Geen productpagina" to "https://www.shoparize.com/be/brands",
            ),
        )
        assertEquals(listOf("Sony PlayStation 5 Slim Digital Edition 825GB", "Hoes voor PlayStation 5"), hits.map { it.title })
        assertEquals("${page}sony-ps5/aaa", hits.first().url)
    }

    @Test
    fun theProductThatIsClearlyWantedIsChosen() {
        val hits = Shoparize.parseSearch(
            searchPage(
                "Hoes voor Sony PlayStation 5 Slim Digital Edition" to "${page}hoes/bbb",
                "Sony PlayStation 5 Slim Digital Edition 825GB Wit" to "${page}sony-ps5/aaa",
            ),
        )
        assertEquals("${page}sony-ps5/aaa", Shoparize.best(hits, "Sony PlayStation 5 Slim Digital Edition")?.url)
    }

    @Test
    fun aBundleIsNotTheProductItself() {
        val hits = Shoparize.parseSearch(
            searchPage(
                "Sony PlayStation 5 Slim Digital + Fortnite Flowering Chaos, Console Digitaal" to "${page}bundle/ddd",
                "Sony PlayStation 5 Slim Digital Edition 825GB" to "${page}sony-ps5/aaa",
            ),
        )
        assertEquals("${page}sony-ps5/aaa", Shoparize.best(hits, "Sony PlayStation 5 Slim Digital Edition")?.url)
        // Only the bundle listed: nothing, rather than its price under the console's name.
        assertNull(Shoparize.best(hits.take(1), "Sony PlayStation 5 Slim Digital Edition"))
    }

    @Test
    fun anotherModelIsNotChosen() {
        val hits = Shoparize.parseSearch(searchPage("Samsung Galaxy S24 128GB" to "${page}s24/ccc"))
        assertNull(Shoparize.best(hits, "Samsung Galaxy S25"))
    }

    @Test
    fun offersForSearchesThenReadsTheChosenProductPage() {
        val asked = mutableListOf<String>()
        val offers = Shoparize.offersFor("Sony PlayStation 5 Slim Digital Edition") { url ->
            asked += url
            if ("/be/q" in url) searchPage("Sony PlayStation 5 Slim Digital Edition 825GB" to "${page}sony-ps5/aaa") else sonyPage
        }
        assertEquals(listOf(Shoparize.searchUrl("Sony PlayStation 5 Slim Digital Edition"), "${page}sony-ps5/aaa"), asked)
        assertEquals(3, offers.size)
    }

    @Test
    fun withoutAClearProductNoProductPageIsRead() {
        val asked = mutableListOf<String>()
        val offers = Shoparize.offersFor("Samsung Galaxy S25") { url ->
            asked += url
            searchPage("Samsung Galaxy S24 128GB" to "${page}s24/ccc")
        }
        assertTrue(offers.isEmpty())
        assertEquals(1, asked.size)
    }

    @Test
    fun aSmallShopGetsASearchLimitedToItsOwnSite() {
        assertEquals(
            "https://www.google.be/search?q=Primrose+Prox+180+Capsules+site%3Apazzox.be",
            Shoparize.shopSearchUrl("Primrose Prox 180 Capsules", "Pazzox.be"),
        )
    }

    @Test
    fun aBigShopGetsItsOwnSearchPage() {
        assertEquals(
            "https://www.coolblue.be/nl/zoeken?query=Sony+PlayStation+5",
            Shoparize.shopSearchUrl("Sony PlayStation 5", "Coolblue.be"),
        )
    }

    @Test
    fun aShopSearchNeverGoesToShoparize() {
        val urls = listOf("Pazzox.be", "Coolblue.be", "Farmaline.be", "Some shop").map { Shoparize.shopSearchUrl("Product", it) }
        assertTrue(urls.none { "shoparize" in it })
    }

    // Shoparize's own tracking links, as its product page has them (unescaped).
    private val track = "https://track.shoparize.com/site.php?pos=0&page=0&sclid=WEB_1&ps=60&cs=product-popup_highlight&search=&cc=be&url="

    private val pazzox = track +
        "https%3A%2F%2Fwww.pazzox.be%2Fonlineapotheek%2F%3Ftt%3D33955_1911357_313167_%26r%3Dhttps%253A%252F%252Fwww.pazzox.be%252Fnl%252Fapotheek%252Fbestellen%252Fprox-caps-180%252F%253Futm_campaign%253Dshopping%2526utm_content%253Dsurfaces_across_google%2526utm_source%253Dgs%2526utm_medium%253Dorganic%2526utm_term%253D5420050400660"

    @Test
    fun theProductPageIsTakenFromATrackingLinkWithoutTheTracking() {
        assertEquals("https://www.pazzox.be/nl/apotheek/bestellen/prox-caps-180/", Shoparize.cleanLink(pazzox, "pazzox.be"))
    }

    @Test
    fun anAffiliateLinkThatNamesTheProductPageIsCleanedToo() {
        val link = track + "https%3A%2F%2Fwww.pazzox.be%2Fnl%2Fapotheek%2Fbestellen%2Fprox-caps-180%2F%3Futm_content%3Dsurfaces_across_google%26utm_term%3D5420050400660%26utm_source%3DTradetracker%26utm_medium%3Daffiliate%26utm_campaign%3DShoparize%2520Shopping"
        assertEquals("https://www.pazzox.be/nl/apotheek/bestellen/prox-caps-180/", Shoparize.cleanLink(link, "pazzox.be"))
    }

    @Test
    fun otherParametersOfTheProductPageStay() {
        val link = track + "https%3A%2F%2Fwww.shop.be%2Fnl%2Fp%2Fprox%2F%3Fvariant%3D12%26utm_source%3Dx%26tduid%3Dabc"
        assertEquals("https://www.shop.be/nl/p/prox/?variant=12", Shoparize.cleanLink(link, "shop.be"))
    }

    @Test
    fun anOpaqueAffiliateLinkGivesNothing() {
        val awin = track + "https%3A%2F%2Fwww.awin1.com%2Fpclick.php%3Fp%3D45350893466%26a%3D323889%26m%3D110550"
        assertNull(Shoparize.cleanLink(awin, "farmaline.be"))
    }

    @Test
    fun aRelativeDeepLinkIsNotGuessedInto() {
        val deep = track + "https%3A%2F%2Fjf79.net%2Fc%2F%3Fsi%3D16143%26li%3D1698127%26dl%3Dbe_nl%252Fprox-180-capsules.html%26ws%3D"
        assertNull(Shoparize.cleanLink(deep, "pharmamarket.be"))
    }

    @Test
    fun aPartnerAddressOfAShopIsNoProductPage() {
        val partner = track + "https%3A%2F%2Fpartnerprogramma.bol.com%2Fclick%2Fclick%3Fp%3D1%26s%3D2"
        assertNull(Shoparize.cleanLink(partner, "bol.com"))
        // Nor is a tracking page on the shop's own site, or its start page.
        assertNull(Shoparize.cleanLink(track + "https%3A%2F%2Fwww.shop.be%2Fclick%2Fgo%3Fid%3D1", "shop.be"))
        assertNull(Shoparize.cleanLink(track + "https%3A%2F%2Fwww.shop.be%2Fonlineapotheek%2F%3Ftt%3D1", "shop.be"))
    }

    @Test
    fun aLinkToAnotherSiteIsNotTakenForTheShops() {
        assertNull(Shoparize.cleanLink(track + "https%3A%2F%2Fwww.other.be%2Fnl%2Fp%2Fprox%2F", "shop.be"))
        assertNull(Shoparize.cleanLink("https://track.shoparize.com/site.php?pos=0", "shop.be"))
    }

    @Test
    fun anOfferCarriesTheCleanLinkOfItsShop() {
        val html = productPage(
            """<a href="${pazzox.replace("&", "&amp;")}" data-tracking="{&quot;shopid&quot;:&quot;pazzox.be&quot;,&quot;element&quot;:&quot;pricesTable&quot;,&quot;price&quot;:111.2}"><span>pazzox.be</span><span> € 111,20 </span></a>""",
            row("farmaline.be", "123.99"),
        )
        val offers = Shoparize.parseOffers(html)
        assertEquals("https://www.pazzox.be/nl/apotheek/bestellen/prox-caps-180/", offers.first { it.seller == "pazzox.be" }.link)
        assertNull(offers.first { it.seller == "farmaline.be" }.link)
    }

    @Test
    fun theSearchUrlEscapesTheWords() {
        assertEquals("https://www.shoparize.com/be/q?q=Sony+WF-1000XM6+%26+case", Shoparize.searchUrl(" Sony WF-1000XM6 & case "))
    }
}
