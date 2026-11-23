package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoTest {

    private val nintendo = "https://store.nintendo.com/nl-be/nintendo-switch-2-the-legend-of-zelda-40th-anniversary-edition-P00211"

    private fun result(href: String, ad: Boolean = false) =
        """<div class="result${if (ad) " result--ad" else ""}"><h2 class="result__title"><a rel="nofollow" class="result__a" href="$href">Title</a></h2></div>"""

    @Test
    fun theFirstResultOnTheShopsSite() {
        val page = "<html>" +
            result("https://duckduckgo.com/y.js?ad_domain=amazon.com.be&u3=x", ad = true) +
            result("//duckduckgo.com/l/?uddg=" + java.net.URLEncoder.encode(nintendo, "UTF-8") + "&amp;rut=abc") +
            result("//duckduckgo.com/l/?uddg=https%3A%2F%2Fstore.nintendo.com%2Fnl-be%2Fother&amp;rut=def") +
            "</html>"
        assertEquals(nintendo, DuckDuckGo.firstResult(page, "store.nintendo.com"))
    }

    @Test
    fun anotherSiteOrNoResultsGivesNothing() {
        val elsewhere = result("//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.fnac.be%2Fa1&amp;rut=x")
        assertNull(DuckDuckGo.firstResult(elsewhere, "store.nintendo.com"))
        // A check page instead of results.
        assertNull(DuckDuckGo.firstResult("<html><form id=\"challenge-form\"></form></html>", "store.nintendo.com"))
    }

    @Test
    fun theSiteOfAShop() {
        assertEquals("store.nintendo.com", GoogleShopping.siteOf("nintendo"))
        assertEquals("fnac.be", GoogleShopping.siteOf("Fnac.be"))
        assertEquals("bax-shop.be", GoogleShopping.siteOf("Bax-shop.be"))
        assertNull(GoogleShopping.siteOf("Hiloro Digital"))
    }

    @Test
    fun aShopKnownByItsNameIsFoundOnItsOwnSite() {
        assertTrue(DuckDuckGo.isShopsSite("kamera-express.be", "Kamera Express"))
        assertTrue(DuckDuckGo.isShopsSite("www.fotodevakman.be", "Fotodevakman"))
        assertTrue(DuckDuckGo.isShopsSite("shop.cameranu.be", "Cameranu"))
        assertFalse(DuckDuckGo.isShopsSite("bol.com", "Kamera Express"))
        assertFalse(DuckDuckGo.isShopsSite("amazon.com.be", "Art & Craft"))
        val page = result("//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.bol.com%2Fbe%2Fnl%2Fp%2Fselphy&amp;rut=a") +
            result("//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.kamera-express.be%2Fproduct%2F12422729%2Fcanon-selphy-cp1500-black&amp;rut=b")
        assertEquals(
            "https://www.kamera-express.be/product/12422729/canon-selphy-cp1500-black",
            DuckDuckGo.firstResult(page) { DuckDuckGo.isShopsSite(it, "Kamera Express") },
        )
    }
}
