package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.Scraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which page the Buy button opens, with the cases that went wrong. */
class BuyLinkTest {

    private val bolLink = "https://www.vergelijk.be/clickout/register?hash=bol"
    private val gladiLink = "https://www.vergelijk.be/clickout/register?hash=gladi"

    @Test
    fun gladiTheButtonsShopNotVergelijksCheapest() {
        // Bol and Gladi.nl both at € 38,00; the button says "Buy at Gladi.nl".
        val page = Scraper.Result("Gladi Tiles", listOf(Scraper.Offer("Bol.", 38.0, link = bolLink), Scraper.Offer("Gladi.nl", 38.0, link = gladiLink)))
        assertEquals(gladiLink, BuyLink.fromPage(page, "Gladi.nl") { null })
    }

    @Test
    fun aShopVergelijkDoesntListGoesThroughShoparizeNotToAnotherShop() {
        val page = Scraper.Result("Gladi Tiles", listOf(Scraper.Offer("Bol.", 38.0, link = bolLink)))
        assertEquals("https://www.gladi.nl/tiles", BuyLink.fromPage(page, "Gladi.nl") { "https://www.gladi.nl/tiles" })
        assertNull(BuyLink.fromPage(page, "Gladi.nl") { null })
    }

    @Test
    fun aListedShopWithoutALinkGoesThroughShoparize() {
        val page = Scraper.Result("X", listOf(Scraper.Offer("Pazzox.be", 5.4)))
        assertEquals("https://www.pazzox.be/p", BuyLink.fromPage(page, "pazzox.be") { "https://www.pazzox.be/p" })
    }

    @Test
    fun withoutAShopNamedThePagesCheapest() {
        val page = Scraper.Result("X", listOf(Scraper.Offer("Bol.", 40.0, link = bolLink), Scraper.Offer("Gladi.nl", 38.0, link = gladiLink)))
        assertEquals(gladiLink, BuyLink.fromPage(page, null) { null })
    }

    @Test
    fun theButtonsShopIsTheOneAskedForElseTheAppsCheapest() {
        val offers = listOf(ShopOffer("Gladi.nl", 38.0), ShopOffer("Bol.com", 39.0))
        assertEquals("Bol.com", BuyLink.shop("Bol.com", offers))
        assertEquals("Gladi.nl", BuyLink.shop(null, offers))
    }

    @Test
    fun aShopPageYouAddedOpensForItsOwnShopOnly() {
        val url = "https://www.fitshop.be/nl/loopband-x"
        assertEquals(url, BuyLink.ownPage(url, "Fitshop.be"))
        assertEquals(url, BuyLink.ownPage(url, null))
        assertNull(BuyLink.ownPage(url, "Bol.com"))
    }

    @Test
    fun throughShoparizeTheCleanProductPageElseTheShopsOwnSearch() {
        val listed = listOf(ShopOffer("Pazzox.be", 111.2), ShopOffer("Farmaline.be", 123.99))
        val clean = "https://www.pazzox.be/nl/apotheek/bestellen/prox-caps-180/"
        assertEquals(clean, BuyLink.fromShoparize(listed, "Pazzox.be", clean, "Primrose Prox 180 Capsules"))
        assertEquals(
            "https://www.google.be/search?q=Primrose+Prox+180+Capsules+site%3Afarmaline.be",
            BuyLink.fromShoparize(listed, "Farmaline.be", null, "Primrose Prox 180 Capsules"),
        )
        // A shop Shoparize didn't list.
        assertNull(BuyLink.fromShoparize(listed, "Bol.com", null, "Primrose Prox 180 Capsules"))
    }

    @Test
    fun aShopWithoutAKnownPageGetsASearchOnItsSiteNeverAnotherShop() {
        // "Buy at Bol.com" on a product added from Multibazar opened Multibazar's page.
        val multibazar = "https://www.multibazar.be/gladi-magnetische-tegels-100"
        assertNull(BuyLink.ownPage(multibazar, "Bol.com"))
        assertEquals(
            "https://www.google.be/search?q=Gladi+magnetische+tegels+100+stuks+site%3Abol.com",
            BuyLink.orSearch(null, "Bol.com", "Gladi magnetische tegels 100 stuks"),
        )
        assertEquals(multibazar, BuyLink.orSearch(multibazar, "Multibazar.be", "Gladi"))
        assertNull(BuyLink.orSearch(null, null, "Gladi"))
    }

    @Test
    fun aShopsSiteIsSearchedWithShoparizesFullerTitle() {
        // "Patchwork site:bol.com" found poufs in patchwork fabric.
        assertEquals("999 Games Patchwork", BuyLink.searchName("Patchwork", "999 Games Patchwork"))
        // A title of another product, or one saying no more, is not used.
        assertEquals("Patchwork", BuyLink.searchName("Patchwork", "Carcassonne"))
        assertEquals("Patchwork", BuyLink.searchName("Patchwork", "patchwork"))
        assertEquals("Patchwork", BuyLink.searchName("Patchwork", null))
    }
}
