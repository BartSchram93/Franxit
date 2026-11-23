package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonPageTest {

    private val black = """
        <span id="productTitle" class="a-size-large">  Canon SELPHY CP1500 Black  </span>
        <div id="corePrice_feature_div"><span class="a-price"><span class="a-offscreen">€119,00</span></span></div>
        <div id="availability" class="a-section"><span class="a-color-success">In stock</span></div>
        <li>Pink: <span>Currently unavailable</span></li>
    """

    @Test
    fun thePriceAndStockOfTheProductShown() {
        val page = AmazonPage.parse(black)!!
        assertEquals("Canon SELPHY CP1500 Black", page.name)
        assertEquals(119.0, page.offers.single().price, 0.001)
        // In stock: the pink one's "Currently unavailable" elsewhere on the page says nothing of it.
        assertEquals(true, page.offers.single().inStock)
        assertFalse(AmazonPage.isSoldOut(black))
    }

    @Test
    fun otherPlacesForThePrice() {
        assertEquals(119.0, AmazonPage.parse("""<input type="hidden" id="twister-plus-price-data-price" value="119.0" />""")!!.offers.single().price, 0.001)
        assertEquals(113.99, AmazonPage.parse(""""priceAmount":113.99,"currencySymbol":"€"""")!!.offers.single().price, 0.001)
        assertNull(AmazonPage.parse("<html>Type the characters you see in this image</html>"))
    }

    @Test
    fun soldOutWhenTheProductsOwnBoxSaysSo() {
        assertTrue(AmazonPage.isSoldOut("""<div id="availability"><span>Momenteel niet verkrijgbaar.</span></div>"""))
        assertEquals(1119.0, AmazonPage.euros("EUR 1.119,00")!!, 0.001)
        assertEquals(119.0, AmazonPage.euros("119,00 €")!!, 0.001)
    }

    @Test
    fun thePhoneLayoutsPrice() {
        // A phone's page: the price in whole and fraction, in the phone's price block.
        val html = """<div id="corePriceDisplay_mobile_feature_div"><span class="a-price"><span class="a-price-symbol">€</span><span class="a-price-whole">360<span class="a-price-decimal">,</span></span><span class="a-price-fraction">00</span></span></div>"""
        assertEquals(360.0, AmazonPage.parse(html)!!.offers.single().price, 0.001)
        val big = """<div id="apex_mobile"><span class="a-price-whole">1.119<span class="a-price-decimal">,</span></span><span class="a-price-fraction">99</span></div>"""
        assertEquals(1119.99, AmazonPage.parse(big)!!.offers.single().price, 0.001)
    }

    @Test
    fun withoutABuyBoxTheOtherSellersCheapest() {
        // The TCL NXTPAPER 14: no buy box, "8 options from €415,06"; vergelijk.be still said € 360.
        val box = """<div id="buybox-see-all-buying-choices"><a>8 options from <span class="a-price"><span class="a-offscreen">€415,06</span></span></a></div>"""
        assertEquals(415.06, AmazonPage.parse(box)!!.offers.single().price, 0.001)
        val text = """<a class="a-button-text">8 options from €<span>415</span><sup>06</sup></a>"""
        assertEquals(415.06, AmazonPage.parse(text)!!.offers.single().price, 0.001)
        val nl = """<span>8 opties vanaf € 1.015,50</span>"""
        assertEquals(1015.5, AmazonPage.parse(nl)!!.offers.single().price, 0.001)
    }
}
