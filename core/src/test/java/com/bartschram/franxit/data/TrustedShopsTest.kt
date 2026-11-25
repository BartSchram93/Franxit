package com.bartschram.franxit.data

import com.bartschram.franxit.scrape.GoogleShopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedShopsTest {

    @Test
    fun namesFromBothSourcesMeetInOneKey() {
        assertEquals("mediamarkt", TrustedShops.key("MediaMarkt.be"))
        assertEquals("mediamarkt", TrustedShops.key("www.mediamarkt.be"))
        assertEquals("bol", TrustedShops.key("Bol."))
        assertEquals("bol", TrustedShops.key("bol.com"))
        assertEquals("krefel", TrustedShops.key("Krëfel"))
        assertEquals("amazon", TrustedShops.key("Amazon.com.be"))
        assertEquals("vandenborre", TrustedShops.key("Vanden Borre"))
    }

    @Test
    fun knownAndLearnedShopsOnly() {
        assertTrue(TrustedShops.isTrusted("Coolblue.be", emptySet()))
        assertTrue(TrustedShops.isTrusted("Bol.com", emptySet()))
        assertFalse(TrustedShops.isTrusted("Garantie2ans.com", emptySet()))
        assertFalse(TrustedShops.isTrusted("Movertix", emptySet()))
        // Listed on vergelijk.be, so learned.
        assertTrue(TrustedShops.isTrusted("Movertix.be", setOf(TrustedShops.key("Movertix"))))
    }

    @Test
    fun googlesExtraWordsDontHideAKnownShop() {
        // Real names from a Google Shopping search.
        assertEquals("Bax-shop.be", TrustedShops.name("Bax Music BEFR", emptySet()))
        assertEquals("Amazon.com.be", TrustedShops.name("Amazon.com.be - Seller", emptySet()))
        assertEquals("Fnac", TrustedShops.name("Fnac", emptySet()))
        assertEquals("bol.com", TrustedShops.name("bol.com", emptySet()))
        // A different shop that only starts like a known one.
        assertEquals(null, TrustedShops.name("Bolsas Shop", emptySet()))
        assertEquals(null, TrustedShops.name("Zurusten DE", emptySet()))
        assertTrue(TrustedShops.same("Bax Music BEFR", "Bax-shop.be"))
        assertFalse(TrustedShops.same("Bax Music BEFR", "Gear4music.be"))
    }

    @Test
    fun unknownShopsAreLeftOutOfGoogleShopping() {
        val json = """
            {"shopping_results": [
              {"title": "Apple AirPods Pro 3", "source": "Garantie2ans.com", "extracted_price": 149.0},
              {"title": "Apple AirPods Pro 3", "source": "Bol.com", "extracted_price": 213.0},
              {"title": "Apple AirPods Pro 3", "source": "Coolblue.be", "extracted_price": 219.0},
              {"title": "Apple AirPods Pro 3", "source": "MediaMarkt.be", "extracted_price": 229.0}
            ]}
        """.trimIndent()
        val shops = GoogleShopping.parse(json, "Apple AirPods Pro 3") { TrustedShops.name(it, emptySet()) }
            .result.offers.map { it.seller }
        assertEquals(listOf("Bol.com", "Coolblue.be", "MediaMarkt.be"), shops)
    }

    @Test
    fun aDutchMusicShopKnownByEveryPhone() {
        // Found on Google Shopping on one phone, left out on another that hadn't learned it from vergelijk.be.
        assertEquals("Gooise-Gitaren.nl", TrustedShops.name("Gooise-Gitaren.nl", emptySet()))
        assertEquals("Gooise Gitaren", TrustedShops.name("Gooise Gitaren", emptySet()))
    }
}
