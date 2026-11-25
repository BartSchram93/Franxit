package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserCheckTest {

    @Test
    fun recognisesCheckPages() {
        assertEquals(true, Scraper.isBrowserCheck("<html><head><title>Just a moment...</title></head><body></body></html>"))
        // What beslist.be sent: a tiny page without a title.
        assertEquals(true, Scraper.isBrowserCheck("<html><head><script>/* 2 KB of script */</script></head></html>"))
    }

    @Test
    fun aRealPageIsNotACheck() {
        val page = "<html><head><title>Hercules KB200B keyboardbank</title></head><body>" + "x".repeat(10_000) + "</body></html>"
        assertEquals(false, Scraper.isBrowserCheck(page))
    }

    @Test
    fun aFullShopPageWithACaptchaOrCloudflareScriptIsNotACheck() {
        val page = "<html><head><title>Fender Player Stratocaster | Music Vanderheyden</title>" +
            "<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></head><body>" +
            "x".repeat(80_000) + "<div class=\"g-recaptcha\">captcha</div><noscript>Please enable JavaScript</noscript></body></html>"
        assertEquals(false, Scraper.isBrowserCheck(page))
        // A real check page stays one: small, with its text.
        assertEquals(true, Scraper.isBrowserCheck("<html><head><title>Attention Required</title></head><body>" + "y".repeat(8_000) + "Please complete the captcha</body></html>"))
    }

    @Test
    fun aPriceInMetaTagsOrMicrodata() {
        val meta = """<html><head><title>Fender Player Stratocaster - Music Vanderheyden</title>
            <meta property="og:title" content="Fender Player Stratocaster">
            <meta property="product:price:amount" content="749.00"><meta property="product:price:currency" content="EUR">
            <meta property="product:availability" content="in stock"></head><body></body></html>"""
        val page = Scraper.parse(meta)
        assertEquals("Fender Player Stratocaster", page.name)
        assertEquals(749.0, page.offers.single().price, 0.001)
        assertEquals(true, page.offers.single().inStock)
        val micro = """<div itemscope itemtype="https://schema.org/Product"><h1 itemprop="name">Shoei NXR2</h1>
            <span itemprop="price" content="1.299,00">€ 1.299,00</span><link itemprop="availability" href="https://schema.org/OutOfStock"></div>"""
        assertEquals(1299.0, Scraper.parse(micro).offers.single().price, 0.001)
        assertEquals(false, Scraper.parse(micro).offers.single().inStock)
    }

    @Test
    fun anOdooShopsPriceIsText() {
        // music-vanderheyden.be: the price in a hidden span, with one decimal.
        val odoo = """<html><head><title>Epiphone SG Bass EB-3 Cherry | Music Vanderheyden</title></head><body>
            <div itemprop="offers" itemscope="itemscope" itemtype="http://schema.org/Offer" class="product_price">
            <h3><span data-oe-type="monetary" class="oe_price" style="white-space: nowrap;">€ <span class="oe_currency_value">799,00</span></span>
            <span itemprop="price" style="display:none;">799.0</span>
            <span itemprop="priceCurrency" style="display:none;">EUR</span></h3></div></body></html>"""
        assertEquals(799.0, Scraper.parse(odoo).offers.single().price, 0.001)
        assertEquals(1299.5, Scraper.parse(odoo.replace(">799.0<", ">1299.5<")).offers.single().price, 0.001)
        // Another currency isn't read as euros.
        assertThrows(java.io.IOException::class.java) { Scraper.parse(odoo.replace(">EUR<", ">USD<")) }
    }

    @Test
    fun thePagesOwnVariantIsReadNotTheFirstListed() {
        fun product(name: String, id: String, price: String) =
            """{"@type":"Product","name":"$name","url":"https://www.bol.com/be/nl/p/x/$id/","offers":{"@type":"Offer","price":"$price","priceCurrency":"EUR"}}"""
        val data = """{"@type":"ProductGroup","name":"Sennheiser HD 599","hasVariant":[""" +
            product("Sennheiser HD 599 - Koptelefoon - Bruin", "9200000064532561", "129.99") + "," +
            product("Sennheiser HD 599 SE - Koptelefoon - Zwart", "9300000002888093", "84.91") + "]}"
        val page = """<html><head><title>bol | Sennheiser HD 599 SE - Koptelefoon - Zwart</title>
            <link rel="canonical" href="https://www.bol.com/be/nl/p/sennheiser-hd-599-over-ear-koptelefoon-special-edition-zwart/9300000002888093/">
            <script type="application/ld+json">$data</script></head><body></body></html>"""
        val read = Scraper.parse(page)
        assertEquals("Sennheiser HD 599 SE - Koptelefoon - Zwart", read.name)
        assertEquals(84.91, read.offers.single().price, 0.001)
        // Without an address to go by, the title tells.
        val noAddress = page.replace(Regex("<link[^>]*>"), "").replace("/9300000002888093/", "/1/").replace("/9200000064532561/", "/2/")
        assertEquals("Sennheiser HD 599 SE - Koptelefoon - Zwart", Scraper.parse(noAddress).name)
    }
}
