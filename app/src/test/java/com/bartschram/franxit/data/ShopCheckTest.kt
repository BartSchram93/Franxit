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
        val offers = listOfNotNull(ShopCheck.apply(ShopOffer("Coolblue.be", 549.0, null), seen), ShopOffer("Fnac.be", 719.0, null))
        assertEquals("Fnac.be", Offers.cheapest(offers)!!.seller)
    }

    @Test
    fun aPageOfAnotherProductOrPriceChangesNothing() {
        val bundle = Scraper.Result("Nintendo Switch 2 (2026) + Pokémon Legends: Z-A", listOf(Scraper.Offer("Coolblue", 514.0, inStock = true)))
        // A page of another product: the shop is left out.
        assertEquals(ShopCheck.ELSEWHERE, ShopCheck.seen(bundle, zelda))
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

    @Test
    fun aLinkThroughGoogleIsReadAtTheShop() {
        assertEquals(
            "https://www.coolblue.be/nl/product/971234/zelda.html",
            ShopCheck.direct("https://www.google.com/url?sa=t&q=https%3A%2F%2Fwww.coolblue.be%2Fnl%2Fproduct%2F971234%2Fzelda.html&ved=x"),
        )
        assertEquals("https://www.fnac.be/a123", ShopCheck.direct("https://www.fnac.be/a123"))
        // Without the shop's address in it, it stays (and isn't read).
        val aclk = "https://www.google.com/aclk?sa=l&ai=abc"
        assertEquals(aclk, ShopCheck.direct(aclk))
        assertFalse(ShopCheck.isProductPage(ShopCheck.direct(aclk)))
    }

    @Test
    fun aShopsPageOfAnotherColourLeavesTheShopOut() {
        // Galaxus's link for the black Selphy CP1500 opened the pink one, at € 145,18.
        val pink = Scraper.Result("Canon Selphy CP1500 Roze", listOf(Scraper.Offer("Galaxus", 145.18, inStock = true)))
        val seen = ShopCheck.seen(pink, "Canon Selphy CP1500 Zwart")
        assertTrue(seen!!.otherColour)
        assertNull(ShopCheck.apply(ShopOffer("Galaxus.be", 119.0, null), seen))
        // The same colour, or a page that names none: as before.
        val black = Scraper.Result("Canon SELPHY CP1500 zwart", listOf(Scraper.Offer("Galaxus", 118.0, inStock = true)))
        assertEquals(ShopOffer("Galaxus.be", 118.0, true), ShopCheck.apply(ShopOffer("Galaxus.be", 119.0, null), ShopCheck.seen(black, "Canon Selphy CP1500 Zwart")))
    }

    @Test
    fun aShopWhoseLinkIsNoPageOfTheProductIsLeftOut() {
        val bench = "Hercules Stands KB-200B keyboardbank"
        // Fnac's link for the keyboard bench: a list of books.
        assertTrue(ShopCheck.isElsewhere("https://fr.fnac.be/se77634/La-Legende-des-Quatres", bench))
        assertTrue(ShopCheck.isElsewhere("https://www.coolblue.be/nl/zoeken?query=hercules", bench))
        assertTrue(ShopCheck.isElsewhere("https://www.bol.com/be/nl/s/?searchtext=hercules", bench))
        // Product pages stay.
        assertFalse(ShopCheck.isElsewhere("https://www.bax-shop.be/keyboardbanken/hercules-kb200b-keyboardbank", bench))
        assertFalse(ShopCheck.isElsewhere("https://www.fnac.be/a12345/Hercules-KB200B-Keyboardbank", bench))
        assertFalse(ShopCheck.isElsewhere("https://www.bol.com/be/nl/p/hercules-hckb-200b/9300000012345/", bench))
        assertFalse(ShopCheck.isElsewhere("https://www.kamera-express.be/product/12422729/canon-selphy-cp1500-black", "Canon Selphy CP1500 Zwart"))
        // A page of another product leaves the shop out too.
        assertNull(ShopCheck.apply(ShopOffer("Fnac.be", 135.0, null), ShopCheck.ELSEWHERE))
        val book = Scraper.Result("La Légende des Quatres - Le clan des loups", listOf(Scraper.Offer("Fnac", 15.9)))
        assertEquals(ShopCheck.ELSEWHERE, ShopCheck.seen(book, bench))
    }

    @Test
    fun howAShopWasCheckedIsSaid() {
        assertEquals(ShopCheck.Status.CHECKED, ShopCheck.status(ShopCheck.Seen(539.0, false), null))
        assertEquals(ShopCheck.Status.BLOCKED, ShopCheck.status(null, "Coolblue.be only allows browsers"))
        assertEquals(ShopCheck.Status.UNREADABLE, ShopCheck.status(null, "Coolblue.be's page shows no price the app can read"))
    }

    @Test
    fun aBundleOrAnotherStoresPageSaysNothingOfTheProduct() {
        val zelda = "Nintendo Switch 2 The Legend of Zelda 40th Anniversary Edition"
        // Coolblue's link was a bundle with a case: € 579, in stock.
        val bundle = "https://www.coolblue.be/nl/product/988615/nintendo-switch-2-2026-the-legend-of-zelda-ocarina-of-time-bluebuilt-beschermhoes.html"
        assertTrue(ShopCheck.isElsewhere(bundle, zelda))
        val page = Scraper.Result("Nintendo Switch 2 (2026) + The Legend of Zelda: Ocarina of Time + BlueBuilt Beschermhoes", listOf(Scraper.Offer("Coolblue", 579.0, inStock = true)))
        assertEquals(ShopCheck.ELSEWHERE, ShopCheck.seen(page, zelda))
        // Amazon.com.be's price read on amazon.com (the US store, not sold there): not its page.
        assertFalse(ShopCheck.isShopsLink("https://www.amazon.com/Canon-SELPHY-CP1500-Compact-Printer/dp/B0BF6T86WD", "Amazon.com.be"))
        assertTrue(ShopCheck.isShopsLink("https://www.amazon.com.be/dp/B0BF6T86WD", "Amazon.com.be"))
        assertTrue(ShopCheck.isShopsLink("https://fr.fnac.be/a123", "Fnac.be"))
        assertTrue(ShopCheck.isShopsLink("https://store.nintendo.com/nl-be/zelda", "Nintendo"))
        assertTrue(ShopCheck.isShopsLink("https://www.kamera-express.be/product/1", "Kamera Express"))
    }
}
