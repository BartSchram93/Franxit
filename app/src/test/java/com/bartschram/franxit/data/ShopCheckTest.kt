package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Scraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopCheckTest {

    private val zelda = "Nintendo Switch 2 The Legend of Zelda 40th Anniversary Edition"

    @Test
    fun aShopsOwnPageCorrectsGoogleShopping() {
        // Google: € 549 at Coolblue. Coolblue's page: € 539, sold out.
        val page = Scraper.Result("Nintendo Switch 2 The Legend of Zelda 40th Anniversary Edition", listOf(Scraper.Offer("Coolblue", 539.0, inStock = false)))
        val seen = ShopCheck.seen(page, zelda)
        assertEquals(ShopCheck.Seen(539.0, false), seen)
        assertEquals(ShopOffer("Coolblue.be", 539.0, false), ShopCheck.apply(ShopOffer("Coolblue.be", 549.0, null), seen))
        // A sold-out shop no longer sets the price: the next one does.
        val offers = listOf(ShopCheck.apply(ShopOffer("Coolblue.be", 549.0, null), seen), ShopOffer("Fnac.be", 719.0, null))
        assertEquals("Fnac.be", Offers.cheapest(offers)!!.seller)
    }

    @Test
    fun aPageOfAnotherProductOrPriceChangesNothing() {
        val bundle = Scraper.Result("Nintendo Switch 2 (2026) + Pokémon Legends: Z-A", listOf(Scraper.Offer("Coolblue", 514.0, inStock = true)))
        assertNull(ShopCheck.seen(bundle, zelda))
        // Far from the listed price (an accessory's, another version's): only the stock counts.
        assertEquals(ShopOffer("Fnac.be", 719.0, true), ShopCheck.apply(ShopOffer("Fnac.be", 719.0, null), ShopCheck.Seen(49.99, true)))
        assertEquals(ShopOffer("Fnac.be", 719.0, false), ShopCheck.apply(ShopOffer("Fnac.be", 719.0, null), ShopCheck.SOLD_OUT))
        assertEquals(ShopOffer("Fnac.be", 719.0, null), ShopCheck.apply(ShopOffer("Fnac.be", 719.0, null), null))
    }

    @Test
    fun onlyAShopsProductPageIsRead() {
        assertTrue(ShopCheck.isProductPage("https://www.coolblue.be/nl/product/971234/nintendo-switch-2-zelda.html"))
        assertFalse(ShopCheck.isProductPage("https://duckduckgo.com/?q=%5CZelda+site%3Afnac.be"))
        assertFalse(ShopCheck.isProductPage("https://www.google.be/search?q=Zelda+nintendo"))
        assertFalse(ShopCheck.isProductPage(null))
    }
}
