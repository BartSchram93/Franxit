package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopsTest {

    @Test
    fun namesShopsByTheirWebsite() {
        assertEquals("Bol.com", Shops.name("Bol."))
        assertEquals("Bol.com", Shops.name("Bol Plaza"))
        assertEquals("Bol.com partner", Shops.name("Bol Plaza", partners = true))
        assertEquals("Fnac.be", Shops.name("Fnac Marketplace"))
        assertEquals("Cameranu.be", Shops.name("Cameranu"))
        assertEquals("Amazon.com.be", Shops.name("Amazon"))
        assertEquals("MediaMarkt.be", Shops.name("MediaMarkt"))
    }

    @Test
    fun keepsWebsitesWithACapital() {
        assertEquals("Coolblue.be", Shops.name("coolblue.be"))
        assertEquals("Coolblue.be", Shops.name("www.coolblue.be"))
        assertEquals("Techinn.com", Shops.name("Techinn.com"))
        assertEquals("VandenBorre.be", Shops.name("VandenBorre.be"))
    }

    @Test
    fun leavesUnknownNamesAlone() {
        assertEquals("Some Shop", Shops.name("Some Shop"))
    }

    @Test
    fun ignoresAccents() {
        assertEquals("Krefel.be", Shops.name("Krëfel"))
    }

    @Test
    fun aShopInSmallLettersGetsACapital() {
        assertEquals("Nintendo", Shops.name("nintendo"))
        assertEquals("eBay", Shops.name("eBay"))
    }

    @Test
    fun amazonBeIsAmazonComBe() {
        assertEquals("Amazon.com.be", Shops.name("Amazon.be"))
        assertEquals("Amazon.com.be", Shops.name("amazon.be"))
        assertTrue(TrustedShops.same("Amazon.be", "Amazon.com.be"))
    }

    @Test
    fun aShopsDutchSiteIsItsBelgianOneWhenItHasOne() {
        // vergelijk.be lists Dyson.be and VandenBorre.be: their Dutch names are shown and read as those.
        assertEquals("Dyson.be", Shops.name("Dyson.nl"))
        assertEquals("dyson.be", com.bartschram.franxit.scrape.GoogleShopping.siteOf("Dyson.nl"))
        assertEquals("coolblue.be", com.bartschram.franxit.scrape.GoogleShopping.siteOf("Coolblue.nl"))
        assertEquals("Coolblue.be", Shops.name("Coolblue.nl"))
        // A Dutch shop without a Belgian site stays Dutch.
        assertEquals("Kabelshop.nl", Shops.name("Kabelshop.nl"))
        assertEquals("kabelshop.nl", com.bartschram.franxit.scrape.GoogleShopping.siteOf("Kabelshop.nl"))
    }
}
