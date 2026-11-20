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

    @Test
    fun theSearchUrlEscapesTheWords() {
        assertEquals("https://www.shoparize.com/be/q?q=Sony+WF-1000XM6+%26+case", Shoparize.searchUrl(" Sony WF-1000XM6 & case "))
    }
}
