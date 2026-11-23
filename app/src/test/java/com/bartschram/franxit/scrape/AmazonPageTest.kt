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
}
